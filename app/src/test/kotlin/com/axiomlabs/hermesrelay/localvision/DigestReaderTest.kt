package com.axiomlabs.hermesrelay.localvision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigestReaderTest {
    private fun image(vararg pixels: Int) = GrayImage(pixels.size, 1, ByteArray(pixels.size) { pixels[it].toByte() })

    private val templates = mapOf(
        '0' to image(0, 0, 0),
        '1' to image(255, 255, 255),
        '2' to image(0, 127, 255),
        '/' to image(255, 127, 0),
    )

    @Test fun `all cells matched returns number`() {
        assertEquals(21, DigestDecoder.decode(listOf(templates.getValue('2'), templates.getValue('1')), templates, 0.99))
    }

    @Test fun `one low confidence cell returns null`() {
        assertNull(DigestDecoder.decode(listOf(templates.getValue('2'), image(0, 255, 0)), templates, 0.90))
    }

    @Test fun `missing character template returns null`() {
        assertNull(DigestDecoder.decode(listOf(templates.getValue('2'), image(40, 180, 230)), templates, 0.99))
    }

    @Test fun `slash returns numerator`() {
        val cells = "2/10".map(templates::getValue)
        assertEquals(2, DigestDecoder.decode(cells, templates, 0.99))
    }

    @Test fun `five right aligned characters return ten`() {
        val strip = rightAlignedStrip("10/10", 15)

        assertEquals(10, DigestDecoder.decodeRightAligned(strip, templates, 0.99, 3))
    }

    @Test fun `four right aligned characters ignore blank leading cell`() {
        val rightAlignedTemplates = templates + ('7' to image(127, 255, 127))
        val strip = rightAlignedStrip("7/10", 15, rightAlignedTemplates)

        assertEquals(7, DigestDecoder.decodeRightAligned(strip, rightAlignedTemplates, 0.99, 3))
    }

    @Test fun `low confidence middle character returns null`() {
        val strip = rightAlignedStrip("10/10", 15).let { source ->
            source.copy(pixels = source.pixels.copyOf().also { pixels ->
                pixels[3] = 80
                pixels[4] = 80
                pixels[5] = 80
            })
        }

        assertNull(DigestDecoder.decodeRightAligned(strip, templates, 0.99, 3))
    }

    @Test fun `invalid step and empty templates return null`() {
        val strip = rightAlignedStrip("10/10", 15)

        assertNull(DigestDecoder.decodeRightAligned(strip, templates, 0.99, 0))
        assertNull(DigestDecoder.decodeRightAligned(strip, templates, 0.99, -1))
        assertNull(DigestDecoder.decodeRightAligned(strip, emptyMap(), 0.99, 3))
    }

    private fun rightAlignedStrip(
        text: String,
        width: Int,
        availableTemplates: Map<Char, GrayImage> = templates,
    ): GrayImage {
        val pixels = ByteArray(width) { 64 }
        var cursor = width
        for (character in text.reversed()) {
            val template = availableTemplates.getValue(character)
            template.pixels.copyInto(pixels, cursor - template.width)
            cursor -= 3
        }
        return GrayImage(width, 1, pixels)
    }
}
