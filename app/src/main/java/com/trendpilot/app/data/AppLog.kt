package com.trendpilot.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.trendpilot.app.BuildConfig
import com.trendpilot.app.security.Secret
import com.trendpilot.app.security.SecureSettings
import com.trendpilot.app.settings.SettingsRepository
import com.trendpilot.core.engine.EngineState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plain-text activity log in the app's private storage (rotates at ~1 MB, two files kept).
 * Every line goes through [redact]; secrets are never written on purpose.
 */
class AppLog(context: Context) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val current = File(dir, "engine_log.txt")
    private val previous = File(dir, "engine_log.1.txt")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun write(tag: String, message: String) {
        runCatching {
            if (current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText("${fmt.format(Date())} [$tag] ${redact(message).replace('\n', ' ')}\n")
        }
    }

    fun error(tag: String, message: String, e: Throwable) =
        write(tag, "$message: ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}")

    @Synchronized
    fun tail(maxLines: Int): List<String> {
        val lines = (if (previous.exists()) previous.readLines() else emptyList()) + (if (current.exists()) current.readLines() else emptyList())
        return lines.takeLast(maxLines)
    }

    companion object {
        private const val MAX_BYTES = 1_000_000L
        private val SECRET_PATTERNS = listOf(
            Regex("(signature=)[0-9a-fA-F]+"),
            Regex("([?&]key=)[^&\\s]+"),
            Regex("AIza[0-9A-Za-z_\\-]{20,}"),
            Regex("sk-[0-9A-Za-z_\\-]{16,}"),
        )

        fun redact(s: String): String = SECRET_PATTERNS.fold(s) { acc, r -> acc.replace(r) { m -> (m.groupValues.getOrNull(1) ?: "") + "***" } }
    }
}

/** Builds a shareable diagnostic report. Contains no API keys or secrets. */
class LogExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val log: AppLog,
    private val settings: SettingsRepository,
    private val secure: SecureSettings,
) {
    suspend fun export(state: EngineState?): Intent {
        val s = settings.load()
        val mode = s.mode
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun t(ms: Long?) = ms?.let { utc.format(Date(it)) } ?: "—"
        val sb = StringBuilder()
        sb.appendLine("=== TrendPilot AI · informe de diagnóstico ===")
        sb.appendLine("Generado: ${t(System.currentTimeMillis())}")
        sb.appendLine("App ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Modo: $mode · entorno Binance (modo real): ${s.binanceEnvironment}")
        sb.appendLine()
        sb.appendLine("--- Credenciales (solo si están cargadas; nunca el valor) ---")
        Secret.entries.forEach { sb.appendLine("${it.label}: ${if (secure.isSet(it)) "cargada" else "no cargada"}") }
        sb.appendLine()
        sb.appendLine("--- Configuración ---")
        sb.appendLine("IA principal: ${s.primaryProvider} · Gemini ${s.geminiModel} · Claude ${s.claudeModel} · OpenAI ${s.openAiModel}")
        sb.appendLine("Exigir IA para comprar: ${s.requireAiConfirmation} · presupuestos USD: gemini ${s.geminiBudgetUsd}, claude ${s.claudeBudgetUsd}, openai ${s.openAiBudgetUsd}")
        sb.appendLine("Riesgo: ${s.maxRiskPerTradePct}%/op · exposición ${s.maxTotalExposurePct}% · reserva ${s.minReservePct}% · por activo ${s.maxPerAssetPct}% · posiciones ${s.maxPositions} · DD ${s.maxDrawdownPct}% · pérdida diaria ${s.dailyLossLimitPct}%")
        sb.appendLine("Paper: capital ${s.paperInitialCapital} · inicio ${t(s.paperStartedAtMs)}")
        sb.appendLine()
        sb.appendLine("--- Estado del motor ---")
        if (state == null) sb.appendLine("(motor no inicializado)") else with(state) {
            sb.appendLine("Estrategia: ${if (strategyRunning) "en ejecución" else "detenida"} · compras pausadas: $entriesPausedByUser · emergencia: $emergency ${emergencyReason ?: ""}")
            sb.appendLine("Conexión: ${connectivity.connected} · reanudación manual pendiente: ${connectivity.needsManualResume} · reconciliación limpia: $reconciliationClean")
            sb.appendLine("IA disponible: $aiAvailable · último error IA: ${aiLastError ?: "—"}")
            sb.appendLine("Defensivo: $defensiveActive ${defensiveTriggers.joinToString("; ")}")
            sb.appendLine("Drawdown bloqueado: ${equityGuard?.drawdownLocked} · pérdida diaria bloqueada: ${equityGuard?.dailyLossLocked} · pico: ${equityGuard?.peakEquity}")
            sb.appendLine("Patrimonio: $lastEquity · exposición: ${lastExposurePct?.let { "%.1f%%".format(Locale.US, it) }}")
            sb.appendLine("Último monitoreo: ${t(lastMonitorMs)} · último análisis: ${t(lastAnalysisMs)}")
            sb.appendLine("Universo (${universe?.selected?.size ?: 0}) al ${t(universe?.generatedAtMs)}: ${universe?.selected?.joinToString().orEmpty()}")
            sb.appendLine("Bloqueadas: ${blockedSymbols.joinToString().ifEmpty { "ninguna" }}")
        }
        sb.appendLine()
        sb.appendLine("--- Posiciones abiertas ---")
        db.positions().open(mode).ifEmpty { null }?.forEach {
            sb.appendLine("${it.symbol} ${it.status} qty ${it.quantity} entrada ${it.entryPrice} stop ${it.currentStop} (${it.stopType}) abierta ${t(it.openedAtMs)}")
        } ?: sb.appendLine("ninguna")
        sb.appendLine()
        sb.appendLine("--- Operaciones cerradas (últimas 50) ---")
        db.trades().recentList(mode, 50).ifEmpty { null }?.forEach {
            sb.appendLine("${t(it.closedAtMs)} ${it.symbol} pnl ${it.pnlQuote} (${"%.2f".format(Locale.US, it.rMultiple)}R) fees ${it.feesQuote} motivo ${it.exitReason}")
        } ?: sb.appendLine("ninguna")
        sb.appendLine()
        sb.appendLine("--- Decisiones (últimas 150, más reciente primero) ---")
        db.decisions().recentList(mode, 150).forEach {
            sb.appendLine("${t(it.timestampMs)} [${it.kind}] ${it.symbol ?: ""} ${it.summary}${it.aiProvider?.let { p -> " (IA $p)" } ?: ""}")
            if (it.details.isNotBlank()) sb.appendLine("    " + AppLog.redact(it.details).replace("\n", "\n    "))
        }
        sb.appendLine()
        sb.appendLine("--- Alertas (últimas 150) ---")
        db.events().recentList(mode, 150).forEach { sb.appendLine("${t(it.timestampMs)} [${it.level}] ${it.title}: ${AppLog.redact(it.detail).replace("\n", " ")}") }
        sb.appendLine()
        sb.appendLine("--- Órdenes (últimas 100) ---")
        db.intents().recentList(mode, 100).forEach {
            sb.appendLine("${t(it.createdAtMs)} ${it.kind} ${it.symbol} ${it.side} ${it.type} qty ${it.quantity} stop ${it.stopPrice ?: "-"} estado ${it.status} ${it.note}")
        }
        sb.appendLine()
        sb.appendLine("--- Uso de IA (últimas 50 llamadas) ---")
        db.aiUsage().recentList(50).forEach {
            sb.appendLine("${t(it.timestampMs)} ${it.provider}/${it.model} ok=${it.ok} in=${it.inputTokens} out=${it.outputTokens} US$${"%.4f".format(Locale.US, it.costUsd)} ${AppLog.redact(it.error ?: "")}")
        }
        sb.appendLine()
        sb.appendLine("--- Registro del motor (últimas 1500 líneas) ---")
        log.tail(1500).forEach { sb.appendLine(it) }

        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val file = File(dir, "trendpilot_log_$stamp.txt")
        file.writeText(AppLog.redact(sb.toString()))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TrendPilot log $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartir log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
