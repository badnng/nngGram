package com.badnng.moe

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import com.badnng.moe.chat.*
import com.badnng.moe.media.ownedMediaRangeReader
import kotlin.test.*

class MediaDownloadOwnershipRenderTest {
    @Test fun actualVisibilityRetainsItsOwnArtworkLeaseAcrossNativeProgressAndReleasesOnLeaving() {
        val active = mutableStateOf(true)
        val file = mutableStateOf(MediaFile(7, canDownload = true))
        val acquired = mutableListOf<MediaDownloadOwner>()
        val released = mutableListOf<MediaDownloadOwner>()
        val actions = ChatFeatureActions(acquireOwnedMedia = { owner, _ -> acquired += owner },
            releaseOwnedMedia = { owner, _ -> released += owner })
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 2, 3))
        ComposePixelScene(160, 100) { MediaArtworkVisibility(snapshot, actions, file.value, active.value) }.use { scene ->
            scene.settledFrame()
            assertEquals(1, acquired.size)
            assertEquals(MediaDownloadPurpose.ARTWORK, acquired.single().purpose)
            assertTrue(acquired.single().leaseId > 0)
            file.value = file.value.copy(downloading = true, downloadedSize = 123)
            scene.settledFrame()
            assertEquals(1, acquired.size)
            assertTrue(released.isEmpty())
            active.value = false
            scene.settledFrame()
            assertEquals(acquired, released)
        }
    }

    @Test fun spoilerAndFocusGatesDoNotAuthorizeAutomaticMediaUntilActuallyVisible() {
        val active = mutableStateOf(false)
        val focused = mutableStateOf(true)
        val calls = mutableListOf<Boolean>()
        val token = ChatToken(1, 2, 3)
        val actions = ChatFeatureActions(automaticMedia = { actual, message, value ->
            assertEquals(token, actual); assertEquals(10L, message); calls += value
        })
        ComposePixelScene(160, 100) {
            val nativeWindow = LocalWindowInfo.current
            val window = remember(nativeWindow) { object : WindowInfo by nativeWindow { override val isWindowFocused get() = focused.value } }
            CompositionLocalProvider(LocalWindowInfo provides window) { AutomaticMediaVisibility(actions, token, 10, active.value) }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(calls.none { it })
            active.value = true
            scene.settledFrame()
            assertEquals(true, calls.last())
            focused.value = false
            scene.settledFrame()
            assertEquals(false, calls.last())
        }
    }

    @Test fun everySourceInstanceHasSeparateProbeAndPlaybackOwnersAndOldCloseTargetsOnlyItsOwnLeases() {
        val reads = mutableListOf<MediaDownloadOwner>()
        val released = mutableListOf<MediaDownloadOwner>()
        val actions = ChatFeatureActions(readOwnedVideoPart = { owner, _, _, _, result -> reads += owner; result(ChatResult(ByteArray(1))) },
            releaseOwnedVideoPart = { owner, _ -> released += owner })
        val firstFactory = ownedMediaRangeReader(actions, ChatToken(1, 2, 3), 10, 7)
        val secondFactory = ownedMediaRangeReader(actions, ChatToken(1, 2, 3), 10, 7)
        val first = checkNotNull(firstFactory.fork)(false)
        val firstProbe = checkNotNull(firstFactory.fork)(true)
        val second = checkNotNull(secondFactory.fork)(false)
        val secondProbe = checkNotNull(secondFactory.fork)(true)
        first.read(0, 1) { }
        firstProbe.read(0, 1) { }
        second.read(0, 1) { }
        secondProbe.read(0, 1) { }
        assertEquals(4, reads.map { it.leaseId }.distinct().size)
        assertEquals(listOf(MediaDownloadPurpose.STREAM, MediaDownloadPurpose.PROBE, MediaDownloadPurpose.STREAM, MediaDownloadPurpose.PROBE), reads.map { it.purpose })
        first.release()
        firstProbe.release()
        assertEquals(reads.take(2), released)
        secondProbe.release()
        assertEquals(reads.last(), released.last())
    }
}
