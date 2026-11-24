package com.hermesandroid.relay.accessibility

import com.hermesandroid.relay.script.runtime.ScriptStep
import com.hermesandroid.relay.script.runtime.ScriptStepSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.hypot

/** Absolute display pixels. No page recognition, coordinate scaling, or catalog writes. */
data class RecordedGesture(
    val kind: String, val tStartMs: Long, val x: Int, val y: Int,
    val toX: Int, val toY: Int, val durationMs: Long,
) {
    fun step(): ScriptStep = when (kind) {
        "tap" -> ScriptStep.Tap(x, y)
        "long_press" -> ScriptStep.LongPress(x, y, durationMs)
        "swipe" -> ScriptStep.Swipe(x, y, toX, toY, durationMs)
        else -> error("unsupported recorded gesture")
    }

    fun json(): JsonObject = buildJsonObject {
        put("kind", kind); put("t_start_ms", tStartMs); put("x", x); put("y", y)
        put("to_x", toX); put("to_y", toY); put("duration_ms", durationMs)
    }
}

fun classifyRecordedGesture(x: Int, y: Int, toX: Int, toY: Int,
    heldMs: Long, tStartMs: Long): RecordedGesture {
    val moved = hypot(toX.toDouble() - x, toY.toDouble() - y)
    val kind = if (moved >= 24.0) "swipe" else if (heldMs >= 300) "long_press" else "tap"
    val duration = when (kind) {
        "swipe" -> heldMs.coerceIn(100, 2_000)
        "long_press" -> heldMs.coerceIn(300, 5_000)
        else -> 100L // Existing ActionExecutor and script tap default.
    }
    return RecordedGesture(kind, tStartMs, x, y, toX, toY, duration)
}

/** Short gaps omit the wait, never the user's actual action. */
fun recordedWaitMs(previousUpMs: Long?, downMs: Long): Long? = previousUpMs?.let {
    (downMs - it).takeIf { gap -> gap >= 80 }?.coerceAtMost(10_000)
}

/** Main-thread owned by GestureRecorder; pure JVM state and export contract for tests. */
class GestureRecording {
    enum class State { IDLE, RECORDING, PAUSED, STOPPED, LOST }
    var state = State.IDLE
        private set
    var sessionId: String? = null
        private set
    var pendingSave = false
        private set
    private val gestures = mutableListOf<RecordedGesture>()
    private val steps = mutableListOf<ScriptStep>()
    private var previousUpMs: Long? = null
    private var saved: JsonObject? = null
    val stepCount: Int get() = steps.size

    fun start(id: String): String? {
        if (state == State.RECORDING || state == State.PAUSED) return "already_recording"
        if (pendingSave) return "confirmation_required"
        sessionId = id
        gestures.clear(); steps.clear(); previousUpMs = null; saved = null
        state = State.RECORDING
        return null
    }

    fun pause(): String? {
        if (state != State.RECORDING) return "not_recording"
        state = State.PAUSED
        previousUpMs = null
        return null
    }

    fun resume(): String? {
        if (state != State.PAUSED) return "not_paused"
        state = State.RECORDING
        previousUpMs = null // Paused time and control gestures never become waits.
        return null
    }

    fun append(gesture: RecordedGesture, downMs: Long, upMs: Long) {
        if (state != State.RECORDING) return
        recordedWaitMs(previousUpMs, downMs)?.let { steps += ScriptStep.Wait(it) }
        gestures += gesture
        steps += gesture.step()
        previousUpMs = upMs
    }

    fun stop(): String? {
        if (state == State.STOPPED && (pendingSave || saved != null)) return null
        if (state != State.RECORDING && state != State.PAUSED) return if (state == State.LOST) "recorder_lost" else "not_recording"
        state = State.STOPPED
        pendingSave = true
        return null
    }

    /** Only this explicit save creates the independent replay JSON. */
    fun save(): JsonObject? {
        saved?.let { return it }
        if (!pendingSave) return null
        return buildJsonObject {
            put("ok", true); put("session_id", sessionId)
            put("step_count", stepCount); put("gesture_count", gestures.size)
            put("gestures", JsonArray(gestures.map { it.json() }))
            put("steps", JsonArray(steps.map { Json.encodeToJsonElement(ScriptStepSerializer, it) }))
        }.also { saved = it; pendingSave = false }
    }

    fun result(): JsonObject? = saved

    fun discard(lost: Boolean = false) {
        gestures.clear(); steps.clear(); saved = null; previousUpMs = null; pendingSave = false
        state = if (lost) State.LOST else State.STOPPED
    }
}
