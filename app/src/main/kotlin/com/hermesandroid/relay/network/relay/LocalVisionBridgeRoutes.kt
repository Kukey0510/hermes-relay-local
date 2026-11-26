package com.hermesandroid.relay.network.relay

import android.content.Context
import android.os.SystemClock
import com.axiomlabs.hermesrelay.localvision.DigestReader
import com.axiomlabs.hermesrelay.localvision.FrameArchive
import com.axiomlabs.hermesrelay.localvision.LocalVisionRunner
import com.axiomlabs.hermesrelay.localvision.LocalVisionResult
import com.axiomlabs.hermesrelay.localvision.LocalVisionSpecStore
import com.axiomlabs.hermesrelay.localvision.RgbImage
import com.axiomlabs.hermesrelay.localvision.isValidLocalVisionGame
import com.axiomlabs.hermesrelay.localvision.localVisionPageFiles
import com.hermesandroid.relay.accessibility.ScreenCapture
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object LocalVisionBridgeRoutes {
    const val PATH: String = "/localvision"

    data class Result(val status: Int, val payload: JsonObject)

    private data class RunnerKey(val directory: File, val game: String?)

    private data class FileStamp(
        val file: File,
        val exists: Boolean,
        val isFile: Boolean,
        val modifiedAt: Long,
        val length: Long,
    )

    private data class RunnerCache(
        val files: List<FileStamp>,
        val runner: LocalVisionRunner,
    )

    private val runnerCache = mutableMapOf<RunnerKey, RunnerCache>()
    private val specStore = LocalVisionSpecStore()

    @Synchronized
    private fun runnerFor(context: Context, game: String?): LocalVisionRunner {
        val directory = File(context.filesDir, "localvision")
        val key = RunnerKey(directory, game)
        val files = localVisionPageFiles(directory, game).map {
            FileStamp(it, it.exists(), it.isFile, it.lastModified(), it.length())
        }
        runnerCache[key]?.takeIf { it.files == files }?.let { return it.runner }
        // Build eagerly under this lock so last-good specs follow request order.
        val runner = LocalVisionRunner(context, directory, game, specStore)
        runnerCache[key] = RunnerCache(files, runner)
        return runner
    }

    private fun detect(context: Context, image: RgbImage, game: String?): LocalVisionResult {
        val runner = runnerFor(context, game)
        // PageDetector owns a mutable template cache shared by requests.
        return synchronized(runner) { runner.detect(image) }
    }

    suspend fun handle(
        screenCapture: ScreenCapture?,
        appContext: Context?,
        body: JsonObject,
    ): Result {
        val gameValue = body["game"]?.takeUnless { it == JsonNull }
        if (gameValue != null && (gameValue !is JsonPrimitive || !gameValue.isString ||
                !isValidLocalVisionGame(gameValue.content))) return error(400, "invalid_game")
        val game = (gameValue as? JsonPrimitive)?.content
        if (screenCapture == null) return error(503, "capture_not_wired")

        val delayMs = ((body["delay_ms"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 5_000)
        val fresh = (body["fresh"] as? JsonPrimitive)?.booleanOrNull ?: false
        val withFrame = (body["with_frame"] as? JsonPrimitive)?.booleanOrNull ?: true
        val saveFrame = (body["save_frame"] as? JsonPrimitive)?.booleanOrNull ?: false
        val saveUnknown = (body["save_unknown"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (delayMs > 0) delay(delayMs.toLong())

        val started = SystemClock.elapsedRealtime()
        val image = screenCapture.captureLocalFrame(fresh || saveFrame).getOrElse { cause ->
            return error(
                status = 200,
                code = "capture_failed",
                detail = (cause.message ?: cause.toString()).take(200),
                captureMs = SystemClock.elapsedRealtime() - started,
                matchMs = 0L,
            )
        }
        val capturedAt = SystemClock.elapsedRealtime()
        val detection = appContext?.let { ctx ->
            detect(ctx, image, game)
        }
        val matchedAt = SystemClock.elapsedRealtime()
        val page = detection?.page ?: "other"
        val archiveTag = when {
            saveFrame -> "manual"
            page == "other" && saveUnknown -> "unknown"
            else -> null
        }
        val frame = screenCapture.lastFrameInfo
        val archived = archiveTag?.let { FrameArchive.save(appContext, image, it) }
        return Result(200, buildJsonObject {
            put("ok", true)
            put("page", page)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("capture_ms", capturedAt - started)
            put("match_ms", matchedAt - capturedAt)
            put("source", detection?.source ?: "assets")
            put("spec", detection?.spec ?: "assets")
            if (archived != null) put("frame_saved", archived.file.name)
            if (withFrame && frame != null) {
                putJsonObject("frame") {
                    put("width", frame.width)
                    put("height", frame.height)
                    put("format", frame.format)
                    put("row_stride", frame.rowStride)
                    put("pixel_stride", frame.pixelStride)
                    put("samples", buildJsonArray { frame.samples.forEach { add(JsonPrimitive(it)) } })
                }
            }
        })
    }

    suspend fun handleRead(
        screenCapture: ScreenCapture?,
        appContext: Context?,
        body: JsonObject,
    ): Result {
        val field = (body["field"] as? JsonPrimitive)?.content.takeUnless { it.isNullOrEmpty() }
            ?: return Result(200, buildJsonObject {
                put("ok", false)
                put("error", "missing_field")
            })
        val engine = (body["engine"] as? JsonPrimitive)?.content ?: "auto"
        if (engine !in setOf("template", "ocr", "auto")) return Result(400, buildJsonObject {
            put("ok", false)
            put("error", "invalid_engine")
        })
        val delayMs = ((body["delay_ms"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 5_000)
        val saveFrame = (body["save_frame"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (delayMs > 0) delay(delayMs.toLong())
        if (screenCapture == null) return Result(503, buildJsonObject {
            put("ok", false)
            put("error", "capture_not_wired")
        })

        val started = SystemClock.elapsedRealtime()
        val image = screenCapture.captureLocalFrame(fresh = saveFrame).getOrElse { cause ->
            return Result(200, buildJsonObject {
                put("ok", false)
                put("error", "capture_failed")
                put("detail", (cause.message ?: cause.toString()).take(200))
            })
        }
        var engineUsed = if (engine == "ocr") "ocr" else "template"
        val reading = appContext?.let { context ->
            val reader = DigestReader(context, File(context.filesDir, "localvision"))
            if (engine == "ocr") reader.readOcr(image, field) else {
                val template = reader.read(image, field)
                if (engine == "auto" && template.value == null) {
                    engineUsed = "ocr"
                    reader.readOcr(image, field)
                } else template
            }
        }
        val value = reading?.value
        val archived = if (saveFrame && value == null) {
            FrameArchive.save(appContext, image, "read")
        } else {
            null
        }
        return Result(200, buildJsonObject {
            put("ok", true)
            put("field", field)
            put("value", value?.let(::JsonPrimitive) ?: JsonNull)
            put("source", reading?.source ?: "no_context")
            put("engine_used", engineUsed)
            reading?.confidence?.let { put("confidence", it) }
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            if (archived != null) put("frame_saved", archived.file.name)
        })
    }

    private fun error(status: Int, code: String, detail: String? = null,
        captureMs: Long? = null, matchMs: Long? = null): Result = Result(
        status,
        buildJsonObject {
            put("ok", false)
            put("page", "other")
            put("error", code)
            if (detail != null) put("detail", detail)
            captureMs?.let { put("capture_ms", it) }
            matchMs?.let { put("match_ms", it) }
        },
    )
}
