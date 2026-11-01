package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Synthetic authority, scheduler, codec and process only; no account or actual UI. */
class ProfileStickerAvatarArtworkOwnershipTest {
    @Test fun frozenDisplayRetainsOnlyThePreparedFrameAcrossSourceLeaseAndClipClosure() {
        val codec = FakeCodec()
        val f = Fixture(codec)
        try {
            val (source, clip) = f.openPreview()
            var artwork: ProfileStickerAvatarArtwork? = null
            val frame = clip.frame(0, { true }) { artwork = it.value }
            await { f.ui.size() > 0 }; f.ui.drain()
            val borrowed = assertNotNull(artwork)
            val image = assertNotNull(borrowed.image)
            val first = assertNotNull(borrowed.freezeDisplay())
            assertNull(borrowed.freezeDisplay(), "At most one holder is live for a displayed frame")
            assertSame(image, first.image)
            first.close(); first.close()
            val frozen = assertNotNull(borrowed.freezeDisplay(), "An open source may retry after a holder closes")
            frame.close(); clip.close(); source.close()
            assertNull(borrowed.image)
            assertSame(image, frozen.image, "The independent display reference survives source and clip closure")
            assertEquals(0, codec.opened.disposed.get(), "Its prepared frame and resource charge are still owned")
            assertNull(borrowed.freezeDisplay(), "A revoked source cannot issue new display holders")
            frozen.close(); frozen.close()
            assertNull(frozen.image)
            assertEquals(1, codec.opened.disposed.get(), "The final reference releases the frame exactly once")
        } finally { f.close() }
    }

    @Test fun savedDefaultReaderReportsUnsupportedWithInvalidWitness() {
        val reader = object : ProfileStickerAvatarEncodedReader {
            override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) = AutoCloseable {}
            override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) = AutoCloseable {}
        }
        var error: SettingsError? = null
        val request = SettingsSavedProfileStickerAvatarRequest.Current(SettingsProfileExtrasToken(1, 7, 9), 3, 77)
        val lease = reader.savedPreview(request, { true }) { error = it.error }
        assertEquals(SettingsError.UNSUPPORTED, error); assertFalse(lease.requestCurrent()); lease.close()

        val provider = object : ProfileStickerAvatarArtworkProvider {
            override val generation = 1L
            override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                candidate: ProfileStickerAvatarCandidateTicket, physicalSizePx: Int, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease =
                object : ProfileStickerAvatarArtworkLease { override fun close() {} }
            override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                selection: ProfileStickerAvatarSelectionTicket, physicalSizePx: Int, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarArtworkLease =
                object : ProfileStickerAvatarArtworkLease { override fun close() {} }
            override fun close() {}
        }
        error = null
        val fallback = provider.savedPreview(request, 32, { true }) { error = it.error }
        assertEquals(SettingsError.UNSUPPORTED, error); assertFalse(fallback.requestCurrent()); fallback.close()
    }

    @Test fun savedPendingReadClosesOnProviderRetirementAndLateBytesCannotPublish() {
        val f = Fixture(FakeCodec())
        try {
            var delivered = 0
            val request = SettingsSavedProfileStickerAvatarRequest.History(SettingsProfileExtrasToken(1, 7, 9), 4, 88)
            val owner = f.provider.savedPreview(request, 32, { true }) { delivered++ }
            assertTrue(owner.requestCurrent()); f.provider.close()
            assertTrue(f.reader.savedClosed.get()); assertFalse(owner.requestCurrent())
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.FULL)); f.ui.drain()
            assertEquals(0, delivered); assertEquals(0L, f.encodedBudget.used())
            owner.close()
        } finally { f.close() }
    }

    @Test fun savedCallbackBeforeLeaseReturnCannotEscapeConcurrentProviderClose() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val f = Fixture(FakeCodec())
        val result = AtomicReference<ProfileStickerAvatarSavedPreviewLease?>(); val delivered = AtomicInteger()
        try {
            f.reader.savedInline = f.encoded(ProfileStickerAvatarMediaRole.FULL)
            f.reader.beforeSavedReturn = { entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) }
            val worker = Thread {
                result.set(f.provider.savedPreview(SettingsSavedProfileStickerAvatarRequest.Current(
                    SettingsProfileExtrasToken(1, 7, 9), 3, 77), 32, { true }) { delivered.incrementAndGet() })
            }
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(f.encodedBudget.used() > 0); f.provider.close(); finish.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertTrue(f.reader.savedClosed.get()); assertFalse(assertNotNull(result.get()).requestCurrent())
            await { f.encodedBudget.used() == 0L }; f.ui.drain(); assertEquals(0, delivered.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun savedDemandRevokedBeforeReaderReturnsRejectsBufferedBytesWithoutEarlyPhysicalClose() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val f = Fixture(FakeCodec())
        val result = AtomicReference<ProfileStickerAvatarSavedPreviewLease?>(); val delivered = AtomicInteger()
        val demand = SettingsSavedProfileStickerAvatarDemand()
        try {
            f.reader.beforeSavedReturn = { entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)) }
            val worker = Thread {
                result.set(f.provider.savedPreviewOwned(SettingsSavedProfileStickerAvatarRequest.Current(
                    SettingsProfileExtrasToken(1, 7, 9), 3, 77), 32, { true }, demand) { delivered.incrementAndGet() })
            }
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(demand, f.reader.savedDemand.get()); demand.revokeDemand()
            assertFalse(demand.current()); assertFalse(f.reader.savedClosed.get(), "Demand revocation does not close an in-flight physical read")
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.FULL))
            assertTrue(f.encodedBudget.used() > 0, "The synchronous result remains held until the reader returns")
            finish.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertTrue(f.reader.savedDemandRevoked.get()); val lease = assertNotNull(result.get())
            assertFalse(lease.requestCurrent()); await { f.encodedBudget.used() == 0L }; f.ui.drain()
            assertEquals(0, delivered.get()); lease.close(); assertTrue(f.reader.savedClosed.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun savedDemandRevokedBeforeRequestDoesNotEnterTheReaderOrReportAnError() {
        val f = Fixture(FakeCodec()); val demand = SettingsSavedProfileStickerAvatarDemand()
        try {
            demand.revokeDemand(); var delivered = 0
            val owner = f.provider.savedPreviewOwned(SettingsSavedProfileStickerAvatarRequest.Current(
                SettingsProfileExtrasToken(1, 7, 9), 3, 77), 32, { true }, demand) { delivered++ }
            f.ui.drain(); assertNull(f.reader.savedDemand.get()); assertEquals(0, delivered)
            assertFalse(owner.requestCurrent()); owner.close()
        } finally { f.close() }
    }

    @Test fun savedSourceRevocationDuringDecodeRetainsBytesUntilWorkerExit() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val codec = FakeCodec().apply { onOpen = { entered.countDown(); finish.await(); opened } }
        val f = Fixture(codec)
        try {
            var delivered = 0
            val request = SettingsSavedProfileStickerAvatarRequest.Current(SettingsProfileExtrasToken(1, 7, 9), 3, 77)
            val owner = f.provider.savedPreview(request, 32, { true }) { delivered++ }
            val original = f.encoded(ProfileStickerAvatarMediaRole.FULL)
            val encoded = checkNotNull(original.restrictSource(f.reader.savedSource::get)); original.close()
            f.reader.deliver(0, encoded); assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.reader.savedSource.set(false); assertFalse(owner.requestCurrent())
            assertTrue(f.encodedBudget.used() > 0, "Physical worker still owns encoded bytes")
            finish.countDown(); await { f.encodedBudget.used() == 0L && f.ui.size() > 0 }; f.ui.drain()
            assertEquals(0, delivered); assertEquals(1, codec.opened.closed.get()); owner.close()
        } finally { finish.countDown(); f.close() }
    }

    @Test fun savedSourceGateSurvivesReadCloseButRevokesClipAndBorrowedArtwork() {
        val f = Fixture(FakeCodec())
        try {
            var preview: ProfileStickerAvatarPreviewClip? = null
            val request = SettingsSavedProfileStickerAvatarRequest.Current(SettingsProfileExtrasToken(1, 7, 9), 3, 77)
            val owner = f.provider.savedPreview(request, 32, { true }) { preview = it.value }
            val original = f.encoded(ProfileStickerAvatarMediaRole.FULL)
            val encoded = checkNotNull(original.restrictSource(f.reader.savedSource::get)); original.close()
            f.reader.deliver(0, encoded); await { f.ui.size() > 0 }; f.ui.drain()
            val clip = assertNotNull(preview); assertTrue(clip.active()); assertTrue(owner.requestCurrent())
            assertTrue(f.reader.savedClosed.get(), "The physical reader closes after successful encoded delivery")
            var artwork: ProfileStickerAvatarArtwork? = null
            val frame = clip.frame(0, { true }) { artwork = it.value }
            await { f.ui.size() > 0 }; f.ui.drain(); assertNotNull(artwork?.image)
            owner.revokeDemand()
            assertFalse(assertNotNull(f.reader.savedDemand.get()).current())
            assertTrue(clip.active()); assertTrue(owner.requestCurrent()); assertNotNull(artwork?.image)
            f.reader.savedSource.set(false)
            assertFalse(clip.active()); assertFalse(owner.requestCurrent()); assertNull(artwork?.image)
            frame.close(); owner.close()
        } finally { f.close() }
    }

    @Test fun savedEarlyFailureKeepsWitnessAndLateSourceLossDropsQueuedError() {
        val f = Fixture(FakeCodec()); f.reader.failSavedSynchronously = true
        try {
            val request = SettingsSavedProfileStickerAvatarRequest.History(SettingsProfileExtrasToken(1, 7, 9), 4, 88)
            var failure: SettingsError? = null
            val owner = f.provider.savedPreview(request, 32, { true }) { failure = it.error }
            assertTrue(owner.requestCurrent()); assertFalse(f.reader.savedClosed.get())
            await { f.ui.size() > 0 }; f.ui.drain()
            assertEquals(SettingsError.REQUEST_FAILED, failure); assertTrue(owner.requestCurrent())
            owner.close(); assertTrue(f.reader.savedClosed.get())

            f.reader.savedSource.set(true); f.reader.savedClosed.set(false); failure = null
            val stale = f.provider.savedPreview(request, 32, { true }) { failure = it.error }
            await { f.ui.size() > 0 }; f.reader.savedSource.set(false); f.ui.drain()
            assertNull(failure); assertFalse(stale.requestCurrent())
            f.reader.savedSource.set(true); assertFalse(stale.requestCurrent(), "A revoked source witness never revives")
            stale.close()
        } finally { f.close() }
    }

    @Test fun savedThrowingFailureConsumerClosesItsRetainedWitness() {
        val f = Fixture(FakeCodec()); f.reader.failSavedSynchronously = true
        try {
            val request = SettingsSavedProfileStickerAvatarRequest.History(SettingsProfileExtrasToken(1, 7, 9), 4, 88)
            val owner = f.provider.savedPreview(request, 32, { true }) { error("Synthetic consumer failure") }
            await { f.ui.size() > 0 }; f.ui.drain()
            assertTrue(f.reader.savedClosed.get()); assertFalse(owner.requestCurrent())
            owner.close()
        } finally { f.close() }
    }
    @Test fun cancelledThumbnailRetainsEncodedUntilPhysicalWorkerExits() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val codec = FakeCodec().apply { onThumbnail = { entered.countDown(); finish.await(); frame() } }
        val f = Fixture(codec)
        try {
            var delivered = 0
            val lease = f.thumbnail { delivered++ }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lease.close(); lease.close()
            assertTrue(f.encodedBudget.used() > 0)
            finish.countDown(); await { f.encodedBudget.used() == 0L && f.ui.size() > 0 }; f.ui.drain()
            assertEquals(0, delivered)
            assertEquals(1, codec.disposed.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun latestFrameReplacesPendingWithoutStartingAnotherPhysicalFrame() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val clip = FakeClip().apply { onFrame = { time ->
            if (time == 1L) { entered.countDown(); finish.await() }
            frame()
        } }
        val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            val delivered = CopyOnWriteArrayList<Long>()
            val a = preview.frame(1, { true }) { delivered += 1 }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val b = preview.frame(2, { true }) { delivered += 2 }
            val c = preview.frame(3, { true }) { delivered += 3 }
            assertEquals(listOf(1L), clip.times.toList())
            finish.countDown(); await { clip.times.size == 2 }; await { f.ui.size() >= 2 }; f.ui.drain()
            assertEquals(listOf(1L, 3L), clip.times.toList())
            assertEquals(listOf(1L, 3L), delivered.toList())
            a.close(); b.close(); c.close(); owner.close()
            assertEquals(1, clip.closed.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun continuousRequestsMakeProgressEvenWhenEachCodecFrameIsSlow() {
        val gates = List(3) { CountDownLatch(1) }
        val simultaneous = AtomicInteger(); val peak = AtomicInteger()
        val clip = FakeClip().apply { onFrame = { time ->
            val current = simultaneous.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            try {
                val gate = when (time) { 0L -> 0; 10L -> 1; 20L -> 2; else -> error("Unexpected pending frame $time") }
                assertTrue(gates[gate].await(5, TimeUnit.SECONDS))
                frame()
            } finally { simultaneous.decrementAndGet() }
        } }
        val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            val delivered = CopyOnWriteArrayList<Long>()
            val leases = mutableListOf<ProfileStickerAvatarArtworkLease>()
            fun request(time: Long) {
                leases += preview.frame(time, { true }) { response -> if (response.value?.image != null) delivered += time }
            }
            request(0); await { clip.times == listOf(0L) }
            for (time in 1L..10L) request(time)
            gates[0].countDown(); await { clip.times.size == 2 && f.ui.size() >= 1 }; f.ui.drain()
            assertEquals(listOf(0L), delivered.toList())
            for (time in 11L..20L) request(time)
            gates[1].countDown(); await { clip.times.size == 3 && f.ui.size() >= 1 }; f.ui.drain()
            assertEquals(listOf(0L, 10L), delivered.toList())
            gates[2].countDown(); await { f.ui.size() >= 1 }; f.ui.drain()
            assertEquals(listOf(0L, 10L, 20L), delivered.toList())
            assertEquals(listOf(0L, 10L, 20L), clip.times.toList()); assertEquals(1, peak.get())
            leases.forEach { it.close() }; owner.close()
        } finally { gates.forEach { it.countDown() }; f.close() }
    }

    @Test fun reorderedUiDispatchCannotReplaceANewerDeliveredFrame() {
        val clip = FakeClip(); val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            val delivered = mutableListOf<Long>()
            val first = preview.frame(1, { true }) { if (it.value?.image != null) delivered += 1 }
            await { f.ui.size() >= 1 }
            val second = preview.frame(2, { true }) { if (it.value?.image != null) delivered += 2 }
            await { f.ui.size() >= 2 }
            f.ui.drainReverse()
            assertEquals(listOf(2L), delivered)
            first.close(); second.close(); owner.close()
            assertEquals(2, clip.disposed.get())
        } finally { f.close() }
    }

    @Test fun closingClipDoesNotDisposeCodecUntilRunningFrameLeaves() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val clip = FakeClip().apply { onFrame = { entered.countDown(); finish.await(); frame() } }
        val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            var delivered = 0
            preview.frame(0, { true }) { delivered++ }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            preview.frame(1, { true }) { delivered++ }
            preview.close(); owner.close()
            assertEquals(0, clip.closed.get())
            finish.countDown(); await { clip.closed.get() == 1 }; await { f.ui.size() > 0 }; f.ui.drain()
            assertEquals(0, delivered)
            assertEquals(listOf(0L), clip.times.toList(), "Closing discards pending work without launching it")
            assertEquals(1, clip.disposed.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun explicitlyCancelledRunningFrameCannotPublishAndLatestPendingStillRuns() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val clip = FakeClip().apply { onFrame = { time ->
            if (time == 1L) { entered.countDown(); finish.await() }
            frame()
        } }
        val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            val delivered = mutableListOf<Long>()
            val first = preview.frame(1, { true }) { if (it.value?.image != null) delivered += 1 }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); first.close()
            val next = preview.frame(2, { true }) { if (it.value?.image != null) delivered += 2 }
            finish.countDown(); await { clip.times.size == 2 && f.ui.size() >= 2 }; f.ui.drain()
            assertEquals(listOf(2L), delivered)
            assertEquals(listOf(1L, 2L), clip.times.toList())
            next.close(); owner.close(); assertEquals(2, clip.disposed.get())
        } finally { finish.countDown(); f.close() }
    }

    @Test fun queuedPublicationRechecksFocusAndReleasesLatePixels() {
        val focused = AtomicBoolean(true)
        val codec = FakeCodec()
        val f = Fixture(codec)
        try {
            var delivered = 0
            val lease = f.thumbnail(focused::get) { delivered++ }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            await { f.ui.size() > 0 }
            focused.set(false); f.ui.drain()
            assertEquals(0, delivered)
            assertEquals(1, codec.disposed.get())
            lease.close()
        } finally { f.close() }
    }

    @Test fun differentSelectionCannotReceivePreviousSelectionPixels() {
        val f = Fixture(FakeCodec())
        try {
            var old = 0; var fresh = 0
            val first = f.preview { old++ }
            first.close()
            f.preview { fresh++ }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.FULL))
            f.reader.deliver(1, f.encoded(ProfileStickerAvatarMediaRole.FULL))
            await { f.ui.size() > 0 }; f.ui.drain()
            assertEquals(0, old); assertEquals(1, fresh)
            await { f.encodedBudget.used() == 0L }
        } finally { f.close() }
    }

    @Test fun fullIdentityCannotBeUsedAsThumbnailAndErrorsRemainVisible() {
        val f = Fixture(FakeCodec())
        try {
            var result: SettingsResult<ProfileStickerAvatarArtwork>? = null
            f.thumbnail { result = it }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.FULL))
            f.ui.drain()
            assertEquals(SettingsError.INVALID_VALUE, result?.error)
            assertEquals(0L, f.encodedBudget.used())
        } finally { f.close() }
    }

    @Test fun readyBorrowedPixelsAreRevokedByTheOwningLease() {
        val codec = FakeCodec(); val f = Fixture(codec)
        try {
            var artwork: ProfileStickerAvatarArtwork? = null
            val lease = f.thumbnail { artwork = it.value }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            await { f.ui.size() > 0 }; f.ui.drain()
            val ready = assertNotNull(artwork)
            assertNotNull(ready.image)
            lease.close(); lease.close()
            assertNull(ready.image); assertEquals(1, codec.disposed.get())
        } finally { f.close() }
    }

    @Test fun throwingConsumerDoesNotRetainPreparedPixels() {
        val codec = FakeCodec(); val f = Fixture(codec)
        try {
            f.thumbnail { throw IllegalStateException("synthetic consumer") }
            f.reader.deliver(0, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            await { f.ui.size() > 0 }; f.ui.drain()
            assertEquals(1, codec.disposed.get()); assertEquals(0L, f.encodedBudget.used())
        } finally { f.close() }
    }

    @Test fun twoWorkersAndEightQueuedKeepTheirEncodedHoldsOnClose() {
        val entered = CountDownLatch(2); val finish = CountDownLatch(1)
        val codec = FakeCodec().apply { onThumbnail = { entered.countDown(); finish.await(); frame() } }
        val f = Fixture(codec)
        try {
            val leases = mutableListOf<ProfileStickerAvatarArtworkLease>()
            var rateLimited = 0
            repeat(2) {
                leases += f.thumbnail { if (it.error == SettingsError.RATE_LIMITED) rateLimited++ }
                f.reader.deliver(it, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(9) { index ->
                leases += f.thumbnail { if (it.error == SettingsError.RATE_LIMITED) rateLimited++ }
                f.reader.deliver(index + 2, f.encoded(ProfileStickerAvatarMediaRole.THUMBNAIL))
            }
            f.ui.drain()
            assertEquals(1, rateLimited)
            leases.forEach { it.close() }
            assertTrue(f.encodedBudget.used() > 0)
            finish.countDown(); await { f.encodedBudget.used() == 0L }; f.ui.drain()
        } finally { finish.countDown(); f.close() }
    }

    @Test fun staticClipDoesNotRequestFramesUntilCallerExplicitlyRequestsOne() {
        val clip = FakeClip(animatedValue = false)
        val f = Fixture(FakeCodec().apply { opened = clip })
        try {
            val (owner, preview) = f.openPreview()
            assertFalse(preview.animated); assertEquals(0, clip.times.size)
            val lease = preview.frame(0, { true }) {}
            await { f.ui.size() > 0 }; f.ui.drain()
            assertEquals(listOf(0L), clip.times.toList())
            lease.close(); owner.close()
        } finally { f.close() }
    }

    @Test fun vfrFrameLookupUsesPtsAndRejectsRegressingOrMissingTimestamps() {
        val probe = """
            [FRAME]
            best_effort_timestamp_time=0.000000
            pkt_duration_time=0.100000
            [/FRAME]
            [FRAME]
            best_effort_timestamp_time=0.100000
            pkt_duration_time=0.100000
            [/FRAME]
            [FRAME]
            best_effort_timestamp_time=0.400000
            pkt_duration_time=0.100000
            [/FRAME]
            [STREAM]
            width=8
            height=4
            avg_frame_rate=10/1
            [/STREAM]
            [FORMAT]
            start_time=0.000000
            duration=0.500000
            [/FORMAT]
        """.trimIndent()
        val timeline = profileStickerAvatarVideoTimeline(probe, 8, 4)
        assertEquals(500L, timeline.durationMillis); assertEquals(100L, timeline.frameHintMillis)
        assertEquals(0, timeline.indexAt(50)); assertEquals(1, timeline.indexAt(150))
        assertEquals(1, timeline.indexAt(350)); assertEquals(2, timeline.indexAt(450))
        assertFails { profileStickerAvatarVideoTimeline(probe.replace("0.400000", "0.050000"), 8, 4) }
        assertFails { profileStickerAvatarVideoTimeline(probe.replace("best_effort_timestamp_time=0.400000", ""), 8, 4) }
    }

    @Test fun lastFrameDurationUsesRealModernOrLegacyFieldAndNeverContainerTail() {
        fun probe(lastFields: String, containerSeconds: String = "5.000000") = """
            [FRAME]
            best_effort_timestamp_time=0.000000
            duration_time=0.100000
            [/FRAME]
            [FRAME]
            best_effort_timestamp_time=0.100000
            duration_time=0.300000
            [/FRAME]
            [FRAME]
            best_effort_timestamp_time=0.400000
            $lastFields
            [/FRAME]
            [STREAM]
            width=8
            height=4
            [/STREAM]
            [FORMAT]
            start_time=0.000000
            duration=$containerSeconds
            [/FORMAT]
        """.trimIndent().lineSequence().joinToString("\n") { it.trim() }
        val modern = profileStickerAvatarVideoTimeline(probe("duration_time=0.250000\npkt_duration_time=N/A"), 8, 4)
        assertEquals(650L, modern.durationMillis)
        assertEquals(2, modern.indexAt(600))
        val legacy = profileStickerAvatarVideoTimeline(probe("duration_time=0\npkt_duration_time=0.100000"), 8, 4)
        assertEquals(500L, legacy.durationMillis)
        val same = profileStickerAvatarVideoTimeline(probe("duration_time=0.100000\npkt_duration_time=0.100000"), 8, 4)
        assertEquals(500L, same.durationMillis)
        assertFails { profileStickerAvatarVideoTimeline(probe("duration_time=0.250000\npkt_duration_time=0.100000"), 8, 4) }
        assertFails { profileStickerAvatarVideoTimeline(probe("duration_time=N/A\npkt_duration_time=0"), 8, 4) }
        assertFails { profileStickerAvatarVideoTimeline(probe("duration_time=not-a-number"), 8, 4) }
        assertFails { profileStickerAvatarVideoTimeline(probe("duration_time=-0.1"), 8, 4) }
        assertFails { profileStickerAvatarVideoTimeline(probe("duration_time=0.250000", "0.500000"), 8, 4) }
    }

    @Test fun singleFrameWithoutDurationIsStaticOnlyAfterMatchingPhysicalExtraction() {
        val text = """
            [FRAME]
            best_effort_timestamp_time=0.000000
            duration_time=N/A
            pkt_duration_time=N/A
            [/FRAME]
            [STREAM]
            width=1
            height=1
            [/STREAM]
            [FORMAT]
            start_time=N/A
            duration=N/A
            [/FORMAT]
        """.trimIndent()
        val candidate = profileStickerAvatarVideoTimeline(text, 1, 1)
        assertContentEquals(longArrayOf(0), candidate.positionsMicros)
        assertEquals(0L, candidate.durationMillis); assertEquals(0L, candidate.frameHintMillis)
        val multiple = text.replace("[/FRAME]", "[/FRAME]\n[FRAME]\nbest_effort_timestamp_time=0.100000\n" +
            "duration_time=N/A\npkt_duration_time=N/A\n[/FRAME]").replace("duration=N/A", "duration=1.000000")
        assertFails { profileStickerAvatarVideoTimeline(multiple, 1, 1) }

        val folder = Files.createTempDirectory("synthetic-static-webm-process-")
        val ffmpeg = Files.createFile(folder.resolve("ffmpeg.exe"))
        val ffprobe = Files.createFile(folder.resolve("ffprobe.exe"))
        val disk = ProfileStickerAvatarDiskBudget(); val pixels = ProfileStickerAvatarPixelBudget()
        val budget = ProfileStickerAvatarEncodedBudget(); val bytes = AtomicReference(byteArrayOf(255.toByte(), 0, 0, 255.toByte()))
        val processes = ProfileStickerAvatarProcesses(start = { command ->
            if (command.first() == ffprobe.toString()) CompletedProcess(text.encodeToByteArray())
            else CompletedProcess(bytes.get())
        })
        val codec = SkiaProfileStickerAvatarCodec(pixels, disk, { ffmpeg }, processes)
        val permit = ArtworkWorkerPermit {}
        fun open(): ArtworkCodecClip {
            val encodedBytes = ByteArray(16)
            val file = MediaFile(1, encodedBytes.size.toLong(), uniqueId = "synthetic-single-webm")
            val asset = ProfileStickerAvatarAsset(file, MediaFormat.WEBM, 1, 1)
            val encoded = ProfileStickerAvatarEncoded.take(ProfileStickerAvatarOwnedMedia(
                ProfileStickerAvatarMediaRole.FULL, asset, false), encodedBytes, checkNotNull(budget.reserve(16)))
            return try { codec.open(encoded, 1, { true }, permit) } finally { encoded.close() }
        }
        try {
            val clip = open()
            try {
                assertFalse(clip.animated); assertEquals(0L, clip.durationMillis); assertEquals(0L, clip.frameDurationMillis)
                clip.frame(0, { true }, permit).use { frame ->
                    val pixel = IntArray(1); frame.image.readPixels(pixel)
                    assertEquals(0xffff0000.toInt(), pixel.single())
                }
            } finally { clip.close() }
            await { disk.used() == 0L && budget.used() == 0L }
            listOf(ByteArray(0), ByteArray(8)).forEach { mismatch ->
                bytes.set(mismatch)
                assertFails { open() }
                await { disk.used() == 0L && budget.used() == 0L }
            }
            assertEquals(0L, pixels.used())
        } finally {
            permit.release(); Files.deleteIfExists(ffmpeg); Files.deleteIfExists(ffprobe); Files.deleteIfExists(folder)
        }
    }

    @Test fun retainedCopiesAndPixelReservationsNeverReturnCapacityEarly() {
        val f = Fixture(FakeCodec())
        try {
            val encoded = f.encoded(ProfileStickerAvatarMediaRole.FULL)
            val worker = assertNotNull(encoded.retain())
            val copy = assertNotNull(encoded.reserveCopy(20))
            encoded.close()
            assertEquals(36L, f.encodedBudget.used())
            worker.close(); assertEquals(20L, f.encodedBudget.used())
            copy.close(); copy.close(); assertEquals(0L, f.encodedBudget.used())
            val pixels = ProfileStickerAvatarPixelBudget(100)
            val held = assertNotNull(pixels.reserve(80))
            assertNull(pixels.reserve(21)); held.shrink(30)
            assertEquals(30L, pixels.used()); held.close(); held.close(); assertEquals(0L, pixels.used())
        } finally { f.close() }
    }

    @Test fun unconfirmedChildKeepsSlotScratchAndPixelChargeUntilActualExit() {
        val disk = ProfileStickerAvatarDiskBudget(1024)
        val pixels = ProfileStickerAvatarPixelBudget(100)
        val scratch = ProfileStickerAvatarScratch.create(20, disk)
        Files.write(scratch.input, ByteArray(20))
        val slotReleased = AtomicBoolean(false)
        val permit = ArtworkWorkerPermit { slotReleased.set(true) }
        val process = StubbornProcess()
        val memory = assertNotNull(pixels.reserve(80))
        val runner = ProfileStickerAvatarProcesses(start = { process }, timeoutMillis = 20)
        try {
            assertFails { runner.run(listOf("synthetic"), scratch, permit, 0, { true }, ByteArrayOutputStream(), memory) }
            scratch.release(); permit.release()
            assertTrue(process.destroyed.get()); assertFalse(slotReleased.get())
            assertEquals(80L, pixels.used()); assertEquals(20L, disk.used())
            assertTrue(Files.exists(scratch.input))
            process.exited.set(true)
            await { slotReleased.get() && pixels.used() == 0L && disk.used() == 0L }
            assertFalse(Files.exists(scratch.directory))
        } finally { process.exited.set(true) }
    }

    @Test fun continuousStdoutStillHitsDeadlineAndReaperRetainsResources() {
        val disk = ProfileStickerAvatarDiskBudget(1024)
        val pixels = ProfileStickerAvatarPixelBudget(100)
        val scratch = ProfileStickerAvatarScratch.create(20, disk)
        Files.write(scratch.input, ByteArray(20))
        val slotReleased = AtomicBoolean(false)
        val permit = ArtworkWorkerPermit { slotReleased.set(true) }
        val reads = AtomicInteger()
        val input = object : InputStream() {
            override fun available() = 1
            override fun read(): Int { reads.incrementAndGet(); return 42 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet(); buffer[offset] = 42; return 1
            }
        }
        val process = StubbornProcess(input)
        val memory = assertNotNull(pixels.reserve(80))
        val clock = AtomicLong()
        val runner = ProfileStickerAvatarProcesses(start = { process }, timeoutMillis = 1,
            nanoTime = { clock.getAndAdd(100_000) })
        val sink = ByteArrayOutputStream()
        try {
            assertFailsWith<IllegalStateException> {
                runner.run(listOf("synthetic"), scratch, permit, 1024, { true }, sink, memory)
            }
            assertTrue(reads.get() > 0); assertTrue(sink.size() in 1 until 1024)
            scratch.release(); permit.release()
            assertTrue(process.destroyed.get()); assertFalse(slotReleased.get())
            assertEquals(80L, pixels.used()); assertEquals(20L, disk.used())
            assertTrue(Files.exists(scratch.input))
            process.exited.set(true)
            await { slotReleased.get() && pixels.used() == 0L && disk.used() == 0L }
            assertFalse(Files.exists(scratch.directory))
        } finally { process.exited.set(true) }
    }

    private class Ui : ProfileStickerAvatarUiDispatch {
        private val queue = ConcurrentLinkedQueue<Pair<() -> Unit, () -> Unit>>()
        override fun post(deliver: () -> Unit, discard: () -> Unit) { queue.add(deliver to discard) }
        fun size() = queue.size
        fun drain() { while (true) (queue.poll() ?: break).first() }
        fun drainReverse() {
            val waiting = mutableListOf<Pair<() -> Unit, () -> Unit>>()
            while (true) {
                val entry = queue.poll() ?: break
                waiting.add(entry)
            }
            waiting.asReversed().forEach { it.first() }
        }
    }
    private class Reader : ProfileStickerAvatarEncodedReader {
        private data class Call(val callback: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit)
        private val calls = CopyOnWriteArrayList<Call>()
        val savedSource = AtomicBoolean(true); val savedClosed = AtomicBoolean(); var failSavedSynchronously = false
        val savedDemand = AtomicReference<SettingsSavedProfileStickerAvatarDemand?>(); val savedDemandRevoked = AtomicBoolean()
        var savedInline: ProfileStickerAvatarEncoded? = null; var beforeSavedReturn: (() -> Unit)? = null
        override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable = add(result)
        override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable = add(result)
        override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease {
            calls += Call(result)
            if (failSavedSynchronously) result(SettingsResult(error = SettingsError.REQUEST_FAILED))
            savedInline?.let { result(SettingsResult(value = it)) }
            beforeSavedReturn?.invoke()
            return object : SettingsSavedProfileStickerAvatarLease {
                override fun requestCurrent() = !savedClosed.get() && savedSource.get() && active()
                override fun revokeDemand() { savedDemandRevoked.set(true) }
                override fun close() { savedClosed.set(true) }
            }
        }
        override fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            demand: SettingsSavedProfileStickerAvatarDemand,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease {
            savedDemand.set(demand)
            return savedPreview(request, { demand.current() && active() }, result)
        }
        private fun add(result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable {
            calls += Call(result); return AutoCloseable {}
        }
        fun deliver(index: Int, encoded: ProfileStickerAvatarEncoded) = calls[index].callback(SettingsResult(encoded))
    }
    private class FakeCodec : ProfileStickerAvatarCodec {
        val disposed = AtomicInteger()
        var onThumbnail: () -> ArtworkFrame = { frame() }
        var opened: FakeClip = FakeClip()
        var onOpen: () -> ArtworkCodecClip = { opened }
        fun frame() = ArtworkFrame(ImageBitmap(1, 1), false) { disposed.incrementAndGet() }
        override fun thumbnail(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
            permit: ArtworkWorkerPermit) = onThumbnail()
        override fun open(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
            permit: ArtworkWorkerPermit): ArtworkCodecClip = onOpen()
    }
    private class FakeClip(animatedValue: Boolean = true) : ArtworkCodecClip {
        override val animated = animatedValue
        override val durationMillis = if (animated) 1000L else 0L
        override val frameDurationMillis = if (animated) 100L else 0L
        val times = CopyOnWriteArrayList<Long>(); val closed = AtomicInteger(); val disposed = AtomicInteger()
        var onFrame: (Long) -> ArtworkFrame = { frame() }
        fun frame() = ArtworkFrame(ImageBitmap(1, 1), false) { disposed.incrementAndGet() }
        override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit): ArtworkFrame {
            times += timeMillis; return onFrame(timeMillis)
        }
        override fun close() { closed.incrementAndGet() }
    }
    private class Fixture(codec: FakeCodec) : AutoCloseable {
        val encodedBudget = ProfileStickerAvatarEncodedBudget()
        val ui = Ui(); val reader = Reader(); val workers = ProfileStickerAvatarArtworkWorkers()
        val provider = JvmProfileStickerAvatarArtworkProvider(1, reader, ui, codec, workers)
        private val parent = SettingsProfileExtrasToken(1, 7, 9)
        private val picker = ProfileStickerAvatarPickerTicket()
        private val candidate = ProfileStickerAvatarCandidateTicket()
        private val selection = ProfileStickerAvatarSelectionTicket()
        fun encoded(role: ProfileStickerAvatarMediaRole): ProfileStickerAvatarEncoded {
            val bytes = ByteArray(16) { it.toByte() }
            val file = MediaFile(1, bytes.size.toLong(), uniqueId = "synthetic")
            val asset = ProfileStickerAvatarAsset(file, MediaFormat.WEBP, 1, 1)
            val hold = checkNotNull(encodedBudget.reserve(bytes.size.toLong()))
            return ProfileStickerAvatarEncoded.take(ProfileStickerAvatarOwnedMedia(role, asset, false), bytes, hold)
        }
        fun thumbnail(active: () -> Boolean = { true }, result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit) =
            provider.thumbnail(parent, picker, candidate, 16, active, result)
        fun preview(result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit) =
            provider.preview(parent, picker, selection, 32, { true }, result)
        fun openPreview(): Pair<ProfileStickerAvatarArtworkLease, ProfileStickerAvatarPreviewClip> {
            var value: ProfileStickerAvatarPreviewClip? = null
            val owner = preview { value = it.value }
            reader.deliver(0, encoded(ProfileStickerAvatarMediaRole.FULL))
            await { ui.size() > 0 }; ui.drain()
            return owner to assertNotNull(value)
        }
        override fun close() { provider.close(); ui.drain(); workers.close() }
    }
    private class StubbornProcess(private val stdout: InputStream = ByteArrayInputStream(ByteArray(0))) : Process() {
        val destroyed = AtomicBoolean(); val exited = AtomicBoolean()
        private val output = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (!exited.get()) Thread.yield(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.get()
        override fun exitValue(): Int = if (exited.get()) 0 else throw IllegalThreadStateException()
        override fun isAlive() = !exited.get()
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process { destroyed.set(true); return this }
    }
    private class CompletedProcess(bytes: ByteArray) : Process() {
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(bytes)
        private val errors = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = errors
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun isAlive() = false
        override fun destroy() {}
        override fun destroyForcibly(): Process = this
    }
    private companion object {
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) { check(System.nanoTime() < end) { "Synthetic condition timed out" }; Thread.sleep(5) }
        }
    }
}
