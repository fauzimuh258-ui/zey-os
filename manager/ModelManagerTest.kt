package com.zeyos.app.manager

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// NOTE: getInstalledModels/downloadModel run on Dispatchers.IO inside ModelManager,
// which runTest's virtual clock cannot fast-forward — the retry-related tests below
// incur their real (small) backoff delays: ~0.5s and ~6s respectively.
class ModelManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelManager = ModelManager(baseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getInstalledModels parses model names from a successful response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"tinyllama:1.1b-chat-v1.0-q4_0"},{"name":"gemma:2b-instruct-q4_0"}]}"""
            )
        )

        val models = modelManager.getInstalledModels()

        assertEquals(listOf("tinyllama:1.1b-chat-v1.0-q4_0", "gemma:2b-instruct-q4_0"), models)
        assertEquals("/api/tags", server.takeRequest().path)
    }

    @Test
    fun `getInstalledModels serves cached result within TTL without a second request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"a"}]}"""))

        modelManager.getInstalledModels()
        modelManager.getInstalledModels() // should hit cache, not the server

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getInstalledModels forceRefresh bypasses the cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"a"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"a"},{"name":"b"}]}"""))

        val first = modelManager.getInstalledModels()
        val second = modelManager.getInstalledModels(forceRefresh = true)

        assertEquals(listOf("a"), first)
        assertEquals(listOf("a", "b"), second)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `getInstalledModels retries on server error and eventually succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"tinyllama"}]}"""))

        val models = modelManager.getInstalledModels()

        assertEquals(listOf("tinyllama"), models)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `getInstalledModels returns stale cache when all retries fail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"cached_model"}]}"""))
        modelManager.getInstalledModels() // warms the cache

        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val result = modelManager.getInstalledModels(forceRefresh = true)

        assertEquals(listOf("cached_model"), result) // falls back to stale cache, doesn't throw
    }

    @Test
    fun `downloadModel returns true and reports progress on success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val progressMessages = mutableListOf<String>()

        val result = modelManager.downloadModel("tinyllama:1.1b-chat-v1.0-q4_0") { progressMessages.add(it) }

        assertTrue(result)
        assertTrue(progressMessages.any { it.contains("ready") })
        val recorded = server.takeRequest()
        assertEquals("/api/pull", recorded.path)
        assertEquals("POST", recorded.method)
    }

    @Test
    fun `downloadModel retries then fails after exhausting attempts`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val progressMessages = mutableListOf<String>()

        val result = modelManager.downloadModel("gemma:2b-instruct-q4_0") { progressMessages.add(it) }

        assertFalse(result)
        assertTrue(progressMessages.any { it.contains("Failed") })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `deleteModel invalidates the cache so the next read hits the server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[{"name":"a"}]}"""))
        modelManager.getInstalledModels() // warms cache

        server.enqueue(MockResponse().setResponseCode(200)) // DELETE response
        modelManager.deleteModel("a")

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val afterDelete = modelManager.getInstalledModels() // must hit server again, not stale cache

        assertEquals(emptyList<String>(), afterDelete)
        assertEquals(3, server.requestCount)
    }
}
