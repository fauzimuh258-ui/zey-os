package com.zeyos.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zeyos.app.util.Logger
import java.security.SecureRandom

/**
 * Handles two concerns that matter once Zey OS talks to a remote API
 * Gateway (Path B in the ToT routing logic) or exposes any local endpoint:
 *  1. Secure storage of the API key used to authenticate outbound calls.
 *  2. A local token-bucket rate limiter protecting the low-end device from
 *     runaway request loops (its own, or a misbehaving caller's).
 */
class SecurityManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "zeyos_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Logger.e("SecurityManager", "Falling back to standard prefs; encryption unavailable", e)
        context.getSharedPreferences("zeyos_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun getOrCreateApiKey(): String {
        prefs.getString(KEY_API_KEY, null)?.let { return it }
        val newKey = generateApiKey()
        prefs.edit().putString(KEY_API_KEY, newKey).apply()
        return newKey
    }

    fun rotateApiKey(): String {
        val newKey = generateApiKey()
        prefs.edit().putString(KEY_API_KEY, newKey).apply()
        Logger.i("SecurityManager", "API key rotated")
        return newKey
    }

    private fun generateApiKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return "zos_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val KEY_API_KEY = "gateway_api_key"
    }
}

/**
 * Thread-safe token-bucket limiter. Default: 10-request burst capacity,
 * refilling at 5/sec — enough headroom for UI-driven calls, tight enough to
 * stop a runaway retry loop from saturating a 3GB-RAM device.
 */
class RateLimiter(
    private val capacity: Int = 10,
    private val refillPerSecond: Double = 5.0
) {
    private var tokens: Double = capacity.toDouble()
    private var lastRefillNanos: Long = System.nanoTime()
    private val lock = Any()

    fun tryAcquire(): Boolean = synchronized(lock) {
        refill()
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            tokens = (tokens + elapsedSeconds * refillPerSecond).coerceAtMost(capacity.toDouble())
            lastRefillNanos = now
        }
    }
}
