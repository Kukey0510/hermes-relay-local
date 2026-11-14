package com.hermesandroid.relay.script.runtime

import com.axiomlabs.hermesrelay.localvision.RgbImage
import kotlin.math.floor

data class PowerStrip(val cardIndex: Int, val rowIndex: Int, val left: Int, val right: Int)
data class PowerComposite(val width: Int, val height: Int, val pixels: IntArray, val strips: List<PowerStrip>)

/** Pure pixel processing for the fixed 2460x1080 Azur Lane exercise opponent list. */
object PowerImagePreprocessor {
    private data class Area(val x: Int, val y: Int, val w: Int, val h: Int)
    private val cardSlots = listOf(
        Area(430, 150, 320, 410), Area(800, 150, 320, 410),
        Area(1170, 150, 320, 410), Area(1540, 150, 320, 410),
    )
    private val powerRelative = Area(13, 225, 305, 99)
    const val SCALE = 4
    const val GAP = 32

    fun compose(image: RgbImage): PowerComposite {
        require(image.width >= 2460 && image.height >= 1080) { "power OCR requires a 2460x1080 frame" }
        val rowHeight = powerRelative.h / 2
        val smallWidth = powerRelative.w * SCALE
        val smallHeight = rowHeight * SCALE
        val width = smallWidth * 8 + GAP * 7
        val output = IntArray(width * smallHeight) { WHITE }
        val strips = ArrayList<PowerStrip>(8)
        var strip = 0
        cardSlots.forEachIndexed { cardIndex, card ->
            repeat(2) { rowIndex ->
                val mask = yellowMask(image, card.x + powerRelative.x,
                    card.y + powerRelative.y + rowIndex * rowHeight, powerRelative.w, rowHeight)
                val scaled = bicubic(mask, powerRelative.w, rowHeight, SCALE)
                val left = strip * (smallWidth + GAP)
                for (y in 0 until smallHeight) {
                    scaled.copyInto(output, y * width + left, y * smallWidth, (y + 1) * smallWidth)
                }
                strips += PowerStrip(cardIndex, rowIndex, left, left + smallWidth)
                strip++
            }
        }
        return PowerComposite(width, smallHeight, output, strips)
    }

    fun blackPixelCounts(composite: PowerComposite): List<Int> = composite.strips.map { strip ->
        var count = 0
        for (y in 0 until composite.height) for (x in strip.left until strip.right) {
            if (composite.pixels[y * composite.width + x] == BLACK) count++
        }
        count
    }

    private fun yellowMask(image: RgbImage, x0: Int, y0: Int, width: Int, height: Int): IntArray {
        val result = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            result[y * width + x] = if (isYellow(image.rgb[(y0 + y) * image.width + x0 + x])) BLACK else WHITE
        }
        return result
    }

    /** OpenCV-compatible HSV threshold: H 18..42 (36..84 degrees), S >= 90, V >= 140. */
    internal fun isYellow(color: Int): Boolean {
        val r = color ushr 16 and 0xff; val g = color ushr 8 and 0xff; val b = color and 0xff
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
        if (max < 140 || delta * 255 < 90 * max || delta == 0) return false
        val hue6 = when (max) {
            r -> 60.0 * (g - b) / delta
            g -> 120.0 + 60.0 * (b - r) / delta
            else -> 240.0 + 60.0 * (r - g) / delta
        }.let { if (it < 0) it + 360 else it }
        return hue6 / 2.0 in 18.0..42.0
    }

    private fun bicubic(source: IntArray, width: Int, height: Int, scale: Int): IntArray {
        val outWidth = width * scale; val outHeight = height * scale
        return IntArray(outWidth * outHeight) { index ->
            val x = index % outWidth; val y = index / outWidth
            val sx = (x + 0.5) / scale - 0.5; val sy = (y + 0.5) / scale - 0.5
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt()
            var sum = 0.0; var weights = 0.0
            for (dy in -1..2) for (dx in -1..2) {
                val weight = cubic(sx - (ix + dx)) * cubic(sy - (iy + dy))
                val pixel = source[(iy + dy).coerceIn(0, height - 1) * width + (ix + dx).coerceIn(0, width - 1)]
                sum += (pixel and 0xff) * weight; weights += weight
            }
            val gray = (sum / weights).toInt().coerceIn(0, 255)
            -0x1000000 or (gray shl 16) or (gray shl 8) or gray
        }
    }

    private fun cubic(value: Double): Double {
        val x = kotlin.math.abs(value)
        return when { x <= 1 -> (1.5 * x - 2.5) * x * x + 1; x < 2 -> ((-0.5 * x + 2.5) * x - 4) * x + 2; else -> 0.0 }
    }

    private const val BLACK = -0x1000000
    private const val WHITE = -0x1
}
