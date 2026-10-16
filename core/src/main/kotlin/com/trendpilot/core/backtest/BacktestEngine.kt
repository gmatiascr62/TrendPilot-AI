package com.trendpilot.core.backtest

import com.trendpilot.core.filters.LotSize
import com.trendpilot.core.filters.Notional
import com.trendpilot.core.filters.PriceFilter
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.indicators.IndicatorSnapshot
import com.trendpilot.core.indicators.Indicators
import com.trendpilot.core.model.Candle
import com.trendpilot.core.risk.EntryRequest
import com.trendpilot.core.risk.GuardFlags
import com.trendpilot.core.risk.OpenPosition
import com.trendpilot.core.risk.PortfolioSnapshot
import com.trendpilot.core.risk.RiskConfig
import com.trendpilot.core.risk.RiskDecision
import com.trendpilot.core.risk.RiskEngine
import com.trendpilot.core.strategy.ExitEvaluator
import com.trendpilot.core.strategy.ExitReason
import com.trendpilot.core.strategy.RegimeClassifier
import com.trendpilot.core.strategy.StrategyConfig
import com.trendpilot.core.strategy.TrailingStopPolicy
import com.trendpilot.core.strategy.TrendStrategy
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

data class BacktestConfig(
    val initialCapital: Double = 200.0,
    val risk: RiskConfig = RiskConfig(requireAiConfirmation = false),
    val strategy: StrategyConfig = StrategyConfig(),
    val feeRate: Double = 0.001,
    /** Applied on every fill, on top of gaps. */
    val slippage: Double = 0.002,
    val warmupDays: Int = 210,
)

data class BacktestTrade(
    val symbol: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val qty: Double,
    val fees: Double,
    val pnl: Double,
    val r: Double,
    val reason: ExitReason,
)

data class EquityPoint(val time: Long, val equity: Double, val btcBenchmark: Double)

data class BacktestResult(
    val trades: List<BacktestTrade>,
    val equity: List<EquityPoint>,
    val totalReturnPct: Double,
    val btcReturnPct: Double,
    val maxDrawdownPct: Double,
    val winRatePct: Double,
    val profitFactor: Double,
    val avgR: Double,
    val totalFees: Double,
    val avgExposurePct: Double,
    val warnings: List<String>,
)

/**
 * Daily-bar event simulation without look-ahead:
 *  - decisions use only candles closed at or before day t;
 *  - entries fill at day t+1 open (+slippage); a gap below the stop cancels the entry;
 *  - stops fill at min(open, stop) (gaps) minus slippage;
 *  - discretionary exits decided at close t fill at the next open.
 * AI decisions are NOT simulated: they cannot be reconstructed historically. Results describe the
 * deterministic rules only.
 */
class BacktestEngine(private val cfg: BacktestConfig = BacktestConfig()) {

    /**
     * [candles]: daily candles by symbol (must include "BTCUSDT").
     * [membership]: symbols eligible at a given time (historical top-20); null = current universe
     * (survivorship bias warning).
     */
    fun run(
        candles: Map<String, List<Candle>>,
        rules: Map<String, SymbolRules> = emptyMap(),
        membership: ((Long) -> Set<String>)? = null,
        fromTime: Long? = null,
        toTime: Long? = null,
    ): BacktestResult {
        val warnings = mutableListOf(
            "Las decisiones de la IA no se simulan: este resultado refleja solo las reglas deterministas.",
            "Comisión ${cfg.feeRate * 100}% y slippage ${cfg.slippage * 100}% por lado; los gaps se ejecutan al precio de apertura.",
        )
        if (membership == null) warnings += "Sesgo de supervivencia: se usa el universo actual, no la composición histórica del top 20."
        if (rules.isEmpty()) warnings += "Filtros de Binance aproximados (minNotional 5 USDT); los filtros históricos pudieron ser distintos."

        val btc = candles["BTCUSDT"] ?: error("se requieren velas de BTCUSDT")
        val timeline = btc.map { it.openTime }.filter { (fromTime == null || it >= fromTime) && (toTime == null || it <= toTime) }
        val byTime = candles.mapValues { (_, list) -> list.withIndex().associate { it.value.openTime to it.index } }
        val strategy = TrendStrategy(cfg.strategy)
        val trailing = TrailingStopPolicy(cfg.strategy)
        val riskEngine = RiskEngine(cfg.risk)

        var cash = cfg.initialCapital
        data class Pos(val symbol: String, val qty: Double, val entry: Double, val initialStop: Double, var stop: Double, var highest: Double, val entryTime: Long, val entryFee: Double)
        val open = mutableListOf<Pos>()
        val pendingEntries = mutableListOf<Pair<String, RiskDecision.Approved>>()
        val pendingExits = mutableMapOf<String, ExitReason>()
        val trades = mutableListOf<BacktestTrade>()
        val curve = mutableListOf<EquityPoint>()
        var exposureSum = 0.0

        val btcStartIdx = timeline.firstOrNull()?.let { byTime["BTCUSDT"]!![it] }
        val btcQty = btcStartIdx?.let { cfg.initialCapital * (1 - cfg.feeRate) / (btc[it].open * (1 + cfg.slippage)) } ?: 0.0

        fun close(p: Pos, price: Double, time: Long, reason: ExitReason) {
            val px = price * (1 - cfg.slippage)
            val gross = p.qty * px
            val fee = gross * cfg.feeRate
            cash += gross - fee
            val pnl = gross - fee - (p.qty * p.entry + p.entryFee)
            val riskPerUnit = p.entry - p.initialStop
            trades += BacktestTrade(p.symbol, p.entryTime, time, p.entry, px, p.qty, p.entryFee + fee, pnl, if (riskPerUnit > 0) pnl / (riskPerUnit * p.qty) else 0.0, reason)
            open.remove(p)
        }

        for (t in timeline) {
            // 1. Open of day t: discretionary exits, then entries.
            for ((sym, reason) in pendingExits.toMap()) {
                val p = open.firstOrNull { it.symbol == sym } ?: continue
                val c = candles[sym]!![byTime[sym]!![t] ?: continue]
                close(p, c.open, t, reason)
            }
            pendingExits.clear()
            for ((sym, d) in pendingEntries) {
                val idx = byTime[sym]?.get(t) ?: continue
                val c = candles[sym]!![idx]
                val stop = d.stopPrice.toDouble()
                if (c.open <= stop) continue // gapped through the stop: skip
                val px = c.open * (1 + cfg.slippage)
                val qty = d.quantity.toDouble()
                val cost = qty * px
                val fee = cost * cfg.feeRate
                if (cost + fee > cash) continue
                cash -= cost + fee
                open += Pos(sym, qty, px, stop, stop, c.high, t, fee)
            }
            pendingEntries.clear()

            // 2. Intraday stops.
            for (p in open.toList()) {
                val c = candles[p.symbol]!![byTime[p.symbol]!![t] ?: continue]
                if (c.open <= p.stop) close(p, c.open, t, ExitReason.STOP_HIT)
                else if (c.low <= p.stop) close(p, p.stop, t, ExitReason.STOP_HIT)
            }

            // 3. Close of day t: mark, trail, exits, new signals.
            val marks = open.associate { p -> p.symbol to candles[p.symbol]!![byTime[p.symbol]!![t]!!].close }
            val positionsValue = open.sumOf { it.qty * marks.getValue(it.symbol) }
            val equity = cash + positionsValue
            exposureSum += if (equity > 0) positionsValue / equity else 0.0
            val btcIdx = byTime["BTCUSDT"]!![t]!!
            curve += EquityPoint(t, equity, btcQty * btc[btcIdx].close)

            for (p in open) {
                val list = candles[p.symbol]!!
                val i = byTime[p.symbol]!![t]!!
                val history = list.subList(0, i + 1)
                p.highest = max(p.highest, list[i].high)
                val atr = Indicators.atr(history).last()
                val proposal = trailing.propose(
                    TrailingStopPolicy.Input(p.entry, p.initialStop, p.stop, p.highest, atr, list[i].close,
                        Indicators.lastSwingLow(history, i), cfg.feeRate + cfg.slippage),
                )
                if (proposal != null && proposal > p.stop) p.stop = proposal
                val exit = ExitEvaluator.assess(history)
                if (exit.deteriorated) pendingExits[p.symbol] = exit.reason!!
            }

            if (btcIdx + 1 >= cfg.warmupDays) {
                val regime = RegimeClassifier.classify(btc.subList(0, btcIdx + 1))
                val eligible = membership?.invoke(t) ?: candles.keys
                val signals = eligible.mapNotNull { sym ->
                    val i = byTime[sym]?.get(t) ?: return@mapNotNull null
                    if (open.any { it.symbol == sym }) return@mapNotNull null
                    (strategy.evaluate(sym, candles[sym]!!.subList(0, i + 1), regime) as? TrendStrategy.Result.Signal)?.signal
                }.sortedByDescending { it.score }

                var simulatedPositions = open.map {
                    OpenPosition(it.symbol, it.symbol.removeSuffix("USDT"), bd(it.qty), bd(marks.getValue(it.symbol)), bd(it.stop), true)
                }
                var pendingNotional = BigDecimal.ZERO
                var freeCash = bd(cash)
                for (s in signals) {
                    val r = rules[s.symbol] ?: syntheticRules(s.symbol, s.referencePrice)
                    val pf = PortfolioSnapshot(bd(equity), freeCash, simulatedPositions, pendingNotional, pendingEntries.map { it.first }.toSet(), t)
                    val req = EntryRequest(s.symbol, r, bd(s.referencePrice), t, bd(s.stopPrice), t, isRealMode = false)
                    val d = riskEngine.evaluateEntry(req, pf, GuardFlags(aiAvailable = true))
                    if (d is RiskDecision.Approved) {
                        pendingEntries += s.symbol to d
                        pendingNotional += d.expectedNotional
                        freeCash -= d.expectedNotional * BigDecimal.valueOf(1 + cfg.feeRate)
                        simulatedPositions = simulatedPositions + OpenPosition(s.symbol, s.symbol.removeSuffix("USDT"), BigDecimal.ZERO, BigDecimal.ZERO, d.stopPrice, true)
                    }
                }
            }
        }
        // Close remaining positions at the last close for reporting.
        val lastT = timeline.lastOrNull()
        if (lastT != null) for (p in open.toList()) {
            val c = candles[p.symbol]!![byTime[p.symbol]!![lastT] ?: continue]
            close(p, c.close, lastT, ExitReason.MANUAL)
        }
        if (curve.isNotEmpty()) curve[curve.lastIndex] = curve.last().copy(equity = cash)
        return summarize(trades, curve, exposureSum, warnings)
    }

    private fun summarize(trades: List<BacktestTrade>, curve: List<EquityPoint>, exposureSum: Double, warnings: List<String>): BacktestResult {
        val start = cfg.initialCapital
        val end = curve.lastOrNull()?.equity ?: start
        var peak = start
        var maxDd = 0.0
        for (p in curve) {
            peak = max(peak, p.equity)
            maxDd = max(maxDd, (peak - p.equity) / peak * 100)
        }
        val wins = trades.filter { it.pnl > 0 }
        val grossWin = wins.sumOf { it.pnl }
        val grossLoss = -trades.filter { it.pnl <= 0 }.sumOf { it.pnl }
        val w = warnings.toMutableList()
        if (trades.size < 30) w += "Solo ${trades.size} operaciones: muestra insuficiente para conclusiones estadísticas."
        return BacktestResult(
            trades = trades,
            equity = curve,
            totalReturnPct = (end / start - 1) * 100,
            btcReturnPct = curve.lastOrNull()?.let { (it.btcBenchmark / start - 1) * 100 } ?: 0.0,
            maxDrawdownPct = maxDd,
            winRatePct = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            profitFactor = if (grossLoss == 0.0) (if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0) else grossWin / grossLoss,
            avgR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.r } / trades.size,
            totalFees = trades.sumOf { it.fees },
            avgExposurePct = if (curve.isEmpty()) 0.0 else exposureSum / curve.size * 100,
            warnings = w,
        )
    }

    /**
     * Walk-forward evaluation: the period is split into consecutive out-of-sample windows that
     * are simulated independently with the SAME fixed parameters (no re-optimisation), showing
     * whether results are stable across regimes rather than driven by one lucky period.
     */
    fun walkForward(candles: Map<String, List<Candle>>, folds: Int, rules: Map<String, SymbolRules> = emptyMap(), membership: ((Long) -> Set<String>)? = null): List<BacktestResult> {
        val btc = candles["BTCUSDT"] ?: error("se requieren velas de BTCUSDT")
        if (btc.size <= cfg.warmupDays + folds) return emptyList()
        val usable = btc.drop(cfg.warmupDays)
        val size = usable.size / folds
        return (0 until folds).map { f ->
            val from = usable[f * size].openTime
            val to = usable[min(usable.lastIndex, (f + 1) * size - 1)].openTime
            run(candles, rules, membership, from, to)
        }
    }

    private fun syntheticRules(symbol: String, price: Double): SymbolRules {
        val tick = BigDecimal.ONE.movePointLeft(max(2, 6 - kotlin.math.log10(max(price, 1e-9)).toInt()).coerceAtMost(8))
        return SymbolRules(
            symbol, symbol.removeSuffix("USDT"), "USDT", "TRADING", true,
            setOf("MARKET", "LIMIT", "STOP_LOSS_LIMIT", "STOP_LOSS"),
            priceFilter = PriceFilter(BigDecimal.ZERO, BigDecimal.ZERO, tick),
            lotSize = LotSize(BigDecimal("0.00000001"), BigDecimal("9000000"), BigDecimal("0.00000001")),
            notional = Notional(BigDecimal("5"), true, null, false),
        )
    }

    private fun bd(d: Double) = BigDecimal.valueOf(d)
}
