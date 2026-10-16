package com.trendpilot.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured output contract. The AI reviews candidates the deterministic strategy already
 * produced and the open positions; it cannot originate trades, see credentials or call orders.
 * Its "confidence" is a qualitative, uncalibrated label, never a probability.
 */
@Serializable
data class AiAnalysis(
    val snapshotId: String,
    val dataAsOf: String,
    val market: MarketView,
    val candidates: List<CandidateReview>,
    val positions: List<PositionReview>,
)

@Serializable
data class MarketView(val regime: String, val defensiveRecommended: Boolean, val reason: String)

@Serializable
data class CandidateReview(
    val symbol: String,
    val action: String,
    val rank: Int,
    val confidence: String,
    val reason: String,
    val dataUsed: List<String>,
    val proposedStop: Double?,
    val invalidation: String,
)

@Serializable
data class PositionReview(
    val symbol: String,
    val action: String,
    val confidence: String,
    val reason: String,
    val dataUsed: List<String>,
    val proposedStop: Double?,
    val partialFraction: Double?,
    val invalidation: String,
)

object AiSchema {
    val CANDIDATE_ACTIONS = setOf("APPROVE", "VETO")
    val POSITION_ACTIONS = setOf("HOLD", "RAISE_STOP", "EXIT", "PARTIAL_EXIT")
    val CONFIDENCE = setOf("LOW", "MEDIUM", "HIGH")
    val REGIMES = setOf("RISK_ON", "NEUTRAL", "RISK_OFF")

    private fun nullableNumber(desc: String) =
        """{"anyOf":[{"type":"number"},{"type":"null"}],"description":"$desc"}"""

    private fun enumOf(values: Set<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    /** JSON Schema (strict subset: every object closed, every property required). */
    val JSON_SCHEMA: String = """
    {
      "type":"object","additionalProperties":false,
      "required":["snapshotId","dataAsOf","market","candidates","positions"],
      "properties":{
        "snapshotId":{"type":"string","description":"Copia exacta del snapshotId recibido"},
        "dataAsOf":{"type":"string","description":"Copia exacta del dataAsOf recibido (UTC ISO-8601)"},
        "market":{"type":"object","additionalProperties":false,"required":["regime","defensiveRecommended","reason"],
          "properties":{
            "regime":{"type":"string","enum":${enumOf(REGIMES)}},
            "defensiveRecommended":{"type":"boolean"},
            "reason":{"type":"string"}}},
        "candidates":{"type":"array","items":{"type":"object","additionalProperties":false,
          "required":["symbol","action","rank","confidence","reason","dataUsed","proposedStop","invalidation"],
          "properties":{
            "symbol":{"type":"string"},
            "action":{"type":"string","enum":${enumOf(CANDIDATE_ACTIONS)}},
            "rank":{"type":"integer","description":"1 = mejor"},
            "confidence":{"type":"string","enum":${enumOf(CONFIDENCE)},"description":"Etiqueta cualitativa, no probabilidad"},
            "reason":{"type":"string"},
            "dataUsed":{"type":"array","items":{"type":"string"}},
            "proposedStop":${nullableNumber("Stop sugerido; solo puede ser más alto que el del sistema")},
            "invalidation":{"type":"string"}}}},
        "positions":{"type":"array","items":{"type":"object","additionalProperties":false,
          "required":["symbol","action","confidence","reason","dataUsed","proposedStop","partialFraction","invalidation"],
          "properties":{
            "symbol":{"type":"string"},
            "action":{"type":"string","enum":${enumOf(POSITION_ACTIONS)}},
            "confidence":{"type":"string","enum":${enumOf(CONFIDENCE)}},
            "reason":{"type":"string"},
            "dataUsed":{"type":"array","items":{"type":"string"}},
            "proposedStop":${nullableNumber("Para RAISE_STOP: nuevo stop, mayor al actual")},
            "partialFraction":${nullableNumber("Para PARTIAL_EXIT: fracción entre 0.1 y 0.5")},
            "invalidation":{"type":"string"}}}}
      }
    }
    """.trimIndent()

    val json = Json { ignoreUnknownKeys = false; isLenient = false }
}

/** Data handed to the AI. Contains market data and portfolio percentages only: no keys, no IDs. */
@Serializable
data class AiSnapshot(
    val snapshotId: String,
    val dataAsOf: String,
    val btc: AssetFeatures,
    val btcRegime: String,
    val defensiveTriggers: List<String>,
    val equityQuote: Double,
    val exposurePct: Double,
    val reservePct: Double,
    val candidates: List<CandidateFeatures>,
    val positions: List<PositionFeatures>,
)

@Serializable
data class AssetFeatures(
    val symbol: String,
    val close: Double,
    val sma20: Double,
    val sma50: Double,
    val sma200: Double,
    val rsi14: Double,
    val macdHist: Double,
    val atr14: Double,
    val relVolume: Double,
    val change24hPct: Double,
    val volatility20: Double,
    val swingLow: Double?,
    val swingHigh: Double?,
    val corrToBtc30d: Double,
)

@Serializable
data class CandidateFeatures(val features: AssetFeatures, val setup: String, val systemStop: Double, val systemReasons: List<String>, val score: Double)

@Serializable
data class PositionFeatures(
    val features: AssetFeatures,
    val entryPrice: Double,
    val currentStop: Double,
    val unrealizedPct: Double,
    val daysHeld: Double,
    val systemExitSignal: String?,
)

data class AiRequest(val snapshot: AiSnapshot, val systemPrompt: String, val userContent: String, val schema: String)

data class AiUsage(val provider: String, val model: String, val inputTokens: Long, val outputTokens: Long, val costUsd: Double)

data class AiRawResponse(val json: String, val usage: AiUsage)

interface AiProvider {
    val name: String
    val model: String
    /** Throws on transport/API errors. Must never be given exchange credentials. */
    suspend fun analyze(request: AiRequest): AiRawResponse
}

object AiPrompt {
    /** Stable system prompt (kept byte-identical so it can be prompt-cached). */
    val SYSTEM = """
        Sos un analista de riesgo para un bot personal de swing trading en Binance Spot (solo pares USDT, sin margen,
        futuros ni apalancamiento). Horizonte habitual: 1 a 4 semanas, seguimiento de tendencia.
        Tu rol es revisar, no ejecutar:
        - Los candidatos ya pasaron reglas técnicas deterministas. Aprobá (APPROVE) o vetá (VETO) cada uno y ordenalos.
          Vetá ante dudas razonables: resistencias cercanas, correlación alta con posiciones abiertas, divergencias,
          volumen débil, o mercado frágil. No adivines suelos.
        - Para cada posición abierta: HOLD, RAISE_STOP (solo subir; nunca bajar ni aflojar), EXIT (solo con deterioro
          confirmado) o PARTIAL_EXIT (fracción 0.1–0.5, con justificación).
        - No impongas take-profit fijo. Evitá sobreoperar: sin cambio claro, HOLD.
        - "confidence" es una etiqueta cualitativa (LOW/MEDIUM/HIGH), no una probabilidad.
        - Usá solo los datos recibidos; listá en dataUsed los campos que fundamentan cada decisión.
        - Copiá exactamente snapshotId y dataAsOf.
        Un motor de riesgo determinista valida y puede rechazar todo lo que propongas. No prometas rentabilidad.
        Respondé únicamente con el JSON del esquema.
    """.trimIndent()

    fun build(snapshot: AiSnapshot): AiRequest {
        val payload = Json { prettyPrint = false; encodeDefaults = true }.encodeToString(AiSnapshot.serializer(), snapshot)
        val user = "Snapshot de mercado y cartera (UTC):\n$payload"
        return AiRequest(snapshot, SYSTEM, user, AiSchema.JSON_SCHEMA)
    }
}
