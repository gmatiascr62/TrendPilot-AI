package com.trendpilot.core.strategy

import com.trendpilot.core.indicators.IndicatorSnapshot
import com.trendpilot.core.indicators.Indicators
import com.trendpilot.core.model.Candle
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic swing trend-following rules on DAILY candles (4h candles are only used to
 * confirm short-term recovery). Parameters are deliberately few and round to limit overfitting.
 */
data class StrategyConfig(
    val rsiEntryMin: Double = 50.0,
    val rsiEntryMax: Double = 70.0,
    val rsiOverbought: Double = 75.0,
    val maxExtensionAtr: Double = 3.0,
    val minRelVolume: Double = 0.8,
    val stopAtrBuffer: Double = 0.5,
    val minStopAtr: Double = 1.5,
    val maxStopAtr: Double = 3.5,
    val pullbackLookback: Int = 10,
    val pullbackTouchPct: Double = 1.5,
    val trailAtrMultiple: Double = 3.0,
    val trailMinStepAtr: Double = 0.5,
    val trailMinStepPct: Double = 1.0,
    val breakevenAfterR: Double = 1.5,
)

enum class MarketRegime { RISK_ON, NEUTRAL, RISK_OFF }

enum class SetupType { TREND_CONTINUATION, PULLBACK_RECOVERY }

data class EntrySignal(
    val symbol: String,
    val setup: SetupType,
    val referencePrice: Double,
    val stopPrice: Double,
    val atr: Double,
    val score: Double,
    val invalidation: String,
    val reasons: List<String>,
    val snapshot: IndicatorSnapshot,
)

data class Rejection(val symbol: String, val reasons: List<String>)

object RegimeClassifier {
    /** BTC daily trend: above SMA200 with SMA50 > SMA200 is risk-on; below SMA200 is risk-off. */
    fun classify(btcDaily: List<Candle>): MarketRegime {
        if (btcDaily.size < 210) return MarketRegime.NEUTRAL
        val s = IndicatorSnapshot.at(btcDaily)
        if (!s.isComplete) return MarketRegime.NEUTRAL
        return when {
            s.close < s.sma200 -> MarketRegime.RISK_OFF
            s.close > s.sma200 && s.sma50 > s.sma200 && s.close > s.sma50 -> MarketRegime.RISK_ON
            else -> MarketRegime.NEUTRAL
        }
    }
}

class TrendStrategy(private val cfg: StrategyConfig = StrategyConfig()) {

    /**
     * Evaluates [daily] candles (closed only) of [symbol]. Returns a signal or the reasons for
     * rejection. Never decides alone: the RiskEngine sizes and vetoes, and the AI may veto.
     */
    fun evaluate(symbol: String, daily: List<Candle>, regime: MarketRegime): Result {
        if (daily.size < 210) return Result.Rejected(Rejection(symbol, listOf("historial insuficiente (${daily.size} velas diarias < 210)")))
        val s = IndicatorSnapshot.at(daily)
        if (!s.isComplete) return Result.Rejected(Rejection(symbol, listOf("indicadores incompletos")))
        val why = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        if (regime == MarketRegime.RISK_OFF) why += "BTC en régimen bajista (bajo SMA200): sin compras nuevas"

        // 1. Confirmed uptrend structure.
        if (s.close <= s.sma50) why += "precio no supera SMA50"
        if (s.sma50 <= s.sma200) why += "SMA50 no supera SMA200"
        if (s.sma50 <= s.sma50TenAgo) why += "SMA50 sin pendiente positiva"
        if (why.isEmpty()) reasons += "tendencia alcista: precio>SMA50>SMA200 con SMA50 ascendente"

        // 2. Avoid chasing.
        if (s.rsi14 > cfg.rsiOverbought) why += "RSI ${fmt(s.rsi14)} sobrecomprado"
        if (s.close > s.sma20 + cfg.maxExtensionAtr * s.atr14) why += "precio extendido >${cfg.maxExtensionAtr} ATR sobre SMA20"
        if (!s.relVolume.isNaN() && s.relVolume < cfg.minRelVolume) why += "volumen relativo bajo (${fmt(s.relVolume)})"

        // 3. Setup.
        val i = daily.lastIndex
        val sma20Series = Indicators.sma(daily.map { it.close }, 20)
        val touchedPullback = (max(0, i - cfg.pullbackLookback)..i).any { k ->
            !sma20Series[k].isNaN() && daily[k].low <= sma20Series[k] * (1 + cfg.pullbackTouchPct / 100)
        }
        val macdRising = s.macdHist > s.macdHistPrev
        val setup = when {
            touchedPullback && s.close > s.sma20 && s.close > daily[i - 1].high && s.rsiPrev < s.rsi14 &&
                s.rsi14 >= cfg.rsiEntryMin && macdRising -> SetupType.PULLBACK_RECOVERY
            s.close > s.sma20 && s.rsi14 in cfg.rsiEntryMin..cfg.rsiEntryMax && s.macdHist > 0 && macdRising -> SetupType.TREND_CONTINUATION
            else -> null
        }
        if (setup == null) why += "sin gatillo: ni continuación (RSI ${fmt(s.rsi14)}, MACD hist ${fmt(s.macdHist)}) ni recuperación de retroceso"
        else reasons += when (setup) {
            SetupType.PULLBACK_RECOVERY -> "retroceso a SMA20 con recuperación: cierre sobre máximo previo, RSI y MACD girando al alza"
            SetupType.TREND_CONTINUATION -> "continuación: RSI ${fmt(s.rsi14)} en zona 50–70, MACD hist positivo y creciente"
        }

        if (why.isNotEmpty() || setup == null) return Result.Rejected(Rejection(symbol, why))

        val stop = initialStop(s)
        val resistance = s.swingHigh
        if (resistance != null && resistance > s.close && (resistance - s.close) < 1.0 * s.atr14) {
            return Result.Rejected(Rejection(symbol, listOf("resistencia cercana en ${fmt(resistance)} (<1 ATR)")))
        }
        val score = score(s, setup, regime)
        return Result.Signal(
            EntrySignal(
                symbol = symbol,
                setup = setup,
                referencePrice = s.close,
                stopPrice = stop,
                atr = s.atr14,
                score = score,
                invalidation = "cierre diario bajo SMA50 (${fmt(s.sma50)}) o stop ${fmt(stop)}",
                reasons = reasons,
                snapshot = s,
            ),
        )
    }

    /** Stop below the last swing low with an ATR buffer, clamped to [minStopAtr, maxStopAtr]. */
    fun initialStop(s: IndicatorSnapshot): Double {
        val structural = (s.swingLow ?: (s.close - 2 * s.atr14)) - cfg.stopAtrBuffer * s.atr14
        val tightest = s.close - cfg.minStopAtr * s.atr14
        val widest = s.close - cfg.maxStopAtr * s.atr14
        return min(tightest, max(widest, structural))
    }

    private fun score(s: IndicatorSnapshot, setup: SetupType, regime: MarketRegime): Double {
        var score = 0.0
        score += ((s.sma50 / s.sma200) - 1).coerceIn(0.0, 0.5) * 100 // trend strength
        score += (70 - kotlin.math.abs(s.rsi14 - 60)) / 10 // momentum sweet spot near 60
        score += if (s.relVolume.isNaN()) 0.0 else (s.relVolume - 1).coerceIn(-0.5, 1.0) * 5
        score -= (s.volatility20 * 100).coerceIn(0.0, 10.0) // prefer calmer trends
        if (setup == SetupType.PULLBACK_RECOVERY) score += 2
        if (regime == MarketRegime.RISK_ON) score += 3
        return score
    }

    sealed interface Result {
        data class Signal(val signal: EntrySignal) : Result
        data class Rejected(val rejection: Rejection) : Result
    }
}

enum class ExitReason { STOP_HIT, TREND_BREAK, MOMENTUM_LOSS, AI_RECOMMENDATION, ROTATION, MANUAL, EMERGENCY, DEFENSIVE }

data class ExitAssessment(val deteriorated: Boolean, val reason: ExitReason?, val detail: String)

object ExitEvaluator {
    /**
     * Confirmed deterioration of an open position (daily closes only):
     * - close below SMA50, or
     * - two consecutive closes below SMA20 with negative MACD histogram.
     */
    fun assess(daily: List<Candle>): ExitAssessment {
        if (daily.size < 60) return ExitAssessment(false, null, "historial insuficiente")
        val now = IndicatorSnapshot.at(daily)
        val prev = IndicatorSnapshot.at(daily, daily.lastIndex - 1)
        if (!now.sma50.isNaN() && now.close < now.sma50) {
            return ExitAssessment(true, ExitReason.TREND_BREAK, "cierre ${fmt(now.close)} bajo SMA50 ${fmt(now.sma50)}")
        }
        if (now.close < now.sma20 && prev.close < prev.sma20 && now.macdHist < 0 && prev.macdHist < 0) {
            return ExitAssessment(true, ExitReason.MOMENTUM_LOSS, "dos cierres bajo SMA20 con MACD negativo")
        }
        return ExitAssessment(false, null, "tendencia intacta")
    }
}

/**
 * ATR chandelier trailing stop. Only ever raises the stop, and only by meaningful steps so we
 * don't churn orders on noise.
 */
class TrailingStopPolicy(private val cfg: StrategyConfig = StrategyConfig()) {
    data class Input(
        val entryPrice: Double,
        val initialStop: Double,
        val currentStop: Double,
        val highestHighSinceEntry: Double,
        val atr: Double,
        val lastClose: Double,
        val swingLow: Double?,
        val feeAndSlippageFraction: Double,
    )

    /** Returns the new stop or null when no change is warranted. Never returns a lower stop. */
    fun propose(i: Input): Double? {
        if (i.atr.isNaN() || i.atr <= 0) return null
        val r = i.entryPrice - i.initialStop
        var candidate = i.highestHighSinceEntry - cfg.trailAtrMultiple * i.atr
        if (i.swingLow != null && i.swingLow < i.lastClose) {
            candidate = max(candidate, min(i.swingLow - cfg.stopAtrBuffer * i.atr, i.lastClose - cfg.minStopAtr * i.atr))
        }
        if (r > 0 && i.highestHighSinceEntry >= i.entryPrice + cfg.breakevenAfterR * r) {
            val breakeven = i.entryPrice * (1 + 2 * i.feeAndSlippageFraction)
            candidate = max(candidate, min(breakeven, i.lastClose - cfg.minStopAtr * i.atr))
        }
        // Must stay below the market with room to breathe.
        candidate = min(candidate, i.lastClose - cfg.minStopAtr * i.atr * 0.5)
        val minStep = max(cfg.trailMinStepAtr * i.atr, i.currentStop * cfg.trailMinStepPct / 100)
        return if (candidate > i.currentStop + minStep) candidate else null
    }
}

internal fun fmt(d: Double): String = if (d.isNaN()) "n/d" else String.format(java.util.Locale.US, "%.4g", d)
