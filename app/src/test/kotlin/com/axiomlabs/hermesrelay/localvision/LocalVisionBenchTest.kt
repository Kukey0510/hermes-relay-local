package com.axiomlabs.hermesrelay.localvision

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.ceil
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisionBenchTest {
    @Test
    fun blhxFixturesMatchManifestWithinPerformanceBudget() {
        val root = repositoryRoot()
        val fixtureRoot = root.resolve("fixtures/blhx")
        val expected = readManifest(fixtureRoot.resolve("manifest.md"))
        val frames = Files.walk(fixtureRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension.equals("png", ignoreCase = true) }
                .sorted()
                .toList()
        }

        assertEquals("manifest entries", 12, expected.size)
        assertEquals("fixture PNGs", expected.keys, frames.mapTo(linkedSetOf()) { fixtureRoot.relativize(it).unixPath() })

        val assets = root.resolve("app/src/main/assets")
        val spec = PageSpecParser.parse(Files.readString(assets.resolve("pages.json")))
        val detector = PageDetector(spec, FileTemplateLoader(assets))
        val results = frames.map { frame ->
            val relative = fixtureRoot.relativize(frame).unixPath()
            val image = PngRgbImageLoader.load(frame)
            lateinit var actual: String
            val elapsedNanos = measureNanoTime { actual = detector.detect(image) }
            BenchResult(relative, expected.getValue(relative), actual, elapsedNanos / 1_000_000.0)
        }

        val timings = results.map(BenchResult::milliseconds).sorted()
        val median = (timings[5] + timings[6]) / 2.0
        val p99 = timings[ceil(timings.size * 0.99).toInt() - 1]
        val correct = results.count { it.expected == it.actual }
        val summary = summaryJson(results.size, correct, median, p99)

        val output = root.resolve("build/localvision-bench.json")
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            (results.map(BenchResult::toJson) + summary).joinToString("\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
        results.filter { it.expected != it.actual }.forEach { println(it.toJson()) }
        println(summary)

        assertEquals("all fixture pages must match", results.size, correct)
        assertTrue("median detect time must be < 60 ms, was $median", median < 60.0)
        assertTrue("p99 detect time must be < 300 ms, was $p99", p99 < 300.0)
    }

    private class FileTemplateLoader(private val assets: Path) : TemplateLoader {
        override fun load(name: String): GrayImage? {
            val path = sequenceOf(assets, assets.resolve("templates"))
                .map { directory -> directory.resolve(name).normalize() }
                .firstOrNull { candidate -> candidate.startsWith(assets) && Files.isRegularFile(candidate) }
                ?: return null
            return Grayscale.fromRgb(PngRgbImageLoader.load(path))
        }
    }

    private data class BenchResult(
        val frame: String,
        val expected: String,
        val actual: String,
        val milliseconds: Double,
    ) {
        fun toJson(): String =
            "{\"frame\":\"${frame.jsonEscape()}\",\"expect\":\"${expected.jsonEscape()}\"," +
                "\"actual\":\"${actual.jsonEscape()}\",\"ms\":${milliseconds.formatMillis()}}"
    }

    private fun readManifest(path: Path): LinkedHashMap<String, String> {
        val row = Regex("""^\|\s*([^|]+/)\s*\|\s*([^|]+\.png)\s*\|\s*([^|]+)\s*\|""")
        val entries = linkedMapOf<String, String>()
        Files.readAllLines(path).forEach { line ->
            row.find(line)?.destructured?.let { (directory, file, expected) ->
                entries[directory.trim() + file.trim()] = expected.trim()
            }
        }
        return entries
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate.parent != null) {
            if (candidate.resolve("fixtures/blhx/manifest.md").toFile().isFile) return candidate
            candidate = candidate.parent
        }
        error("Cannot find repository root from ${System.getProperty("user.dir")}")
    }

    private fun Path.unixPath(): String = joinToString("/") { it.name }
}

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
private fun Double.formatMillis(): String = java.lang.String.format(java.util.Locale.ROOT, "%.3f", this)
private fun summaryJson(total: Int, correct: Int, median: Double, p99: Double): String =
    "{\"total\":$total,\"correct\":$correct,\"median_ms\":${median.formatMillis()}," +
        "\"p99_ms\":${p99.formatMillis()}}"
