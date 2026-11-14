package com.hermesandroid.relay.script.runtime

import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import com.axiomlabs.hermesrelay.localvision.FrameArchive
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.relay.LocalVisionBridgeRoutes
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import kotlinx.coroutines.delay
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object ScriptRuntime {
    private val runner = ScriptRunner()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Catalog(val scripts: List<RuntimeScript>)

    fun load(context: Context, scriptId: String): RuntimeScript? {
        val cache = ScriptCatalog.cacheFile(context)
        val content = if (cache.isFile) cache.readText()
            else context.assets.open("scripts.json").bufferedReader().use { it.readText() }
        val scripts = if (content.trimStart().startsWith("[")) json.decodeFromString<List<RuntimeScript>>(content)
            else json.decodeFromString<Catalog>(content).scripts
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

    suspend fun run(context: Context, screenCapture: ScreenCapture?, scriptId: String): ScriptRunResult {
        val store = ScriptRunStore(context.filesDir)
        store.cleanupExpiredLogs()
        val script = runCatching { load(context, scriptId) }.getOrElse {
            return ScriptRunResult(success = false, errorCode = "invalid_script")
        } ?: return ScriptRunResult(success = false, errorCode = "script_not_found")
        val service = HermesAccessibilityService.instance
            ?: return ScriptRunResult(success = false, errorCode = "service_unavailable")
        val runId = UUID.randomUUID().toString()
        val startedAt = System.nanoTime()
        val app = context.applicationContext as com.hermesandroid.relay.HermesRelayApp
        val connection = app.runtime.connectionViewModel
        val reporter = ScriptEventReporter(HttpScriptEventSender(
            baseUrl = { connection.apiServerUrl.value }, bearer = connection::getApiKey,
        ), outbox = ScriptEventOutbox(context.filesDir))
        // Block 24: replay anything the server missed before a new run starts.
        reporter.flush()
        val result = runner.run(
            script = script,
            access = AndroidAccess(context, screenCapture, service),
            runId = runId,
            onRunStart = {
                store.writePending(PendingScriptRun(script.id, runId, 0))
                store.append(ScriptLogEvent("run_start", script.id, runId, ScriptLogEvent.timestamp()))
            },
            onStep = { log, durationMs ->
                store.writePending(PendingScriptRun(script.id, runId, log.stepIndex))
                store.append(ScriptLogEvent(
                    event = "step", scriptId = script.id, runId = runId, ts = ScriptLogEvent.timestamp(),
                    stepIndex = log.stepIndex, op = log.op, success = log.success, attempts = log.attempts,
                    message = log.message, durationMs = durationMs,
                ))
                if (!log.success) reporter.report(log.toEvent(script.id, runId))
            },
        )
        if (result.errorCode == "run_in_progress") return result
        store.append(ScriptLogEvent(
            event = "run_end", scriptId = script.id, runId = runId, ts = ScriptLogEvent.timestamp(),
            success = result.success, durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            errorCode = result.errorCode,
        ))
        reporter.report(ScriptEvent(
            scriptId = script.id, runId = runId, level = if (result.success) "info" else "error",
            stepIndex = result.logs.lastOrNull()?.stepIndex ?: 0,
            op = "run_end", message = result.errorCode ?: "completed", ts = java.time.Instant.now().toString(),
        ))
        store.clearPending()
        return result
    }

    private class AndroidAccess(
        private val context: Context,
        private val capture: ScreenCapture?,
        private val service: HermesAccessibilityService,
    ) : ScriptAccess {
        override fun foregroundPackage(): String? =
            service.rootInActiveWindow?.packageName?.toString() ?: service.currentApp

        override suspend fun tap(x: Int, y: Int) = service.actionExecutor.tap(x, y).ok
        override suspend fun tapText(text: String) = service.actionExecutor.tapText(text).ok
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) =
            service.actionExecutor.swipe(fromX, fromY, toX, toY, durationMs).ok
        override fun screenSize(): Pair<Int, Int> =
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

        override suspend fun probe(expect: String, timeoutMs: Long): Boolean {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
            do {
                // save_unknown=false：probe 在超时前会轮询多次，每轮都存档会把手机构建目录刷爆
                val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject {
                    put("with_frame", false)
                    put("save_unknown", false)
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

        override suspend fun launchApp(pkg: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }

        override suspend fun back(): Boolean =
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

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
    }
}
