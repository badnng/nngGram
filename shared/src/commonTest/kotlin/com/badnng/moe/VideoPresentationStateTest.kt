package com.badnng.moe

import com.badnng.moe.media.VideoPlaybackPreferences
import com.badnng.moe.media.VideoPresentationState
import kotlin.test.*

class VideoPresentationStateTest {
    @Test fun leavingTheViewerWhileInactiveRestoresMuteWithoutRestartingPlayback() {
        val state = VideoPresentationState()
        state.enterViewer(VideoPlaybackPreferences(0f, true))
        assertEquals(VideoPlaybackPreferences(0f, false),
            state.leaveViewer(VideoPlaybackPreferences(0.8f, false), active = false))
    }

    @Test fun openingAMutedPreviewStartsTheViewerWithSoundAndRestoresMuteOnReturn() {
        val state = VideoPresentationState()
        val preview = VideoPlaybackPreferences(0f, true)
        assertEquals(VideoPlaybackPreferences(1f, true), state.enterViewer(preview))
        assertEquals(preview, state.leaveViewer(VideoPlaybackPreferences(0.8f, true)))
    }

    @Test fun openingAPausedPreviewPlaysExplicitlyAndReturningRestoresPause() {
        val state = VideoPresentationState()
        val preview = VideoPlaybackPreferences(0f, false)
        assertEquals(VideoPlaybackPreferences(1f, true), state.enterViewer(preview))
        assertEquals(preview, state.leaveViewer(VideoPlaybackPreferences(1f, true)))
    }

    @Test fun changingTheViewerDoesNotOverwriteTheInlineVolumeOrPlaybackIntent() {
        val state = VideoPresentationState()
        val preview = VideoPlaybackPreferences(0.35f, true)
        state.enterViewer(preview)
        assertEquals(preview, state.leaveViewer(VideoPlaybackPreferences(0f, false)))
        assertEquals(VideoPlaybackPreferences(1f, true), state.enterViewer(preview))
    }

    @Test fun viewerRemembersItsPositiveVolumeIndependentlyOfTheMutedPreview() {
        val state = VideoPresentationState()
        val preview = VideoPlaybackPreferences(0f, true)
        state.enterViewer(preview)
        state.leaveViewer(VideoPlaybackPreferences(0.4f, true))
        assertEquals(VideoPlaybackPreferences(0.4f, true), state.enterViewer(preview))
        state.leaveViewer(VideoPlaybackPreferences(0f, true))
        assertEquals(VideoPlaybackPreferences(0.4f, true), state.enterViewer(preview))
    }

    @Test fun repeatedCompositionDoesNotReplaceTheSavedInlineState() {
        val state = VideoPresentationState()
        val preview = VideoPlaybackPreferences(0f, false)
        state.enterViewer(preview)
        val viewer = VideoPlaybackPreferences(0.6f, true)
        assertEquals(viewer, state.enterViewer(viewer))
        assertEquals(preview, state.leaveViewer(viewer))
        assertEquals(preview, state.leaveViewer(preview))
    }
}
