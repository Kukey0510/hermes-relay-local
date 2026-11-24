package com.hermesandroid.relay.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hermesandroid.relay.script.runtime.ScriptRuntime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Service-owned; constructing this object never mounts a window. All mutations run on main. */
class GestureRecorder(private val service: HermesAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = service.getSharedPreferences("gesture_recorder", Context.MODE_PRIVATE)
    private val model = GestureRecording().apply {
        // A durable marker detects process death. Never restore partially recorded actions.
        if (prefs.getBoolean("unfinished", false)) discard(lost = true)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val operations = Mutex()
    private var capture: CaptureView? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var controls: ComposeView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var owner: RecorderWindowOwner? = null
    private var forwarding: Job? = null
    private var forwardingActive = false
    private var startedAt = 0L
    private var inputEpoch = 0L
    private var version by mutableStateOf(0)
    private var expanded by mutableStateOf(true)
    private var exitPanel by mutableStateOf(false)
    private var resumeAfterPrompt = false
    private val idleTimeout = Runnable {
        Log.w(TAG, "recorder inactive for 30 minutes; discarding session")
        close(lost = true)
    }

    val hasWindows: Boolean get() = capture != null || controls != null

    /** Called only by an explicit app button. Idle controls let Start work over the game. */
    fun showControls(): JsonObject {
        gate()?.let { return error(it) }
        return try {
            mountControls()
            activity()
            status()
        } catch (failure: Exception) {
            Log.w(TAG, "recorder controls unavailable", failure)
            close(lost = true)
            error("overlay_unavailable")
        }
    }

    suspend fun command(operation: String): JsonObject = withContext(Dispatchers.Main.immediate) {
        // A second stop waits for the first callback/save, then returns the same cached JSON.
        operations.withLock {
            gate()?.let { return@withLock error(it) }
            activity()
            when (operation) {
                "start" -> start()
                "pause" -> transition(model.pause())
                "resume" -> transition(model.resume())
                "stop" -> {
                    // A bridge stop is the caller's explicit save request. A local Stop still
                    // requires the user to choose Save; polling must not override that choice.
                    if (model.pendingSave) error("confirmation_required") else stop(save = true)
                }
                else -> error("unknown_recorder_command")
            }
        }
    }

    private fun gate(): String? = when {
        ScriptRuntime.isRunning() -> "script_running"
        !service.isMasterEnabled() -> "bridge_disabled"
        service.hasRecorderConfirmation -> "confirmation_pending"
        service.hasRecorderScreenReads -> "screen_busy"
        else -> null
    }

    private fun start(): JsonObject {
        if (forwardingActive) return error("gesture_in_progress")
        val failure = model.start(UUID.randomUUID().toString())
        if (failure != null) return error(failure)
        // Synchronous persistence: a killed process must not look like a clean idle session.
        if (!prefs.edit().putBoolean("unfinished", true).commit()) {
            close(lost = true)
            return error("recorder_lost")
        }
        startedAt = SystemClock.uptimeMillis()
        inputEpoch++
        return try {
            mountCapture()
            // Both windows have the same type: attach controls last to ensure their z order.
            controls?.let { view ->
                val layout = checkNotNull(controlParams)
                wm.removeViewImmediate(view)
                wm.addView(view, layout)
            } ?: mountControls()
            // Stays expanded while recording: the whole point of the floating window is that
            // Stop/Pause are reachable without re-expanding the ball first.
            expanded = true
            refresh()
            status()
        } catch (failure: Exception) {
            Log.w(TAG, "recorder capture unavailable", failure)
            close(lost = true)
            error("overlay_unavailable")
        }
    }

    private fun transition(failure: String?): JsonObject {
        if (model.state == GestureRecording.State.LOST) return error("recorder_lost")
        if (failure != null) return error(failure)
        inputEpoch++ // A finger held across a pause/resume boundary is never recorded.
        refresh()
        return status()
    }

    private suspend fun stop(save: Boolean): JsonObject {
        model.stop()?.let { return error(it) }
        inputEpoch++
        expanded = true
        refresh()
        removeCapture()
        forwarding?.join() // Return only after the last captured action reached its callback.
        if (model.state == GestureRecording.State.LOST) return error("recorder_lost")
        refresh()
        return if (save) saveResult() else status()
    }

    private fun saveResult(): JsonObject {
        val result = model.save() ?: return error("not_recording")
        prefs.edit().putBoolean("unfinished", false).apply()
        refresh()
        // Result remains in memory for an idempotent POST /recorder/stop; never touch catalog.
        return result
    }

    /** The runtime reserves its run first, then awaits this before any action or frame capture. */
    suspend fun prepareForScript() {
        inputEpoch++
        if (model.state == GestureRecording.State.RECORDING || model.state == GestureRecording.State.PAUSED || model.pendingSave) {
            model.discard(lost = true)
        }
        removeWindows()
        forwarding?.join()
    }

    suspend fun suspendForConfirmation() {
        resumeAfterPrompt = model.state == GestureRecording.State.RECORDING
        if (resumeAfterPrompt) model.pause()
        inputEpoch++
        // CI-UNVERIFIED: the existing SYSTEM_ALERT_WINDOW confirmation must receive real
        // touches, with neither its Allow/Deny buttons nor recorder controls captured/injected.
        windowTouchable(capture, captureParams, false, 0f)
        windowTouchable(controls, controlParams, false, 0f)
        forwarding?.join() // Finish any previously injected game stroke before showing Allow.
        refresh()
    }

    fun restoreAfterConfirmation() {
        if (ScriptRuntime.isRunning() || !service.isMasterEnabled()) return
        if (resumeAfterPrompt && model.state == GestureRecording.State.PAUSED) model.resume()
        resumeAfterPrompt = false
        inputEpoch++
        if (!forwardingActive) windowTouchable(capture, captureParams, true, 1f)
        windowTouchable(controls, controlParams, true, 0.9f)
        refresh()
    }

    private fun windowTouchable(view: View?, layout: WindowManager.LayoutParams?, touchable: Boolean, alpha: Float) {
        if (view == null || layout == null) return
        layout.flags = if (touchable) layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        layout.alpha = alpha
        runCatching { wm.updateViewLayout(view, layout) }.onFailure { close(lost = true) }
    }

    /** Lifecycle callbacks are on main. Android removes the process-owned windows on process death. */
    fun close(lost: Boolean) {
        inputEpoch++
        if (lost || model.result() == null) model.discard(lost)
        if (!lost) prefs.edit().putBoolean("unfinished", false).apply()
        // Let an already injected stroke reach its callback. Script startup joins it before
        // taking control; cancelling only the coroutine would leave Android's stroke running.
        removeWindows()
        refresh()
    }

    fun destroy() {
        close(lost = true)
        scope.cancel()
    }

    private fun status(): JsonObject = buildJsonObject {
        put("ok", true); put("session_id", model.sessionId)
        put("state", model.state.name.lowercase()); put("step_count", model.stepCount)
    }

    private fun error(code: String): JsonObject = buildJsonObject {
        put("ok", false); put("error", code)
    }

    private fun refresh() { version++ }
    private fun activity() {
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, 30L * 60 * 1_000)
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }

    private fun mountCapture() {
        if (capture != null) return
        val view = CaptureView()
        val layout = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // rawX/rawY and injected gestures share physical screen coordinates, including insets.
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        // CI-UNVERIFIED: verify full-display raw pixel coordinates and insets on the target phone.
        wm.addView(view, layout)
        capture = view
        captureParams = layout
    }

    private fun mountControls() {
        if (controls != null) return
        val lifecycleOwner = RecorderWindowOwner()
        owner = lifecycleOwner
        lifecycleOwner.start()
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                @Suppress("UNUSED_VARIABLE") val observedVersion = version
                MaterialTheme {
                    Column(Modifier.width(144.dp).background(Color(0xDD222222), RoundedCornerShape(24.dp)).padding(4.dp)) {
                        val label = when (model.state) {
                            GestureRecording.State.RECORDING -> "● ${model.stepCount}"
                            GestureRecording.State.PAUSED -> "❙❙ ${model.stepCount}"
                            GestureRecording.State.STOPPED -> "REC ${model.stepCount}"
                            else -> "REC"
                        }
                        val color = when (model.state) {
                            GestureRecording.State.RECORDING -> Color.Red
                            GestureRecording.State.PAUSED -> Color.Yellow
                            else -> Color.LightGray
                        }
                        Box(Modifier.width(136.dp).heightIn(min = 48.dp).pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val initialLayout = controlParams
                                val origin = down.position + androidx.compose.ui.geometry.Offset(
                                    initialLayout?.x?.toFloat() ?: 0f, initialLayout?.y?.toFloat() ?: 0f)
                                var previous = origin
                                var dragging = false
                                var cancelled = false
                                var upTime = down.uptimeMillis
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || event.changes.size != 1) { cancelled = true; break }
                                    val layout = controlParams
                                    val position = change.position + androidx.compose.ui.geometry.Offset(
                                        layout?.x?.toFloat() ?: 0f, layout?.y?.toFloat() ?: 0f)
                                    val movement = position - previous
                                    val delta = position - origin
                                    previous = position
                                    if (hypot(delta.x.toDouble(), delta.y.toDouble()) > 12.dp.toPx()) dragging = true
                                    if (dragging) moveControls(movement.x, movement.y, dragging = true)
                                    change.consume()
                                    upTime = change.uptimeMillis
                                    val pressed = change.pressed
                                } while (pressed)
                                if (dragging) snapControls()
                                else if (!cancelled) {
                                    activity()
                                    if (upTime - down.uptimeMillis > 300) { exitPanel = true; expanded = true }
                                    else expanded = !expanded
                                }
                            }
                        }, contentAlignment = Alignment.Center) { Text(label, color = color) }
                        if (expanded) {
                            if (model.pendingSave) {
                                Text("${model.stepCount} 条步骤", color = Color.White)
                                TextButton(onClick = { local { saveResult() } }) { Text("保存") }
                                TextButton(onClick = { model.discard(); prefs.edit().putBoolean("unfinished", false).apply(); refresh() }) { Text("丢弃") }
                            } else {
                                TextButton(enabled = model.state !in setOf(GestureRecording.State.RECORDING, GestureRecording.State.PAUSED),
                                    onClick = { local { start() } }) { Text("开始") }
                                TextButton(enabled = model.state == GestureRecording.State.RECORDING,
                                    onClick = { local { transition(model.pause()) } }) { Text("暂停") }
                                TextButton(enabled = model.state == GestureRecording.State.PAUSED,
                                    onClick = { local { transition(model.resume()) } }) { Text("继续") }
                                TextButton(enabled = model.state in setOf(GestureRecording.State.RECORDING, GestureRecording.State.PAUSED),
                                    onClick = { local { stop(save = false) } }) { Text("停止") }
                            }
                            TextButton(onClick = { exitPanel = !exitPanel }) { Text("×") }
                            if (exitPanel) TextButton(onClick = { close(lost = false) }) { Text("退出（丢弃未保存）") }
                        }
                    }
                }
            }
        }
        val bounds = screenBounds()
        val layout = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = prefs.getInt("x", bounds.width()).coerceIn(0, bounds.width())
            y = prefs.getInt("y", bounds.height() / 3).coerceIn(0, bounds.height())
            alpha = 0.9f
        }
        // CI-UNVERIFIED: verify controls stay above capture and their taps/drags never reach the game.
        wm.addView(view, layout)
        controls = view
        controlParams = layout
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampControls() }
    }

    private fun local(block: suspend () -> JsonObject) {
        scope.launch {
            operations.withLock {
                gate()?.let { Toast.makeText(service, it, Toast.LENGTH_SHORT).show(); return@withLock }
                activity()
                val result = block()
                if (result["ok"].toString() == "false") Toast.makeText(service, result["error"].toString(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun moveControls(dx: Float, dy: Float, dragging: Boolean) {
        activity()
        val layout = controlParams ?: return
        layout.x += dx.roundToInt(); layout.y += dy.roundToInt()
        layout.alpha = if (dragging) 0.4f else 0.9f
        clampControls()
    }

    private fun clampControls() {
        val view = controls ?: return
        val layout = controlParams ?: return
        val bounds = screenBounds()
        val (topInset, bottomInset) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            insets.top to insets.bottom
        } else {
            @Suppress("DEPRECATION")
            (view.rootWindowInsets?.systemWindowInsetTop ?: 0) to (view.rootWindowInsets?.systemWindowInsetBottom ?: 0)
        }
        layout.x = layout.x.coerceIn(0, (bounds.width() - view.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(topInset, (bounds.height() - view.height - bottomInset).coerceAtLeast(topInset))
        runCatching { wm.updateViewLayout(view, layout) }.onFailure {
            Log.w(TAG, "recorder control layout failed", it)
            close(lost = true)
        }
    }

    private fun snapControls() {
        val view = controls ?: return
        val layout = controlParams ?: return
        val right = (screenBounds().width() - view.width).coerceAtLeast(0)
        layout.x = if (layout.x < right / 2) 0 else right
        layout.alpha = 0.9f
        clampControls()
        // CI-UNVERIFIED: verify edge snapping, safe vertical bounds, and position after reopening/rotation.
        prefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
    }

    private fun removeCapture() {
        val view = capture
        capture = null; captureParams = null
        if (view != null) remove(view)
    }

    private fun screenBounds(): android.graphics.Rect {
        if (android.os.Build.VERSION.SDK_INT >= 30) return wm.currentWindowMetrics.bounds
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        return android.graphics.Rect(0, 0, size.x, size.y)
    }

    private fun remove(view: View) {
        runCatching { wm.removeViewImmediate(view) }.onFailure { first ->
            Log.w(TAG, "recorder window removal failed", first)
            // Also remove its input interception if WindowManager rejects removal transiently.
            runCatching {
                val layout = view.layoutParams as WindowManager.LayoutParams
                layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                layout.alpha = 0f
                wm.updateViewLayout(view, layout)
                wm.removeView(view)
            }.onFailure { Log.e(TAG, "recorder window cleanup failed", it) }
        }
    }

    private fun removeWindows() {
        handler.removeCallbacks(idleTimeout)
        removeCapture()
        controls?.let { remove(it) }
        controls = null; controlParams = null
        owner?.stop(); owner = null
    }

    private inner class CaptureView : View(service) {
        private var downX = 0
        private var downY = 0
        private var downMs = 0L
        private var epoch = 0L
        private var record = false
        private var touching = false

        init { setWillNotDraw(false); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas) }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            activity()
            if (ScriptRuntime.isRunning() || !service.isMasterEnabled()) { close(lost = true); return true }
            if (forwardingActive) return true
            if (event.pointerCount != 1) { touching = false; return true }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = event.rawX.roundToInt(); downY = event.rawY.roundToInt()
                    downMs = event.eventTime; epoch = inputEpoch
                    record = model.state == GestureRecording.State.RECORDING
                }
                MotionEvent.ACTION_CANCEL -> touching = false
                MotionEvent.ACTION_UP -> if (touching) {
                    touching = false
                    val up = event.eventTime
                    val gesture = classifyRecordedGesture(downX, downY, event.rawX.roundToInt(), event.rawY.roundToInt(),
                        up - downMs, downMs - startedAt)
                    if (record && epoch == inputEpoch) model.append(gesture, downMs, up)
                    refresh()
                    forwardingActive = true
                    forwarding = scope.launch {
                        val layout = captureParams ?: run { forwardingActive = false; return@launch }
                        try {
                            // CI-UNVERIFIED: Android 12+ must pass injected input to the game without a
                            // self-capture loop; alpha=0 AND NOT_TOUCHABLE must be applied before dispatch.
                            layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            layout.alpha = 0f
                            wm.updateViewLayout(this@CaptureView, layout)
                            delay(16) // Give WindowManager a frame to install the new input region.
                            Log.i(TAG, "session=${model.sessionId} finger_up_ms=$up dispatch_ms=${SystemClock.uptimeMillis()} kind=${gesture.kind}")
                            val result = withTimeout(6_000) {
                                when (gesture.kind) {
                                    "tap" -> service.actionExecutor.tap(gesture.x, gesture.y)
                                    "long_press" -> service.actionExecutor.longPress(gesture.x, gesture.y, null, gesture.durationMs)
                                    else -> service.actionExecutor.swipe(gesture.x, gesture.y, gesture.toX, gesture.toY, gesture.durationMs)
                                }
                            }
                            // CI-UNVERIFIED: correlate these callbacks with actual game responses for ten taps;
                            // dispatch completion alone does not prove the <=200ms game-response target.
                            Log.i(TAG, "session=${model.sessionId} callback_ms=${SystemClock.uptimeMillis()} finger_up_ms=$up ok=${result.ok}")
                            if (!result.ok) close(lost = true)
                        } catch (cancel: CancellationException) {
                            close(lost = true)
                            throw cancel
                        } catch (failure: Exception) {
                            Log.w(TAG, "recorder forwarding failed", failure)
                            close(lost = true)
                        } finally {
                            forwardingActive = false
                            if (capture === this@CaptureView && !service.hasRecorderConfirmation) {
                                layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                layout.alpha = 1f
                                runCatching { wm.updateViewLayout(this@CaptureView, layout) }
                                    .onFailure { close(lost = true) }
                            }
                        }
                    }
                }
            }
            return true
        }
    }

    companion object { private const val TAG = "GestureRecorder" }
}

private class RecorderWindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    fun start() { controller.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}
