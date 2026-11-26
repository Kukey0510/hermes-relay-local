package com.hermesandroid.relay.ui.scripts

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ScriptSyncGameStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val old = "2026-11-24T00:00:00Z"
    private val current = "2026-11-25T00:00:00Z"
    private val newer = "2026-11-26T00:00:00Z"
    private fun entry(id: String, game: String? = null, games: String? = null): String =
        """{"id":"$id","name":"$id","tags":[],"updatedAt":"now","description":"","sourceNote":"",${game?.let { "\"game\":\"$it\"," }.orEmpty()}${games?.let { "\"games\":$it," }.orEmpty()}"targetPackages":["pkg"],"steps":[{"op":"wait","ms":1}]}"""
    private fun catalog(version: String, vararg entries: String) =
        """{"version":"$version","games":{"jc":{"package":"pkg"}},"scripts":[${entries.joinToString(",")}]}"""
    private fun fullSnapshot(content: String) = content.replaceFirst("{", "{\"full_snapshot\":true,")
    private fun ids(content: String): List<String> =
        ((Json.parseToJsonElement(content) as JsonObject)["scripts"] as JsonArray)
            .map { ((it as JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content }
    private fun withServer(body: String, block: (ScriptSyncManager, DiskStorage, MutableList<String>) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val storage = DiskStorage(temporary.newFolder())
            val logs = mutableListOf<String>()
            val manager = ScriptSyncManager(
                ScriptApiClient({ server.url("/").toString() }, { null }), storage,
                isRunActive = { false }, log = { logs += it }, now = { 10_000L },
            )
            block(manager, storage, logs)
        }
    }

    @Test fun refreshWritesEveryGameAndNoLegacyCatalog() = withServer(
        catalog(newer, entry("jc", "jc"), entry("bb", "bb"), entry("blhx", "blhx")),
    ) { manager, storage, _ ->
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(setOf("jc", "bb", "blhx"), storage.writes.toSet())
        assertEquals(listOf("jc"), ids(storage.readCatalogForGame("jc")!!))
        assertEquals(listOf("bb"), ids(storage.readCatalogForGame("bb")!!))
        assertFalse(storage.legacy.exists())
    }

    @Test fun olderGameSkipsWhileOtherGameUpdates() = withServer(
        catalog(current, entry("incoming-jc", "jc"), entry("incoming-blhx", "blhx"), entry("incoming-bb", "bb")),
    ) { manager, storage, logs ->
        storage.seed("jc", catalog(newer, entry("keep-jc", "jc")))
        storage.seed("blhx", catalog(old, entry("old-blhx", "blhx")))
        storage.seed("bb", catalog(old, entry("old-bb", "bb")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("blhx", "bb"), storage.writes)
        assertEquals(listOf("keep-jc"), ids(storage.readCatalogForGame("jc")!!))
        assertEquals(listOf("incoming-blhx"), ids(storage.readCatalogForGame("blhx")!!))
        assertEquals(listOf("incoming-bb"), ids(storage.readCatalogForGame("bb")!!))
        assertTrue(logs.single().contains("game=jc"))
        assertEquals(0, storage.backups("jc").size)
        assertEquals(1, storage.backups("blhx").size)
        assertEquals(1, storage.backups("bb").size)
    }

    @Test fun singleGameResponseKeepsOtherTwoGameFilesByteForByte() = withServer(
        catalog(newer, entry("new-jc", "jc")),
    ) { manager, storage, _ ->
        val blhx = catalog(old, entry("keep-blhx", "blhx"))
        val bb = catalog(old, entry("keep-bb", "bb"))
        storage.seed("jc", catalog(old, entry("old-jc", "jc")))
        storage.seed("blhx", blhx)
        storage.seed("bb", bb)
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("jc"), storage.writes)
        assertEquals(blhx, storage.readCatalogForGame("blhx"))
        assertEquals(bb, storage.readCatalogForGame("bb"))
        assertTrue(storage.backups("blhx").isEmpty())
        assertTrue(storage.backups("bb").isEmpty())
    }

    @Test fun singleGameRefreshInPartialMigrationKeepsLegacyGamesVisible() = withServer(
        catalog(newer, entry("new-jc", "jc")),
    ) { manager, storage, _ ->
        val legacy = catalog(old, entry("keep-blhx", "blhx"), entry("keep-bb", "bb"), entry("old-jc", "jc"))
        storage.legacy.parentFile!!.mkdirs()
        storage.legacy.writeText(legacy)
        storage.seed("jc", catalog(current, entry("cached-jc", "jc")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("jc"), storage.writes)
        assertEquals(legacy, storage.legacy.readText())
        assertEquals(setOf("new-jc", "keep-blhx", "keep-bb"),
            ids(ScriptCatalog.readContent(storage.directory, { error("assets") }, {})).toSet())
    }

    @Test fun emptyUnmarkedResponseDoesNotDeleteEvenUnknownScripts() = withServer(catalog(newer)) { manager, storage, _ ->
        val unknown = catalog(old, entry("keep-unknown"))
        storage.seed(ScriptCatalog.UNKNOWN_GAME, unknown)
        storage.seed("jc", catalog(old, entry("keep-jc", "jc")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertTrue(storage.writes.isEmpty())
        assertEquals(unknown, storage.readCatalogForGame(ScriptCatalog.UNKNOWN_GAME))
        assertEquals(listOf("keep-jc"), ids(storage.readCatalogForGame("jc")!!))
        assertEquals(0L, storage.lastSyncMs)
    }

    @Test fun falseOrNonBooleanSnapshotFlagNeverAuthorizesDeletion() {
        for (flag in listOf("false", "\"true\"", "null", "1", "{}")) {
            withServer(catalog(newer, entry("new-jc", "jc")).replaceFirst("{", "{\"full_snapshot\":$flag,")) { manager, storage, _ ->
                val bb = catalog(old, entry("keep-bb", "bb"))
                storage.seed("bb", bb)
                assertTrue(runBlocking { manager.refresh(force = true) })
                assertEquals(listOf("jc"), storage.writes)
                assertEquals(bb, storage.readCatalogForGame("bb"))
            }
        }
    }

    @Test fun fullSnapshotRemovesLegacyOnlyGamesDuringPartialMigration() = withServer(
        fullSnapshot(catalog(newer, entry("new-jc", "jc"))),
    ) { manager, storage, _ ->
        val legacy = catalog(old, entry("removed-blhx", "blhx"), entry("removed-bb", "bb"))
        storage.legacy.parentFile!!.mkdirs()
        storage.legacy.writeText(legacy)
        storage.seed("jc", catalog(current, entry("old-jc", "jc")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(setOf("jc", "blhx", "bb"), storage.writes.toSet())
        assertEquals(emptyList<String>(), ids(storage.readCatalogForGame("blhx")!!))
        assertEquals(emptyList<String>(), ids(storage.readCatalogForGame("bb")!!))
        assertEquals(listOf("new-jc"), ids(ScriptCatalog.readContent(storage.directory, { error("assets") }, {})))
        assertEquals(legacy, storage.legacy.readText())
    }

    @Test fun explicitEmptySnapshotEmptiesAllThreeGames() = withServer(
        fullSnapshot(catalog(newer)),
    ) { manager, storage, _ ->
        for (game in listOf("blhx", "jc", "bb")) storage.seed(game, catalog(old, entry("old-$game", game)))
        assertTrue(runBlocking { manager.refresh(force = true) })
        for (game in listOf("blhx", "jc", "bb")) {
            assertEquals(emptyList<String>(), ids(storage.readCatalogForGame(game)!!))
            assertEquals(1, storage.backups(game).size)
        }
        assertEquals(emptyList<String>(), ids(ScriptCatalog.readContent(storage.directory, { error("assets") }, {})))
    }

    @Test fun equalGameSkipsWithoutBlockingNewerGame() = withServer(
        catalog(current, entry("changed-jc", "jc"), entry("new-bb", "bb")),
    ) { manager, storage, _ ->
        storage.seed("jc", catalog(current, entry("keep-jc", "jc")))
        storage.seed("bb", catalog(old, entry("old-bb", "bb")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("bb"), storage.writes)
        assertEquals(listOf("keep-jc"), ids(storage.readCatalogForGame("jc")!!))
    }

    @Test fun unknownGameScriptsAreRetained() = withServer(
        catalog(newer, entry("no-game"), entry("blank", " "),
            entry("null", "jc").replace("\"game\":\"jc\"", "\"game\":null,\"games\":null"), entry("jc", "jc")),
    ) { manager, storage, _ ->
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("no-game", "blank", "null"), ids(storage.readCatalogForGame(ScriptCatalog.UNKNOWN_GAME)!!))
        assertTrue(storage.directory.resolve("scripts/catalog/<unknown>.json").isFile)
    }

    @Test fun multiGameScriptIsWrittenOnceInEveryDeclaredGame() = withServer(
        catalog(newer, entry("shared", "jc", "[\"jc\",\"bb\"]")),
    ) { manager, storage, _ ->
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(setOf("jc", "bb"), storage.writes.toSet())
        assertEquals(listOf("shared"), ids(storage.readCatalogForGame("jc")!!))
        assertEquals(listOf("shared"), ids(storage.readCatalogForGame("bb")!!))
    }

    @Test fun refreshPreservesRawStepsAndGameDeclaration() {
        val body = catalog(newer, entry("jc", "jc"))
        withServer(body) { manager, storage, _ ->
            assertTrue(runBlocking { manager.refresh(force = true) })
            assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(storage.readCatalogForGame("jc")!!))
        }
    }

    @Test fun removedGamesAreEmptiedWhenSnapshotIsNewer() = withServer(
        fullSnapshot(catalog(newer, entry("jc", "jc"))),
    ) { manager, storage, _ ->
        storage.seed("bb", catalog(old, entry("removed", "bb")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(emptyList<String>(), ids(storage.readCatalogForGame("bb")!!))
        assertEquals(listOf("removed"), ids(storage.backups("bb").single().readText()))
    }

    @Test fun removedGameWithNewerLocalVersionRemainsIntact() = withServer(
        fullSnapshot(catalog(current, entry("jc", "jc"))),
    ) { manager, storage, _ ->
        storage.seed("bb", catalog(newer, entry("keep", "bb")))
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("jc"), storage.writes)
        assertEquals(listOf("keep"), ids(storage.readCatalogForGame("bb")!!))
    }

    @Test fun unknownServerVersionProtectsKnownGameButDoesNotBlockUnknownLocalGame() = withServer(
        catalog("unknown", entry("new-jc", "jc"), entry("new-bb", "bb")),
    ) { manager, storage, _ ->
        storage.seed("jc", catalog(current, entry("keep", "jc")))
        assertFalse(runBlocking { manager.refresh(force = true) })
        assertEquals(listOf("bb"), storage.writes)
        assertEquals(listOf("keep"), ids(storage.readCatalogForGame("jc")!!))
        assertEquals(ScriptSyncKind.Offline, manager.state.kind)
    }

    @Test fun legacyRefreshReadsOldCatalogAndWritesOnlyNewFiles() = withServer(
        catalog(newer, entry("new-jc", "jc"), entry("new-bb", "bb")),
    ) { manager, storage, _ ->
        val legacy = catalog(old, entry("old-jc", "jc"), entry("old-bb", "bb"))
        storage.legacy.parentFile!!.mkdirs()
        storage.legacy.writeText(legacy)
        assertTrue(runBlocking { manager.refresh(force = true) })
        assertEquals(legacy, storage.legacy.readText())
        assertEquals(setOf("jc", "bb"), ScriptCatalog.catalogFiles(storage.directory).map { it.nameWithoutExtension }.toSet())
        assertEquals(setOf("new-jc", "new-bb"), ids(ScriptCatalog.readContent(storage.directory, { error("assets") }, {})).toSet())
    }

    @Test fun perGameBackupsHaveIndependentRetention() {
        val directory = temporary.root.resolve("scripts/catalog").apply { mkdirs() }
        val jc = directory.resolve("jc.json")
        val bb = directory.resolve("bb.json").apply { writeText("bb") }
        backupCatalogFile(bb, 10_000L)
        repeat(5) { index -> jc.writeText("jc-$index"); backupCatalogFile(jc, 10_000L) }
        val jcBackups = directory.listFiles()!!.filter { it.name.startsWith("jc.json.bak_") }.sortedBy { it.name }
        assertEquals(listOf("jc-2", "jc-3", "jc-4"), jcBackups.map { it.readText() })
        assertEquals("bb", directory.listFiles()!!.single { it.name.startsWith("bb.json.bak_") }.readText())
    }

    private class DiskStorage(val directory: File) : ScriptSyncStorage {
        val legacy get() = directory.resolve("scripts/catalog.json")
        override var lastAttemptMs = 0L
        override var lastSyncMs = 0L
        override var pendingTags: Map<String, List<String>>? = null
        val writes = mutableListOf<String>()
        override fun readCatalog(): String? = legacy.takeIf(File::isFile)?.readText()
        override fun backupCatalog(timestampMs: Long) = backupCatalogFile(legacy, timestampMs)
        override fun writeCatalog(content: String) = error("legacy writes are forbidden")
        override fun catalogGames() = ScriptCatalog.cachedGames(directory)
        override fun readCatalogForGame(game: String) = ScriptCatalog.cachedForGame(directory, game)
        override fun backupCatalogForGame(game: String, timestampMs: Long) {
            val file = ScriptCatalog.gameCacheFile(directory, game)
            if (file.isFile) backupCatalogFile(file, timestampMs)
            else if (readCatalogForGame(game) != null) backupCatalog(timestampMs)
        }
        override fun writeCatalogForGame(game: String, content: String) {
            writes += game
            ScriptCatalog.writeForGame(directory, game, content)
        }
        fun seed(game: String, content: String) = ScriptCatalog.writeForGame(directory, game, content)
        fun backups(game: String) = directory.resolve("scripts/catalog").listFiles().orEmpty()
            .filter { it.name.startsWith("$game.json.bak_") }.sortedBy { it.name }
    }
}
