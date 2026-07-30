package com.zeyos.app.monitor

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

// Unchanged — was defined but never registered anywhere. Now wired into
// AIService (see file 13) so onTrimMemory/onLowMemory actually fire the callback.
class SystemMemoryController(private val context: Context) : ComponentCallbacks2 {

    interface MemoryCallback {
        fun onCriticalMemoryDetected()
    }

    private var callback: MemoryCallback? = null

    fun registerCallback(cb: MemoryCallback) {
        this.callback = cb
        context.registerComponentCallbacks(this)
    }

    fun unregister() {
        context.unregisterComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                callback?.onCriticalMemoryDetected()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        callback?.onCriticalMemoryDetected()
    }
}
