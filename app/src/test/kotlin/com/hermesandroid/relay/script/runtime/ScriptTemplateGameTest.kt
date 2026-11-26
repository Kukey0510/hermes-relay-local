package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScriptTemplateGameTest {
    @Test fun explicitTemplateGameRoundTripsAndOverridesPackageInference() {
        val step = Json.decodeFromString<ScriptStep>("""{"op":"tap_template","template":"x.png","game":"bb"}""")
        assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))
        assertEquals(listOf("bb"), run(listOf("com.megagame.crosscore"), step))
    }

    @Test fun knownTargetPackagesSelectAllThreeGameDirectories() {
        val step = ScriptStep.TapTemplate("x.png")
        assertEquals(listOf("jc"), run(listOf("com.megagame.crosscore"), step))
        assertEquals(listOf("blhx"), run(listOf("com.bilibili.azurlane"), step))
        assertEquals(listOf("bb"), run(listOf("com.palmpi.matrix"), step))
    }

    @Test fun unknownAndAmbiguousPackagesKeepNullGame() {
        val step = ScriptStep.TapTemplate("x.png")
        assertEquals(listOf<String?>(null), run(listOf("unknown.pkg"), step))
        assertNull(templateGameForPackages(listOf("com.megagame.crosscore", "com.palmpi.matrix")))
    }

    @Test fun legacyAccessOverloadStillReceivesTemplateCalls() {
        var called = false
        val access = object : BaseAccess() {
            override suspend fun tapTemplate(name: String, area: List<Int>?, minScore: Double,
                offsetX: Int, offsetY: Int, tap: Boolean): String {
                called = true
                return "hit:1.0:1,2"
            }
        }
        val script = RuntimeScript("legacy", listOf("unknown.pkg"), listOf(ScriptStep.TapTemplate("x.png", game = "jc")))
        assertTrue(runBlocking { ScriptRunner().run(script, access) }.success)
        assertTrue(called)
    }

    @Test fun nonStringTemplateGameIsRejected() {
        assertTrue(runCatching {
            Json.decodeFromString<ScriptStep>("""{"op":"tap_template","template":"x.png","game":42}""")
        }.isFailure)
    }

    @Test fun mergedCatalogStillResolvesRuntimeGamesAndOriginalSteps() {
        val contents = listOf(
            """{"version":"a","games":{"jc":{"package":"jc.pkg"}},"scripts":[{"id":"jc","game":"jc","steps":[{"op":"wait","ms":1}]}]}""",
            """{"version":"b","games":{"bb":{"package":"bb.pkg"}},"scripts":[{"id":"bb","game":"bb","steps":[]}]}""",
        )
        val merged = com.hermesandroid.relay.ui.scripts.ScriptCatalog.mergeContents(contents, {})
        val scripts = ScriptRuntime.decodeCatalog(merged, {})
        assertEquals(listOf("jc", "bb"), scripts.map { it.id })
        assertEquals(listOf("jc.pkg"), scripts.first().targetPackages)
        assertEquals(listOf(ScriptStep.Wait(1)), scripts.first().steps)
        assertEquals(listOf("bb.pkg"), scripts.last().targetPackages)
    }

    private fun run(packages: List<String>, step: ScriptStep): List<String?> {
        val games = mutableListOf<String?>()
        val access = object : BaseAccess(packages.first()) {
            override suspend fun tapTemplate(name: String, area: List<Int>?, minScore: Double,
                offsetX: Int, offsetY: Int, tap: Boolean, game: String?): String {
                games += game
                return "hit:1.0:1,2"
            }
        }
        assertTrue(runBlocking { ScriptRunner().run(RuntimeScript("game", packages, listOf(step)), access) }.success)
        return games
    }

    private open class BaseAccess(private val pkg: String = "unknown.pkg") : ScriptAccess {
        override fun foregroundPackage() = pkg
        override fun foregroundPackageFromWindows() = pkg
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
    }
}
