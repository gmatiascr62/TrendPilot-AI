package com.trendpilot.core.paper

import com.trendpilot.core.execution.BinanceErrors
import com.trendpilot.core.execution.CancelReplaceResult
import com.trendpilot.core.execution.Clock
import com.trendpilot.core.execution.ExchangeGateway
import com.trendpilot.core.execution.ExchangeOrder
import com.trendpilot.core.execution.Fill
import com.trendpilot.core.execution.GatewayException
import com.trendpilot.core.execution.NewOrder
import com.trendpilot.core.execution.OrderStatus
import com.trendpilot.core.execution.SystemClock
import com.trendpilot.core.filters.FilterValidator
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.AssetBalance
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.TradingMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.MathContext

/**
 * Simulated Spot exchange with real prices and simulated orders. It applies the same filters,
 * fees and a conservative slippage, keeps balances locked by resting stops, and triggers stops
 * on price updates. Fault injection lets tests reproduce timeouts, rejections and partial fills.
 */
class PaperBroker(
    initialQuote: BigDecimal,
    private val rules: suspend (String) -> SymbolRules,
    private val feeRate: BigDecimal = BigDecimal("0.001"),
    private val slippage: BigDecimal = BigDecimal("0.001"),
    private val clock: Clock = SystemClock,
    override val mode: TradingMode = TradingMode.PAPER,
) : ExchangeGateway {

    /** Faults consumed by the next matching call (tests and "chaos" paper runs). */
    sealed interface Fault {
        /** The order is executed but the caller gets a timeout. */
        data object TimeoutAfterExecute : Fault
        /** The request never reaches the exchange but the caller gets a timeout. */
        data object TimeoutBeforeExecute : Fault
        data class Reject(val code: Int, val msg: String) : Fault
        /** MARKET order fills only this fraction. */
        data class PartialFill(val fraction: BigDecimal) : Fault
        data object NetworkDown : Fault
    }

    private val lock = Mutex()
    private val mc = MathContext.DECIMAL64
    private val free = linkedMapOf<String, BigDecimal>()
    private val locked = linkedMapOf<String, BigDecimal>()
    private val prices = mutableMapOf<String, PriceTick>()
    private val orders = linkedMapOf<String, ExchangeOrder>() // by clientOrderId
    private var nextOrderId = 1L
    val placeFaults = ArrayDeque<Fault>()
    val cancelFaults = ArrayDeque<Fault>()
    val queryFaults = ArrayDeque<Fault>()
    override val quoteAsset = "USDT"

    init { free[quoteAsset] = initialQuote }

    /** Serializable snapshot so a simulated account survives process death. */
    data class State(
        val free: Map<String, BigDecimal>,
        val locked: Map<String, BigDecimal>,
        val orders: List<ExchangeOrder>,
        val nextOrderId: Long,
    )

    suspend fun exportState(): State = lock.withLock { State(free.toMap(), locked.toMap(), orders.values.toList(), nextOrderId) }

    suspend fun importState(state: State) = lock.withLock {
        free.clear(); free.putAll(state.free)
        locked.clear(); locked.putAll(state.locked)
        orders.clear(); state.orders.forEach { orders[it.clientOrderId] = it }
        nextOrderId = state.nextOrderId
    }

    suspend fun updatePrice(tick: PriceTick) = lock.withLock {
        prices[tick.symbol] = tick
        triggerStops(tick)
    }

    /** Applies a full candle path (open → low → high → close) for backtest-like replays. */
    suspend fun updatePrices(symbol: String, path: List<BigDecimal>, timeMs: Long) {
        for (p in path) updatePrice(PriceTick(symbol, p, timeMs))
    }

    override suspend fun lastPrice(symbol: String): PriceTick = lock.withLock {
        prices[symbol] ?: throw GatewayException.Rejected(-1121, "Sin precio para $symbol")
    }

    override suspend fun balances(): List<AssetBalance> = lock.withLock {
        checkNetwork(queryFaults)
        (free.keys + locked.keys).distinct().map { AssetBalance(it, free[it] ?: BigDecimal.ZERO, locked[it] ?: BigDecimal.ZERO) }
    }

    override suspend fun openOrders(symbol: String?): List<ExchangeOrder> = lock.withLock {
        orders.values.filter { OrderStatus.isWorking(it.status) && (symbol == null || it.symbol == symbol) }
    }

    override suspend fun queryOrder(symbol: String, clientOrderId: String): ExchangeOrder? = lock.withLock {
        checkNetwork(queryFaults)
        orders[clientOrderId]?.takeIf { it.symbol == symbol }
    }

    override suspend fun placeOrder(order: NewOrder): ExchangeOrder = lock.withLock {
        when (val f = placeFaults.removeFirstOrNull()) {
            Fault.TimeoutBeforeExecute -> throw GatewayException.Unknown("timeout simulado (no ejecutada)")
            Fault.NetworkDown -> throw GatewayException.NotSent("red simulada caída")
            is Fault.Reject -> throw GatewayException.Rejected(f.code, f.msg)
            Fault.TimeoutAfterExecute -> {
                execute(order, null)
                throw GatewayException.Unknown("timeout simulado (ejecutada)")
            }
            is Fault.PartialFill -> execute(order, f.fraction)
            null -> execute(order, null)
        }
    }

    override suspend fun cancelOrder(symbol: String, clientOrderId: String): ExchangeOrder = lock.withLock {
        when (val f = cancelFaults.removeFirstOrNull()) {
            is Fault.Reject -> throw GatewayException.Rejected(f.code, f.msg)
            Fault.TimeoutBeforeExecute, Fault.NetworkDown -> throw GatewayException.Unknown("timeout simulado")
            else -> Unit
        }
        cancelInternal(symbol, clientOrderId)
    }

    override suspend fun cancelReplace(symbol: String, cancelClientOrderId: String, newOrder: NewOrder): CancelReplaceResult = lock.withLock {
        try {
            when (val f = cancelFaults.removeFirstOrNull()) {
                is Fault.Reject -> throw GatewayException.Rejected(f.code, f.msg)
                else -> Unit
            }
            cancelInternal(symbol, cancelClientOrderId)
        } catch (e: GatewayException.Rejected) {
            return@withLock CancelReplaceResult(false, null, false, e.message)
        }
        try {
            when (val f = placeFaults.removeFirstOrNull()) {
                is Fault.Reject -> throw GatewayException.Rejected(f.code, f.msg)
                else -> Unit
            }
            CancelReplaceResult(true, execute(newOrder, null), true, null)
        } catch (e: GatewayException.Rejected) {
            CancelReplaceResult(true, null, true, e.message)
        }
    }

    override suspend fun amendQuantity(symbol: String, clientOrderId: String, newQuantity: BigDecimal): ExchangeOrder = lock.withLock {
        val o = orders[clientOrderId]?.takeIf { OrderStatus.isWorking(it.status) }
            ?: throw GatewayException.Rejected(BinanceErrors.UNKNOWN_ORDER, "Order does not exist.")
        require(newQuantity < o.origQty && newQuantity.signum() > 0)
        val base = rules(symbol).baseAsset
        val released = o.origQty - newQuantity
        locked[base] = (locked[base] ?: BigDecimal.ZERO) - released
        free[base] = (free[base] ?: BigDecimal.ZERO) + released
        val amended = o.copy(origQty = newQuantity, updateTimeMs = clock.nowMs())
        orders[clientOrderId] = amended
        amended
    }

    // ----------------------------------------------------------------- internals (lock held)

    private fun checkNetwork(q: ArrayDeque<Fault>) {
        if (q.firstOrNull() == Fault.NetworkDown) {
            q.removeFirst()
            throw GatewayException.NotSent("red simulada caída")
        }
    }

    private suspend fun execute(order: NewOrder, partial: BigDecimal?): ExchangeOrder {
        val r = rules(order.symbol)
        val existing = orders[order.clientOrderId]
        if (existing != null && OrderStatus.isWorking(existing.status)) {
            throw GatewayException.Rejected(-2010, "Duplicate clientOrderId among open orders")
        }
        val tick = prices[order.symbol] ?: throw GatewayException.Rejected(-1121, "Sin precio para ${order.symbol}")
        val violations = FilterValidator.validate(r, order.type, order.side, order.quantity, order.price, order.stopPrice, tick.price)
        if (violations.isNotEmpty()) throw GatewayException.Rejected(-1013, "Filter failure: ${violations.first().filter}")
        val id = nextOrderId++
        val now = clock.nowMs()
        return when (order.type) {
            OrderType.MARKET -> {
                val qty = partial?.let { com.trendpilot.core.filters.FilterMath.floorToStep(order.quantity * it, r.lotSize?.stepSize ?: BigDecimal.ZERO) } ?: order.quantity
                val o = fillMarket(order, r, id, qty, tick.price, now)
                orders[order.clientOrderId] = o
                o
            }
            OrderType.STOP_LOSS, OrderType.STOP_LOSS_LIMIT -> {
                require(order.side == Side.SELL) { "solo stops de venta" }
                if (tick.price <= order.stopPrice!!) throw GatewayException.Rejected(-2010, "Stop price would trigger immediately.")
                val have = free[r.baseAsset] ?: BigDecimal.ZERO
                if (have < order.quantity) throw GatewayException.Rejected(-2010, "Account has insufficient balance for requested action.")
                free[r.baseAsset] = have - order.quantity
                locked[r.baseAsset] = (locked[r.baseAsset] ?: BigDecimal.ZERO) + order.quantity
                val o = ExchangeOrder(order.symbol, id, order.clientOrderId, order.side, order.type.name, OrderStatus.NEW, order.quantity,
                    BigDecimal.ZERO, BigDecimal.ZERO, order.price, order.stopPrice, emptyList(), now)
                orders[order.clientOrderId] = o
                o
            }
            OrderType.LIMIT -> throw GatewayException.Rejected(-1013, "LIMIT no simulado")
        }
    }

    private fun fillMarket(order: NewOrder, r: SymbolRules, id: Long, qty: BigDecimal, price: BigDecimal, now: Long): ExchangeOrder {
        val px = if (order.side == Side.BUY) price * (BigDecimal.ONE + slippage) else price * (BigDecimal.ONE - slippage)
        val quoteAmt = (qty * px).round(mc)
        if (order.side == Side.BUY) {
            val cash = free[quoteAsset] ?: BigDecimal.ZERO
            if (cash < quoteAmt) throw GatewayException.Rejected(-2010, "Account has insufficient balance for requested action.")
            free[quoteAsset] = cash - quoteAmt
            val commission = (qty * feeRate).round(mc)
            free[r.baseAsset] = (free[r.baseAsset] ?: BigDecimal.ZERO) + qty - commission
            return ExchangeOrder(order.symbol, id, order.clientOrderId, order.side, "MARKET", if (qty < order.quantity) OrderStatus.EXPIRED else OrderStatus.FILLED,
                order.quantity, qty, quoteAmt, null, null, listOf(Fill(px, qty, commission, r.baseAsset)), now)
        } else {
            val have = free[r.baseAsset] ?: BigDecimal.ZERO
            if (have < qty) throw GatewayException.Rejected(-2010, "Account has insufficient balance for requested action.")
            free[r.baseAsset] = have - qty
            val commission = (quoteAmt * feeRate).round(mc)
            free[quoteAsset] = (free[quoteAsset] ?: BigDecimal.ZERO) + quoteAmt - commission
            return ExchangeOrder(order.symbol, id, order.clientOrderId, order.side, "MARKET", OrderStatus.FILLED,
                order.quantity, qty, quoteAmt, null, null, listOf(Fill(px, qty, commission, quoteAsset)), now)
        }
    }

    private suspend fun cancelInternal(symbol: String, clientOrderId: String): ExchangeOrder {
        val o = orders[clientOrderId]?.takeIf { it.symbol == symbol && OrderStatus.isWorking(it.status) }
            ?: throw GatewayException.Rejected(BinanceErrors.CANCEL_REJECTED, "Unknown order sent.")
        val base = rules(symbol).baseAsset
        locked[base] = (locked[base] ?: BigDecimal.ZERO) - o.origQty
        free[base] = (free[base] ?: BigDecimal.ZERO) + o.origQty
        val c = o.copy(status = OrderStatus.CANCELED, updateTimeMs = clock.nowMs())
        orders[clientOrderId] = c
        return c
    }

    private suspend fun triggerStops(tick: PriceTick) {
        val triggered = orders.values.filter { it.symbol == tick.symbol && OrderStatus.isWorking(it.status) && it.stopPrice != null && tick.price <= it.stopPrice }
        for (o in triggered) {
            val r = rules(o.symbol)
            val limit = o.price
            // Gaps: a STOP_LOSS fills at the (worse) market; a STOP_LOSS_LIMIT does not fill below its limit.
            if (limit != null && tick.price < limit) continue
            val px = (if (limit != null) limit.max(tick.price) else tick.price.min(o.stopPrice!!)) * (BigDecimal.ONE - slippage)
            val quoteAmt = (o.origQty * px).round(mc)
            locked[r.baseAsset] = (locked[r.baseAsset] ?: BigDecimal.ZERO) - o.origQty
            val commission = (quoteAmt * feeRate).round(mc)
            free[quoteAsset] = (free[quoteAsset] ?: BigDecimal.ZERO) + quoteAmt - commission
            orders[o.clientOrderId] = o.copy(status = OrderStatus.FILLED, executedQty = o.origQty, cumulativeQuoteQty = quoteAmt,
                fills = listOf(Fill(px, o.origQty, commission, quoteAsset)), updateTimeMs = tick.eventTimeMs)
        }
    }

    /** Equity in quote terms at current prices. */
    suspend fun equity(): BigDecimal = lock.withLock {
        var total = (free[quoteAsset] ?: BigDecimal.ZERO) + (locked[quoteAsset] ?: BigDecimal.ZERO)
        for (asset in (free.keys + locked.keys).distinct()) {
            if (asset == quoteAsset) continue
            val qty = (free[asset] ?: BigDecimal.ZERO) + (locked[asset] ?: BigDecimal.ZERO)
            val p = prices[asset + quoteAsset]?.price ?: continue
            total += qty * p
        }
        total
    }
}
