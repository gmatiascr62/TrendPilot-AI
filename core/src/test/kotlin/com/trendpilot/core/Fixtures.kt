package com.trendpilot.core

import com.trendpilot.core.filters.LotSize
import com.trendpilot.core.filters.Notional
import com.trendpilot.core.filters.PriceFilter
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.Candle
import kotlin.math.sin

object Fixtures {
    fun rules(
        symbol: String = "ETHUSDT",
        base: String = symbol.removeSuffix("USDT"),
        tick: String = "0.01",
        step: String = "0.0001",
        minNotional: String = "5",
        orderTypes: Set<String> = setOf("LIMIT", "LIMIT_MAKER", "MARKET", "STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"),
        cancelReplace: Boolean = true,
        amend: Boolean = true,
    ) = SymbolRules(
        symbol = symbol, baseAsset = base, quoteAsset = "USDT", status = "TRADING", isSpotTradingAllowed = true,
        orderTypes = orderTypes, otoAllowed = true, ocoAllowed = true, cancelReplaceAllowed = cancelReplace, amendAllowed = amend,
        priceFilter = PriceFilter("0.01".toBigDecimal(), "1000000".toBigDecimal(), tick.toBigDecimal()),
        lotSize = LotSize("0.0001".toBigDecimal(), "9000".toBigDecimal(), step.toBigDecimal()),
        marketLotSize = LotSize("0".toBigDecimal(), "1000".toBigDecimal(), "0".toBigDecimal()),
        notional = Notional(minNotional.toBigDecimal(), true, "9000000".toBigDecimal(), false),
    )

    /** Deterministic synthetic daily series: steady trend + oscillation, optional shock. */
    fun dailySeries(
        days: Int,
        start: Double = 100.0,
        dailyDrift: Double = 0.003,
        amplitude: Double = 0.04,
        startTime: Long = 1_600_000_000_000L,
        shockAt: Int? = null,
        shockPct: Double = -0.25,
    ): List<Candle> {
        val out = mutableListOf<Candle>()
        var level = start
        for (d in 0 until days) {
            level *= (1 + dailyDrift)
            if (shockAt != null && d == shockAt) level *= (1 + shockPct)
            val wave = 1 + amplitude * sin(d / 6.0)
            val close = level * wave
            val open = if (out.isEmpty()) close else out.last().close
            val high = maxOf(open, close) * 1.012
            val low = minOf(open, close) * 0.988
            val t = startTime + d * 86_400_000L
            out += Candle(t, open, high, low, close, 1000.0 + (d % 7) * 50, t + 86_399_999L)
        }
        return out
    }
}
