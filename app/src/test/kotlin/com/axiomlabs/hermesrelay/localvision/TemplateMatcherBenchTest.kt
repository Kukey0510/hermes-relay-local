package com.axiomlabs.hermesrelay.localvision

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherBenchTest {
    @Test
    fun templateMatcherMeetsM1ThroughM7() {
        val root = repositoryRoot()
        val info = gray(root, "fixtures/blhx/info/capB_info_231813_0.png")
        val list = gray(root, "fixtures/blhx/list/capA_list_231751_0.png")
        val templates = root.resolve("app/src/test/resources/localvision-templates")
        val results = mutableListOf<MatcherBenchResult>()

        val button = matcher(templates, "info-start-button.png") // crop: (1120, 820, 32, 16)
        lateinit var m1: MatchResult
        val m1Nanos = measureNanoTime {
            m1 = button.find(info, Rect(1090, 790, 100, 80), minScore = 1.0)
        }
        val m1Pass = m1.found && distance(m1, 1120, 820) < 5
        results += result("M-1", "found@(1120,820), error<5px", matchActual(m1), m1Nanos)

        val fullScreen = matcher(templates, "info-anchor-emblem.png") // crop: (1283, 489, 4, 4)
        lateinit var m2: MatchResult
        val m2Nanos = measureNanoTime { m2 = fullScreen.find(info, minScore = 1.0) }
        val m2Pass = m2.found && distance(m2, 1283, 489) < 8
        results += result("M-2", "found@(1283,489), error<8px", matchActual(m2), m2Nanos)

        val repeated = ArrayList<MatchResult>(50)
        val m3Nanos = measureNanoTime {
            repeat(50) {
                repeated += button.find(info, Rect(1090, 790, 100, 80), minScore = 1.0)
            }
        }
        val m3Pass = repeated.size == 50 && repeated.all { it == repeated.first() } && repeated.first() == m1
        results += result("M-3", "50/50 identical", "${repeated.count { it == repeated.first() }}/50 identical", m3Nanos)

        val absent = matcher(templates, "home-negative.png") // crop from another real frame: (700, 500, 8, 8)
        lateinit var m4: MatchResult
        val m4Nanos = measureNanoTime { m4 = absent.find(info, minScore = 1.0, downscale = true) }
        val m4Pass = !m4.found && m4.x == -1 && m4.y == -1
        results += result("M-4", "found=false,x=-1,y=-1", matchActual(m4), m4Nanos)

        lateinit var m5: MatchResult
        val m5Nanos = measureNanoTime { m5 = button.find(list, minScore = 0.95, downscale = true) }
        val m5Pass = !m5.found && m5.x == -1 && m5.y == -1
        results += result("M-5", "detail orange button absent from list", matchActual(m5), m5Nanos)

        val occludedInfo = occlude(info, Rect(1120, 820, 16, 16))
        lateinit var m6: MatchResult
        val m6Nanos = measureNanoTime {
            m6 = button.find(occludedInfo, Rect(1090, 790, 100, 80), minScore = 1.0)
        }
        val m6Pass = !m6.found || distance(m6, 1120, 820) < 5
        results += result("M-6", "not found or correct coordinate", matchActual(m6), m6Nanos)

        val multi = listOf(
            Triple("info-anchor-portrait.png", 468, 599),
            Triple("info-anchor-emblem.png", 1283, 489),
            Triple("info-anchor-title.png", 941, 260),
            Triple("info-anchor-button.png", 1166, 811),
        )
        // Warm the JVM before applying the end-to-end four-template budget.
        multi.forEach { (name, x, y) ->
            matcher(templates, name).find(info, Rect(x - 20, y - 20, 42, 42), minScore = 1.0)
        }
        lateinit var matches: List<Pair<Triple<String, Int, Int>, MatchResult>>
        val m7Nanos = measureNanoTime {
            matches = multi.map { expected ->
                val area = Rect(expected.second - 20, expected.third - 20, 42, 42)
                expected to matcher(templates, expected.first).find(info, area, minScore = 1.0)
            }
        }
        val m7Ms = m7Nanos / 1_000_000.0
        val m7Pass = matches.all { (expected, actual) ->
            actual.found && distance(actual, expected.second, expected.third) < 5
        } && m7Ms < 100.0
        results += result(
            "M-7",
            "4/4 correct,total<100ms",
            "${matches.count { (e, a) -> a.found && distance(a, e.second, e.third) < 5 }}/4 correct",
            m7Nanos,
        )

        writeReport(root, results, listOf(m1Pass, m2Pass, m3Pass, m4Pass, m5Pass, m6Pass, m7Pass))

        assertTrue("M-1 small-area coordinate: ${matchActual(m1)}", m1Pass)
        assertTrue("M-2 full-screen coordinate: ${matchActual(m2)}", m2Pass)
        assertTrue("M-3 repeated results must be identical", m3Pass)
        assertTrue("M-4 absent template must not return a coordinate: ${matchActual(m4)}", m4Pass)
        assertTrue("M-5 similar orange control must not match: ${matchActual(m5)}", m5Pass)
        assertTrue("M-6 an occluded target must not produce a wrong coordinate: ${matchActual(m6)}", m6Pass)
        assertTrue("M-7 four matches took ${formatMillis(m7Ms)} ms", m7Pass)
    }

    private fun matcher(directory: Path, name: String): TemplateMatcher =
        TemplateMatcher(Grayscale.fromRgb(PngRgbImageLoader.load(directory.resolve(name))))

    private fun gray(root: Path, relative: String): GrayImage =
        Grayscale.fromRgb(PngRgbImageLoader.load(root.resolve(relative)))

    private fun occlude(image: GrayImage, area: Rect): GrayImage {
        val pixels = image.pixels.copyOf()
        for (y in area.y until area.y + area.h) {
            for (x in area.x until area.x + area.w) pixels[y * image.width + x] = 0
        }
        return GrayImage(image.width, image.height, pixels)
    }

    private fun distance(result: MatchResult, x: Int, y: Int): Int =
        maxOf(abs(result.x - x), abs(result.y - y))

    private fun matchActual(result: MatchResult): String =
        "found=${result.found},x=${result.x},y=${result.y},score=${String.format(Locale.ROOT, "%.6f", result.score)}"

    private fun result(name: String, expected: String, actual: String, nanos: Long) =
        MatcherBenchResult(name, expected, actual, nanos / 1_000_000.0)

    private fun writeReport(root: Path, results: List<MatcherBenchResult>, passes: List<Boolean>) {
        val output = root.resolve("build/localvision-matcher-bench.json")
        Files.createDirectories(output.parent)
        val summary = "{\"total\":${results.size},\"passed\":${passes.count { it }},\"failed\":${passes.count { !it }}}"
        Files.writeString(
            output,
            (results.map(MatcherBenchResult::toJson) + summary).joinToString("\n", postfix = "\n"),
            StandardCharsets.UTF_8,
        )
        results.forEach { println(it.toJson()) }
        println(summary)
    }

    private fun repositoryRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate.parent != null) {
            if (Files.isRegularFile(candidate.resolve("fixtures/blhx/manifest.md"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot find repository root")
    }

    private data class MatcherBenchResult(
        val name: String,
        val expected: String,
        val actual: String,
        val milliseconds: Double,
    ) {
        fun toJson(): String =
            "{\"name\":\"${escape(name)}\",\"expected\":\"${escape(expected)}\"," +
                "\"actual\":\"${escape(actual)}\",\"ms\":${formatMillis(milliseconds)}}"
    }

    private companion object {
        fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        fun formatMillis(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    }
}
