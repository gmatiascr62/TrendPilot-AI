package com.trendpilot.core.engine

import com.trendpilot.core.ai.AiFeatures
import com.trendpilot.core.ai.AiOutcome
import com.trendpilot.core.ai.AiRouter
import com.trendpilot.core.ai.AiSnapshot
import com.trendpilot.core.ai.CandidateFeatures
import com.trendpilot.core.ai.PositionFeatures
import com.trendpilot.core.execution.Clock
import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.EventLevel
import com.trendpilot.core.execution.EventSink
import com.trendpilot.core.execution.ExchangeGateway
import com.trendpilot.core.execution.ExecutionCoordinator
import com.trendpilot.core.execution.ExecutionOutcome
import com.trendpilot.core.execution.GuardControl
import com.trendpilot.core.execution.Position
import com.trendpilot.core.execution.PositionStatus
import com.trendpilot.core.execution.PositionStore
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.indicators.IndicatorSnapshot
import com.trendpilot.core.indicators.Indicators
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.Ticker24h
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.risk.ConnectivityMonitor
import com.trendpilot.core.risk.ConnectivityState
import com.trendpilot.core.risk.DefensiveConfig
import com.trendpilot.core.risk.DefensiveInputs
import com.trendpilot.core.risk.DefensiveMode
import com.trendpilot.core.risk.EntryRequest
import com.trendpilot.core.risk.EquityGuard
import com.trendpilot.core.risk.EquityGuardState
import com.trendpilot.core.risk.GuardFlags
import com.trendpilot.core.risk.OpenPosition
import com.trendpilot.core.risk.PortfolioSnapshot
import com.trendpilot.core.risk.RiskConfig
import com.trendpilot.core.risk.RiskDecision
import com.trendpilot.core.risk.RiskEngine
import com.trendpilot.core.strategy.EntrySignal
import com.trendpilot.core.strategy.ExitEvaluator
import com.trendpilot.core.strategy.ExitReason
import com.trendpilot.core.strategy.MarketRegime
import com.trendpilot.core.strategy.RegimeClassifier
import com.trendpilot.core.strategy.StrategyConfig
import com.trendpilot.core.strategy.TrailingStopPolicy
import com.trendpilot.core.strategy.TrendStrategy
import com.trendpilot.core.universe.UniverseResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.util.UUID

/** Read-only market access used by the engine (Binance public data in every mode). */
interface MarketDataSource {
    suspend fun dailyCandles(symbol: String, count: Int = 400): List<Candle>
    suspend fun rules(symbols: Collection<String>): Map<String, SymbolRules>
    suspend fun tickers(symbols: Collection<String>): Map<String, Ticker24h>
}

fun interface UniverseSource { suspend fun refresh(nowMs: Long): UniverseResult }

/** Persisted engine state (flags survive restarts). */
data class EngineState(
    val mode: TradingMode,
    val strategyRunning: Boolean = false,
    val entriesPausedByUser: Boolean = false,
    val emergency: Boolean = false,
    val emergencyReason: String? = null,
    val reconciliationClean: Boolean = false,
    val realModeArmed: Boolean = false,
    val blockedSymbols: Set<String> = emptySet(),
    val defensiveActive: Boolean = false,
    val defensiveTriggers: List<String> = emptyList(),
    val aiAvailable: Boolean = true,
    val aiDefensive: Boolean = false,
    val aiLastError: String? = null,
    val equityGuard: EquityGuardState? = null,
    val connectivity: ConnectivityState = ConnectivityMonitor.INITIAL,
    val universe: UniverseResult? = null,
    val lastAnalysisMs: Long? = null,
    val lastMonitorMs: Long? = null,
    val lastEquity: BigDecimal? = null,
    val lastExposurePct: Double? = null,
)

interface EngineStateStore {
    suspend fun load(mode: TradingMode): EngineState?
    suspend fun save(state: EngineState)
}

/** Human-readable record of every decision (shown in History/Audit). */
data class DecisionRecord(
    val id: String,
    val mode: TradingMode,
    val timestampMs: Long,
    val kind: String,
    val symbol: String?,
    val summary: String,
    val details: String,
    val aiProvider: String? = null,
)

fun interface DecisionLog { suspend fun record(d: DecisionRecord) }

data class EngineConfig(
    val risk: RiskConfig = RiskConfig(),
    val strategy: StrategyConfig = StrategyConfig(),
    val defensive: DefensiveConfig = DefensiveConfig(),
    val universeMaxAgeMs: Long = 24 * 3_600_000L,
    val analysisIntervalMs: Long = 4 * 3_600_000L,
    /** Minimum score edge required for an entry, after costs; avoids over-trading. */
    val minEntryScore: Double = 0.0,
    val aiExitMinConfidence: Set<String> = setOf("MEDIUM", "HIGH"),
)

/**
 * Orchestrates the cycles. Critical deterministic rules always run before and after the AI and
 * override it. Every order goes through [ExecutionCoordinator] after a fresh RiskEngine check.
 */
class TradingEngine(
    private val gateway: ExchangeGateway,
    private val market: MarketDataSource,
    private val universeSource: UniverseSource,
    private val positions: PositionStore,
    private val stateStore: EngineStateStore,
    private val events: EventSink,
    private val decisions: DecisionLog,
    private val ai: AiRouter?,
    private val config: () -> EngineConfig,
    private val clock: Clock,
    coordinatorFactory: (GuardControl) -> ExecutionCoordinator,
) {
    val mode: TradingMode get() = gateway.mode
    private val lock = Mutex()
    private var state: EngineState = EngineState(mode)
    private val connectivity = ConnectivityMonitor()
    private val mc = MathContext.DECIMAL64
    private var rulesCache: Map<String, SymbolRules> = emptyMap()
    private val dailyCache = mutableMapOf<String, Pair<Long, List<Candle>>>()

    private val guardControl = object : GuardControl {
        override suspend fun raiseEmergency(reason: String) = update { it.copy(emergency = true, emergencyReason = reason) }
        override suspend fun markReconciliationDirty(reason: String) {
            update { it.copy(reconciliationClean = false) }
            log("RECONCILIACION", null, "Reconciliación requerida", reason)
        }
    }
    val coordinator: ExecutionCoordinator = coordinatorFactory(guardControl)

    suspend fun currentState(): EngineState = lock.withLock { state }

    suspend fun openPositions(): List<Position> = positions.open(mode)

    suspend fun init() = lock.withLock {
        state = stateStore.load(mode) ?: EngineState(mode)
        // After a process restart we cannot know how long we were offline: treat the last OK
        // timestamp as the start of the outage so the conservative policy applies.
        val c = state.connectivity
        state = state.copy(connectivity = c.copy(connected = false, disconnectedSinceMs = c.disconnectedSinceMs ?: c.lastOkMs), reconciliationClean = false)
        stateStore.save(state)
    }

    fun flags(s: EngineState = state): GuardFlags = GuardFlags(
        drawdownLocked = s.equityGuard?.drawdownLocked == true,
        dailyLossLocked = s.equityGuard?.dailyLossLocked == true,
        defensiveMode = s.defensiveActive,
        entriesPausedByUser = s.entriesPausedByUser,
        strategyStopped = !s.strategyRunning,
        emergency = s.emergency,
        connectivityOk = s.connectivity.connected,
        reconciliationClean = s.reconciliationClean,
        needsManualResume = s.connectivity.needsManualResume,
        aiAvailable = s.aiAvailable,
        realModeArmed = s.realModeArmed,
        blockedSymbols = s.blockedSymbols,
    )

    // ------------------------------------------------------------------ cycles

    /**
     * ~5 min: reconcile, mark equity, update guards, trail stops and exit on confirmed
     * deterioration. Runs even when the strategy is stopped (protection never stops).
     */
    suspend fun monitorCycle() {
        val now = clock.nowMs()
        val report = try {
            coordinator.reconcile()
        } catch (e: Exception) {
            update { it.copy(connectivity = connectivity.onFailure(it.connectivity, now), reconciliationClean = false) }
            emit(EventLevel.WARNING, "Sin conexión con el exchange", "${e.javaClass.simpleName}: ${e.message}. Las protecciones en el exchange siguen activas; compras y ajustes de stop congelados.")
            return
        }
        val wasConnected = state.connectivity.connected
        update { it.copy(connectivity = connectivity.onReconciled(it.connectivity, now, !report.clean), reconciliationClean = report.clean, lastMonitorMs = now) }
        if (!wasConnected) {
            emit(EventLevel.INFO, "Conexión restablecida", if (state.connectivity.needsManualResume) "Reconciliación completada. Compras en pausa hasta tu confirmación." else "Reconciliación limpia.", notify = true)
        }

        val pf = portfolio() ?: return
        markEquity(pf)
        evaluateDefensive()

        if (!state.connectivity.connected) return
        for (pos in positions.open(mode).filter { it.status == PositionStatus.PROTECTED }) {
            runCatching { managePosition(pos) }.onFailure {
                emit(EventLevel.WARNING, "Error gestionando ${pos.symbol}", it.message ?: it.javaClass.simpleName, pos.symbol)
            }
        }
    }

    private suspend fun managePosition(pos: Position) {
        val daily = daily(pos.symbol)
        if (daily.size < 60) return
        val last = gateway.lastPrice(pos.symbol).price
        val highest = maxOf(pos.highestPrice, last, daily.filter { it.openTime >= pos.openedAtMs }.maxOfOrNull { BigDecimal.valueOf(it.high) } ?: BigDecimal.ZERO)
        var current = pos
        if (highest > pos.highestPrice) { current = pos.copy(highestPrice = highest); positions.save(current) }

        val exit = ExitEvaluator.assess(daily)
        if (exit.deteriorated) {
            log("SALIDA", pos.symbol, "Deterioro confirmado: ${exit.detail}", "Regla determinista; cierre a mercado.")
            coordinator.closePosition(pos.id, exit.reason!!)
            return
        }
        val atr = Indicators.atr(daily).last()
        val proposal = TrailingStopPolicy(config().strategy).propose(
            TrailingStopPolicy.Input(
                entryPrice = pos.entryPrice.toDouble(), initialStop = pos.initialStop.toDouble(), currentStop = pos.currentStop.toDouble(),
                highestHighSinceEntry = highest.toDouble(), atr = atr, lastClose = last.toDouble(),
                swingLow = Indicators.lastSwingLow(daily, daily.lastIndex), feeAndSlippageFraction = config().risk.feeRate + config().risk.slippage,
            ),
        ) ?: return
        val newStop = BigDecimal.valueOf(proposal)
        RiskEngine(config().risk).validateStopChange(pos.currentStop, newStop, last)?.let { return }
        val out = coordinator.raiseStop(pos.id, newStop, "trailing ATR (máximo ${highest.round(MathContext(8))}, ATR ${"%.4g".format(atr)})")
        log("STOP", pos.symbol, "Trailing stop ${pos.currentStop} → ${newStop.round(MathContext(8))}", out.toString())
    }

    /** Every 4h: universe, signals, AI review, then strictly verified entries. */
    suspend fun analysisCycle(force: Boolean = false) {
        val now = clock.nowMs()
        if (!force && state.lastAnalysisMs != null && now - state.lastAnalysisMs!! < config().analysisIntervalMs - 60_000) return
        if (!state.strategyRunning) { log("ANALISIS", null, "Estrategia detenida: análisis omitido", ""); return }
        update { it.copy(lastAnalysisMs = now) }

        val universe = state.universe?.takeIf { now - it.generatedAtMs < config().universeMaxAgeMs } ?: runCatching {
            universeSource.refresh(now).also { u ->
                update { it.copy(universe = u) }
                log("UNIVERSO", null, "Universo actualizado: ${u.selected.size} activos", "Seleccionados: ${u.selected.joinToString()}\nExcluidos: ${u.excluded.joinToString("\n") { "${it.first}: ${it.second}" }}")
            }
        }.getOrElse { e ->
            emit(EventLevel.WARNING, "Universo no actualizado", "Se usa la última lista válida. ${e.message}")
            state.universe ?: return
        }

        val btcDaily = daily("BTCUSDT")
        val regime = RegimeClassifier.classify(btcDaily)
        val open = positions.open(mode)
        val strategy = TrendStrategy(config().strategy)
        val signals = mutableListOf<EntrySignal>()
        val rejections = mutableListOf<String>()
        for (symbol in universe.selected) {
            if (open.any { it.symbol == symbol }) continue
            val candles = runCatching { daily(symbol) }.getOrNull() ?: continue
            when (val r = strategy.evaluate(symbol, candles, regime)) {
                is TrendStrategy.Result.Signal -> if (r.signal.score >= config().minEntryScore) signals += r.signal
                is TrendStrategy.Result.Rejected -> rejections += "$symbol: ${r.rejection.reasons.joinToString("; ")}"
            }
        }
        signals.sortByDescending { it.score }
        log("ANALISIS", null, "Régimen BTC $regime: ${signals.size} candidatos", (signals.map { "${it.symbol} ${it.setup} score ${"%.1f".format(it.score)}: ${it.reasons.joinToString("; ")}" } + rejections).joinToString("\n"))

        // AI review (candidates + open positions). The deterministic rules already ran.
        var ordered = signals.map { it to it.stopPrice }
        var aiProvider: String? = null
        if (ai != null && (signals.isNotEmpty() || open.isNotEmpty())) {
            val snapshot = buildSnapshot(signals, open, btcDaily, regime)
            when (val outcome = ai.analyze(snapshot)) {
                is AiOutcome.Ok -> {
                    val v = outcome.result
                    aiProvider = "${v.usage.provider}/${v.usage.model}"
                    update { it.copy(aiAvailable = true, aiLastError = null) }
                    log("IA", null, "Revisión IA (${aiProvider}): mercado ${v.analysis.market.regime}", v.analysis.market.reason + if (v.dropped.isNotEmpty()) "\nDescartado por validación: ${v.dropped.joinToString("; ")}" else "", aiProvider)
                    update { it.copy(aiDefensive = v.analysis.market.defensiveRecommended) }
                    ordered = v.approvedCandidates.mapNotNull { c ->
                        val s = signals.firstOrNull { it.symbol == c.symbol } ?: return@mapNotNull null
                        log("IA", c.symbol, "Candidato aprobado (${c.confidence}, rank ${c.rank})", "${c.reason}\nInvalidación: ${c.invalidation}\nDatos: ${c.dataUsed.joinToString()}", aiProvider)
                        // The AI may only TIGHTEN the system stop (higher), never widen it.
                        val aiStop = c.proposedStop
                        val minDistance = s.atr * config().strategy.minStopAtr
                        val stop = if (aiStop != null && aiStop > s.stopPrice && s.referencePrice - aiStop >= minDistance) aiStop else s.stopPrice
                        s to stop
                    }
                    val vetoed = signals.map { it.symbol } - ordered.map { it.first.symbol }.toSet()
                    vetoed.forEach { sym ->
                        val c = v.analysis.candidates.firstOrNull { it.symbol == sym }
                        log("IA", sym, "Candidato vetado por la IA", c?.reason ?: "sin aprobación", aiProvider)
                    }
                    applyAiPositionActions(v.positionActions, aiProvider)
                }
                is AiOutcome.Unavailable -> {
                    update { it.copy(aiAvailable = false, aiLastError = outcome.reasons.joinToString("; ")) }
                    emit(EventLevel.WARNING, "IA no disponible", "Sin compras nuevas; protecciones activas. ${outcome.reasons.joinToString("; ")}")
                }
            }
        }

        evaluateDefensive()
        for ((signal, stop) in ordered) {
            if (!tryEnter(signal, stop, aiProvider)) continue
        }
        persist()
    }

    private suspend fun applyAiPositionActions(actions: List<com.trendpilot.core.ai.PositionReview>, provider: String?) {
        val open = positions.open(mode)
        for (a in actions) {
            val pos = open.firstOrNull { it.symbol == a.symbol && it.status == PositionStatus.PROTECTED } ?: continue
            when (a.action) {
                "RAISE_STOP" -> {
                    val last = gateway.lastPrice(pos.symbol).price
                    val requested = BigDecimal.valueOf(a.proposedStop!!)
                    // The AI must not choke the trend: its stop stays at least minStopAtr·ATR below price.
                    val daily = daily(pos.symbol)
                    if (daily.size < 60) { log("IA", pos.symbol, "Subida de stop rechazada", "sin historial diario para medir el ATR", provider); continue }
                    val atr = Indicators.atr(daily).last()
                    val ceiling = last - BigDecimal.valueOf(atr * config().strategy.minStopAtr)
                    val proposed = if (requested > ceiling) ceiling else requested
                    val clampNote = if (proposed < requested)
                        "\nIA pidió ${requested.round(MathContext(8))}; limitado a ${proposed.round(MathContext(8))} (precio − ${config().strategy.minStopAtr} ATR)" else ""
                    val err = RiskEngine(config().risk).validateStopChange(pos.currentStop, proposed, last)
                        ?: if (proposed <= pos.currentStop) "el stop actual ya está a menos de ${config().strategy.minStopAtr} ATR del precio" else null
                    if (err != null) { log("IA", pos.symbol, "Subida de stop rechazada", "$err$clampNote", provider); continue }
                    val out = coordinator.raiseStop(pos.id, proposed, "IA: ${a.reason}")
                    log("STOP", pos.symbol, "Stop elevado por recomendación IA validada", "${a.reason}$clampNote\n$out", provider)
                }
                "EXIT" -> if (a.confidence in config().aiExitMinConfidence) {
                    log("SALIDA", pos.symbol, "Venta anticipada recomendada por IA (${a.confidence})", a.reason, provider)
                    coordinator.closePosition(pos.id, ExitReason.AI_RECOMMENDATION)
                } else log("IA", pos.symbol, "Recomendación de salida ignorada (confianza ${a.confidence})", a.reason, provider)
                "PARTIAL_EXIT" -> if (a.confidence in config().aiExitMinConfidence) {
                    val out = coordinator.partialExit(pos.id, a.partialFraction!!, ExitReason.AI_RECOMMENDATION)
                    log("SALIDA", pos.symbol, "Venta parcial ${(a.partialFraction * 100).toInt()}% (${a.confidence})", "${a.reason}\n$out", provider)
                }
            }
        }
    }

    /** Every abandoned entry leaves a trace: nothing fails silently. */
    private suspend fun skip(signal: EntrySignal, why: String, aiProvider: String?): Boolean {
        log("RIESGO", signal.symbol, "Entrada no evaluada", why, aiProvider)
        return false
    }

    private suspend fun tryEnter(signal: EntrySignal, stop: Double, aiProvider: String?): Boolean {
        // Fresh state before EVERY order: balances, open orders, prices, rules, guards.
        val pf = portfolio() ?: return skip(signal, "no se pudo leer saldo u órdenes abiertas del exchange", aiProvider)
        val rules = runCatching { rules(listOf(signal.symbol))[signal.symbol] }.getOrNull()
            ?: return skip(signal, "no se pudieron obtener los filtros de ${signal.symbol}", aiProvider)
        val tick = runCatching { gateway.lastPrice(signal.symbol) }.getOrElse { e ->
            return skip(signal, "sin precio actual de ${signal.symbol}: ${e.message}", aiProvider)
        }
        val req = EntryRequest(signal.symbol, rules, tick.price, tick.eventTimeMs, BigDecimal.valueOf(stop), clock.nowMs(), mode == TradingMode.REAL)
        val decision = RiskEngine(config().risk).evaluateEntry(req, pf, flags())
        return when (decision) {
            is RiskDecision.Rejected -> {
                log("RIESGO", signal.symbol, "Entrada rechazada por el motor de riesgo", decision.reasons.joinToString("\n"), aiProvider)
                false
            }
            is RiskDecision.Approved -> {
                log("ENTRADA", signal.symbol, "Entrada aprobada: ${decision.quantity} (${signal.setup})", (signal.reasons + decision.checks + "Invalidación: ${signal.invalidation}").joinToString("\n"), aiProvider)
                val out = coordinator.openPosition(decision, signal.setup.name, signal.reasons.joinToString("; "))
                out is ExecutionOutcome.Done
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun portfolio(): PortfolioSnapshot? {
        val balances = runCatching { gateway.balances() }.getOrElse {
            update { s -> s.copy(connectivity = connectivity.onFailure(s.connectivity, clock.nowMs())) }
            return null
        }
        val open = positions.open(mode)
        val openOrders = runCatching { gateway.openOrders() }.getOrElse { return null }
        var equity = BigDecimal.ZERO
        var exposure = BigDecimal.ZERO
        val quote = gateway.quoteAsset
        val prices = mutableMapOf<String, BigDecimal>()
        for (b in balances) {
            if (b.asset == quote) { equity += b.total; continue }
            if (b.total.signum() == 0) continue
            val p = runCatching { gateway.lastPrice(b.asset + quote).price }.getOrNull() ?: continue
            prices[b.asset] = p
            equity += b.total * p
            exposure += b.total * p
        }
        val pendingBuys = openOrders.filter { it.side == com.trendpilot.core.model.Side.BUY }
        val pendingNotional = pendingBuys.fold(BigDecimal.ZERO) { a, o -> a + (o.price ?: BigDecimal.ZERO) * (o.origQty - o.executedQty) }
        val held = open.map { OpenPosition(it.symbol, it.baseAsset, it.quantity, prices[it.baseAsset] ?: it.entryPrice, it.currentStop, it.status == PositionStatus.PROTECTED) }
        val free = balances.firstOrNull { it.asset == quote }?.free ?: BigDecimal.ZERO
        val exposurePct = if (equity.signum() > 0) exposure.divide(equity, mc).toDouble() * 100 else 0.0
        update { it.copy(lastEquity = equity, lastExposurePct = exposurePct) }
        return PortfolioSnapshot(equity, free, held, pendingNotional, pendingBuys.map { it.symbol }.toSet(), clock.nowMs())
    }

    private suspend fun markEquity(pf: PortfolioSnapshot) {
        val guard = EquityGuard(config().risk)
        val before = state.equityGuard
        val after = if (before == null) guard.initial(pf.equity, clock.nowMs()) else guard.update(before, pf.equity, clock.nowMs())
        update { it.copy(equityGuard = after) }
        if (before?.drawdownLocked != true && after.drawdownLocked) {
            emit(EventLevel.SECURITY, "Drawdown máximo alcanzado", "Caída ≥ ${config().risk.maxDrawdownPct}% desde el máximo (${after.peakEquity.setScale(2, java.math.RoundingMode.HALF_UP)}). Compras bloqueadas hasta reactivación manual. Las protecciones siguen activas.")
        }
        if (before?.dailyLossLocked != true && after.dailyLossLocked) {
            emit(EventLevel.SECURITY, "Límite de pérdida diaria", "Pérdida ≥ ${config().risk.dailyLossLimitPct}% hoy (UTC). Sin compras hasta mañana UTC.")
        }
    }

    private suspend fun evaluateDefensive() {
        val btc = runCatching { daily("BTCUSDT") }.getOrNull() ?: return
        val universe = state.universe?.selected.orEmpty()
        val tickers = runCatching { market.tickers(universe + "BTCUSDT") }.getOrElse { emptyMap() }
        val changes = universe.mapNotNull { tickers[it]?.priceChangePercent }.sorted()
        val median = if (changes.isEmpty()) Double.NaN else changes[changes.size / 2]
        var below = 0
        var counted = 0
        for (s in universe) {
            val c = dailyCache[s]?.second ?: continue
            if (c.size < 50) continue
            counted++
            if (c.last().close < Indicators.sma(c.map { it.close }, 50).last()) below++
        }
        val inputs = DefensiveInputs(
            btcChange24hPct = tickers["BTCUSDT"]?.priceChangePercent ?: Double.NaN,
            btcRegime = RegimeClassifier.classify(btc),
            universeMedianChange24hPct = median,
            pctUniverseBelowSma50 = if (counted == 0) Double.NaN else below * 100.0 / counted,
            aiDefensiveFlag = state.aiDefensive,
        )
        val d = DefensiveMode.evaluate(config().defensive, inputs)
        val was = state.defensiveActive
        update { it.copy(defensiveActive = d.active, defensiveTriggers = d.triggers) }
        if (d.active != was) {
            emit(EventLevel.SECURITY, if (d.active) "Modo defensivo ACTIVADO" else "Modo defensivo desactivado",
                if (d.active) "Sin compras ni rotaciones de entrada. Protecciones activas. Motivos: ${d.triggers.joinToString("; ")}" else "Condiciones normalizadas.")
        }
    }

    private suspend fun buildSnapshot(signals: List<EntrySignal>, open: List<Position>, btcDaily: List<Candle>, regime: MarketRegime): AiSnapshot {
        val now = clock.nowMs()
        val btcCloses = btcDaily.map { it.close }
        fun corr(c: List<Candle>) = Indicators.correlation(c.map { it.close }.takeLast(31), btcCloses.takeLast(31))
        val equity = state.lastEquity?.toDouble() ?: 0.0
        return AiSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            dataAsOf = Instant.ofEpochMilli(now).toString(),
            btc = AiFeatures.from("BTCUSDT", IndicatorSnapshot.at(btcDaily), 1.0),
            btcRegime = regime.name,
            defensiveTriggers = state.defensiveTriggers,
            equityQuote = AiFeatures.clean(equity),
            exposurePct = AiFeatures.clean(state.lastExposurePct ?: 0.0),
            reservePct = AiFeatures.clean(100 - (state.lastExposurePct ?: 0.0)),
            candidates = signals.map {
                val c = daily(it.symbol)
                CandidateFeatures(AiFeatures.from(it.symbol, it.snapshot, corr(c)), it.setup.name, AiFeatures.clean(it.stopPrice), it.reasons, AiFeatures.clean(it.score))
            },
            positions = open.filter { it.status == PositionStatus.PROTECTED }.map { p ->
                val c = daily(p.symbol)
                val s = IndicatorSnapshot.at(c)
                PositionFeatures(
                    AiFeatures.from(p.symbol, s, corr(c)), AiFeatures.clean(p.entryPrice.toDouble()), AiFeatures.clean(p.currentStop.toDouble()),
                    AiFeatures.clean((s.close / p.entryPrice.toDouble() - 1) * 100), AiFeatures.clean((now - p.openedAtMs) / 86_400_000.0),
                    ExitEvaluator.assess(c).takeIf { it.deteriorated }?.detail,
                )
            },
        )
    }

    private suspend fun daily(symbol: String): List<Candle> {
        val day = clock.nowMs() / 86_400_000L
        dailyCache[symbol]?.let { (d, c) -> if (d == day) return c }
        val c = market.dailyCandles(symbol, 400)
        dailyCache[symbol] = day to c
        return c
    }

    private suspend fun rules(symbols: Collection<String>): Map<String, SymbolRules> {
        val missing = symbols.filter { it !in rulesCache }
        if (missing.isNotEmpty()) rulesCache = rulesCache + market.rules(missing)
        return rulesCache.filterKeys { it in symbols }
    }

    suspend fun rulesFor(symbol: String): SymbolRules = rules(listOf(symbol))[symbol] ?: error("sin reglas para $symbol")

    /** Daily refresh of exchange rules (filters can change). */
    fun invalidateRules() { rulesCache = emptyMap() }

    // ------------------------------------------------------------------ manual controls

    suspend fun startStrategy() { update { it.copy(strategyRunning = true) }; log("CONTROL", null, "Estrategia iniciada", "") }
    /** Stops analysis and new entries. Never cancels protections. */
    suspend fun stopStrategy() { update { it.copy(strategyRunning = false) }; log("CONTROL", null, "Estrategia detenida (protecciones intactas)", "") }
    suspend fun pauseEntries(paused: Boolean) { update { it.copy(entriesPausedByUser = paused) }; log("CONTROL", null, if (paused) "Compras pausadas" else "Compras reanudadas", "") }
    suspend fun blockSymbol(symbol: String, blocked: Boolean) = update { it.copy(blockedSymbols = if (blocked) it.blockedSymbols + symbol else it.blockedSymbols - symbol) }
    suspend fun confirmResumeAfterOutage() { update { it.copy(connectivity = connectivity.onManualResume(it.connectivity)) }; log("CONTROL", null, "Reanudación manual tras desconexión", "") }
    suspend fun reactivateAfterDrawdown() {
        val eq = state.lastEquity ?: return
        update { s -> s.copy(equityGuard = s.equityGuard?.let { EquityGuard(config().risk).manualReactivate(it, eq) }) }
        log("CONTROL", null, "Reactivación manual tras drawdown", "Nuevo máximo de referencia: $eq")
    }
    /** Clears the emergency flag only when every open position is protected. */
    suspend fun clearEmergency(): Boolean {
        val unprotected = positions.open(mode).any { it.status != PositionStatus.PROTECTED }
        if (unprotected) return false
        update { it.copy(emergency = false, emergencyReason = null) }
        log("CONTROL", null, "Emergencia despejada manualmente", "")
        return true
    }
    suspend fun armRealMode(armed: Boolean) {
        require(mode == TradingMode.REAL)
        update { it.copy(realModeArmed = armed) }
        emit(EventLevel.SECURITY, if (armed) "Modo REAL habilitado" else "Modo REAL deshabilitado", "Cambio confirmado por el usuario.")
    }
    suspend fun closePosition(id: String) = coordinator.closePosition(id, ExitReason.MANUAL)
    suspend fun liquidateAll() = coordinator.liquidateAll(ExitReason.MANUAL)

    // ------------------------------------------------------------------ state

    private suspend fun update(f: (EngineState) -> EngineState) {
        lock.withLock {
            state = f(state)
            stateStore.save(state)
        }
    }

    private suspend fun persist() = lock.withLock { stateStore.save(state) }

    private suspend fun emit(level: EventLevel, title: String, detail: String, symbol: String? = null, notify: Boolean = level != EventLevel.INFO) =
        events.emit(EngineEvent(level, mode, title, detail, symbol, clock.nowMs(), notify))

    private suspend fun log(kind: String, symbol: String?, summary: String, details: String, provider: String? = null) =
        decisions.record(DecisionRecord(UUID.randomUUID().toString(), mode, clock.nowMs(), kind, symbol, summary, details, provider))
}
