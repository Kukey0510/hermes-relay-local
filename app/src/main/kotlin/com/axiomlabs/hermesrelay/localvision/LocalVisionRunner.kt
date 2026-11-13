package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class LocalVisionResult(val page: String, val elapsedMs: Double, val source: String = "assets")

/** Android asset adapter for the platform-independent local page detector. */
class LocalVisionRunner(context: Context, private val hotReloadDir: File? = null) {
    private val appContext = context.applicationContext

    private data class DetectorState(
        val detector: PageDetector,
        val source: String,
        val usedTemplateFallback: AtomicBoolean,
    )

    private val detectorState: DetectorState? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val hotPages = hotReloadDir?.resolve("pages.json")
            val hotSpec = hotPages?.takeIf(File::isFile)?.let { file ->
                runCatching { PageSpecParser.parse(file.readText()) }.getOrNull()
            }
            val source = when {
                hotSpec != null -> "hot"
                hotReloadDir != null -> "assets_fallback"
                else -> "assets"
            }
            val spec = hotSpec ?: appContext.assets.open("pages.json").bufferedReader().use {
                PageSpecParser.parse(it.readText())
            }
            val usedTemplateFallback = AtomicBoolean(false)
            DetectorState(
                PageDetector(
                    spec,
                    object : TemplateLoader {
                        override fun load(name: String): GrayImage? =
                            loadTemplate(name, usedTemplateFallback)
                    },
                ),
                source,
                usedTemplateFallback,
            )
        }.getOrNull()
    }

    fun detect(image: RgbImage): LocalVisionResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val state = detectorState
        val page = runCatching { state?.detector?.detect(image) ?: "other" }.getOrDefault("other")
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val source = when {
            state == null -> if (hotReloadDir != null) "assets_fallback" else "assets"
            state.usedTemplateFallback.get() -> "assets_fallback"
            else -> state.source
        }
        return LocalVisionResult(page, elapsedMs, source)
    }

    private fun loadTemplate(name: String, usedFallback: AtomicBoolean): GrayImage? = runCatching {
        val hotTemplate = hotReloadDir?.resolve("templates")?.resolve(name)
        val bitmap = hotTemplate?.takeIf(File::isFile)?.let { file ->
            runCatching { file.inputStream().use(BitmapFactory::decodeStream) }.getOrNull()
        } ?: run {
            if (hotReloadDir != null) usedFallback.set(true)
            appContext.assets.open("templates/$name").use(BitmapFactory::decodeStream)
        }
            ?: return@runCatching null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Grayscale.fromRgb(RgbImage(bitmap.width, bitmap.height, pixels))
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}
