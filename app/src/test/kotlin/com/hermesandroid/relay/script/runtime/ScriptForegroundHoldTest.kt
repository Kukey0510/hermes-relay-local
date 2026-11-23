package com.hermesandroid.relay.script.runtime

import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.bridge.BridgeRunTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptForegroundHoldTest {
    private val service = mockk<HermesAccessibilityService>()

    @Before fun setUp() {
        mockkObject(HermesAccessibilityService.Companion, BridgeRunTracker)
        every { HermesAccessibilityService.instance } returns service
        every { service.isMasterEnabled() } returns true
        every { BridgeRunTracker.keepForeground(any(), any()) } returns
            BridgeRunTracker.KeepForegroundResult(0L, 0L, false)
    }

    @After fun tearDown() {
        unmockkObject(HermesAccessibilityService.Companion, BridgeRunTracker)
    }

    private fun script(field: String = "300000", step: String = """{"op":"tap","x":1,"y":2}""") =
        ScriptRuntime.decodeCatalog("""[{
            "id":"hold-test","targetPackages":["game.pkg"],"steps":[$step]
            ${if (field.isEmpty()) "" else ",\"hold_foreground_ms\":$field"}
        }]""").single()

    private class Access(val tapSucceeds: Boolean = true, val stopOnTap: Boolean = false) : ScriptAccess {
        override fun foregroundPackageFromWindows() = foregroundPackage()
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override fun foregroundPackage() = "game.pkg"
        override suspend fun tap(x: Int, y: Int): Boolean {
            if (stopOnTap) ScriptRunControl.requestStop("test-stop")
            return tapSucceeds
        }
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
    }

    @Test fun successfulScriptHoldsOnceAfterCompletion() = runTest {
        val script = script()
        val held = mutableListOf<Long>()
        val result = ScriptRuntime.withForegroundHold(script, onHeld = { held += it }) {
            ScriptRunner().run(script, Access()).also {
                verify(exactly = 0) { BridgeRunTracker.keepForeground(any(), any()) }
            }
        }
        assertEquals(300_000L, script.holdForegroundMs)
        assertTrue(result.success)
        assertEquals(listOf(300L), held)
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun failedScriptHoldsOnce() = runTest {
        val script = script()
        val result = ScriptRuntime.withForegroundHold(script) {
            ScriptRunner().run(script, Access(tapSucceeds = false))
        }
        assertFalse(result.success)
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun stoppedScriptHoldsOnce() = runTest {
        val script = script()
        val result = ScriptRuntime.withForegroundHold(script) {
            ScriptRunner().run(script, Access(stopOnTap = true))
        }
        assertEquals("cancelled", result.errorCode)
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun coroutineCancellationHoldsOnce() = runTest {
        val script = script(step = """{"op":"wait","ms":60000}""")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            ScriptRuntime.withForegroundHold(script) { ScriptRunner().run(script, Access()) }
        }
        verify(exactly = 0) { BridgeRunTracker.keepForeground(any(), any()) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun exceptionHoldsOnceAndPropagatesOriginalFailure() = runTest {
        val failure = IllegalStateException("test failure")
        try {
            ScriptRuntime.withForegroundHold(script()) { throw failure }
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun absentZeroSubsecondAndNegativeValuesDoNotHold() = runTest {
        for (field in listOf("", "0", "999", "-1000")) {
            val script = script(field)
            if (field.isEmpty()) assertEquals(0L, script.holdForegroundMs)
            ScriptRuntime.withForegroundHold(script, onHeld = { error("unexpected hold log") }) {
                assertTrue(ScriptRunner().run(script, Access()).success)
            }
        }
        verify(exactly = 0) { BridgeRunTracker.keepForeground(any(), any()) }
    }

    @Test fun disabledMasterDoesNotHold() = runTest {
        every { service.isMasterEnabled() } returns false
        val script = script()
        ScriptRuntime.withForegroundHold(script, onHeld = { error("unexpected hold log") }) {
            ScriptRunner().run(script, Access())
        }
        verify(exactly = 0) { BridgeRunTracker.keepForeground(any(), any()) }
    }

    @Test fun unavailableServiceDoesNotHold() = runTest {
        every { HermesAccessibilityService.instance } returns null
        ScriptRuntime.withForegroundHold(script()) { Unit }
        verify(exactly = 0) { BridgeRunTracker.keepForeground(any(), any()) }
    }

    @Test fun millisecondsAreRoundedDown() = runTest {
        ScriptRuntime.withForegroundHold(script("300999")) { Unit }
        verify(exactly = 1) { BridgeRunTracker.keepForeground(300L, any()) }
    }

    @Test fun enormousValueUsesTrackerLimitWithoutOverflow() = runTest {
        ScriptRuntime.withForegroundHold(script(Long.MAX_VALUE.toString())) { Unit }
        verify(exactly = 1) {
            BridgeRunTracker.keepForeground(BridgeRunTracker.MAX_KEEP_FOREGROUND_SECONDS, any())
        }
    }
}
