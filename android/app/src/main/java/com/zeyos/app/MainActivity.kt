// Add these fields to MainActivity, and this button block inside onCreate(),
// after the existing btnViewLogs wiring:

private val storageDetector by lazy { StorageDetector(this) }
private val multiStorageManager by lazy { MultiStorageManager(storageDetector, StorageBenchmark()) }

// --- inside onCreate() ---
val btnScanStorage = findViewById<Button>(R.id.btnScanStorage)
val tvStorageReport = findViewById<TextView>(R.id.tvStorageReport)

btnScanStorage.setOnClickListener {
    btnScanStorage.isEnabled = false
    tvStorageReport.text = "Scanning storage and benchmarking..."
    lifecycleScope.launch {
        val status = memoryMonitor.getStatus()
        val rec = multiStorageManager.recommendFor(modelSizeGb = 4.5, availableRamMb = status.availableRamMb)
        tvStorageReport.text = if (rec == null) {
            "No external storage detected."
        } else {
            buildString {
                append("Target: ${rec.target.label} (${rec.target.freeBytes / (1024 * 1024 * 1024)}GB free)\n")
                rec.benchmarkResult?.let {
                    append("Read: ${"%.0f".format(it.readMbPerSec)}MB/s  Write: ${"%.0f".format(it.writeMbPerSec)}MB/s\n")
                }
                append("Recommended swap: ${rec.recommendedSwapGb}GB\n")
                rec.warnings.forEach { append("⚠ $it\n") }
            }
        }
        btnScanStorage.isEnabled = true
    }
}
