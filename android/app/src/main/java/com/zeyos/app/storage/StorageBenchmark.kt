package com.zeyos.app.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

data class BenchmarkResult(
    val path: String,
    val writeMbPerSec: Double,
    val readMbPerSec: Double,
    val sampleSizeMb: Int
)

/**
 * Sequential read/write throughput probe for a storage path, using a real
 * file (not shortcuts) so results reflect the actual filesystem —
 * FAT32/exFAT/FUSE-backed removable storage is often dramatically slower
 * than internal storage. NOTE: the read figure is a best-effort userspace
 * measurement; without root to drop kernel caches, a read shortly after a
 * write can be partly cushioned by page cache, so treat it as an
 * optimistic upper bound rather than a guarantee of sustained speed.
 */
class StorageBenchmark {

    suspend fun run(directoryPath: String, sampleSizeMb: Int = 64): BenchmarkResult? =
        withContext(Dispatchers.IO) {
            val dir = File(directoryPath)
            if (!dir.exists() || !dir.canWrite()) return@withContext null

            val testFile = File(dir, ".zeyos_bench_${System.currentTimeMillis()}.tmp")
            val sampleBytes = sampleSizeMb * 1024 * 1024
            val buffer = ByteArray(1024 * 1024) { (it % 256).toByte() }

            try {
                val writeStartNs = System.nanoTime()
                RandomAccessFile(testFile, "rw").use { raf ->
                    var written = 0
                    while (written < sampleBytes) {
                        raf.write(buffer)
                        written += buffer.size
                    }
                    raf.fd.sync() // force to physical storage, not just page cache
                }
                val writeSeconds = (System.nanoTime() - writeStartNs) / 1_000_000_000.0

                val readStartNs = System.nanoTime()
                RandomAccessFile(testFile, "r").use { raf ->
                    var readTotal = 0
                    val readBuf = ByteArray(1024 * 1024)
                    while (readTotal < sampleBytes) {
                        val n = raf.read(readBuf)
                        if (n <= 0) break
                        readTotal += n
                    }
                }
                val readSeconds = (System.nanoTime() - readStartNs) / 1_000_000_000.0

                BenchmarkResult(
                    path = directoryPath,
                    writeMbPerSec = sampleSizeMb / writeSeconds,
                    readMbPerSec = sampleSizeMb / readSeconds,
                    sampleSizeMb = sampleSizeMb
                )
            } catch (e: Exception) {
                null
            } finally {
                testFile.delete()
            }
        }
}
