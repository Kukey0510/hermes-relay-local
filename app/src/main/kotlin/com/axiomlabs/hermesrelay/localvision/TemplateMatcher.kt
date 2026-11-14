package com.axiomlabs.hermesrelay.localvision

class TemplateMatcher(template: GrayImage) {
    val template: GrayImage = template

    fun find(
        hay: GrayImage,
        area: Rect? = null,
        minScore: Double = 0.85,
        downscale: Boolean = false,
    ): MatchResult {
        if (!minScore.isFinite() || minScore !in 0.0..1.0) return NOT_FOUND
        val scale = if (downscale) 2 else 1
        val searchImage = if (downscale) Grayscale.downsample2x(hay) else hay
        val needle = if (downscale) Grayscale.downsample2x(template) else template
        if (needle.width == 0 || needle.height == 0) return NOT_FOUND

        val requested = area ?: Rect(0, 0, hay.width, hay.height)
        if (requested.w <= 0 || requested.h <= 0 || requested.x < 0 || requested.y < 0 ||
            requested.x + requested.w > hay.width || requested.y + requested.h > hay.height
        ) return NOT_FOUND
        val left = (requested.x + scale - 1) / scale
        val top = (requested.y + scale - 1) / scale
        val right = (requested.x + requested.w) / scale - needle.width
        val bottom = (requested.y + requested.h) / scale - needle.height
        if (right < left || bottom < top) return NOT_FOUND

        val pixelCount = needle.width * needle.height
        val maximumDifference = 255L * pixelCount
        // Differences are raw 0..255 values; round up to preserve threshold ties.
        val allowedDifference = kotlin.math.ceil((1.0 - minScore) * maximumDifference).toLong()
        var bestScore = -1.0
        var bestX = -1
        var bestY = -1
        for (y in top..bottom) {
            position@ for (x in left..right) {
                var difference = 0L
                for (needleY in 0 until needle.height) {
                    var hayIndex = (y + needleY) * searchImage.width + x
                    var needleIndex = needleY * needle.width
                    for (needleX in 0 until needle.width) {
                        difference += kotlin.math.abs(
                            (searchImage.pixels[hayIndex++].toInt() and 0xff) -
                                (needle.pixels[needleIndex++].toInt() and 0xff),
                        )
                        if (difference > allowedDifference &&
                            1.0 - difference.toDouble() / maximumDifference < minScore
                        ) continue@position
                    }
                }
                val score = 1.0 - difference.toDouble() / maximumDifference
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        return if (bestScore >= minScore) {
            MatchResult(true, bestX * scale, bestY * scale, bestScore.coerceIn(0.0, 1.0))
        } else NOT_FOUND
    }

    private companion object {
        val NOT_FOUND = MatchResult(false, -1, -1, 0.0)
    }
}
