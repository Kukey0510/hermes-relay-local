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
                    outcome = execute(step, access)
                } while (!outcome.ok && attempt <= step.retries)
                val entry = log(index, step, outcome.ok, attempt, outcome.message)
                logs += entry
                onStep(entry, elapsedMs(startedAt))
                if (!outcome.ok) return ScriptRunResult(runId = runId, success = false, errorCode = "step_failed", logs = logs)
            }
            return ScriptRunResult(runId = runId, success = true, logs = logs)
        } finally { running.set(false) }
    }

    private suspend fun execute(step: ScriptStep, access: ScriptAccess): StepOutcome {
        val timeout = step.timeoutMs?.coerceAtLeast(1)
        val result = if (timeout == null) perform(step, access)
            else withTimeoutOrNull(timeout) { perform(step, access) } ?: StepOutcome(false, "timeout")
        return result
    }

    private suspend fun perform(step: ScriptStep, access: ScriptAccess): StepOutcome = when (step) {
        is ScriptStep.Tap -> StepOutcome(access.tap(step.x, step.y), "tap")
        is ScriptStep.TapText -> StepOutcome(access.tapText(step.text), "tapText")
        is ScriptStep.Wait -> {
            val clamped = step.ms.coerceIn(0, 10_000)
            access.waitMs(clamped); StepOutcome(true, "wait_ms=$clamped")
        }
        is ScriptStep.Probe -> StepOutcome(access.probe(step.expect, step.timeoutMs ?: 3_000), "probe=${step.expect}")
        is ScriptStep.Read -> access.read(step.field)?.let { StepOutcome(true, "${step.field}=$it") }
            ?: StepOutcome(false, "read_failed:${step.field}")
    }

    private fun log(index: Int, step: ScriptStep, success: Boolean, attempts: Int, message: String) =
        ScriptRunLog(index, step.id, step::class.simpleName.orEmpty(), success, attempts, message)
    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000
    private data class StepOutcome(val ok: Boolean, val message: String)
}
