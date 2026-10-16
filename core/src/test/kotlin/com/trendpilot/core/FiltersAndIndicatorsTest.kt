package com.trendpilot.core

import com.trendpilot.core.filters.FilterMath
import com.trendpilot.core.filters.FilterValidator
import com.trendpilot.core.indicators.Indicators
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class FiltersAndIndicatorsTest {
    @Test fun `floor and ceil to step are exact`() {
        assertEquals(BigDecimal("0.1234"), FilterMath.floorToStep(BigDecimal("0.123456"), BigDecimal("0.0001")))
        assertEquals(BigDecimal("0.1235"), FilterMath.ceilToStep(BigDecimal("0.123401"), BigDecimal("0.0001")))
        assertEquals(BigDecimal("2500.12"), FilterMath.floorToStep(BigDecimal("2500.129"), BigDecimal("0.01000000")))
        assertTrue(FilterMath.isMultiple(BigDecimal("1.2300"), BigDecimal("0.01")))
    }

    @Test fun `validator rejects quantity off step, below min notional and unsupported type`() {
        val r = Fixtures.rules()
        val v1 = FilterValidator.validate(r, OrderType.MARKET, Side.BUY, BigDecimal("0.00015"), null, null, BigDecimal("2000"))
        assertTrue(v1.any { it.filter == "LOT_SIZE" })
        val v2 = FilterValidator.validate(r, OrderType.MARKET, Side.BUY, BigDecimal("0.0010"), null, null, BigDecimal("2000"))
        assertTrue(v2.any { it.filter == "NOTIONAL" }) // 2 USDT < 5
        val noStops = Fixtures.rules(orderTypes = setOf("MARKET", "LIMIT"))
        val v3 = FilterValidator.validate(noStops, OrderType.STOP_LOSS, Side.SELL, BigDecimal("0.01"), null, BigDecimal("1900"), BigDecimal("1900"))
        assertTrue(v3.any { it.filter == "ORDER_TYPES" })
        val ok = FilterValidator.validate(r, OrderType.STOP_LOSS, Side.SELL, BigDecimal("0.0100"), null, BigDecimal("1900.00"), BigDecimal("1900"))
        assertTrue(ok.toString(), ok.isEmpty())
    }

    @Test fun `sma and rsi known values`() {
        val s = Indicators.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(s[1].isNaN())
        assertEquals(2.0, s[2], 1e-12)
        assertEquals(4.0, s[4], 1e-12)
        val up = (1..30).map { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(up).last(), 1e-9)
        val flat = List(30) { 5.0 }
        assertEquals(50.0, Indicators.rsi(flat).last(), 1e-9)
    }

    @Test fun `indicators have no look-ahead`() {
        val candles = Fixtures.dailySeries(300)
        val closes = candles.map { it.close }
        val fullRsi = Indicators.rsi(closes)
        val fullAtr = Indicators.atr(candles)
        val fullMacd = Indicators.macd(closes).histogram
        for (i in listOf(50, 120, 250)) {
            assertEquals(fullRsi[i], Indicators.rsi(closes.subList(0, i + 1)).last(), 1e-9)
            assertEquals(fullAtr[i], Indicators.atr(candles.subList(0, i + 1)).last(), 1e-9)
            assertEquals(fullMacd[i], Indicators.macd(closes.subList(0, i + 1)).histogram.last(), 1e-9)
        }
    }
}
