package com.axiomlabs.hermesrelay.localvision

import java.nio.file.Path
import javax.imageio.ImageIO

/** JVM-only PNG decoder for fixtures and template assets. */
object PngRgbImageLoader {
    fun load(path: Path): RgbImage {
        val image = ImageIO.read(path.toFile())
            ?: throw IllegalArgumentException("Not a decodable image: $path")
        val rgb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, rgb, 0, image.width)
        for (index in rgb.indices) {
            rgb[index] = rgb[index] and 0x00ff_ffff
        }
        return RgbImage(image.width, image.height, rgb)
    }
}
