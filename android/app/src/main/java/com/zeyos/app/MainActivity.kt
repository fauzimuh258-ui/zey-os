package com.zeyos.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zeyos.app.manager.ModelManager
import com.zeyos.app.manager.QuantizationManager
import com.zeyos.app.monitor.MemoryMonitor
import com.zeyos.app.security.SecurityManager
import com.zeyos.app.service.AIService
import com.zeyos.app.storage.MultiStorageManager
import com.zeyos.app.storage.StorageBenchmark
import com.zeyos.app.storage.StorageDetector
import com.zeyos.app.ui.LogViewerActivity
import com.zeyos.app.util.Logger
import com.zeyos.app.util.NotificationHelper
import com.zeyos.app.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var memoryMonitor: MemoryMonitor
    private lateinit var securityManager: SecurityManager
    private val modelManager = ModelManager()
    private val quantizationManager = QuantizationManager()
    private val storageDetector by lazy { StorageDetector(this) }
    private val multiStorageManager by lazy { MultiStorageManager(storageDetector, StorageBenchmark()) }

    private val downloadTargets = listOf("tinyllama:1.1b-chat-v1.0-q4_0", "gemma:2b-instruct-q4_0")
    private var downloadIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.init(applicationContext)
        NotificationHelper.init(applicationContext)
        memoryMonitor = MemoryMonitor(this)
        securityManager = SecurityManager(this)
        securityManager.getOrCreateApiKey() // Ensures a key exists before any Path-B gateway call is made

        PermissionHelper.requestNotificationPermission(this)

        startAIService()

        val tvStatus = findViewById<TextView>(R.id.tvStatusIndicator)
        val tvMemory = findViewById<TextView>(R.id.tvMemory)
        val tvModels = findViewById<TextView>(R.id.tvModels)
        val tvDownloadProgress = findViewById<TextView>(R.id.tvDownloadProgress)
        val btnRefresh = findViewById<Button>(R.id.btnRefresh)
        val btnDownload = findViewById<Button>(R.id.btnDownload)
        val btnLogs = findViewById<Button>(R.id.btnViewLogs)
        val btnScanStorage = findViewById<Button>(R.id.btnScanStorage)
        val tvStorageReport = findViewById<TextView>(R.id.tvStorageReport)

        btnRefresh.setOnClickListener { updateDashboard(tvStatus, tvMemory, tvModels) }
        btnLogs.setOnClickListener { startActivity(Intent(this, LogViewerActivity::class.java)) }

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

        btnDownload.setOnClickListener {
            val target = downloadTargets[downloadIndex % downloadTargets.size]
            downloadIndex++
            btnDownload.isEnabled = false
            lifecycleScope.launch {
                val ok = modelManager.downloadModel(target) { progress ->
                    runOnUiThread { tvDownloadProgress.text = progress }
                }
                if (ok) NotificationHelper.notifyModelReady(applicationContext, target)
                else NotificationHelper.notifyDownloadFailed(applicationContext, target)
                btnDownload.isEnabled = true
                updateDashboard(tvStatus, tvMemory, tvModels)
            }
        }

        lifecycleScope.launch {
            while (true) {
                updateDashboard(tvStatus, tvMemory, tvModels)
                delay(5000)
            }
        }
    }

    private fun startAIService() {
        val intent = Intent(this, AIService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun updateDashboard(tvStatus: TextView, tvMemory: TextView, tvModels: TextView) {
        val status = memoryMonitor.getStatus()
        val quant = quantizationManager.determineOptimalQuantization(status.availableRamMb, storageAvailableGb = 5)

        tvMemory.text = "RAM: ${status.availableRamMb} MB / ${status.totalRamMb} MB\n" +
                "Temp: ${status.cpuTempCelsius}°C\n" +
                "Recommended quant: ${quant.description}"

        val (color, label) = when {
            status.isLowMemory || status.cpuTempCelsius >= 65f -> Color.parseColor("#FF5252") to "CRITICAL"
            status.availableRamMb < 1000 -> Color.parseColor("#FFC107") to "WARNING"
            else -> Color.parseColor("#00E676") to "HEALTHY"
        }
        tvStatus.text = "\u25CF $label"
        tvStatus.setTextColor(color)

        lifecycleScope.launch {
            val models = modelManager.getInstalledModels()
            tvModels.text = if (models.isEmpty()) {
                "Installed Models: None / Ollama Offline"
            } else {
                "Installed Models:\n" + models.joinToString("\n- ", prefix = "- ")
            }
        }
    }
}
