package com.hermesandroid.relay.script.runtime

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import com.axiomlabs.hermesrelay.localvision.FrameArchive
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.relay.LocalVisionBridgeRoutes
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        runCatching { store.cleanupExpiredLogs() }
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
            runner.run(
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
                    event = if (log.message.startsWith("power_fallback")) "power_fallback" else "step",
                    scriptId = script.id, runId = runId, ts = ScriptLogEvent.timestamp(),
                    stepIndex = log.stepIndex, op = log.op, success = log.success, attempts = log.attempts,
                    message = log.message, durationMs = durationMs,
                ))
                if (!log.success) runCatching { reporter.report(log.toEvent(script.id, runId)) }
            },
        )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            store.clearPending()
            return ScriptRunResult(success = false, errorCode = "run_exception")
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
        override fun screenOn(): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

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
            // 必须走 service.startActivity：无障碍服务对「后台启动 Activity」有系统豁免，
            // 用 application context 会在 App 退到后台后被 Android 10+ 静默拦掉
            // （2026-11-15 实测：脚本里 launch 一直不生效，服务器 /open_app 却秒起，差别就在这一行）。
            return runCatching { service.startActivity(intent); true }.getOrDefault(false)
        }

        /**
         * 冷启动到 home：全自动闭环，中途不问服务器、不等外部指令。
         * ① 每 2 秒看一次画面方向，竖屏（桌面/被抢前台）就再拉起一次 App；
         * ② 连续两次横屏才认为游戏真起来了，避免一次误判就开始乱点；
         * ③ 之后每 intervalMs 判一次页，命中 boot 就点屏幕中心，点完 2 秒复查，最多 3 次；
         * ④ 命中 home 立刻返回，带上从启动到进主页的毫秒数。
         */
        override suspend fun coldStartTo(pkg: String?, bootPage: String, homePage: String,
            tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long, intervalMs: Long,
            popupPage: String, popupX: Int, popupY: Int, maxPopups: Int): String {
            val started = monotonicMs()
            val (screenWidth, screenHeight) = screenSize()
            val tx = tapX ?: (screenWidth / 2)
            val ty = tapY ?: (screenHeight / 2)
            var landscapeStreak = 0
            var lastLaunchAt = 0L
            var taps = 0
            var firstBootAt = 0L
            var lastPage = "?"
            var otherSince: Long? = null
            var rescues = 0
            var popupTaps = 0

            suspend fun detectPage(): String = runCatching {
                val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject {
                    put("with_frame", false)
                    put("save_unknown", false)
                })
                (result.payload["page"] as? JsonPrimitive)?.content ?: "other"
            }.getOrDefault("other")

            while (monotonicMs() - started < bootWaitMs) {
                if (ScriptRunControl.isCancelled()) return "fail:cancelled reason=${ScriptRunControl.reason} rescue=$rescues"
                if (!screenOn()) return "fail:screen_off rescue=$rescues"
                val frame = runCatching { capture?.captureLocalFrame()?.getOrNull() }.getOrNull()
                val landscape = frame != null && frame.width > frame.height
                if (!landscape) {
                    landscapeStreak = 0
                    if (monotonicMs() - lastLaunchAt >= 2_000) {
                        val name = pkg ?: return "fail:no_package rescue=$rescues"
                        lastLaunchAt = monotonicMs()
                        launchApp(name)
                    }
                    delay(2_000)
                    continue
                }
                landscapeStreak++
                if (landscapeStreak >= 2) break
                delay(2_000)
            }
            if (landscapeStreak < 2) {
                return "fail:no_landscape rescue=$rescues elapsed=${monotonicMs() - started}ms"
            }

            val pageDeadline = monotonicMs() + homeWaitMs
            while (monotonicMs() < pageDeadline) {
                if (ScriptRunControl.isCancelled()) return "fail:cancelled reason=${ScriptRunControl.reason} rescue=$rescues"
                if (!screenOn()) return "fail:screen_off rescue=$rescues"
                lastPage = detectPage()
                if (lastPage == homePage) {
                    return "ok:home_at_${monotonicMs() - started}ms taps=$taps popups=$popupTaps"
                }
                // 主页上常盖一层「信息」弹窗：判据会先命中 popup，此时点右上角 X 关掉再继续等主页。
                // 不处理的话会一直卡在 popup，永远不会等于 home（2026-11-15 真机实测）。
                if (lastPage == popupPage && popupTaps < maxPopups) {
                    tap(popupX, popupY)
                    popupTaps++
                    delay(1_500)
                    continue
                }
                val now = monotonicMs()
                if (lastPage == "other") {
                    val since = otherSince ?: now.also { otherSince = it }
                    // 只在「还没点到启动页」的加载阶段自救；已经开始点 boot 之后就别再重开游戏了
                    if (taps == 0 && pkg != null && now - since >= 20_000 && now - lastLaunchAt >= 20_000 && rescues < 3) {
                        lastLaunchAt = now
                        launchApp(pkg)
                        rescues++
                        otherSince = monotonicMs()
                    }
                } else {
                    otherSince = null
                }
                if (lastPage == bootPage) {
                    if (firstBootAt == 0L) firstBootAt = monotonicMs()
                    if (taps < 3 && monotonicMs() - firstBootAt < 15_000) {
                        tap(tx, ty)
                        taps++
                        delay(2_000)
                        continue
                    }
                }
                delay(intervalMs.coerceIn(300, 5_000))
            }
            return "fail:no_home rescue=$rescues last=$lastPage taps=$taps elapsed=${monotonicMs() - started}ms"
        }

        override suspend fun back(): Boolean =
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        override suspend fun currentPage(): String? = runCatching {
            val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject {
                put("with_frame", false)
                put("save_unknown", false)
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
