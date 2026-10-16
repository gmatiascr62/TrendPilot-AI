package com.trendpilot.core.risk

import com.trendpilot.core.filters.FilterMath
import com.trendpilot.core.filters.FilterValidator
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Risk limits. Percentages are of the bot's total equity (whole Spot account, marked to market).
 * Defaults are the values confirmed by the user on 2026-10-16.
 */
data class RiskConfig(
    val maxRiskPerTradePct: Double = 1.0,
    val maxTotalExposurePct: Double = 70.0,
    val minReservePct: Double = 30.0,
    val maxPerAssetPct: Double = 25.0,
    val maxPositions: Int = 3,
    val maxDrawdownPct: Double = 8.0,
    val dailyLossLimitPct: Double = 2.0,
    /** Taker fee per side as a fraction (0.001 = 0.1%). Updated from account commission rates. */
    val feeRate: Double = 0.001,
    /** Conservative slippage per side as a fraction, applied to entry AND stop exit. */
    val slippage: Double = 0.002,
    /** Extra gap allowance under the stop for sizing; stops do not guarantee the fill price. */
    val stopGapAllowance: Double = 0.003,
    val maxPriceAgeMs: Long = 120_000,
    val minRewardRiskToResistance: Double = 0.0,
    val requireAiConfirmation: Boolean = true,
    /** STOP_LOSS_LIMIT fallback: limit price this fraction below the stop trigger. */
    val stopLimitOffset: Double = 0.015,
) {
    init {
        require(maxRiskPerTradePct in 0.05..2.0) { "riesgo por operación fuera de rango" }
        require(maxTotalExposurePct + minReservePct <= 100.0 + 1e-9) { "exposición + reserva > 100%" }
        require(maxPerAssetPct <= maxTotalExposurePct)
        require(maxPositions in 1..5)
        require(maxDrawdownPct in 1.0..50.0)
        require(dailyLossLimitPct in 0.5..20.0)
    }
}

data class OpenPosition(
    val symbol: String,
    val baseAsset: String,
    val quantity: BigDecimal,
    val markPrice: BigDecimal,
    val stopPrice: BigDecimal?,
    val protectionConfirmed: Boolean,
) {
    val marketValue: BigDecimal get() = quantity * markPrice
}

/** Everything the RiskEngine needs, freshly reconciled from the exchange (or the paper broker). */
data class PortfolioSnapshot(
    val equity: BigDecimal,
    val freeQuote: BigDecimal,
    val positions: List<OpenPosition>,
    /** Notional of open BUY orders (reserved cash). */
    val pendingBuyNotional: BigDecimal,
    val symbolsWithOpenEntryOrders: Set<String>,
    val takenAtMs: Long,
)

data class GuardFlags(
    val drawdownLocked: Boolean = false,
    val dailyLossLocked: Boolean = false,
    val defensiveMode: Boolean = false,
    val entriesPausedByUser: Boolean = false,
    val strategyStopped: Boolean = false,
    val emergency: Boolean = false,
    val connectivityOk: Boolean = true,
    val reconciliationClean: Boolean = true,
    val needsManualResume: Boolean = false,
    val aiAvailable: Boolean = true,
    val realModeArmed: Boolean = false,
    val blockedSymbols: Set<String> = emptySet(),
)

data class EntryRequest(
    val symbol: String,
    val rules: SymbolRules,
    /** Best ask (or last price) we expect to pay. */
    val referencePrice: BigDecimal,
    val priceTimestampMs: Long,
    val stopPrice: BigDecimal,
    val nowMs: Long,
    val isRealMode: Boolean,
)

sealed interface RiskDecision {
    data class Approved(
        val symbol: String,
        val quantity: BigDecimal,
        val expectedNotional: BigDecimal,
        val stopPrice: BigDecimal,
        val stopLimitPrice: BigDecimal?,
        val stopType: OrderType,
        val plannedRiskQuote: BigDecimal,
        val plannedRiskPct: Double,
        val protectedQuantity: BigDecimal,
        val checks: List<String>,
    ) : RiskDecision

    data class Rejected(val symbol: String, val reasons: List<String>) : RiskDecision
}

/**
 * Deterministic gatekeeper. Works with or without AI and is the last word before any entry.
 * It never loosens a stop and never sizes beyond any configured limit.
 */
class RiskEngine(private val cfg: RiskConfig) {
    private val mc = MathContext.DECIMAL64

    fun evaluateEntry(req: EntryRequest, pf: PortfolioSnapshot, flags: GuardFlags): RiskDecision {
        val reasons = mutableListOf<String>()
        val checks = mutableListOf<String>()
        val r = req.rules

        // --- Gates that block every new entry.
        if (flags.strategyStopped) reasons += "estrategia detenida"
        if (flags.entriesPausedByUser) reasons += "compras pausadas manualmente"
        if (flags.emergency) reasons += "protocolo de emergencia activo"
        if (flags.drawdownLocked) reasons += "drawdown máximo ${cfg.maxDrawdownPct}% alcanzado: requiere reactivación manual"
        if (flags.dailyLossLocked) reasons += "límite de pérdida diaria ${cfg.dailyLossLimitPct}% alcanzado"
        if (flags.defensiveMode) reasons += "modo defensivo activo"
        if (!flags.connectivityOk) reasons += "sin conectividad confirmada con el exchange"
        if (!flags.reconciliationClean) reasons += "reconciliación pendiente o con discrepancias"
        if (flags.needsManualResume) reasons += "reanudación manual pendiente tras desconexión"
        if (cfg.requireAiConfirmation && !flags.aiAvailable) reasons += "IA no disponible (Claude y GPT fallaron o sin presupuesto)"
        if (req.isRealMode && !flags.realModeArmed) reasons += "modo real no habilitado"
        if (req.symbol in flags.blockedSymbols) reasons += "activo bloqueado por el usuario"
        if (pf.positions.any { !it.protectionConfirmed }) reasons += "hay posiciones sin protección confirmada"
        if (pf.positions.any { it.symbol == req.symbol }) reasons += "ya existe posición en ${req.symbol}"
        if (req.symbol in pf.symbolsWithOpenEntryOrders) reasons += "orden de entrada pendiente en ${req.symbol}"
        if (pf.positions.size >= cfg.maxPositions) reasons += "máximo de ${cfg.maxPositions} posiciones alcanzado"
        if (req.nowMs - req.priceTimestampMs > cfg.maxPriceAgeMs) reasons += "precio desactualizado (${(req.nowMs - req.priceTimestampMs) / 1000}s)"
        if (req.nowMs - pf.takenAtMs > cfg.maxPriceAgeMs) reasons += "saldo/estado de cuenta desactualizado"
        if (!r.isTrading) reasons += "${req.symbol} no está en TRADING"
        val stopType = r.protectiveStopType()
        if (stopType == null) reasons += "${req.symbol} no admite órdenes stop: no se puede proteger"
        if (!r.supports(OrderType.MARKET)) reasons += "${req.symbol} no admite MARKET"
        if (req.stopPrice >= req.referencePrice) reasons += "stop ${req.stopPrice} no está bajo el precio ${req.referencePrice}"
        if (pf.equity.signum() <= 0) reasons += "patrimonio no positivo"
        if (reasons.isNotEmpty()) return RiskDecision.Rejected(req.symbol, reasons)
        stopType!!

        // --- Prices rounded to exchange ticks (stop rounded DOWN: never tighter than requested
        // on paper, and the risk is recomputed with the rounded value).
        val tick = r.priceFilter?.tickSize ?: BigDecimal.ZERO
        val stop = FilterMath.floorToStep(req.stopPrice, tick)
        val stopLimit = if (stopType == OrderType.STOP_LOSS_LIMIT) {
            FilterMath.floorToStep(stop * bd(1 - cfg.stopLimitOffset), tick)
        } else null

        val fee = bd(cfg.feeRate)
        val effEntry = req.referencePrice * bd(1 + cfg.slippage)
        val worstExit = (stopLimit ?: stop) * bd(1 - cfg.slippage - cfg.stopGapAllowance)
        // Loss per unit bought: price drop + entry fee + exit fee.
        val lossPerUnit = (effEntry - worstExit) + effEntry * fee + worstExit * fee
        if (lossPerUnit.signum() <= 0) return RiskDecision.Rejected(req.symbol, listOf("distancia al stop inválida"))

        val equity = pf.equity
        val riskBudget = equity * bd(cfg.maxRiskPerTradePct / 100)
        val qtyByRisk = riskBudget.divide(lossPerUnit, mc)

        val exposure = pf.positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue } + pf.pendingBuyNotional
        val capTotal = equity * bd(cfg.maxTotalExposurePct / 100) - exposure
        val capAsset = equity * bd(cfg.maxPerAssetPct / 100)
        val reserve = equity * bd(cfg.minReservePct / 100)
        // Cash we may spend while keeping the reserve (pending buys already consume free cash).
        val capCash = (pf.freeQuote - reserve).divide(bd(1 + cfg.feeRate), mc)
        val capNotional = minOf(capTotal, capAsset, capCash)
        checks += "presupuesto de riesgo ${riskBudget.money()} (${cfg.maxRiskPerTradePct}%)"
        checks += "cupos: total ${capTotal.money()}, activo ${capAsset.money()}, caja tras reserva ${capCash.money()}"
        if (capNotional.signum() <= 0) return RiskDecision.Rejected(req.symbol, listOf("sin cupo: exposición/reserva al límite") + checks)

        val qtyByCap = capNotional.divide(effEntry, mc)
        var qty = minOf(qtyByRisk, qtyByCap)
        val lot = r.marketLotSize?.takeIf { it.stepSize.signum() > 0 } ?: r.lotSize
        val step = maxStep(lot?.stepSize, r.lotSize?.stepSize)
        qty = FilterMath.floorToStep(qty, step)

        // Quantity left after a base-asset commission must still be protectable.
        val protectedQty = FilterMath.floorToStep(qty * bd(1 - cfg.feeRate), r.lotSize?.stepSize ?: step)

        val buyViolations = FilterValidator.validate(r, OrderType.MARKET, Side.BUY, qty, null, null, effEntry)
        val stopViolations = FilterValidator.validate(r, stopType, Side.SELL, protectedQty, stopLimit, stop, stop)
        if (buyViolations.isNotEmpty() || stopViolations.isNotEmpty()) {
            return RiskDecision.Rejected(
                req.symbol,
                listOf("capital insuficiente para una orden válida y protegible: NO se opera") +
                    buyViolations.map { "compra: ${it.filter} ${it.detail}" } +
                    stopViolations.map { "stop: ${it.filter} ${it.detail}" } + checks,
            )
        }

        val plannedRisk = qty * lossPerUnit
        val plannedRiskPct = plannedRisk.divide(equity, mc).toDouble() * 100
        // Defensive double check: rounding never pushes us over a limit.
        val notional = qty * effEntry
        if (plannedRiskPct > cfg.maxRiskPerTradePct + 1e-9 || notional > capNotional + bd(1e-8)) {
            return RiskDecision.Rejected(req.symbol, listOf("verificación final de límites falló") + checks)
        }
        checks += "riesgo planificado ${plannedRisk.money()} (${"%.3f".format(java.util.Locale.US, plannedRiskPct)}%) con slippage y comisiones"
        return RiskDecision.Approved(
            symbol = req.symbol,
            quantity = qty,
            expectedNotional = notional,
            stopPrice = stop,
            stopLimitPrice = stopLimit,
            stopType = stopType,
            plannedRiskQuote = plannedRisk,
            plannedRiskPct = plannedRiskPct,
            protectedQuantity = protectedQty,
            checks = checks,
        )
    }

    /** A new stop is acceptable only if it does not increase risk (never lower an existing stop). */
    fun validateStopChange(currentStop: BigDecimal, proposed: BigDecimal, lastPrice: BigDecimal): String? = when {
        proposed < currentStop -> "rechazado: el stop propuesto ($proposed) aflojaría la protección (actual $currentStop)"
        proposed >= lastPrice -> "rechazado: el stop propuesto ($proposed) no está bajo el precio ($lastPrice)"
        else -> null
    }

    private fun maxStep(a: BigDecimal?, b: BigDecimal?): BigDecimal {
        val x = a ?: BigDecimal.ZERO
        val y = b ?: BigDecimal.ZERO
        return if (x > y) x else y
    }

    private fun bd(d: Double) = BigDecimal(d.toString())
}

internal fun BigDecimal.money(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

/**
 * Tracks equity high-water mark and the UTC-day starting equity, producing the drawdown and
 * daily-loss locks. The state is persisted by the caller.
 */
data class EquityGuardState(
    val peakEquity: BigDecimal,
    val dayStartEquity: BigDecimal,
    val utcDay: Long,
    val drawdownLocked: Boolean,
    val dailyLossLocked: Boolean,
)

class EquityGuard(private val cfg: RiskConfig) {
    fun initial(equity: BigDecimal, nowMs: Long) =
        EquityGuardState(equity, equity, nowMs / DAY_MS, drawdownLocked = false, dailyLossLocked = false)

    fun update(state: EquityGuardState, equity: BigDecimal, nowMs: Long): EquityGuardState {
        val day = nowMs / DAY_MS
        var s = state
        if (day != s.utcDay) {
            // Daily lock resets automatically at the next UTC day (user decision 2026-10-16).
            s = s.copy(utcDay = day, dayStartEquity = equity, dailyLossLocked = false)
        }
        val peak = if (equity > s.peakEquity) equity else s.peakEquity
        val dd = if (peak.signum() > 0) (peak - equity).toDouble() / peak.toDouble() * 100 else 0.0
        val dayLoss = if (s.dayStartEquity.signum() > 0) (s.dayStartEquity - equity).toDouble() / s.dayStartEquity.toDouble() * 100 else 0.0
        return s.copy(
            peakEquity = peak,
            // Drawdown lock is sticky: only a manual reactivation clears it.
            drawdownLocked = s.drawdownLocked || dd >= cfg.maxDrawdownPct,
            dailyLossLocked = s.dailyLossLocked || dayLoss >= cfg.dailyLossLimitPct,
        )
    }

    fun drawdownPct(state: EquityGuardState, equity: BigDecimal): Double =
        if (state.peakEquity.signum() > 0) (state.peakEquity - equity).toDouble() / state.peakEquity.toDouble() * 100 else 0.0

    /** Manual reactivation resets the high-water mark to current equity. */
    fun manualReactivate(state: EquityGuardState, equity: BigDecimal) =
        state.copy(drawdownLocked = false, peakEquity = equity)

    companion object { const val DAY_MS = 86_400_000L }
}
