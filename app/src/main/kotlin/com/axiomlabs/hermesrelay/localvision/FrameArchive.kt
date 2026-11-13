package com.axiomlabs.hermesrelay.localvision

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FrameArchive {
    const val DIR_NAME = "frames"

    private const val MAX_FILES = 12
    private const val MAX_BYTES = 20L * 1024L * 1024L
    private val lock = Any()

    data class Entry(val file: File, val tag: String, val savedAtMs: Long)

    /** 环形裁剪的决策结果，与文件系统无关，纯 JVM 可测。 */
    internal data class ArchiveCandidate(
        val name: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
    )

    /** 返回应当被删除的条目，按「最旧优先」顺序；调用方负责真正 delete。 */
    internal fun evictionsFor(
        entries: List<ArchiveCandidate>,
        maxFiles: Int = MAX_FILES,
        maxBytes: Long = MAX_BYTES,
    ): List<ArchiveCandidate> {
        val oldestFirst = entries.sortedWith(
            compareBy<ArchiveCandidate> { it.lastModifiedMs }.thenBy { it.name },
        )
        if (maxFiles <= 0) return oldestFirst

        var remainingFiles = oldestFirst.size
        var remainingBytes = oldestFirst.sumOf { it.sizeBytes }
        val evictions = mutableListOf<ArchiveCandidate>()
        for (candidate in oldestFirst) {
            if (remainingFiles <= maxFiles && remainingBytes <= maxBytes) break
            evictions += candidate
            remainingFiles -= 1
            remainingBytes -= candidate.sizeBytes
        }
        return evictions
    }

    fun save(context: Context?, image: RgbImage, tag: String): Entry? = synchronized(lock) {
        if (context == null) return@synchronized null
        var bitmap: Bitmap? = null
        try {
            val directory = File(context.filesDir, DIR_NAME)
            if (!directory.exists() && !directory.mkdirs()) return@synchronized null
            val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(directory, "${timestamp}_${safeTag}.png")
            bitmap = Bitmap.createBitmap(image.rgb, image.width, image.height, Bitmap.Config.ARGB_8888)
            val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) {
                file.delete()
                return@synchronized null
            }
            trim(directory)
            if (file.exists()) Entry(file, tag, file.lastModified()) else null
        } catch (_: Throwable) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    fun list(context: Context?): List<Entry> = synchronized(lock) {
        try {
            val directory = context?.let { File(it.filesDir, DIR_NAME) } ?: return@synchronized emptyList()
            archiveFiles(directory).map { file ->
                Entry(
                    file,
                    file.name.removeSuffix(".png").substringAfter('_').substringAfter('_').substringAfter('_'),
                    file.lastModified(),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun trim(directory: File) {
        val filesByName = archiveFiles(directory).associateBy { it.name }
        val candidates = filesByName.values.map { file ->
            ArchiveCandidate(file.name, file.lastModified(), file.length())
        }
        evictionsFor(candidates).forEach { candidate -> filesByName[candidate.name]?.delete() }
    }

    private fun archiveFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()
}
