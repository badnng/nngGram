package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import demo.shared.generated.resources.Res
import demo.shared.generated.resources.telegram_wallpaper_day
import demo.shared.generated.resources.telegram_wallpaper_night
import demo.shared.generated.resources.telegram_wallpaper_pattern
import org.jetbrains.compose.resources.imageResource
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Official, locally bundled Telegram Desktop artwork. Static drawing has no animation/frame loop. */
@Composable
internal fun ConversationWallpaper(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = LocalAppEffectiveDark.current ?: (MiuixTheme.colorScheme.surface.luminance() < 0.5f),
) {
    val selected = LocalLocalWallpaperController.current?.committed
    if (selected != null) {
        val display = remember(selected.frame) { selected.frame.retain() }
        DisposableEffect(display) { onDispose { display.close() } }
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(display, viewport, selected.blurred) {
            if (viewport.width > 0 && viewport.height > 0) {
                val scale = maxOf(viewport.width.toFloat() / display.width, viewport.height.toFloat() / display.height)
                display.prepare((display.width * scale).roundToInt().coerceAtLeast(1),
                    (display.height * scale).roundToInt().coerceAtLeast(1), selected.blurred)
            }
        }
        Canvas(modifier.fillMaxSize().onSizeChanged { viewport = it }) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val scale = maxOf(size.width / display.width, size.height / display.height)
            val width = display.width * scale
            val height = display.height * scale
            clipRect {
                display.draw(this, Rect((size.width - width) / 2f, (size.height - height) / 2f,
                    (size.width + width) / 2f, (size.height + height) / 2f), selected.blurred)
            }
        }
        return
    }
    val night = when (LocalSettingsPreferences.current.wallpaper) {
        SettingsWallpaper.AUTOMATIC -> darkTheme
        SettingsWallpaper.DAY -> false
        SettingsWallpaper.NIGHT -> true
    }
    val consumerProbe = LocalSettingsThemeConsumerProbe.current
    SideEffect { consumerProbe?.invoke("wallpaper", night) }
    val background = imageResource(if (night) Res.drawable.telegram_wallpaper_night else Res.drawable.telegram_wallpaper_day)
    val pattern = if (night) null else imageResource(Res.drawable.telegram_wallpaper_pattern)
    Canvas(modifier.fillMaxSize()) {
        val viewport = IntSize(size.width.roundToInt(), size.height.roundToInt())
        if (viewport.width <= 0 || viewport.height <= 0) return@Canvas
        clipRect {
            drawImage(background, dstSize = viewport, filterQuality = FilterQuality.Medium)
            if (pattern != null) {
                // Keep motifs at a fixed desktop size when the conversation/window grows.
                val tile = IntSize(720.dp.roundToPx().coerceAtLeast(1), 1480.dp.roundToPx().coerceAtLeast(1))
                var y = 0
                while (y < viewport.height) {
                    var x = 0
                    while (x < viewport.width) {
                        drawImage(pattern, dstOffset = IntOffset(x, y), dstSize = tile,
                            alpha = 0.5f, blendMode = BlendMode.Softlight, filterQuality = FilterQuality.Medium)
                        x += tile.width
                    }
                    y += tile.height
                }
            }
        }
    }
}


