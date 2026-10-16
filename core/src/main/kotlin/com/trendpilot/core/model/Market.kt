package com.trendpilot.core.model

import java.math.BigDecimal

/** A closed (or in-progress) candle. Times are epoch milliseconds, UTC. */
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteVolume: Double = volume * close,
) {
    fun isClosedAt(nowMs: Long): Boolean = closeTime < nowMs
}

enum class Interval(val binance: String, val millis: Long) {
    H4("4h", 4 * 3_600_000L),
    D1("1d", 86_400_000L),
}

/** Separate ledgers per mode. Simulations are never presented as real. */
enum class TradingMode { BACKTEST, PAPER, REAL }

enum class Side { BUY, SELL }

/** Order types actually used by the bot. All exist in Binance Spot `orderTypes`. */
enum class OrderType { MARKET, LIMIT, STOP_LOSS, STOP_LOSS_LIMIT }

data class PriceTick(val symbol: String, val price: BigDecimal, val eventTimeMs: Long)

data class Ticker24h(
    val symbol: String,
    val lastPrice: BigDecimal,
    val priceChangePercent: Double,
    val quoteVolume: BigDecimal,
    val closeTimeMs: Long,
)

data class AssetBalance(val asset: String, val free: BigDecimal, val locked: BigDecimal) {
    val total: BigDecimal get() = free + locked
}
