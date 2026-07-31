package com.zeyos.app.storage

/**
 * Chooses where swap/model files should live when more than one storage
 * location is available, and turns raw benchmark numbers into a concrete,
 * device-specific "what to expect" warning instead of an abstract claim.
 */
class MultiStorageManager(
    private val detector: StorageDetector,
    private val benchmark: StorageBenchmark
) {

    data class Recommendation(
        val target: StorageInfo,
        val benchmarkResult: BenchmarkResult?,
        val recommendedSwapGb: Int,
        val warnings: List<String>
    )

    suspend fun recommendFor(modelSizeGb: Double, availableRamMb: Long): Recommendation? {
        val candidates = detector.detectAll().filter { it.freeBytes > 1024L * 1024 * 1024 }
        if (candidates.isEmpty()) return null

        val target = candidates.filter { it.isRemovable }.maxByOrNull { it.freeBytes }
            ?: candidates.maxByOrNull { it.freeBytes }
            ?: return null

        val result = benchmark.run(target.path)
        val warnings = mutableListOf<String>()

        val availableRamGb = availableRamMb / 1024.0
        val shortfallGb = (modelSizeGb - availableRamGb).coerceAtLeast(0.0)
        // Headroom on top of the raw shortfall — KV cache and context buffers
        // need room too, not just the raw weight size.
        val recommendedSwapGb = (shortfallGb * 1.3).let { if (it < 1) 1 else Math.ceil(it).toInt() }

        if (shortfallGb > 0) {
            warnings.add(
                "Model needs ~${"%.1f".format(modelSizeGb)}GB but only ${"%.1f".format(availableRamGb)}GB " +
                    "RAM is available — ${"%.1f".format(shortfallGb)}GB must come from swap on every token."
            )
        }
        if (result != null && result.readMbPerSec > 0 && shortfallGb > 0) {
            val secPerToken = shortfallGb * 1024 / result.readMbPerSec
            warnings.add(
                "${target.label} measured ${"%.0f".format(result.readMbPerSec)}MB/s read — " +
                    "at that speed, expect roughly ${"%.0f".format(secPerToken)}s PER TOKEN, not per response."
            )
        }
        if (target.isEmulated) {
            warnings.add("${target.label} is emulated storage backed by internal flash, not a distinct physical device.")
        }

        return Recommendation(target, result, recommendedSwapGb, warnings)
    }
}
