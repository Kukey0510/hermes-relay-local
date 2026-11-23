package com.hermesandroid.relay.script.runtime

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import com.axiomlabs.hermesrelay.localvision.FrameArchive
import com.axiomlabs.hermesrelay.localvision.Grayscale
import com.axiomlabs.hermesrelay.localvision.LocalVisionTemplateLoader
import com.axiomlabs.hermesrelay.localvision.Rect
import com.axiomlabs.hermesrelay.localvision.TemplateMatcher
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.bridge.BridgeRunTracker
import com.hermesandroid.relay.network.relay.LocalVisionBridgeRoutes
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object ScriptRuntime {
    private val runner = ScriptRunner()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var loadErrors: Map<String, String> = emptyMap()

    private fun catalogWarning(message: String) { android.util.Log.w("ScriptRuntime", message) }

    internal fun gamesDeclaration(content: String, warn: (String) -> Unit = ::catalogWarning): Map<String, GameDeclaration> =
        decodeGamesDeclaration(json.parseToJsonElement(content), json, warn)

    internal fun decodeCatalog(content: String, warn: (String) -> Unit = ::catalogWarning): List<RuntimeScript> {
        loadErrors = emptyMap()
        val root = json.parseToJsonElement(content)
        val games = decodeGamesDeclaration(root, json, warn)
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["scripts"] as? JsonArray ?: error("missing scripts array")
            else -> error("invalid catalog root")
        }
        val errors = mutableMapOf<String, String>()
        val scripts = entries.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement<RuntimeScript>(scriptWithGameDefaults(entry, games, warn)) }.getOrElse { error ->
                val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                if (id != null) errors[id] = error.message ?: error.javaClass.simpleName
                null
            }
        }
        loadErrors = errors.toMap()
        return scripts
    }

    internal fun missingScriptError(scriptId: String): String =
        if (loadErrors.containsKey(scriptId)) "invalid_script" else "script_not_found"

    internal fun coldStartTapPoint(tapX: Int?, tapY: Int?, frameSize: Pair<Int, Int>?,
        fallbackSize: () -> Pair<Int, Int>): Pair<Int, Int> {
        if (tapX != null && tapY != null) return tapX to tapY
        val (width, height) = frameSize ?: fallbackSize()
        return (tapX ?: width / 2) to (tapY ?: height / 2)
    }

    fun load(context: Context, scriptId: String): RuntimeScript? {
        val cache = ScriptCatalog.cacheFile(context)
        val content = if (cache.isFile) cache.readText()
            else context.assets.open("scripts.json").bufferedReader().use { it.readText() }
        val scripts = decodeCatalog(content)
        return scripts.firstOrNull { it.id == scriptId }
    }

    /** Server asks the phone to re-send whatever it could not deliver (bridge route). */
    suspend fun flushOutbox(context: Context): ScriptFlushResult {
        val app = context.applicationContext as com.hermesandroid.relay.HermesRelayApp
        val connection = app.runtime.connectionViewModel
        val reporter = ScriptEventReporter(HttpScriptEventSender(
            baseUrl = { connection.apiServerUrl.value }, bearer = connection::getApiKey,
        ), outbox = ScriptEventOutbox(context.filesDir))
        return reporter.flush()
    }

    data class RunStatus(
        val runId: String,
        val state: String = "running",
        val errorCode: String? = null,
        val lastStep: Int? = null,
        val startedAt: Long = System.nanoTime(),
        val finishedAt: Long? = null,
    ) {
        val elapsedMs: Long get() = ((finishedAt ?: System.nanoTime()) - startedAt) / 1_000_000
    }

    private val history = linkedMapOf<String, RunStatus>()
    private var activeRunId: String? = null
    private var stopRequested = false
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    @Synchronized fun status(runId: String): RunStatus? = history[runId]?.let {
        if (runId == activeRunId && ScriptRunControl.isPaused(runId)) it.copy(state = "paused") else it
    }

    /** Same JSON response contract for local and bridge callers. */
    @Synchronized fun resume(body: JsonObject): JsonObject {
        val runId = (body["runId"] as? JsonPrimitive)?.content.orEmpty()
        if (runId != activeRunId || history[runId]?.finishedAt != null) return buildJsonObject {
            put("ok", false); put("error", "run_not_found")
        }
        val steps = body["steps"]?.let { json.decodeFromJsonElement<List<ScriptStep>>(it) }
        ScriptRunControl.resume(runId, steps)
        return buildJsonObject { put("ok", true); put("runId", runId); put("resumed", true) }
    }

    @Synchronized fun isRunning(): Boolean = activeRunId != null

    /** Serialize cache replacement with run reservation; stale pending_run files do not block sync. */
    @Synchronized internal fun updateCatalogIfIdle(update: () -> Unit): Boolean {
        if (activeRunId != null) return false
        update()
        return true
    }

    @Synchronized private fun reserve(): String? {
        if (activeRunId != null) return null
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        stopRequested = false
        history[runId] = RunStatus(runId)
        while (history.size > 20) history.remove(history.keys.first())
        return runId
    }

    @Synchronized fun requestStop(reason: String) {
        if (activeRunId != null) stopRequested = true
        ScriptRunControl.requestStop(reason)
    }

    @Synchronized private fun stepCompleted(runId: String, stepIndex: Int) {
        history[runId]?.let { history[runId] = it.copy(lastStep = stepIndex) }
    }

    @Synchronized private fun restoreStopRequest() {
        if (stopRequested) ScriptRunControl.requestStop("bridge:/script/stop")
    }

    /** Reservation happens before returning, so concurrent requests cannot queue extra runs. */
    fun startAsync(context: Context, screenCapture: ScreenCapture?, scriptId: String): String? {
        val runId = reserve() ?: return null
        backgroundScope.launch { runReserved(context.applicationContext, screenCapture, scriptId, runId) }
        return runId
    }

    suspend fun run(context: Context, screenCapture: ScreenCapture?, scriptId: String): ScriptRunResult {
        val runId = reserve() ?: return ScriptRunResult(success = false, errorCode = "run_in_progress")
        return runReserved(context, screenCapture, scriptId, runId)
    }

    private suspend fun runReserved(context: Context, screenCapture: ScreenCapture?, scriptId: String,
        runId: String): ScriptRunResult {
        var errorCode: String? = "run_exception"
        try {
            val result = executeRun(context, screenCapture, scriptId, runId).copy(runId = runId)
            errorCode = result.errorCode
            return result
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            errorCode = "cancelled"
            throw cancel
        } catch (_: Exception) {
            return ScriptRunResult(runId = runId, success = false, errorCode = errorCode)
        } finally {
            synchronized(this) {
                history[runId]?.let {
                    history[runId] = it.copy(
                        state = if (errorCode == "cancelled") "cancelled" else "done",
                        errorCode = errorCode, finishedAt = System.nanoTime(),
                    )
                }
                activeRunId = null
            }
        }
    }

    /** Apply the script's bounded hold on every exit, including coroutine cancellation. */
    internal suspend fun <T> withForegroundHold(
        script: RuntimeScript,
        onHeld: (Long) -> Unit = {},
        block: suspend () -> T,
    ): T = try {
        block()
    } finally {
        val seconds = (script.holdForegroundMs / 1_000L)
            .coerceIn(0L, BridgeRunTracker.MAX_KEEP_FOREGROUND_SECONDS)
        if (seconds > 0L && HermesAccessibilityService.instance?.isMasterEnabled() == true) {
            BridgeRunTracker.keepForeground(seconds)
            runCatching { onHeld(seconds) }
        }
    }

    private suspend fun executeRun(context: Context, screenCapture: ScreenCapture?, scriptId: String,
        runId: String): ScriptRunResult {
        val store = ScriptRunStore(context.filesDir)
        val startedAt = System.nanoTime()
        fun earlyFailure(code: String): ScriptRunResult {
            runCatching { store.append(ScriptLogEvent(
                event = "run_end", scriptId = scriptId, runId = runId, ts = ScriptLogEvent.timestamp(),
                success = false, durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                errorCode = code, message = "errorCode=$code ",
            )) }
            return ScriptRunResult(success = false, errorCode = code)
        }
        runCatching { store.cleanupExpiredLogs() }
        val script = runCatching { load(context, scriptId) }.getOrElse {
            return earlyFailure("invalid_script")
        } ?: return earlyFailure(missingScriptError(scriptId))
        val service = HermesAccessibilityService.instance
            ?: return earlyFailure("service_unavailable")
        val app = context.applicationContext as com.hermesandroid.relay.HermesRelayApp
        val connection = app.runtime.connectionViewModel
        val reporter = ScriptEventReporter(HttpScriptEventSender(
            baseUrl = { connection.apiServerUrl.value }, bearer = connection::getApiKey,
        ), outbox = ScriptEventOutbox(context.filesDir))
        // Block 24: replay anything the server missed before a new run starts.
        runCatching { reporter.flush() }
        // 脚本跑的时候前台会被 Relay 自己的 12s 空闲回前台抢走（脚本步骤不算「服务器命令」，
        // 定时器不会重置）→ 下一个步骤就 guard_package_mismatch。这里在整轮期间持续续命。
        com.hermesandroid.relay.bridge.BridgeRunTracker.markForegroundChanged()
        val keepAlive = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (isActive) {
                com.hermesandroid.relay.bridge.BridgeRunTracker.onBridgeCommandActivity()
                kotlinx.coroutines.delay(4_000)
            }
        }
        val result = try {
            withForegroundHold(script, onHeld = { seconds ->
                store.append(ScriptLogEvent(
                    event = "keep_foreground", scriptId = script.id, runId = runId,
                    ts = ScriptLogEvent.timestamp(), message = "已保持前台 $seconds 秒",
                ))
            }) {
                runner.run(
                    script = script,
                    access = AndroidAccess(context, screenCapture, service),
                    runId = runId,
                    resolveScript = { id ->
                        load(context, id) ?: if (missingScriptError(id) == "invalid_script")
                            error("invalid included script") else null
                    },
                    evidenceStore = store,
                    onRunStart = {
                        restoreStopRequest()
                        store.writePending(PendingScriptRun(script.id, runId, 0))
                        store.append(ScriptLogEvent("run_start", script.id, runId, ScriptLogEvent.timestamp()))
                    },
                    onPause = { event ->
                        runCatching { store.append(ScriptLogEvent(
                            event = event.op, scriptId = script.id, runId = runId,
                            ts = event.ts, stepIndex = event.stepIndex, op = event.op, message = event.message,
                        )) }
                        reporter.report(event)
                    },
                    onStep = { log, durationMs ->
                        stepCompleted(runId, log.stepIndex)
                        store.writePending(PendingScriptRun(script.id, runId, log.stepIndex))
                        store.append(log.toLogEvent(script.id, runId, durationMs))
                        if (!log.success) runCatching { reporter.report(log.toEvent(script.id, runId)) }
                    },
                )
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            store.clearPending()
            return earlyFailure("run_exception")
        } finally {
            keepAlive.cancel()
        }
        if (result.errorCode == "run_in_progress") return result
        val endMessage = "errorCode=${result.errorCode} ${result.logs.lastOrNull()?.message.orEmpty().take(120)}"
        runCatching { store.append(ScriptLogEvent(
            event = "run_end", scriptId = script.id, runId = runId, ts = ScriptLogEvent.timestamp(),
            success = result.success, durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            errorCode = result.errorCode, message = endMessage,
        )) }
        runCatching { reporter.report(ScriptEvent(
            scriptId = script.id, runId = runId, level = if (result.success) "info" else if (result.errorCode in setOf("cancelled", "screen_off")) "warn" else "error",
            stepIndex = result.logs.lastOrNull()?.stepIndex ?: 0,
            op = "run_end", message = endMessage, ts = java.time.Instant.now().toString(),
        )) }
        runCatching { store.clearPending() }
        return result
    }

    internal class AndroidAccess(
        private val context: Context,
        private val capture: ScreenCapture?,
        private val service: HermesAccessibilityService,
    ) : ScriptAccess {
        override fun foregroundPackage(): String? =
            service.rootInActiveWindow?.packageName?.toString() ?: service.currentApp

        override fun foregroundPackageFromWindows(): String? = service.foregroundPackageFromWindows()

        override suspend fun frameSize(): Pair<Int, Int>? =
            runCatching { capture?.captureLocalFrame()?.getOrNull() }.getOrNull()?.let { it.width to it.height }

        override suspend fun tap(x: Int, y: Int) = service.actionExecutor.tap(x, y).ok
        override suspend fun tapTemplate(name: String, area: List<Int>?, minScore: Double,
            offsetX: Int, offsetY: Int): String? {
            val foreground = foregroundPackage()?.takeIf { it.isNotBlank() } ?: return null
            val frame = capture?.captureLocalFrame(fresh = true)?.getOrNull() ?: return null
            val searchArea = area?.let { (x, y, w, h) ->
                if (x < 0 || y < 0 || w <= 0 || h <= 0 ||
                    x.toLong() + w > frame.width || y.toLong() + h > frame.height
                ) return null
                Rect(x, y, w, h)
            }
            val (match, template) = withContext(Dispatchers.Default) {
                val template = LocalVisionTemplateLoader(context).load(name) ?: return@withContext null
                TemplateMatcher(template).find(Grayscale.fromRgb(frame), searchArea, minScore) to template
            } ?: return null
            if (!match.found) return null

            val (width, height) = screenSize()
            val maxX = (width - 1).coerceAtLeast(0).toLong()
            val maxY = (height - 1).coerceAtLeast(0).toLong()
            val x = (match.x.toLong() + template.width / 2 + offsetX).coerceIn(0L, maxX).toInt()
            val y = (match.y.toLong() + template.height / 2 + offsetY).coerceIn(0L, maxY).toInt()
            currentCoroutineContext().ensureActive()
            if (ScriptRunControl.isCancelled() || !screenOn() || foregroundPackage() != foreground) return null
            return if (tap(x, y)) "hit:${match.score}:$x,$y" else null
        }

        override suspend fun tapText(text: String) = service.actionExecutor.tapText(text).ok
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
            service.actionExecutor.swipe(fromX, fromY, toX, toY, durationMs).ok
        override fun screenOn(): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

        override fun screenSize(): Pair<Int, Int> =
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

        override suspend fun probe(expect: String, timeoutMs: Long): Boolean = probe(expect, timeoutMs, null)

        override suspend fun probe(expect: String, timeoutMs: Long, game: String?): Boolean {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
            do {
                // save_unknown=false：probe 在超时前会轮询多次，每轮都存档会把手机构建目录刷爆
                val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject {
                    put("with_frame", false)
                    put("save_unknown", false)
                    game?.let { put("game", it) }
                })
                if ((result.payload["page"] as? JsonPrimitive)?.content == expect) return true
                if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
                delay(100)
            } while (true)
        }

        override suspend fun read(field: String): String? {
            val result = LocalVisionBridgeRoutes.handleRead(capture, context, buildJsonObject { put("field", field) })
            return (result.payload["value"] as? JsonPrimitive)?.contentOrNull
        }

        override var forceStopFailureReason: String = "unsupported"
            private set

        override suspend fun forceStop(pkg: String, waitMs: Long): Boolean {
            fun fail(reason: String): Boolean {
                forceStopFailureReason = reason
                android.util.Log.w("ScriptRuntime", "force_stop failed: $reason")
                return false
            }
            fun nodes() = service.rootInActiveWindow?.let { service.reader.readScreen(it).nodes }.orEmpty()
            val stopLabels = setOf("强行停止", "强制停止", "Force stop")
            fun matches(node: com.hermesandroid.relay.accessibility.ScreenReader.ScreenNode,
                labels: Set<String>) = labels.any {
                it.equals(node.text?.trim(), ignoreCase = true) ||
                    it.equals(node.contentDescription?.trim(), ignoreCase = true)
            }
            try {
                android.util.Log.i("ScriptRuntime", "force_stop open settings: $pkg")
                val opened = service.actionExecutor.sendIntent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$pkg", null, null, null, null)
                if (!opened.ok) return fail("open_settings")
                delay(1_000)
                val button = nodes().firstOrNull { it.enabled && matches(it, stopLabels) }
                    ?: return fail("force_stop_button_missing")
                android.util.Log.i("ScriptRuntime", "force_stop tap stop button")
                if (!tap(button.bounds.centerX, button.bounds.centerY)) return fail("tap_stop")
                delay(600)
                val currentNodes = nodes()
                val confirm = currentNodes.firstOrNull { it.enabled && it.viewId == "android:id/button1" }
                    ?: currentNodes.firstOrNull { it.enabled && matches(it, setOf("确定", "强行停止", "OK")) }
                if (confirm != null) {
                    android.util.Log.i("ScriptRuntime", "force_stop tap confirmation")
                    if (!tap(confirm.bounds.centerX, confirm.bounds.centerY)) return fail("tap_confirmation")
                } else android.util.Log.i("ScriptRuntime", "force_stop no confirmation")
                val wait = waitMs.coerceIn(0, 10_000)
                android.util.Log.i("ScriptRuntime", "force_stop wait_ms=$wait")
                delay(wait)
                android.util.Log.i("ScriptRuntime", "force_stop complete: $pkg")
                return true
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                return fail(error.javaClass.simpleName)
            }
        }

        override suspend fun launchApp(pkg: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 必须走 service.startActivity：无障碍服务对「后台启动 Activity」有系统豁免，
            // 用 application context 会在 App 退到后台后被 Android 10+ 静默拦掉
            // （2026-11-15 实测：脚本里 launch 一直不生效，服务器 /open_app 却秒起，差别就在这一行）。
            return runCatching { service.startActivity(intent); true }.getOrDefault(false)
        }

        /** Window identity confirms foreground independently of frame orientation. */
        override suspend fun coldStartTo(pkg: String?, bootPage: String, homePage: String,
            tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long, intervalMs: Long,
            popupPage: String, popupX: Int, popupY: Int, maxPopups: Int,
            acceptPages: List<String>, orientation: String): String = coldStartToPages(
                pkg, bootPage, homePage, tapX, tapY, bootWaitMs, homeWaitMs, intervalMs,
                popupPage, popupX, popupY, maxPopups, acceptPages, orientation,
                readForeground = service::foregroundPackageFromWindows,
                detectPage = { currentPage() ?: "other" },
                frameSize = {
                    runCatching { capture?.captureLocalFrame()?.getOrNull() }
                        .getOrNull()?.let { it.width to it.height }
                },
            )

        override suspend fun back(): Boolean =
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        override suspend fun currentPage(): String? = currentPage(null)

        override suspend fun currentPage(game: String?): String? = runCatching {
            val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject {
                put("with_frame", false)
                put("save_unknown", false)
                game?.let { put("game", it) }
            })
            (result.payload["page"] as? JsonPrimitive)?.content
        }.getOrNull()

        override suspend fun frameSample(): IntArray? {
            val image = capture?.captureLocalFrame()?.getOrNull() ?: return null
            val sampleCount = (image.rgb.size + 31) / 32
            val channels = IntArray(sampleCount * 3)
            var output = 0
            for (index in image.rgb.indices step 32) {
                val pixel = image.rgb[index]
                channels[output++] = pixel ushr 16 and 0xff
                channels[output++] = pixel ushr 8 and 0xff
                channels[output++] = pixel and 0xff
            }
            return channels
        }

        override suspend fun saveSnapshot(tag: String): Boolean {
            val image = capture?.captureLocalFrame()?.getOrNull() ?: return false
            return FrameArchive.save(context, image, tag) != null
        }

        override suspend fun recognizePowers(minConfidence: Double): PowerRecognition? {
            val started = android.os.SystemClock.elapsedRealtime()
            val frame = capture?.captureLocalFrame()?.getOrNull() ?: return null
            val composite = runCatching { PowerImagePreprocessor.compose(frame) }.getOrNull() ?: return null
            val bitmap = Bitmap.createBitmap(composite.width, composite.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(composite.pixels, 0, composite.width, 0, 0, composite.width, composite.height)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result: Text = try {
                suspendCancellableCoroutine<Text?> { continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
                }
            } finally {
                recognizer.close(); bitmap.recycle()
            } ?: return null
            val pieces = Array(8) { mutableListOf<Pair<Int, String>>() }
            result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.forEach { element ->
                val box = element.boundingBox ?: return@forEach
                val stripIndex = composite.strips.indexOfFirst { box.centerX() in it.left until it.right }
                val digits = element.text.filter(Char::isDigit)
                if (stripIndex >= 0 && digits.isNotEmpty()) pieces[stripIndex] += box.left to digits
            }
            val cards = (0..3).map { card -> (0..1).map { row ->
                val text = pieces[card * 2 + row].sortedBy { it.first }.joinToString("") { it.second }
                // Latin Text Recognition v2 exposes no per-element confidence; -1 means unavailable.
                PowerReading(text.toIntOrNull(), -1f)
            } }
            return PowerRecognition(cards, android.os.SystemClock.elapsedRealtime() - started)
        }
    }
}
