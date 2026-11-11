package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import kotlin.system.measureNanoTime

internal object DigestDecoder {
    fun decode(cells: List<GrayImage>, templates: Map<Char, GrayImage>, threshold: Double): Int? {
        if (cells.isEmpty() || templates.isEmpty() || threshold !in 0.0..1.0) return null
        val decoded = buildString {
            for (cell in cells) {
                val match = templates.entries.mapNotNull { (character, template) ->
                    if (template.width != cell.width || template.height != cell.height) return@mapNotNull null
                    val result = TemplateMatcher(template).find(cell, minScore = 0.0)
                    character to result.score
                }.maxByOrNull { it.second } ?: return null
                if (match.second < threshold) return null
                append(match.first)
            }
        }
        val number = decoded.substringBefore('/')
        return number.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }
}

class DigestReader(context: Context, private val hotReloadDir: File? = null) {
    data class ReadResult(val field: String, val value: Int?, val source: String, val elapsedMs: Double)

    private data class FieldSpec(
        val name: String,
        val region: Rect,
        val charset: List<Char>,
        val cellWidth: Int,
        val cellHeight: Int,
        val threshold: Double,
    )

    private val appContext = context.applicationContext
    private val specs: Map<String, FieldSpec> = loadSpecs()

    /** 读不到（无该字段/无模板/置信不足）→ value = null，不抛异常。 */
    fun read(image: RgbImage, field: String): ReadResult {
        var value: Int? = null
        var source = "low_confidence"
        val elapsed = measureNanoTime {
            try {
                val spec = specs[field]
                if (spec == null) {
                    source = "unknown_field"
                    return@measureNanoTime
                }
                val templates = spec.charset.mapNotNull { char -> loadTemplate(field, char)?.let { char to it } }.toMap()
                if (templates.isEmpty()) {
                    source = "no_templates"
                    return@measureNanoTime
                }
                val cropped = crop(image, spec.region)
                val gray = Grayscale.fromRgb(cropped)
                if (gray.height < spec.cellHeight) return@measureNanoTime
                val cells = (0 until gray.width / spec.cellWidth).map { index ->
                    crop(gray, Rect(index * spec.cellWidth, 0, spec.cellWidth, spec.cellHeight))
                }
                value = DigestDecoder.decode(cells, templates, spec.threshold)
                if (value != null) source = "template"
            } catch (_: Exception) {
                value = null
            }
        }
        return ReadResult(field, value, source, elapsed / 1_000_000.0)
    }

    fun fields(): List<String> = specs.keys.toList()

    private fun loadSpecs(): Map<String, FieldSpec> {
        val assetJson = { appContext.assets.open("reads.json").bufferedReader().use { it.readText() } }
        val json = hotReloadDir?.resolve("reads.json")?.takeIf(File::isFile)?.let {
            runCatching { it.readText().also(::parseSpecs) }.getOrNull()
        } ?: runCatching(assetJson).getOrNull() ?: return emptyMap()
        return runCatching { parseSpecs(json).associateBy(FieldSpec::name) }.getOrDefault(emptyMap())
    }

    private fun parseSpecs(json: String): List<FieldSpec> {
        val fields = JSONObject(json).getJSONArray("fields")
        return (0 until fields.length()).map { index ->
            val item = fields.getJSONObject(index)
            val region = item.getJSONArray("region")
            val charset = item.getJSONArray("charset")
            require(item.getString("layout") == "left_to_right")
            FieldSpec(
                item.getString("name"),
                Rect(region.getInt(0), region.getInt(1), region.getInt(2), region.getInt(3)),
                (0 until charset.length()).map { charset.getString(it).single() },
                item.getInt("cell_width"),
                item.getInt("cell_height"),
                item.getDouble("threshold"),
            ).also { require(it.cellWidth > 0 && it.cellHeight > 0 && it.threshold in 0.0..1.0) }
        }
    }

    private fun loadTemplate(field: String, char: Char): GrayImage? {
        val relative = "digits/$field/$char.png"
        val bytes = hotReloadDir?.resolve(relative)?.takeIf(File::isFile)?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: runCatching { appContext.assets.open(relative).use { it.readBytes() } }.getOrNull()
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Grayscale.fromRgb(RgbImage(bitmap.width, bitmap.height, pixels))
    }

    private fun crop(image: RgbImage, area: Rect): RgbImage {
        require(area.w > 0 && area.h > 0 && area.x >= 0 && area.y >= 0)
        require(area.x + area.w <= image.width && area.y + area.h <= image.height)
        val pixels = IntArray(area.w * area.h)
        for (y in 0 until area.h) image.rgb.copyInto(pixels, y * area.w, (area.y + y) * image.width + area.x, (area.y + y) * image.width + area.x + area.w)
        return RgbImage(area.w, area.h, pixels)
    }

    private fun crop(image: GrayImage, area: Rect): GrayImage {
        val pixels = ByteArray(area.w * area.h)
        for (y in 0 until area.h) image.pixels.copyInto(pixels, y * area.w, (area.y + y) * image.width + area.x, (area.y + y) * image.width + area.x + area.w)
        return GrayImage(area.w, area.h, pixels)
    }
}
