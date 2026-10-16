package com.trendpilot.app.data

import com.trendpilot.core.ai.AiUsage
import com.trendpilot.core.ai.UsageLedger
import com.trendpilot.core.engine.DecisionLog
import com.trendpilot.core.engine.DecisionRecord
import com.trendpilot.core.engine.EngineState
import com.trendpilot.core.engine.EngineStateStore
import com.trendpilot.core.execution.ClosedTrade
import com.trendpilot.core.execution.ExchangeOrder
import com.trendpilot.core.execution.Fill
import com.trendpilot.core.execution.IntentKind
import com.trendpilot.core.execution.IntentStatus
import com.trendpilot.core.execution.IntentStore
import com.trendpilot.core.execution.OrderIntent
import com.trendpilot.core.execution.Position
import com.trendpilot.core.execution.PositionStatus
import com.trendpilot.core.execution.PositionStore
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.paper.PaperBroker
import com.trendpilot.core.risk.ConnectivityState
import com.trendpilot.core.risk.EquityGuardState
import com.trendpilot.core.strategy.ExitReason
import com.trendpilot.core.universe.UniverseResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RoomIntentStore(private val dao: IntentDao) : IntentStore {
    override suspend fun save(intent: OrderIntent) = dao.upsert(intent.toEntity())
    override suspend fun byClientOrderId(clientOrderId: String) = dao.byClientOrderId(clientOrderId)?.toModel()
    override suspend fun unresolved(mode: TradingMode) = dao.unresolved(mode.name).map { it.toModel() }

    private fun OrderIntent.toEntity() = IntentEntity(id, mode.name, symbol, kind.name, side.name, type.name, quantity, price, stopPrice,
        clientOrderId, status.name, positionId, createdAtMs, updatedAtMs, exchangeOrderId, executedQty, cumulativeQuote, note)

    private fun IntentEntity.toModel() = OrderIntent(id, TradingMode.valueOf(mode), symbol, IntentKind.valueOf(kind), Side.valueOf(side),
        OrderType.valueOf(type), quantity, price, stopPrice, clientOrderId, IntentStatus.valueOf(status), positionId, createdAtMs, updatedAtMs,
        exchangeOrderId, executedQty, cumulativeQuote, note)
}

class RoomPositionStore(private val dao: PositionDao, private val trades: TradeDao) : PositionStore {
    override suspend fun save(position: Position) = dao.upsert(position.toEntity())
    override suspend fun get(id: String) = dao.get(id)?.toModel()
    override suspend fun open(mode: TradingMode) = dao.open(mode.name).map { it.toModel() }
    override suspend fun recordTrade(trade: ClosedTrade) = trades.insert(
        TradeEntity(0, trade.positionId, trade.mode.name, trade.symbol, trade.quantity, trade.entryPrice, trade.exitPrice, trade.feesQuote,
            trade.pnlQuote, trade.rMultiple, trade.openedAtMs, trade.closedAtMs, trade.setup, trade.exitReason.name),
    )

    companion object {
        fun Position.toEntity() = PositionEntity(id, mode.name, symbol, baseAsset, quantity, entryPrice, entryFeesQuote, costBasisQuote, initialStop,
            currentStop, stopType.name, stopLimitPrice, stopClientOrderId, status.name, openedAtMs, highestPrice, setup, entryReasons, dustQty,
            realizedQuote, realizedFeesQuote, closedAtMs, exitReason?.name)

        fun PositionEntity.toModel() = Position(id, TradingMode.valueOf(mode), symbol, baseAsset, quantity, entryPrice, entryFeesQuote, costBasisQuote,
            initialStop, currentStop, OrderType.valueOf(stopType), stopLimitPrice, stopClientOrderId, PositionStatus.valueOf(status), openedAtMs,
            highestPrice, setup, entryReasons, dustQty, realizedQuote, realizedFeesQuote, closedAtMs, exitReason?.let { ExitReason.valueOf(it) })
    }
}

class RoomDecisionLog(private val dao: DecisionDao) : DecisionLog {
    override suspend fun record(d: DecisionRecord) =
        dao.insert(DecisionEntity(d.id, d.mode.name, d.timestampMs, d.kind, d.symbol, d.summary, d.details, d.aiProvider))
}

class RoomUsageLedger(private val dao: AiUsageDao) : UsageLedger {
    override suspend fun spentThisMonthUsd(provider: String, nowMs: Long): Double {
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
            .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return dao.spentSince(provider, start)
    }

    override suspend fun record(usage: AiUsage, nowMs: Long, ok: Boolean, error: String?) =
        dao.insert(AiUsageEntity(0, usage.provider, usage.model, usage.inputTokens, usage.outputTokens, usage.costUsd, nowMs, ok, error?.take(500)))
}

// ---------------------------------------------------------------- engine state (JSON in kv)

@Serializable
private data class EngineStateDto(
    val mode: String, val strategyRunning: Boolean, val entriesPausedByUser: Boolean, val emergency: Boolean, val emergencyReason: String?,
    val reconciliationClean: Boolean, val realModeArmed: Boolean, val blockedSymbols: Set<String>, val defensiveActive: Boolean,
    val defensiveTriggers: List<String>, val aiAvailable: Boolean, val aiDefensive: Boolean, val aiLastError: String?,
    val guard: GuardDto?, val conn: ConnDto, val universe: UniverseDto?, val lastAnalysisMs: Long?, val lastMonitorMs: Long?,
    val lastEquity: String?, val lastExposurePct: Double?,
)

@Serializable private data class GuardDto(val peak: String, val dayStart: String, val day: Long, val dd: Boolean, val daily: Boolean)
@Serializable private data class ConnDto(val connected: Boolean, val since: Long?, val lastOk: Long?, val manual: Boolean)
@Serializable private data class UniverseDto(val selected: List<String>, val excluded: List<List<String>>, val at: Long, val source: String)

class KvEngineStateStore(private val kv: KvDao) : EngineStateStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(mode: TradingMode): EngineState? {
        val raw = kv.get("engine_state_${mode.name}") ?: return null
        val d = runCatching { json.decodeFromString(EngineStateDto.serializer(), raw) }.getOrNull() ?: return null
        return EngineState(
            mode = TradingMode.valueOf(d.mode), strategyRunning = d.strategyRunning, entriesPausedByUser = d.entriesPausedByUser,
            emergency = d.emergency, emergencyReason = d.emergencyReason, reconciliationClean = d.reconciliationClean,
            realModeArmed = d.realModeArmed, blockedSymbols = d.blockedSymbols, defensiveActive = d.defensiveActive,
            defensiveTriggers = d.defensiveTriggers, aiAvailable = d.aiAvailable, aiDefensive = d.aiDefensive, aiLastError = d.aiLastError,
            equityGuard = d.guard?.let { EquityGuardState(BigDecimal(it.peak), BigDecimal(it.dayStart), it.day, it.dd, it.daily) },
            connectivity = ConnectivityState(d.conn.connected, d.conn.since, d.conn.lastOk, d.conn.manual),
            universe = d.universe?.let { u -> UniverseResult(u.selected, u.excluded.map { it[0] to it[1] }, u.at, u.source) },
            lastAnalysisMs = d.lastAnalysisMs, lastMonitorMs = d.lastMonitorMs, lastEquity = d.lastEquity?.let(::BigDecimal),
            lastExposurePct = d.lastExposurePct,
        )
    }

    override suspend fun save(state: EngineState) {
        val s = state
        val d = EngineStateDto(
            s.mode.name, s.strategyRunning, s.entriesPausedByUser, s.emergency, s.emergencyReason, s.reconciliationClean, s.realModeArmed,
            s.blockedSymbols, s.defensiveActive, s.defensiveTriggers, s.aiAvailable, s.aiDefensive, s.aiLastError,
            s.equityGuard?.let { GuardDto(it.peakEquity.toPlainString(), it.dayStartEquity.toPlainString(), it.utcDay, it.drawdownLocked, it.dailyLossLocked) },
            ConnDto(s.connectivity.connected, s.connectivity.disconnectedSinceMs, s.connectivity.lastOkMs, s.connectivity.needsManualResume),
            s.universe?.let { u -> UniverseDto(u.selected, u.excluded.map { listOf(it.first, it.second) }, u.generatedAtMs, u.source) },
            s.lastAnalysisMs, s.lastMonitorMs, s.lastEquity?.toPlainString(), s.lastExposurePct,
        )
        kv.put(KvEntity("engine_state_${s.mode.name}", json.encodeToString(EngineStateDto.serializer(), d)))
    }
}

// ---------------------------------------------------------------- paper broker state

@Serializable private data class FillDto(val price: String, val qty: String, val commission: String, val asset: String)
@Serializable private data class OrderDto(
    val symbol: String, val orderId: Long, val cid: String, val side: String, val type: String, val status: String, val origQty: String,
    val executedQty: String, val cumQuote: String, val price: String?, val stopPrice: String?, val fills: List<FillDto>, val updateTime: Long,
)
@Serializable private data class PaperDto(val free: Map<String, String>, val locked: Map<String, String>, val orders: List<OrderDto>, val nextId: Long)

object PaperStateCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(s: PaperBroker.State): String = json.encodeToString(
        PaperDto.serializer(),
        PaperDto(
            s.free.mapValues { it.value.toPlainString() }, s.locked.mapValues { it.value.toPlainString() },
            s.orders.map { o ->
                OrderDto(o.symbol, o.orderId, o.clientOrderId, o.side.name, o.type, o.status, o.origQty.toPlainString(), o.executedQty.toPlainString(),
                    o.cumulativeQuoteQty.toPlainString(), o.price?.toPlainString(), o.stopPrice?.toPlainString(),
                    o.fills.map { FillDto(it.price.toPlainString(), it.qty.toPlainString(), it.commission.toPlainString(), it.commissionAsset) }, o.updateTimeMs)
            },
            s.nextOrderId,
        ),
    )

    fun decode(raw: String): PaperBroker.State {
        val d = json.decodeFromString(PaperDto.serializer(), raw)
        return PaperBroker.State(
            d.free.mapValues { BigDecimal(it.value) }, d.locked.mapValues { BigDecimal(it.value) },
            d.orders.map { o ->
                ExchangeOrder(o.symbol, o.orderId, o.cid, Side.valueOf(o.side), o.type, o.status, BigDecimal(o.origQty), BigDecimal(o.executedQty),
                    BigDecimal(o.cumQuote), o.price?.let(::BigDecimal), o.stopPrice?.let(::BigDecimal),
                    o.fills.map { Fill(BigDecimal(it.price), BigDecimal(it.qty), BigDecimal(it.commission), it.asset) }, o.updateTime)
            },
            d.nextId,
        )
    }
}
