package com.trendpilot.infra.universe

import com.trendpilot.core.universe.MarketCapEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * External market-cap ranking from CoinGecko's public API (GET /api/v3/coins/markets).
 * An optional demo API key raises the rate limit; it is NOT a trading credential.
 */
class CoinGeckoClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://api.coingecko.com/api/v3",
    private val demoApiKey: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun topByMarketCap(perPage: Int = 100): List<MarketCapEntry> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$perPage&page=1&sparkline=false"
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        demoApiKey()?.takeIf { it.isNotBlank() }?.let { builder.header("x-cg-demo-api-key", it) }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("CoinGecko HTTP ${resp.code}")
            json.parseToJsonElement(resp.body!!.string()).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val rank = o.num("market_cap_rank")?.toInt() ?: return@mapNotNull null
                MarketCapEntry(
                    id = o.text("id") ?: return@mapNotNull null,
                    symbol = o.text("symbol") ?: return@mapNotNull null,
                    name = o.text("name") ?: "",
                    marketCapUsd = o.num("market_cap") ?: 0.0,
                    rank = rank,
                    priceUsd = o.num("current_price") ?: 0.0,
                )
            }
        }
    }

    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.content?.toDoubleOrNull()
}
