package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class ComposerVoiceGestureRenderTest {
    @Test
    fun aPointerPressReleasesToSendSlidesToCancelAndLocksWithoutSendingOnRelease() {
        var presses = 0
        var locks = 0
        var fallbackClicks = 0
        val releases = mutableListOf<Boolean>()
        var raised by mutableStateOf(false)
        ComposePixelScene(500, 500) {
            MiuixTheme(colors = remember { lightColorScheme() }) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (raised) 44.dp else 24.dp)
                        .then(composerVoiceGesture(true, { Rect(16f, 412f, 484f, 476f) },
                            { presses++; raised = true }, { releases += it; raised = false }, { locks++ }))
                        .size(32.dp).clickable { fallbackClicks++ })
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val x = 460f
            val y = 460f
            scene.pressAt(x, y)
            scene.settledFrame(2)
            assertEquals(1, presses)
            scene.releaseAt(x, y)
            scene.settledFrame(2)
            assertEquals(listOf(false), releases)
            assertEquals(0, locks, "Raising the recording bar must not count as an upward pointer drag.")
            assertEquals(0, fallbackClicks, "A consumed pointer recording must not also trigger keyboard/click activation.")
            scene.pressAt(x, y)
            scene.dragTo(x - 100, y)
            scene.releaseAt(x - 100, y)
            scene.settledFrame(2)
            assertEquals(listOf(false, true), releases)
            scene.pressAt(x, y)
            scene.dragTo(x, y - 60)
            scene.settledFrame(2)
            assertEquals(1, locks)
            scene.releaseAt(x, y - 60)
            scene.settledFrame(2)
            assertEquals(3, presses)
            assertEquals(listOf(false, true), releases, "A locked recording must wait for an explicit stop/send control.")
        }
    }
}
