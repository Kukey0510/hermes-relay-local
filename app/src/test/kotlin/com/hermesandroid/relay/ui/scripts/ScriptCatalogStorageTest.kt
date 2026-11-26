package com.hermesandroid.relay.ui.scripts

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptCatalogStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val filesDir get() = temporary.root
    private fun entry(id: String, game: String = "jc", name: String = id) =
        """{"id":"$id","name":"$name","game":"$game","tags":[],"updatedAt":"now","description":"","steps":[],"sourceNote":""}"""
    private fun catalog(vararg entries: String) =
        """{"version":"2026-11-26T00:00:00Z","scripts":[${entries.joinToString(",")}]}"""
    private fun put(path: String, content: String): File = filesDir.resolve(path).apply {
        parentFile!!.mkdirs(); writeText(content)
    }
    private fun read(warn: (String) -> Unit = {}): String =
        ScriptCatalog.readContent(filesDir, { error("unexpected asset read") }, warn)

    @Test fun twoGameCatalogsMergeInFilenameOrder() {
        put("scripts/catalog/jc.json", catalog(entry("jc")))
        put("scripts/catalog/blhx.json", catalog(entry("blhx", "blhx")))
        assertEquals(listOf("blhx", "jc"), ScriptCatalog.decode(read()).map { it.id })
    }

    @Test fun duplicateIdUsesLaterFilenameAndWarns() {
        put("scripts/catalog/b.json", catalog(entry("same", name = "later")))
        put("scripts/catalog/a.json", catalog(entry("same", name = "earlier")))
        val warnings = mutableListOf<String>()
        assertEquals("later", ScriptCatalog.decode(read { warnings += it }).single().name)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("same"))
    }

    @Test fun aSingleNewFileAlsoResolvesDuplicateIds() {
        put("scripts/catalog/jc.json", catalog(entry("same", name = "first"), entry("same", name = "last")))
        val warnings = mutableListOf<String>()
        assertEquals("last", ScriptCatalog.decode(read { warnings += it }).single().name)
        assertEquals(1, warnings.size)
    }

    @Test fun invalidReplacementKeepsTheLastGoodGameFile() {
        val good = catalog(entry("good"))
        ScriptCatalog.writeForGame(filesDir, "jc", good)
        assertTrue(runCatching {
            ScriptCatalog.writeForGame(filesDir, "jc", """{"version":"bad","scripts":[{}]}""")
        }.isFailure)
        assertEquals(good, filesDir.resolve("scripts/catalog/jc.json").readText())
    }

    @Test fun missingDirectoryReadsUnchangedLegacyCatalog() {
        val legacy = catalog(entry("legacy"))
        put("scripts/catalog.json", legacy)
        assertEquals(legacy, read())
        assertEquals(ScriptCatalog.decode(legacy), ScriptCatalog.decode(read()))
    }

    @Test fun emptyDirectoryFallsBackToLegacy() {
        filesDir.resolve("scripts/catalog").mkdirs()
        val legacy = catalog(entry("legacy"))
        put("scripts/catalog.json", legacy)
        assertEquals(legacy, read())
    }

    @Test fun temporaryAndBackupFilesDoNotSuppressLegacyFallback() {
        put("scripts/catalog/jc.json.tmp", "broken")
        put("scripts/catalog/jc.json.bak_20261126-000000", "broken")
        put("scripts/catalog/folder.json/ignore.txt", "not a file")
        val legacy = catalog(entry("legacy"))
        put("scripts/catalog.json", legacy)
        assertEquals(legacy, read())
    }

    @Test fun noDiskCatalogStillUsesAssets() {
        val asset = catalog(entry("asset"))
        assertEquals(asset, ScriptCatalog.readContent(filesDir, { asset }, {}))
    }

    @Test fun newCatalogsTakePrecedenceOverLegacyWholeFile() {
        put("scripts/catalog.json", catalog(entry("old")))
        put("scripts/catalog/jc.json", catalog(entry("new")))
        assertEquals(listOf("new"), ScriptCatalog.decode(read()).map { it.id })
    }

    @Test fun emptyNewPartitionDoesNotResurrectLegacyScriptsForThatGame() {
        put("scripts/catalog.json", catalog(entry("old-jc"), entry("keep-bb", "bb")))
        put("scripts/catalog/jc.json", catalog())
        assertEquals(listOf("keep-bb"), ScriptCatalog.decode(read()).map { it.id })
        assertEquals(setOf("jc", "bb"), ScriptCatalog.cachedGames(filesDir))
    }

    @Test fun newerPartitionWinsIdsAndDeclarationsOverLegacyFallback() {
        val legacy = catalog(entry("same", "bb", "old"), entry("keep", "bb"))
            .replaceFirst("{", "{\"games\":{\"jc\":{\"package\":\"old.pkg\"},\"bb\":{\"package\":\"bb.pkg\"}},")
        val newer = catalog(entry("same", "jc", "new"))
            .replaceFirst("{", "{\"games\":{\"jc\":{\"package\":\"new.pkg\"}},")
        put("scripts/catalog.json", legacy)
        put("scripts/catalog/jc.json", newer)
        val warnings = mutableListOf<String>()
        val merged = read { warnings += it }
        assertEquals("new", ScriptCatalog.decode(merged).single { it.id == "same" }.name)
        val games = (Json.parseToJsonElement(merged) as JsonObject)["games"] as JsonObject
        assertEquals(JsonPrimitive("new.pkg"), (games["jc"] as JsonObject)["package"])
        assertEquals(JsonPrimitive("bb.pkg"), (games["bb"] as JsonObject)["package"])
        assertEquals(1, warnings.size)
    }

    @Test fun completeNewLayoutSurvivesCorruptObsoleteLegacyCatalog() {
        put("scripts/catalog.json", "{broken")
        for (game in listOf("blhx", "jc", "bb")) put("scripts/catalog/$game.json", catalog(entry(game, game)))
        val warnings = mutableListOf<String>()
        assertEquals(setOf("blhx", "jc", "bb"), ScriptCatalog.decode(read { warnings += it }).map { it.game }.toSet())
        assertEquals(1, warnings.size)
    }

    @Test fun legacyFallbackCannotRestoreDeclarationsRemovedByNewPartition() {
        val legacy = catalog(entry("old-jc"), entry("keep-bb", "bb"))
            .replaceFirst("{", "{\"games\":{\"jc\":{\"package\":\"obsolete.pkg\"},\"bb\":{\"package\":\"bb.pkg\"}},")
        put("scripts/catalog.json", legacy)
        put("scripts/catalog/jc.json", catalog(entry("new-jc")))
        val games = (Json.parseToJsonElement(read()) as JsonObject)["games"] as JsonObject
        assertEquals(setOf("bb"), games.keys)
    }

    @Test fun missingPartitionDoesNotPullInAssetsOverAnInstalledCatalog() {
        put("scripts/catalog/jc.json", catalog(entry("jc")))
        assertEquals(listOf("jc"), ScriptCatalog.decode(read()).map { it.id })
    }

    @Test fun readingUnknownPartitionRetainsScriptsWithoutAddingAFourthGame() {
        ScriptCatalog.writeForGame(filesDir, ScriptCatalog.UNKNOWN_GAME,
            catalog(entry("no-game").replace("\"game\":\"jc\",", "")))
        for (game in listOf("blhx", "jc", "bb")) {
            ScriptCatalog.writeForGame(filesDir, game, catalog(entry(game, game)))
        }
        val entries = ScriptCatalog.decode(read())
        assertEquals(4, entries.size)
        assertEquals("", entries.single { it.id == "no-game" }.game)
        assertEquals(listOf("bb", "blhx", "jc"), scriptGamesOf(entries))
        assertEquals(4, filterScripts(entries, emptySet(), emptySet()).size)
    }

    @Test fun malformedNewCatalogIsAnErrorRatherThanSilentLegacyFallback() {
        put("scripts/catalog.json", catalog(entry("old")))
        put("scripts/catalog/jc.json", "{broken")
        assertTrue(runCatching { ScriptCatalog.decode(read()) }.isFailure)
    }

    @Test fun writesOnlyNewPathAndPreservesLegacyBytes() {
        val legacy = put("scripts/catalog.json", "legacy bytes")
        val content = catalog(entry("new"))
        ScriptCatalog.writeForGame(filesDir, "jc", content)
        assertEquals(content, filesDir.resolve("scripts/catalog/jc.json").readText())
        assertEquals("legacy bytes", legacy.readText())
        assertFalse(filesDir.resolve("scripts/catalog/jc.json.tmp").exists())
    }

    @Test fun firstWriteCreatesBothMissingDirectories() {
        assertFalse(filesDir.resolve("scripts").exists())
        val content = catalog(entry("first"))
        ScriptCatalog.writeForGame(filesDir, "jc", content)
        assertEquals(content, filesDir.resolve("scripts/catalog/jc.json").readText())
        assertFalse(filesDir.resolve("scripts/catalog.json").exists())
        assertFalse(filesDir.resolve("scripts/catalog/jc.json.tmp").exists())
    }

    @Test fun gameAndGamesGroupWithoutLosingRuntimeJsonOrDeclarations() {
        val source = """{"version":"2026-11-26T00:00:00Z","games":{"jc":{"package":"pkg"}},"scripts":[
            {"id":"shared","game":"jc","games":["blhx","jc"],"targetPackages":["pkg"],"start_at":1,"steps":[{"op":"wait","ms":1},{"op":"tap","x":2,"y":3}]},
            {"id":"unknown","steps":[]}, {"id":"blank","game":" ","games":[],"steps":[]}
        ]}"""
        val groups = ScriptCatalog.splitByGame(source)
        assertEquals(setOf("jc", "blhx", ScriptCatalog.UNKNOWN_GAME), groups.keys)
        val root = Json.parseToJsonElement(source) as JsonObject
        val expected = (root["scripts"] as JsonArray).first()
        for (game in listOf("jc", "blhx")) {
            val group = Json.parseToJsonElement(groups.getValue(game)) as JsonObject
            assertEquals(root["games"], group["games"])
            assertEquals(expected, (group["scripts"] as JsonArray).single())
        }
        val unknown = Json.parseToJsonElement(groups.getValue(ScriptCatalog.UNKNOWN_GAME)) as JsonObject
        assertEquals(2, (unknown["scripts"] as JsonArray).size)
    }

    @Test fun gamesOnlyAndMissingGameEntriesDecodeAndRoundTrip() {
        val source = entry("shared").replace("\"game\":\"jc\"", "\"games\":[\"jc\",\"blhx\"]")
        val decoded = ScriptCatalog.decode(catalog(source)).single()
        assertEquals("jc", decoded.game)
        assertEquals(listOf("jc", "blhx"), decoded.games)
        assertEquals(decoded, Json.decodeFromString<ScriptEntry>(Json.encodeToString(ScriptEntrySerializer, decoded)))
        val unknown = entry("unknown").replace("\"game\":\"jc\",", "")
        assertEquals("", ScriptCatalog.decode(catalog(unknown)).single().game)
        val nullGame = entry("unknown").replace("\"game\":\"jc\"", "\"game\":null,\"games\":null")
        assertEquals("", ScriptCatalog.decode(catalog(nullGame)).single().game)
    }

    @Test fun legacyPerGameReadDoesNotMixDifferentGamesVersions() {
        put("scripts/catalog.json", catalog(entry("jc", "jc")))
        assertNotNull(ScriptCatalog.cachedForGame(filesDir, "jc"))
        assertNull(ScriptCatalog.cachedForGame(filesDir, "blhx"))
        val newer = catalog(entry("new", "jc"))
        ScriptCatalog.writeForGame(filesDir, "jc", newer)
        assertEquals(newer, ScriptCatalog.cachedForGame(filesDir, "jc"))
    }

    @Test fun mergedRawCatalogPreservesGameDeclarationsAndObjectSteps() {
        val first = """{"version":"a","games":{"jc":{"package":"jc.pkg"}},"scripts":[{"id":"jc","game":"jc","steps":[{"op":"wait","ms":1}]}]}"""
        val second = """{"version":"b","games":{"blhx":{"package":"blhx.pkg"}},"scripts":[{"id":"blhx","game":"blhx","steps":[]}]}"""
        val merged = Json.parseToJsonElement(ScriptCatalog.mergeContents(listOf(first, second), {})) as JsonObject
        assertEquals(setOf("jc", "blhx"), (merged["games"] as JsonObject).keys)
        val step = (((merged["scripts"] as JsonArray).first() as JsonObject)["steps"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("wait"), step["op"])
    }

    @Test fun invalidGameCannotEscapeCatalogDirectory() {
        for (game in listOf("../other", "/tmp/other", "..", "a\\b", "")) {
            assertTrue(runCatching { ScriptCatalog.writeForGame(filesDir, game, catalog()) }.isFailure)
        }
        assertFalse(filesDir.resolve("scripts/catalog").exists())
    }
}
