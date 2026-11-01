package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Draft, fake-session tests. No client, native RPC, paths, account or production mutation. */
class OfficialProfileEmojiArtworkSourceTest {
    @Test fun generationAccountAndMalformedRequestsAreRejectedBeforeMetadataOrFiles() {
        Fixture().use { f ->
            val cases = listOf(
                f.token().copy(generation = 9) to SettingsError.INVALID_VALUE,
                f.token().copy(accountUserId = 999) to SettingsError.NOT_READY,
                f.token().copy(ownerId = 0) to SettingsError.INVALID_VALUE,
                f.token().copy(revision = -1) to SettingsError.INVALID_VALUE,
                f.token().copy(customEmojiId = 0) to SettingsError.INVALID_VALUE,
            )
            cases.forEach { (token, error) ->
                val result = Results()
                f.source.acquire(token, f.emoji, f.request(), { true }, result::add)
                assertEquals(error, result.only().error)
            }
            listOf(0, 513).forEach { edge ->
                val result = Results()
                f.source.acquire(f.token(), f.emoji, f.request(edge), { true }, result::add)
                assertEquals(SettingsError.INVALID_VALUE, result.only().error)
            }
            assertTrue(f.actor.isEmpty())
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.files.calls.isEmpty())
            assertTrue(f.decoder.calls.isEmpty())
        }
    }

    @Test fun coldNullExpectedResolvesOnlyTheSelectedNativeEmojiAndUsesItsRealThumbnail() {
        Fixture().use { f ->
            val result = Results()
            f.source.acquire(f.token(), null, f.request(), { true }, result::add)
            f.until { result.isNotEmpty() }
            val artwork = assertNotNull(result.only().value)
            assertNotNull(artwork.image)
            assertEquals(listOf(43L), f.catalog.loads)
            assertEquals(listOf(listOf(f.emoji.customEmojiId)), f.catalog.resolves)
            val read = f.files.calls.single()
            assertEquals(ProfileEmojiFileRole.THUMBNAIL, read.role)
            assertEquals(ProfileEmojiResourceDomain.OPTION, read.token.domain)
            assertEquals(10L, read.token.generation)
            assertEquals(43L, read.token.accountUserId)
            assertEquals(f.emoji, read.expected)
            assertTrue(read.maximumBytes in 1..24 * 1024 * 1024)
            assertEquals(read.role, f.decoder.calls.single().encoded.role)
            assertTrue(artwork.isAlphaMask, "A native repaintable option uses the actual alpha mask.")
        }
    }

    @Test fun profileReplyAndGiftRolesReadFullArtworkWithSeparateResourceDomains() {
        for ((role, domain, mask) in listOf(
            Triple(ProfileEmojiArtworkRole.PROFILE_MASK, ProfileEmojiResourceDomain.PROFILE, true),
            Triple(ProfileEmojiArtworkRole.REPLY_MASK, ProfileEmojiResourceDomain.REPLY, true),
            Triple(ProfileEmojiArtworkRole.GIFT, ProfileEmojiResourceDomain.REPLY, false),
        )) Fixture().use { f ->
            val result = Results()
            f.source.acquire(f.token(), f.emoji, f.request(role = role), { true }, result::add)
            f.until { result.isNotEmpty() }
            assertNotNull(result.only().value)
            assertEquals(ProfileEmojiFileRole.FULL, f.files.calls.single().role)
            assertEquals(domain, f.files.calls.single().token.domain)
            assertEquals(mask, f.decoder.calls.single().alphaMask)
        }
    }

    @Test fun mismatchedNativeAccountOrEmojiFingerprintCannotReachFileRead() {
        Fixture().use { f ->
            f.catalog.userId = 999
            val result = f.acquire()
            f.until { result.isNotEmpty() }
            assertEquals(SettingsError.CONFLICT, result.only().error)
            assertTrue(f.files.calls.isEmpty())
        }
        Fixture().use { f ->
            f.catalog.emoji = f.emoji.copy(sticker = f.emoji.sticker.copy(setId = 987))
            val result = f.acquire()
            f.until { result.isNotEmpty() }
            assertEquals(SettingsError.CONFLICT, result.only().error)
            assertTrue(f.files.calls.isEmpty())
        }
    }

    @Test fun nativeFileIdentityAndSanitizationAreCheckedBeforeDecode() {
        val invalid: List<(ProfileEmojiEncoded) -> ProfileEmojiEncoded> = listOf(
            { value -> ProfileEmojiEncoded(value.emoji, value.role, value.file.copy(uniqueId = "other"), value.bytes()) },
            { value -> ProfileEmojiEncoded(value.emoji, value.role, value.file.copy(localPath = "forbidden-native-path"), value.bytes()) },
            { value -> ProfileEmojiEncoded(value.emoji, value.role, value.file.copy(size = value.size + 1L), value.bytes()) },
            { value -> ProfileEmojiEncoded(value.emoji, ProfileEmojiFileRole.FULL, value.file, value.bytes()) },
        )
        invalid.forEach { transform -> Fixture().use { f ->
            f.files.transform = transform
            val result = f.acquire()
            f.until { result.isNotEmpty() }
            assertEquals(SettingsError.CONFLICT, result.only().error)
            assertTrue(f.decoder.calls.isEmpty())
        } }
    }

    @Test fun inactiveOutputOrChangedAccountRetiresQueuedAdmissionsWithoutMetadata() {
        for (gate in 0..2) Fixture().use { f ->
            var active = true
            val result = Results()
            f.source.acquire(f.token(), f.emoji, f.request(), { active }, result::add)
            when (gate) { 0 -> active = false; 1 -> f.outputAllowed = false; else -> f.account = 999 }
            f.until { result.isNotEmpty() }
            assertEquals(SettingsError.NOT_READY, result.only().error)
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.files.calls.isEmpty())
        }
    }

    @Test fun discardedActorAdmissionIsTerminalEvenIfItsWorkIsLaterInvoked() {
        Fixture().use { f ->
            val result = f.acquire()
            val admitted = f.takeAdmission()
            admitted.discard()
            admitted.work()
            assertEquals(SettingsError.NOT_READY, result.only().error)
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.files.calls.isEmpty())
            assertTrue(f.decoder.calls.isEmpty())
        }
    }

    @Test fun higherOwnerRevisionRevokesOldLeaseAndRejectsLateFileCompletion() {
        Fixture().use { f ->
            f.files.autoComplete = false
            val old = Results()
            f.source.acquire(f.token(), f.emoji, f.request(), { true }, old::add)
            f.drain()
            val oldRead = f.files.calls.single()
            val newer = Results()
            f.source.acquire(f.token().copy(revision = 2), f.emoji, f.request(), { true }, newer::add)
            f.drain()
            assertEquals(SettingsError.NOT_READY, old.only().error)
            assertTrue(oldRead.closed.get() > 0)
            assertFalse(oldRead.active())
            oldRead.complete()
            f.files.calls.last().complete()
            f.until { newer.isNotEmpty() }
            assertNotNull(newer.only().value?.image)
            assertEquals(1, old.size)
            assertEquals(1, f.decoder.calls.size)
            val stale = Results()
            f.source.acquire(f.token(), f.emoji, f.request(), { true }, stale::add)
            assertEquals(SettingsError.CONFLICT, stale.only().error)
        }
    }

    @Test fun liveOldArtworkIsImmediatelyRevokedWhenItsOwnerRevisionChanges() {
        Fixture().use { f ->
            val first = f.acquire()
            f.until { first.isNotEmpty() }
            val oldArtwork = assertNotNull(first.only().value)
            assertNotNull(oldArtwork.image)
            val second = Results()
            f.source.acquire(f.token().copy(revision = 2), f.emoji, f.request(), { true }, second::add)
            assertNull(oldArtwork.image)
            f.until { second.isNotEmpty() }
            assertNotNull(second.only().value?.image)
        }
    }

    @Test fun identicalRevisionCannotReplaceTheExpectedIdentity() {
        Fixture().use { f ->
            val first = f.acquire()
            f.until { first.isNotEmpty() }
            val result = Results()
            val changed = f.emoji.copy(needsRepainting = false)
            f.source.acquire(f.token(), changed, f.request(), { true }, result::add)
            assertEquals(SettingsError.CONFLICT, result.only().error)
            assertNotNull(first.only().value?.image)
        }
    }

    @Test fun repeatedAnimationFramesReuseMetadataAndEncodedBytesWithoutPerFrameRequests() {
        Fixture(format = MediaFormat.TGS).use { f ->
            val token = f.token()
            val frames = mutableListOf<ProfileEmojiArtworkLease>()
            try {
                for (time in listOf(0L, 33L, 0L)) {
                    val result = Results()
                    frames += f.source.acquire(token, null,
                        f.request(role = ProfileEmojiArtworkRole.PROFILE_MASK, time = time), { true }, result::add)
                    f.until { result.isNotEmpty() }
                    assertNotNull(result.only().value?.image)
                }
                assertEquals(1, f.catalog.loads.size)
                assertEquals(1, f.catalog.resolves.size)
                assertEquals(1, f.files.calls.size)
                assertEquals(listOf(0L, 33L), f.decoder.calls.map { it.request.frameTimeMillis })
            } finally { frames.forEach(ProfileEmojiArtworkLease::close) }
        }
    }

    @Test fun independentLeasesSharePreparedPixelsAndOneCloseCannotInvalidateTheOther() {
        Fixture().use { f ->
            val first = Results()
            val firstLease = f.source.acquire(f.token(), f.emoji, f.request(), { true }, first::add)
            f.until { first.isNotEmpty() }
            val second = Results()
            val secondLease = f.source.acquire(f.token().copy(ownerId = 6), f.emoji, f.request(), { true }, second::add)
            f.until { second.isNotEmpty() }
            val firstArtwork = assertNotNull(first.only().value)
            val secondArtwork = assertNotNull(second.only().value)
            assertNotSame(firstArtwork, secondArtwork)
            assertSame(firstArtwork.image, secondArtwork.image)
            assertEquals(1, f.decoder.calls.size)
            assertEquals(1, f.files.calls.size)
            firstLease.close()
            firstLease.close()
            assertNull(firstArtwork.image)
            assertNotNull(secondArtwork.image)
            assertFalse(f.decoder.bitmaps.single().isClosed)
            secondLease.close()
            f.source.close()
            assertTrue(f.decoder.bitmaps.single().isClosed)
            assertEquals(0, f.catalog.retired)
            assertEquals(0, f.files.retired)
        }
    }

    @Test fun nativeDuplicateCallbacksCannotDecodeOrPublishTwice() {
        Fixture().use { f ->
            f.files.autoComplete = false
            val result = f.acquire()
            f.drain()
            val read = f.files.calls.single()
            read.complete()
            read.complete()
            f.until { result.isNotEmpty() }
            f.drain()
            assertEquals(1, result.size)
            assertEquals(1, f.decoder.calls.size)
        }
    }

    @Test fun identicalNativeIdentitiesCannotReuseAnotherAccountsEncodedOrPreparedArtwork() {
        Fixture().use { f ->
            val first = f.acquire()
            f.until { first.isNotEmpty() }
            val firstArtwork = assertNotNull(first.only().value)
            val firstImage = assertNotNull(firstArtwork.image)
            f.account = 999
            f.catalog.userId = 999
            val second = Results()
            f.source.acquire(f.token().copy(accountUserId = 999), f.emoji, f.request(), { true }, second::add)
            f.until { second.isNotEmpty() }
            assertNull(firstArtwork.image)
            assertNotSame(firstImage, assertNotNull(second.only().value?.image))
            assertEquals(listOf(43L, 999L), f.catalog.loads)
            assertEquals(listOf(43L, 999L), f.files.calls.map { it.token.accountUserId },
                "Even colliding file/custom identities require the current account's own files authority.")
            assertEquals(2, f.decoder.calls.size)
        }
    }

    @Test fun concurrentSubscribersShareNativeReadAndDecodeAfterOriginalSubscriberCloses() {
        Fixture().use { f ->
            f.files.autoComplete = false
            val results = List(9) { Results() }
            val leases = results.mapIndexed { index, result ->
                f.source.acquire(f.token().copy(ownerId = index + 1L), f.emoji, f.request(), { true }, result::add)
            }
            try {
                f.drain()
                val read = f.files.calls.single()
                leases.first().close()
                assertEquals(SettingsError.NOT_READY, results.first().only().error)
                assertTrue(read.active(), "Surviving subscribers retain the actual native read.")
                assertEquals(0, read.closed.get())
                read.complete()
                f.until { results.drop(1).all { it.isNotEmpty() } }
                val artwork = results.drop(1).map { assertNotNull(it.only().value) }
                val image = assertNotNull(artwork.first().image)
                assertTrue(artwork.all { it.image === image })
                assertEquals(1, f.files.calls.size)
                assertEquals(1, f.decoder.calls.size)
            } finally { leases.forEach(ProfileEmojiArtworkLease::close) }
        }
    }

    @Test fun nativeCompletionQueuedBeforeLastCloseCannotPopulateTheCacheAfterRetirement() {
        Fixture().use { f ->
            f.files.autoComplete = false
            val old = Results()
            val oldLease = f.source.acquire(f.token(), f.emoji, f.request(), { true }, old::add)
            f.drain()
            f.files.calls.single().complete()
            oldLease.close()
            f.drain()
            assertEquals(SettingsError.NOT_READY, old.only().error)
            assertTrue(f.decoder.calls.isEmpty())
            val retry = f.acquire()
            f.drain()
            assertEquals(2, f.files.calls.size, "Retired native completion must not authorize a later acquisition.")
            assertTrue(retry.isEmpty())
            f.files.calls.last().complete()
            f.until { retry.isNotEmpty() }
            assertNotNull(retry.only().value?.image)
        }
    }

    @Test fun discardedWorkerPublicationClosesItsLateBitmapAndCompletesOnlyOnce() {
        Fixture().use { f ->
            f.decoder.block = true
            val result = f.acquire()
            f.drain()
            assertTrue(f.decoder.started.await(5, TimeUnit.SECONDS))
            f.decoder.release.countDown()
            val publication = f.takeAdmission()
            publication.discard()
            publication.work()
            assertEquals(SettingsError.NOT_READY, result.only().error)
            assertTrue(f.decoder.bitmaps.single().isClosed)
            assertEquals(1, result.size)
        }
    }

    @Test fun closeDuringAnUncooperativeDecodeCannotRevivePixelsOnLateCompletion() {
        Fixture().use { f ->
            f.decoder.block = true
            val result = f.acquire()
            f.drain()
            assertTrue(f.decoder.started.await(5, TimeUnit.SECONDS))
            f.source.close()
            assertEquals(SettingsError.NOT_READY, result.only().error)
            f.decoder.release.countDown()
            f.until { f.decoder.bitmaps.isNotEmpty() && f.decoder.bitmaps.all { it.isClosed } }
            assertNull(result.only().value)
            assertEquals(1, result.size)
            assertTrue(f.files.calls.all { it.closed.get() > 0 })
        }
    }

    @Test fun invalidDecoderGeometryIsClosedAndItsFailureIsMemoized() {
        Fixture().use { f ->
            f.decoder.wrongEdge = true
            val first = f.acquire()
            f.until { first.isNotEmpty() }
            assertEquals(SettingsError.INVALID_VALUE, first.only().error)
            assertTrue(f.decoder.bitmaps.single().isClosed)
            val second = f.acquire()
            f.until { second.isNotEmpty() }
            assertEquals(SettingsError.INVALID_VALUE, second.only().error)
            assertEquals(1, f.decoder.calls.size)
            assertEquals(1, f.files.calls.size)
        }
    }

    @Test fun explicitNewRevisionRetriesANegativeDecodeWithoutReusingItsFailure() {
        Fixture().use { f ->
            f.decoder.wrongEdge = true
            val failed = f.acquire()
            f.until { failed.isNotEmpty() }
            assertEquals(SettingsError.INVALID_VALUE, failed.only().error)
            f.decoder.wrongEdge = false
            val retry = Results()
            f.source.acquire(f.token().copy(revision = 2), f.emoji, f.request(), { true }, retry::add)
            f.until { retry.isNotEmpty() }
            assertNotNull(retry.only().value?.image)
            assertEquals(2, f.decoder.calls.size, "Explicit owner revision retries the asset instead of keeping a permanent failure.")
            assertTrue(f.decoder.bitmaps.first().isClosed)
            assertFalse(f.decoder.bitmaps.last().isClosed)
        }
    }

    @Test fun heldFramesRemainChargedToThePhysicalPixelBudgetUntilLeaseClose() {
        Fixture(format = MediaFormat.TGS).use { f ->
            val leases = mutableListOf<ProfileEmojiArtworkLease>()
            val values = mutableListOf<ProfileEmojiArtwork>()
            try {
                repeat(16) { index ->
                    val result = Results()
                    leases += f.source.acquire(f.token(), f.emoji,
                        f.request(512, ProfileEmojiArtworkRole.PROFILE_MASK, index * 33L), { true }, result::add)
                    f.until { result.isNotEmpty() }
                    values += assertNotNull(result.only().value)
                }
                val overflow = Results()
                f.source.acquire(f.token(), f.emoji,
                    f.request(512, ProfileEmojiArtworkRole.PROFILE_MASK, 16 * 33L), { true }, overflow::add)
                f.until { overflow.isNotEmpty() }
                assertEquals(SettingsError.RATE_LIMITED, overflow.only().error)
                assertTrue(values.all { it.image != null }, "Eviction cannot release pixels still owned by live leases.")
                assertEquals(16, f.decoder.calls.size)
                leases.removeAt(0).close()
                assertTrue(f.decoder.bitmaps.first().isClosed)
                val retry = Results()
                leases += f.source.acquire(f.token(), f.emoji,
                    f.request(512, ProfileEmojiArtworkRole.PROFILE_MASK, 16 * 33L), { true }, retry::add)
                f.until { retry.isNotEmpty() }
                assertNotNull(retry.only().value?.image)
            } finally { leases.forEach(ProfileEmojiArtworkLease::close) }
        }
    }

    @Test fun fullNativeAssetAboveTwelveMiBWithinTheEncodedBudgetCanDecode() {
        Fixture(size = 13 * 1024 * 1024).use { f ->
            val result = Results()
            f.source.acquire(f.token(), f.emoji, f.request(role = ProfileEmojiArtworkRole.GIFT), { true }, result::add)
            f.until { result.isNotEmpty() }
            assertNotNull(result.only().value?.image)
            assertEquals(ProfileEmojiFileRole.FULL, f.files.calls.single().role)
            assertEquals(13 * 1024 * 1024, f.decoder.calls.single().encoded.size)
            assertEquals(1, f.decoder.calls.size)
        }
    }

    @Test fun ninthNativeReadWaitsAndProgressesWhenAnEarlierLeaseCloses() {
        Fixture().use { f ->
            f.files.autoComplete = false
            val results = List(9) { Results() }
            val emojis = List(9) { index -> f.emoji.copy(customEmojiId = 100L + index,
                sticker = f.emoji.sticker.copy(id = 1000L + index,
                    media = f.emoji.sticker.media.copy(
                        file = f.emoji.sticker.media.file.copy(id = 100 + index, uniqueId = "full-$index"),
                        thumbnail = f.emoji.sticker.media.thumbnail?.copy(id = 200 + index, uniqueId = "thumb-$index"),
                    ))) }
            emojis.forEach { f.catalog.byId[it.customEmojiId] = it }
            val leases = results.mapIndexed { index, result ->
                f.source.acquire(f.token().copy(ownerId = index + 1L, customEmojiId = emojis[index].customEmojiId),
                    emojis[index], f.request(), { true }, result::add)
            }
            try {
                f.drain()
                assertEquals(8, f.files.calls.size)
                assertTrue(results.last().isEmpty(), "The ninth read waits rather than failing permanently.")
                leases.first().close()
                f.drain()
                assertEquals(9, f.files.calls.size)
                f.files.calls.last().complete()
                f.until { results.last().isNotEmpty() }
                assertNotNull(results.last().only().value?.image)
            } finally { leases.forEach(ProfileEmojiArtworkLease::close) }
        }
    }

    private class Results : CopyOnWriteArrayList<SettingsResult<ProfileEmojiArtwork>>() {
        fun only(): SettingsResult<ProfileEmojiArtwork> = single()
    }

    private data class Admission(val work: () -> Unit, val discard: () -> Unit)

    private class Fixture(format: MediaFormat = MediaFormat.WEBP, size: Int = 16) : AutoCloseable {
        val emoji = sampleEmoji(format, size)
        val catalog = Catalog(emoji)
        val files = Files()
        val decoder = Decoder()
        val actor = LinkedBlockingQueue<Admission>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var account = 43L
        var outputAllowed = true
        val source = OfficialProfileEmojiArtworkSource(10, catalog, files, scope, { account }, { outputAllowed },
            { work, discard -> actor.add(Admission(work, discard)) }, decoder)
        fun token() = ProfileEmojiArtworkToken(10, 43, 5, 1, emoji.customEmojiId)
        fun request(edge: Int = 16, role: ProfileEmojiArtworkRole = ProfileEmojiArtworkRole.OPTION_THUMBNAIL,
            time: Long = 0) = ProfileEmojiArtworkRequest(role, edge, time)
        fun acquire(): Results = Results().also { source.acquire(token(), emoji, request(), { true }, it::add) }
        fun takeAdmission(): Admission = assertNotNull(actor.poll(5, TimeUnit.SECONDS), "Expected bounded actor progress")
        fun drain() {
            repeat(10_000) { (actor.poll() ?: return).work() }
            error("Unbounded synchronous actor work")
        }
        fun until(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!done()) {
                val remaining = deadline - System.nanoTime()
                assertTrue(remaining > 0, "Timed out awaiting bounded fake-source progress")
                actor.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)?.work?.invoke()
            }
        }
        override fun close() {
            decoder.release.countDown()
            source.close()
            scope.cancel()
            while (true) (actor.poll() ?: break).discard()
        }
    }

    private class Catalog(var emoji: SettingsBackgroundEmoji) : TdSettingsProfileColorsSession {
        var userId = 43L
        var retired = 0
        val loads = mutableListOf<Long>()
        val resolves = mutableListOf<List<Long>>()
        val byId = mutableMapOf<Long, SettingsBackgroundEmoji>()
        override fun observe(observer: (() -> Unit)?) = error("Source must not take the catalog observer")
        override fun catalog() = error("Selected metadata must use bounded native resolution")
        override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) {
            loads += userId
            assertTrue(active())
            result(SettingsResult(SettingsProfileColorsData(this.userId, true,
                SettingsProfileColorsValues(SettingsProfileColorValue(0), SettingsProfileColorValue(0)),
                SettingsProfileColorsCatalog())))
        }
        override fun resolveEmojis(ids: List<Long>, active: () -> Boolean,
            result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            resolves += ids.toList()
            assertTrue(active())
            result(SettingsResult(ids.map { byId[it] ?: emoji }))
        }
        override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) =
            error("Artwork resolution must not enumerate unrelated emoji")
        override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart,
            desired: SettingsProfileColorValue, replaceGift: Boolean, active: () -> Boolean,
            result: (SettingsProfileColorMutationResult) -> Unit) = error("No mutation is authorized by artwork display")
        override fun retire() { retired++ }
    }

    private class Files : TdProfileEmojiFilesSession {
        inner class Read(val token: ProfileEmojiResourceToken, val expected: SettingsBackgroundEmoji,
            val role: ProfileEmojiFileRole, val maximumBytes: Int, val active: () -> Boolean,
            val result: (SettingsResult<ProfileEmojiEncoded>) -> Unit) : ProfileEmojiFileLease {
            val closed = AtomicInteger()
            fun complete() {
                val file = if (role == ProfileEmojiFileRole.FULL) expected.sticker.media.file
                    else assertNotNull(expected.sticker.media.thumbnail)
                result(SettingsResult(transform(ProfileEmojiEncoded(expected, role, file, ByteArray(file.size.toInt()) { 7 }))))
            }
            override fun close() { closed.incrementAndGet() }
        }
        var autoComplete = true
        var retired = 0
        var transform: (ProfileEmojiEncoded) -> ProfileEmojiEncoded = { it }
        val calls = mutableListOf<Read>()
        override fun read(token: ProfileEmojiResourceToken, expected: SettingsBackgroundEmoji, role: ProfileEmojiFileRole,
            maximumBytes: Int, active: () -> Boolean, result: (SettingsResult<ProfileEmojiEncoded>) -> Unit): ProfileEmojiFileLease {
            val call = Read(token, expected, role, maximumBytes, active, result)
            calls += call
            if (autoComplete) call.complete()
            return call
        }
        override fun retire() { retired++ }
    }

    private class Decoder : ProfileEmojiArtworkDecoder {
        data class Call(val encoded: ProfileEmojiArtworkInput, val request: ProfileEmojiArtworkRequest, val alphaMask: Boolean)
        val calls = CopyOnWriteArrayList<Call>()
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var block = false
        @Volatile var wrongEdge = false
        override fun decode(encoded: ProfileEmojiArtworkInput, request: ProfileEmojiArtworkRequest,
            alphaMask: Boolean, active: () -> Boolean): PreparedProfileEmojiFrame {
            calls += Call(encoded, request, alphaMask)
            started.countDown()
            if (block) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (release.count != 0L && System.nanoTime() < deadline) {
                    try { release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* Simulate a late codec. */ }
                }
            }
            val edge = request.physicalSizePx - if (wrongEdge) 1 else 0
            val bitmap = Surface.makeRasterN32Premul(edge, edge).use { surface ->
                surface.canvas.clear(0xff2070c0.toInt())
                surface.makeImageSnapshot().use { Bitmap.makeFromImage(it) }
            }
            bitmaps += bitmap
            val format = if (encoded.role == ProfileEmojiFileRole.FULL) encoded.emoji.sticker.media.format
                else encoded.emoji.sticker.media.thumbnailFormat
            val animated = format == MediaFormat.TGS || format == MediaFormat.WEBM
            return PreparedProfileEmojiFrame(bitmap, alphaMask, animated, if (animated) 1000L else 0L, if (animated) 33L else 0L)
        }
    }

    private companion object {
        fun sampleEmoji(format: MediaFormat, size: Int): SettingsBackgroundEmoji {
            val file = MediaFile(7, size.toLong(), size.toLong(), uniqueId = "synthetic-full")
            val thumb = MediaFile(8, 8, 8, uniqueId = "synthetic-thumbnail")
            return SettingsBackgroundEmoji(55, StickerItem(700, 77, "x", MessageMedia(
                file, thumb, 128, 96, format, thumbnailFormat = MediaFormat.WEBP,
            )), needsRepainting = true)
        }
    }
}
