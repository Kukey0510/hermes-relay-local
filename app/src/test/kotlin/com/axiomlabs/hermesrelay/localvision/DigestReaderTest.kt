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
}
