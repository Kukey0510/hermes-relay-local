package com.axiomlabs.hermesrelay.localvision

data class PageSpec(val pages: Map<String, Page>)

data class Page(val checks: List<PageCheck>)

sealed interface PageCheck

data class ColorPageCheck(val area: Rect, val expect: IntArray, val tol: Int) : PageCheck {
    override fun equals(other: Any?): Boolean =
        other is ColorPageCheck && area == other.area && expect.contentEquals(other.expect) && tol == other.tol

    override fun hashCode(): Int = 31 * (31 * area.hashCode() + expect.contentHashCode()) + tol
}

data class TemplatePageCheck(val file: String, val area: Rect, val min: Double) : PageCheck

data class UnknownPageCheck(val type: String) : PageCheck

interface TemplateLoader { fun load(name: String): GrayImage? }

class PageDetector(
    private val spec: PageSpec,
    private val loader: TemplateLoader,
    private val templateCache: MutableMap<String, GrayImage> = mutableMapOf(),
) {
    fun detect(img: RgbImage): String = try {
        val gray by lazy { Grayscale.fromRgb(img) }
        spec.pages.entries.firstOrNull { (_, page) ->
            page.checks.all { check ->
                when (check) {
                    is ColorPageCheck -> ColorCheck.within(ColorCheck.meanInArea(img, check.area), check.expect, check.tol)
                    is TemplatePageCheck -> {
                        val template = templateCache[check.file] ?: loader.load(check.file)?.also {
                            templateCache[check.file] = it
                        }
                        template != null && TemplateMatcher(template).find(gray, check.area, check.min).found
                    }
                    is UnknownPageCheck -> false
                }
            }
        }?.key ?: "other"
    } catch (_: Exception) {
        "other"
    }
}

object PageSpecParser {
    fun parse(json: String): PageSpec {
        try {
            val root = JsonParser(json).parse() as? Map<*, *> ?: invalid()
            val pagesObject = root["pages"] as? Map<*, *> ?: invalid()
            val pages = linkedMapOf<String, Page>()
            for ((rawId, rawPage) in pagesObject) {
                val id = rawId as? String ?: invalid()
                val pageObject = rawPage as? Map<*, *> ?: invalid()
                val rawChecks = pageObject["checks"] as? List<*> ?: invalid()
                pages[id] = Page(rawChecks.map { parseCheck(it as? Map<*, *> ?: invalid()) })
            }
            return PageSpec(pages)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid pages JSON", error)
        }
    }

    private fun parseCheck(value: Map<*, *>): PageCheck {
        val type = value["type"] as? String ?: invalid()
        return when (type) {
            "color" -> ColorPageCheck(rect(value["area"]), ints(value["expect"], 3), number(value["tol"]).toInt())
            "template" -> TemplatePageCheck(
                value["file"] as? String ?: invalid(),
                rect(value["area"]),
                number(value["min"]).toDouble(),
            )
            else -> UnknownPageCheck(type)
        }
    }

    private fun rect(value: Any?): Rect {
        val values = ints(value, 4)
        return Rect(values[0], values[1], values[2], values[3])
    }

    private fun ints(value: Any?, size: Int): IntArray {
        val list = value as? List<*> ?: invalid()
        if (list.size != size) invalid()
        return IntArray(size) { number(list[it]).toInt() }
    }

    private fun number(value: Any?): Number = value as? Number ?: invalid()

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid pages JSON")
}

private class JsonParser(private val source: String) {
    private var position = 0

    fun parse(): Any? {
        val value = value()
        whitespace()
        if (position != source.length) fail()
        return value
    }

    private fun value(): Any? {
        whitespace()
        if (position >= source.length) fail()
        return when (source[position]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> numberValue()
        }
    }

    private fun objectValue(): Map<String, Any?> {
        position++
        val result = linkedMapOf<String, Any?>()
        whitespace()
        if (consume('}')) return result
        while (true) {
            whitespace()
            if (position >= source.length || source[position] != '"') fail()
            val key = stringValue()
            whitespace()
            if (!consume(':')) fail()
            result[key] = value()
            whitespace()
            if (consume('}')) return result
            if (!consume(',')) fail()
        }
    }

    private fun arrayValue(): List<Any?> {
        position++
        val result = mutableListOf<Any?>()
        whitespace()
        if (consume(']')) return result
        while (true) {
            result += value()
            whitespace()
            if (consume(']')) return result
            if (!consume(',')) fail()
        }
    }

    private fun stringValue(): String {
        position++
        val result = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> {
                    if (position >= source.length) fail()
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (position + 4 > source.length) fail()
                            result.append(source.substring(position, position + 4).toIntOrNull(16)?.toChar() ?: fail())
                            position += 4
                        }
                        else -> fail()
                    }
                }
                else -> {
                    if (char < ' ') fail()
                    result.append(char)
                }
            }
        }
        fail()
    }

    private fun numberValue(): Number {
        val start = position
        if (position < source.length && source[position] == '-') position++
        while (position < source.length && source[position].isDigit()) position++
        if (position < source.length && source[position] == '.') {
            position++
            while (position < source.length && source[position].isDigit()) position++
        }
        if (position < source.length && source[position] in "eE") {
            position++
            if (position < source.length && source[position] in "+-") position++
            while (position < source.length && source[position].isDigit()) position++
        }
        val token = source.substring(start, position)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail()
    }

    private fun <T> literal(text: String, result: T): T {
        if (!source.startsWith(text, position)) fail()
        position += text.length
        return result
    }

    private fun whitespace() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    private fun consume(char: Char): Boolean =
        if (position < source.length && source[position] == char) {
            position++
            true
        } else false

    private fun fail(): Nothing = throw IllegalArgumentException("Invalid JSON at offset $position")
}
