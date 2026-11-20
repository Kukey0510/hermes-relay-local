package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GameDeclarationTest {
    private val declaration = """{"package":"game.pkg","orientation":"portrait","base":[1080,2460],"boot":"entry","home":"lobby"}"""

    private fun catalog(script: String, games: String = """{"test":$declaration}""") =
        """{"games":$games,"scripts":[$script]}"""

    @Test fun declarationsKeepOptionalFieldsAndBaseWithoutChangingSteps() {
        val content = catalog("""{"id":"s","game":"test","steps":[{"op":"tap","x":12,"y":34}]}""",
            """{"test":$declaration,"partial":{"boot":"entry"},"empty":{}}""")
        val games = ScriptRuntime.gamesDeclaration(content) { fail(it) }
        assertEquals(GameDeclaration("game.pkg", "portrait", listOf(1080, 2460), "entry", "lobby"), games["test"])
        assertEquals(GameDeclaration(boot = "entry"), games["partial"])
        assertEquals(GameDeclaration(), games["empty"])
        val script = ScriptRuntime.decodeCatalog(content) { fail(it) }.single()
        assertEquals(listOf("game.pkg"), script.targetPackages)
        assertEquals(ScriptStep.Tap(12, 34), script.steps.single())
    }

    @Test fun malformedDeclarationsAreSkippedWithoutHidingValidGamesOrScripts() {
        val warnings = mutableListOf<String>()
        val content = catalog("""{"id":"s","targetPackages":[],"steps":[]}""", """{
            "test":$declaration,"not_object":[],"bad_package":{"package":42},
            "bad_base":{"base":[1080]},"bad_orientation":{"orientation":"sideways"},
            "bad_home":{"home":{}},"bad_dimension":{"base":[0,1080]}
        }""")
        assertEquals(setOf("test"), ScriptRuntime.gamesDeclaration(content, warnings::add).keys)
        assertEquals(6, warnings.size)
        assertEquals("s", ScriptRuntime.decodeCatalog(content) {}.single().id)
        for (games in listOf("[]", "42", "null")) {
            warnings.clear()
            val invalid = catalog("""{"id":"s","targetPackages":[],"steps":[]}""", games)
            assertTrue(ScriptRuntime.gamesDeclaration(invalid, warnings::add).isEmpty())
            assertEquals(1, warnings.size)
            assertEquals("s", ScriptRuntime.decodeCatalog(invalid) {}.single().id)
        }
    }

    @Test fun bareColdStartReceivesDeclaredDefaultsThroughRunner() = runTest {
        val script = ScriptRuntime.decodeCatalog(catalog(
            """{"id":"s","game":"test","steps":[{"op":"coldStart"}]}""")) { fail(it) }.single()
        assertEquals("test", script.game)
        assertEquals("game.pkg", script.packageName)
        assertEquals("portrait", script.orientation)
        val access = Access()
        assertTrue(ScriptRunner().run(script, access).success)
        assertEquals(listOf("game.pkg", "entry", "lobby", "portrait"), access.received)
    }

    @Test fun explicitScriptAndStepFieldsOverrideDeclarationIncludingLegacyDefaults() = runTest {
        val script = ScriptRuntime.decodeCatalog(catalog("""{
            "id":"s","game":"test","package":"script.pkg","targetPackages":["guard.pkg"],
            "orientation":"landscape","boot":"script_boot","home":"script_home",
            "steps":[{"op":"coldStart"},{"op":"coldStart","package":"step.pkg",
                "orientation":"any","boot":"boot","home":"home"}]
        }""")) { fail(it) }.single()
        assertEquals(listOf("guard.pkg"), script.targetPackages)
        val first = script.steps.first() as ScriptStep.ColdStart
        assertEquals(listOf("script.pkg", "script_boot", "script_home", "landscape"),
            listOf(first.packageName, first.bootPage, first.homePage, first.orientation))
        val access = Access()
        assertTrue(ScriptRunner().run(script, access).success)
        assertEquals(listOf("step.pkg", "boot", "home", "any"), access.received)
    }

    @Test fun explicitTargetPackageAndNestedColdStartsKeepPrecedence() {
        val script = ScriptRuntime.decodeCatalog(catalog("""{
            "id":"s","game":"test","targetPackages":["explicit.pkg"],
            "steps":[{"op":"loop","times":1,"steps":[{"op":"if_var","name":"x",
                "then":[{"op":"coldStart"}],"else":[{"op":"coldStart","boot":"boot"}]}]}],
            "on_fail":[{"op":"coldStart"}]
        }""")) { fail(it) }.single()
        val branch = (script.steps.single() as ScriptStep.Loop).steps.single() as ScriptStep.IfVar
        for (step in listOf(branch.thenSteps.single(), branch.elseSteps.single(), script.onFail.single())) {
            val cold = step as ScriptStep.ColdStart
            assertEquals("explicit.pkg", cold.packageName)
            assertEquals("portrait", cold.orientation)
            assertEquals("lobby", cold.homePage)
        }
        assertEquals("entry", (branch.thenSteps.single() as ScriptStep.ColdStart).bootPage)
        assertEquals("boot", (branch.elseSteps.single() as ScriptStep.ColdStart).bootPage)
    }

    @Test fun missingGamesOrUnknownReferenceLogsOnceAndKeepsExplicitBehavior() {
        for (games in listOf("", """, "games":{}""", """, "games":{"other":$declaration}""")) {
            val warnings = mutableListOf<String>()
            val script = ScriptRuntime.decodeCatalog("""{"scripts":[{
                "id":"s","game":"missing","targetPackages":["explicit.pkg"],
                "steps":[{"op":"coldStart","package":"step.pkg","home":"explicit_home"}]
            }]$games}""", warnings::add).single()
            assertEquals(1, warnings.size)
            assertEquals(ScriptStep.ColdStart(packageName = "step.pkg", homePage = "explicit_home"), script.steps.single())
        }
        assertEquals(ScriptStep.ColdStart(), ScriptRuntime.decodeCatalog(
            """[{"id":"legacy","targetPackages":[],"steps":[{"op":"coldStart"}]}]""") { fail(it) }.single().steps.single())
    }

    private class Access : ScriptAccess {
        var received: List<String?> = emptyList()
        override fun foregroundPackage(): String? = error("coldStart must be guard exempt")
        override fun foregroundPackageFromWindows(): String? = null
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int) = false
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
        override suspend fun coldStartTo(pkg: String?, bootPage: String, homePage: String,
            tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long, intervalMs: Long,
            popupPage: String, popupX: Int, popupY: Int, maxPopups: Int,
            acceptPages: List<String>, orientation: String): String {
            received = listOf(pkg, bootPage, homePage, orientation)
            return "ok:already_on_$homePage"
        }
    }
}
