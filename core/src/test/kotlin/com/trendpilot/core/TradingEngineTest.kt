package com.trendpilot.core

import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiRouter
import com.trendpilot.core.ai.AiUsage
import com.trendpilot.core.ai.BudgetConfig
import com.trendpilot.core.ai.ProviderPricing
import com.trendpilot.core.ai.UsageLedger
import com.trendpilot.core.engine.DecisionRecord
import com.trendpilot.core.engine.EngineConfig
import com.trendpilot.core.engine.EngineState
import com.trendpilot.core.engine.EngineStateStore
import com.trendpilot.core.engine.MarketDataSource
import com.trendpilot.core.engine.TradingEngine
import com.trendpilot.core.execution.Clock
import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.ExecutionConfig
import com.trendpilot.core.execution.ExecutionCoordinator
import com.trendpilot.core.execution.InMemoryIntentStore
import com.trendpilot.core.execution.InMemoryPositionStore
import com.trendpilot.core.execution.PositionStatus
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.model.Ticker24h
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.paper.PaperBroker
import com.trendpilot.core.strategy.MarketRegime
import com.trendpilot.core.strategy.TrendStrategy
import com.trendpilot.core.universe.UniverseResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TradingEngineTest {
    /** Finds a deterministic series whose LAST closed candle produces an entry signal. */
    private val ethSeries: List<Candle> by lazy {
        (300..900).asSequence().map { Fixtures.dailySeries(it, start = 1500.0, dailyDrift = 0.003, amplitude = 0.0, noise = 0.025) }
            .first { TrendStrategy().evaluate("ETHUSDT", it, MarketRegime.RISK_ON) is TrendStrategy.Result.Signal }
    }
    private val btcSeries by lazy { Fixtures.dailySeries(ethSeries.size, start = 30000.0, dailyDrift = 0.003, amplitude = 0.01) }

    private inner class Env(scope: TestScope, aiBody: (() -> String)?) {
        val now = ethSeries.last().closeTime + 3_600_000L
        val clock = Clock { now + scope.testScheduler.currentTime }
        val rules = mapOf("ETHUSDT" to Fixtures.rules("ETHUSDT"), "BTCUSDT" to Fixtures.rules("BTCUSDT", step = "0.00001"))
        val broker = PaperBroker(BigDecimal("1000"), { rules.getValue(it) }, clock = clock, slippage = BigDecimal.ZERO)
        val positions = InMemoryPositionStore()
        val events = mutableListOf<EngineEvent>()
        val decisions = mutableListOf<DecisionRecord>()
        var saved: EngineState? = null
        val market = object : MarketDataSource {
            override suspend fun dailyCandles(symbol: String, count: Int) = if (symbol == "BTCUSDT") btcSeries else ethSeries
            override suspend fun rules(symbols: Collection<String>): Map<String, SymbolRules> = rules.filterKeys { it in symbols }
            override suspend fun tickers(symbols: Collection<String>) = symbols.associateWith { Ticker24h(it, BigDecimal.ONE, 0.5, BigDecimal("1e9"), now) }
        }
        val provider = object : AiProvider {
            override val name = "claude"
            override val model = "claude-opus-5-5"
            override suspend fun analyze(request: AiRequest): AiRawResponse {
                val snap = request.snapshot
                val body = aiBody?.invoke() ?: error("HTTP 529")
                return AiRawResponse(body.replace("SNAP", snap.snapshotId).replace("ASOF", snap.dataAsOf), AiUsage(name, model, 100, 10, 0.0))
            }
        }
        val ledger = object : UsageLedger {
            override suspend fun spentThisMonthUsd(provider: String, nowMs: Long) = 0.0
            override suspend fun record(usage: AiUsage, nowMs: Long, ok: Boolean, error: String?) {}
        }
        val engine = TradingEngine(
            broker, market, { UniverseResult(listOf("ETHUSDT"), emptyList(), now, "test") }, positions,
            object : EngineStateStore {
                override suspend fun load(mode: TradingMode) = saved
                override suspend fun save(state: EngineState) { saved = state }
            },
            { events += it }, { decisions += it },
            AiRouter(listOf(provider to BudgetConfig(10.0, ProviderPricing(4.0, 20.0), 0.1)), ledger) { clock.nowMs() },
            { EngineConfig() }, clock,
        ) { guards -> ExecutionCoordinator(broker, InMemoryIntentStore(), positions, { events += it }, guards, { rules.getValue(it) }, ExecutionConfig(), clock) }

        suspend fun prices() {
            broker.updatePrice(PriceTick("ETHUSDT", BigDecimal.valueOf(ethSeries.last().close).setScale(2, java.math.RoundingMode.HALF_UP), clock.nowMs()))
            broker.updatePrice(PriceTick("BTCUSDT", BigDecimal.valueOf(btcSeries.last().close).setScale(2, java.math.RoundingMode.HALF_UP), clock.nowMs()))
        }
    }

    private val approve = """
        {"snapshotId":"SNAP","dataAsOf":"ASOF","market":{"regime":"RISK_ON","defensiveRecommended":false,"reason":"ok"},
         "candidates":[{"symbol":"ETHUSDT","action":"APPROVE","rank":1,"confidence":"MEDIUM","reason":"tendencia sana","dataUsed":["sma50"],"proposedStop":null,"invalidation":"SMA50"}],
         "positions":[]}
    """.trimIndent()

    @Test fun `approved candidate becomes a protected position after a clean reconciliation`() = runTest {
        val env = Env(this) { approve }
        env.prices()
        env.engine.init()
        env.engine.startStrategy()
        env.engine.monitorCycle()
        assertTrue(env.engine.currentState().reconciliationClean)
        env.engine.analysisCycle(force = true)
        val pos = env.positions.items.values.single()
        assertEquals(PositionStatus.PROTECTED, pos.status)
        assertTrue(env.decisions.any { it.kind == "ENTRADA" && it.aiProvider != null })
    }

    @Test fun `AI unavailable blocks new entries but keeps running`() = runTest {
        val env = Env(this, null)
        env.prices()
        env.engine.init()
        env.engine.startStrategy()
        env.engine.monitorCycle()
        env.engine.analysisCycle(force = true)
        assertTrue(env.positions.items.isEmpty())
        assertFalse(env.engine.currentState().aiAvailable)
        assertTrue(env.decisions.any { it.kind == "RIESGO" && it.details.contains("IA no disponible") })
    }

    @Test fun `stopped strategy skips analysis and paused entries are respected`() = runTest {
        val env = Env(this) { approve }
        env.prices()
        env.engine.init()
        env.engine.monitorCycle()
        env.engine.analysisCycle(force = true) // not started
        assertTrue(env.positions.items.isEmpty())
        env.engine.startStrategy()
        env.engine.pauseEntries(true)
        env.engine.analysisCycle(force = true)
        assertTrue(env.positions.items.isEmpty())
        assertTrue(env.decisions.any { it.details.contains("pausadas") })
    }

    @Test fun `an approved candidate without a price is logged, never dropped silently`() = runTest {
        val env = Env(this) { approve }
        env.broker.updatePrice(PriceTick("BTCUSDT", BigDecimal.valueOf(btcSeries.last().close).setScale(2, java.math.RoundingMode.HALF_UP), env.clock.nowMs()))
        env.engine.init()
        env.engine.startStrategy()
        env.engine.monitorCycle()
        env.engine.analysisCycle(force = true) // ETHUSDT approved by the AI but the broker has no ETH price
        assertTrue(env.positions.items.isEmpty())
        assertTrue(env.decisions.any { it.kind == "RIESGO" && it.symbol == "ETHUSDT" && it.details.contains("sin precio") })
    }

    @Test fun `entries wait for a reconciliation before trading`() = runTest {
        val env = Env(this) { approve }
        env.prices()
        env.engine.init()
        env.engine.startStrategy()
        env.engine.analysisCycle(force = true) // no monitor cycle yet: not reconciled / not connected
        assertTrue(env.positions.items.isEmpty())
    }

    @Test fun `snapshot sent to the AI contains no credentials or account identifiers`() = runTest {
        var captured = ""
        val env = Env(this) { approve }
        env.prices()
        env.engine.init(); env.engine.startStrategy(); env.engine.monitorCycle()
        val spy = object : AiProvider {
            override val name = "claude"; override val model = "m"
            override suspend fun analyze(request: AiRequest): AiRawResponse { captured = request.userContent; error("stop") }
        }
        AiRouter(listOf(spy to BudgetConfig(10.0, ProviderPricing(1.0, 1.0), 0.0)), env.ledger).analyze(
            com.trendpilot.core.ai.AiSnapshot("x", "2026-01-01T00:00:00Z", com.trendpilot.core.ai.AiFeatures.from("BTCUSDT", com.trendpilot.core.indicators.IndicatorSnapshot.at(btcSeries), 1.0), "RISK_ON", emptyList(), 1.0, 0.0, 100.0, emptyList(), emptyList()),
        )
        val json = Json.parseToJsonElement(captured.substringAfter("\n")).jsonObject
        assertFalse(captured.contains("apiKey", true) || captured.contains("secret", true))
        assertEquals("x", json["snapshotId"]!!.jsonPrimitive.content)
    }
}
