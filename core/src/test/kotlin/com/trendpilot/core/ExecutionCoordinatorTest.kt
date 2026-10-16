package com.trendpilot.core

import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.EventLevel
import com.trendpilot.core.execution.ExecutionConfig
import com.trendpilot.core.execution.ExecutionCoordinator
import com.trendpilot.core.execution.ExecutionOutcome
import com.trendpilot.core.execution.GuardControl
import com.trendpilot.core.execution.InMemoryIntentStore
import com.trendpilot.core.execution.InMemoryPositionStore
import com.trendpilot.core.execution.IntentKind
import com.trendpilot.core.execution.IntentStatus
import com.trendpilot.core.execution.OrderStatus
import com.trendpilot.core.execution.PositionStatus
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.paper.PaperBroker
import com.trendpilot.core.risk.RiskDecision
import com.trendpilot.core.strategy.ExitReason
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ExecutionCoordinatorTest {
    private val baseTime = 1_700_000_000_000L

    private class Harness(scope: TestScope, cancelReplace: Boolean = true) {
        val rules = Fixtures.rules(cancelReplace = cancelReplace)
        val clock = com.trendpilot.core.execution.Clock { 1_700_000_000_000L + scope.testScheduler.currentTime }
        val broker = PaperBroker(BigDecimal("1000"), { rules }, clock = clock, slippage = BigDecimal.ZERO)
        val intents = InMemoryIntentStore()
        val positions = InMemoryPositionStore()
        val events = mutableListOf<EngineEvent>()
        val emergencies = mutableListOf<String>()
        val dirty = mutableListOf<String>()
        private var ids = 0
        fun coordinator() = ExecutionCoordinator(
            broker, intents, positions, { events += it },
            object : GuardControl {
                override suspend fun raiseEmergency(reason: String) { emergencies += reason }
                override suspend fun markReconciliationDirty(reason: String) { dirty += reason }
            },
            { rules }, ExecutionConfig(), clock, { "id${ids++}".padEnd(20, '0') },
        )
        val coordinator = coordinator()

        fun approved(qty: String = "0.1000", stop: String = "1800.00") = RiskDecision.Approved(
            "ETHUSDT", BigDecimal(qty), BigDecimal(qty) * BigDecimal("2000"), BigDecimal(stop), null, OrderType.STOP_LOSS,
            BigDecimal("20"), 1.0, BigDecimal(qty), emptyList(),
        )
    }

    private suspend fun Harness.price(p: String) = broker.updatePrice(PriceTick("ETHUSDT", BigDecimal(p), 0))

    @Test fun `entry is protected by a confirmed exchange stop`() = runTest {
        val h = Harness(this)
        h.price("2000")
        val out = h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done
        val pos = out.position!!
        assertEquals(PositionStatus.PROTECTED, pos.status)
        val stop = h.broker.queryOrder("ETHUSDT", pos.stopClientOrderId!!)!!
        assertEquals(OrderStatus.NEW, stop.status)
        assertEquals(pos.quantity, stop.origQty)
        // base commission was deducted: the stop covers the net quantity
        assertEquals(BigDecimal("0.0999"), pos.quantity)
    }

    @Test fun `timeout after execution is resolved by query and never duplicated`() = runTest {
        val h = Harness(this)
        h.price("2000")
        h.broker.placeFaults += PaperBroker.Fault.TimeoutAfterExecute
        val out = h.coordinator.openPosition(h.approved(), "TREND", "test")
        assertTrue(out is ExecutionOutcome.Done)
        val entries = h.intents.items.values.filter { it.kind == IntentKind.ENTRY }
        assertEquals(1, entries.size)
        assertEquals(IntentStatus.FILLED, entries.single().status)
        // Only one buy happened: 1000 - 200 USDT spent.
        val usdt = h.broker.balances().first { it.asset == "USDT" }.free
        assertEquals(0, usdt.compareTo(BigDecimal("800.0000")))
    }

    @Test fun `timeout before execution is declared not placed after recvWindow`() = runTest {
        val h = Harness(this)
        h.price("2000")
        h.broker.placeFaults += PaperBroker.Fault.TimeoutBeforeExecute
        val out = h.coordinator.openPosition(h.approved(), "TREND", "test")
        assertTrue(out is ExecutionOutcome.Failed)
        assertEquals(IntentStatus.NOT_PLACED, h.intents.items.values.single().status)
        assertTrue(h.positions.items.isEmpty())
    }

    @Test fun `partial fill is protected for the filled quantity only`() = runTest {
        val h = Harness(this)
        h.price("2000")
        h.broker.placeFaults += PaperBroker.Fault.PartialFill(BigDecimal("0.5"))
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        assertEquals(PositionStatus.PROTECTED, pos.status)
        assertEquals(BigDecimal("0.0499"), pos.quantity)
        assertEquals(pos.quantity, h.broker.queryOrder("ETHUSDT", pos.stopClientOrderId!!)!!.origQty)
    }

    @Test fun `unconfirmable protection triggers emergency and closes the position`() = runTest {
        val h = Harness(this)
        h.price("2000")
        repeat(3) { h.broker.placeFaults.addLast(PaperBroker.Fault.Reject(-2010, "simulated reject")) }
        // first fault would hit the entry: prepend a no-op by filling entry first
        h.broker.placeFaults.addFirst(PaperBroker.Fault.PartialFill(BigDecimal.ONE))
        val out = h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done
        assertEquals(1, h.emergencies.size)
        assertEquals(PositionStatus.CLOSED, out.position!!.status)
        assertEquals(ExitReason.EMERGENCY, h.positions.trades.single().exitReason)
        assertTrue(h.events.any { it.level == EventLevel.EMERGENCY })
    }

    @Test fun `stop raise never lowers and survives a failed replacement`() = runTest {
        val h = Harness(this)
        h.price("2000")
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        h.price("2300")
        assertTrue(h.coordinator.raiseStop(pos.id, BigDecimal("1700"), "x") is ExecutionOutcome.Failed)
        // cancel succeeds but the new order is rejected (HTTP 409 case): must re-protect immediately
        h.broker.placeFaults += PaperBroker.Fault.Reject(-2010, "new order rejected")
        val out = h.coordinator.raiseStop(pos.id, BigDecimal("2000"), "trailing") as ExecutionOutcome.Done
        val p = out.position!!
        assertEquals(PositionStatus.PROTECTED, p.status)
        assertEquals(BigDecimal("2000.00"), p.currentStop)
        assertEquals(1, h.broker.openOrders("ETHUSDT").size)
    }

    @Test fun `stop raise without cancelReplace cancels then places`() = runTest {
        val h = Harness(this, cancelReplace = false)
        h.price("2000")
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        h.price("2300")
        val p = (h.coordinator.raiseStop(pos.id, BigDecimal("2100"), "t") as ExecutionOutcome.Done).position!!
        assertEquals(BigDecimal("2100.00"), p.currentStop)
        assertEquals(1, h.broker.openOrders().size)
    }

    @Test fun `restart recovery rebuilds and protects an orphan filled entry`() = runTest {
        val h = Harness(this)
        h.price("2000")
        // Crash simulation: the buy executed, the app died before saving the position.
        h.broker.placeFaults += PaperBroker.Fault.TimeoutAfterExecute
        h.broker.queryFaults += PaperBroker.Fault.NetworkDown
        repeat(10) { h.broker.queryFaults += PaperBroker.Fault.NetworkDown }
        h.coordinator.openPosition(h.approved(), "TREND", "test")
        assertTrue(h.positions.items.isEmpty())
        h.broker.queryFaults.clear()
        val restarted = h.coordinator() // new process, same stores
        val report = restarted.reconcile()
        val pos = h.positions.items.values.single()
        assertEquals(PositionStatus.PROTECTED, pos.status)
        assertTrue(report.actions.any { it.contains("reconstruida") })
    }

    @Test fun `stop executed while offline is detected and booked`() = runTest {
        val h = Harness(this)
        h.price("2000")
        h.coordinator.openPosition(h.approved(), "TREND", "test")
        h.price("1750") // stop at 1800 triggers inside the exchange
        val report = h.coordinator.reconcile()
        assertTrue(report.actions.any { it.contains("ejecutado") })
        val trade = h.positions.trades.single()
        assertEquals(ExitReason.STOP_HIT, trade.exitReason)
        assertTrue(trade.pnlQuote.signum() < 0)
    }

    @Test fun `reconciliation re-protects a position whose stop disappeared`() = runTest {
        val h = Harness(this)
        h.price("2000")
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        h.broker.cancelOrder("ETHUSDT", pos.stopClientOrderId!!) // e.g. cancelled from the Binance app
        val report = h.coordinator.reconcile()
        assertTrue(report.discrepancies.isNotEmpty())
        val now = h.positions.items.values.single()
        assertEquals(PositionStatus.PROTECTED, now.status)
        assertNotNull(h.broker.queryOrder("ETHUSDT", now.stopClientOrderId!!))
    }

    @Test fun `partial exit keeps remaining quantity protected and refuses dust`() = runTest {
        val h = Harness(this)
        h.price("2000")
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        h.price("2400")
        val out = h.coordinator.partialExit(pos.id, 0.5, ExitReason.AI_RECOMMENDATION) as ExecutionOutcome.Done
        val p = out.position!!
        assertEquals(BigDecimal("0.0499"), p.quantity)
        assertEquals(p.quantity, h.broker.queryOrder("ETHUSDT", p.stopClientOrderId!!)!!.origQty)
        // A fraction leaving < 5 USDT notional at the stop is refused.
        val tiny = h.coordinator.partialExit(p.id, 0.99, ExitReason.AI_RECOMMENDATION)
        assertTrue(tiny is ExecutionOutcome.Failed)
    }

    @Test fun `manual close cancels protection only for the sold position`() = runTest {
        val h = Harness(this)
        h.price("2000")
        val pos = (h.coordinator.openPosition(h.approved(), "TREND", "test") as ExecutionOutcome.Done).position!!
        h.price("2100")
        val out = h.coordinator.closePosition(pos.id, ExitReason.MANUAL)
        assertTrue(out is ExecutionOutcome.Done)
        assertTrue(h.broker.openOrders().isEmpty())
        assertTrue(h.positions.trades.single().pnlQuote.signum() > 0)
    }
}
