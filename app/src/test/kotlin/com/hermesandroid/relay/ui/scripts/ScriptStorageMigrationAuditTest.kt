package com.hermesandroid.relay.ui.scripts

import com.hermesandroid.relay.script.runtime.ScriptRuntime
import com.hermesandroid.relay.script.runtime.ScriptStep
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptStorageMigrationAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture by lazy {
        Json.parseToJsonElement(javaClass.getResource("/scripts/storage-migration.json")!!.readText()) as JsonObject
    }
    private val newer get() = fixture.getValue("new") as JsonObject
    private fun put(path: String, content: String): File = temporary.root.resolve(path).apply {
        parentFile!!.mkdirs()
        writeText(content)
    }
    private fun read() = ScriptCatalog.readContent(temporary.root, { error("unexpected assets") }, {})

    private fun assertVisible(expected: Map<String, Int>, expectedIds: Set<String>) {
        val content = read()
        val entries = ScriptCatalog.decode(content)
        assertEquals(expected, entries.groupingBy(ScriptEntry::game).eachCount())
        assertEquals(expectedIds, entries.map { it.id }.toSet())
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertEquals(listOf("bb", "blhx", "jc"), scriptGamesOf(entries))
        // Both runtime entry points consume the same merged raw JSON and game declarations.
        val scripts = ScriptRuntime.decodeCatalog(content, {})
        assertEquals(expectedIds, scripts.map { it.id }.toSet())
        val packages = mapOf("blhx" to "com.bilibili.azurlane", "jc" to "com.megagame.crosscore", "bb" to "com.palmpi.matrix")
        for (script in scripts) {
            val game = entries.single { it.id == script.id }.game
            assertEquals(listOf(packages.getValue(game)), script.targetPackages)
            assertEquals(listOf(ScriptStep.Wait(1)), script.steps)
        }
    }

    @Test fun stateAOnlyLegacyShowsTwoBlhxOneJcThreeBbScripts() {
        val legacy = fixture.getValue("legacy").toString()
        put("scripts/catalog.json", legacy)
        assertFalse(temporary.root.resolve("scripts/catalog").exists())
        assertEquals(legacy, read())
        assertVisible(mapOf("blhx" to 2, "jc" to 1, "bb" to 3), setOf(
            "legacy-blhx-1", "legacy-blhx-2", "legacy-jc-1", "legacy-bb-1", "legacy-bb-2", "legacy-bb-3"))
    }

    @Test fun stateBOnlyJcMigratedShowsTwoBlhxTwoJcThreeBbScripts() {
        val legacy = put("scripts/catalog.json", fixture.getValue("legacy").toString())
        val original = legacy.readBytes()
        val jc = put("scripts/catalog/jc.json", newer.getValue("jc").toString())
        assertVisible(mapOf("blhx" to 2, "jc" to 2, "bb" to 3), setOf(
            "legacy-blhx-1", "legacy-blhx-2", "new-jc-1", "new-jc-2", "legacy-bb-1", "legacy-bb-2", "legacy-bb-3"))
        assertEquals(setOf("blhx", "jc", "bb"), ScriptCatalog.cachedGames(temporary.root))
        assertEquals(jc.readText(), ScriptCatalog.cachedForGame(temporary.root, "jc"))
        assertArrayEquals(original, legacy.readBytes())
    }

    @Test fun stateCAllMigratedShowsOneBlhxTwoJcTwoBbScriptsWithoutLegacyEntries() {
        put("scripts/catalog.json", fixture.getValue("legacy").toString())
        for ((game, content) in newer) put("scripts/catalog/$game.json", content.toString())
        assertVisible(mapOf("blhx" to 1, "jc" to 2, "bb" to 2), setOf(
            "new-blhx-1", "new-jc-1", "new-jc-2", "new-bb-1", "new-bb-2"))
    }
}
