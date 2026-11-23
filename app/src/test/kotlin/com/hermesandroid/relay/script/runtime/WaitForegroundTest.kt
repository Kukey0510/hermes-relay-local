package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WaitForegroundTest {
    @Test fun fieldsDefaultAndRoundTrip() {
        val defaults = Json.decodeFromString<ScriptStep>("""{"op":"wait_foreground"}""")
        assertEquals(ScriptStep.WaitForeground(), defaults)
        assertEquals(60_000L, defaults.timeoutMs)
        for (orientation in listOf(null, "landscape", "portrait", "any")) {
            val step: ScriptStep = ScriptStep.WaitForeground("game.pkg", orientation, 1234, "ready", 2)
            assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(step)))
        }
        assertTrue(runCatching { Json.decodeFromString<ScriptStep>(
            """{"op":"wait_foreground","orientation":"sideways"}""") }.isFailure)
    }

    @Test fun liveWindowMatchReturnsImmediatelyEvenWhenEventGuardSeesSystemUi() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(ScriptStep.WaitForeground("game.pkg")), access)
        assertTrue(result.success)
        assertEquals("wait_foreground", result.logs.single().op)
        assertEquals("ok:foreground_after=0", result.logs.single().message)
        assertEquals(0, access.guardReads)
        assertEquals(1, access.windowReads)
        assertEquals(0, access.frameReads)
        assertEquals(0L, access.clock)
    }

    @Test fun wrongWindowsPackageTimesOutWithLastPackageEvenWhenGuardClaimsTarget() = runTest {
        val access = Access(windowPackage = "com.android.systemui", eventPackage = "game.pkg")
        val result = ScriptRunner().run(script(ScriptStep.WaitForeground("game.pkg", timeoutMs = 250)), access)
        assertFalse(result.success)
        assertEquals("fail:foreground_timeout last=com.android.systemui", result.logs.single().message)
        assertEquals(250L, access.clock)
        assertEquals(0, access.guardReads)
    }

    @Test fun pollsUntilWindowPackageChangesAndUsesFirstTargetPackage() = runTest {
        val access = Access(windowPackage = "launcher", packageAfterMs = 200)
        val result = ScriptRunner().run(script(ScriptStep.WaitForeground(timeoutMs = 500)), access)
        assertTrue(result.success)
        assertEquals("ok:foreground_after=200", result.logs.single().message)
        assertEquals(0, access.guardReads)
    }

    @Test fun missingPackageFailsWithoutReadingWindowsAndExplicitPackageWorksWithoutTargets() = runTest {
        val access = Access()
        val missing = ScriptRunner().run(script(ScriptStep.WaitForeground(), targets = emptyList()), access)
        assertFalse(missing.success)
        assertEquals("fail:no_package", missing.logs.single().message)
        assertEquals(0, access.windowReads)
        assertTrue(ScriptRunner().run(script(ScriptStep.WaitForeground("game.pkg"), targets = emptyList()), access).success)
    }

    @Test fun orientationUsesActualFrameAndWaitsForItToMatch() = runTest {
        for ((orientation, frame) in listOf("landscape" to (2460 to 1080), "portrait" to (1080 to 2460))) {
            val access = Access(size = frame)
            assertTrue(ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = orientation)), access).success)
            assertEquals(1, access.frameReads)
        }
        val access = Access(size = 1080 to 2460, landscapeAfterMs = 200)
        val result = ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = "landscape", timeoutMs = 300)), access)
        assertTrue(result.success)
        assertEquals("ok:foreground_after=200", result.logs.single().message)
    }

    @Test fun wrongMissingOrSquareFrameTimesOutWithMatchedPackage() = runTest {
        for (frame in listOf(1080 to 2460, null, 1080 to 1080)) {
            val access = Access(size = frame)
            val result = ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = "landscape", timeoutMs = 300)), access)
            assertFalse(result.success)
            assertEquals("fail:foreground_timeout last=game.pkg", result.logs.single().message)
            assertEquals(300L, access.clock)
        }
        val access = Access(size = 2460 to 1080)
        assertFalse(ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = "portrait", timeoutMs = 100)), access).success)
    }

    @Test fun unknownWindowsNeverFallsBackToEventOrFrameAndAnySkipsCapture() = runTest {
        val access = Access(windowPackage = null, eventPackage = "game.pkg")
        val result = ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = "landscape", timeoutMs = 100)), access)
        assertEquals("fail:foreground_timeout last=unknown", result.logs.single().message)
        assertEquals(0, access.frameReads)
        val any = Access(size = null)
        assertTrue(ScriptRunner().run(script(ScriptStep.WaitForeground(orientation = "any")), any).success)
        assertEquals(0, any.frameReads)
    }

    @Test fun allContainerPathsAndOnFailExemptWaitForegroundFromGuard() = runTest {
        val wait = ScriptStep.WaitForeground("game.pkg", timeoutMs = 100)
        for (step in listOf(
            ScriptStep.Loop(1, steps = listOf(wait)),
            ScriptStep.IfPage("home", thenSteps = listOf(wait)),
            ScriptStep.IfPage("other", elseSteps = listOf(wait)),
            ScriptStep.IfVar("v", equals = "", thenSteps = listOf(wait)),
            ScriptStep.IfVar("v", equals = "other", elseSteps = listOf(wait)),
        )) {
            val access = Access()
            assertTrue(ScriptRunner().run(script(step), access).success)
            assertEquals(0, access.guardReads)
            assertEquals(1, access.windowReads)
        }
        val access = Access()
        val result = ScriptRunner().run(script(ScriptStep.Launch("game.pkg")).copy(onFail = listOf(wait)), access)
        assertFalse(result.success)
        assertEquals("on_fail:wait_foreground", result.logs.last().op)
        assertTrue(result.logs.last().success)
        assertEquals(0, access.guardReads)
    }

    @Test fun cancellationAndScreenOffStopPolling() = runTest {
        val cancelled = Access(windowPackage = "launcher", stopAfterWait = true)
        assertEquals("cancelled", ScriptRunner().run(script(ScriptStep.WaitForeground()), cancelled).errorCode)
        assertEquals(100L, cancelled.clock)
        val screenOff = Access(interactive = false)
        assertEquals("screen_off", ScriptRunner().run(script(ScriptStep.WaitForeground()), screenOff).errorCode)
        assertEquals(0, screenOff.windowReads)
    }

    private fun script(step: ScriptStep, targets: List<String> = listOf("game.pkg", "other.pkg")) =
        RuntimeScript("foreground-test", targets, listOf(step))

    private class Access(
        val windowPackage: String? = "game.pkg", val eventPackage: String = "com.android.systemui",
        val size: Pair<Int, Int>? = 2460 to 1080, val packageAfterMs: Long = Long.MAX_VALUE,
        val landscapeAfterMs: Long = Long.MAX_VALUE, val stopAfterWait: Boolean = false,
        val interactive: Boolean = true,
    ) : ScriptAccess {
        var clock = 0L
        var guardReads = 0
        var windowReads = 0
        var frameReads = 0
        override fun foregroundPackage(): String { guardReads++; return eventPackage }
        override fun foregroundPackageFromWindows(): String? {
            windowReads++
            return if (clock >= packageAfterMs) "game.pkg" else windowPackage
        }
        override suspend fun frameSize(): Pair<Int, Int>? {
            frameReads++
            return if (clock >= landscapeAfterMs) 2460 to 1080 else size
        }
        override fun screenSize() = 1 to 2 // Display metrics must not decide frame orientation.
        override fun screenOn() = interactive
        override fun monotonicMs() = clock
        override suspend fun waitMs(ms: Long) {
            clock += ms
            if (stopAfterWait) ScriptRunControl.requestStop("foreground-test")
        }
        override suspend fun tap(x: Int, y: Int) = false
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = expect == "home"
        override suspend fun read(field: String): String? = null
    }
}
