package com.trendpilot.infra

import com.trendpilot.core.execution.GatewayException
import com.trendpilot.core.execution.NewOrder
import com.trendpilot.core.model.Interval
import com.trendpilot.core.model.OrderType
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.TradingMode
import com.trendpilot.infra.ai.ClaudeProvider
import com.trendpilot.infra.ai.OpenAiConfig
import com.trendpilot.infra.ai.OpenAiProvider
import com.trendpilot.infra.binance.BinanceCredentials
import com.trendpilot.infra.binance.BinanceEnvironment
import com.trendpilot.infra.binance.BinanceGateway
import com.trendpilot.infra.binance.BinanceMarketData
import com.trendpilot.infra.binance.BinanceRestClient
import com.trendpilot.core.ai.AiPrompt
import com.trendpilot.core.ai.AiSchema
import com.trendpilot.core.ai.AiSnapshot
import com.trendpilot.core.ai.AssetFeatures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class BinanceGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: BinanceGateway
    private lateinit var market: BinanceMarketData
    private val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val base = server.url("").toString().trimEnd('/')
        val env = BinanceEnvironment("MOCK", base, base, base.replace("http", "ws"))
        val rest = BinanceRestClient(http, env, { BinanceCredentials("key", "secret") }, clock = { 1_700_000_000_000L })
        market = BinanceMarketData(rest)
        gateway = BinanceGateway(rest, market, TradingMode.REAL)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `signature matches the official documentation example`() {
        val secret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j".toByteArray()
        val payload = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559"
        assertEquals("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71", BinanceRestClient.hmacSha256Hex(secret, payload))
    }

    @Test fun `credentials never appear in toString`() {
        assertFalse(BinanceCredentials("abc", "supersecret").toString().contains("supersecret"))
    }

    @Test fun `unknown order maps to null, 5xx to Unknown and 400 to Rejected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":-2013,"msg":"Order does not exist."}"""))
        assertNull(gateway.queryOrder("ETHUSDT", "tpX"))

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":-1007,"msg":"Timeout waiting for response from backend server."}"""))
        val order = NewOrder("ETHUSDT", Side.BUY, OrderType.MARKET, BigDecimal("0.1"), "tpRE1")
        assertTrue(runCatching { gateway.placeOrder(order) }.exceptionOrNull() is GatewayException.Unknown)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":-2010,"msg":"Account has insufficient balance for requested action."}"""))
        val rej = runCatching { gateway.placeOrder(order) }.exceptionOrNull()
        assertTrue(rej is GatewayException.Rejected && rej.code == -2010)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("""{"code":-1003,"msg":"Too many requests"}"""))
        assertTrue(runCatching { gateway.placeOrder(order) }.exceptionOrNull() is GatewayException.RateLimited)
    }

    @Test fun `order request is signed and carries the idempotent clientOrderId`() = runTest {
        server.enqueue(MockResponse().setBody("""{"symbol":"ETHUSDT","orderId":7,"clientOrderId":"tpRE1","transactTime":1,"origQty":"0.1","executedQty":"0.1","cummulativeQuoteQty":"200","status":"FILLED","type":"MARKET","side":"BUY","fills":[{"price":"2000","qty":"0.1","commission":"0.0001","commissionAsset":"ETH"}]}"""))
        val o = gateway.placeOrder(NewOrder("ETHUSDT", Side.BUY, OrderType.MARKET, BigDecimal("0.1"), "tpRE1"))
        assertEquals("FILLED", o.status)
        assertEquals(BigDecimal("0.0001"), o.fills.single().commission)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("key", req.getHeader("X-MBX-APIKEY"))
        val url = req.requestUrl!!
        assertEquals("tpRE1", url.queryParameter("newClientOrderId"))
        assertEquals("FULL", url.queryParameter("newOrderRespType"))
        assertTrue(url.queryParameter("signature")!!.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun `cancelReplace partial failure is decoded from the error data`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""
            {"code":-2021,"msg":"Order cancel-replace partially failed.","data":{"cancelResult":"SUCCESS","newOrderResult":"FAILURE",
             "cancelResponse":{"symbol":"ETHUSDT","orderId":5,"clientOrderId":"c","origClientOrderId":"tpRS1","status":"CANCELED","origQty":"0.1","executedQty":"0","cummulativeQuoteQty":"0","type":"STOP_LOSS","side":"SELL"},
             "newOrderResponse":{"code":-2010,"msg":"Order would immediately trigger."}}}
        """.trimIndent()))
        val r = gateway.cancelReplace("ETHUSDT", "tpRS1", NewOrder("ETHUSDT", Side.SELL, OrderType.STOP_LOSS, BigDecimal("0.1"), "tpRR2", stopPrice = BigDecimal("1900")))
        assertTrue(r.cancelSucceeded)
        assertNull(r.newOrder)
        assertTrue(r.errorMessage!!.contains("trigger"))
        assertEquals("STOP_ON_FAILURE", server.takeRequest().requestUrl!!.queryParameter("cancelReplaceMode"))
    }

    @Test fun `exchangeInfo filters are parsed`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"symbols":[{"symbol":"ETHUSDT","status":"TRADING","baseAsset":"ETH","quoteAsset":"USDT","isSpotTradingAllowed":true,
             "orderTypes":["LIMIT","MARKET","STOP_LOSS_LIMIT"],"otoAllowed":true,"cancelReplaceAllowed":true,"amendAllowed":false,
             "filters":[{"filterType":"PRICE_FILTER","minPrice":"0.01","maxPrice":"1000000","tickSize":"0.01"},
                        {"filterType":"LOT_SIZE","minQty":"0.0001","maxQty":"9000","stepSize":"0.0001"},
                        {"filterType":"NOTIONAL","minNotional":"5","applyMinToMarket":true,"maxNotional":"9000000","applyMaxToMarket":false,"avgPriceMins":5}]}]}
        """.trimIndent()))
        val r = market.exchangeInfo(listOf("ETHUSDT")).getValue("ETHUSDT")
        assertEquals(BigDecimal("0.0001"), r.lotSize!!.stepSize)
        assertEquals(BigDecimal("5"), r.notional!!.minNotional)
        assertEquals(OrderType.STOP_LOSS_LIMIT, r.protectiveStopType())
        assertTrue(r.cancelReplaceAllowed)
        assertFalse(r.amendAllowed)
    }

    @Test fun `klines exclude the candle still in progress`() = runTest {
        val closedEnd = 1_699_999_999_999L
        server.enqueue(MockResponse().setBody("""[[1699913600000,"1","2","0.5","1.5","10",$closedEnd,"15",1,"1","1","0"],[1700000000000,"1.5","2","1","1.8","5",1700086399999,"9",1,"1","1","0"]]"""))
        val k = market.klines("ETHUSDT", Interval.D1)
        assertEquals(1, k.size)
        assertEquals(15.0, k.single().quoteVolume, 0.0)
    }

    @Test fun `openai provider sends strict json schema and reads usage`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1200,"completion_tokens":300}}"""))
        val p = OpenAiProvider(http, { "sk-test" }, { OpenAiConfig(baseUrl = server.url("/v1").toString().trimEnd('/')) })
        val f = AssetFeatures("BTCUSDT", 1.0, 1.0, 1.0, 1.0, 50.0, 0.0, 0.1, 1.0, 0.0, 0.01, null, null, 1.0)
        val snap = AiSnapshot("s", "2026-10-16T00:00:00Z", f, "NEUTRAL", emptyList(), 100.0, 0.0, 100.0, emptyList(), emptyList())
        val r = p.analyze(AiPrompt.build(snap))
        assertEquals(1200, r.usage.inputTokens)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gpt-5.6-terra", sent["model"].toString().trim('"'))
        assertTrue(sent["response_format"].toString().contains("\"strict\":true"))
        assertFalse(sent.toString().contains("sk-test"))
    }

    @Test fun `claude schema conversion keeps structure`() {
        val plain = ClaudeProvider.toPlain(Json.parseToJsonElement(AiSchema.JSON_SCHEMA)) as Map<*, *>
        assertEquals(false, plain["additionalProperties"])
        assertTrue((plain["required"] as List<*>).contains("positions"))
    }
}
