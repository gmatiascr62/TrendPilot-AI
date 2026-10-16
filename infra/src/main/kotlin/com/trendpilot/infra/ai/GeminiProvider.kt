package com.trendpilot.infra.ai

import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiUsage
import kotlinx.coroutines.Dispatchers
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

data class GeminiConfig(
    /** Alias that tracks Google's current Flash model (free tier eligible). Editable in Settings. */
    val model: String = "gemini-flash-latest",
    val maxOutputTokens: Int = 16_000,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
)

/**
 * Google Gemini via the REST generateContent endpoint with a JSON-schema response
 * (`responseMimeType` + `responseJsonSchema`). The key goes in the `x-goog-api-key` header,
 * never in the URL, so it cannot leak into logged request lines.
 */
class GeminiProvider(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val config: () -> GeminiConfig = { GeminiConfig() },
) : AiProvider {
    override val name = "gemini"
    override val model: String get() = config().model
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun analyze(request: AiRequest): AiRawResponse = withContext(Dispatchers.IO) {
        val cfg = config()
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("API key de Gemini no configurada")
        require(cfg.model.isNotBlank()) { "modelo Gemini no configurado" }
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
                put("responseJsonSchema", json.parseToJsonElement(request.schema))
                put("maxOutputTokens", cfg.maxOutputTokens)
            }
        }
        val req = Request.Builder()
            .url("${cfg.baseUrl}/models/${cfg.model}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Gemini HTTP ${resp.code}: ${text.take(300)}")
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
                AiUsage(name, cfg.model, count("promptTokenCount"), count("candidatesTokenCount") + count("thoughtsTokenCount"), 0.0),
            )
        }
    }
}
