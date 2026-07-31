package com.zeyos.app.manager

import org.junit.Assert.*
import org.junit.Test

class QuantizationManagerTest {

    private val manager = QuantizationManager()

    @Test
    fun `recommends Q2_K when RAM is below 800MB`() {
        val result = manager.determineOptimalQuantization(availableRamMb = 500, storageAvailableGb = 5)
        assertEquals(QuantLevel.Q2_K, result)
    }

    @Test
    fun `recommends Q2_K when storage is below 2GB regardless of RAM`() {
        val result = manager.determineOptimalQuantization(availableRamMb = 2000, storageAvailableGb = 1)
        assertEquals(QuantLevel.Q2_K, result)
    }

    @Test
    fun `recommends Q3_K_M for moderate RAM between 800 and 1200MB`() {
        val result = manager.determineOptimalQuantization(availableRamMb = 1000, storageAvailableGb = 5)
        assertEquals(QuantLevel.Q3_K_M, result)
    }

    @Test
    fun `recommends Q4_K_M when RAM exceeds 1200MB and storage is sufficient`() {
        val result = manager.determineOptimalQuantization(availableRamMb = 2000, storageAvailableGb = 5)
        assertEquals(QuantLevel.Q4_K_M, result)
    }

    @Test
    fun `boundary at exactly 800MB is Q3_K_M`() {
        assertEquals(QuantLevel.Q3_K_M, manager.determineOptimalQuantization(800, 5))
    }

    @Test
    fun `boundary at exactly 1200MB is still Q3_K_M`() {
        assertEquals(QuantLevel.Q3_K_M, manager.determineOptimalQuantization(1200, 5))
    }

    @Test
    fun `boundary at 1201MB rolls over to Q4_K_M`() {
        assertEquals(QuantLevel.Q4_K_M, manager.determineOptimalQuantization(1201, 5))
    }

    @Test
    fun `formats recommended model tag with correct quant suffix`() {
        assertEquals("gemma:2b-instruct:q2_K", manager.getRecommendedModelTag("gemma:2b-instruct", QuantLevel.Q2_K))
        assertEquals("gemma:2b-instruct:q3_K_M", manager.getRecommendedModelTag("gemma:2b-instruct", QuantLevel.Q3_K_M))
        assertEquals("gemma:2b-instruct:q4_K_M", manager.getRecommendedModelTag("gemma:2b-instruct", QuantLevel.Q4_K_M))
    }
}
