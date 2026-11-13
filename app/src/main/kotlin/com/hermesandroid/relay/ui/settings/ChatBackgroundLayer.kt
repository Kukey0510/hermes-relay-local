package com.hermesandroid.relay.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.data.ChatBackgroundRepository
import com.hermesandroid.relay.data.ChatBackgroundSettings
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatBackgroundLayer(settings: ChatBackgroundSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // Alpha is deliberately excluded: moving the slider never decodes the image again.
    var image by remember(settings.mode, settings.presetId, settings.customPath) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(settings.mode, settings.presetId, settings.customPath) {
        image = withContext(Dispatchers.IO) {
            try {
                when (settings.mode) {
                    "preset" -> if (settings.presetId in ChatBackgroundRepository.PRESETS) {
                        context.assets.open("chat-backgrounds/${settings.presetId}.jpg").use {
                            BitmapFactory.decodeStream(it)?.asImageBitmap()
                        }
                    } else null
                    "custom" -> BitmapFactory.decodeFile(settings.customPath)?.asImageBitmap()
                    else -> null
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
    val bitmap = image
    val themeColor = if (LocalBrand.current.isDark) Color.Black else Color.White
    Box(modifier.graphicsLayer().background(RelayRefresh.Background)) {
        if (settings.mode == "none" || bitmap == null) {
            Box(Modifier.fillMaxSize().relayGridTexture(alpha = 0.14f))
        } else {
            Box(Modifier.fillMaxSize().background(themeColor))
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = settings.alpha,
                modifier = Modifier.fillMaxSize(),
            )
            // A theme-colored veil keeps foreground text readable over any photo.
            // 0.25 at full opacity still lets the picture read as a picture; the
            // floor drops as the user fades the background out. Anything heavier
            // (the earlier 0.70 floor) left the photo barely visible at all.
            val scrimAlpha = (0.72f - 0.47f * settings.alpha).coerceIn(0.25f, 0.72f)
            Box(Modifier.fillMaxSize().background(themeColor.copy(alpha = scrimAlpha)))
        }
    }
}
