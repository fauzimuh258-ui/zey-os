package com.zeyos.app.manager

import com.zeyos.app.util.Logger
import com.zeyos.app.util.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ModelManager(private val baseUrl: String = "http://127.0.0.1:11434") {

    private var modelCache: List<String> = emptyList()
    private var cacheTimestamp: Long = 0L
    private val cacheTtlMs = 15_000L // Avoid hammering Ollama every 5s dashboard poll

    suspend fun getInstalledModels(forceRefresh: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && modelCache.isNotEmpty() && (now - cacheTimestamp) < cacheTtlMs) {
            return@withContext modelCache
        }

        val models = mutableListOf<String>()
        try {
            RetryPolicy.retry(
                times = 2,
                initialDelayMs = 500,
                onRetry = { attempt, e -> Logger.w("ModelManager", "getInstalledModels retry #$attempt: ${e.message}") }
            ) {
                val url = URL("$baseUrl/api/tags")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val array = json.optJSONArray("models")
                    models.clear()
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            models.add(array.getJSONObject(i).getString("name"))
                        }
                    }
                } else {
                    throw IllegalStateException("Ollama returned HTTP ${conn.responseCode}")
                }
            }
            modelCache = models
            cacheTimestamp = now
        } catch (e: Exception) {
            Logger.e("ModelManager", "getInstalledModels failed after retries", e)
            return@withContext modelCache // Serve stale cache over a hard failure
        }
        return@withContext models
    }

    suspend fun downloadModel(modelName: String, onProgress: ((String) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                RetryPolicy.retry(
                    times = 3,
                    initialDelayMs = 2000,
                    onRetry = { attempt, e ->
                        Logger.w("ModelManager", "downloadModel($modelName) retry #$attempt: ${e.message}")
                        onProgress?.invoke("Retry $attempt/3 for $modelName...")
                    }
                ) {
                    onProgress?.invoke("Downloading $modelName...")
                    val url = URL("$baseUrl/api/pull")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 15 * 60 * 1000 // pulls can legitimately take minutes on slow links

                    val jsonParam = JSONObject().apply {
                        put("name", modelName)
                        put("stream", false)
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    if (conn.responseCode != 200) {
                        throw IllegalStateException("Pull failed with HTTP ${conn.responseCode}")
                    }
                }
                cacheTimestamp = 0L // Invalidate cache so the new model shows up immediately
                onProgress?.invoke("$modelName ready.")
                true
            } catch (e: Exception) {
                Logger.e("ModelManager", "downloadModel($modelName) failed permanently", e)
                onProgress?.invoke("Failed to download $modelName.")
                false
            }
        }

    suspend fun deleteModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/delete")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 3000

            val jsonParam = JSONObject().apply { put("name", modelName) }
            OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

            val success = conn.responseCode == 200
            if (success) cacheTimestamp = 0L
            success
        } catch (e: Exception) {
            Logger.e("ModelManager", "deleteModel($modelName) failed", e)
            false
        }
    }

    /**
     * Storage hygiene: if only one model should be resident at a time on a
     * 3GB-RAM device, drop everything except [keepModel]. Returns removed models.
     */
    suspend fun autoCleanup(keepModel: String): List<String> = withContext(Dispatchers.IO) {
        val removed = mutableListOf<String>()
        val installed = getInstalledModels(forceRefresh = true)
        for (model in installed) {
            if (model != keepModel && deleteModel(model)) {
                removed.add(model)
            }
        }
        if (removed.isNotEmpty()) Logger.i("ModelManager", "Auto-cleanup removed: ${removed.joinToString()}")
        removed
    }
}
