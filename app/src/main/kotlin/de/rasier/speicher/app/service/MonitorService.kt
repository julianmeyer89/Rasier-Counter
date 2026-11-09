package de.rasier.speicher.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.rasier.speicher.app.R
import de.rasier.speicher.app.graph
import de.rasier.speicher.app.ui.MainActivity
import de.rasier.speicher.control.Alert

/**
 * Keeps the automations and statistics running while the app is not visible.
 * Shown as a permanent notification, as Android requires for background work of this kind.
 */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = graph.notifier.serviceNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, notification, type)
        graph.polling.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        graph.polling.release()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}

class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val shown = mutableSetOf<String>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Hintergrunddienst", NotificationManager.IMPORTANCE_MIN),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Warnungen", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(): Notification = NotificationCompat.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Rasier Speicher")
        .setContentText("Überwacht Speicher und Automatiken")
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    /** Posts each alert once while it is active; forgets alerts that went away. */
    @Synchronized
    fun showAlerts(alerts: List<Alert>) {
        val keys = alerts.map { it.key }.toSet()
        shown.retainAll(keys)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (a in alerts) {
            if (a.severity == Alert.Severity.INFO || !shown.add(a.key)) continue
            val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(a.title)
                .setContentText(a.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            manager.notify(a.key.hashCode(), n)
        }
    }

    companion object {
        const val SERVICE_ID = 1
        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_ALERTS = "alerts"
    }
}
