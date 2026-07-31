package com.zeyos.app.storage

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

data class StorageInfo(
    val label: String,
    val path: String,
    val isRemovable: Boolean,
    val isEmulated: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    val state: String
)

/**
 * Enumerates internal storage plus any externally mounted volumes (MicroSD,
 * USB OTG mass storage) Android has already mounted. Relies on
 * StorageManager rather than raw USB Host APIs — it will only see a USB OTG
 * drive if Android/the OEM mounted it as a storage volume, which is the
 * normal case for mass-storage-class USB drives on most Android 9+ devices,
 * but isn't guaranteed on every OEM skin.
 */
class StorageDetector(private val context: Context) {

    fun detectAll(): List<StorageInfo> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val results = mutableListOf<StorageInfo>()

        val volumes: List<StorageVolume> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) storageManager.storageVolumes
            else emptyList()

        for (volume in volumes) {
            val dir = resolveDirectory(volume) ?: continue
            if (!dir.exists()) continue

            results.add(
                StorageInfo(
                    label = volume.getDescription(context) ?: dir.name,
                    path = dir.absolutePath,
                    isRemovable = volume.isRemovable,
                    isEmulated = volume.isEmulated,
                    totalBytes = dir.totalSpace,
                    freeBytes = dir.freeSpace,
                    state = volume.state
                )
            )
        }
        return results
    }

    private fun resolveDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        // getDirectory() only became public API in R (30). Below that, the same
        // info was exposed via a hidden getPathFile()/getPath() method — not part
        // of the public SDK, so this is a best-effort reflective fallback for
        // older low-end devices; if it fails, that volume is simply skipped
        // rather than crashing the scan.
        return try {
            volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
        } catch (e: Exception) {
            try {
                val path = volume.javaClass.getMethod("getPath").invoke(volume) as? String
                path?.let { File(it) }
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun detectRemovableOnly(): List<StorageInfo> = detectAll().filter { it.isRemovable }

    fun recommendSwapTarget(minFreeGb: Int = 4): StorageInfo? =
        detectRemovableOnly()
            .filter { it.freeBytes >= minFreeGb * 1024L * 1024L * 1024L }
            .maxByOrNull { it.freeBytes }
}
