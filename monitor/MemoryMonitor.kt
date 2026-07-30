package com.zeyos.app.monitor

import android.app.ActivityManager
import android.content.Context
import java.io.File

// Unchanged from the original blueprint — no bugs found here.
class MemoryMonitor(private val context: Context) {

    data class MemoryStatus(
        val totalRamMb: Long,
        val availableRamMb: Long,
        val isLowMemory: Boolean,
        val cpuTempCelsius: Float
    )

    fun getStatus(): MemoryStatus {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val totalRam = memoryInfo.totalMem / (1024 * 1024)
        val availRam = memoryInfo.availMem / (1024 * 1024)

        return MemoryStatus(
            totalRamMb = totalRam,
            availableRamMb = availRam,
            isLowMemory = memoryInfo.lowMemory || availRam < 300,
            cpuTempCelsius = readThermalZone()
        )
    }

    private fun readThermalZone(): Float {
        return try {
            val thermalFile = File("/sys/class/thermal/thermal_zone0/temp")
            if (thermalFile.exists()) {
                val tempStr = thermalFile.readText().trim()
                val tempVal = tempStr.toFloatOrNull() ?: 0f
                if (tempVal > 1000) tempVal / 1000f else tempVal
            } else {
                0.0f
            }
        } catch (e: Exception) {
            0.0f
        }
    }
}
