package com.trendpilot.core.execution

import com.trendpilot.core.filters.FilterMath
import com.trendpilot.core.filters.FilterValidator
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import com.trendpilot.core.risk.RiskDecision
import com.trendpilot.core.strategy.ExitReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.MathContext
import java.util.UUID

data class ExecutionConfig(
    val stopPlacementAttempts: Int = 3,
    val retryDelayMs: Long = 1_500,
    val unknownQueryAttempts: Int = 6,
    val unknownQueryDelayMs: Long = 2_000,
    /** If protection cannot be confirmed, sell the unprotected position at market. */
    val emergencyExitIfUnprotected: Boolean = true,
    val feeRate: Double = 0.001,
    val stopLimitOffset: Double = 0.015,
    /** Assets the reconciliation ignores (e.g. BNB kept for fees). */
    val ignoredAssets: Set<String> = setOf("BNB"),
    val dustQuoteThreshold: BigDecimal = BigDecimal("1"),
)

/** Hooks into the guard flags owned by the engine. */
interface GuardControl {
    suspend fun raiseEmergency(reason: String)
    suspend fun markReconciliationDirty(reason: String)
}

sealed interface ExecutionOutcome {
    data class Done(val message: String, val position: Position? = null) : ExecutionOutcome
    data class Failed(val message: String) : ExecutionOutcome
}

data class ReconciliationReport(val discrepancies: List<String>, val actions: List<String>) {
    val clean: Boolean get() = discrepancies.isEmpty()
}

/**
 * The ONLY component that sends orders. A single mutex serialises every order of the account,
 * so two decisions can never race on the same symbol or balance.
 *
 * Invariants:
 *  - an intent is persisted before sending; its clientOrderId is derived from the intent id;
 *  - an order with unknown outcome is queried, never blindly re-sent;
 *  - a position is either protected by a confirmed exchange-side stop, being protected, or
 *    in emergency handling (which blocks new entries);
 *  - protection is cancelled only immediately before selling the same quantity.
 */
class ExecutionCoordinator(
    private val gateway: ExchangeGateway,
    private val intents: IntentStore,
    private val positions: PositionStore,
    private val events: EventSink,
    private val guards: GuardControl,
    private val rulesProvider: suspend (String) -> SymbolRules,
    private val cfg: ExecutionConfig = ExecutionConfig(),
    private val clock: Clock = SystemClock,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(20) },
) {
    private val mutex = Mutex()
    private val mc = MathContext.DECIMAL64
    private val mode get() = gateway.mode
    private val quote get() = gateway.quoteAsset

    // ------------------------------------------------------------------ entries

    suspend fun openPosition(decision: RiskDecision.Approved, setup: String, reasons: String): ExecutionOutcome = mutex.withLock {
        val rules = rulesProvider(decision.symbol)
        val positionId = newId()
        val entry = newIntent(IntentKind.ENTRY, decision.symbol, Side.BUY, OrderType.MARKET, decision.quantity, positionId,
            // The planned stop travels with the intent so a crash right after the fill can still protect it.
            price = decision.stopLimitPrice, stopPrice = decision.stopPrice, note = setup)
        val order = NewOrder(decision.symbol, Side.BUY, OrderType.MARKET, decision.quantity, entry.clientOrderId)
        val result = submit(entry, order)
        val filled = when (result) {
            is Submit.Placed -> result.order
            is Submit.Refused -> return@withLock fail(decision.symbol, "Compra rechazada: ${result.reason}")
            Submit.NotPlaced -> return@withLock fail(decision.symbol, "Compra no colocada (confirmado)")
            Submit.StillUnknown -> {
                guards.markReconciliationDirty("compra ${entry.clientOrderId} con estado desconocido")
                return@withLock fail(decision.symbol, "Compra en estado desconocido: se reconciliará")
            }
        }
        if (filled.executedQty.signum() <= 0) return@withLock fail(decision.symbol, "Compra sin ejecución (${filled.status})")
        val position = buildPositionFromFill(positionId, rules, filled, decision.stopPrice, decision.stopLimitPrice, decision.stopType, setup, reasons)
        positions.save(position)
        emit(EventLevel.TRADE, "Compra ${rules.symbol}", "Cantidad ${filled.executedQty} a ${position.entryPrice.toPlainString()} (costo ${position.costBasisQuote.setScale(2, java.math.RoundingMode.HALF_UP)} $quote). $reasons", rules.symbol)
        val protectedPos = protect(position, rules, listOf(decision.stopPrice to decision.stopLimitPrice))
        ExecutionOutcome.Done("Posición abierta en ${rules.symbol}", protectedPos)
    }

    private suspend fun buildPositionFromFill(
        positionId: String, rules: SymbolRules, filled: ExchangeOrder, stop: BigDecimal, stopLimit: BigDecimal?,
        stopType: OrderType, setup: String, reasons: String,
    ): Position {
        val executed = filled.executedQty
        val avg = filled.cumulativeQuoteQty.divide(executed, mc)
        val baseCommission = filled.fills.filter { it.commissionAsset == rules.baseAsset }.fold(BigDecimal.ZERO) { a, f -> a + f.commission }
        val feesQuote = feesInQuote(filled, rules, avg)
        val free = runCatching { gateway.balances().firstOrNull { it.asset == rules.baseAsset }?.free }.getOrNull()
        val net = executed - baseCommission
        val usable = if (free != null && free < net) free else net
        val protectedQty = FilterMath.floorToStep(usable, rules.lotSize?.stepSize ?: BigDecimal.ZERO)
        return Position(
            id = positionId, mode = mode, symbol = rules.symbol, baseAsset = rules.baseAsset,
            quantity = protectedQty, entryPrice = avg, entryFeesQuote = feesQuote,
            costBasisQuote = filled.cumulativeQuoteQty + (if (baseCommission.signum() > 0) BigDecimal.ZERO else feesQuote),
            initialStop = stop, currentStop = stop, stopType = stopType, stopLimitPrice = stopLimit,
            stopClientOrderId = null, status = PositionStatus.UNPROTECTED, openedAtMs = clock.nowMs(),
            highestPrice = avg, setup = setup, entryReasons = reasons, dustQty = (usable - protectedQty).max(BigDecimal.ZERO),
        )
    }

    // ------------------------------------------------------------------ protection

    /**
     * Places and confirms the protective stop. [candidates] are tried in order (e.g. new stop,
     * then previous stop). If nothing can be confirmed the emergency protocol starts.
     */
    private suspend fun protect(position: Position, rules: SymbolRules, candidates: List<Pair<BigDecimal, BigDecimal?>>): Position {
        var pos = position.copy(status = PositionStatus.UNPROTECTED, stopClientOrderId = null)
        positions.save(pos)
        val type = rules.protectiveStopType()
        if (type == null) {
            return emergency(pos, rules, "${rules.symbol} no admite órdenes stop")
        }
        for ((stop, limitIn) in candidates) {
            val limit = if (type == OrderType.STOP_LOSS_LIMIT) limitIn ?: FilterMath.floorToStep(stop * BigDecimal.valueOf(1 - cfg.stopLimitOffset), rules.priceFilter?.tickSize ?: BigDecimal.ZERO) else null
            val violations = FilterValidator.validate(rules, type, Side.SELL, pos.quantity, limit, stop, stop)
            if (violations.isNotEmpty()) {
                emit(EventLevel.SECURITY, "Stop inválido ${rules.symbol}", violations.joinToString { "${it.filter}: ${it.detail}" }, rules.symbol)
                continue
            }
            repeat(cfg.stopPlacementAttempts) { attempt ->
                val intent = newIntent(IntentKind.PROTECTIVE_STOP, rules.symbol, Side.SELL, type, pos.quantity, pos.id, limit, stop)
                val order = NewOrder(rules.symbol, Side.SELL, type, pos.quantity, intent.clientOrderId, limit, stop, if (limit != null) "GTC" else null)
                when (val r = submit(intent, order)) {
                    is Submit.Placed -> {
                        val confirmed = runCatching { gateway.queryOrder(rules.symbol, intent.clientOrderId) }.getOrNull() ?: r.order
                        when {
                            OrderStatus.isWorking(confirmed.status) -> {
                                pos = pos.copy(status = PositionStatus.PROTECTED, stopClientOrderId = intent.clientOrderId, currentStop = stop, stopLimitPrice = limit, stopType = type)
                                positions.save(pos)
                                emit(EventLevel.STOP, "Stop confirmado ${rules.symbol}", "Stop ${stop.toPlainString()} (${type.name}) para ${pos.quantity} confirmado en el exchange", rules.symbol)
                                return pos
                            }
                            confirmed.status == OrderStatus.FILLED -> return finalizeClose(pos, confirmed, ExitReason.STOP_HIT, rules)
                            else -> emit(EventLevel.WARNING, "Stop no activo ${rules.symbol}", "Estado ${confirmed.status}", rules.symbol)
                        }
                    }
                    is Submit.Refused -> {
                        if (r.reason.contains("trigger immediately", ignoreCase = true)) {
                            emit(EventLevel.SECURITY, "Precio bajo el stop ${rules.symbol}", "El stop se activaría al instante: salida a mercado", rules.symbol)
                            return marketSell(pos, rules, ExitReason.STOP_HIT)
                        }
                        emit(EventLevel.WARNING, "Stop rechazado ${rules.symbol}", "Intento ${attempt + 1}: ${r.reason}", rules.symbol)
                    }
                    Submit.NotPlaced -> Unit
                    Submit.StillUnknown -> guards.markReconciliationDirty("stop ${intent.clientOrderId} con estado desconocido")
                }
                delay(cfg.retryDelayMs)
            }
        }
        return emergency(pos, rules, "no se pudo confirmar el stop de ${rules.symbol}")
    }

    private suspend fun emergency(pos: Position, rules: SymbolRules, reason: String): Position {
        guards.raiseEmergency(reason)
        emit(EventLevel.EMERGENCY, "EMERGENCIA ${rules.symbol}", "$reason. Compras bloqueadas." + if (cfg.emergencyExitIfUnprotected) " Intentando cerrar la posición sin protección." else "", rules.symbol)
        return if (cfg.emergencyExitIfUnprotected) marketSell(pos, rules, ExitReason.EMERGENCY, reprotectOnFailure = false) else pos
    }

    // ------------------------------------------------------------------ stop updates

    /** Raises the stop. Never lowers it. Uses cancelReplace when the symbol allows it. */
    suspend fun raiseStop(positionId: String, requestedStop: BigDecimal, why: String): ExecutionOutcome = mutex.withLock {
        val pos = positions.get(positionId) ?: return@withLock ExecutionOutcome.Failed("posición inexistente")
        if (pos.status != PositionStatus.PROTECTED) return@withLock ExecutionOutcome.Failed("posición no protegida: primero reconciliar")
        val rules = rulesProvider(pos.symbol)
        val tick = rules.priceFilter?.tickSize ?: BigDecimal.ZERO
        val newStop = FilterMath.floorToStep(requestedStop, tick)
        if (newStop <= pos.currentStop) return@withLock ExecutionOutcome.Failed("el nuevo stop no supera al actual")
        val last = gateway.lastPrice(pos.symbol).price
        if (newStop >= last) return@withLock ExecutionOutcome.Failed("el nuevo stop no está bajo el precio actual")
        val type = pos.stopType
        val newLimit = if (type == OrderType.STOP_LOSS_LIMIT) FilterMath.floorToStep(newStop * BigDecimal.valueOf(1 - cfg.stopLimitOffset), tick) else null
        val oldCid = pos.stopClientOrderId ?: return@withLock ExecutionOutcome.Done("re-protegido", protect(pos, rules, listOf(newStop to newLimit)))
        val fallbacks = listOf(newStop to newLimit, pos.currentStop to pos.stopLimitPrice)

        if (rules.cancelReplaceAllowed) {
            val intent = newIntent(IntentKind.STOP_REPLACE, pos.symbol, Side.SELL, type, pos.quantity, pos.id, newLimit, newStop)
            intents.save(intent)
            val order = NewOrder(pos.symbol, Side.SELL, type, pos.quantity, intent.clientOrderId, newLimit, newStop, if (newLimit != null) "GTC" else null)
            val res = try {
                gateway.cancelReplace(pos.symbol, oldCid, order)
            } catch (e: GatewayException.Rejected) {
                CancelReplaceResult(cancelSucceeded = false, newOrder = null, newOrderAttempted = false, errorMessage = e.message)
            } catch (e: GatewayException) {
                // Unknown: inspect both orders.
                val newQ = resolve(intent)
                val oldQ = runCatching { gateway.queryOrder(pos.symbol, oldCid) }.getOrNull()
                CancelReplaceResult(oldQ == null || !OrderStatus.isWorking(oldQ.status), newQ, true, e.message)
            }
            val newWorking = res.newOrder?.let { OrderStatus.isWorking(it.status) || it.status == OrderStatus.PENDING_NEW } == true
            when {
                res.cancelSucceeded && newWorking -> {
                    intents.save(intent.copy(status = IntentStatus.WORKING, updatedAtMs = clock.nowMs(), exchangeOrderId = res.newOrder!!.orderId))
                    val updated = pos.copy(currentStop = newStop, stopLimitPrice = newLimit, stopClientOrderId = intent.clientOrderId)
                    positions.save(updated)
                    emit(EventLevel.STOP, "Stop elevado ${pos.symbol}", "${pos.currentStop.toPlainString()} → ${newStop.toPlainString()}. $why", pos.symbol)
                    return@withLock ExecutionOutcome.Done("stop elevado", updated)
                }
                !res.cancelSucceeded -> {
                    intents.save(intent.copy(status = IntentStatus.NOT_PLACED, updatedAtMs = clock.nowMs(), note = res.errorMessage ?: ""))
                    return@withLock handleOldStopAfterFailedCancel(pos, rules, oldCid, fallbacks)
                }
                else -> {
                    intents.save(intent.copy(status = IntentStatus.REJECTED, updatedAtMs = clock.nowMs(), note = res.errorMessage ?: ""))
                    emit(EventLevel.SECURITY, "Stop cancelado sin reemplazo ${pos.symbol}", "Reponiendo protección de inmediato", pos.symbol)
                    return@withLock ExecutionOutcome.Done("re-protegido tras fallo", protect(pos, rules, fallbacks))
                }
            }
        }

        // No cancelReplace: cancel then place (short unprotected window, handled explicitly).
        try {
            gateway.cancelOrder(pos.symbol, oldCid)
        } catch (e: GatewayException) {
            return@withLock handleOldStopAfterFailedCancel(pos, rules, oldCid, fallbacks)
        }
        val reprotected = protect(pos, rules, fallbacks)
        if (reprotected.status == PositionStatus.PROTECTED) {
            emit(EventLevel.STOP, "Stop elevado ${pos.symbol}", "${pos.currentStop.toPlainString()} → ${reprotected.currentStop.toPlainString()}. $why", pos.symbol)
        }
        ExecutionOutcome.Done("stop actualizado", reprotected)
    }

    private suspend fun handleOldStopAfterFailedCancel(
        pos: Position, rules: SymbolRules, oldCid: String, fallbacks: List<Pair<BigDecimal, BigDecimal?>>,
    ): ExecutionOutcome {
        val old = runCatching { gateway.queryOrder(pos.symbol, oldCid) }.getOrElse {
            guards.markReconciliationDirty("no se pudo consultar el stop $oldCid")
            return ExecutionOutcome.Failed("estado del stop desconocido; se reconciliará")
        }
        return when {
            old != null && OrderStatus.isWorking(old.status) -> ExecutionOutcome.Failed("cancelación rechazada; el stop anterior sigue activo")
            old != null && old.status == OrderStatus.FILLED -> ExecutionOutcome.Done("el stop ya se ejecutó", finalizeClose(pos, old, ExitReason.STOP_HIT, rules))
            else -> ExecutionOutcome.Done("re-protegido", protect(pos, rules, fallbacks))
        }
    }

    // ------------------------------------------------------------------ exits

    suspend fun closePosition(positionId: String, reason: ExitReason): ExecutionOutcome = mutex.withLock {
        val pos = positions.get(positionId) ?: return@withLock ExecutionOutcome.Failed("posición inexistente")
        if (pos.status == PositionStatus.CLOSED) return@withLock ExecutionOutcome.Failed("ya cerrada")
        val rules = rulesProvider(pos.symbol)
        val closed = marketSell(pos, rules, reason)
        if (closed.status == PositionStatus.CLOSED) ExecutionOutcome.Done("posición cerrada", closed)
        else ExecutionOutcome.Failed("no se pudo cerrar; protección ${if (closed.status == PositionStatus.PROTECTED) "mantenida" else "pendiente"}")
    }

    /**
     * Sells [fraction] of a position. Refused when either the sold part or the remaining part
     * would violate exchange minimums (that would leave untracked residual balances).
     */
    suspend fun partialExit(positionId: String, fraction: Double, reason: ExitReason): ExecutionOutcome = mutex.withLock {
        require(fraction > 0 && fraction < 1)
        val pos = positions.get(positionId) ?: return@withLock ExecutionOutcome.Failed("posición inexistente")
        if (pos.status != PositionStatus.PROTECTED) return@withLock ExecutionOutcome.Failed("posición no protegida")
        val rules = rulesProvider(pos.symbol)
        val step = rules.lotSize?.stepSize ?: BigDecimal.ZERO
        val remaining = FilterMath.floorToStep(pos.quantity * BigDecimal.valueOf(1 - fraction), step)
        val sell = pos.quantity - remaining
        val last = gateway.lastPrice(pos.symbol).price
        val stopType = pos.stopType
        val vSell = FilterValidator.validate(rules, OrderType.MARKET, Side.SELL, sell, null, null, last)
        val vRem = FilterValidator.validate(rules, stopType, Side.SELL, remaining, pos.stopLimitPrice, pos.currentStop, pos.currentStop)
        if (vSell.isNotEmpty() || vRem.isNotEmpty()) {
            return@withLock ExecutionOutcome.Failed("venta parcial inválida (dejaría saldo sin seguimiento): " + (vSell + vRem).joinToString { it.detail })
        }
        val oldCid = pos.stopClientOrderId!!
        // 1. Shrink protection to the remaining quantity first (frees balance for the sale).
        var shrunk = pos
        val reduced = if (rules.amendAllowed) {
            runCatching { gateway.amendQuantity(pos.symbol, oldCid, remaining) }.isSuccess
        } else false
        if (reduced) {
            shrunk = pos.copy(quantity = remaining)
        } else {
            try { gateway.cancelOrder(pos.symbol, oldCid) } catch (e: GatewayException) {
                return@withLock handleOldStopAfterFailedCancel(pos, rules, oldCid, listOf(pos.currentStop to pos.stopLimitPrice))
            }
            shrunk = protect(pos.copy(quantity = remaining), rules, listOf(pos.currentStop to pos.stopLimitPrice))
            if (shrunk.status != PositionStatus.PROTECTED) return@withLock ExecutionOutcome.Failed("no se pudo re-proteger el remanente")
        }
        positions.save(shrunk)
        // 2. Sell the freed part.
        val intent = newIntent(IntentKind.PARTIAL_EXIT, pos.symbol, Side.SELL, OrderType.MARKET, sell, pos.id)
        when (val r = submit(intent, NewOrder(pos.symbol, Side.SELL, OrderType.MARKET, sell, intent.clientOrderId))) {
            is Submit.Placed -> {
                val fees = feesInQuote(r.order, rules, last)
                val proceeds = r.order.cumulativeQuoteQty - fees
                val costShare = pos.costBasisQuote.multiply(sell).divide(pos.quantity, mc)
                val updated = shrunk.copy(
                    costBasisQuote = pos.costBasisQuote - costShare,
                    realizedQuote = pos.realizedQuote + proceeds - costShare,
                    realizedFeesQuote = pos.realizedFeesQuote + fees,
                )
                positions.save(updated)
                emit(EventLevel.TRADE, "Venta parcial ${pos.symbol}", "Vendido ${r.order.executedQty}; resultado ${(proceeds - costShare).setScale(2, java.math.RoundingMode.HALF_UP)} $quote. Motivo: $reason", pos.symbol)
                ExecutionOutcome.Done("venta parcial ejecutada", updated)
            }
            Submit.StillUnknown -> {
                guards.markReconciliationDirty("venta parcial de ${pos.symbol} con estado desconocido")
                ExecutionOutcome.Failed("venta parcial en estado desconocido; se reconciliará")
            }
            else -> {
                // The sale did not happen: protection must cover the full quantity again.
                val shrunkCid = shrunk.stopClientOrderId
                val cancelled = shrunkCid == null || runCatching { gateway.cancelOrder(pos.symbol, shrunkCid) }.isSuccess
                if (cancelled) protect(pos, rules, listOf(pos.currentStop to pos.stopLimitPrice))
                else guards.markReconciliationDirty("protección parcial en ${pos.symbol} tras venta fallida")
                ExecutionOutcome.Failed("venta parcial no ejecutada; protección restablecida sobre la cantidad completa")
            }
        }
    }

    /** Cancels this position's stop and sells at market. Re-protects if the sale fails. */
    private suspend fun marketSell(pos: Position, rules: SymbolRules, reason: ExitReason, reprotectOnFailure: Boolean = true): Position {
        var p = pos.copy(status = PositionStatus.CLOSING)
        positions.save(p)
        val stopCid = pos.stopClientOrderId
        if (stopCid != null) {
            try {
                gateway.cancelOrder(pos.symbol, stopCid)
            } catch (e: GatewayException) {
                val q = runCatching { gateway.queryOrder(pos.symbol, stopCid) }.getOrNull()
                when {
                    q != null && q.status == OrderStatus.FILLED -> return finalizeClose(pos, q, ExitReason.STOP_HIT, rules)
                    q != null && OrderStatus.isWorking(q.status) -> {
                        p = pos.copy(status = PositionStatus.PROTECTED)
                        positions.save(p)
                        emit(EventLevel.WARNING, "Venta no iniciada ${pos.symbol}", "No se pudo cancelar el stop; protección intacta", pos.symbol)
                        return p
                    }
                    else -> Unit // already cancelled/expired: proceed to sell
                }
            }
        }
        val free = runCatching { gateway.balances().firstOrNull { it.asset == rules.baseAsset }?.free }.getOrNull() ?: pos.quantity
        val step = rules.marketLotSize?.stepSize?.takeIf { it.signum() > 0 } ?: rules.lotSize?.stepSize ?: BigDecimal.ZERO
        val qty = FilterMath.floorToStep(free.min(pos.quantity + pos.dustQty), step)
        val intent = newIntent(IntentKind.EXIT, pos.symbol, Side.SELL, OrderType.MARKET, qty, pos.id, note = reason.name)
        val r = if (qty.signum() > 0) submit(intent, NewOrder(pos.symbol, Side.SELL, OrderType.MARKET, qty, intent.clientOrderId)) else Submit.Refused("cantidad nula")
        if (r is Submit.Placed && r.order.executedQty.signum() > 0) return finalizeClose(pos, r.order, reason, rules)
        if (r is Submit.StillUnknown) guards.markReconciliationDirty("venta ${intent.clientOrderId} con estado desconocido")
        emit(EventLevel.SECURITY, "Venta fallida ${pos.symbol}", "Motivo: ${(r as? Submit.Refused)?.reason ?: r.toString()}", pos.symbol)
        return if (reprotectOnFailure && r !is Submit.StillUnknown) protect(pos, rules, listOf(pos.currentStop to pos.stopLimitPrice)) else p
    }

    private suspend fun finalizeClose(pos: Position, exitOrder: ExchangeOrder, reason: ExitReason, rules: SymbolRules): Position {
        val avgExit = if (exitOrder.executedQty.signum() > 0) exitOrder.cumulativeQuoteQty.divide(exitOrder.executedQty, mc) else pos.currentStop
        val exitFees = feesInQuote(exitOrder, rules, avgExit)
        val proceeds = exitOrder.cumulativeQuoteQty - exitFees
        val pnl = pos.realizedQuote + proceeds - pos.costBasisQuote
        val totalFees = pos.entryFeesQuote + pos.realizedFeesQuote + exitFees
        val initialRisk = (pos.entryPrice - pos.initialStop) * pos.quantity
        val r = if (initialRisk.signum() > 0) pnl.divide(initialRisk, mc).toDouble() else 0.0
        val now = clock.nowMs()
        val closed = pos.copy(status = PositionStatus.CLOSED, closedAtMs = now, exitReason = reason, realizedQuote = pnl, realizedFeesQuote = totalFees - pos.entryFeesQuote)
        positions.save(closed)
        positions.recordTrade(ClosedTrade(pos.id, mode, pos.symbol, exitOrder.executedQty, pos.entryPrice, avgExit, totalFees, pnl, r, pos.openedAtMs, now, pos.setup, reason))
        emit(EventLevel.TRADE, "Venta ${pos.symbol}", "Salida ${avgExit.round(MathContext(8)).toPlainString()} por ${reasonText(reason)}. Resultado neto ${pnl.setScale(2, java.math.RoundingMode.HALF_UP)} $quote (${"%.2f".format(java.util.Locale.US, r)}R)", pos.symbol)
        return closed
    }

    // ------------------------------------------------------------------ reconciliation

    /** Must run at startup, after reconnecting and periodically. Repairs protection when possible. */
    suspend fun reconcile(): ReconciliationReport = mutex.withLock {
        val disc = mutableListOf<String>()
        val actions = mutableListOf<String>()

        for (intent in intents.unresolved(mode)) {
            val q = resolve(intent)
            if (q == null && intents.byClientOrderId(intent.clientOrderId)?.status == IntentStatus.UNKNOWN) {
                disc += "orden ${intent.clientOrderId} (${intent.kind}) sigue en estado desconocido"
                continue
            }
            if (intent.kind == IntentKind.ENTRY && q != null && q.executedQty.signum() > 0 && intent.positionId != null && positions.get(intent.positionId) == null) {
                val rules = rulesProvider(intent.symbol)
                val stop = intent.stopPrice ?: continue
                val pos = buildPositionFromFill(intent.positionId, rules, q, stop, intent.price, rules.protectiveStopType() ?: OrderType.STOP_LOSS_LIMIT, intent.note, "recuperada tras reinicio")
                positions.save(pos)
                actions += "posición ${intent.symbol} reconstruida desde la compra ${intent.clientOrderId}"
                protect(pos, rules, listOf(stop to intent.price))
            }
        }

        val balances = runCatching { gateway.balances() }.getOrElse {
            return@withLock ReconciliationReport(listOf("no se pudieron leer saldos: ${it.message}"), actions)
        }
        val tracked = mutableSetOf<String>()
        for (pos in positions.open(mode)) {
            val rules = rulesProvider(pos.symbol)
            tracked += pos.baseAsset
            val cid = pos.stopClientOrderId
            if (cid == null || pos.status == PositionStatus.UNPROTECTED || pos.status == PositionStatus.OPENING) {
                disc += "${pos.symbol} sin stop confirmado"
                actions += "re-protegiendo ${pos.symbol}"
                protect(pos, rules, listOf(pos.currentStop to pos.stopLimitPrice))
                continue
            }
            val q = runCatching { gateway.queryOrder(pos.symbol, cid) }.getOrElse {
                disc += "no se pudo consultar el stop de ${pos.symbol}"
                continue
            }
            when {
                q == null || q.status == OrderStatus.CANCELED || q.status == OrderStatus.EXPIRED || q.status == OrderStatus.REJECTED -> {
                    disc += "el stop de ${pos.symbol} no está activo (${q?.status ?: "inexistente"})"
                    actions += "re-protegiendo ${pos.symbol}"
                    protect(pos, rules, listOf(pos.currentStop to pos.stopLimitPrice))
                }
                q.status == OrderStatus.FILLED -> {
                    actions += "stop de ${pos.symbol} ejecutado mientras la app no supervisaba"
                    finalizeClose(pos, q, ExitReason.STOP_HIT, rules)
                }
                q.status == OrderStatus.PARTIALLY_FILLED -> disc += "stop de ${pos.symbol} parcialmente ejecutado"
                q.origQty.compareTo(pos.quantity) != 0 -> disc += "stop de ${pos.symbol} cubre ${q.origQty} pero la posición es ${pos.quantity}"
                else -> Unit
            }
            val held = balances.firstOrNull { it.asset == pos.baseAsset }?.total ?: BigDecimal.ZERO
            if (q != null && q.status != OrderStatus.FILLED && held < pos.quantity) {
                disc += "saldo de ${pos.baseAsset} ($held) menor que la posición (${pos.quantity})"
            }
        }

        val open = runCatching { gateway.openOrders() }.getOrElse { emptyList() }
        open.filter { !it.clientOrderId.startsWith(CID_PREFIX) }.forEach {
            disc += "orden externa en ${it.symbol} (${it.clientOrderId}): no creada por TrendPilot"
        }
        for (b in balances) {
            if (b.asset == quote || b.asset in tracked || b.asset in cfg.ignoredAssets || b.total.signum() == 0) continue
            val value = runCatching { gateway.lastPrice(b.asset + quote).price * b.total }.getOrNull() ?: continue
            if (value >= cfg.dustQuoteThreshold) disc += "saldo no gestionado: ${b.total} ${b.asset} (≈${value.setScale(2, java.math.RoundingMode.HALF_UP)} $quote)"
        }
        if (disc.isNotEmpty()) {
            emit(EventLevel.SECURITY, "Reconciliación con discrepancias", disc.joinToString("\n"))
        }
        ReconciliationReport(disc, actions)
    }

    /** Liquidates every open position (explicit user confirmation is enforced by the UI). */
    suspend fun liquidateAll(reason: ExitReason = ExitReason.MANUAL): List<ExecutionOutcome> {
        val ids = positions.open(mode).map { it.id }
        return ids.map { closePosition(it, reason) }
    }

    // ------------------------------------------------------------------ plumbing

    private sealed interface Submit {
        data class Placed(val order: ExchangeOrder) : Submit
        data class Refused(val reason: String) : Submit
        data object NotPlaced : Submit
        data object StillUnknown : Submit
    }

    private suspend fun submit(intent: OrderIntent, order: NewOrder): Submit {
        intents.save(intent)
        return try {
            val o = gateway.placeOrder(order)
            intents.save(intent.withOrder(o))
            Submit.Placed(o)
        } catch (e: GatewayException.Rejected) {
            intents.save(intent.copy(status = IntentStatus.REJECTED, updatedAtMs = clock.nowMs(), note = e.message ?: ""))
            Submit.Refused(e.message ?: "rechazada")
        } catch (e: GatewayException.RateLimited) {
            intents.save(intent.copy(status = IntentStatus.NOT_PLACED, updatedAtMs = clock.nowMs(), note = "rate limit"))
            Submit.Refused("límite de peticiones: reintentar en ${e.retryAfterMs / 1000}s")
        } catch (e: GatewayException.NotSent) {
            intents.save(intent.copy(status = IntentStatus.NOT_PLACED, updatedAtMs = clock.nowMs(), note = e.message ?: ""))
            Submit.NotPlaced
        } catch (e: GatewayException.Unknown) {
            intents.save(intent.copy(status = IntentStatus.UNKNOWN, updatedAtMs = clock.nowMs(), note = e.message ?: ""))
            emit(EventLevel.WARNING, "Estado de orden desconocido", "${intent.kind} ${intent.symbol}: consultando al exchange", intent.symbol)
            val q = resolve(intent)
            when {
                q != null -> Submit.Placed(q)
                intents.byClientOrderId(intent.clientOrderId)?.status == IntentStatus.NOT_PLACED -> Submit.NotPlaced
                else -> Submit.StillUnknown
            }
        }
    }

    /**
     * Resolves an intent whose outcome is unknown by querying its clientOrderId. Declares
     * NOT_PLACED only after the recvWindow has elapsed (the exchange rejects late requests).
     */
    private suspend fun resolve(intent: OrderIntent): ExchangeOrder? {
        repeat(cfg.unknownQueryAttempts) {
            try {
                val q = gateway.queryOrder(intent.symbol, intent.clientOrderId)
                if (q != null) {
                    intents.save(intent.withOrder(q))
                    return q
                }
                if (clock.nowMs() - intent.updatedAtMs > gateway.recvWindowMs + 1_000) {
                    intents.save(intent.copy(status = IntentStatus.NOT_PLACED, updatedAtMs = clock.nowMs(), note = "confirmado inexistente"))
                    return null
                }
            } catch (_: GatewayException) {
                // keep trying
            }
            delay(cfg.unknownQueryDelayMs)
        }
        return null
    }

    private fun OrderIntent.withOrder(o: ExchangeOrder): OrderIntent {
        val st = when {
            o.status == OrderStatus.FILLED -> IntentStatus.FILLED
            OrderStatus.isWorking(o.status) -> IntentStatus.WORKING
            o.executedQty.signum() > 0 -> IntentStatus.PARTIALLY_FILLED_FINAL
            o.status == OrderStatus.REJECTED -> IntentStatus.REJECTED
            else -> IntentStatus.CANCELED
        }
        return copy(status = st, exchangeOrderId = o.orderId, executedQty = o.executedQty, cumulativeQuote = o.cumulativeQuoteQty, updatedAtMs = clock.nowMs())
    }

    private fun newIntent(
        kind: IntentKind, symbol: String, side: Side, type: OrderType, qty: BigDecimal, positionId: String?,
        price: BigDecimal? = null, stopPrice: BigDecimal? = null, note: String = "",
    ): OrderIntent {
        val id = newId()
        val now = clock.nowMs()
        return OrderIntent(id, mode, symbol, kind, side, type, qty, price, stopPrice, clientOrderId(kind, id), IntentStatus.PREPARED, positionId, now, now, note = note)
    }

    private fun clientOrderId(kind: IntentKind, id: String): String {
        val m = when (mode) { com.trendpilot.core.model.TradingMode.REAL -> 'R'; com.trendpilot.core.model.TradingMode.PAPER -> 'P'; else -> 'B' }
        return "$CID_PREFIX$m${kind.code}$id".take(36)
    }

    private suspend fun feesInQuote(o: ExchangeOrder, rules: SymbolRules, price: BigDecimal): BigDecimal {
        if (o.fills.isEmpty()) return o.cumulativeQuoteQty * BigDecimal.valueOf(cfg.feeRate)
        var total = BigDecimal.ZERO
        for (f in o.fills) {
            total += when (f.commissionAsset) {
                rules.quoteAsset -> f.commission
                rules.baseAsset -> f.commission * f.price
                else -> runCatching { gateway.lastPrice(f.commissionAsset + rules.quoteAsset).price * f.commission }.getOrElse { f.qty * f.price * BigDecimal.valueOf(cfg.feeRate) }
            }
        }
        return total
    }

    private fun fail(symbol: String, msg: String): ExecutionOutcome.Failed = ExecutionOutcome.Failed("$symbol: $msg")

    private suspend fun emit(level: EventLevel, title: String, detail: String, symbol: String? = null) =
        events.emit(EngineEvent(level, mode, title, detail, symbol, clock.nowMs()))

    companion object {
        const val CID_PREFIX = "tp"

        fun reasonText(r: ExitReason) = when (r) {
            ExitReason.STOP_HIT -> "stop de protección"
            ExitReason.TREND_BREAK -> "ruptura de tendencia"
            ExitReason.MOMENTUM_LOSS -> "pérdida de impulso"
            ExitReason.AI_RECOMMENDATION -> "recomendación de la IA validada"
            ExitReason.ROTATION -> "rotación selectiva"
            ExitReason.MANUAL -> "orden manual"
            ExitReason.EMERGENCY -> "protocolo de emergencia"
            ExitReason.DEFENSIVE -> "modo defensivo"
        }
    }
}
