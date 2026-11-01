package com.badnng.moe.media

internal data class VideoPlaybackPreferences(val volume: Float, val playing: Boolean)

/** The viewer's sound settings must never overwrite the chat preview's settings. */
internal class VideoPresentationState {
    private var inlinePreferences: VideoPlaybackPreferences? = null
    private var lastAudibleViewerVolume = 1f
    val inViewer: Boolean get() = inlinePreferences != null

    fun enterViewer(current: VideoPlaybackPreferences, active: Boolean = true): VideoPlaybackPreferences {
        if (inlinePreferences != null) return current
        inlinePreferences = current
        return VideoPlaybackPreferences(lastAudibleViewerVolume, playing = active)
    }

    fun leaveViewer(current: VideoPlaybackPreferences, active: Boolean = true): VideoPlaybackPreferences {
        val previous = inlinePreferences ?: return current
        if (current.volume.isFinite() && current.volume > 0f) {
            lastAudibleViewerVolume = current.volume.coerceAtMost(1f)
        }
        inlinePreferences = null
        return previous.copy(playing = previous.playing && active)
    }
}
