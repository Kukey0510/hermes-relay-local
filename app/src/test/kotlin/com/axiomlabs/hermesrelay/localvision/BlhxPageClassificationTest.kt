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

    /**
     * 2026-11-14 真机素材矩阵（app/src/test/resources/frames/blhx，每页 3 帧）。
     *
     * sortie / battle / result 已补齐判据，期望与各自目录名一致。
     * 这个测试同时充当"负样本不误判"的守门：任何页被判成别的页都会列在失败信息里。
     */
    @Test
    fun v2FrameMatrixMatchesDirectoryLabels() {
        val root = repositoryRoot()
        val frameRoot = root.resolve("app/src/test/resources/frames/blhx")
        if (!Files.isDirectory(frameRoot)) return
        val assets = root.resolve("app/src/main/assets")
        val spec = PageSpecParser.parse(Files.readString(assets.resolve("pages.json")))
        val detector = PageDetector(spec, AssetTemplateLoader(assets))
        val expectations = linkedMapOf(
            "home" to "home",
            "list" to "list",
            "info" to "info",
            "formation" to "formation",
            "negative" to "other",
            "sortie" to "sortie",
            "battle" to "battle",
            "result" to "result"
        )
        val failures = mutableListOf<String>()
        var checked = 0
        for ((dir, expected) in expectations) {
            val directory = frameRoot.resolve(dir)
            if (!Files.isDirectory(directory)) continue
            val frames = Files.list(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension.equals("png", ignoreCase = true) }
                    .sorted()
                    .toList()
            }
            for (frame in frames) {
                checked += 1
                val got = detector.detect(PngRgbImageLoader.load(frame))
                if (got != expected) failures += "$dir/${frame.fileName}: wanted $expected got $got"
            }
        }
        assertEquals("checked $checked frames\n" + failures.joinToString("\n"), emptyList<String>(), failures)
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
