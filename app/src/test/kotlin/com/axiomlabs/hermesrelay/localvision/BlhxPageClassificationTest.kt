package com.axiomlabs.hermesrelay.localvision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertEquals
import org.junit.Test

class BlhxPageClassificationTest {
    @Test
    fun negativeFixturesAreAllOther() {
        assertAllFramesDetectAs("fixtures/blhx-negatives", "other")
    }

    @Test
    fun listExtraFixturesAreAllList() {
        assertAllFramesDetectAs("fixtures/blhx-list-extra", "list")
    }

    private fun assertAllFramesDetectAs(relativeDirectory: String, expected: String) {
        val root = repositoryRoot()
        val directory = root.resolve(relativeDirectory)
        val frames = Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("png", ignoreCase = true) }
                .sorted()
                .toList()
        }
        val assets = root.resolve("app/src/main/assets")
        val spec = PageSpecParser.parse(Files.readString(assets.resolve("pages.json")))
        val detector = PageDetector(spec, AssetTemplateLoader(assets))
        val matching = frames.count { detector.detect(PngRgbImageLoader.load(it)) == expected }

        assertEquals("all ${frames.size} PNGs in $relativeDirectory must detect as $expected", frames.size, matching)
    }

    private class AssetTemplateLoader(private val assets: Path) : TemplateLoader {
        override fun load(name: String): GrayImage? {
            val path = assets.resolve("templates").resolve(name).normalize()
            if (!path.startsWith(assets) || !Files.isRegularFile(path)) return null
            return Grayscale.fromRgb(PngRgbImageLoader.load(path))
        }
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate.parent != null) {
            if (Files.isRegularFile(candidate.resolve("fixtures/blhx/manifest.md"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot find repository root from ${System.getProperty("user.dir")}")
    }
}
