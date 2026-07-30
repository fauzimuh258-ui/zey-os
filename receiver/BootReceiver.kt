package com.zeyos.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zeyos.app.service.AIService
import com.zeyos.app.util.Logger

// Was declared in AndroidManifest.xml in the original blueprint but never
// implemented — the boot-start permission was a no-op. Implemented here.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Logger.init(context.applicationContext)
        Logger.i("BootReceiver", "Boot completed, starting AIService")

        val serviceIntent = Intent(context, AIService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
