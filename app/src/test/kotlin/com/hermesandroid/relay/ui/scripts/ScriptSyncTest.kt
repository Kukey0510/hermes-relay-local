package com.hermesandroid.relay.ui.scripts

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSyncTest {
    private val catalog = """{"version":"2026-11-19T11:59:51Z","scripts":[]}"""

    @Test
    fun cacheIsPreferredWithoutReadingAssets() {
        var assetRead = false
        val selected = ScriptCatalog.preferredContent(catalog) { assetRead = true; "[]" }
        assertEquals(catalog, selected)
        assertFalse(assetRead)
    }

    @Test
    fun failedRefreshKeepsOldCache() = withServer(MockResponse().setResponseCode(500)) { server ->
        val storage = FakeStorage(cached = catalog)
        val manager = manager(server, storage)
        assertFalse(runBlocking { manager.refresh() })
        assertEquals(catalog, storage.cached)
        assertEquals(0, storage.writes)
        assertEquals(ScriptSyncKind.Offline, manager.state.kind)
    }

    @Test
    fun automaticRefreshIsThrottledForFiveMinutes() = withServer(MockResponse().setBody(catalog)) { server ->
        var now = 10_000L
        val storage = FakeStorage()
        val manager = ScriptSyncManager(api(server), storage) { now }
        assertTrue(runBlocking { manager.refresh() })
        now += SCRIPT_CATALOG_THROTTLE_MS - 1
        assertFalse(runBlocking { manager.refresh() })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failedTagWriteRemainsPending() = withServer(MockResponse().setResponseCode(503)) { server ->
        val storage = FakeStorage()
        val manager = manager(server, storage)
        val tags = mapOf("one" to listOf("daily"))
        assertFalse(runBlocking { manager.submitTags(tags) })
        assertEquals(tags, storage.pendingTags)
        assertEquals(ScriptSyncKind.Pending, manager.state.kind)
    }

    private fun manager(server: MockWebServer, storage: FakeStorage) =
        ScriptSyncManager(api(server), storage) { 10_000L }

    private fun api(server: MockWebServer) = ScriptApiClient(
        baseUrl = { server.url("/").toString() }, bearer = { "token" },
    )

    private fun withServer(response: MockResponse, block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.enqueue(response); block(server) }
    }

    private class FakeStorage(var cached: String? = null) : ScriptSyncStorage {
        override var lastAttemptMs = 0L
        override var lastSyncMs = 0L
        override var pendingTags: Map<String, List<String>>? = null
        var writes = 0
        override fun readCatalog() = cached
        override fun backupCatalog(timestampMs: Long) = Unit
        override fun writeCatalog(content: String) { writes++; cached = content }
    }
}
