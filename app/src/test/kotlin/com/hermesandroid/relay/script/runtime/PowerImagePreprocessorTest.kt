package com.hermesandroid.relay.script.runtime

import com.axiomlabs.hermesrelay.localvision.RgbImage
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerImagePreprocessorTest {
    @Test fun threeDeviceFramesProduceEightNonEmptyPowerRows() {
        val frames = listOf("20261114-141826.png", "20261114-143406.png", "20261114-145257.png")
        frames.forEach { name ->
            val composite = PowerImagePreprocessor.compose(load(name))
            assertEquals(8, composite.strips.size)
            assertEquals(305 * 4 * 8 + PowerImagePreprocessor.GAP * 7, composite.width)
            assertEquals((99 / 2) * 4, composite.height)
            val areas = PowerImagePreprocessor.blackPixelCounts(composite)
            assertEquals("$name: $areas", 8, areas.size)
            areas.forEachIndexed { row, area ->
                assertTrue("$name row $row has implausible yellow-mask area $area", area in 1_000..80_000)
            }
        }
    }

    @Test fun hsvThresholdKeepsOnlySpecifiedYellowRange() {
        assertTrue(PowerImagePreprocessor.isYellow(0xffffcc00.toInt()))
        assertTrue(!PowerImagePreprocessor.isYellow(0xffffffff.toInt()))
        assertTrue(!PowerImagePreprocessor.isYellow(0xffff0000.toInt()))
    }

    private fun load(name: String): RgbImage {
        val resource = requireNotNull(javaClass.getResourceAsStream("/frames/blhx/list/$name"))
        val image: BufferedImage = resource.use(ImageIO::read)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return RgbImage(image.width, image.height, pixels)
    }
}
