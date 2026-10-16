package com.trendpilot.core.ai

import com.trendpilot.core.indicators.IndicatorSnapshot
import java.math.BigDecimal
import java.math.MathContext

object AiFeatures {
    private val mc = MathContext(6)

    /** Rounds to 6 significant digits (fewer tokens) and maps missing values to 0.0. */
    fun clean(d: Double): Double = if (d.isNaN() || d.isInfinite()) 0.0 else BigDecimal(d).round(mc).toDouble()
    private fun cleanOrNull(d: Double?): Double? = d?.let { if (it.isNaN()) null else clean(it) }

    fun from(symbol: String, s: IndicatorSnapshot, corrToBtc: Double) = AssetFeatures(
        symbol = symbol,
        close = clean(s.close),
        sma20 = clean(s.sma20),
        sma50 = clean(s.sma50),
        sma200 = clean(s.sma200),
        rsi14 = clean(s.rsi14),
        macdHist = clean(s.macdHist),
        atr14 = clean(s.atr14),
        relVolume = clean(s.relVolume),
        change24hPct = clean(s.change24hPct),
        volatility20 = clean(s.volatility20),
        swingLow = cleanOrNull(s.swingLow),
        swingHigh = cleanOrNull(s.swingHigh),
        corrToBtc30d = clean(corrToBtc),
    )
}
