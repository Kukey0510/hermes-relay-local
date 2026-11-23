package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScriptPauseTest {
    private class Access : ScriptAccess {
        val taps = mutableListOf<Int>()
        var foregroundReads = 0
        var pageReads = 0
        override fun foregroundPackage(): String { foregroundReads++; return "game.pkg" }
        override fun foregroundPackageFromWindows() = foregroundPackage()
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int): Boolean { taps += x; return true }
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = true
        override suspend fun read(field: String): String? = null
        override suspend fun currentPage(): String { pageReads++; return "unknown_page" }
    }

    private fun script(vararg steps: ScriptStep) = RuntimeScript(
        "pause-test", listOf("game.pkg"), steps.toList(), autoEvidence = false,
    )

    @Test fun pauseResumesNextStepWithoutForegroundOrDeviceCallsWhileWaiting() = runTest {
        val access = Access()
        val events = mutableListOf<ScriptEvent>()
        val task = async {
            ScriptRunner().run(script(ScriptStep.Pause(timeoutMs = 1), ScriptStep.Tap(2, 2)),
                access, runId = "pause", onPause = { events += it })
        }
        runCurrent()
        assertTrue(ScriptRunControl.isPaused("pause"))
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(task.isCompleted)
        assertEquals(0, access.foregroundReads)
        assertEquals(0, access.pageReads)
        assertTrue(access.taps.isEmpty())
        assertEquals("warn", events.single().level)
        assertEquals("need_help", events.single().message)
        ScriptRunControl.resume("other")
        runCurrent()
        assertFalse(task.isCompleted)
        ScriptRunControl.resume("pause")
        assertTrue(task.await().success)
        assertEquals(listOf(2), access.taps)
        assertFalse(ScriptRunControl.isPaused("pause"))
    }

    @Test fun stopInterruptsPauseAndSkipsNextStepAndFailureHandler() = runTest {
        val access = Access()
        val task = async { ScriptRunner().run(script(ScriptStep.Pause(), ScriptStep.Tap(2, 2))
            .copy(onFail = listOf(ScriptStep.Tap(3, 3))), access, runId = "stop") }
        runCurrent()
        ScriptRunControl.requestStop("test")
        assertEquals("cancelled", task.await().errorCode)
        assertTrue(access.taps.isEmpty())
        assertFalse(ScriptRunControl.isPaused("stop"))
    }

    @Test fun pauseTimeoutFailsAtItsOwnDeadline() = runTest {
        val access = Access()
        val task = async { ScriptRunner().run(script(ScriptStep.Pause(pauseTimeoutMs = 50),
            ScriptStep.Tap(2, 2)), access, runId = "timeout") }
        runCurrent()
        advanceTimeBy(49)
        assertFalse(task.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("step_timeout", task.await().errorCode)
        assertTrue(access.taps.isEmpty())
        assertEquals(0, access.foregroundReads)
    }

    @Test fun serverStepsOverrideThenAndRunBeforeContinuation() = runTest {
        for (steps in listOf(null, emptyList(), listOf(ScriptStep.Tap(3, 3)))) {
            val access = Access()
            val events = mutableListOf<ScriptEvent>()
            val task = async { ScriptRunner().run(script(ScriptStep.Tap(1, 1, id = "previous"),
                ScriptStep.AskServer(reason = "help", thenSteps = listOf(ScriptStep.Tap(2, 2))),
                ScriptStep.Tap(4, 4)), access, runId = "ask", onPause = { events += it }) }
            runCurrent()
            assertEquals("unknown_page", events.single().page)
            assertEquals("previous", events.single().lastStep)
            assertEquals("help", events.single().message)
            val reads = access.foregroundReads
            advanceTimeBy(100)
            assertEquals(reads, access.foregroundReads)
            assertEquals(1, access.pageReads)
            ScriptRunControl.resume("ask", steps)
            assertTrue(task.await().success)
            val expected = when { steps == null -> listOf(1, 2, 4); steps.isEmpty() -> listOf(1, 4); else -> listOf(1, 3, 4) }
            assertEquals(expected, access.taps)
        }
    }

    @Test fun askWithoutThenOrServerStepsSimplyContinuesInsideExistingBranch() = runTest {
        val access = Access()
        val task = async { ScriptRunner().run(script(ScriptStep.IfVar("flag", equals = "",
            thenSteps = listOf(ScriptStep.AskServer(), ScriptStep.Tap(5, 5)))), access, runId = "nested") }
        runCurrent()
        assertEquals(0, access.foregroundReads)
        ScriptRunControl.resume("nested")
        assertTrue(task.await().success)
        assertEquals(listOf(5), access.taps)
    }

    @Test fun wireDefaultsClampingAndRoundTrip() {
        val pause = Json.decodeFromString<ScriptStep>("""{"op":"pause"}""") as ScriptStep.Pause
        assertEquals(600_000L, pause.pauseTimeoutMs)
        assertNull(pause.timeoutMs)
        val ask = Json.decodeFromString<ScriptStep>("""{"op":"ask_server","timeout_ms":9999999,"then":[{"op":"back"}]}""") as ScriptStep.AskServer
        assertEquals(3_600_000L, ask.pauseTimeoutMs)
        assertEquals(ask, Json.decodeFromString<ScriptStep>(Json.encodeToString<ScriptStep>(ask)))
        assertEquals(pause, Json.decodeFromString<ScriptStep>(Json.encodeToString<ScriptStep>(pause)))
        val oldEvent = ScriptEvent("script", "run", "info", 0, "run_end", "done", "now")
        assertFalse(Json { encodeDefaults = true }.encodeToString(oldEvent).contains("lastStep"))
        assertFalse(Json { encodeDefaults = true }.encodeToString(oldEvent).contains("page"))
    }
}
