package com.trendpilot.core.risk

import com.trendpilot.core.strategy.MarketRegime

/**
 * Defensive mode combines hard market rules with an optional AI flag. Hard rules always win:
 * the AI can turn defensive mode ON, but can never turn it off while a hard rule holds.
 * The 5% BTC 24h drop is a test parameter, not a validated threshold.
 */
data class DefensiveConfig(
    val btcDrop24hPct: Double = 5.0,
    val universeDrop24hMedianPct: Double = 6.0,
    val breadthBelowSma50Pct: Double = 70.0,
    val honorAiDefensiveFlag: Boolean = true,
)

data class DefensiveInputs(
    val btcChange24hPct: Double,
    val btcRegime: MarketRegime,
    val universeMedianChange24hPct: Double,
    val pctUniverseBelowSma50: Double,
    val aiDefensiveFlag: Boolean,
)

data class DefensiveState(val active: Boolean, val triggers: List<String>)

object DefensiveMode {
    fun evaluate(cfg: DefensiveConfig, i: DefensiveInputs): DefensiveState {
        val t = mutableListOf<String>()
        if (!i.btcChange24hPct.isNaN() && i.btcChange24hPct <= -cfg.btcDrop24hPct) t += "BTC ${"%.1f".format(i.btcChange24hPct)}% en 24h"
        if (i.btcRegime == MarketRegime.RISK_OFF) t += "BTC bajo SMA200 diaria"
        if (!i.universeMedianChange24hPct.isNaN() && i.universeMedianChange24hPct <= -cfg.universeDrop24hMedianPct) t += "caída mediana del universo ${"%.1f".format(i.universeMedianChange24hPct)}%"
        if (!i.pctUniverseBelowSma50.isNaN() && i.pctUniverseBelowSma50 >= cfg.breadthBelowSma50Pct) t += "${"%.0f".format(i.pctUniverseBelowSma50)}% del universo bajo SMA50"
        if (cfg.honorAiDefensiveFlag && i.aiDefensiveFlag) t += "alerta defensiva de la IA"
        return DefensiveState(t.isNotEmpty(), t)
    }
}

/**
 * Connectivity policy ("conservadora", user decision 2026-10-16):
 * - while disconnected: no entries and no stop adjustments (exchange-side stops keep working);
 * - on reconnect: full REST reconciliation;
 * - offline > [manualResumeAfterMs] or any discrepancy: entries stay paused until the user confirms.
 */
data class ConnectivityConfig(val manualResumeAfterMs: Long = 30 * 60_000L)

data class ConnectivityState(
    val connected: Boolean,
    val disconnectedSinceMs: Long?,
    val lastOkMs: Long?,
    val needsManualResume: Boolean,
)

class ConnectivityMonitor(private val cfg: ConnectivityConfig = ConnectivityConfig()) {
    fun onFailure(s: ConnectivityState, nowMs: Long): ConnectivityState =
        if (s.connected) s.copy(connected = false, disconnectedSinceMs = nowMs) else s

    /** Called after a reconnect AND a completed reconciliation. */
    fun onReconciled(s: ConnectivityState, nowMs: Long, discrepancies: Boolean): ConnectivityState {
        val offlineMs = s.disconnectedSinceMs?.let { nowMs - it } ?: 0L
        val manual = s.needsManualResume || discrepancies || offlineMs > cfg.manualResumeAfterMs
        return ConnectivityState(connected = true, disconnectedSinceMs = null, lastOkMs = nowMs, needsManualResume = manual)
    }

    fun onManualResume(s: ConnectivityState) = s.copy(needsManualResume = false)

    companion object {
        val INITIAL = ConnectivityState(connected = false, disconnectedSinceMs = null, lastOkMs = null, needsManualResume = false)
    }
}
