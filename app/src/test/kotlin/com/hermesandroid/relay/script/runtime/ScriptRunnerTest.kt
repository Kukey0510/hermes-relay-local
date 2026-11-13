package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerTest {
    private val target = "game.pkg"

    @Test fun fourStepsSucceed() = runTest {
        val access = FakeAccess()
        val result = ScriptRunner().run(script(
            ScriptStep.Tap(1, 2), ScriptStep.TapText("go"),
            ScriptStep.Read("coins"), ScriptStep.Wait(1),
        ), access)
        assertTrue(result.success)
        assertEquals(4, result.logs.size)
    }

    @Test fun probeTimeoutStopsFollowingSteps() = runTest {
        val access = FakeAccess(probeAnswers = mutableListOf(false))
        val result = ScriptRunner().run(script(ScriptStep.Probe("home", 1), ScriptStep.Tap(1, 2)), access)
        assertFalse(result.success)
        assertEquals(0, access.tapCalls)
        assertEquals(1, result.logs.size)
    }

    @Test fun retriesCanSucceedOnThirdAttempt() = runTest {
        val access = FakeAccess(tapAnswers = mutableListOf(false, false, true))
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2, retries = 2), ScriptStep.Wait(0)), access)
        assertTrue(result.success)
        assertEquals(3, result.logs.first().attempts)
        assertEquals(2, result.logs.size)
    }

    @Test fun packageOutsideAllowlistRejectsBeforeAction() = runTest {
        val access = FakeAccess(packageName = "other.pkg")
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2)), access)
        assertEquals(ScriptGuard.PACKAGE_MISMATCH, result.errorCode)
        assertEquals(0, access.tapCalls)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun unknownOpThrows() {
        Json.decodeFromString<ScriptStep>("""{"op":"swipe"}""")
    }

    @Test fun waitIsClampedToTenSeconds() = runTest {
        val access = FakeAccess()
        assertTrue(ScriptRunner().run(script(ScriptStep.Wait(99_999)), access).success)
        assertEquals(listOf(10_000L), access.waits)
    }

    private fun script(vararg steps: ScriptStep) = RuntimeScript("id", listOf(target), steps.toList())

    private class FakeAccess(
        var packageName: String = "game.pkg",
        val tapAnswers: MutableList<Boolean> = mutableListOf(true),
        val probeAnswers: MutableList<Boolean> = mutableListOf(true),
    ) : ScriptAccess {
        var tapCalls = 0
        val waits = mutableListOf<Long>()
        override fun foregroundPackage() = packageName
        override suspend fun tap(x: Int, y: Int): Boolean { tapCalls++; return tapAnswers.removeFirst() }
        override suspend fun tapText(text: String) = true
        override suspend fun probe(expect: String, timeoutMs: Long) = probeAnswers.removeFirst()
        override suspend fun read(field: String) = "42"
        override suspend fun waitMs(ms: Long) { waits += ms }
    }
}
