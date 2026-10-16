package com.trendpilot.core.ai

import java.time.Instant

/** Result of deterministic validation. Invalid items are dropped individually with a reason. */
data class ValidatedAnalysis(
    val analysis: AiAnalysis,
    val approvedCandidates: List<CandidateReview>,
    val positionActions: List<PositionReview>,
    val dropped: List<String>,
    val usage: AiUsage,
)

sealed interface AiOutcome {
    data class Ok(val result: ValidatedAnalysis) : AiOutcome
    /** No usable analysis: new entries must be blocked; protections continue. */
    data class Unavailable(val reasons: List<String>) : AiOutcome
}

object AiValidator {
    /**
     * Parses and validates [raw] against [snapshot]. Rejects the whole response when it refers to
     * another snapshot, is stale, or is malformed. Never trusts the AI for numbers it can't verify.
     */
    fun validate(raw: AiRawResponse, snapshot: AiSnapshot, nowMs: Long, maxAgeMs: Long = 20 * 60_000L): Result<ValidatedAnalysis> = runCatching {
        val a = AiSchema.json.decodeFromString(AiAnalysis.serializer(), raw.json)
        require(a.snapshotId == snapshot.snapshotId) { "snapshotId no coincide" }
        require(a.dataAsOf == snapshot.dataAsOf) { "dataAsOf no coincide" }
        val asOf = Instant.parse(snapshot.dataAsOf).toEpochMilli()
        require(nowMs - asOf <= maxAgeMs) { "datos demasiado antiguos (${(nowMs - asOf) / 60_000} min)" }
        require(a.market.regime in AiSchema.REGIMES) { "régimen inválido" }

        val dropped = mutableListOf<String>()
        val candidateSymbols = snapshot.candidates.associateBy { it.features.symbol }
        val approved = mutableListOf<CandidateReview>()
        val seen = mutableSetOf<String>()
        for (c in a.candidates) {
            val src = candidateSymbols[c.symbol]
            when {
                src == null -> dropped += "${c.symbol}: no era candidato del sistema (la IA no origina compras)"
                !seen.add(c.symbol) -> dropped += "${c.symbol}: duplicado"
                c.action !in AiSchema.CANDIDATE_ACTIONS -> dropped += "${c.symbol}: acción inválida ${c.action}"
                c.confidence !in AiSchema.CONFIDENCE -> dropped += "${c.symbol}: confianza inválida"
                c.reason.isBlank() -> dropped += "${c.symbol}: sin justificación"
                c.proposedStop != null && (c.proposedStop.isNaN() || c.proposedStop <= 0 || c.proposedStop >= src.features.close) ->
                    dropped += "${c.symbol}: stop propuesto inválido"
                c.action == "APPROVE" -> approved += c
            }
        }
        val positionSymbols = snapshot.positions.associateBy { it.features.symbol }
        val actions = mutableListOf<PositionReview>()
        for (p in a.positions) {
            val src = positionSymbols[p.symbol]
            when {
                src == null -> dropped += "${p.symbol}: no es una posición abierta"
                p.action !in AiSchema.POSITION_ACTIONS -> dropped += "${p.symbol}: acción inválida ${p.action}"
                p.confidence !in AiSchema.CONFIDENCE -> dropped += "${p.symbol}: confianza inválida"
                p.reason.isBlank() -> dropped += "${p.symbol}: sin justificación"
                p.action == "RAISE_STOP" && (p.proposedStop == null || p.proposedStop <= src.currentStop || p.proposedStop >= src.features.close) ->
                    dropped += "${p.symbol}: RAISE_STOP inválido (debe ser > stop actual y < precio)"
                p.action == "PARTIAL_EXIT" && (p.partialFraction == null || p.partialFraction !in 0.1..0.5) ->
                    dropped += "${p.symbol}: fracción parcial inválida"
                p.action != "HOLD" -> actions += p
            }
        }
        ValidatedAnalysis(a, approved.sortedBy { it.rank }, actions, dropped, raw.usage)
    }
}

/** Per-provider monthly budget with configurable per-million-token prices. */
data class ProviderPricing(val inputPerMTokUsd: Double, val outputPerMTokUsd: Double) {
    fun cost(inputTokens: Long, outputTokens: Long) = inputTokens / 1e6 * inputPerMTokUsd + outputTokens / 1e6 * outputPerMTokUsd
}

data class BudgetConfig(val monthlyBudgetUsd: Double, val pricing: ProviderPricing, val estimatedCallCostUsd: Double)

interface UsageLedger {
    suspend fun spentThisMonthUsd(provider: String, nowMs: Long): Double
    suspend fun record(usage: AiUsage, nowMs: Long, ok: Boolean, error: String?)
}

/**
 * Claude first, GPT as fallback. A provider over budget (including the expected cost of this
 * call) is skipped. If both fail, the outcome is Unavailable and entries are blocked.
 */
class AiRouter(
    private val providers: List<Pair<AiProvider, BudgetConfig>>,
    private val ledger: UsageLedger,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun analyze(snapshot: AiSnapshot): AiOutcome {
        val request = AiPrompt.build(snapshot)
        val failures = mutableListOf<String>()
        for ((provider, budget) in providers) {
            val spent = ledger.spentThisMonthUsd(provider.name, now())
            if (spent + budget.estimatedCallCostUsd > budget.monthlyBudgetUsd) {
                failures += "${provider.name}: presupuesto mensual agotado (${"%.2f".format(spent)}/${budget.monthlyBudgetUsd} USD)"
                continue
            }
            val raw = try {
                provider.analyze(request)
            } catch (e: Exception) {
                ledger.record(AiUsage(provider.name, provider.model, 0, 0, 0.0), now(), ok = false, error = e.javaClass.simpleName + ": " + e.message)
                failures += "${provider.name}: ${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val priced = raw.copy(usage = raw.usage.copy(costUsd = budget.pricing.cost(raw.usage.inputTokens, raw.usage.outputTokens)))
            val validated = AiValidator.validate(priced, snapshot, now())
            ledger.record(priced.usage, now(), ok = validated.isSuccess, error = validated.exceptionOrNull()?.message)
            validated.onSuccess { return AiOutcome.Ok(it) }
            failures += "${provider.name}: respuesta inválida: ${validated.exceptionOrNull()?.message}"
        }
        return AiOutcome.Unavailable(failures)
    }
}
