package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPickTest {
    private val cards = listOf(
        listOf(listOf(1580, 220, 300, 44)),
        listOf(listOf(1580, 463, 300, 44)),
        listOf(listOf(1580, 706, 300, 44)),
    )

    @Test fun defaultsDecode() {
        assertEquals(ScriptStep.Pick(cards), Json.decodeFromString<ScriptStep>(
            """{"op":"pick","cards":[[[1580,220,300,44]],[[1580,463,300,44]],[[1580,706,300,44]]]}"""))
    }

    @Test fun allFieldsAndMultipleRegionsRoundTrip() {
        val source = """{"op":"pick","cards":[[[10,20,30,40],[10,70,30,40]],[[50,20,30,40]]],
            "mode":"max","tap_x":1665,"tap_dy":55,"fallback_index":1,"min_conf":0.75,"dry":true,
            "id":"choose","timeout_ms":5000,"retries":2}"""
        val expected = ScriptStep.Pick(
            listOf(listOf(listOf(10, 20, 30, 40), listOf(10, 70, 30, 40)), listOf(listOf(50, 20, 30, 40))),
            "max", 1665, 55, 1, 0.75, true, "choose", 5_000, 2,
        )
        val step = Json.decodeFromString<ScriptStep>(source)
        assertEquals(expected, step)
        assertEquals(expected, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))
        val defaults = ScriptStep.Pick(cards)
        assertEquals(defaults, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, defaults)))
    }

    @Test fun emptyCardsAreInvalidScript() {
        assertInvalid("""{"op":"pick","cards":[]}""")
    }

    @Test fun missingCardsAreInvalidScript() {
        assertInvalid("""{"op":"pick"}""")
    }

    @Test fun malformedRegionsAndOptionsAreRejected() {
        for (value in listOf("null", "[[]]", "[[1,2,3,4]]", "[[[0,0,1]]]", "[[[-1,0,1,1]]]",
            "[[[0,0,0,1]]]", "[[[0,0,1,-1]]]", "[[[0,0,1.5,1]]]", """[[[0,0,"1",1]]]""",
            "[[[2147483647,0,1,1]]]")) {
            assertInvalid("""{"op":"pick","cards":$value}""")
        }
        for (field in listOf(""""mode":"median"""", """"mode":null""", """"min_conf":-0.1""",
            """"min_conf":1.1""", """"min_conf":"NaN"""", """"min_conf":1e400""",
            """"min_conf":null""", """"dry":"true"""", """"tap_x":1.5""",
            """"tap_dy":null""", """"fallback_index":"0"""")) {
            assertInvalid("""{"op":"pick","cards":[[[0,0,1,1]]],$field}""")
        }
    }

    @Test fun runnerForwardsAllOptionsAndRecordsHit() = runTest {
        val step = ScriptStep.Pick(cards, "max", 1665, 55, 2, 0.75, true)
        val access = Access().apply { hit = "hit:1:1665,540" }
        val result = ScriptRunner().run(RuntimeScript("pick", listOf("game.pkg"), listOf(step)), access)
        assertTrue(result.success)
        assertEquals(listOf(step), access.calls)
        assertEquals("pick", result.logs.single().op)
        assertEquals("hit:1:1665,540", result.logs.single().message)
    }

    @Test fun runnerReportsFailureOnNullHit() = runTest {
        val result = ScriptRunner().run(RuntimeScript("pick", listOf("game.pkg"),
            listOf(ScriptStep.Pick(cards))), Access())
        assertFalse(result.success)
        assertEquals("step_failed", result.errorCode)
        assertEquals("pick_failed", result.logs.single().message)
    }

    @Test fun runnerGuardsPickBeforeAccess() = runTest {
        val access = Access().apply { foreground = "com.android.systemui" }
        val result = ScriptRunner().run(RuntimeScript("pick", listOf("game.pkg"),
            listOf(ScriptStep.Pick(cards))), access)
        assertEquals("guard_system_ui", result.errorCode)
        assertTrue(access.calls.isEmpty())
    }

    private fun assertInvalid(source: String) {
        val error = runCatching { Json.decodeFromString<ScriptStep>(source) }.exceptionOrNull()
        assertTrue("Expected invalid_script for $source, got $error", error?.message?.contains("invalid_script") == true)
    }

    private class Access : ScriptAccess {
        var foreground = "game.pkg"
        var hit: String? = null
        val calls = mutableListOf<ScriptStep.Pick>()
        override fun foregroundPackage() = foreground
        override fun foregroundPackageFromWindows() = foreground
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun tapText(text: String) = true
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
        override suspend fun pick(cards: List<List<List<Int>>>, mode: String, tapX: Int?, tapDy: Int,
            fallbackIndex: Int, minConf: Double, dry: Boolean): String? {
            calls += ScriptStep.Pick(cards, mode, tapX, tapDy, fallbackIndex, minConf, dry)
            return hit
        }
    }
}
