package com.badnng.moe.tdlib

import androidx.compose.ui.graphics.ImageBitmap
import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.media.JvmProfileStickerAvatarArtworkProvider
import com.badnng.moe.media.ProfileStickerAvatarArtworkWorkers
import com.badnng.moe.media.ProfileStickerAvatarEncodedReader
import com.badnng.moe.media.ProfileStickerAvatarPixelBudget
import com.badnng.moe.media.ProfileStickerAvatarUiDispatch
import com.badnng.moe.media.SkiaProfileStickerAvatarCodec
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.*

/**
 * Real OfficialTdSession/settings catalogs, Engine/actor, Files/shared broker and Skia provider.
 * Only the TDLib transport is controlled. Every native response/update is delivered serially on
 * this test's receiver thread; actual Files/broker/codec workers keep their production executors.
 * Generated local WEBP only: no native client, network, account, upload, UI composition or audio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSavedProfileStickerAvatarPipelineSmokeTest {
    @Test fun currentNativeFailureKeepsRetryWitnessAndSuccessfulReadCloseDoesNotRevokeItsRealSource() = runTest {
        smokeOnly()
        withPipeline { p ->
            val request = openCurrent(p)
            val failures = mutableListOf<SettingsResult<ProfileStickerAvatarPreviewClip>>()
            val failed = p.provider.savedPreview(request, 16, p.previewVisible::get, failures::add)
            val lookup = next<TdApi.GetCustomEmojiStickers>(p)
            assertContentEquals(longArrayOf(CUSTOM_EMOJI_ID), assertIs<TdApi.GetCustomEmojiStickers>(lookup.command).customEmojiIds)
            lookup.reply(TdApi.Error(500, "SYNTHETIC_FAILURE"))
            await(p) { failures.isNotEmpty() }
            assertEquals(SettingsError.REQUEST_FAILED, failures.single().error)
            assertTrue(failed.requestCurrent(), "An ordinary failure preserves the exact admitted native source witness")
            assertTrue(p.reader.reads.single().native.get()!!.requestCurrent())
            assertEquals(0, p.reader.reads.single().closes.get())
            assertEquals(0, p.native.byteReads())
            assertEquals(1, p.native.commands.count { it is TdApi.GetCustomEmojiStickers }, "There is no automatic retry")
            failed.close()
            assertFalse(failed.requestCurrent())
            assertEquals(1, p.reader.reads.single().closes.get())

            // A new user retry starts the same typed request, with a fresh real actor grant.
            val previews = mutableListOf<SettingsResult<ProfileStickerAvatarPreviewClip>>()
            val retried = p.provider.savedPreview(request, 16, p.previewVisible::get, previews::add)
            await(p) { previews.isNotEmpty() }
            val clip = assertNotNull(previews.single().value)
            val successfulRead = p.reader.reads.last()
            val source = assertNotNull(successfulRead.encodedSource.get())
            assertEquals(1, successfulRead.closes.get(), "Provider closes its actual Engine read after taking encoded ownership")
            assertFalse(successfulRead.native.get()!!.requestCurrent(), "The reader handle itself has really closed")
            assertTrue(source(), "Successful encoded has independent source authority after read close")
            assertTrue(clip.active()); assertTrue(retried.requestCurrent()); assertFalse(clip.animated)
            assertEquals(3, p.native.commands.count { it is TdApi.GetCustomEmojiStickers }, "The retry resolves and revalidates the actual custom identity")
            assertEquals(1, p.native.byteReads())
            assertTrue(p.native.commands.filterIsInstance<TdApi.ReadFilePart>().all { it.fileId == CURRENT_FILE_ID })
            assertTrue(p.native.commands.none { it is TdApi.GetDefaultProfilePhotoCustomEmojiStickers || it is TdApi.GetInstalledStickerSets })
            var artwork: ProfileStickerAvatarArtwork? = null
            val frame = clip.frame(0, p.previewVisible::get) { artwork = it.value }
            await(p) { artwork != null }
            val borrowed = assertNotNull(artwork)
            val image = assertNotNull(borrowed.image)
            assertEquals(16, image.width); assertEquals(16, image.height)
            assertColorNear(0xffff4010.toInt(), image.pixel(8, 8), 15)
            assertEquals(0, image.pixel(8, 0), "The real 8x4 WEBP retains its transparent aspect-fit canvas")

            // Same photo ID, different native background: revoke before the actor consumes its update.
            p.native.currentColor = 0x123457
            p.native.updateCurrent()
            assertFalse(source()); assertFalse(clip.active()); assertFalse(retried.requestCurrent())
            assertNull(borrowed.image, "Already borrowed artwork checks the actual source at access time")
            assertTrue(p.pixels.used() > 0L, "Source revocation hides artwork without freeing pixels still borrowed by UI")
            val readsAtRevocation = p.native.byteReads()
            var staleFrames = 0
            val stale = clip.frame(1, p.previewVisible::get) { if (it.value != null) staleFrames++ }
            settle(p)
            assertEquals(0, staleFrames); assertEquals(readsAtRevocation, p.native.byteReads())
            assertEquals(SettingsCurrentProfilePhotoStatus.CONFLICT, p.engine.state.value.settings.profileExtras.currentPhoto.status)
            stale.close(); frame.close(); retried.close(); clip.close()
        }
    }

    @Test fun historyUsesItsActualRawPageAndArchivedSetThenNativeConflictDropsBorrowedAndQueuedPixels() = runTest {
        smokeOnly()
        withPipeline { p ->
            assertTrue(p.engine.openSettingsProfilePhotoHistory(p.parent, p.historyVisible::get))
            await(p) { p.engine.state.value.settings.profileExtras.history.entries.any { it.photoId == HISTORY_PHOTO_ID } }
            val history = p.engine.state.value.settings.profileExtras.history
            val entry = history.entries.single { it.photoId == HISTORY_PHOTO_ID }
            assertEquals(HISTORY_RAW_OFFSET, entry.rawOffset)
            assertEquals(SettingsProfilePhotoSticker.Type.RegularOrMask(SET_ID, STICKER_ID), entry.sticker?.type)
            assertEquals(SettingsProfilePhotoSticker.Background.Gradient(0x112233, 0x445566, 135), entry.sticker?.background)
            assertTrue(history.hasMore)
            val request = SettingsSavedProfileStickerAvatarRequest.History(p.parent, history.epoch, entry.photoId)
            val previews = mutableListOf<SettingsResult<ProfileStickerAvatarPreviewClip>>()
            val owner = p.provider.savedPreview(request, 16, p.previewVisible::get, previews::add)
            await(p) { previews.isNotEmpty() }
            val clip = assertNotNull(previews.single().value)
            val source = assertNotNull(p.reader.reads.single().encodedSource.get())
            assertEquals(1, p.reader.reads.single().closes.get()); assertTrue(source()); assertTrue(owner.requestCurrent())
            assertEquals(2, p.native.commands.count { it is TdApi.GetStickerSet }, "The saved source resolves and revalidates its actual archived set")
            assertTrue(p.native.commands.filterIsInstance<TdApi.GetStickerSet>().all { it.setId == SET_ID })
            assertTrue(p.native.commands.filterIsInstance<TdApi.ReadFilePart>().all { it.fileId == HISTORY_FILE_ID })
            assertEquals(1, p.native.byteReads(), "Only the explicitly selected raw position reads full artwork")
            assertTrue(p.native.commands.none { it is TdApi.GetInstalledStickerSets || it is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
            var artwork: ProfileStickerAvatarArtwork? = null
            val displayed = clip.frame(0, p.previewVisible::get) { artwork = it.value }
            await(p) { artwork != null }
            val borrowed = assertNotNull(artwork)
            assertColorNear(0xffff4010.toInt(), assertNotNull(borrowed.image).pixel(8, 8), 15)
            var queuedFrames = 0
            val queued = clip.frame(1, p.previewVisible::get) { if (it.value != null) queuedFrames++ }
            await(p, deliverUi = false) { p.ui.size() > 0 }

            assertTrue(p.engine.moreSettingsProfilePhotoHistory(p.parent))
            val more = next<TdApi.GetUserProfilePhotos>(p)
            assertEquals(HISTORY_PAGE_SIZE, assertIs<TdApi.GetUserProfilePhotos>(more.command).offset)
            val pendingResults = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val pending = assertNotNull(p.environment).reader.savedPreview(request, p.previewVisible::get, pendingResults::add)
            assertTrue(pending.requestCurrent())
            // Do not run the actor between this queued acquire and the actual native page conflict.
            more.reply(TdApi.ChatPhotos(HISTORY_PAGE_SIZE + 1,
                arrayOf(p.native.historyPhoto(colorChanged = true))))
            assertFalse(source(), "Native publish revokes the earlier real page ticket synchronously")
            assertFalse(pending.requestCurrent(), "The queued exact page witness also revokes before actor admission")
            assertFalse(clip.active()); assertFalse(owner.requestCurrent()); assertNull(borrowed.image)
            p.ui.drain()
            assertEquals(0, queuedFrames, "Real decoded pixels already queued for UI cannot publish after source loss")
            settle(p)
            assertTrue(p.engine.state.value.settings.profileExtras.history.refreshRequired)
            assertEquals(1, p.native.byteReads())
            assertEquals(2, p.native.commands.count { it is TdApi.GetStickerSet }, "The revoked queued acquire never reaches native resolution")
            assertTrue(pendingResults.none { it.value != null })
            pending.close(); displayed.close(); queued.close(); owner.close(); clip.close()
        }
    }

    @Test fun offFramePrefixLiveControlDemandRevocationCloseRefreshAndMandatoryLockCannotReviveOldOutput() = runTest {
        smokeOnly()
        for (boundary in listOf("caller-prefix-live", "caller-prefix-revoked", "close-prefix", "refresh-read", "mandatory-read", "stop-read", "authorization-read")) withPipeline { p ->
            val callerPrefix = boundary.startsWith("caller-prefix-")
            val closeAtPrefix = boundary == "close-prefix"
            val request = openCurrent(p)
            val previews = mutableListOf<SettingsResult<ProfileStickerAvatarPreviewClip>>()
            if (callerPrefix) p.native.currentDownloaded = false
            // The window/selection owner possesses this token before any reader can admit work.
            val demand = if (callerPrefix) SettingsSavedProfileStickerAvatarDemand() else null
            val owner = if (demand == null) p.provider.savedPreview(request, 16, p.previewVisible::get, previews::add)
                else p.provider.savedPreviewOwned(request, 16, p.previewVisible::get, demand, previews::add)
            if (callerPrefix) {
                val sharedDemand = assertNotNull(demand)
                val revoke = boundary == "caller-prefix-revoked"
                val prefix = next<TdApi.GetFileDownloadedPrefixSize>(p)
                assertEquals(CURRENT_FILE_ID, assertIs<TdApi.GetFileDownloadedPrefixSize>(prefix.command).fileId)
                assertTrue(p.native.session.chats.managesDownload(CURRENT_FILE_ID))
                assertSame(sharedDemand, p.reader.reads.single().demand.get(), "Provider forwards the same prebuilt UI demand into the environment reader")
                val commandsBeforeInactive = p.native.commands.size
                val requestsBeforeInactive = p.native.rangeRequests()
                withDownloadSchedulerPaused {
                    // Identical native id/unique/size and prefix=0; only token revocation differs.
                    // Do not query owner before the callback or let watchdog hide the atomic gate.
                    prefix.reply(TdApi.FileDownloadedPrefixSize(0)) {
                        p.previewVisible.set(false)
                        if (revoke) sharedDemand.revokeDemand()
                    }
                    val continuation = p.native.commands.drop(commandsBeforeInactive)
                    if (revoke) assertTrue(continuation.none { it is TdApi.GetFileDownloadedPrefixSize || it is TdApi.DownloadFile || it is TdApi.ReadFilePart })
                    else {
                        val download = assertIs<TdApi.DownloadFile>(continuation.single())
                        assertEquals(CURRENT_FILE_ID, download.fileId); assertEquals(0L, download.offset)
                        assertEquals(p.native.bytes.size.toLong(), download.limit)
                        assertFalse(download.synchronous)
                    }
                    // The positive control has now proved real synchronous broker progress.
                    // Retire it before acknowledging its native request, then drain that callback.
                    sharedDemand.revokeDemand()
                    assertFalse(owner.requestCurrent())
                }
                if (!revoke) p.native.replyDefault(next<TdApi.DownloadFile>(p))
                settle(p)
                val expectedRanges = requestsBeforeInactive + if (revoke) 0 else 1
                assertEquals(expectedRanges, p.native.rangeRequests())
                assertTrue(previews.none { it.value != null }); assertEquals(0, p.native.byteReads())
                p.previewVisible.set(true)
                assertFalse(owner.requestCurrent(), "Restoring visibility cannot revive a retired owner")
                settle(p)
                assertTrue(previews.none { it.value != null }); assertFalse(owner.requestCurrent())
                assertEquals(expectedRanges, p.native.rangeRequests()); assertEquals(0, p.native.byteReads())
                assertEquals(1, p.native.commands.count { it is TdApi.GetCustomEmojiStickers })
                owner.close()
                return@withPipeline
            }
            val prefix = next<TdApi.GetFileDownloadedPrefixSize>(p)
            assertEquals(CURRENT_FILE_ID, assertIs<TdApi.GetFileDownloadedPrefixSize>(prefix.command).fileId)
            assertTrue(p.native.session.chats.managesDownload(CURRENT_FILE_ID), "The real chat/settings session shares this broker lane")
            val late = if (closeAtPrefix) prefix else {
                prefix.reply(TdApi.FileDownloadedPrefixSize(p.native.bytes.size.toLong()))
                next<TdApi.ReadFilePart>(p)
            }
            assertTrue(owner.requestCurrent())
            val expectedReads = if (closeAtPrefix) 0 else 1
            assertEquals(expectedReads, p.native.byteReads())
            val nextEpoch = mutableListOf<Long?>()
            val mediaBeforeBoundary = p.native.rangeRequests()
            val stopped = if (boundary == "stop-read") async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { p.engine.stop() }
            } else null
            when (boundary) {
                "close-prefix" -> assertTrue(p.engine.closeSettingsCurrentProfilePhoto(p.parent, request.epoch))
                "refresh-read" -> assertTrue(p.engine.refreshSettingsCurrentProfilePhoto(p.parent, request.epoch, nextEpoch::add))
                "mandatory-read" -> {
                    p.native.requireLoginEmail()
                    assertFalse(owner.requestCurrent(), "The real suggested-actions gate revokes Engine output before its actor runs")
                }
                "stop-read" -> {
                    assertTrue(p.previewVisible.get(), "Stop revokes the real Engine owner while its caller remains visible")
                    assertEquals(SettingsCurrentProfilePhotoStatus.READY, p.engine.state.value.settings.profileExtras.currentPhoto.status)
                    assertSame(p.environment, p.engine.profileStickerAvatarMedia.value, "The stop action has not run on the actor yet")
                    assertFalse(owner.requestCurrent(), "stop() atomically revokes saved output before actor admission")
                    assertFalse(assertNotNull(p.reader.reads.single().native.get()).requestCurrent())
                    assertFalse(assertNotNull(stopped).isCompleted)
                }
                "authorization-read" -> {
                    p.native.auth(AuthorizationPhase.OTHER_STEP)
                    assertTrue(p.previewVisible.get())
                    assertEquals(AuthorizationPhase.READY, p.engine.state.value.status.authorization, "The authorization update is still queued for the actor")
                    assertFalse(owner.requestCurrent(), "The real native authorization callback revokes output synchronously")
                    assertFalse(assertNotNull(p.reader.reads.single().native.get()).requestCurrent())
                }
            }
            runCurrent()
            assertFalse(owner.requestCurrent(), "Actor close/refresh invalidates the admitted source before the held native callback")
            if (boundary == "refresh-read") assertNotEquals(request.epoch, assertNotNull(nextEpoch.single()))
            val stopClose = if (stopped != null) next<TdApi.Close>(p).also {
                assertFalse(stopped.isCompleted, "Stop must wait for the real Close acknowledgement")
                assertFalse(it.responded.get())
            } else null
            p.native.replyDefault(late)
            settle(p)
            assertTrue(previews.none { it.value != null })
            assertEquals(expectedReads, p.native.byteReads(), "A late prefix cannot start bytes; late bytes cannot request another part")
            assertEquals(1, p.native.commands.count { it is TdApi.GetCustomEmojiStickers }, "Old bytes cannot trigger metadata revalidation")
            if (boundary == "authorization-read")
                assertEquals(mediaBeforeBoundary, p.native.rangeRequests(), "Authorization loss cannot continue media after the late byte response")

            if (stopped != null) {
                val close = assertNotNull(stopClose)
                assertTrue(late.responded.get(), "The already dispatched byte callback physically returned")
                assertFalse(stopped.isCompleted, "Late bytes cannot complete an unacknowledged stop")
                assertFalse(close.responded.get())
                assertEquals(mediaBeforeBoundary, p.native.rangeRequests(), "Stopping permits Close, but no additional media range")
                close.reply(TdApi.Ok())
                runCurrent()
                assertFalse(stopped.isCompleted, "Close Ok cannot replace the native CLOSED update")
                assertTrue(p.engine.state.value.clientActive)
                p.native.auth(AuthorizationPhase.CLOSED)
                await(p) { stopped.isCompleted }
                assertTrue(stopped.await().getOrThrow())
                assertTrue(close.responded.get())
                assertFalse(p.engine.state.value.clientActive)
                assertNull(p.engine.profileStickerAvatarMedia.value)
                assertFalse(p.native.session.chats.managesDownload(CURRENT_FILE_ID))
                assertEquals(1, p.native.commands.count { it is TdApi.Close })
            }

            val commandsBeforeStaleRequest = p.native.commands.size
            val mediaBeforeStaleRequest = p.native.rangeRequests()
            val staleResults = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            val stale = assertNotNull(p.environment).reader.savedPreview(request, p.previewVisible::get, staleResults::add)
            runCurrent()
            assertFalse(stale.requestCurrent()); assertTrue(staleResults.none { it.value != null })
            if (boundary == "stop-read" || boundary == "authorization-read")
                assertEquals(mediaBeforeStaleRequest, p.native.rangeRequests(), "Retired saved ownership cannot start new media")
            else assertEquals(commandsBeforeStaleRequest, p.native.commands.size, "An old epoch is rejected before any native dispatch")
            stale.close(); owner.close()
        }
    }

    private suspend fun TestScope.withPipeline(block: suspend TestScope.(Pipeline) -> Unit) {
        val native = NativeFixture()
        val engine = syntheticOpenEngine(native.store, native, StandardTestDispatcher(testScheduler))
        val p = Pipeline(native, engine)
        var primaryFailure: Throwable? = null
        try {
            assertTrue(engine.start()); runCurrent()
            native.auth(AuthorizationPhase.WAITING_PARAMETERS)
            await(p) { engine.state.value.proxy == ProxyStatus.DIRECT }
            native.auth(AuthorizationPhase.READY)
            await(p) { engine.profileStickerAvatarMedia.value != null && engine.state.value.settings.enabled }
            assertEquals(SELF_ID, engine.state.value.chats.account?.userId)
            assertIs<OfficialTdSettingsSession>(native.session.settings)
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE))
            await(p) { engine.state.value.settings.profile?.userId == SELF_ID }
            assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, SELF_ID, p.pageVisible::get))
            await(p) { engine.state.value.settings.profileExtras.data != null && !engine.state.value.settings.profileExtras.loading }
            p.parent = engine.state.value.settings.profileExtras.token
            p.bindArtwork(assertNotNull(engine.profileStickerAvatarMedia.value))
            block(p)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun recordCleanup(failure: Throwable) {
                val first = primaryFailure ?: cleanupFailure
                if (first == null) cleanupFailure = failure
                else if (first !== failure) first.addSuppressed(failure)
            }
            fun cleanup(step: () -> Unit) {
                try { step() } catch (failure: Throwable) { recordCleanup(failure) }
            }
            cleanup { p.closeArtwork() }
            cleanup { native.completeHeldCalls() }
            var confirmedClosed = false
            try {
                val closed = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.close() } }
                await(p) { closed.isCompleted }
                confirmedClosed = closed.await().getOrThrow()
                assertTrue(confirmedClosed)
            } catch (failure: Throwable) { recordCleanup(failure) }
            cleanup { settle(p) }
            cleanup {
                await(p) { p.pixels.used() == 0L }
                assertEquals(0L, p.pixels.used(), "UI/worker cleanup releases the actual Skia pixel reservations")
            }
            cleanup { assertTrue(native.calls.all { it.responded.get() }, "Every physical synthetic native callback reaches a terminal") }
            if (confirmedClosed) cleanup { native.delete() }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun TestScope.openCurrent(p: Pipeline): SettingsSavedProfileStickerAvatarRequest.Current {
        val epochs = mutableListOf<Long?>()
        assertTrue(p.engine.openSettingsCurrentProfilePhoto(p.parent, p.currentVisible::get, epochs::add))
        await(p) { p.engine.state.value.settings.profileExtras.currentPhoto.status == SettingsCurrentProfilePhotoStatus.READY }
        val current = p.engine.state.value.settings.profileExtras.currentPhoto
        assertEquals(assertNotNull(epochs.single()), current.epoch)
        val photo = assertNotNull(current.photo)
        assertEquals(CURRENT_PHOTO_ID, photo.photoId)
        assertEquals(SettingsProfilePhotoSticker.Type.CustomEmoji(CUSTOM_EMOJI_ID), photo.sticker?.type)
        assertEquals(SettingsProfilePhotoSticker.Background.Solid(0x123456), photo.sticker?.background)
        assertFalse(photo.hasAnimation); assertFalse(photo.invalidSticker)
        return SettingsSavedProfileStickerAvatarRequest.Current(p.parent, current.epoch, photo.photoId)
    }

    private fun TestScope.tick(p: Pipeline, deliverUi: Boolean = true) {
        runCurrent()
        while (true) p.native.replyDefault(p.native.queue.poll() ?: break)
        if (deliverUi) p.ui.drain()
        runCurrent()
    }

    private fun TestScope.await(p: Pipeline, deliverUi: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            tick(p, deliverUi)
            if (condition()) return
            check(System.nanoTime() < deadline) { "Synthetic saved-avatar pipeline did not reach its observable boundary" }
            Thread.sleep(2)
        } while (true)
    }

    private inline fun <reified T : TdApi.Function<*>> TestScope.next(p: Pipeline): NativeCall {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            runCurrent()
            val call = p.native.queue.poll()
            if (call != null) {
                if (call.command is T) return call
                p.native.replyDefault(call)
            } else {
                check(System.nanoTime() < deadline) { "Missing controlled native ${T::class.simpleName}" }
                Thread.sleep(2)
            }
        }
    }

    private fun TestScope.settle(p: Pipeline) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(180)
        do { tick(p); Thread.sleep(2) } while (System.nanoTime() < deadline)
    }

    /** Hold the actual serial scheduler so its 100 ms watchdog cannot hide the synchronous race. */
    private fun withDownloadSchedulerPaused(action: () -> Unit) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        DownloadBrokerScheduler.schedule(0) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Controlled broker barrier was not released" }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            action()
        } finally { release.countDown() }
    }

    private class Pipeline(val native: NativeFixture, val engine: TdEngine) {
        val pageVisible = AtomicBoolean(true)
        val currentVisible = AtomicBoolean(true)
        val historyVisible = AtomicBoolean(true)
        val previewVisible = AtomicBoolean(true)
        val ui = ControlledUi()
        val pixels = ProfileStickerAvatarPixelBudget()
        lateinit var parent: SettingsProfileExtrasToken
        var environment: ProfileStickerAvatarMediaEnvironment? = null
        lateinit var reader: ObservingReader
        private var workers: ProfileStickerAvatarArtworkWorkers? = null
        private var artwork: JvmProfileStickerAvatarArtworkProvider? = null
        val provider get() = checkNotNull(artwork)
        fun bindArtwork(environment: ProfileStickerAvatarMediaEnvironment) {
            this.environment = environment
            reader = ObservingReader(environment.reader)
            val ownedWorkers = ProfileStickerAvatarArtworkWorkers()
            workers = ownedWorkers
            artwork = JvmProfileStickerAvatarArtworkProvider(environment.generation, reader, ui,
                SkiaProfileStickerAvatarCodec(pixels = pixels, ffmpeg = { error("Generated static WEBP must not start a process") }), ownedWorkers)
        }
        fun closeArtwork() {
            previewVisible.set(false); artwork?.close(); ui.drain(); workers?.close(); ui.drain()
        }
    }

    /** Passive observation only. Every request, authority predicate and encoded object is delegated unchanged. */
    private class ObservingReader(private val delegate: ProfileStickerAvatarEncodedReader) : ProfileStickerAvatarEncodedReader by delegate {
        class Read {
            val native = AtomicReference<SettingsSavedProfileStickerAvatarLease?>()
            val encodedSource = AtomicReference<(() -> Boolean)?>()
            val demand = AtomicReference<SettingsSavedProfileStickerAvatarDemand?>()
            val closes = AtomicInteger()
        }
        val reads = CopyOnWriteArrayList<Read>()
        override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease =
            observe(null, result) { callback -> delegate.savedPreview(request, active, callback) }

        override fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, active: () -> Boolean,
            demand: SettingsSavedProfileStickerAvatarDemand,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit): SettingsSavedProfileStickerAvatarLease =
            observe(demand, result) { callback -> delegate.savedPreviewOwned(request, active, demand, callback) }

        private fun observe(demand: SettingsSavedProfileStickerAvatarDemand?,
            result: (SettingsResult<ProfileStickerAvatarEncoded>) -> Unit,
            submit: ((SettingsResult<ProfileStickerAvatarEncoded>) -> Unit) -> SettingsSavedProfileStickerAvatarLease,
        ): SettingsSavedProfileStickerAvatarLease {
            val observed = Read(); observed.demand.set(demand); reads += observed
            val native = submit { response ->
                response.value?.let { observed.encodedSource.set(it.sourceLease()) }
                result(response)
            }
            observed.native.set(native)
            val open = AtomicBoolean(true)
            return object : SettingsSavedProfileStickerAvatarLease {
                override fun requestCurrent() = native.requestCurrent()
                override fun revokeDemand() = native.revokeDemand()
                override fun close() { if (open.compareAndSet(true, false)) { observed.closes.incrementAndGet(); native.close() } }
            }
        }
    }

    private class ControlledUi : ProfileStickerAvatarUiDispatch {
        private val queue = ConcurrentLinkedQueue<Pair<() -> Unit, () -> Unit>>()
        override fun post(deliver: () -> Unit, discard: () -> Unit) { queue.add(deliver to discard) }
        fun size() = queue.size
        fun drain() { while (true) (queue.poll() ?: break).first() }
    }

    private class NativeCall(val command: TdApi.Function<*>, private val callback: (TdApi.Object) -> Unit,
        private val receiver: (() -> Unit) -> Unit) {
        val responded = AtomicBoolean()
        fun reply(value: TdApi.Object, before: () -> Unit = {}) {
            check(responded.compareAndSet(false, true)) { "A physical controlled response was already delivered" }
            receiver { try { before() } finally { callback(value) } }
        }
    }

    private class NativeFixture : TdTransport {
        val root: Path = Files.createTempDirectory("saved-avatar-pipeline-").toRealPath()
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val bytes = syntheticWebp()
        private val asset = root.resolve("generated.webp")
        private val receiverThread = Thread.currentThread().id
        private val receiving = AtomicBoolean()
        private val sends = Any()
        private val ready = AtomicBoolean()
        private val gate = TdUserInteractionGate()
        private val suggestions = TdLoginEmailSuggestions(gate)
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        lateinit var session: OfficialTdSession
        val queue = ConcurrentLinkedQueue<NativeCall>()
        val calls = CopyOnWriteArrayList<NativeCall>()
        val commands = CopyOnWriteArrayList<TdApi.Function<*>>()
        var currentColor = 0x123456
        var currentDownloaded = true
        init {
            Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n")
            Files.write(asset, bytes)
        }
        override fun load() = NativeInfo("synthetic-no-DLL", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit): TdSession {
            authorization = onAuthorization
            session = OfficialTdSession(offline = true, interactionGate = gate,
                loginEmailSuggestions = suggestions, isAuthorizationReady = ready::get,
                send = { command, result, _ ->
                    val call = NativeCall(command, result, ::receive)
                    synchronized(sends) { commands += command; calls += call; queue.add(call) }
                })
            return session
        }
        private fun receive(action: () -> Unit) {
            check(Thread.currentThread().id == receiverThread) { "All native callbacks belong to one controlled ResponseReceiver" }
            check(receiving.compareAndSet(false, true)) { "Native callbacks cannot overlap or reenter" }
            try { action() } finally { receiving.set(false) }
        }
        fun auth(phase: AuthorizationPhase) = receive {
            ready.set(phase == AuthorizationPhase.READY); authorization(AuthorizationDetails(phase))
        }
        fun updateCurrent() = receive {
            (session.settings as OfficialTdSettingsSession).accept(TdApi.UpdateUserFullInfo(SELF_ID, full()))
        }
        fun requireLoginEmail() = receive {
            suggestions.accept(TdApi.UpdateSuggestedActions(arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
        }
        fun byteReads() = commands.count { it is TdApi.ReadFilePart }
        fun rangeRequests() = commands.count { it is TdApi.GetFileDownloadedPrefixSize || it is TdApi.DownloadFile || it is TdApi.ReadFilePart }
        fun completeHeldCalls() {
            var first: Throwable? = null
            calls.filter { !it.responded.get() }.forEach { call ->
                try { replyDefault(call) } catch (failure: Throwable) {
                    val previous = first
                    if (previous == null) first = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            first?.let { throw it }
        }
        fun replyDefault(call: NativeCall) {
            if (call.responded.get()) return
            val response: TdApi.Object = when (val command = call.command) {
                is TdApi.GetMe -> self()
                is TdApi.GetUserFullInfo -> { check(command.userId == SELF_ID); full() }
                is TdApi.GetOption -> if (command.name == "bio_length_max") TdApi.OptionValueInteger(4096) else TdApi.OptionValueEmpty()
                is TdApi.GetUserProfilePhotos -> {
                    check(command.userId == SELF_ID && command.limit == HISTORY_PAGE_SIZE)
                    if (command.offset == 0) TdApi.ChatPhotos(HISTORY_PAGE_SIZE + 1, Array(HISTORY_PAGE_SIZE) { index ->
                        if (index == HISTORY_RAW_OFFSET) historyPhoto() else TdApi.ChatPhoto().apply {
                            id = 1_000L + index; sizes = emptyArray()
                        }
                    }) else {
                        check(command.offset == HISTORY_PAGE_SIZE)
                        TdApi.ChatPhotos(HISTORY_PAGE_SIZE + 1, arrayOf(historyPhoto(colorChanged = true)))
                    }
                }
                is TdApi.GetCustomEmojiStickers -> {
                    check(command.customEmojiIds.contentEquals(longArrayOf(CUSTOM_EMOJI_ID)))
                    TdApi.Stickers(arrayOf(sticker(CURRENT_FILE_ID, 18, 0, TdApi.StickerFullTypeCustomEmoji(CUSTOM_EMOJI_ID, false))))
                }
                is TdApi.GetStickerSet -> {
                    check(command.setId == SET_ID)
                    TdApi.StickerSet().apply {
                        id = SET_ID; isInstalled = false; isArchived = true; stickerType = TdApi.StickerTypeRegular()
                        stickers = arrayOf(sticker(HISTORY_FILE_ID, STICKER_ID, SET_ID, TdApi.StickerFullTypeRegular()))
                    }
                }
                is TdApi.GetFileDownloadedPrefixSize -> {
                    check(command.fileId == CURRENT_FILE_ID || command.fileId == HISTORY_FILE_ID)
                    TdApi.FileDownloadedPrefixSize((bytes.size.toLong() - command.offset).coerceAtLeast(0))
                }
                is TdApi.ReadFilePart -> {
                    check(command.fileId == CURRENT_FILE_ID || command.fileId == HISTORY_FILE_ID)
                    val offset = command.offset.toInt(); val end = minOf(bytes.size.toLong(), command.offset + command.count).toInt()
                    check(offset in 0 until end); TdApi.Data(bytes.copyOfRange(offset, end))
                }
                is TdApi.GetFile -> file(command.fileId)
                is TdApi.DownloadFile -> file(command.fileId).apply { local.isDownloadingActive = true }
                is TdApi.GetProxies -> TdApi.AddedProxies(emptyArray())
                is TdApi.LoadChats -> TdApi.Error(404, "SYNTHETIC_EMPTY_LIST")
                is TdApi.SetOption -> {
                    // Real notification bootstrap, with this fixture's fresh disabled defaults.
                    val value = (command.value as? TdApi.OptionValueInteger)?.value
                    check(when (command.name) {
                        "notification_group_size_max" -> value == 10L
                        "notification_group_count_max" -> value == 0L
                        else -> false
                    }) { "Unexpected synthetic notification option ${command.name}" }
                    TdApi.Ok()
                }
                is TdApi.SetNetworkType, is TdApi.SetTdlibParameters, is TdApi.DisableProxy,
                is TdApi.CancelDownloadFile, is TdApi.Close -> TdApi.Ok()
                else -> {
                    // Unrelated startup read capabilities are deliberately unavailable. No write fallback.
                    check(command.javaClass.simpleName.startsWith("Get")) { "Unexpected synthetic native write ${command.javaClass.simpleName}" }
                    TdApi.Error(404, "SYNTHETIC_UNAVAILABLE_READ")
                }
            }
            call.reply(response)
            if (call.command is TdApi.Close) auth(AuthorizationPhase.CLOSED)
        }
        private fun self() = TdApi.User().apply {
            id = SELF_ID; firstName = "Synthetic"; lastName = ""; phoneNumber = ""; type = TdApi.UserTypeRegular()
            profilePhoto = TdApi.ProfilePhoto().apply { id = CURRENT_PHOTO_ID }
        }
        private fun full() = TdApi.UserFullInfo().apply {
            photo = TdApi.ChatPhoto().apply {
                id = CURRENT_PHOTO_ID; sizes = emptyArray()
                sticker = TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeCustomEmoji(CUSTOM_EMOJI_ID), TdApi.BackgroundFillSolid(currentColor))
            }
        }
        fun historyPhoto(colorChanged: Boolean = false) = TdApi.ChatPhoto().apply {
            id = HISTORY_PHOTO_ID; sizes = emptyArray()
            sticker = TdApi.ChatPhotoSticker(TdApi.ChatPhotoStickerTypeRegularOrMask(SET_ID, STICKER_ID),
                TdApi.BackgroundFillGradient(if (colorChanged) 0x112234 else 0x112233, 0x445566, 135))
        }
        private fun sticker(fileId: Int, stickerId: Long, set: Long, type: TdApi.StickerFullType) = TdApi.Sticker().apply {
            id = stickerId; setId = set; width = 8; height = 4; format = TdApi.StickerFormatWebp(); fullType = type
            sticker = file(fileId)
        }
        private fun file(fileId: Int) = TdApi.File().apply {
            check(fileId == CURRENT_FILE_ID || fileId == HISTORY_FILE_ID)
            id = fileId; size = bytes.size.toLong(); expectedSize = size
            local = TdApi.LocalFile().apply {
                path = asset.toString(); canBeDownloaded = true; canBeDeleted = true
                isDownloadingCompleted = fileId != CURRENT_FILE_ID || currentDownloaded
                downloadedSize = if (isDownloadingCompleted) bytes.size.toLong() else 0L
                downloadedPrefixSize = downloadedSize
            }
            remote = TdApi.RemoteFile().apply { uniqueId = "generated-webp-$fileId" }
        }
        fun delete() {
            val parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            val checked = root.toRealPath()
            check(checked.parent == parent && checked.fileName.toString().startsWith("saved-avatar-pipeline-"))
            Files.walk(checked).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(checked)); Files.deleteIfExists(path)
            } }
        }
    }

    private companion object {
        const val SELF_ID = 43L
        const val CURRENT_PHOTO_ID = 90L
        const val HISTORY_PHOTO_ID = 91L
        const val CUSTOM_EMOJI_ID = -700L
        const val SET_ID = -10L
        const val STICKER_ID = -20L
        const val CURRENT_FILE_ID = 3456
        const val HISTORY_FILE_ID = 4567
        const val HISTORY_RAW_OFFSET = 7
        const val HISTORY_PAGE_SIZE = 32
        fun smokeOnly() = assumeTrue("Run generated saved-avatar/Skia pipeline with -PmediaSmoke",
            !System.getProperty("nnggram.ffmpeg.path").isNullOrBlank())
        fun syntheticWebp(): ByteArray {
            val rgba = ByteArray(8 * 4 * 4) { index -> when (index % 4) {
                0 -> 255; 1 -> 64; 2 -> 16; else -> 255
            }.toByte() }
            val info = ImageInfo(8, 4, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)
            return Image.makeRaster(info, rgba, 32).use { image ->
                checkNotNull(image.encodeToData(EncodedImageFormat.WEBP)).use { it.bytes }
            }
        }
        fun ImageBitmap.pixel(x: Int, y: Int) = IntArray(width * height).also { readPixels(it) }[y * width + x]
        fun assertColorNear(expected: Int, actual: Int, tolerance: Int) {
            for (shift in listOf(24, 16, 8, 0)) assertTrue(
                abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= tolerance,
                "Unexpected generated WEBP pixel channel at shift $shift")
        }
    }
}
