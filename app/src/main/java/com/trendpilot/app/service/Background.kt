package com.trendpilot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.trendpilot.app.data.AppLog
import com.trendpilot.app.engine.EngineHolder
import com.trendpilot.app.settings.SettingsRepository
import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.EventLevel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Deferrable safety net (every 15 min, the WorkManager minimum). If the foreground service was
 * killed by the system it runs a protection/reconciliation cycle itself and warns the user.
 * It does not try to restart the foreground service from the background, which Android 12+ forbids.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val holder: EngineHolder,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val log: AppLog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (EngineService.running) return Result.success()
        log.write("WATCHDOG", "el servicio no estaba activo: verificación de respaldo")
        val engine = runCatching { holder.ready() }.getOrElse { return Result.retry() }
        if (!engine.currentState().strategyRunning && engine.openPositions().isEmpty()) return Result.success()
        runCatching { engine.monitorCycle() }
        notifier.onEvent(
            EngineEvent(EventLevel.WARNING, engine.mode, "Servicio de supervisión detenido",
                "Android detuvo el servicio. Se ejecutó una verificación de protecciones de respaldo. Abrí la app para reanudar la supervisión continua.",
                null, System.currentTimeMillis()),
            settings.load(),
        )
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Restarts supervision after a reboot or an app update (BOOT_COMPLETED may start specialUse FGS). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        WatchdogWorker.schedule(context)
        runCatching { EngineService.start(context) }
    }
}
