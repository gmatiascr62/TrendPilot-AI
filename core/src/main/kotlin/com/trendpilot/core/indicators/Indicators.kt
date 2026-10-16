package com.trendpilot.core.indicators

import com.trendpilot.core.model.Candle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Indicator math. Every function returns a list aligned with its input; positions without
 * enough history are `Double.NaN`. Value i only uses inputs 0..i (no look-ahead).
 */
object Indicators {
    fun sma(values: List<Double>, period: Int): List<Double> {
        require(period > 0)
        val out = MutableList(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): List<Double> {
        require(period > 0)
        val out = MutableList(values.size) { Double.NaN }
        if (values.size < period) return out
        val k = 2.0 / (period + 1)
        var prev = values.subList(0, period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's RSI. */
    fun rsi(closes: List<Double>, period: Int = 14): List<Double> {
        val out = MutableList(closes.size) { Double.NaN }
        if (closes.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = rsiOf(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
            out[i] = rsiOf(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiOf(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) (if (avgGain == 0.0) 50.0 else 100.0) else 100 - 100 / (1 + avgGain / avgLoss)

    data class Macd(val macd: List<Double>, val signal: List<Double>, val histogram: List<Double>)

    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd {
        val f = ema(closes, fast)
        val s = ema(closes, slow)
        val line = closes.indices.map { f[it] - s[it] }
        val firstValid = line.indexOfFirst { !it.isNaN() }
        val signal = MutableList(closes.size) { Double.NaN }
        if (firstValid >= 0) {
            val sig = ema(line.subList(firstValid, line.size), signalPeriod)
            sig.forEachIndexed { i, v -> signal[firstValid + i] = v }
        }
        val hist = closes.indices.map { line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    fun trueRange(candles: List<Candle>): List<Double> = candles.mapIndexed { i, c ->
        if (i == 0) c.high - c.low
        else maxOf(c.high - c.low, abs(c.high - candles[i - 1].close), abs(c.low - candles[i - 1].close))
    }

    /** Wilder's ATR. */
    fun atr(candles: List<Candle>, period: Int = 14): List<Double> {
        val tr = trueRange(candles)
        val out = MutableList(candles.size) { Double.NaN }
        if (candles.size < period) return out
        var prev = tr.subList(0, period).average()
        out[period - 1] = prev
        for (i in period until candles.size) {
            prev = (prev * (period - 1) + tr[i]) / period
            out[i] = prev
        }
        return out
    }

    /** Volume relative to its own moving average (1.0 = average). */
    fun relativeVolume(candles: List<Candle>, period: Int = 20): List<Double> {
        val vol = candles.map { it.quoteVolume }
        val avg = sma(vol, period)
        return vol.indices.map { if (avg[it].isNaN() || avg[it] == 0.0) Double.NaN else vol[it] / avg[it] }
    }

    /** Lowest low of the [lookback] candles ending at index [i] (inclusive). */
    fun lowestLow(candles: List<Candle>, i: Int, lookback: Int): Double =
        candles.subList(max(0, i - lookback + 1), i + 1).minOf { it.low }

    fun highestHigh(candles: List<Candle>, fromIndex: Int, toIndex: Int): Double =
        candles.subList(max(0, fromIndex), toIndex + 1).maxOf { it.high }

    /**
     * Most recent confirmed swing low (pivot) before index [i]: a low lower than the
     * [strength] candles on each side. Only pivots fully confirmed by index i are used.
     */
    fun lastSwingLow(candles: List<Candle>, i: Int, strength: Int = 3, maxLookback: Int = 40): Double? {
        val start = max(strength, i - maxLookback)
        for (p in (i - strength) downTo start) {
            val l = candles[p].low
            val left = (p - strength until p).all { candles[it].low > l }
            val right = (p + 1..p + strength).all { candles[it].low >= l }
            if (left && right) return l
        }
        return null
    }

    /** Most recent confirmed swing high (resistance) before index [i]. */
    fun lastSwingHigh(candles: List<Candle>, i: Int, strength: Int = 3, maxLookback: Int = 60): Double? {
        val start = max(strength, i - maxLookback)
        for (p in (i - strength) downTo start) {
            val h = candles[p].high
            val left = (p - strength until p).all { candles[it].high < h }
            val right = (p + 1..p + strength).all { candles[it].high <= h }
            if (left && right) return h
        }
        return null
    }

    fun logReturns(closes: List<Double>): List<Double> =
        (1 until closes.size).map { ln(closes[it] / closes[it - 1]) }

    /** Annualised-free stdev of log returns over the last [period] values. */
    fun volatility(closes: List<Double>, period: Int = 20): Double {
        val r = logReturns(closes).takeLast(period)
        if (r.size < 2) return Double.NaN
        val m = r.average()
        return sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
    }

    /** Pearson correlation of the last [period] log returns of two aligned series. */
    fun correlation(a: List<Double>, b: List<Double>, period: Int = 30): Double {
        val ra = logReturns(a).takeLast(period)
        val rb = logReturns(b).takeLast(period)
        val n = minOf(ra.size, rb.size)
        if (n < 3) return Double.NaN
        val x = ra.takeLast(n)
        val y = rb.takeLast(n)
        val mx = x.average()
        val my = y.average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (k in 0 until n) {
            cov += (x[k] - mx) * (y[k] - my)
            vx += (x[k] - mx) * (x[k] - mx)
            vy += (y[k] - my) * (y[k] - my)
        }
        return if (vx == 0.0 || vy == 0.0) Double.NaN else cov / sqrt(vx * vy)
    }
}

/** Snapshot of indicator values at one candle index, consumed by the strategy and the AI prompt. */
data class IndicatorSnapshot(
    val index: Int,
    val close: Double,
    val sma20: Double,
    val sma50: Double,
    val sma200: Double,
    val sma50TenAgo: Double,
    val rsi14: Double,
    val rsiPrev: Double,
    val macdHist: Double,
    val macdHistPrev: Double,
    val atr14: Double,
    val relVolume: Double,
    val swingLow: Double?,
    val swingHigh: Double?,
    val volatility20: Double,
    val change24hPct: Double,
) {
    val isComplete: Boolean
        get() = listOf(sma20, sma50, sma200, sma50TenAgo, rsi14, rsiPrev, macdHist, macdHistPrev, atr14).none { it.isNaN() }

    companion object {
        /** Computes the snapshot at [i] using only candles[0..i]. */
        fun at(candles: List<Candle>, i: Int = candles.lastIndex): IndicatorSnapshot {
            val view = candles.subList(0, i + 1)
            val closes = view.map { it.close }
            val sma20 = Indicators.sma(closes, 20)
            val sma50 = Indicators.sma(closes, 50)
            val sma200 = Indicators.sma(closes, 200)
            val rsi = Indicators.rsi(closes)
            val macd = Indicators.macd(closes)
            val atr = Indicators.atr(view)
            val rv = Indicators.relativeVolume(view)
            val last = view.lastIndex
            fun prev(l: List<Double>, back: Int) = if (last - back >= 0) l[last - back] else Double.NaN
            return IndicatorSnapshot(
                index = i,
                close = closes[last],
                sma20 = sma20[last],
                sma50 = sma50[last],
                sma200 = sma200[last],
                sma50TenAgo = prev(sma50, 10),
                rsi14 = rsi[last],
                rsiPrev = prev(rsi, 1),
                macdHist = macd.histogram[last],
                macdHistPrev = prev(macd.histogram, 1),
                atr14 = atr[last],
                relVolume = rv[last],
                swingLow = Indicators.lastSwingLow(view, last),
                swingHigh = Indicators.lastSwingHigh(view, last),
                volatility20 = Indicators.volatility(closes),
                change24hPct = if (last >= 1) (closes[last] / closes[last - 1] - 1) * 100 else Double.NaN,
            )
        }
    }
}
