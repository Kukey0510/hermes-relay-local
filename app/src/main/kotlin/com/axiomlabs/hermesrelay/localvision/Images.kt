package com.axiomlabs.hermesrelay.localvision

data class RgbImage(val width: Int, val height: Int, val rgb: IntArray) {
    init {
        require(width >= 0 && height >= 0 && rgb.size == width * height)
    }
}

data class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height)
    }
}

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int): Boolean = w > 0 && h > 0 &&
        px.toLong() >= x && py.toLong() >= y && px.toLong() < x.toLong() + w && py.toLong() < y.toLong() + h
}

data class MatchResult(val found: Boolean, val x: Int, val y: Int, val score: Double)

object Grayscale {
    fun fromRgb(img: RgbImage): GrayImage {
        val output = ByteArray(img.rgb.size)
        for (i in img.rgb.indices) {
            val color = img.rgb[i]
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            output[i] = ((299 * red + 587 * green + 114 * blue + 500) / 1000).toByte()
        }
        return GrayImage(img.width, img.height, output)
    }

    fun downsample2x(src: GrayImage): GrayImage {
        val width = src.width / 2
        val height = src.height / 2
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val top = y * 2 * src.width
            val bottom = top + src.width
            for (x in 0 until width) {
                val sourceX = x * 2
                val sum = (src.pixels[top + sourceX].toInt() and 0xff) +
                    (src.pixels[top + sourceX + 1].toInt() and 0xff) +
                    (src.pixels[bottom + sourceX].toInt() and 0xff) +
                    (src.pixels[bottom + sourceX + 1].toInt() and 0xff)
                output[y * width + x] = ((sum + 2) / 4).toByte()
            }
        }
        return GrayImage(width, height, output)
    }
}

object ColorCheck {
    fun meanInArea(img: RgbImage, area: Rect, ignore: List<Rect> = emptyList()): IntArray {
        require(area.w > 0 && area.h > 0)
        require(area.x >= 0 && area.y >= 0 && area.x + area.w <= img.width && area.y + area.h <= img.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in area.y until area.y + area.h) {
            var index = y * img.width + area.x
            repeat(area.w) {
                val x = index % img.width
                val color = img.rgb[index++]
                if (ignore.any { it.contains(x, y) }) return@repeat
                count++
                red += color ushr 16 and 0xff
                green += color ushr 8 and 0xff
                blue += color and 0xff
            }
        }
        if (count == 0L) return intArrayOf()
        return intArrayOf((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    fun within(mean: IntArray, expect: IntArray, tol: Int): Boolean {
        if (mean.size < 3 || expect.size < 3 || tol < 0) return false
        return (0..2).all { kotlin.math.abs(mean[it] - expect[it]) <= tol }
    }
}
