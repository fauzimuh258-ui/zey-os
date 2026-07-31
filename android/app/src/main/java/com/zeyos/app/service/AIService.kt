package com.zeyos.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.zeyos.app.monitor.MemoryMonitor
import com.zeyos.app.monitor.SystemMemoryController
import com.zeyos.app.security.RateLimiter
import com.zeyos.app.util.Logger
import com.zeyos.app.util.NotificationHelper
import com.zeyos.app.util.RetryPolicy
import kotlinx.coroutines.*

class AIService : Service(), SystemMemoryController.MemoryCallback {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var memoryMonitor: MemoryMonitor
    private lateinit var systemMemoryController: SystemMemoryController
    private val unloadRateLimiter = RateLimiter(capacity = 3, refillPerSecond = 0.2) // guards against unload thrash-loops
    private val CHANNEL_ID = "ZeyOS_AI_Service"

    override fun onCreate() {
        super.onCreate()
        Logger.init(applicationContext)
        NotificationHelper.init(applicationContext)

        memoryMonitor = MemoryMonitor(this)
        systemMemoryController = SystemMemoryController(this).also { it.registerCallback(this) }

        createNotificationChannel()
        startForeground(1, buildNotification("Zey OS Engine Active (Idle)"))

        Logger.i("AIService", "Service started")
        startBackgroundMonitoring()
    }

    private fun startBackgroundMonitoring() {
        serviceScope.launch {
            while (isActive) {
                val status = memoryMonitor.getStatus()

                if (status.isLowMemory) {
                    handleCriticalMemory(status.availableRamMb)
                }
                if (status.cpuTempCelsius >= THERMAL_LIMIT_C) {
                    NotificationHelper.notifyThermalThrottle(applicationContext, status.cpuTempCelsius)
                    Logger.w("AIService", "Thermal limit hit: ${status.cpuTempCelsius}C")
                }

                delay(10_000)
            }
        }
    }

    // Wired in from the previously-unused SystemMemoryController: this OS-level
    // ComponentCallbacks2 signal fires faster than the 10s poll loop above can.
    override fun onCriticalMemoryDetected() {
        serviceScope.launch { handleCriticalMemory(memoryMonitor.getStatus().availableRamMb) }
    }

    private suspend fun handleCriticalMemory(availableMb: Long) {
        if (!unloadRateLimiter.tryAcquire()) {
            Logger.w("AIService", "Unload rate-limited, skipping duplicate trigger")
            return
        }
        Logger.w("AIService", "Low memory ($availableMb MB) — unloading model")
        NotificationHelper.notifyLowMemory(applicationContext, availableMb)
        triggerOllamaUnload()
    }

    private suspend fun triggerOllamaUnload() {
        try {
            RetryPolicy.retry(times = 2, initialDelayMs = 500) {
                val process = Runtime.getRuntime().exec("pkill -f ollama")
                val exitCode = process.waitFor()
                // pkill exits 1 when no matching process was found — not a real failure here.
                if (exitCode != 0 && exitCode != 1) throw IllegalStateException("pkill exited with $exitCode")
            }
        } catch (e: Exception) {
            Logger.e("AIService", "Failed to unload ollama after retries", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Zey OS Engine Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Zey OS Engine")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        systemMemoryController.unregister()
        serviceScope.cancel()
        Logger.i("AIService", "Service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val THERMAL_LIMIT_C = 65.0f
    }
}
