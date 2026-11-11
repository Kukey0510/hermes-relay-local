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
        val files = archiveFiles(directory).toMutableList()
        var totalBytes = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size > MAX_FILES || totalBytes > MAX_BYTES)) {
            val oldest = files.removeAt(0)
            val length = oldest.length()
            if (oldest.delete()) totalBytes -= length
        }
    }

    private fun archiveFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()
}
