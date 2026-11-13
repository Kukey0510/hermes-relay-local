package com.hermesandroid.relay.network.relay

import android.content.Context
import android.os.SystemClock
import com.axiomlabs.hermesrelay.localvision.DigestReader
import com.axiomlabs.hermesrelay.localvision.FrameArchive
import com.axiomlabs.hermesrelay.localvision.LocalVisionRunner
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

    suspend fun handle(
        screenCapture: ScreenCapture?,
        appContext: Context?,
        body: JsonObject,
    ): Result {
        if (screenCapture == null) return error(503, "capture_not_wired")

        val delayMs = ((body["delay_ms"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 5_000)
        val withFrame = (body["with_frame"] as? JsonPrimitive)?.booleanOrNull ?: true
        val saveFrame = (body["save_frame"] as? JsonPrimitive)?.booleanOrNull ?: false
        val saveUnknown = (body["save_unknown"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (delayMs > 0) delay(delayMs.toLong())

        val started = SystemClock.elapsedRealtime()
        val image = screenCapture.captureLocalFrame().getOrElse { cause ->
            return error(
                status = 200,
                code = "capture_failed",
                detail = (cause.message ?: cause.toString()).take(200),
            )
        }
        val detection = appContext?.let { ctx ->
            LocalVisionRunner(ctx, java.io.File(ctx.filesDir, "localvision")).detect(image)
        }
        val frame = screenCapture.lastFrameInfo
        val page = detection?.page ?: "other"
        val archiveTag = when {
            saveFrame -> "manual"
            page == "other" && saveUnknown -> "unknown"
            else -> null
        }
        val archived = archiveTag?.let { FrameArchive.save(appContext, image, it) }
        return Result(200, buildJsonObject {
            put("ok", true)
            put("page", page)
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            put("source", detection?.source ?: "assets")
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
        val delayMs = ((body["delay_ms"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 5_000)
        val saveFrame = (body["save_frame"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (delayMs > 0) delay(delayMs.toLong())
        if (screenCapture == null) return Result(503, buildJsonObject {
            put("ok", false)
            put("error", "capture_not_wired")
        })

        val started = SystemClock.elapsedRealtime()
        val image = screenCapture.captureLocalFrame().getOrElse { cause ->
            return Result(200, buildJsonObject {
                put("ok", false)
                put("error", "capture_failed")
                put("detail", (cause.message ?: cause.toString()).take(200))
            })
        }
        val reading = appContext?.let { context ->
            DigestReader(context, File(context.filesDir, "localvision")).read(image, field)
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
            put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            if (archived != null) put("frame_saved", archived.file.name)
        })
    }

    private fun error(status: Int, code: String, detail: String? = null): Result = Result(
        status,
        buildJsonObject {
            put("ok", false)
            put("page", "other")
            put("error", code)
            if (detail != null) put("detail", detail)
        },
    )
}
