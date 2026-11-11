package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock

data class LocalVisionResult(val page: String, val elapsedMs: Double)

/** Android asset adapter for the platform-independent local page detector. */
class LocalVisionRunner(context: Context) {
    private val appContext = context.applicationContext

    private val detector: PageDetector? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            val json = appContext.assets.open("pages.json").bufferedReader().use { it.readText() }
            PageDetector(
                PageSpecParser.parse(json),
                object : TemplateLoader {
                    override fun load(name: String): GrayImage? = loadTemplate(name)
                },
            )
        }.getOrNull()
    }

    fun detect(image: RgbImage): LocalVisionResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val page = runCatching { detector?.detect(image) ?: "other" }.getOrDefault("other")
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        return LocalVisionResult(page, elapsedMs)
    }

    private fun loadTemplate(name: String): GrayImage? = runCatching {
        val bitmap = appContext.assets.open("templates/$name").use(BitmapFactory::decodeStream)
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
