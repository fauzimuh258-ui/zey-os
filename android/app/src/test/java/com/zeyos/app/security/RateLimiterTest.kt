package com.zeyos.app.security

import org.junit.Assert.*
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `allows requests up to burst capacity`() {
        val limiter = RateLimiter(capacity = 3, refillPerSecond = 1.0)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `blocks requests beyond burst capacity`() {
        val limiter = RateLimiter(capacity = 2, refillPerSecond = 0.001) // effectively no refill mid-test
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `refills tokens over time`() {
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 20.0) // ~1 token per 50ms
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        Thread.sleep(100)
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `never exceeds capacity even after a long idle period`() {
        val limiter = RateLimiter(capacity = 2, refillPerSecond = 100.0)
        Thread.sleep(200) // would over-fill far past capacity if uncapped
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `is thread-safe under concurrent access`() {
        val limiter = RateLimiter(capacity = 50, refillPerSecond = 0.001)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..200).map {
            Thread { if (limiter.tryAcquire()) successCount.incrementAndGet() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(50, successCount.get()) // exactly capacity, no lost-update races
    }
}
