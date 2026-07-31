package com.zeyos.app.manager

/**
 * Recommends a GGUF quantization level from live device headroom. Wired
 * into MainActivity's status panel (was defined but never called in the
 * original blueprint).
 */
enum class QuantLevel(val description: String, val ramRequiredMb: Int, val qualityScore: Int) {
    Q2_K("2-bit Quantization (Ultra Compressed / Low RAM)", 600, 60),
    Q3_K_M("3-bit Quantization (Balanced / Low RAM)", 900, 75),
    Q4_K_M("4-bit Quantization (Recommended Standard)", 1400, 90)
}

class QuantizationManager {

    fun determineOptimalQuantization(availableRamMb: Long, storageAvailableGb: Long): QuantLevel {
        return when {
            availableRamMb < 800 || storageAvailableGb < 2 -> QuantLevel.Q2_K
            availableRamMb in 800..1200 -> QuantLevel.Q3_K_M
            else -> QuantLevel.Q4_K_M
        }
    }

    fun getRecommendedModelTag(baseModel: String, quantLevel: QuantLevel): String {
        val quantSuffix = when (quantLevel) {
            QuantLevel.Q2_K -> "q2_K"
            QuantLevel.Q3_K_M -> "q3_K_M"
            QuantLevel.Q4_K_M -> "q4_K_M"
        }
        return "$baseModel:$quantSuffix"
    }
}
