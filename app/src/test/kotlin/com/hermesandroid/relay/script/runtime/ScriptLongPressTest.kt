package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScriptLongPressTest {
    @Test fun defaultsAndAllFieldsRoundTrip() {
        assertEquals(ScriptStep.LongPress(2300, 900),
            Json.decodeFromString<ScriptStep>("""{"op":"long_press","x":2300,"y":900}"""))
        val step = ScriptStep.LongPress(2000, 800, 5_000, "hold", 6_000, 2)
        val encoded = Json.encodeToString(ScriptStepSerializer, step)
        assertTrue(encoded.contains("\"duration_ms\":5000"))
        assertEquals(step, Json.decodeFromString<ScriptStep>(encoded))
        assertEquals(ScriptStep.LongPress(1, 2, 300), Json.decodeFromString<ScriptStep>(
            """{"op":"long_press","x":1,"y":2,"duration_ms":300}"""))
    }

    @Test fun invalidDurationsAreRejectedRatherThanSilentlyClamped() {
        for (duration in listOf("299", "5001", "0", "-1", "null", "1.5", "\"invalid\"")) {
            assertThrows(Exception::class.java) {
                Json.decodeFromString<ScriptStep>("""{"op":"long_press","x":1,"y":2,"duration_ms":$duration}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.encodeToString(ScriptStepSerializer, ScriptStep.LongPress(1, 2, 5_001))
        }
    }

    @Test fun runnerForwardsAbsoluteCoordinatesAndAcceptedDurations() = runTest {
        val access = Access()
        val result = ScriptRunner().run(RuntimeScript("long", listOf("game.pkg"), listOf(
            ScriptStep.LongPress(2300, 900, 300), ScriptStep.LongPress(2300, 900, 5_000))), access)
        assertTrue(result.success)
        assertEquals(listOf(ScriptStep.LongPress(2300, 900, 300), ScriptStep.LongPress(2300, 900, 5_000)), access.calls)
        assertEquals(listOf("long_press", "long_press"), result.logs.map { it.op })
    }

    @Test fun invalidProgrammaticStepsReturnInvalidScriptWithoutInjecting() = runTest {
        for (duration in listOf(299L, 5_001L)) {
            val access = Access()
            val result = ScriptRunner().run(RuntimeScript("invalid", listOf("game.pkg"), listOf(
                ScriptStep.LongPress(1, 2, duration))), access)
            assertEquals("invalid_script", result.errorCode)
            assertTrue(access.calls.isEmpty())
        }
    }

    @Test fun longPressRetainsForegroundAndFailureGuards() = runTest {
        val access = Access().apply { foreground = "com.android.systemui" }
        val script = RuntimeScript("guard", listOf("game.pkg"), listOf(ScriptStep.LongPress(1, 2)))
        assertEquals("guard_system_ui", ScriptRunner().run(script, access).errorCode)
        assertTrue(access.calls.isEmpty())
        access.foreground = "game.pkg"
        access.ok = false
        assertEquals("step_failed", ScriptRunner().run(script, access).errorCode)
    }

    private class Access : ScriptAccess {
        var foreground = "game.pkg"
        var ok = true
        val calls = mutableListOf<ScriptStep.LongPress>()
        override fun foregroundPackage() = foreground
        override fun foregroundPackageFromWindows() = foreground
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun tapText(text: String) = true
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
        override suspend fun longPress(x: Int, y: Int, nodeId: String?, duration: Long): Boolean {
            assertNull(nodeId)
            calls += ScriptStep.LongPress(x, y, duration)
            return ok
        }
    }
}
