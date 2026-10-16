package com.trendpilot.infra.binance

import com.trendpilot.core.model.PriceTick
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Mini-ticker WebSocket feed (market data only, no credentials). OkHttp answers server pings.
 * Reconnects with exponential backoff + jitter and proactively before the 24h connection limit.
 * Prices from here are hints for monitoring; every order decision re-reads state over REST.
 */
class BinanceMarketStream(
    private val http: OkHttpClient,
    private val environment: BinanceEnvironment,
    private val maxConnectionMs: Long = 23 * 3_600_000L,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun prices(symbols: Set<String>): Flow<PriceTick> = flow {
        var attempt = 0
        while (true) {
            val started = System.currentTimeMillis()
            try {
                connectOnce(symbols).collect {
                    attempt = 0
                    emit(it)
                    if (System.currentTimeMillis() - started > maxConnectionMs) throw RotateConnection()
                }
            } catch (_: RotateConnection) {
                continue
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            _connected.value = false
            attempt++
            delay(minOf(60_000L, 1_000L * (1L shl attempt.coerceAtMost(6))) + Random.nextLong(0, 1_000))
        }
    }

    private class RotateConnection : RuntimeException()

    private fun connectOnce(symbols: Set<String>): Flow<PriceTick> = callbackFlow {
        val streams = symbols.joinToString("/") { "${it.lowercase()}@miniTicker" }
        val request = Request.Builder().url("${environment.streamBase}/stream?streams=$streams").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { _connected.value = true }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val data = root["data"]?.jsonObject ?: return
                val symbol = data.str("s") ?: return
                val close = data.dec("c") ?: return
                val time = data.str("E")?.toLongOrNull() ?: System.currentTimeMillis()
                trySendBlocking(PriceTick(symbol, close, time))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        awaitClose { _connected.value = false; ws.cancel() }
    }
}
