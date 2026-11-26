package com.hermesandroid.relay.ui.scripts

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptSyncProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val older = catalog("2026-11-18T11:59:51Z")
    private val local = catalog("2026-11-19T11:59:51Z")
    private val newer = catalog("2026-11-20T11:59:51Z")

    @Test fun olderServerSkipsWriteAndLogsWithoutAdvancingSyncTime() = server(older) { server ->
        val storage = storage(local)
        storage.lastSyncMs = 123L
        val messages = mutableListOf<String>()
        val manager = manager(server, storage, log = { messages += it })
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(0, storage.writes)
        assertTrue(storage.backups().isEmpty())
        assertEquals(local, storage.readCatalog())
        assertEquals(ScriptSyncKind.Synced, manager.state.kind)
        assertEquals(123L, manager.state.lastSyncMs)
        assertEquals(123L, storage.lastSyncMs)
        assertEquals(listOf("skip: server older (server=2026-11-18T11:59:51Z, local=2026-11-19T11:59:51Z)"), messages)
    }

    @Test fun equalVersionSkipsWriteAndBackup() = server(local) { server ->
        val storage = storage(local)
        storage.lastSyncMs = 123L
        val manager = manager(server, storage)
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(0, storage.writes)
        assertTrue(storage.backups().isEmpty())
        assertEquals(123L, storage.lastSyncMs)
        assertEquals(ScriptSyncKind.Synced, manager.state.kind)
    }

    @Test fun newerVersionBacksUpBeforeSingleWrite() = server(newer) { server ->
        val storage = storage(local)
        storage.beforeWrite = { assertEquals(listOf(local), storage.backups().map { it.readText() }) }
        val manager = manager(server, storage)
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(1, storage.writes)
        assertEquals(newer, storage.readCatalog())
        assertEquals(10_000L, storage.lastSyncMs)
        assertEquals("catalog.json.bak_19700101-000010", storage.backups().single().name)
    }

    @Test fun corruptLocalCacheIsBackedUpBeforeRepair() = server(newer) { server ->
        val storage = storage("{broken")
        storage.beforeWrite = { assertEquals("{broken", storage.backups().single().readText()) }
        assertTrue(runBlocking { manager(server, storage).refresh(force = true) })
        assertEquals(1, storage.writes)
        assertEquals(newer, storage.readCatalog())
    }

    @Test fun missingLocalVersionAllowsReplacementWithBackup() = server(newer) { server ->
        val storage = storage("""{"scripts":[]}""")
        assertTrue(runBlocking { manager(server, storage).refresh(force = true) })
        assertEquals(1, storage.writes)
        assertEquals("""{"scripts":[]}""", storage.backups().single().readText())
    }

    @Test fun failedFetchNeverWritesOrBacksUp() = server(newer, code = 500) { server ->
        val storage = storage(local)
        storage.lastSyncMs = 123L
        val manager = manager(server, storage)
        assertFalse(runBlocking { manager.refresh(force = true) })
        assertEquals(0, storage.writes)
        assertTrue(storage.backups().isEmpty())
        assertEquals(local, storage.readCatalog())
        assertEquals(123L, storage.lastSyncMs)
        assertEquals(ScriptSyncKind.Offline, manager.state.kind)
    }

    @Test fun runningRefusesEvenForcedRefreshAndSyncResumesAfterRun() = server(newer) { server ->
        var running = true
        val storage = storage(local)
        val manager = manager(server, storage, active = { running })
        assertFalse(runBlocking { manager.refresh(force = true) })
        assertEquals(0, server.requestCount)
        assertEquals(0, storage.writes)
        assertEquals(0L, storage.lastAttemptMs)
        assertEquals("脚本运行中，不同步", manager.state.message)
        running = false
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(1, server.requestCount)
        assertEquals(1, storage.writes)
        assertNull(manager.state.message)
    }

    @Test fun runStartingDuringFetchPreventsBackupAndWrite() = server(newer) { server ->
        val running = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                running.set(true)
                return MockResponse().setBody(newer)
            }
        }
        val storage = storage(local)
        val manager = manager(server, storage, active = running::get)
        assertFalse(runBlocking { manager.refresh(force = true) })
        assertEquals(0, storage.writes)
        assertTrue(storage.backups().isEmpty())
        assertEquals(local, storage.readCatalog())
        assertEquals("脚本运行中，不同步", manager.state.message)
    }

    @Test fun unknownServerVersionCannotReplaceKnownLocalVersion() = server(catalog("unknown")) { server ->
        val storage = storage(local)
        val manager = manager(server, storage)
        assertFalse(runBlocking { manager.refresh(force = true) })
        assertEquals(0, storage.writes)
        assertTrue(storage.backups().isEmpty())
        assertEquals(local, storage.readCatalog())
    }

    @Test fun backupsKeepNewestThreeAndPreserveSameSecondCopies() {
        val storage = storage(local)
        val unrelated = File(storage.file.parentFile, "keep.txt").apply { writeText("keep") }
        repeat(5) { index ->
            storage.file.writeText("old-$index")
            storage.backupCatalog(10_000L)
        }
        assertEquals(listOf("old-2", "old-3", "old-4"), storage.backups().map { it.readText() })
        assertEquals(listOf("catalog.json.bak_19700101-000012", "catalog.json.bak_19700101-000013",
            "catalog.json.bak_19700101-000014"), storage.backups().map { it.name })
        assertEquals("keep", unrelated.readText())
    }

    @Test fun backupFailurePreventsReplacement() = server(newer) { server ->
        val storage = storage(local)
        storage.backupFails = true
        assertFalse(runBlocking { manager(server, storage).refresh(force = true) })
        assertEquals(0, storage.writes)
        assertEquals(local, storage.readCatalog())
    }

    private fun catalog(version: String) = """{"version":"$version","full_snapshot":true,"scripts":[]}"""
    private fun storage(content: String) = FileStorage(File(temporary.newFolder(), "catalog.json").apply { writeText(content) })
    private fun manager(server: MockWebServer, storage: FileStorage, active: () -> Boolean = { false },
        log: (String) -> Unit = {}) = ScriptSyncManager(
        ScriptApiClient(baseUrl = { server.url("/").toString() }, bearer = { null }), storage,
        isRunActive = active, log = log, now = { 10_000L },
    )
    private fun server(body: String, code: Int = 200, block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.enqueue(MockResponse().setResponseCode(code).setBody(body)); block(server) }
    }

    private class FileStorage(val file: File) : ScriptSyncStorage {
        override var lastAttemptMs = 0L
        override var lastSyncMs = 0L
        override var pendingTags: Map<String, List<String>>? = null
        var writes = 0
        var backupFails = false
        var beforeWrite: () -> Unit = {}
        override fun readCatalog() = file.readText()
        override fun backupCatalog(timestampMs: Long) {
            if (backupFails) throw java.io.IOException("backup failed")
            backupCatalogFile(file, timestampMs)
        }
        override fun writeCatalog(content: String) { beforeWrite(); writes++; file.writeText(content) }
        fun backups() = file.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith("catalog.json.bak_") }.sortedBy { it.name }
    }
}
