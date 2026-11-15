package com.axiomlabs.hermesrelay.localvision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMaskTest {
    @Test fun colorMaskExcludesPixelsAndFullyMaskedCheckFails() {
        val image = RgbImage(2, 1, intArrayOf(0xff0000, 0x0000ff))
        val area = Rect(0, 0, 2, 1)
        val mean = ColorCheck.meanInArea(image, area, listOf(Rect(1, 0, 1, 1)))
        assertTrue(mean.contentEquals(intArrayOf(255, 0, 0)))
        assertFalse(ColorCheck.within(ColorCheck.meanInArea(image, area, listOf(area)), intArrayOf(0, 0, 0), 0))
    }

    @Test fun templateMaskMovesWithWindowAndDisablesDownscale() {
        val template = GrayImage(2, 1, byteArrayOf(10, 20))
        val hay = GrayImage(4, 1, byteArrayOf(0, 10, 99, 0))
        val match = TemplateMatcher(template).find(hay, minScore = 1.0,
            downscale = true, ignore = listOf(Rect(1, 0, 1, 1)))
        assertTrue(match.found)
        assertEquals(1, match.x)
    }

    @Test fun parserSupportsNeedAndIgnoreWithAllChecksFallback() {
        val check = """{"type":"color","area":[0,0,2,1],"expect":[255,0,0],"tol":0,"ignore":[[1,0,1,1]]}"""
        val miss = check.replace("[255,0,0]", "[0,255,0]")
        val image = RgbImage(2, 1, intArrayOf(0xff0000, 0x0000ff))
        for ((need, expected) in listOf(1 to "home", 0 to "other", 3 to "other")) {
            val spec = PageSpecParser.parse("""{"pages":{"home":{"need":$need,"checks":[$check,$miss]}}}""")
            val loader = object : TemplateLoader {
                override fun load(name: String): GrayImage? = null
            }
            assertEquals(expected, PageDetector(spec, loader).detect(image))
        }
    }
}
