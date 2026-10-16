package com.trendpilot.core.execution

import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.strategy.ExitReason
import java.math.BigDecimal

enum class IntentKind(val code: Char) { ENTRY('E'), PROTECTIVE_STOP('S'), STOP_REPLACE('R'), EXIT('X'), PARTIAL_EXIT('P') }

enum class IntentStatus {
    /** Persisted, not yet sent. If found after a crash, it was never sent or is unknown: query. */
    PREPARED,
    UNKNOWN,
    WORKING,
    FILLED,
    PARTIALLY_FILLED_FINAL,
    REJECTED,
    CANCELED,
    NOT_PLACED,
    ;

    val resolved: Boolean get() = this != PREPARED && this != UNKNOWN
}

/** Written to storage BEFORE the order is sent (transactional intent). */
data class OrderIntent(
    val id: String,
    val mode: TradingMode,
    val symbol: String,
    val kind: IntentKind,
    val side: Side,
    val type: OrderType,
    val quantity: BigDecimal,
    val price: BigDecimal?,
    val stopPrice: BigDecimal?,
    val clientOrderId: String,
    val status: IntentStatus,
    val positionId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val exchangeOrderId: Long? = null,
    val executedQty: BigDecimal = BigDecimal.ZERO,
    val cumulativeQuote: BigDecimal = BigDecimal.ZERO,
    val note: String = "",
)

enum class PositionStatus { OPENING, UNPROTECTED, PROTECTED, CLOSING, CLOSED }

data class Position(
    val id: String,
    val mode: TradingMode,
    val symbol: String,
    val baseAsset: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryFeesQuote: BigDecimal,
    val costBasisQuote: BigDecimal,
    val initialStop: BigDecimal,
    val currentStop: BigDecimal,
    val stopType: OrderType,
    val stopLimitPrice: BigDecimal?,
    val stopClientOrderId: String?,
    val status: PositionStatus,
    val openedAtMs: Long,
    val highestPrice: BigDecimal,
    val setup: String,
    val entryReasons: String,
    val dustQty: BigDecimal = BigDecimal.ZERO,
    val realizedQuote: BigDecimal = BigDecimal.ZERO,
    val realizedFeesQuote: BigDecimal = BigDecimal.ZERO,
    val closedAtMs: Long? = null,
    val exitReason: ExitReason? = null,
)

data class ClosedTrade(
    val positionId: String,
    val mode: TradingMode,
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val feesQuote: BigDecimal,
    val pnlQuote: BigDecimal,
    val rMultiple: Double,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val setup: String,
    val exitReason: ExitReason,
)

interface IntentStore {
    suspend fun save(intent: OrderIntent)
    suspend fun byClientOrderId(clientOrderId: String): OrderIntent?
    suspend fun unresolved(mode: TradingMode): List<OrderIntent>
}

interface PositionStore {
    suspend fun save(position: Position)
    suspend fun get(id: String): Position?
    suspend fun open(mode: TradingMode): List<Position>
    suspend fun recordTrade(trade: ClosedTrade)
}

enum class EventLevel { INFO, TRADE, STOP, WARNING, SECURITY, EMERGENCY }

/** Audit + notification sink. Must never receive secrets. */
data class EngineEvent(
    val level: EventLevel,
    val mode: TradingMode,
    val title: String,
    val detail: String,
    val symbol: String? = null,
    val timestampMs: Long,
    val notify: Boolean = level != EventLevel.INFO,
)

fun interface EventSink { suspend fun emit(event: EngineEvent) }

fun interface Clock { fun nowMs(): Long }

object SystemClock : Clock { override fun nowMs() = System.currentTimeMillis() }

/** In-memory stores for tests, backtests and the paper engine before Room is wired. */
class InMemoryIntentStore : IntentStore {
    val items = linkedMapOf<String, OrderIntent>()
    override suspend fun save(intent: OrderIntent) { items[intent.id] = intent }
    override suspend fun byClientOrderId(clientOrderId: String) = items.values.lastOrNull { it.clientOrderId == clientOrderId }
    override suspend fun unresolved(mode: TradingMode) = items.values.filter { it.mode == mode && !it.status.resolved }
}

class InMemoryPositionStore : PositionStore {
    val items = linkedMapOf<String, Position>()
    val trades = mutableListOf<ClosedTrade>()
    override suspend fun save(position: Position) { items[position.id] = position }
    override suspend fun get(id: String) = items[id]
    override suspend fun open(mode: TradingMode) = items.values.filter { it.mode == mode && it.status != PositionStatus.CLOSED }
    override suspend fun recordTrade(trade: ClosedTrade) { trades += trade }
}
