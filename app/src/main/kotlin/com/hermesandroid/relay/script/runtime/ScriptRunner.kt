package com.hermesandroid.relay.script.runtime

import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

interface ScriptAccess {
    /** Must read rootInActiveWindow.packageName now, falling back only to the last window event. */
    fun foregroundPackage(): String?
    /** Read live application windows; never fall back to accessibility event caches. */
    fun foregroundPackageFromWindows(): String?
    /** Dimensions of an actual captured frame, or null when capture is unavailable. */
    suspend fun frameSize(): Pair<Int, Int>?
    suspend fun tap(x: Int, y: Int): Boolean
    /** Tap the matched template center plus offset and return "hit:<score>:<x>,<y>", or null on a miss. */
    suspend fun tapTemplate(name: String, area: List<Int>?, minScore: Double,
        offsetX: Int, offsetY: Int): String? = null
    suspend fun tapText(text: String): Boolean
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean = false
    fun screenOn(): Boolean = true
    fun screenSize(): Pair<Int, Int> = Int.MAX_VALUE to Int.MAX_VALUE
    fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    suspend fun probe(expect: String, timeoutMs: Long): Boolean
    /** Compatibility overload: existing access implementations keep their default-spec behavior. */
    suspend fun probe(expect: String, timeoutMs: Long, game: String? = null): Boolean = probe(expect, timeoutMs)
    suspend fun read(field: String): String?
    suspend fun launchApp(pkg: String): Boolean = false
    suspend fun forceStop(pkg: String, waitMs: Long): Boolean = false
    val forceStopFailureReason: String get() = "unsupported"
    /** Back from a root page may leave the app; scripts must follow it with a probe. */
    suspend fun back(): Boolean = false
    /** 立刻判一次当前页（不等待、不点屏）；判不出返回 null。脚本用 op "page" 调它。 */
    suspend fun currentPage(): String? = null
    suspend fun currentPage(game: String? = null): String? = currentPage()
    suspend fun frameSample(): IntArray? = null
    suspend fun saveSnapshot(tag: String): Boolean = false
    suspend fun recognizePowers(minConfidence: Double): PowerRecognition? = null
    /** 冷启动到 home 页的全自动流程；返回 "ok:home_at_<ms>"、"ok:already_on_<page>" 或 "fail:<原因>"。 */
    suspend fun coldStartTo(pkg: String?, bootPage: String, homePage: String,
        tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long,
        intervalMs: Long, popupPage: String, popupX: Int, popupY: Int,
        maxPopups: Int, acceptPages: List<String>, orientation: String): String = "unsupported"
    suspend fun waitMs(ms: Long) = delay(ms)
}

data class ScriptRunLog(val stepIndex: Int, val stepId: String?, val op: String,
    val success: Boolean, val attempts: Int, val message: String,
    val loopDepth: Int? = null, val iteration: Int? = null)
data class ScriptRunResult(val runId: String = UUID.randomUUID().toString(), val success: Boolean,
    val errorCode: String? = null, val logs: List<ScriptRunLog> = emptyList())

data class PowerReading(val value: Int?, val confidence: Float = -1f)
data class PowerRecognition(val cards: List<List<PowerReading>>, val durationMs: Long)

internal fun meanAbsDiff(first: IntArray, second: IntArray): Int {
    if (first.size != second.size) return Int.MAX_VALUE
    if (first.isEmpty()) return 0
    return (first.indices.sumOf { abs(first[it].toLong() - second[it].toLong()) } / first.size)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

class ScriptRunner {
    companion object {
        private val running = AtomicBoolean(false)
    }

    suspend fun run(
        script: RuntimeScript,
        access: ScriptAccess,
        runId: String = UUID.randomUUID().toString(),
        onRunStart: suspend () -> Unit = {},
        onStep: suspend (ScriptRunLog, Long) -> Unit = { _, _ -> },
        resolveScript: (String) -> RuntimeScript? = { null },
        evidenceStore: ScriptRunStore? = null,
        onPause: suspend (ScriptEvent) -> Unit = {},
    ): ScriptRunResult {
        if (!running.compareAndSet(false, true)) return ScriptRunResult(runId = runId, success = false, errorCode = "run_in_progress")
        ScriptRunControl.reset()
        val logs = mutableListOf<ScriptRunLog>()
        val variables = RunVariables(resolveScript, mutableListOf(script.id), script.id, runId, onPause,
            recordChild = { entry, durationMs ->
                logs += entry
                runCatching { onStep(entry, durationMs) }
            }).apply {
            putAll(script.vars.mapValues { it.value.content })
        }
        try {
            runCatching { onRunStart() }
            var failure: String? = null
            for ((index, step) in script.steps.withIndex()) {
                variables.stepIndex = index
                val startedAt = System.nanoTime()
                var attempt = 0
                var outcome = StepOutcome(false, "not_executed")
                do {
                    val stopped = stopOutcome(access)
                    if (stopped != null) {
                        outcome = stopped
                        break
                    }
                    // 容器只调度子步骤；前台校验由子步骤各自执行。
                    if (step !is ScriptStep.Launch && step !is ScriptStep.ForceStop && step !is ScriptStep.ColdStart &&
                        step !is ScriptStep.WaitForeground && step !is ScriptStep.Page && step !is ScriptStep.IfVar &&
                    step !is ScriptStep.Loop && step !is ScriptStep.IfPage && step !is ScriptStep.Include &&
                        step !is ScriptStep.Pause && step !is ScriptStep.AskServer) {
                        ScriptGuard.rejection(access.foregroundPackage(), script.targetPackages)?.let { code ->
                            val entry = log(index, step, false, attempt, code)
                            logs += entry
                            runCatching { onStep(entry, elapsedMs(startedAt)) }
                            failure = code
                            break
                        }
                    }
                    attempt++
                    outcome = execute(step, access, script.targetPackages, variables)
                    if (!outcome.isTerminal) outcome = stopOutcome(access) ?: outcome
                } while (!outcome.ok && !outcome.isTerminal && attempt <= step.retries)
                if (failure != null) break
                val entry = log(index, step, outcome.ok, attempt, outcome.message)
                logs += entry
                runCatching { onStep(entry, elapsedMs(startedAt)) }
                if (!outcome.ok) {
                    failure = outcome.errorCode ?: "step_failed"
                    break
                }
            }
            val failedStepIndex = logs.lastOrNull()?.stepIndex ?: 0
            if (failure != null && failure !in setOf("cancelled", "screen_off")) runOnFail(script, access, variables, logs, onStep)
            stopOutcome(access)?.let { failure = it.errorCode }
            val evidenceError = failure
            if (script.autoEvidence && evidenceError != null && evidenceError !in setOf("cancelled", "screen_off")) {
                val tag = "fail_${script.id}_$failedStepIndex"
                val saved = runCatching { access.saveSnapshot(tag) }.getOrDefault(false)
                runCatching { evidenceStore?.appendFailureEvidence(script.id, runId, evidenceError, logs, tag, saved) }
            }
            return ScriptRunResult(runId = runId, success = failure == null, errorCode = failure, logs = logs)
        } finally { ScriptRunControl.endPause(); running.set(false) }
    }

    private suspend fun runOnFail(script: RuntimeScript, access: ScriptAccess, variables: RunVariables,
        logs: MutableList<ScriptRunLog>, onStep: suspend (ScriptRunLog, Long) -> Unit) {
        val started = access.monotonicMs()
        for (step in script.onFail.take(10)) {
            stopOutcome(access)?.let {
                val entry = log(-1, step, false, 0, it.message, "on_fail:")
                logs += entry
                runCatching { onStep(entry, 0) }
                return
            }
            val remaining = 60_000 - (access.monotonicMs() - started)
            if (remaining <= 0) break
            val stepStarted = System.nanoTime()
            val guarded = if (step is ScriptStep.Launch || step is ScriptStep.ForceStop || step is ScriptStep.Page ||
                step is ScriptStep.IfVar || step is ScriptStep.WaitForeground || step is ScriptStep.Include ||
                step is ScriptStep.Pause || step is ScriptStep.AskServer) null
                else ScriptGuard.rejection(access.foregroundPackage(), script.targetPackages)
            val performed = guarded?.let { StepOutcome(false, it, it) } ?: withTimeoutOrNull(remaining) {
                execute(step, access, script.targetPackages, variables)
            } ?: StepOutcome(false, "timeout", "step_timeout")
            val outcome = if (performed.isTerminal) performed else stopOutcome(access) ?: performed
            val entry = log(-1, step, outcome.ok, 1, outcome.message, "on_fail:")
            logs += entry
            runCatching { onStep(entry, elapsedMs(stepStarted)) }
            if (outcome.isTerminal) return
        }
    }

    private suspend fun execute(step: ScriptStep, access: ScriptAccess, targetPackages: Collection<String>,
        variables: RunVariables, depth: Int = 0): StepOutcome {
        if (depth > 8) return StepOutcome(false, "nesting_too_deep", "invalid_script")
        stopOutcome(access)?.let { return it }
        // Polling primitives own their deadlines and timeout result messages.
        val timeout = if (step is ScriptStep.Probe || step is ScriptStep.IfPage || step is ScriptStep.WaitForeground ||
            step is ScriptStep.Pause || step is ScriptStep.AskServer) null else step.timeoutMs?.coerceAtLeast(1)
        return try {
            val result = if (timeout == null) perform(step, access, targetPackages, variables, depth)
            else withTimeoutOrNull(timeout) { perform(step, access, targetPackages, variables, depth) }
                ?: StepOutcome(false, "timeout", "step_timeout")
            variables.lastStep = step.id ?: log(0, step, result.ok, 1, result.message).op
            result
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // 任何一步抛异常都必须变成一条失败的 step 日志，不允许整条脚本 500
            StepOutcome(false, "exception:${error.javaClass.simpleName}:${error.message?.take(80)}", "step_exception")
        }
    }

    private suspend fun perform(step: ScriptStep, access: ScriptAccess, targetPackages: Collection<String>,
        variables: RunVariables, depth: Int): StepOutcome {
        return when (step) {
            is ScriptStep.Pause -> pause(step.reason, step.pauseTimeoutMs, false, emptyList(), access, targetPackages, variables, depth)
            is ScriptStep.AskServer -> pause(step.reason, step.pauseTimeoutMs, true, step.thenSteps, access, targetPackages, variables, depth)
            is ScriptStep.Tap -> StepOutcome(access.tap(step.x, step.y), "tap")
            is ScriptStep.TapTemplate -> {
                val hit = access.tapTemplate(step.template, step.area, step.min, step.offsetX, step.offsetY)
                if (hit != null) {
                    val (_, score, point) = hit.split(':', limit = 3)
                    step.asName?.takeIf { it.isNotEmpty() }?.let { variables[it] = point }
                    val formattedScore = String.format(Locale.ROOT, "%.2f", score.toDouble())
                    StepOutcome(true, "template=${step.template} score=$formattedScore at=$point")
                } else if (step.optional) {
                    StepOutcome(true, "absent:${step.template}")
                } else {
                    StepOutcome(false, "template_not_found:${step.template}")
                }
            }
            is ScriptStep.TapText -> StepOutcome(access.tapText(substitute(step.text, variables)), "tapText")
            is ScriptStep.Wait -> {
                val clamped = step.ms.coerceIn(0, 30_000)
                access.waitMs(clamped); StepOutcome(true, "wait_ms=$clamped")
            }
            is ScriptStep.Probe -> StepOutcome(access.probe(step.expect, step.timeoutMs ?: 3_000, step.game), "probe=${step.expect}")
            is ScriptStep.Read -> access.read(step.field)?.let { value ->
                firstNumber(value)?.let { variables[step.asName] = it }
                StepOutcome(true, "${step.field}=$value")
            } ?: if (step.optional) {
                variables[step.asName] = ""
                StepOutcome(true, "read_optional:${step.field}=null")
            } else StepOutcome(false, "read_failed:${step.field}")
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
                val expect = substitute(step.expect, variables)
                val timeout = (step.timeoutMs ?: 3_000).coerceAtLeast(1)
                val hit = withTimeoutOrNull(timeout) { access.probe(expect, timeout, step.game) }
                    ?: return StepOutcome(false, "timeout", "step_timeout")
                val branch = if (hit) step.thenSteps else step.elseSteps
                performChildren(branch, access, targetPackages, variables, "if_page", depth + 1)
            }
            is ScriptStep.Loop -> {
                val fromVariable = step.timesFrom?.let { variables[it] }?.let(::firstNumber)?.toIntOrNull()
                val times = fromVariable ?: step.times
                val fallback = step.timesFrom != null && fromVariable == null
                if (times !in 1..100 || step.maxTotalMs < 0) {
                    StepOutcome(false, "invalid_loop", "invalid_script")
                } else {
                    val maxTotalMs = step.maxTotalMs.takeIf { it > 0 } ?: 600_000
                    val started = access.monotonicMs()
                    var outcome = StepOutcome(true, if (fallback) "loop_stop:times;times_fallback" else "loop_stop:times")
                    for (iteration in 0 until times) {
                        stopOutcome(access)?.let { return it }
                        if (access.monotonicMs() - started >= maxTotalMs) { outcome = StepOutcome(true, "loop_stop:max_total_ms"); break }
                        variables.loopIterations += iteration + 1
                        val child = try {
                            withTimeoutOrNull(maxTotalMs - (access.monotonicMs() - started)) {
                                performChildren(step.steps, access, targetPackages, variables, "loop", depth + 1)
                            } ?: return StepOutcome(true, "loop_stop:max_total_ms")
                        } finally {
                            variables.loopIterations.removeAt(variables.loopIterations.lastIndex)
                        }
                        if (!child.ok) return child
                        if (access.monotonicMs() - started >= maxTotalMs) { outcome = StepOutcome(true, "loop_stop:max_total_ms"); break }
                        val condition = step.untilCondition ?: step.until?.let { ScriptStep.LoopUntil(it) }
                        if (condition != null) {
                            val hit = withTimeoutOrNull(maxTotalMs - (access.monotonicMs() - started)) {
                                access.probe(substitute(condition.page, variables), 1_000, condition.spec)
                            } ?: return StepOutcome(true, "loop_stop:max_total_ms")
                            if (hit) return StepOutcome(true, "loop_stop:until")
                        }
                    }
                    outcome
                }
            }
            is ScriptStep.ForceStop -> {
                try {
                    if (access.forceStop(step.packageName, step.waitMs.coerceIn(0, 10_000)))
                        StepOutcome(true, "force_stop:${step.packageName}")
                    else StepOutcome(false, if (access.forceStopFailureReason == "force_stop_button_missing")
                        "force_stop_button_missing" else "force_stop_failed:${access.forceStopFailureReason}")
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    StepOutcome(false, "force_stop_failed:${error.javaClass.simpleName}")
                }
            }
            is ScriptStep.Launch -> {
                val pkg = step.packageName ?: targetPackages.firstOrNull()
                    ?: return StepOutcome(false, "launch_no_package", "invalid_script")
                if (!access.launchApp(pkg)) StepOutcome(false, "launch_failed", "launch_failed")
                else { val wait = step.waitMs.coerceIn(0, 90_000); access.waitMs(wait); StepOutcome(true, "launch_wait_ms=$wait") }
            }
            is ScriptStep.Page -> {
                val current = access.currentPage(step.game) ?: "other"
                variables[step.asName] = current
                StepOutcome(true, "page=$current as=${step.asName}")
            }
            is ScriptStep.IfVar -> {
                val value = variables[step.name] ?: ""
                val hit = when {
                    step.notEquals != null -> value != step.notEquals
                    step.equals != null -> value == step.equals
                    else -> false
                }
                performChildren(if (hit) step.thenSteps else step.elseSteps, access, targetPackages, variables, "if_var", depth + 1)
            }
            is ScriptStep.Include -> {
                if (step.script in variables.includeStack) return StepOutcome(false, "include_cycle:${step.script}", "invalid_script")
                val included = try {
                    variables.resolveScript(step.script)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return StepOutcome(false, "include_invalid:${step.script}", "invalid_script")
                } ?: return StepOutcome(false, "include_missing:${step.script}", "script_not_found")
                variables.includeStack += step.script
                try {
                    performChildren(included.steps, access, targetPackages, variables, "include", depth + 1)
                } finally {
                    variables.includeStack.removeAt(variables.includeStack.lastIndex)
                }
            }
            is ScriptStep.SetVar -> {
                variables[step.name] = substitute(step.value, variables)
                StepOutcome(true, "set_var:${step.name}")
            }
            is ScriptStep.IncrVar -> {
                val value = variables[step.name]?.toLongOrNull() ?: 0L
                variables[step.name] = (value + step.by).toString()
                StepOutcome(true, "incr_var:${step.name}")
            }
            is ScriptStep.Back -> {
                val ok = access.back()
                StepOutcome(ok, if (ok) "back" else "back_failed")
            }
            is ScriptStep.WaitForeground -> waitForeground(step, access, targetPackages)
            is ScriptStep.WaitStable -> waitStable(step, access)
            is ScriptStep.ColdStart -> {
                val message = access.coldStartTo(step.packageName ?: targetPackages.firstOrNull(), step.bootPage, step.homePage,
                    step.tapX, step.tapY, step.bootWaitMs, step.homeWaitMs, step.intervalMs,
                    step.popupPage, step.popupX, step.popupY, step.maxPopups, step.accept, step.orientation)
                val code = when {
                    message.startsWith("fail:cancelled") -> "cancelled"
                    message.startsWith("fail:screen_off") -> "screen_off"
                    else -> null
                }
                StepOutcome(message.startsWith("ok"), message, code)
            }
            is ScriptStep.Snapshot -> {
                val tag = step.tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "_" }
                val ok = access.saveSnapshot(tag)
                StepOutcome(ok, if (ok) "snapshot:$tag" else "capture_failed")
            }
            is ScriptStep.TapWeakest -> {
                if (step.centers.size !in 1..8 || step.y !in 0..access.screenSize().second) {
                    return StepOutcome(false, "invalid_tapWeakest_coordinates", "invalid_script")
                }
                val fallback = step.fallbackIndex.coerceIn(step.centers.indices)
                val minConfidence = step.minConfidence.coerceIn(0.0, 1.0)
                val recognition = access.recognizePowers(minConfidence)
                val cards = recognition?.cards.orEmpty()
                val sums = step.centers.indices.map { index -> cards.getOrNull(index)?.takeIf { it.size == 2 && it.all { reading ->
                    reading.value != null && (reading.confidence < 0f || reading.confidence >= minConfidence)
                } }
                    ?.sumOf { it.value!! } }
                val selected = sums.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index ?: fallback
                val isFallback = sums.all { it == null }
                val tapped = step.dry || access.tap(step.centers[selected], step.y)
                val readings = step.centers.indices.joinToString(";") { card ->
                    val rows = cards.getOrNull(card).orEmpty()
                    "$card:${rows.getOrNull(0)?.value ?: "?"}@${rows.getOrNull(0)?.confidence ?: -1f}+" +
                        "${rows.getOrNull(1)?.value ?: "?"}@${rows.getOrNull(1)?.confidence ?: -1f}=${sums[card] ?: "?"}"
                }
                val event = if (isFallback) "power_fallback" else "power_selected"
                StepOutcome(tapped, "$event cards=[$readings] selected=$selected duration_ms=${recognition?.durationMs ?: 0} dry=${step.dry}")
            }
        }
    }

    private suspend fun pause(reason: String, timeoutMs: Long, askServer: Boolean,
        thenSteps: List<ScriptStep>, access: ScriptAccess, targetPackages: Collection<String>,
        variables: RunVariables, depth: Int): StepOutcome {
        // Capture context before entering paused state; waiting performs no device access.
        val page = if (askServer) access.currentPage() else null
        val signal = ScriptRunControl.beginPause(variables.runId)
        val resumed = try {
            variables.onPause(ScriptEvent(variables.scriptId, variables.runId, "warn", variables.stepIndex,
                if (askServer) "ask_server" else "pause", reason, java.time.Instant.now().toString(),
                page, if (askServer) variables.lastStep else null))
            withTimeoutOrNull(timeoutMs.coerceIn(0, 3_600_000)) { ResumeSteps(signal.await()) }
        } finally {
            ScriptRunControl.endPause()
        }
        if (ScriptRunControl.isCancelled()) return StepOutcome(false, "cancelled reason=${ScriptRunControl.reason}", "cancelled")
        if (resumed == null) return StepOutcome(false, "timeout", "step_timeout")
        val children = if (askServer) resumed.steps ?: thenSteps else emptyList()
        if (children.isEmpty()) return StepOutcome(true, if (askServer) "ask_server" else "pause")
        return performChildren(children, access, targetPackages, variables, if (askServer) "ask_server" else "pause", depth + 1)
    }

    private data class ResumeSteps(val steps: List<ScriptStep>?)

    private suspend fun waitForeground(step: ScriptStep.WaitForeground, access: ScriptAccess,
        targetPackages: Collection<String>): StepOutcome {
        val pkg = step.packageName ?: targetPackages.firstOrNull() ?: return StepOutcome(false, "fail:no_package")
        val started = access.monotonicMs()
        val timeout = (step.timeoutMs ?: 60_000).coerceAtLeast(0)
        while (true) {
            stopOutcome(access)?.let { return it }
            val lastPackage = access.foregroundPackageFromWindows()
            if (lastPackage == pkg) {
                val orientationMatches = when (step.orientation) {
                    null, "any" -> true
                    else -> access.frameSize()?.let { (width, height) ->
                        if (step.orientation == "landscape") width > height else height > width
                    } ?: false
                }
                if (orientationMatches) return StepOutcome(true, "ok:foreground_after=${access.monotonicMs() - started}")
            }
            val elapsed = access.monotonicMs() - started
            if (elapsed >= timeout) return StepOutcome(false, "fail:foreground_timeout last=${lastPackage ?: "unknown"}")
            access.waitMs(minOf(100L, timeout - elapsed))
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
            stopOutcome(access)?.let { return it }
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
        targetPackages: Collection<String>, variables: RunVariables, owner: String, depth: Int = 0): StepOutcome {
        if (depth > 8) return StepOutcome(false, "nesting_too_deep", "invalid_script")
        for (step in steps) {
            val startedAt = System.nanoTime()
            var attempt = 0
            var outcome: StepOutcome
            var rejectedBeforeAction = false
            do {
                val stopped = stopOutcome(access)
                if (stopped != null) {
                    outcome = stopped
                    rejectedBeforeAction = true
                    break
                }
                val guarded = if (step !is ScriptStep.Launch && step !is ScriptStep.ForceStop && step !is ScriptStep.ColdStart &&
                    step !is ScriptStep.WaitForeground && step !is ScriptStep.Page && step !is ScriptStep.IfVar &&
                    step !is ScriptStep.Loop && step !is ScriptStep.IfPage && step !is ScriptStep.Include &&
                        step !is ScriptStep.Pause && step !is ScriptStep.AskServer)
                    ScriptGuard.rejection(access.foregroundPackage(), targetPackages) else null
                if (guarded != null) {
                    outcome = StepOutcome(false, guarded, guarded)
                    rejectedBeforeAction = true
                    break
                }
                attempt++
                outcome = execute(step, access, targetPackages, variables, depth)
                if (!outcome.isTerminal) outcome = stopOutcome(access) ?: outcome
            } while (!outcome.ok && !outcome.isTerminal && attempt <= step.retries)
            if (variables.loopIterations.isNotEmpty() && (step is ScriptStep.Page || step is ScriptStep.Tap)) {
                val message = if (step is ScriptStep.Tap) "${outcome.message} x=${step.x} y=${step.y}" else outcome.message
                val entry = log(variables.stepIndex, step, outcome.ok, attempt, message).copy(
                    loopDepth = variables.loopIterations.size,
                    iteration = variables.loopIterations.last(),
                )
                variables.recordChild(entry, elapsedMs(startedAt))
            }
            if (rejectedBeforeAction) return outcome
            if (!outcome.ok) return StepOutcome(false, "$owner:${outcome.message}", outcome.errorCode)
        }
        return StepOutcome(true, owner)
    }

    private fun substitute(value: String, variables: Map<String, String>) =
        Regex("\\$\\{([^\\x7D]+)\\}").replace(value) { variables[it.groupValues[1]] ?: it.value }
    private fun firstNumber(value: String): String? = Regex("\\d+").find(value)?.value

    private fun log(index: Int, step: ScriptStep, success: Boolean, attempts: Int, message: String, prefix: String = "") =
        ScriptRunLog(index, step.id, prefix + when (step) {
            is ScriptStep.Pause -> "pause"; is ScriptStep.AskServer -> "ask_server"
            is ScriptStep.Tap -> "tap"; is ScriptStep.TapText -> "tapText"; is ScriptStep.Wait -> "wait"
            is ScriptStep.TapTemplate -> "tap_template"
            is ScriptStep.Probe -> "probe"; is ScriptStep.Read -> "read"; is ScriptStep.Swipe -> "swipe"
            is ScriptStep.TapIfText -> "tapIfText"; is ScriptStep.IfPage -> "if_page"; is ScriptStep.Loop -> "loop"
            is ScriptStep.ForceStop -> "force_stop"
            is ScriptStep.Launch -> "launch"; is ScriptStep.Back -> "back"; is ScriptStep.WaitStable -> "wait_stable"
            is ScriptStep.Page -> "page"; is ScriptStep.IfVar -> "if_var"
            is ScriptStep.Include -> "include"; is ScriptStep.SetVar -> "set_var"; is ScriptStep.IncrVar -> "incr_var"
            is ScriptStep.Snapshot -> "snapshot"
            is ScriptStep.TapWeakest -> "tapWeakest"
            is ScriptStep.ColdStart -> "coldStart"
            is ScriptStep.WaitForeground -> "wait_foreground"
        }, success, attempts, message)
    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000
    private fun stopOutcome(access: ScriptAccess): StepOutcome? = when {
        ScriptRunControl.isCancelled() -> StepOutcome(false, "cancelled reason=${ScriptRunControl.reason}", "cancelled")
        !access.screenOn() -> StepOutcome(false, "screen_off", "screen_off")
        else -> null
    }

    /** Per-run state; include only shares variables, never child defaults or failure handlers. */
    private class RunVariables(
        val resolveScript: (String) -> RuntimeScript?,
        val includeStack: MutableList<String>,
        val scriptId: String,
        val runId: String,
        val onPause: suspend (ScriptEvent) -> Unit,
        val recordChild: suspend (ScriptRunLog, Long) -> Unit,
        var stepIndex: Int = 0,
        var lastStep: String? = null,
    ) : LinkedHashMap<String, String>() {
        // One-based iteration of each active loop, independent of include/if nesting.
        val loopIterations = mutableListOf<Int>()
    }

    private data class StepOutcome(val ok: Boolean, val message: String, val errorCode: String? = null) {
        val isTerminal: Boolean get() = errorCode == "cancelled" || errorCode == "screen_off"
    }

}
