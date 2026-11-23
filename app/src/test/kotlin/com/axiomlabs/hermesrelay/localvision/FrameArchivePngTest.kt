package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameArchivePngTest {
    @Test
    fun savesRgbPixelsWithoutAlphaAsOpaquePng() {
        val context: Context = RuntimeEnvironment.getApplication()
        val pixels = intArrayOf(0x00FF0000, 0x0000FF00, 0x000000FF, 0x00FFFFFF)
        val entry = FrameArchive.save(context, RgbImage(2, 2, pixels), "opaque-test")
        assertNotNull(entry)
        val file = requireNotNull(entry).file
        try {
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                assertEquals(2, decoded.width)
                assertEquals(2, decoded.height)
                for (index in pixels.indices) {
                    val pixel = decoded.getPixel(index % 2, index / 2)
                    assertEquals(255, Color.alpha(pixel))
                    assertEquals(pixels[index], pixel and 0x00FFFFFF)
                }
            } finally {
                decoded.recycle()
            }
        } finally {
            file.delete()
        }
    }
}
