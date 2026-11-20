package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptStopTest {
    private class Access : ScriptAccess {
        var taps = 0
        var interactive = true
        var stopOnTap = false
        override fun foregroundPackageFromWindows() = foregroundPackage()
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override fun foregroundPackage(): String {
            check(interactive) { "foreground guard must not run while screen is off" }
            return "game.pkg"
        }
        override fun screenOn() = interactive
        override suspend fun tap(x: Int, y: Int): Boolean {
            taps++
            if (stopOnTap) ScriptRunControl.requestStop("test-stop")
            return false
        }
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = true
        override suspend fun read(field: String): String? = null
    }

    private fun script(step: ScriptStep) = RuntimeScript(
        "stop-test", listOf("game.pkg"), listOf(step),
        onFail = listOf(ScriptStep.Tap(9, 9)),
    )

    @Test fun cancellationSkipsRetriesAndOnFailInNestedSteps() = runTest {
        val access = Access().apply { stopOnTap = true }
        val result = ScriptRunner().run(script(ScriptStep.Loop(5,
            steps = listOf(ScriptStep.Tap(1, 1, retries = 3)), retries = 2)), access)
        assertEquals("cancelled", result.errorCode)
        assertEquals(1, access.taps)
        assertTrue(result.logs.single().message.contains("test-stop"))
        assertEquals("warn", result.logs.single().toEvent("stop-test", result.runId).level)
    }

    @Test fun screenOffPrecedesForegroundGuardAndSkipsOnFail() = runTest {
        val access = Access().apply { interactive = false }
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 1, retries = 3)), access)
        assertEquals("screen_off", result.errorCode)
        assertEquals(0, access.taps)
        assertEquals("warn", result.logs.single().toEvent("stop-test", result.runId).level)
    }

    @Test fun nextRunResetsPreviousStopRequest() = runTest {
        ScriptRunControl.requestStop("previous-run")
        val result = ScriptRunner().run(script(ScriptStep.Probe("home")), Access())
        assertTrue(result.success)
    }
}
