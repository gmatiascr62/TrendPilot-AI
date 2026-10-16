package com.trendpilot.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trendpilot.app.R
import com.trendpilot.app.settings.AppSettings
import com.trendpilot.app.ui.MainActivity
import com.trendpilot.core.execution.EngineEvent
import com.trendpilot.core.execution.EventLevel
import java.util.concurrent.atomic.AtomicInteger

class Notifier(private val context: Context) {
    private val ids = AtomicInteger(1000)

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ENGINE, "Motor en ejecución", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Notificación persistente del servicio de supervisión"
        })
        nm.createNotificationChannel(NotificationChannel(CH_TRADES, "Operaciones y stops", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SECURITY, "Alertas de seguridad", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Emergencias, drawdown, modo defensivo, conectividad"
        })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun engineNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_ENGINE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateEngine(text: String) = post(ENGINE_ID, engineNotification(text))

    fun onEvent(e: EngineEvent, s: AppSettings) {
        if (!e.notify) return
        val allowed = when (e.level) {
            EventLevel.TRADE -> s.notifyTrades
            EventLevel.STOP -> s.notifyStops
            EventLevel.WARNING -> s.notifyConnectivity
            EventLevel.SECURITY, EventLevel.EMERGENCY -> true // safety alerts cannot be muted
            EventLevel.INFO -> s.notifyConnectivity
        }
        if (!allowed) return
        val channel = if (e.level == EventLevel.TRADE || e.level == EventLevel.STOP) CH_TRADES else CH_SECURITY
        val prefix = if (e.mode.name == "REAL") "[REAL] " else "[${e.mode.name}] "
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning.takeIf { e.level == EventLevel.EMERGENCY || e.level == EventLevel.SECURITY } ?: android.R.drawable.ic_menu_compass)
            .setContentTitle(prefix + e.title)
            .setContentText(e.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.detail))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setPriority(if (e.level == EventLevel.EMERGENCY) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .build()
        post(ids.incrementAndGet(), n)
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CH_ENGINE = "engine"
        const val CH_TRADES = "trades"
        const val CH_SECURITY = "security"
        const val ENGINE_ID = 1
    }
}
