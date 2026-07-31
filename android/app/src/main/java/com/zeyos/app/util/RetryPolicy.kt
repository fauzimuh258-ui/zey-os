package com.zeyos.app.util

import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Generic exponential-backoff retry helper used across network and process
 * operations (model pull, ollama health-check, future API gateway calls).
 */
object RetryPolicy {

    suspend fun <T> retry(
        times: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 15000,
        factor: Double = 2.0,
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastError: Throwable? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                onRetry(attempt + 1, e)
                if (attempt < times - 1) {
                    delay(currentDelay)
                    currentDelay = min((currentDelay * factor).toLong(), maxDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Retry failed with no captured error")
    }
}
