package com.trendpilot.infra.binance

import com.trendpilot.core.execution.BinanceErrors
import com.trendpilot.core.execution.CancelReplaceResult
import com.trendpilot.core.execution.ExchangeGateway
import com.trendpilot.core.execution.ExchangeOrder
import com.trendpilot.core.execution.Fill
import com.trendpilot.core.execution.GatewayException
import com.trendpilot.core.execution.NewOrder
import com.trendpilot.core.filters.LotSize
import com.trendpilot.core.filters.Notional
import com.trendpilot.core.filters.PercentPriceBySide
import com.trendpilot.core.filters.PriceFilter
import com.trendpilot.core.filters.SymbolRules
import com.trendpilot.core.model.AssetBalance
import com.trendpilot.core.model.Candle
import com.trendpilot.core.model.Interval
import com.trendpilot.core.model.PriceTick
import com.trendpilot.core.model.Side
import com.trendpilot.core.model.Ticker24h
import com.trendpilot.core.model.TradingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** Read-only market data (public endpoints). */
class BinanceMarketData(private val rest: BinanceRestClient) {

    suspend fun exchangeInfo(symbols: Collection<String>? = null): Map<String, SymbolRules> {
        val params = if (symbols.isNullOrEmpty()) mapOf("permissions" to "SPOT")
        else mapOf("symbols" to symbols.joinToString(",", "[", "]") { "\"$it\"" })
        val root = rest.publicGet("/api/v3/exchangeInfo", params).jsonObject
        return root["symbols"]!!.jsonArray.map { parseRules(it.jsonObject) }.associateBy { it.symbol }
    }

    /** Closed candles only: the in-progress candle is dropped. */
    suspend fun klines(symbol: String, interval: Interval, limit: Int = 500, endTime: Long? = null): List<Candle> {
        val params = buildMap {
            put("symbol", symbol); put("interval", interval.binance); put("limit", limit.coerceIn(1, 1000).toString())
            endTime?.let { put("endTime", it.toString()) }
        }
        val now = rest.now()
        return rest.publicGet("/api/v3/klines", params).jsonArray.map { row ->
            val a = row.jsonArray
            Candle(
                openTime = a[0].jsonPrimitive.content.toLong(),
                open = a[1].jsonPrimitive.content.toDouble(),
                high = a[2].jsonPrimitive.content.toDouble(),
                low = a[3].jsonPrimitive.content.toDouble(),
                close = a[4].jsonPrimitive.content.toDouble(),
                volume = a[5].jsonPrimitive.content.toDouble(),
                closeTime = a[6].jsonPrimitive.content.toLong(),
                quoteVolume = a[7].jsonPrimitive.content.toDouble(),
            )
        }.filter { it.isClosedAt(now) }
    }

    /** Paginates backwards to fetch up to [days] daily candles (for backtests). */
    suspend fun dailyHistory(symbol: String, days: Int): List<Candle> {
        val out = ArrayList<Candle>()
        var end: Long? = null
        while (out.size < days) {
            val page = klines(symbol, Interval.D1, minOf(1000, days - out.size), end)
            if (page.isEmpty()) break
            out.addAll(0, page)
            end = page.first().openTime - 1
            if (page.size < 2) break
        }
        return out.distinctBy { it.openTime }
    }

    suspend fun ticker24h(symbols: Collection<String>): Map<String, Ticker24h> {
        if (symbols.isEmpty()) return emptyMap()
        val params = mapOf("symbols" to symbols.joinToString(",", "[", "]") { "\"$it\"" })
        return rest.publicGet("/api/v3/ticker/24hr", params).jsonArray.map { it.jsonObject }.map {
            Ticker24h(
                symbol = it.str("symbol")!!,
                lastPrice = it.dec("lastPrice")!!,
                priceChangePercent = it.str("priceChangePercent")!!.toDouble(),
                quoteVolume = it.dec("quoteVolume")!!,
                closeTimeMs = it.str("closeTime")!!.toLong(),
            )
        }.associateBy { it.symbol }
    }

    suspend fun price(symbol: String): PriceTick {
        val o = rest.publicGet("/api/v3/ticker/price", mapOf("symbol" to symbol)).jsonObject
        return PriceTick(symbol, o.dec("price")!!, rest.now())
    }

    companion object {
        fun parseRules(s: JsonObject): SymbolRules {
            val filters = s["filters"]?.jsonArray?.map { it.jsonObject }.orEmpty().associateBy { it.str("filterType") }
            val pf = filters["PRICE_FILTER"]?.let { PriceFilter(it.dec("minPrice")!!, it.dec("maxPrice")!!, it.dec("tickSize")!!) }
            val lot = filters["LOT_SIZE"]?.let { LotSize(it.dec("minQty")!!, it.dec("maxQty")!!, it.dec("stepSize")!!) }
            val mlot = filters["MARKET_LOT_SIZE"]?.let { LotSize(it.dec("minQty")!!, it.dec("maxQty")!!, it.dec("stepSize")!!) }
            val notional = filters["NOTIONAL"]?.let {
                Notional(it.dec("minNotional")!!, it.bool("applyMinToMarket") ?: true, it.dec("maxNotional"), it.bool("applyMaxToMarket") ?: false)
            } ?: filters["MIN_NOTIONAL"]?.let { Notional(it.dec("minNotional")!!, it.bool("applyToMarket") ?: true, null, false) }
            val pps = filters["PERCENT_PRICE_BY_SIDE"]?.let {
                PercentPriceBySide(it.dec("bidMultiplierUp")!!, it.dec("bidMultiplierDown")!!, it.dec("askMultiplierUp")!!, it.dec("askMultiplierDown")!!)
            }
            return SymbolRules(
                symbol = s.str("symbol")!!,
                baseAsset = s.str("baseAsset")!!,
                quoteAsset = s.str("quoteAsset")!!,
                status = s.str("status")!!,
                isSpotTradingAllowed = s.bool("isSpotTradingAllowed") ?: false,
                orderTypes = s["orderTypes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                otoAllowed = s.bool("otoAllowed") ?: false,
                ocoAllowed = s.bool("ocoAllowed") ?: false,
                cancelReplaceAllowed = s.bool("cancelReplaceAllowed") ?: false,
                amendAllowed = s.bool("amendAllowed") ?: false,
                allowTrailingStop = s.bool("allowTrailingStop") ?: false,
                priceFilter = pf, lotSize = lot, marketLotSize = mlot, notional = notional, percentPriceBySide = pps,
                maxNumOrders = filters["MAX_NUM_ORDERS"]?.str("maxNumOrders")?.toIntOrNull(),
                maxNumAlgoOrders = filters["MAX_NUM_ALGO_ORDERS"]?.str("maxNumAlgoOrders")?.toIntOrNull(),
            )
        }
    }
}

/** Result of GET /sapi/v1/account/apiRestrictions: used to refuse keys with withdrawal rights. */
data class ApiKeyPermissions(val enableWithdrawals: Boolean, val enableSpotAndMarginTrading: Boolean, val ipRestrict: Boolean, val enableReading: Boolean)

/** Real exchange gateway (production or testnet). */
class BinanceGateway(
    private val rest: BinanceRestClient,
    private val market: BinanceMarketData,
    override val mode: TradingMode,
) : ExchangeGateway {
    override val recvWindowMs: Long get() = rest.recvWindowMs

    suspend fun apiKeyPermissions(): ApiKeyPermissions {
        val o = rest.signedGet("/sapi/v1/account/apiRestrictions").jsonObject
        return ApiKeyPermissions(o.bool("enableWithdrawals") ?: true, o.bool("enableSpotAndMarginTrading") ?: false, o.bool("ipRestrict") ?: false, o.bool("enableReading") ?: false)
    }

    /** Taker commission rate for [symbol] (standard + tax), from GET /api/v3/account/commission. */
    suspend fun takerCommission(symbol: String): BigDecimal {
        val o = rest.signedGet("/api/v3/account/commission", mapOf("symbol" to symbol)).jsonObject
        val std = o["standardCommission"]?.jsonObject?.dec("taker") ?: BigDecimal("0.001")
        val tax = o["taxCommission"]?.jsonObject?.dec("taker") ?: BigDecimal.ZERO
        return std + tax
    }

    override suspend fun balances(): List<AssetBalance> = mapErrors {
        rest.signedGet("/api/v3/account", mapOf("omitZeroBalances" to "true")).jsonObject["balances"]!!.jsonArray.map {
            val b = it.jsonObject
            AssetBalance(b.str("asset")!!, b.dec("free")!!, b.dec("locked")!!)
        }
    }

    override suspend fun openOrders(symbol: String?): List<ExchangeOrder> = mapErrors {
        rest.signedGet("/api/v3/openOrders", symbol?.let { mapOf("symbol" to it) } ?: emptyMap()).jsonArray.map { parseOrder(it.jsonObject) }
    }

    override suspend fun queryOrder(symbol: String, clientOrderId: String): ExchangeOrder? = try {
        parseOrder(rest.signedGet("/api/v3/order", mapOf("symbol" to symbol, "origClientOrderId" to clientOrderId)).jsonObject)
    } catch (e: BinanceHttpException) {
        if (e.code == BinanceErrors.UNKNOWN_ORDER) null else throw GatewayException.Unknown(e.message ?: "consulta fallida", e)
    } catch (e: java.io.IOException) {
        throw GatewayException.Unknown(e.message ?: "consulta fallida", e)
    }

    override suspend fun placeOrder(order: NewOrder): ExchangeOrder {
        val params = orderParams(order) + ("newOrderRespType" to "FULL")
        return parseOrder(rest.signedTrade("POST", "/api/v3/order", params).jsonObject)
    }

    override suspend fun cancelOrder(symbol: String, clientOrderId: String): ExchangeOrder =
        parseOrder(rest.signedTrade("DELETE", "/api/v3/order", mapOf("symbol" to symbol, "origClientOrderId" to clientOrderId)).jsonObject)

    override suspend fun cancelReplace(symbol: String, cancelClientOrderId: String, newOrder: NewOrder): CancelReplaceResult {
        val params = orderParams(newOrder) + mapOf(
            "cancelReplaceMode" to "STOP_ON_FAILURE",
            "cancelOrigClientOrderId" to cancelClientOrderId,
            "newOrderRespType" to "RESULT",
        )
        val body: JsonObject = try {
            rest.signedTrade("POST", "/api/v3/order/cancelReplace", params).jsonObject
        } catch (e: BinanceHttpException) {
            // -2021 / -2022: partial outcome described in "data".
            (e.body as? JsonObject)?.get("data") as? JsonObject ?: throw GatewayException.Unknown(e.message ?: "cancelReplace", e)
        }
        val cancelOk = body.str("cancelResult") == "SUCCESS"
        val newRes = body.str("newOrderResult")
        val newResp = body["newOrderResponse"]
        val newOrderParsed = if (newRes == "SUCCESS" && newResp is JsonObject) parseOrder(newResp) else null
        val err = (newResp as? JsonObject)?.str("msg") ?: (body["cancelResponse"] as? JsonObject)?.str("msg")
        return CancelReplaceResult(cancelOk, newOrderParsed, newRes != "NOT_ATTEMPTED", err)
    }

    override suspend fun amendQuantity(symbol: String, clientOrderId: String, newQuantity: BigDecimal): ExchangeOrder {
        val o = rest.signedTrade(
            "PUT", "/api/v3/order/amend/keepPriority",
            // Reuse the same clientOrderId so our records keep pointing at the protective order.
            mapOf("symbol" to symbol, "origClientOrderId" to clientOrderId, "newClientOrderId" to clientOrderId, "newQty" to newQuantity.toPlainString()),
        ).jsonObject
        return parseOrder(o["amendedOrder"]!!.jsonObject)
    }

    override suspend fun lastPrice(symbol: String): PriceTick = mapErrors { market.price(symbol) }

    private fun orderParams(o: NewOrder): Map<String, String> = buildMap {
        put("symbol", o.symbol)
        put("side", o.side.name)
        put("type", o.type.name)
        put("quantity", o.quantity.toPlainString())
        put("newClientOrderId", o.clientOrderId)
        o.price?.let { put("price", it.toPlainString()) }
        o.stopPrice?.let { put("stopPrice", it.toPlainString()) }
        o.timeInForce?.let { put("timeInForce", it) }
    }

    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: BinanceHttpException) {
        throw GatewayException.Rejected(e.code, e.message ?: "error")
    } catch (e: java.io.IOException) {
        throw GatewayException.NotSent(e.message ?: "red", e)
    }

    companion object {
        fun parseOrder(o: JsonObject): ExchangeOrder = ExchangeOrder(
            symbol = o.str("symbol")!!,
            orderId = o.str("orderId")!!.toLong(),
            clientOrderId = o.str("clientOrderId")!!,
            side = Side.valueOf(o.str("side") ?: "SELL"),
            type = o.str("type") ?: "",
            status = o.str("status") ?: "NEW",
            origQty = o.dec("origQty") ?: o.dec("qty") ?: BigDecimal.ZERO,
            executedQty = o.dec("executedQty") ?: BigDecimal.ZERO,
            cumulativeQuoteQty = o.dec("cummulativeQuoteQty") ?: o.dec("cumulativeQuoteQty") ?: BigDecimal.ZERO,
            price = o.dec("price")?.takeIf { it.signum() > 0 },
            stopPrice = o.dec("stopPrice")?.takeIf { it.signum() > 0 },
            fills = o["fills"]?.jsonArray?.map { f ->
                val x = f.jsonObject
                Fill(x.dec("price")!!, x.dec("qty")!!, x.dec("commission")!!, x.str("commissionAsset")!!)
            }.orEmpty(),
            updateTimeMs = (o.str("updateTime") ?: o.str("transactTime") ?: o.str("workingTime") ?: "0").toLong(),
        )
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonElement)?.takeIf { it !is JsonNull && it !is JsonArray && it !is JsonObject }?.jsonPrimitive?.content
internal fun JsonObject.dec(k: String): BigDecimal? = str(k)?.toBigDecimalOrNull()
internal fun JsonObject.bool(k: String): Boolean? = str(k)?.toBooleanStrictOrNull()
