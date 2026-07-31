package com.zeyos.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * User-facing alerts, distinct from the always-on foreground service
 * notification (critical memory, thermal throttling, download failures).
 */
object NotificationHelper {

    private const val ALERT_CHANNEL_ID = "ZeyOS_Alerts"
    private var nextNotificationId = 100

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Zey OS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical memory, thermal, and download alerts"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun notify(context: Context, title: String, message: String) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHelper.hasNotificationPermission(context)
        } else true

        if (!hasPermission) {
            Logger.w("NotificationHelper", "Skipped notification, permission not granted: $title")
            return
        }

        val notification: Notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(nextNotificationId++, notification)
        } catch (e: SecurityException) {
            Logger.e("NotificationHelper", "Notification blocked by system", e)
        }
    }

    fun notifyLowMemory(context: Context, availableMb: Long) =
        notify(context, "Zey OS — Low Memory", "Only ${availableMb}MB free. Unloading active model.")

    fun notifyThermalThrottle(context: Context, tempC: Float) =
        notify(context, "Zey OS — Thermal Throttle", "CPU at ${tempC}°C. Reducing thread allocation.")

    fun notifyDownloadFailed(context: Context, modelName: String) =
        notify(context, "Zey OS — Download Failed", "Could not download $modelName after retries.")

    fun notifyModelReady(context: Context, modelName: String) =
        notify(context, "Zey OS — Model Ready", "$modelName downloaded and ready to use.")
}
