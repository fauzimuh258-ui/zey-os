package com.zeyos.app.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `succeeds on first attempt without retrying`() = runTest {
        var callCount = 0
        val result = RetryPolicy.retry(times = 3, initialDelayMs = 1) {
            callCount++
            "success"
        }
        assertEquals("success", result)
        assertEquals(1, callCount)
    }

    @Test
    fun `retries until success within allowed attempts`() = runTest {
        var callCount = 0
        val result = RetryPolicy.retry(times = 3, initialDelayMs = 1) {
            callCount++
            if (callCount < 3) throw RuntimeException("fail #$callCount")
            "success on attempt $callCount"
        }
        assertEquals("success on attempt 3", result)
        assertEquals(3, callCount)
    }

    @Test
    fun `throws last error after exhausting all attempts`() = runTest {
        var callCount = 0
        try {
            RetryPolicy.retry(times = 3, initialDelayMs = 1) {
                callCount++
                throw IllegalStateException("attempt $callCount failed")
            }
            fail("expected an exception to be thrown")
        } catch (e: IllegalStateException) {
            assertEquals("attempt 3 failed", e.message)
        }
        assertEquals(3, callCount)
    }

    @Test
    fun `invokes onRetry callback only for failed attempts`() = runTest {
        val recordedAttempts = mutableListOf<Int>()
        var callCount = 0
        RetryPolicy.retry(
            times = 3,
            initialDelayMs = 1,
            onRetry = { attempt, _ -> recordedAttempts.add(attempt) }
        ) {
            callCount++
            if (callCount < 2) throw RuntimeException("fail")
            "ok"
        }
        assertEquals(listOf(1), recordedAttempts) // one failed attempt before success
    }

    @Test
    fun `completes correctly even when backoff would exceed maxDelayMs`() = runTest {
        var callCount = 0
        val result = RetryPolicy.retry(
            times = 4, initialDelayMs = 1000, maxDelayMs = 2000, factor = 10.0
        ) {
            callCount++
            if (callCount < 4) throw RuntimeException("fail")
            "done"
        }
        assertEquals("done", result)
        assertEquals(4, callCount)
    }
}
