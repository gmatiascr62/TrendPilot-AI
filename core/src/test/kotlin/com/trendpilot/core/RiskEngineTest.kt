package com.trendpilot.core

import com.trendpilot.core.risk.ConnectivityMonitor
import com.trendpilot.core.risk.EntryRequest
import com.trendpilot.core.risk.EquityGuard
import com.trendpilot.core.risk.GuardFlags
import com.trendpilot.core.risk.OpenPosition
import com.trendpilot.core.risk.PortfolioSnapshot
import com.trendpilot.core.risk.RiskConfig
import com.trendpilot.core.risk.RiskDecision
import com.trendpilot.core.risk.RiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class RiskEngineTest {
    private val cfg = RiskConfig()
    private val engine = RiskEngine(cfg)
    private val now = 1_700_000_000_000L

    private fun pf(equity: String, free: String = equity, positions: List<OpenPosition> = emptyList()) =
        PortfolioSnapshot(BigDecimal(equity), BigDecimal(free), positions, BigDecimal.ZERO, emptySet(), now)

    private fun req(price: String = "2000", stop: String = "1800", symbol: String = "ETHUSDT") =
        EntryRequest(symbol, Fixtures.rules(symbol), BigDecimal(price), now, BigDecimal(stop), now, isRealMode = false)

    @Test fun `risk at stop never exceeds 1 percent including fees and slippage`() {
        // 5% stop: the 1% risk budget (not the 25% asset cap) determines the size.
        val d = engine.evaluateEntry(req(stop = "1900"), pf("10000"), GuardFlags()) as RiskDecision.Approved
        assertTrue("riesgo ${d.plannedRiskPct}", d.plannedRiskPct <= 1.0 + 1e-9)
        assertTrue(d.plannedRiskPct > 0.9) // uses the budget, not a tiny fraction
        // The position size is NOT the risk: notional is much larger than 1%.
        assertTrue(d.expectedNotional > BigDecimal("1000"))
    }

    @Test fun `per-asset cap 25 percent binds with a tight stop`() {
        val d = engine.evaluateEntry(req(stop = "1990"), pf("10000"), GuardFlags()) as RiskDecision.Approved
        assertTrue(d.expectedNotional <= BigDecimal("2500"))
    }

    @Test fun `total exposure 70 percent and 30 percent reserve are respected`() {
        val held = listOf(
            OpenPosition("BTCUSDT", "BTC", BigDecimal("0.04"), BigDecimal("60000"), BigDecimal("55000"), true), // 2400
            OpenPosition("SOLUSDT", "SOL", BigDecimal("16"), BigDecimal("150"), BigDecimal("130"), true), // 2400
        )
        val d = engine.evaluateEntry(req(stop = "1990"), pf("10000", free = "5200", positions = held), GuardFlags()) as RiskDecision.Approved
        // 70% of 10000 = 7000; 4800 used -> at most 2200; reserve 3000 -> cash cap 2200/1.001
        assertTrue(d.expectedNotional.toString(), d.expectedNotional <= BigDecimal("2200"))
    }

    @Test fun `small capital that cannot meet exchange minimums does not trade`() {
        // 20 USDT equity, 1% risk = 0.20; stop 10% away => notional ~1.5 USDT < 5 minNotional
        val d = engine.evaluateEntry(req(), pf("20"), GuardFlags())
        assertTrue(d is RiskDecision.Rejected)
        assertTrue((d as RiskDecision.Rejected).reasons.first().contains("NO se opera"))
    }

    @Test fun `gates block entries`() {
        val gates = listOf(
            GuardFlags(drawdownLocked = true), GuardFlags(dailyLossLocked = true), GuardFlags(defensiveMode = true),
            GuardFlags(entriesPausedByUser = true), GuardFlags(emergency = true), GuardFlags(connectivityOk = false),
            GuardFlags(aiAvailable = false), GuardFlags(reconciliationClean = false), GuardFlags(needsManualResume = true),
            GuardFlags(blockedSymbols = setOf("ETHUSDT")),
        )
        for (g in gates) assertTrue(g.toString(), engine.evaluateEntry(req(), pf("10000"), g) is RiskDecision.Rejected)
    }

    @Test fun `max positions, duplicates and unprotected positions block entries`() {
        val three = (1..3).map { OpenPosition("X${it}USDT", "X$it", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, true) }
        assertTrue(engine.evaluateEntry(req(), pf("10000", positions = three), GuardFlags()) is RiskDecision.Rejected)
        val same = listOf(OpenPosition("ETHUSDT", "ETH", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, true))
        assertTrue(engine.evaluateEntry(req(), pf("10000", positions = same), GuardFlags()) is RiskDecision.Rejected)
        val unprotected = listOf(OpenPosition("BTCUSDT", "BTC", BigDecimal.ONE, BigDecimal.TEN, null, false))
        assertTrue(engine.evaluateEntry(req(), pf("10000", positions = unprotected), GuardFlags()) is RiskDecision.Rejected)
    }

    @Test fun `stale prices and real mode without arming are rejected`() {
        val stale = req().copy(priceTimestampMs = now - 10 * 60_000)
        assertTrue(engine.evaluateEntry(stale, pf("10000"), GuardFlags()) is RiskDecision.Rejected)
        val real = req().copy(isRealMode = true)
        assertTrue(engine.evaluateEntry(real, pf("10000"), GuardFlags(realModeArmed = false)) is RiskDecision.Rejected)
    }

    @Test fun `stop changes can never loosen protection`() {
        assertTrue(engine.validateStopChange(BigDecimal("1800"), BigDecimal("1750"), BigDecimal("2000"))!!.contains("aflojaría"))
        assertTrue(engine.validateStopChange(BigDecimal("1800"), BigDecimal("2100"), BigDecimal("2000")) != null)
        assertEquals(null, engine.validateStopChange(BigDecimal("1800"), BigDecimal("1850"), BigDecimal("2000")))
    }

    @Test fun `drawdown lock at 8 percent is sticky and daily loss resets next UTC day`() {
        val g = EquityGuard(cfg)
        val day = EquityGuard.DAY_MS
        var s = g.initial(BigDecimal("1100"), 0)
        s = g.update(s, BigDecimal("1080"), 2000) // -1.8% day, -1.8% dd
        assertFalse(s.drawdownLocked); assertFalse(s.dailyLossLocked)
        s = g.update(s, BigDecimal("1070"), 3000) // -2.7% on the day
        assertTrue(s.dailyLossLocked)
        s = g.update(s, BigDecimal("1070"), day + 10) // next UTC day
        assertFalse(s.dailyLossLocked)
        s = g.update(s, BigDecimal("1011"), 2 * day + 10) // 8.09% below 1100
        assertTrue(s.drawdownLocked)
        s = g.update(s, BigDecimal("1200"), 3 * day + 10) // recovery does not unlock
        assertTrue(s.drawdownLocked)
        s = g.manualReactivate(s, BigDecimal("1200"))
        assertFalse(s.drawdownLocked)
    }

    @Test fun `conservative connectivity policy requires manual resume after long outage`() {
        val m = ConnectivityMonitor()
        var s = m.onReconciled(ConnectivityMonitor.INITIAL, 0, discrepancies = false)
        assertFalse(s.needsManualResume)
        s = m.onFailure(s, 1_000)
        s = m.onReconciled(s, 1_000 + 10 * 60_000, discrepancies = false) // 10 min
        assertFalse(s.needsManualResume)
        s = m.onFailure(s, 2_000_000)
        s = m.onReconciled(s, 2_000_000 + 31 * 60_000, discrepancies = false) // 31 min
        assertTrue(s.needsManualResume)
        s = m.onManualResume(s)
        s = m.onFailure(s, 9_000_000)
        s = m.onReconciled(s, 9_000_000 + 60_000, discrepancies = true)
        assertTrue(s.needsManualResume)
    }
}
