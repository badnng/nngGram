package com.badnng.moe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.badnng.moe.media.VideoViewerShortcut
import com.badnng.moe.media.videoViewerShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoViewerShortcutsTest {
    @Test fun inlineVideosNeverHandleViewerOnlyShortcuts() {
        for (key in listOf(Key.K, Key.J, Key.L, Key.Spacebar, Key.Escape)) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                assertNull(videoViewerShortcut(key, type, expanded = false))
            }
        }
    }

    @Test fun playTogglesOnlyWhenKOrSpaceIsReleased() {
        for (key in listOf(Key.K, Key.Spacebar)) {
            repeat(5) {
                assertEquals(VideoViewerShortcut.CONSUME, videoViewerShortcut(key, KeyEventType.KeyDown, expanded = true))
            }
            assertEquals(VideoViewerShortcut.TOGGLE_PLAY, videoViewerShortcut(key, KeyEventType.KeyUp, expanded = true))
        }
    }

    @Test fun holdingSeekKeysMovesInTenSecondStepsWithoutSeekingAgainOnRelease() {
        for ((key, action) in listOf(Key.J to VideoViewerShortcut.SEEK_BACK, Key.L to VideoViewerShortcut.SEEK_FORWARD)) {
            repeat(3) { assertEquals(action, videoViewerShortcut(key, KeyEventType.KeyDown, expanded = true)) }
            assertEquals(VideoViewerShortcut.CONSUME, videoViewerShortcut(key, KeyEventType.KeyUp, expanded = true))
        }
        assertEquals(-10.0, VideoViewerShortcut.SEEK_BACK.seekDeltaSeconds)
        assertEquals(10.0, VideoViewerShortcut.SEEK_FORWARD.seekDeltaSeconds)
    }

    @Test fun escapeClosesOnceOnRelease() {
        repeat(5) {
            assertEquals(VideoViewerShortcut.CONSUME, videoViewerShortcut(Key.Escape, KeyEventType.KeyDown, expanded = true))
        }
        assertEquals(VideoViewerShortcut.CLOSE, videoViewerShortcut(Key.Escape, KeyEventType.KeyUp, expanded = true))
    }

    @Test fun modifiedGesturesAreLeftToTheWindowAndTextControls() {
        for (key in listOf(Key.K, Key.J, Key.L, Key.Spacebar, Key.Escape)) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                assertNull(videoViewerShortcut(key, type, expanded = true, shift = true))
                assertNull(videoViewerShortcut(key, type, expanded = true, ctrl = true))
                assertNull(videoViewerShortcut(key, type, expanded = true, alt = true))
                assertNull(videoViewerShortcut(key, type, expanded = true, meta = true))
            }
        }
    }

    @Test fun unrelatedKeysRemainAvailableToOtherControls() {
        for (key in listOf(Key.A, Key.Enter, Key.DirectionLeft, Key.DirectionRight)) {
            assertNull(videoViewerShortcut(key, KeyEventType.KeyDown, expanded = true))
            assertNull(videoViewerShortcut(key, KeyEventType.KeyUp, expanded = true))
        }
    }
}
