package com.trendpilot.core

import com.trendpilot.core.ai.AiOutcome
import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiRouter
import com.trendpilot.core.ai.AiSnapshot
import com.trendpilot.core.ai.AiUsage
import com.trendpilot.core.ai.AiValidator
import com.trendpilot.core.ai.AssetFeatures
import com.trendpilot.core.ai.BudgetConfig
import com.trendpilot.core.ai.CandidateFeatures
import com.trendpilot.core.ai.PositionFeatures
import com.trendpilot.core.ai.ProviderPricing
import com.trendpilot.core.ai.UsageLedger
import com.trendpilot.core.backtest.BacktestEngine
import com.trendpilot.core.model.Ticker24h
import com.trendpilot.core.universe.MarketCapEntry
import com.trendpilot.core.universe.UniverseSelector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class AiAndBacktestTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private fun f(sym: String, close: Double) = AssetFeatures(sym, close, close, close, close, 55.0, 1.0, close * 0.03, 1.0, 1.0, 0.02, null, null, 0.8)
    private val snapshot = AiSnapshot(
        "snap-1", "2026-10-16T11:55:00Z", f("BTCUSDT", 60000.0), "RISK_ON", emptyList(), 1000.0, 20.0, 80.0,
        candidates = listOf(CandidateFeatures(f("ETHUSDT", 2000.0), "TREND_CONTINUATION", 1800.0, listOf("x"), 5.0)),
        positions = listOf(PositionFeatures(f("SOLUSDT", 150.0), 140.0, 130.0, 7.0, 3.0, null)),
    )
    private val usage = AiUsage("claude", "claude-opus-5-5", 1000, 200, 0.0)

    private fun response(snapshotId: String = "snap-1", candidate: String = "ETHUSDT", posAction: String = "RAISE_STOP", stop: String = "135.0") = """
        {"snapshotId":"$snapshotId","dataAsOf":"2026-10-16T11:55:00Z",
         "market":{"regime":"RISK_ON","defensiveRecommended":false,"reason":"tendencia"},
         "candidates":[{"symbol":"$candidate","action":"APPROVE","rank":1,"confidence":"MEDIUM","reason":"ok","dataUsed":["rsi14"],"proposedStop":null,"invalidation":"cierre < SMA50"}],
         "positions":[{"symbol":"SOLUSDT","action":"$posAction","confidence":"LOW","reason":"trailing","dataUsed":["atr14"],"proposedStop":$stop,"partialFraction":null,"invalidation":"x"}]}
    """.trimIndent()

    @Test fun `valid response passes and non-candidate buys are dropped`() {
        val ok = AiValidator.validate(AiRawResponse(response(), usage), snapshot, now).getOrThrow()
        assertEquals(listOf("ETHUSDT"), ok.approvedCandidates.map { it.symbol })
        assertEquals("RAISE_STOP", ok.positionActions.single().action)
        val rogue = AiValidator.validate(AiRawResponse(response(candidate = "DOGEUSDT"), usage), snapshot, now).getOrThrow()
        assertTrue(rogue.approvedCandidates.isEmpty())
        assertTrue(rogue.dropped.single().contains("no era candidato"))
    }

    @Test fun `loosening stops, wrong snapshot and stale data are rejected`() {
        val loosen = AiValidator.validate(AiRawResponse(response(stop = "120.0"), usage), snapshot, now).getOrThrow()
        assertTrue(loosen.positionActions.isEmpty())
        assertTrue(AiValidator.validate(AiRawResponse(response(snapshotId = "other"), usage), snapshot, now).isFailure)
        assertTrue(AiValidator.validate(AiRawResponse(response(), usage), snapshot, now + 3_600_000).isFailure)
        assertTrue(AiValidator.validate(AiRawResponse("{not json", usage), snapshot, now).isFailure)
    }

    private class FakeLedger(var spent: Map<String, Double> = emptyMap()) : UsageLedger {
        val records = mutableListOf<AiUsage>()
        override suspend fun spentThisMonthUsd(provider: String, nowMs: Long) = spent[provider] ?: 0.0
        override suspend fun record(usage: AiUsage, nowMs: Long, ok: Boolean, error: String?) { records += usage }
    }

    private fun provider(name: String, body: (() -> String)?) = object : AiProvider {
        override val name = name
        override val model = "$name-model"
        override suspend fun analyze(request: AiRequest): AiRawResponse {
            require(!request.userContent.contains("apiKey", ignoreCase = true))
            return AiRawResponse(body?.invoke() ?: error("HTTP 529 overloaded"), AiUsage(name, model, 1000, 100, 0.0))
        }
    }

    private val budget = BudgetConfig(10.0, ProviderPricing(4.0, 20.0), 0.2)

    @Test fun `falls back to GPT when Claude fails and blocks entries when both fail`() = runTest {
        val ledger = FakeLedger()
        val router = AiRouter(listOf(provider("claude", null) to budget, provider("openai") { response() } to budget), ledger) { now }
        val out = router.analyze(snapshot)
        assertTrue(out is AiOutcome.Ok)
        assertEquals("openai", (out as AiOutcome.Ok).result.usage.provider)
        assertEquals(1000 / 1e6 * 4.0 + 100 / 1e6 * 20.0, out.result.usage.costUsd, 1e-12)

        val both = AiRouter(listOf(provider("claude", null) to budget, provider("openai", null) to budget), ledger) { now }
        assertTrue(both.analyze(snapshot) is AiOutcome.Unavailable)
    }

    @Test fun `exhausted budget skips the provider`() = runTest {
        val ledger = FakeLedger(mapOf("claude" to 9.9, "openai" to 9.9))
        val router = AiRouter(listOf(provider("claude") { response() } to budget, provider("openai") { response() } to budget), ledger) { now }
        val out = router.analyze(snapshot) as AiOutcome.Unavailable
        assertTrue(out.reasons.all { it.contains("presupuesto") })
    }

    @Test fun `backtest has no look-ahead and reports BTC benchmark`() {
        val btc = Fixtures.dailySeries(700, start = 30000.0, dailyDrift = 0.002)
        val eth = Fixtures.dailySeries(700, start = 1500.0, dailyDrift = 0.003, amplitude = 0.06, shockAt = 500)
        val data = mapOf("BTCUSDT" to btc, "ETHUSDT" to eth)
        val engine = BacktestEngine()
        val full = engine.run(data)
        assertTrue(full.trades.isNotEmpty())
        assertTrue(full.warnings.any { it.contains("IA") })
        assertTrue(full.maxDrawdownPct >= 0)
        // Changing data AFTER a cutoff must not change trades that closed before it.
        val cutoffTime = btc[450].openTime
        val mutated = data.mapValues { (_, c) -> c.mapIndexed { i, k -> if (i > 450) k.copy(open = k.open * 3, high = k.high * 3, low = k.low * 3, close = k.close * 3) else k } }
        val other = engine.run(mutated)
        val before = full.trades.filter { it.exitTime < cutoffTime }
        val beforeOther = other.trades.filter { it.exitTime < cutoffTime }
        assertEquals(before, beforeOther)
        assertTrue(engine.walkForward(data, 3).size == 3)
    }

    @Test fun `universe uses external ranking and excludes stables, wrapped and illiquid pairs`() {
        val caps = listOf(
            MarketCapEntry("bitcoin", "btc", "Bitcoin", 1e12, 1, 60000.0),
            MarketCapEntry("tether", "usdt", "Tether", 1e11, 2, 1.0),
            MarketCapEntry("ethereum", "eth", "Ethereum", 4e11, 3, 2000.0),
            MarketCapEntry("wrapped-bitcoin", "wbtc", "Wrapped Bitcoin", 1e10, 4, 60000.0),
            MarketCapEntry("illiquid", "ill", "Illiquid", 1e9, 5, 1.0),
            MarketCapEntry("impostor", "sol", "Other SOL", 1e9, 6, 999.0),
        )
        val rules = listOf("BTCUSDT", "ETHUSDT", "ILLUSDT", "SOLUSDT").associateWith { Fixtures.rules(it) }
        val tickers = mapOf(
            "BTCUSDT" to Ticker24h("BTCUSDT", BigDecimal("60000"), 1.0, BigDecimal("1e9"), now),
            "ETHUSDT" to Ticker24h("ETHUSDT", BigDecimal("2000"), 1.0, BigDecimal("5e8"), now),
            "ILLUSDT" to Ticker24h("ILLUSDT", BigDecimal("1"), 1.0, BigDecimal("1000"), now),
            "SOLUSDT" to Ticker24h("SOLUSDT", BigDecimal("150"), 1.0, BigDecimal("5e8"), now),
        )
        val u = UniverseSelector.select(caps, rules, tickers, now)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), u.selected)
        val reasons = u.excluded.toMap()
        assertTrue(reasons["USDTUSDT"]!!.contains("stablecoin"))
        assertTrue(reasons["WBTCUSDT"]!!.contains("envuelto"))
        assertTrue(reasons["ILLUSDT"]!!.contains("liquidez"))
        assertTrue(reasons["SOLUSDT"]!!.contains("no coincide"))
    }
}
