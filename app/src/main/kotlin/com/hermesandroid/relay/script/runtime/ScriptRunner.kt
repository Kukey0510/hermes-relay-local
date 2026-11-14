package com.hermesandroid.relay.script.runtime

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

interface ScriptAccess {
    /** Must read rootInActiveWindow.packageName now, falling back only to the last window event. */
    fun foregroundPackage(): String?
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun tapText(text: String): Boolean
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean = false
    fun screenSize(): Pair<Int, Int> = Int.MAX_VALUE to Int.MAX_VALUE
    fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    suspend fun probe(expect: String, timeoutMs: Long): Boolean
    suspend fun read(field: String): String?
    suspend fun waitMs(ms: Long) = delay(ms)
}

data class ScriptRunLog(val stepIndex: Int, val stepId: String?, val op: String,
    val success: Boolean, val attempts: Int, val message: String)
data class ScriptRunResult(val runId: String = UUID.randomUUID().toString(), val success: Boolean,
    val errorCode: String? = null, val logs: List<ScriptRunLog> = emptyList())

class ScriptRunner {
    companion object { private val running = AtomicBoolean(false) }

    suspend fun run(
        script: RuntimeScript,
        access: ScriptAccess,
        runId: String = UUID.randomUUID().toString(),
        onRunStart: suspend () -> Unit = {},
        onStep: suspend (ScriptRunLog, Long) -> Unit = { _, _ -> },
    ): ScriptRunResult {
        if (!running.compareAndSet(false, true)) return ScriptRunResult(runId = runId, success = false, errorCode = "run_in_progress")
        val logs = mutableListOf<ScriptRunLog>()
        try {
            onRunStart()
            script.steps.forEachIndexed { index, step ->
                val startedAt = System.nanoTime()
                var attempt = 0
                var outcome: StepOutcome
                do {
                    ScriptGuard.rejection(access.foregroundPackage(), script.targetPackages)?.let { code ->
                        val entry = log(index, step, false, attempt, code)
                        logs += entry
                        onStep(entry, elapsedMs(startedAt))
                        return ScriptRunResult(runId = runId, success = false, errorCode = code, logs = logs)
                    }
                    attempt++
                    outcome = execute(step, access, script.targetPackages)
                } while (!outcome.ok && attempt <= step.retries)
                val entry = log(index, step, outcome.ok, attempt, outcome.message)
                logs += entry
                onStep(entry, elapsedMs(startedAt))
                if (!outcome.ok) return ScriptRunResult(runId = runId, success = false,
                    errorCode = outcome.errorCode ?: "step_failed", logs = logs)
            }
            return ScriptRunResult(runId = runId, success = true, logs = logs)
        } finally { running.set(false) }
    }

    private suspend fun execute(step: ScriptStep, access: ScriptAccess,
        targetPackages: Collection<String>): StepOutcome {
        val timeout = step.timeoutMs?.coerceAtLeast(1)
        val result = if (timeout == null) perform(step, access, targetPackages)
            else withTimeoutOrNull(timeout) { perform(step, access, targetPackages) } ?: StepOutcome(false, "timeout")
        return result
    }

    private suspend fun perform(step: ScriptStep, access: ScriptAccess,
        targetPackages: Collection<String>): StepOutcome = when (step) {
        is ScriptStep.Tap -> StepOutcome(access.tap(step.x, step.y), "tap")
        is ScriptStep.TapText -> StepOutcome(access.tapText(step.text), "tapText")
        is ScriptStep.Wait -> {
            val clamped = step.ms.coerceIn(0, 10_000)
            access.waitMs(clamped); StepOutcome(true, "wait_ms=$clamped")
        }
        is ScriptStep.Probe -> StepOutcome(access.probe(step.expect, step.timeoutMs ?: 3_000), "probe=${step.expect}")
        is ScriptStep.Read -> access.read(step.field)?.let { StepOutcome(true, "${step.field}=$it") }
            ?: StepOutcome(false, "read_failed:${step.field}")
        is ScriptStep.Swipe -> {
            val (width, height) = access.screenSize()
            val maxX = (width - 1).coerceAtLeast(0)
            val maxY = (height - 1).coerceAtLeast(0)
            val duration = step.durationMs.coerceIn(50, 2_000)
            StepOutcome(access.swipe(step.fromX.coerceIn(0, maxX), step.fromY.coerceIn(0, maxY),
                step.toX.coerceIn(0, maxX), step.toY.coerceIn(0, maxY), duration), "swipe")
        }
        is ScriptStep.TapIfText -> {
            val found = access.tapText(step.text)
            if (found) StepOutcome(true, "tapIfText")
            else StepOutcome(step.optional, "absent:${step.text}")
        }
        is ScriptStep.IfPage -> {
            if ((step.thenSteps + step.elseSteps).any { it is ScriptStep.IfPage }) {
                StepOutcome(false, "nested_if_page", "invalid_script")
            } else {
                val branch = if (access.probe(step.expect, step.timeoutMs ?: 3_000)) step.thenSteps else step.elseSteps
                performChildren(branch, access, targetPackages, "if_page")
            }
        }
        is ScriptStep.Loop -> {
            if (step.times !in 1..100 || step.maxTotalMs < 0 || step.steps.any { it is ScriptStep.Loop }) {
                StepOutcome(false, "invalid_loop", "invalid_script")
            } else {
                val started = access.monotonicMs()
                var outcome = StepOutcome(true, "loop_stop:times")
                for (iteration in 0 until step.times) {
                    if (access.monotonicMs() - started >= step.maxTotalMs) {
                        outcome = StepOutcome(true, "loop_stop:max_total_ms"); break
                    }
                    val child = performChildren(step.steps, access, targetPackages, "loop")
                    if (!child.ok) return child
                    if (access.monotonicMs() - started >= step.maxTotalMs) {
                        outcome = StepOutcome(true, "loop_stop:max_total_ms"); break
                    }
                    if (step.until != null && access.probe(step.until, 1_000)) {
                        outcome = StepOutcome(true, "loop_stop:until"); return outcome
                    }
                }
                outcome
            }
        }
    }

    private suspend fun performChildren(steps: List<ScriptStep>, access: ScriptAccess,
        targetPackages: Collection<String>, owner: String): StepOutcome {
        for (step in steps) {
            var attempt = 0
            var outcome: StepOutcome
            do {
                ScriptGuard.rejection(access.foregroundPackage(), targetPackages)?.let {
                    return StepOutcome(false, it, it)
                }
                attempt++
                outcome = execute(step, access, targetPackages)
            } while (!outcome.ok && attempt <= step.retries)
            if (!outcome.ok) return StepOutcome(false, "$owner:${outcome.message}", outcome.errorCode)
        }
        return StepOutcome(true, owner)
    }

    private fun log(index: Int, step: ScriptStep, success: Boolean, attempts: Int, message: String) =
        ScriptRunLog(index, step.id, when (step) {
            is ScriptStep.Tap -> "tap"; is ScriptStep.TapText -> "tapText"; is ScriptStep.Wait -> "wait"
            is ScriptStep.Probe -> "probe"; is ScriptStep.Read -> "read"; is ScriptStep.Swipe -> "swipe"
            is ScriptStep.TapIfText -> "tapIfText"; is ScriptStep.IfPage -> "if_page"; is ScriptStep.Loop -> "loop"
        }, success, attempts, message)
    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000
    private data class StepOutcome(val ok: Boolean, val message: String, val errorCode: String? = null)
}
