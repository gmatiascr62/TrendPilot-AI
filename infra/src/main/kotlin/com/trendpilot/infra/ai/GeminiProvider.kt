package com.trendpilot.infra.ai

import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiConfig(
    /**
     * Flash-Lite by default: on the free tier it has a much larger daily quota than Flash and is
     * less often overloaded (503). Editable in Settings.
     */
    val model: String = DEFAULT_MODEL,
    /** Tried when the primary model keeps failing with a transient error. Blank = none. */
    val fallbackModel: String = "gemini-flash-latest",
    val maxOutputTokens: Int = 16_000,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    /** Waits between attempts on 429/500/503 for the same model. */
    val retryDelaysMs: List<Long> = listOf(3_000, 10_000),
) {
    companion object { const val DEFAULT_MODEL = "gemini-flash-lite-latest" }
}

class GeminiHttpException(val code: Int, body: String) : IOException("Gemini HTTP $code: ${body.take(300)}") {
    val transient: Boolean get() = code == 429 || code == 500 || code == 503 || code == 504
}

/**
 * Google Gemini via the REST generateContent endpoint with a JSON-schema response
 * (`responseMimeType` + `responseJsonSchema`). The key goes in the `x-goog-api-key` header,
 * never in the URL, so it cannot leak into logged request lines.
 *
 * Transient errors (429 quota, 503 overload) are retried with a delay, then the fallback model
 * is tried. If a model rejects the JSON schema (400), the request is repeated asking only for
 * JSON: the deterministic validator checks the structure anyway.
 */
class GeminiProvider(
    baseHttp: OkHttpClient,
    private val apiKey: () -> String?,
    private val config: () -> GeminiConfig = { GeminiConfig() },
) : AiProvider {
    override val name = "gemini"
    override val model: String get() = config().model
    private val json = Json { ignoreUnknownKeys = true }
    /** AI answers can take well over the app's default 15 s read timeout. */
    private val http = baseHttp.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun analyze(request: AiRequest): AiRawResponse {
        val cfg = config()
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("API key de Gemini no configurada")
        require(cfg.model.isNotBlank()) { "modelo Gemini no configurado" }
        val models = listOf(cfg.model, cfg.fallbackModel).filter { it.isNotBlank() }.distinct()
        var last: Exception? = null
        for (m in models) {
            var withSchema = true
            var attempt = 0
            while (true) {
                try {
                    return call(cfg, key, m, request, withSchema)
                } catch (e: GeminiHttpException) {
                    last = e
                    when {
                        e.code == 400 && withSchema && e.message.orEmpty().contains("schema", ignoreCase = true) -> withSchema = false
                        e.transient && attempt < cfg.retryDelaysMs.size -> delay(cfg.retryDelaysMs[attempt++])
                        e.transient -> break // next model
                        else -> throw e // bad key, bad request: retrying will not help
                    }
                }
            }
        }
        throw last ?: IOException("Gemini sin respuesta")
    }

    private suspend fun call(cfg: GeminiConfig, key: String, model: String, request: AiRequest, withSchema: Boolean): AiRawResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", request.systemPrompt) } } }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", request.userContent) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                if (withSchema) put("responseJsonSchema", json.parseToJsonElement(request.schema))
                put("maxOutputTokens", cfg.maxOutputTokens)
            }
        }
        val req = Request.Builder()
            .url("${cfg.baseUrl}/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiHttpException(resp.code, text)
            val root = json.parseToJsonElement(text).jsonObject
            (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                throw AiRefusalException("Gemini bloqueó la solicitud: $it")
            }
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IOException("Gemini no devolvió candidatos")
            when (val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull) {
                null, "STOP" -> Unit
                "MAX_TOKENS" -> throw IllegalStateException("respuesta truncada (MAX_TOKENS)")
                else -> throw AiRefusalException("Gemini terminó con $finish")
            }
            // Skip thought-summary parts; the answer is the concatenation of the remaining text parts.
            val content = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("")
            val usage = root["usageMetadata"] as? JsonObject
            fun count(k: String) = usage?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
            AiRawResponse(
                content,
                // Thinking tokens are billed as output.
                AiUsage(name, model, count("promptTokenCount"), count("candidatesTokenCount") + count("thoughtsTokenCount"), 0.0),
            )
        }
    }
}
