package com.badnng.moe.media

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.settings.*
import com.badnng.moe.tdlib.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStickerAvatarUiDispatchTest {
    @Test fun synchronousDeliveryThrowStillReclaimsExactlyOnce() {
        runTest {
            val f = Fixture(testScheduler, failUiMetadata = true, synchronous = true)
            try {
                var calls = 0
                var error: SettingsError? = null
                f.preview { calls++; error = it.error }; f.deliver()
                assertTrue(f.posted.await(5, TimeUnit.SECONDS))
                assertEquals(1, calls); assertEquals(SettingsError.REQUEST_FAILED, error)
                assertEquals(1, f.clip.closed.get()); assertEquals(0L, f.budget.used())
            } finally { f.close() }
        }
    }

    @Test fun providerWithRealDispatcherReclaimsClipAndReportsUiMetadataFailureOnce() {
        runTest {
            val f = Fixture(testScheduler, failUiMetadata = true)
            try {
                var calls = 0
                var error: SettingsError? = null
                val owner = f.preview { calls++; error = it.error }
                f.deliver()
                assertTrue(f.posted.await(5, TimeUnit.SECONDS)); runCurrent()
                assertEquals(1, calls); assertEquals(SettingsError.REQUEST_FAILED, error)
                assertEquals(1, f.clip.closed.get()); assertEquals(1, f.readClosed.get())
                assertEquals(0L, f.budget.used())
                owner.close(); f.provider.close(); f.uiScope.cancel(); runCurrent()
                assertEquals(1, f.clip.closed.get()); assertEquals(1, calls)
            } finally { f.close() }
        }
    }

    @Test fun duplicateDeliveryAndDiscardCannotCloseSuccessfullyTransferredClip() {
        runTest {
            val f = Fixture(testScheduler, failUiMetadata = false)
            try {
                var calls = 0
                var clip: ProfileStickerAvatarPreviewClip? = null
                val owner = f.preview { calls++; clip = it.value }
                f.deliver()
                assertTrue(f.posted.await(5, TimeUnit.SECONDS)); runCurrent()
                assertEquals(1, calls); assertNotNull(clip)
                assertEquals(0, f.clip.closed.get(), "duplicate discard cannot revoke transferred clip")
                owner.close(); assertEquals(1, f.clip.closed.get())
                f.provider.close(); f.uiScope.cancel(); runCurrent()
                assertEquals(1, f.clip.closed.get()); assertEquals(1, calls)
            } finally { f.close() }
        }
    }
    @Test fun cancelledBeforeDispatchDiscardsExactlyOnce() {
        runTest {
            val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val dispatcher = profileStickerAvatarUiDispatcher(uiScope)
            var delivered = 0; var discarded = 0
            dispatcher.post({ delivered++ }, { discarded++ })
            uiScope.cancel(); runCurrent()
            assertEquals(0, delivered); assertEquals(1, discarded)
        }
    }

    @Test fun completedDeliveryIsNotDiscardedOnLaterDisposal() {
        runTest {
            val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val dispatcher = profileStickerAvatarUiDispatcher(uiScope)
            var delivered = 0; var discarded = 0
            dispatcher.post({ delivered++ }, { discarded++ }); runCurrent()
            uiScope.cancel(); runCurrent()
            assertEquals(1, delivered); assertEquals(0, discarded)
        }
    }

    private class Fixture(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, failUiMetadata: Boolean,
        synchronous: Boolean = false) : AutoCloseable {
        val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val posted = CountDownLatch(1)
        val readClosed = AtomicInteger()
        val budget = ProfileStickerAvatarEncodedBudget()
        val workers = ProfileStickerAvatarArtworkWorkers()
        val clip = TestClip(failUiMetadata)
        private lateinit var received: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit
        private val reader = object : ProfileStickerAvatarEncodedReader {
            override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                candidate: ProfileStickerAvatarCandidateTicket, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable = error("Only preview")
            override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
                selection: ProfileStickerAvatarSelectionTicket, active: () -> Boolean,
                result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): AutoCloseable {
                received = result
                return AutoCloseable { readClosed.incrementAndGet() }
            }
        }
        private val ui = profileStickerAvatarUiDispatcher(uiScope)
        private val duplicateUi = ProfileStickerAvatarUiDispatch { deliver, discard ->
            if (synchronous) { deliver(); deliver(); discard() }
            else ui.post({ deliver(); deliver(); discard() }, { discard(); discard() })
            posted.countDown()
        }
        private val codec = object : ProfileStickerAvatarCodec {
            override fun thumbnail(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
                permit: ArtworkWorkerPermit): ArtworkFrame = error("Only preview")
            override fun open(encoded: ProfileStickerAvatarEncoded, edge: Int, active: () -> Boolean,
                permit: ArtworkWorkerPermit): ArtworkCodecClip = clip
        }
        val provider = JvmProfileStickerAvatarArtworkProvider(1, reader, duplicateUi, codec, workers)
        fun preview(result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit) = provider.preview(
            SettingsProfileExtrasToken(1, 1, 43), ProfileStickerAvatarPickerTicket(),
            ProfileStickerAvatarSelectionTicket(), 16, { true }, result)
        fun deliver() {
            val bytes = ByteArray(16)
            val asset = ProfileStickerAvatarAsset(MediaFile(1, 16, uniqueId = "synthetic"), MediaFormat.WEBP, 1, 1)
            val encoded = ProfileStickerAvatarEncoded.take(ProfileStickerAvatarOwnedMedia(
                ProfileStickerAvatarMediaRole.FULL, asset, false), bytes, checkNotNull(budget.reserve(16)))
            received(SettingsResult(encoded))
        }
        override fun close() { provider.close(); uiScope.cancel(); workers.close() }
    }

    private class TestClip(private val failUiMetadata: Boolean) : ArtworkCodecClip {
        private val probes = AtomicInteger()
        val closed = AtomicInteger()
        override val animated: Boolean get() {
            if (probes.incrementAndGet() > 1 && failUiMetadata) error("Synthetic UI metadata failure")
            return false
        }
        override val durationMillis = 0L
        override val frameDurationMillis = 0L
        override fun frame(timeMillis: Long, active: () -> Boolean, permit: ArtworkWorkerPermit) =
            ArtworkFrame(ImageBitmap(1, 1), false) {}
        override fun close() { closed.incrementAndGet() }
    }
}
