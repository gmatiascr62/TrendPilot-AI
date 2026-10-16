package com.trendpilot.core.execution

import com.trendpilot.core.model.AssetBalance
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.TradingMode
import java.math.BigDecimal

data class NewOrder(
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val quantity: BigDecimal,
    val clientOrderId: String,
    val price: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    /** GTC for resting orders; null for MARKET. */
    val timeInForce: String? = null,
)

data class Fill(val price: BigDecimal, val qty: BigDecimal, val commission: BigDecimal, val commissionAsset: String)

/** Order statuses as reported by Binance (see enums.md#order-status). */
object OrderStatus {
    const val NEW = "NEW"
    const val PENDING_NEW = "PENDING_NEW"
    const val PARTIALLY_FILLED = "PARTIALLY_FILLED"
    const val FILLED = "FILLED"
    const val CANCELED = "CANCELED"
    const val PENDING_CANCEL = "PENDING_CANCEL"
    const val REJECTED = "REJECTED"
    const val EXPIRED = "EXPIRED"
    const val EXPIRED_IN_MATCH = "EXPIRED_IN_MATCH"

    fun isWorking(s: String) = s == NEW || s == PARTIALLY_FILLED || s == PENDING_NEW
    fun isFinal(s: String) = s == FILLED || s == CANCELED || s == REJECTED || s == EXPIRED || s == EXPIRED_IN_MATCH
}

data class ExchangeOrder(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String,
    val side: Side,
    val type: String,
    val status: String,
    val origQty: BigDecimal,
    val executedQty: BigDecimal,
    val cumulativeQuoteQty: BigDecimal,
    val price: BigDecimal?,
    val stopPrice: BigDecimal?,
    val fills: List<Fill> = emptyList(),
    val updateTimeMs: Long,
)

data class CancelReplaceResult(
    val cancelSucceeded: Boolean,
    /** null when the new order was not attempted or failed. */
    val newOrder: ExchangeOrder?,
    val newOrderAttempted: Boolean,
    val errorMessage: String?,
)

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The exchange answered and refused the request. Safe: nothing happened. */
    class Rejected(val code: Int, message: String) : GatewayException("[$code] $message")

    /** Outcome unknown (timeout, -1007, connection dropped after sending). Must query, never resend blindly. */
    class Unknown(message: String, cause: Throwable? = null) : GatewayException(message, cause)

    /** Too many requests. Back off; nothing was executed. */
    class RateLimited(val retryAfterMs: Long, message: String) : GatewayException(message)

    /** Request certainly not sent (e.g. no network, DNS failure before connecting). */
    class NotSent(message: String, cause: Throwable? = null) : GatewayException(message, cause)
}

/** Binance error codes we act on. See errors.md. */
object BinanceErrors {
    const val UNKNOWN_ORDER = -2013 // "Order does not exist."
    const val CANCEL_REJECTED = -2011
    const val NEW_ORDER_REJECTED = -2010
    const val TIMEOUT = -1007
    const val INVALID_TIMESTAMP = -1021
}

/**
 * Exchange access used by the coordinator. Implemented by the real Binance gateway and by the
 * paper broker, so the exact same execution code runs in paper and real modes.
 */
interface ExchangeGateway {
    val mode: TradingMode
    val quoteAsset: String get() = "USDT"

    suspend fun balances(): List<AssetBalance>
    suspend fun openOrders(symbol: String? = null): List<ExchangeOrder>

    /** Returns null only when the exchange states the order does not exist (-2013). */
    suspend fun queryOrder(symbol: String, clientOrderId: String): ExchangeOrder?
    suspend fun placeOrder(order: NewOrder): ExchangeOrder
    suspend fun cancelOrder(symbol: String, clientOrderId: String): ExchangeOrder

    /** POST /api/v3/order/cancelReplace with STOP_ON_FAILURE. Not atomic: see CancelReplaceResult. */
    suspend fun cancelReplace(symbol: String, cancelClientOrderId: String, newOrder: NewOrder): CancelReplaceResult

    /** PUT /api/v3/order/amend/keepPriority: only reduces quantity. */
    suspend fun amendQuantity(symbol: String, clientOrderId: String, newQuantity: BigDecimal): ExchangeOrder
    suspend fun lastPrice(symbol: String): PriceTick

    /** The request window used when signing; after it elapses an unconfirmed order cannot appear. */
    val recvWindowMs: Long get() = 5_000
}
