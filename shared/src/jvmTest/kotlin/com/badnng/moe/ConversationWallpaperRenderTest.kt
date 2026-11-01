package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationWallpaperRenderTest {
    @Test fun officialLocalAssetsPaintLightAndDarkBackgroundsInsideTheirViewport() {
        for (dark in listOf(false, true)) {
            ComposePixelScene(140, 120) {
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    ConversationWallpaper(Modifier.offset(20.dp, 20.dp).size(100.dp, 80.dp), darkTheme = dark)
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                for ((x, y) in listOf(10 to 60, 130 to 60, 70 to 10, 70 to 110)) {
                    assertEquals(Color.Magenta.toArgb(), frame[x, y], "The wallpaper must not paint outside the conversation.")
                }
                val center = frame[70, 60]
                val channels = listOf(16, 8, 0).map { shift -> center ushr shift and 255 }
                if (dark) assertTrue(channels.all { it < 50 }, "Night must use the official dark asset.")
                else assertTrue(channels.sum() > 300 && channels[1] > channels[2],
                    "Day must use the official yellow-green wallpaper rather than a white surface.")
            }
        }
    }
}
