package com.trendpilot.infra.ai

import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenAiConfig(
    /** User-confirmed default (2026-10-16). Editable in Settings; validated with GET /v1/models. */
    val model: String = "gpt-5.6-terra",
    val maxCompletionTokens: Int = 16_000,
    val baseUrl: String = "https://api.openai.com/v1",
)

/** OpenAI fallback via Chat Completions with a strict JSON-schema response format. */
class OpenAiProvider(
    baseHttp: OkHttpClient,
    private val apiKey: () -> String?,
    private val config: () -> OpenAiConfig = { OpenAiConfig() },
) : AiProvider {
    override val name = "openai"
    override val model: String get() = config().model
    private val json = Json { ignoreUnknownKeys = true }
    /** AI answers can take well over the app's default 15 s read timeout. */
    private val http = baseHttp.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun analyze(request: AiRequest): AiRawResponse = withContext(Dispatchers.IO) {
        val cfg = config()
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("API key de OpenAI no configurada")
        require(cfg.model.isNotBlank()) { "modelo OpenAI no configurado" }
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_completion_tokens", cfg.maxCompletionTokens)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", request.systemPrompt) }
                addJsonObject { put("role", "user"); put("content", request.userContent) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "trendpilot_analysis")
                    put("strict", true)
                    put("schema", json.parseToJsonElement(request.schema))
                }
            }
        }
        val req = Request.Builder()
            .url("${cfg.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("OpenAI HTTP ${resp.code}: ${text.take(300)}")
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]!!.jsonArray.first().jsonObject
            val message = choice["message"]!!.jsonObject
            message["refusal"]?.jsonPrimitive?.contentOrNull?.let { throw AiRefusalException("OpenAI rechazó: $it") }
            if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") throw IllegalStateException("respuesta truncada")
            val content = message["content"]!!.jsonPrimitive.content
            val usage = root["usage"] as? JsonObject
            AiRawResponse(
                content,
                AiUsage(name, cfg.model, usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0, usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0, 0.0),
            )
        }
    }

    /** Checks that the configured model id exists for this key. */
    suspend fun modelExists(): Boolean = withContext(Dispatchers.IO) {
        val key = apiKey() ?: return@withContext false
        val req = Request.Builder().url("${config().baseUrl}/models/${config().model}").header("Authorization", "Bearer $key").get().build()
        http.newCall(req).execute().use { it.isSuccessful }
    }
}
