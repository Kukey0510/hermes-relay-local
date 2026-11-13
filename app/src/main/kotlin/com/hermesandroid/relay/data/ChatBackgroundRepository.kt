package com.hermesandroid.relay.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.chatBackgroundStore by preferencesDataStore(name = "chat_background")

data class ChatBackgroundSettings(
    val mode: String = "none",
    val presetId: String = "",
    val customPath: String = "",
    val alpha: Float = 1f,
)

class ChatBackgroundRepository(context: Context) {
    private val context = context.applicationContext

    val settings = this.context.chatBackgroundStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        ChatBackgroundSettings(
            mode = prefs[MODE]?.takeIf { it in setOf("none", "preset", "custom") } ?: "none",
            presetId = prefs[PRESET_ID].orEmpty(),
            customPath = prefs[CUSTOM_PATH].orEmpty(),
            alpha = (prefs[ALPHA] ?: 1f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f },
        )
    }

    suspend fun select(mode: String, presetId: String = "", customPath: String = "") {
        require(mode in setOf("none", "preset", "custom"))
        context.chatBackgroundStore.edit {
            it[MODE] = mode
            it[PRESET_ID] = presetId
            it[CUSTOM_PATH] = customPath
        }
    }

    suspend fun setAlpha(alpha: Float) {
        require(alpha.isFinite())
        context.chatBackgroundStore.edit { it[ALPHA] = alpha.coerceIn(0f, 1f) }
    }

    /** Copy while the picker grant is alive; never persist a content URI. */
    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 4320) inSampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode image")
        var bitmap = decoded
        val directory = File(context.filesDir, "chat_background")
        var destination: File? = null
        try {
            val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                postRotate((exif?.rotationDegrees ?: 0).toFloat())
            }
            val scale = minOf(1f, 2160f / maxOf(decoded.width, decoded.height))
            matrix.postScale(scale, scale)
            bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (bitmap !== decoded) decoded.recycle()
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create image directory")
            val file = File.createTempFile("background-", ".jpg", directory)
            destination = file
            file.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) throw IOException("Cannot encode image")
            }
            file.absolutePath
        } catch (error: Throwable) {
            destination?.delete()
            throw error
        } finally {
            bitmap.recycle()
            if (bitmap !== decoded && !decoded.isRecycled) decoded.recycle()
        }
    }

    companion object {
        val PRESETS = listOf("mist", "dusk", "sand")
        private val MODE = stringPreferencesKey("mode")
        private val PRESET_ID = stringPreferencesKey("preset_id")
        private val CUSTOM_PATH = stringPreferencesKey("custom_path")
        private val ALPHA = floatPreferencesKey("alpha")
    }
}
