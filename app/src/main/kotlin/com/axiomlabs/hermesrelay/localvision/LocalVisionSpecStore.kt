package com.axiomlabs.hermesrelay.localvision

import android.util.Log
import java.io.File

internal fun isValidLocalVisionGame(game: String): Boolean =
    game.matches(Regex("[A-Za-z0-9_-]+"))

internal fun localVisionPageFiles(directory: File, game: String?): List<File> {
    require(game == null || isValidLocalVisionGame(game)) { "invalid game" }
    return listOfNotNull(game?.let { directory.resolve("pages/$it.json") }, directory.resolve("pages.json"))
}

/** In-memory last-good specs; owned by the routes, never shared across app processes. */
class LocalVisionSpecStore(
    private val warn: (String) -> Unit = { Log.w("LocalVision", it) },
) {
    internal data class Selection(val pages: PageSpec, val spec: String, val hot: Boolean)

    private val lastGood = mutableMapOf<File, PageSpec>()

    @Synchronized
    internal fun load(directory: File?, game: String?, readAssets: () -> String): Selection {
        val files = directory?.let { localVisionPageFiles(it, game) }.orEmpty()
        for (file in files) {
            // Deletion follows the fallback chain even if this file has a saved spec.
            if (!file.exists()) continue
            try {
                val pages = PageSpecParser.parse(file.readText())
                lastGood[file] = pages
                val label = if (file == files.last()) "default" else "pages_${file.nameWithoutExtension}"
                return Selection(pages, label, hot = true)
            } catch (error: Exception) {
                val previous = lastGood[file]
                val summary = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    .replace('\n', ' ').replace('\r', ' ').take(200)
                warn("${file.path}: $summary; ${if (previous != null) "using stale spec" else "trying fallback"}")
                if (previous != null) return Selection(previous, "stale", hot = true)
            }
        }
        return Selection(PageSpecParser.parse(readAssets()), "assets", hot = false)
    }
}
