package com.zeyos.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPOutputStream

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatted(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        return "${sdf.format(Date(timestamp))} [${level.name}] $tag: $message"
    }
}

/**
 * Centralized logger for Zey OS. Keeps a bounded in-memory buffer for the
 * live Log Viewer UI, and persists every entry to a rotating file so logs
 * survive process death. Rotated files are gzip-compressed to keep the
 * app's private storage footprint small on low-end devices.
 */
object Logger {

    private const val MAX_MEMORY_ENTRIES = 500
    private const val MAX_LOG_FILE_BYTES = 512 * 1024 // 512KB before rotation

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var logDir: File? = null
    private val buffer = ArrayDeque<LogEntry>()

    @Synchronized
    fun init(context: Context) {
        if (logDir == null) {
            logDir = File(context.filesDir, "logs").apply { mkdirs() }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message + (throwable?.let { " | ${it.message}" } ?: ""))

    @Synchronized
    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        buffer.addLast(entry)
        if (buffer.size > MAX_MEMORY_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
        persist(entry)
    }

    private fun persist(entry: LogEntry) {
        val dir = logDir ?: return
        try {
            val file = File(dir, "zeyos.log")
            if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                rotate(file)
            }
            file.appendText(entry.formatted() + "\n")
        } catch (e: Exception) {
            // Logging must never crash the host process.
        }
    }

    private fun rotate(file: File) {
        try {
            val dir = file.parentFile ?: return
            val archived = File(dir, "zeyos_${System.currentTimeMillis()}.log.gz")
            GZIPOutputStream(archived.outputStream()).use { gz ->
                file.inputStream().use { it.copyTo(gz) }
            }
            file.delete()
            cleanupOldArchives(dir)
        } catch (e: Exception) {
            // Best-effort rotation; keep writing to the live file on failure.
        }
    }

    /** Keeps at most 5 archived, gzip-compressed log files (storage hygiene). */
    private fun cleanupOldArchives(dir: File) {
        val archives = dir.listFiles { f -> f.name.endsWith(".log.gz") }
            ?.sortedByDescending { it.lastModified() } ?: return
        archives.drop(5).forEach { it.delete() }
    }

    /** Live log file, used by the Log Viewer's share/export action. */
    fun currentLogFile(): File? = logDir?.let { File(it, "zeyos.log") }

    fun clearMemoryBuffer() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
