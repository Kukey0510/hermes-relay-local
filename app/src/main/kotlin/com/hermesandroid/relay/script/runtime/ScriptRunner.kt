package com.hermesandroid.relay.script.runtime

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
    suspend fun launchApp(pkg: String): Boolean = false
    /** Back from a root page may leave the app; scripts must follow it with a probe. */
    suspend fun back(): Boolean = false
    suspend fun frameSample(): IntArray? = null
    suspend fun saveSnapshot(tag: String): Boolean = false
    suspend fun waitMs(ms: Long) = delay(ms)
}

data class ScriptRunLog(val stepIndex: Int, val stepId: String?, val op: String,
    val success: Boolean, val attempts: Int, val message: String)
data class ScriptRunResult(val runId: String = UUID.randomUUID().toString(), val success: Boolean,
    val errorCode: String? = null, val logs: List<ScriptRunLog> = emptyList())

internal fun meanAbsDiff(first: IntArray, second: IntArray): Int {
    if (first.size != second.size) return Int.MAX_VALUE
    if (first.isEmpty()) return 0
    return (first.indices.sumOf { abs(first[it].toLong() - second[it].toLong()) } / first.size)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

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
        val variables = script.vars.mapValuesTo(mutableMapOf()) { it.value.content }
        try {
            onRunStart()
            var failure: String? = null
            for ((index, step) in script.steps.withIndex()) {
                val startedAt = System.nanoTime()
                var attempt = 0
                var outcome = StepOutcome(false, "not_executed")
                do {
                    if (step !is ScriptStep.Launch) {
                        ScriptGuard.rejection(access.foregroundPackage(), script.targetPackages)?.let { code ->
                            val entry = log(index, step, false, attempt, code)
                            logs += entry
                            onStep(entry, elapsedMs(startedAt))
                            failure = code
                            break
                        }
                    }
                    attempt++
                    outcome = execute(step, access, script.targetPackages, variables)
                } while (!outcome.ok && attempt <= step.retries)
                if (failure != null) break
                val entry = log(index, step, outcome.ok, attempt, outcome.message)
                logs += entry
                onStep(entry, elapsedMs(startedAt))
                if (!outcome.ok) {
                    failure = outcome.errorCode ?: "step_failed"
                    break
                }
            }
            if (failure != null) runOnFail(script, access, variables, logs, onStep)
            return ScriptRunResult(runId = runId, success = failure == null, errorCode = failure, logs = logs)
        } finally { running.set(false) }
    }

    private suspend fun runOnFail(script: RuntimeScript, access: ScriptAccess, variables: MutableMap<String, String>,
        logs: MutableList<ScriptRunLog>, onStep: suspend (ScriptRunLog, Long) -> Unit) {
        val started = access.monotonicMs()
        for (step in script.onFail.take(10)) {
            val remaining = 60_000 - (access.monotonicMs() - started)
            if (remaining <= 0) break
            val stepStarted = System.nanoTime()
            val outcome = withTimeoutOrNull(remaining) {
                execute(step, access, script.targetPackages, variables)
            } ?: StepOutcome(false, "timeout")
            val entry = log(-1, step, outcome.ok, 1, outcome.message, "on_fail:")
            logs += entry
            onStep(entry, elapsedMs(stepStarted))
        }
    }

    private suspend fun execute(step: ScriptStep, access: ScriptAccess, targetPackages: Collection<String>,
        variables: MutableMap<String, String>): StepOutcome {
        val timeout = step.timeoutMs?.coerceAtLeast(1)
        return if (timeout == null) perform(step, access, targetPackages, variables)
        else withTimeoutOrNull(timeout) { perform(step, access, targetPackages, variables) } ?: StepOutcome(false, "timeout")
    }

    private suspend fun perform(step: ScriptStep, access: ScriptAccess, targetPackages: Collection<String>,
        variables: MutableMap<String, String>): StepOutcome = when (step) {
        is ScriptStep.Tap -> StepOutcome(access.tap(step.x, step.y), "tap")
        is ScriptStep.TapText -> StepOutcome(access.tapText(substitute(step.text, variables)), "tapText")
        is ScriptStep.Wait -> {
            val clamped = step.ms.coerceIn(0, 10_000)
            access.waitMs(clamped); StepOutcome(true, "wait_ms=$clamped")
        }
        is ScriptStep.Probe -> StepOutcome(access.probe(step.expect, step.timeoutMs ?: 3_000), "probe=${step.expect}")
        is ScriptStep.Read -> access.read(step.field)?.let { value ->
            firstNumber(value)?.let { variables[step.asName] = it }
            StepOutcome(true, "${step.field}=$value")
        } ?: StepOutcome(false, "read_failed:${step.field}")
        is ScriptStep.Swipe -> {
            val (width, height) = access.screenSize()
            val maxX = (width - 1).coerceAtLeast(0)
            val maxY = (height - 1).coerceAtLeast(0)
            val duration = step.durationMs.coerceIn(50, 2_000)
            StepOutcome(access.swipe(step.fromX.coerceIn(0, maxX), step.fromY.coerceIn(0, maxY),
                step.toX.coerceIn(0, maxX), step.toY.coerceIn(0, maxY), duration), "swipe")
        }
        is ScriptStep.TapIfText -> {
            val text = substitute(step.text, variables)
            val found = access.tapText(text)
            if (found) StepOutcome(true, "tapIfText") else StepOutcome(step.optional, "absent:$text")
        }
        is ScriptStep.IfPage -> {
            if ((step.thenSteps + step.elseSteps).any { it is ScriptStep.IfPage }) StepOutcome(false, "nested_if_page", "invalid_script")
            else {
                val expect = substitute(step.expect, variables)
                val branch = if (access.probe(expect, step.timeoutMs ?: 3_000)) step.thenSteps else step.elseSteps
                performChildren(branch, access, targetPackages, variables, "if_page")
            }
        }
        is ScriptStep.Loop -> {
            val fromVariable = step.timesFrom?.let { variables[it] }?.let(::firstNumber)?.toIntOrNull()
            val times = fromVariable ?: step.times
            val fallback = step.timesFrom != null && fromVariable == null
            if (times !in 1..100 || step.maxTotalMs < 0 || step.steps.any { it is ScriptStep.Loop }) {
                StepOutcome(false, "invalid_loop", "invalid_script")
            } else {
                val started = access.monotonicMs()
                var outcome = StepOutcome(true, if (fallback) "loop_stop:times;times_fallback" else "loop_stop:times")
                for (iteration in 0 until times) {
                    if (access.monotonicMs() - started >= step.maxTotalMs) { outcome = StepOutcome(true, "loop_stop:max_total_ms"); break }
                    val child = performChildren(step.steps, access, targetPackages, variables, "loop")
                    if (!child.ok) return child
                    if (access.monotonicMs() - started >= step.maxTotalMs) { outcome = StepOutcome(true, "loop_stop:max_total_ms"); break }
                    val until = step.until?.let { substitute(it, variables) }
                    if (until != null && access.probe(until, 1_000)) return StepOutcome(true, "loop_stop:until")
                }
                outcome
            }
        }
        is ScriptStep.Launch -> {
            val pkg = step.packageName ?: targetPackages.firstOrNull()
                ?: return StepOutcome(false, "launch_no_package", "invalid_script")
            if (!access.launchApp(pkg)) StepOutcome(false, "launch_failed", "launch_failed")
            else { val wait = step.waitMs.coerceIn(0, 15_000); access.waitMs(wait); StepOutcome(true, "launch_wait_ms=$wait") }
        }
        is ScriptStep.Back -> {
            val ok = access.back()
            StepOutcome(ok, if (ok) "back" else "back_failed")
        }
        is ScriptStep.WaitStable -> waitStable(step, access)
        is ScriptStep.Snapshot -> {
            val tag = step.tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "_" }
            val ok = access.saveSnapshot(tag)
            StepOutcome(ok, if (ok) "snapshot:$tag" else "capture_failed")
        }
    }

    private suspend fun waitStable(step: ScriptStep.WaitStable, access: ScriptAccess): StepOutcome {
        val frames = step.stableFrames.coerceIn(2, 6)
        val interval = step.intervalMs.coerceIn(100, 1_000)
        val timeout = step.stableTimeoutMs.coerceIn(500, 30_000)
        val tolerance = step.tolerance.coerceIn(0, 32)
        val started = access.monotonicMs()
        var previous = access.frameSample() ?: return StepOutcome(false, "capture_failed")
        var stable = 0
        while (true) {
            access.waitMs(interval)
            val current = access.frameSample() ?: return StepOutcome(false, "capture_failed")
            stable = if (meanAbsDiff(previous, current) <= tolerance) stable + 1 else 0
            val elapsed = access.monotonicMs() - started
            if (stable >= frames - 1) return StepOutcome(true, "stable_after=${elapsed}ms")
            if (elapsed >= timeout) return StepOutcome(false, "not_stable", "not_stable")
            previous = current
        }
    }

    private suspend fun performChildren(steps: List<ScriptStep>, access: ScriptAccess,
        targetPackages: Collection<String>, variables: MutableMap<String, String>, owner: String): StepOutcome {
        for (step in steps) {
            var attempt = 0
            var outcome: StepOutcome
            do {
                if (step !is ScriptStep.Launch) ScriptGuard.rejection(access.foregroundPackage(), targetPackages)?.let { return StepOutcome(false, it, it) }
                attempt++
                outcome = execute(step, access, targetPackages, variables)
            } while (!outcome.ok && attempt <= step.retries)
            if (!outcome.ok) return StepOutcome(false, "$owner:${outcome.message}", outcome.errorCode)
        }
        return StepOutcome(true, owner)
    }

    private fun substitute(value: String, variables: Map<String, String>) =
        Regex("\\$\\{([^}]+)\\}").replace(value) { variables[it.groupValues[1]] ?: it.value }
    private fun firstNumber(value: String): String? = Regex("\\d+").find(value)?.value

    private fun log(index: Int, step: ScriptStep, success: Boolean, attempts: Int, message: String, prefix: String = "") =
        ScriptRunLog(index, step.id, prefix + when (step) {
            is ScriptStep.Tap -> "tap"; is ScriptStep.TapText -> "tapText"; is ScriptStep.Wait -> "wait"
            is ScriptStep.Probe -> "probe"; is ScriptStep.Read -> "read"; is ScriptStep.Swipe -> "swipe"
            is ScriptStep.TapIfText -> "tapIfText"; is ScriptStep.IfPage -> "if_page"; is ScriptStep.Loop -> "loop"
            is ScriptStep.Launch -> "launch"; is ScriptStep.Back -> "back"; is ScriptStep.WaitStable -> "wait_stable"
            is ScriptStep.Snapshot -> "snapshot"
        }, success, attempts, message)
    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000
    private data class StepOutcome(val ok: Boolean, val message: String, val errorCode: String? = null)
}
