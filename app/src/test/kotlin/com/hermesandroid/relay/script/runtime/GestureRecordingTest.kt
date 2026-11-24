package com.hermesandroid.relay.script.runtime

import com.hermesandroid.relay.accessibility.GestureRecording
import com.hermesandroid.relay.accessibility.classifyRecordedGesture
import com.hermesandroid.relay.accessibility.recordedWaitMs
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityGrant
import com.hermesandroid.relay.bridge.BridgeCommandRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class GestureRecordingTest {
    @Test fun classificationUsesPixelAndDurationBoundaries() {
        assertEquals("tap", gesture(23, 299).kind)
        assertEquals("long_press", gesture(23, 300).kind)
        assertEquals("swipe", gesture(24, 299).kind)
        assertEquals("swipe", classifyRecordedGesture(0, 0, 17, 17, 100, 0).kind)
        assertEquals(100L, gesture(0, 20).durationMs)
        assertEquals(300L, gesture(0, 300).durationMs)
        assertEquals(5_000L, gesture(0, 6_000).durationMs)
        assertEquals(100L, gesture(24, 1).durationMs)
        assertEquals(2_000L, gesture(24, 9_000).durationMs)
        val absolute = classifyRecordedGesture(2000, 900, 2300, 1000, 450, 123)
        assertEquals(ScriptStep.Swipe(2000, 900, 2300, 1000, 450), absolute.step())
        assertEquals(123L, absolute.tStartMs)
    }

    @Test fun waitsOmitShortGapsAndCapAtTenSeconds() {
        assertNull(recordedWaitMs(null, 1_000))
        assertNull(recordedWaitMs(1_000, 900))
        assertNull(recordedWaitMs(1_000, 1_079))
        assertEquals(80L, recordedWaitMs(1_000, 1_080))
        assertEquals(10_000L, recordedWaitMs(1_000, 11_000))
        assertEquals(10_000L, recordedWaitMs(1_000, 99_000))
    }

    @Test fun pauseIgnoresActionsAndResumeExcludesPausedWait() {
        val recording = GestureRecording()
        assertNull(recording.start("session"))
        recording.append(gesture(0, 50), 0, 50)
        assertNull(recording.pause())
        recording.append(gesture(0, 50), 100, 150)
        assertNull(recording.resume())
        recording.append(gesture(0, 50), 30_000, 30_050)
        assertNull(recording.stop())
        val result = recording.save()!!
        assertEquals(JsonPrimitive(2), result["step_count"])
        assertEquals(2, (result["gestures"] as JsonArray).size)
        assertEquals(listOf(ScriptStep.Tap(100, 200), ScriptStep.Tap(100, 200)), decodeSteps(result))
    }

    @Test fun exportContainsOnlyReplayOpsAndPreservesActionsWithShortGaps() {
        val recording = GestureRecording()
        recording.start("s")
        recording.append(gesture(0, 50), 0, 50)
        recording.append(gesture(0, 500), 100, 600) // 50ms: no wait, still keep long press.
        recording.append(gesture(24, 400), 20_600, 21_000)
        recording.stop()
        assertNull(recording.result()) // Nothing exportable until Save.
        val result = recording.save()!!
        assertEquals(listOf(ScriptStep.Tap(100, 200), ScriptStep.LongPress(100, 200, 500),
            ScriptStep.Wait(10_000), ScriptStep.Swipe(100, 200, 124, 200, 400)), decodeSteps(result))
        assertEquals(JsonPrimitive(4), result["step_count"])
        assertEquals(JsonPrimitive(3), result["gesture_count"])
        assertFalse(result.toString().contains("expect"))
        assertFalse(result.toString().contains("page"))
    }

    @Test fun transitionsStopAndSaveAreIdempotentAndNewSessionsReset() {
        val recording = GestureRecording()
        assertEquals("not_recording", recording.stop())
        assertEquals("not_recording", recording.pause())
        assertEquals("not_paused", recording.resume())
        recording.start("first")
        assertEquals("already_recording", recording.start("second"))
        assertEquals("not_paused", recording.resume())
        recording.append(gesture(0, 50), 0, 50)
        assertNull(recording.stop())
        assertNull(recording.stop())
        assertEquals("confirmation_required", recording.start("second"))
        val result = recording.save()
        assertNull(recording.stop())
        assertSame(result, recording.save())
        assertNull(recording.start("second"))
        assertEquals(0, recording.stepCount)
        assertNull(recording.result())
    }

    @Test fun discardAndLossNeverReturnPartialResults() {
        val recording = GestureRecording()
        recording.start("s")
        recording.append(gesture(0, 50), 0, 50)
        recording.discard(lost = true)
        assertEquals("recorder_lost", recording.stop())
        assertNull(recording.result())
        assertNull(recording.save())
        assertEquals(0, recording.stepCount)
        recording.start("new")
        recording.stop()
        recording.discard()
        assertNull(recording.save())
        assertEquals("not_recording", recording.stop())
    }

    @Test fun recorderRoutesUseTheSameTimedAuthorityAsTapAndRejectGet() {
        for (path in listOf("/recorder/start", "/recorder/pause", "/recorder/resume", "/recorder/stop")) {
            val authority = BridgeCommandRegistry.resolve(path, "POST")!!
            assertEquals(BridgeCapability.SCREEN_CONTROL, authority.capability)
            assertEquals(BridgeCapabilityGrant.TIMED, authority.grant)
            assertEquals(BridgeCommandRegistry.resolve("/tap", "POST"), authority)
            assertNull(BridgeCommandRegistry.resolve(path, "GET"))
        }
    }

    private fun gesture(distance: Int, held: Long) = classifyRecordedGesture(100, 200, 100 + distance, 200, held, 0)
    private fun decodeSteps(result: JsonObject) = (result["steps"] as JsonArray).map {
        Json.decodeFromJsonElement(ScriptStepSerializer, it)
    }
}
