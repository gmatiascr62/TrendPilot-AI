package com.trendpilot.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trendpilot.app.data.AppLog
import com.trendpilot.app.engine.EngineHolder
import com.trendpilot.app.settings.SettingsRepository
import com.trendpilot.core.model.TradingMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service (type specialUse) that runs the supervision loop: ~5 min monitor cycles and
 * the 4 h analysis. Android may still delay or kill it (Doze, OEM battery managers); protective
 * stops live on the exchange, so a killed service never leaves a position unprotected.
 */
@AndroidEntryPoint
class EngineService : Service() {
    @Inject lateinit var holder: EngineHolder
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var log: AppLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var feed: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ENGINE_ID, notifier.engineNotification("Iniciando supervisión…"), type)
        running = true
        log.write("SERVICIO", "creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART || loop?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loop?.cancel(); feed?.cancel()
        loop = scope.launch {
            val engine = try { holder.ready() } catch (e: Exception) {
                log.error("SERVICIO", "no se pudo iniciar el motor", e)
                notifier.updateEngine("No se pudo iniciar el motor: ${e.javaClass.simpleName}. Abrí la app.")
                return@launch
            }
            startPaperFeed()
            while (isActive) {
                runCycle(engine)
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private suspend fun runCycle(engine: com.trendpilot.core.engine.TradingEngine) {
        val s = settings.load()
        log.write("CICLO", "inicio ${engine.mode}")
        try {
            if (engine.mode == TradingMode.PAPER) {
                val st = engine.currentState()
                holder.refreshPaperPrices(st.universe?.selected.orEmpty() + engine.openPositions().map { it.symbol })
            }
            engine.monitorCycle()
            engine.analysisCycle()
            val st = engine.currentState()
            holder.recordEquity(engine.mode, st.lastEquity)
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val eq = st.lastEquity?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "—"
            val status = when {
                st.emergency -> "EMERGENCIA"
                !st.connectivity.connected -> "sin conexión"
                !st.strategyRunning -> "estrategia detenida"
                st.defensiveActive -> "modo defensivo"
                else -> "activo"
            }
            notifier.updateEngine("${engine.mode} · $status · patrimonio $eq USDT · última revisión $time")
            log.write("CICLO", "fin: $status · patrimonio $eq · conexión ${st.connectivity.connected} · reconciliación ${st.reconciliationClean} · IA ${st.aiAvailable} · universo ${st.universe?.selected?.size ?: 0} · último análisis ${st.lastAnalysisMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "-"}")
        } catch (e: Exception) {
            log.error("CICLO", "error", e)
            notifier.updateEngine("${s.mode} · error en el ciclo: ${e.javaClass.simpleName}. Reintentando.")
        }
    }

    private fun startPaperFeed() {
        feed = scope.launch {
            val engine = holder.ready()
            if (engine.mode != TradingMode.PAPER) return@launch
            val s = settings.load()
            while (isActive) {
                val symbols = (engine.openPositions().map { it.symbol } + "BTCUSDT").toSet()
                runCatching {
                    holder.marketStream(s).prices(symbols).collect { tick ->
                        holder.onPaperTick(tick)
                        val current = engine.openPositions().map { it.symbol }.toSet() + "BTCUSDT"
                        if (current != symbols) throw ResubscribeException()
                    }
                }
                delay(1_000)
            }
        }
    }

    private class ResubscribeException : RuntimeException()

    override fun onDestroy() {
        running = false
        log.write("SERVICIO", "destruido por el sistema o detenido")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MONITOR_INTERVAL_MS = 5 * 60_000L
        const val ACTION_STOP = "com.trendpilot.STOP"
        const val ACTION_RESTART = "com.trendpilot.RESTART"
        @Volatile var running = false
            private set

        fun start(context: Context, restart: Boolean = false) {
            val i = Intent(context, EngineService::class.java).apply { if (restart) action = ACTION_RESTART }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).apply { action = ACTION_STOP })
        }
    }
}
