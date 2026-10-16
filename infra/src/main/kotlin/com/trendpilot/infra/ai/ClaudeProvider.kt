package com.trendpilot.infra.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.trendpilot.core.ai.AiProvider
import com.trendpilot.core.ai.AiRawResponse
import com.trendpilot.core.ai.AiRequest
import com.trendpilot.core.ai.AiUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Duration

data class ClaudeConfig(
    val model: String = "claude-opus-5-5",
    val effort: String = "medium",
    val maxTokens: Long = 16_000,
    /** Server-side refusal fallback (beta). Disable if the API rejects it for this account. */
    val serverSideFallback: Boolean = true,
)

class AiRefusalException(message: String) : Exception(message)

/**
 * Anthropic Claude via the official Java SDK with structured outputs (JSON schema).
 * The API key is read from secure storage at call time and never placed in prompts or logs.
 */
class ClaudeProvider(
    private val apiKey: () -> String?,
    private val config: () -> ClaudeConfig = { ClaudeConfig() },
) : AiProvider {
    override val name = "claude"
    override val model: String get() = config().model

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    private fun client(): AnthropicClient {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("API key de Anthropic no configurada")
        cached?.let { (k, c) -> if (k == key) return c }
        val c = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(5)).maxRetries(2).build()
        cached = key to c
        return c
    }

    override suspend fun analyze(request: AiRequest): AiRawResponse = withContext(Dispatchers.IO) {
        val cfg = config()
        val schema = JsonOutputFormat.Schema.builder().apply {
            Json.parseToJsonElement(request.schema).jsonObject.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(toPlain(v))) }
        }.build()
        val params = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(cfg.maxTokens)
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(request.systemPrompt).cacheControl(CacheControlEphemeral.builder().build()).build()),
            )
            .addUserMessage(request.userContent)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(cfg.effort))
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .apply {
                if (cfg.serverSideFallback) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        val msg = client().messages().create(params)
        if (msg.stopReason().orElse(null) == StopReason.REFUSAL) throw AiRefusalException("Claude rechazó la solicitud")
        if (msg.stopReason().orElse(null) == StopReason.MAX_TOKENS) throw IllegalStateException("respuesta truncada (max_tokens)")
        val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        val u = msg.usage()
        val input = u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L)
        AiRawResponse(text, AiUsage(name, cfg.model, input, u.outputTokens(), 0.0))
    }

    companion object {
        /** Converts kotlinx JSON into plain Kotlin values that JsonValue.from understands. */
        fun toPlain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map { toPlain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }
    }
}
