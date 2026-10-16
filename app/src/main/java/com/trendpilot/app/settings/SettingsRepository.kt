package com.trendpilot.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trendpilot.core.engine.EngineConfig
import com.trendpilot.core.model.TradingMode
import com.trendpilot.core.risk.DefensiveConfig
import com.trendpilot.core.risk.RiskConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Non-secret configuration. Defaults reflect the decisions confirmed on 2026-10-16. */
@Serializable
data class AppSettings(
    val mode: String = TradingMode.PAPER.name,
    /** REAL mode endpoint: TESTNET (default, no real money) or PRODUCTION. */
    val binanceEnvironment: String = "TESTNET",
    val paperInitialCapital: Double = 200.0,
    val paperStartedAtMs: Long? = null,
    // Risk
    val maxRiskPerTradePct: Double = 1.0,
    val maxTotalExposurePct: Double = 70.0,
    val minReservePct: Double = 30.0,
    val maxPerAssetPct: Double = 25.0,
    val maxPositions: Int = 3,
    val maxDrawdownPct: Double = 8.0,
    val dailyLossLimitPct: Double = 2.0,
    val slippagePct: Double = 0.2,
    val requireAiConfirmation: Boolean = true,
    val btcDefensiveDrop24hPct: Double = 5.0,
    // AI
    val primaryProvider: String = "claude",
    /** Haiku by default: the AI only reviews rule-generated candidates, and cost matters on a small account. */
    val claudeModel: String = "claude-haiku-5-5",
    val claudeEffort: String = "medium",
    /** Server-side refusal fallback; only sent for models that support it (not Haiku). */
    val claudeServerFallback: Boolean = true,
    val claudeBudgetUsd: Double = 3.0,
    /** claude-haiku-5-5 prices for prompts up to 100K tokens (above: 0.50 / 2.50). */
    val claudeInputPerMTok: Double = 0.10,
    val claudeOutputPerMTok: Double = 0.50,
    val openAiModel: String = "gpt-5.6-terra",
    val openAiBudgetUsd: Double = 10.0,
    /** Placeholder prices: verify against OpenAI's official pricing page and edit. */
    val openAiInputPerMTok: Double = 5.0,
    val openAiOutputPerMTok: Double = 30.0,
    // Notifications
    val notifyTrades: Boolean = true,
    val notifyStops: Boolean = true,
    val notifySecurity: Boolean = true,
    val notifyConnectivity: Boolean = true,
    // Real mode checklist acknowledgements
    val ackPaperReviewed: Boolean = false,
    val ackStopNoGuarantee: Boolean = false,
    val ackSmallSize: Boolean = false,
    val ackNoWithdrawKey: Boolean = false,
) {
    val tradingMode: TradingMode get() = TradingMode.valueOf(mode)

    fun engineConfig() = EngineConfig(
        risk = RiskConfig(
            maxRiskPerTradePct = maxRiskPerTradePct,
            maxTotalExposurePct = maxTotalExposurePct,
            minReservePct = minReservePct,
            maxPerAssetPct = maxPerAssetPct,
            maxPositions = maxPositions,
            maxDrawdownPct = maxDrawdownPct,
            dailyLossLimitPct = dailyLossLimitPct,
            slippage = slippagePct / 100,
            requireAiConfirmation = requireAiConfirmation,
        ),
        defensive = DefensiveConfig(btcDrop24hPct = btcDefensiveDrop24hPct),
    )
}

class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("app_settings_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    }

    @Volatile var current: AppSettings = AppSettings()
        private set

    suspend fun load(): AppSettings = flow.first().also { current = it }

    /** Validates through RiskConfig's own checks before persisting. */
    suspend fun update(transform: (AppSettings) -> AppSettings): Result<AppSettings> = runCatching {
        val next = transform(load())
        next.engineConfig() // throws on invalid limits
        context.settingsStore.edit { it[key] = json.encodeToString(AppSettings.serializer(), next) }
        current = next
        next
    }
}
