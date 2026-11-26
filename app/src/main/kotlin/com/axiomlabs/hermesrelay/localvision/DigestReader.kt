package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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

    /** 右锚定变长切分：strip 已裁到文本区域，从右边界往左逐位匹配。
     *
     * 步进用**每个字符自己的 advance**（比例字体，实测 '0'=16、'1'=11、'/'=12、
     * 其它数字=12），不要用统一字距 —— 实测按固定 12 步进会在 "10/10" 上错位到读不出。
     */
    fun decodeRightAligned(
        strip: GrayImage,
        templates: Map<Char, GrayImage>,
        threshold: Double,
        cellStep: Int,
        advances: Map<Char, Int> = emptyMap(),
    ): Int? {
        if (templates.isEmpty() || cellStep <= 0 || strip.width <= 0) return null
        var cursor = strip.width
        val chars = mutableListOf<Char>()
        for (iteration in 0 until strip.width / cellStep + 2) {
            val match = templates.entries.mapNotNull { (character, template) ->
                val left = cursor - template.width
                if (left < 0 || template.height > strip.height) return@mapNotNull null
                val pixels = ByteArray(template.width * template.height)
                for (y in 0 until template.height) {
                    strip.pixels.copyInto(
                        pixels,
                        y * template.width,
                        y * strip.width + left,
                        y * strip.width + cursor,
                    )
                }
                val window = GrayImage(template.width, template.height, pixels)
                character to TemplateMatcher(template).find(window, minScore = 0.0).score
            }.maxByOrNull { it.second } ?: break
            if (match.second < threshold) break
            chars += match.first
            cursor -= advances[match.first] ?: cellStep
        }
        val decoded = chars.asReversed().joinToString("")
        val number = decoded.substringBefore('/')
        return number.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }
}

class DigestReader(context: Context, private val hotReloadDir: File? = null) {
    data class ReadResult(val field: String, val value: Int?, val source: String, val elapsedMs: Double, val confidence: Float? = null)

    private data class FieldSpec(
        val name: String,
        val region: Rect,
        val charset: List<Char>,
        val cellWidth: Int,
        val cellHeight: Int,
        val layout: String,
        val cellStep: Int,
        val advance: Map<Char, Int>,
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
                value = when (spec.layout) {
                    "left_to_right" -> {
                        val cells = (0 until gray.width / spec.cellWidth).map { index ->
                            crop(gray, Rect(index * spec.cellWidth, 0, spec.cellWidth, spec.cellHeight))
                        }
                        DigestDecoder.decode(cells, templates, spec.threshold)
                    }
                    "right_to_left" -> DigestDecoder.decodeRightAligned(
                        gray, templates, spec.threshold, spec.cellStep, spec.advance,
                    )
                    else -> null
                }
                if (value != null) source = "template"
            } catch (_: Exception) {
                value = null
            }
        }
        return ReadResult(field, value, source, elapsed / 1_000_000.0)
    }

    /** OCR uses only the configured field region, never the full frame. */
    suspend fun readOcr(image: RgbImage, field: String): ReadResult {
        val started = System.nanoTime()
        val spec = specs[field] ?: return ReadResult(field, null, "unknown_field", 0.0)
        var confidence: Float? = null
        val value = try {
            val region = crop(image, spec.region)
            val bitmap = Bitmap.createBitmap(region.rgb, region.width, region.height, Bitmap.Config.ARGB_8888)
            val recognizer = try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
            val text = suspendCancellableCoroutine<Text?> { continuation ->
                try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { task ->
                            // Keep the bitmap alive until ML Kit finishes, even if the request is cancelled.
                            recognizer.close()
                            bitmap.recycle()
                            if (continuation.isActive) continuation.resume(if (task.isSuccessful) task.result else null)
                        }
                } catch (error: Exception) {
                    recognizer.close()
                    bitmap.recycle()
                    throw error
                }
            }
            val decoded = text?.text?.let(OcrNumberDecoder::decode)
            if (decoded != null) {
                confidence = text?.textBlocks?.flatMap { it.lines }?.flatMap { it.elements }
                    ?.map { it.confidence }?.takeIf { it.isNotEmpty() && it.all { score -> score > 0f && score <= 1f } }
                    ?.minOrNull()
            }
            decoded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        return ReadResult(field, value, if (value != null) "ocr" else "low_confidence",
            (System.nanoTime() - started) / 1_000_000.0, confidence)
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
            val layout = if (item.has("layout")) item.getString("layout") else "left_to_right"
            require(layout == "left_to_right" || layout == "right_to_left")
            val cellWidth = item.getInt("cell_width")
            FieldSpec(
                item.getString("name"),
                Rect(region.getInt(0), region.getInt(1), region.getInt(2), region.getInt(3)),
                (0 until charset.length()).map { charset.getString(it).single() },
                cellWidth,
                item.getInt("cell_height"),
                layout,
                if (item.has("cell_step")) item.getInt("cell_step") else cellWidth,
                parseAdvance(item),
                item.getDouble("threshold"),
            ).also {
                require(it.cellWidth > 0 && it.cellHeight > 0 && it.cellStep > 0 && it.threshold in 0.0..1.0)
            }
        }
    }

    /** 可选的逐字符步进表（比例字体）。缺失或非法值一律忽略，退回 cell_step。 */
    private fun parseAdvance(item: JSONObject): Map<Char, Int> {
        val obj = item.optJSONObject("advance") ?: return emptyMap()
        return obj.keys().asSequence()
            .mapNotNull { key ->
                val char = key.singleOrNull() ?: return@mapNotNull null
                val step = obj.optInt(key, 0)
                if (step > 0) char to step else null
            }
            .toMap()
    }

    private fun loadTemplate(field: String, char: Char, game: String? = null): GrayImage? {
        // '/' 不能进文件名（会被当成路径分隔符），统一存成 slash.png。
        val fileName = if (char.isLetterOrDigit()) char.toString() else "slash"
        val paths = digitTemplatePaths(field, "$fileName.png", game)
        val bytes = hotReloadDir?.let { digitTemplateFile(it, field, "$fileName.png", game) }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: paths.firstNotNullOfOrNull { relative ->
                runCatching { appContext.assets.open(relative).use { it.readBytes() } }.getOrNull()
            } ?: return null
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

internal object OcrNumberDecoder {
    fun decode(text: String): Int? {
        val compact = text.trim()
        // Reject ambiguous text instead of joining unrelated numbers or guessing glyphs.
        if (!Regex("[0-9]+(?:\\s*/\\s*[0-9]+)?").matches(compact)) return null
        return compact.substringBefore('/').trim().toIntOrNull()
    }
}

internal fun digitTemplatePaths(field: String, name: String, game: String? = null): List<String> {
    val parts = field.split('/')
    require(parts.size in 1..2 && parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it }) {
        "invalid field"
    }
    val selectedGame = game ?: parts.takeIf { it.size == 2 }?.first()
    require(selectedGame == null || isValidLocalVisionGame(selectedGame)) { "invalid game" }
    val fieldName = parts.last()
    return listOfNotNull(selectedGame?.let { "digits/$it/$fieldName/$name" },
        "digits/$fieldName/$name").distinct()
}

internal fun digitTemplateFile(directory: File, field: String, name: String, game: String? = null): File? =
    digitTemplatePaths(field, name, game).map(directory::resolve).firstOrNull(File::isFile)
