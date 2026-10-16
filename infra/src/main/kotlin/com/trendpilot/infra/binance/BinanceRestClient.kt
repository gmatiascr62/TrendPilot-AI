package com.trendpilot.infra.binance

import com.trendpilot.core.execution.GatewayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Endpoint sets. Market data uses the public data-only host when trading on production. */
data class BinanceEnvironment(val id: String, val restBase: String, val marketDataBase: String, val streamBase: String) {
    companion object {
        val PRODUCTION = BinanceEnvironment("PRODUCTION", "https://api.binance.com", "https://data-api.binance.vision", "wss://data-stream.binance.vision")
        val TESTNET = BinanceEnvironment("TESTNET", "https://testnet.binance.vision", "https://testnet.binance.vision", "wss://stream.testnet.binance.vision")
    }
}

/** Holds the credentials only in memory, supplied by SecureSettings at runtime. Never logged. */
class BinanceCredentials(val apiKey: String, secret: String) {
    internal val secretBytes: ByteArray = secret.toByteArray(Charsets.UTF_8)
    override fun toString() = "BinanceCredentials(****)"
}

class BinanceHttpException(val httpCode: Int, val code: Int, message: String, val body: JsonElement?) : IOException("HTTP $httpCode [$code] $message")

/**
 * Thin REST client: HMAC-SHA256 signing, server-time offset, used-weight tracking and
 * backoff with jitter on 429/418/5xx. Signed requests are never retried automatically here,
 * because a retry of an order could duplicate it; the ExecutionCoordinator decides.
 */
class BinanceRestClient(
    private val http: OkHttpClient,
    val environment: BinanceEnvironment,
    private val credentials: () -> BinanceCredentials?,
    val recvWindowMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val json = Json { ignoreUnknownKeys = true }
    @Volatile var serverTimeOffsetMs: Long = 0
        private set
    @Volatile var usedWeight1m: Int = 0
        private set
    @Volatile private var bannedUntilMs: Long = 0
    private val timeSync = Mutex()

    suspend fun syncTime() = timeSync.withLock {
        val before = clock()
        val res = publicGet("/api/v3/time", emptyMap(), market = false)
        val after = clock()
        val server = res.jsonObject["serverTime"]!!.jsonPrimitive.long
        serverTimeOffsetMs = server - (before + after) / 2
    }

    fun now() = clock() + serverTimeOffsetMs

    /** Public GET with up to 3 retries (safe: read-only). */
    suspend fun publicGet(path: String, params: Map<String, String>, market: Boolean = true): JsonElement {
        val base = if (market) environment.marketDataBase else environment.restBase
        var attempt = 0
        while (true) {
            try {
                return execute(Request.Builder().url(buildUrl(base, path, params)).get().build())
            } catch (e: BinanceHttpException) {
                if (e.httpCode == 429 || e.httpCode == 418 || e.httpCode >= 500) {
                    if (++attempt > 3) throw e
                    delay(backoff(attempt))
                } else throw e
            } catch (e: IOException) {
                if (++attempt > 3) throw e
                delay(backoff(attempt))
            }
        }
    }

    /** Signed read-only GET (USER_DATA). Retried on transport errors: it has no side effects. */
    suspend fun signedGet(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        var attempt = 0
        while (true) {
            try {
                return execute(signed("GET", path, params))
            } catch (e: BinanceHttpException) {
                if (e.code == -1021 && attempt++ < 1) { syncTime(); continue }
                if ((e.httpCode == 429 || e.httpCode >= 500) && attempt++ < 3) { delay(backoff(attempt)); continue }
                throw e
            } catch (e: IOException) {
                if (attempt++ < 3) { delay(backoff(attempt)); continue } else throw e
            }
        }
    }

    /**
     * Signed request with side effects (orders). Exactly one attempt. Errors are mapped so the
     * caller knows whether the order may have reached the matching engine.
     */
    suspend fun signedTrade(method: String, path: String, params: Map<String, String>): JsonElement {
        if (clock() < bannedUntilMs) throw GatewayException.RateLimited(bannedUntilMs - clock(), "IP bloqueada temporalmente por rate limit")
        val request = signed(method, path, params)
        return try {
            execute(request)
        } catch (e: BinanceHttpException) {
            when {
                e.httpCode == 429 || e.httpCode == 418 -> throw GatewayException.RateLimited(60_000, e.message ?: "rate limit")
                e.code == -1007 || e.httpCode >= 500 -> throw GatewayException.Unknown("estado desconocido: ${e.message}", e)
                // cancelReplace partial outcomes carry the details in "data": let the caller decode them.
                e.code == -2021 || e.code == -2022 -> throw e
                else -> throw GatewayException.Rejected(e.code, e.message ?: "rechazada")
            }
        } catch (e: UnknownHostException) {
            throw GatewayException.NotSent("sin resolución DNS", e)
        } catch (e: ConnectException) {
            throw GatewayException.NotSent("sin conexión", e)
        } catch (e: InterruptedIOException) {
            throw GatewayException.Unknown("timeout", e)
        } catch (e: IOException) {
            throw GatewayException.Unknown("error de red tras enviar", e)
        }
    }

    private fun signed(method: String, path: String, params: Map<String, String>): Request {
        val creds = credentials() ?: throw GatewayException.Rejected(-2014, "credenciales de Binance no configuradas")
        val all = LinkedHashMap(params)
        all["recvWindow"] = recvWindowMs.toString()
        all["timestamp"] = now().toString()
        val query = encode(all)
        val signature = hmacSha256Hex(creds.secretBytes, query)
        val url = "${environment.restBase}$path?$query&signature=$signature"
        val builder = Request.Builder().url(url).header("X-MBX-APIKEY", creds.apiKey)
        return when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(ByteArray(0).toRequestBody(null))
            "DELETE" -> builder.delete()
            else -> error(method)
        }.build()
    }

    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { resp ->
            resp.header("X-MBX-USED-WEIGHT-1M")?.toIntOrNull()?.let { usedWeight1m = it }
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 418 || resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toLongOrNull() ?: 60
                    bannedUntilMs = clock() + retry * 1000
                }
                val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val obj = parsed as? JsonObject
                val code = obj?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val msg = obj?.get("msg")?.jsonPrimitive?.content ?: body.take(200)
                throw BinanceHttpException(resp.code, code, msg, parsed)
            }
            json.parseToJsonElement(body)
        }
    }

    private fun backoff(attempt: Int): Long {
        val base = 500L * (1L shl attempt.coerceAtMost(6))
        val wait = maxOf(base, bannedUntilMs - clock())
        return wait + Random.nextLong(0, 250L * attempt)
    }

    companion object {
        fun encode(params: Map<String, String>) =
            params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

        fun buildUrl(base: String, path: String, params: Map<String, String>) =
            if (params.isEmpty()) "$base$path" else "$base$path?${encode(params)}".toHttpUrl().toString()

        fun hmacSha256Hex(secret: ByteArray, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
