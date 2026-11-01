package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Fake native metadata, file broker and clock only; no account, client or filesystem access. */
class OfficialTdProfileEmojiFilesTest {
    @Test fun readsTheRequestedRoleThroughTheBrokerAndReturnsSanitizedOwnedMetadata() {
        for (role in ProfileEmojiFileRole.entries) {
            val f = Fixture(downloaded = true)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, role, 64, { true }, results::add)
            f.clock.runCurrent()

            val encoded = assertNotNull(results.single().value)
            val selected = if (role == ProfileEmojiFileRole.FULL) f.emoji.sticker.media.file
                else assertNotNull(f.emoji.sticker.media.thumbnail)
            assertEquals(role, encoded.role)
            assertEquals(selected.id, encoded.file.id)
            assertEquals(listOf(selected.id), f.brokerNative.reads.map { it.fileId }.distinct())
            assertTrue(f.brokerNative.downloads.isEmpty())
            assertEquals(listOf(f.emoji.customEmojiId), f.catalog.resolves.first().ids)
            assertTrue(f.catalog.resolves.all { it.ids == listOf(f.emoji.customEmojiId) })
            assertEquals(2, f.catalog.loads.size, "Catalog identity is checked again before publication.")
            assertEquals(2, f.api.calls.size, "Native sticker identity is checked again before publication.")
            assertTrue(f.api.calls.all { assertIs<TdApi.GetCustomEmojiStickers>(it.command).customEmojiIds.contentEquals(longArrayOf(f.emoji.customEmojiId)) })
            assertNull(encoded.file.localPath)
            assertNull(encoded.emoji.sticker.media.file.localPath)
            assertNull(encoded.emoji.sticker.media.thumbnail?.localPath)
            assertContentEquals(f.brokerNative.content.getValue(selected.id), encoded.bytes())
        }
    }

    @Test fun accountAndTokenIdentityAreRequiredBeforeAnyNativeOrCatalogRequest() {
        val mismatched = Fixture(downloaded = true).apply { catalog.loadedUserId = 999 }
        val rejected = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        mismatched.files.read(mismatched.token(), mismatched.emoji, ProfileEmojiFileRole.FULL, 64, { true }, rejected::add)
        assertNotNull(rejected.single().error)
        assertTrue(mismatched.api.calls.isEmpty())
        assertTrue(mismatched.brokerNative.reads.isEmpty())
        assertEquals(1, mismatched.catalog.loads.size)

        val cases = listOf(
            ProfileEmojiResourceToken(0, 43, ProfileEmojiResourceDomain.PROFILE, 5, 1),
            ProfileEmojiResourceToken(10, 0, ProfileEmojiResourceDomain.PROFILE, 5, 1),
            ProfileEmojiResourceToken(10, 43, ProfileEmojiResourceDomain.PROFILE, 0, 1),
            ProfileEmojiResourceToken(10, 43, ProfileEmojiResourceDomain.PROFILE, 5, -1),
        )
        cases.forEach { token ->
            val f = Fixture(downloaded = true)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(token, f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.api.calls.isEmpty())
            assertTrue(f.brokerNative.reads.isEmpty())
        }
    }

    @Test fun catalogFingerprintMustMatchEveryNativeEmojiAndFileIdentityField() {
        val changes: List<(SettingsBackgroundEmoji) -> SettingsBackgroundEmoji> = listOf(
            { it.copy(customEmojiId = it.customEmojiId + 1) },
            { it.copy(needsRepainting = !it.needsRepainting) },
            { it.copy(sticker = it.sticker.copy(id = it.sticker.id + 1)) },
            { it.copy(sticker = it.sticker.copy(setId = it.sticker.setId + 1)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(width = emoji.sticker.media.width + 1)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(height = emoji.sticker.media.height + 1)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(format = MediaFormat.TGS)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(thumbnailFormat = MediaFormat.PHOTO)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(file = emoji.sticker.media.file.copy(id = 70))) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(file = emoji.sticker.media.file.copy(uniqueId = "replaced-full"))) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(thumbnail = null)) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(thumbnail = emoji.sticker.media.thumbnail!!.copy(id = 80))) },
            { emoji -> emoji.withMedia(emoji.sticker.media.copy(thumbnail = emoji.sticker.media.thumbnail!!.copy(uniqueId = "replaced-thumb"))) },
        )
        changes.forEachIndexed { index, change ->
            val f = Fixture(downloaded = true)
            f.catalog.resolveValue = { _, _ -> SettingsResult(listOf(change(f.emoji))) }
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
            assertNotNull(results.single().error, "Fingerprint replacement $index must be rejected.")
            assertTrue(f.api.calls.isEmpty(), "Catalog replacement $index reached the direct native lookup.")
            assertTrue(f.brokerNative.downloads.isEmpty())
            assertTrue(f.brokerNative.reads.isEmpty())
        }
    }

    @Test fun directNativeStickerMustHaveTheExactCustomEmojiTypeAndFingerprint() {
        for (change in 0..12) {
            val f = Fixture(downloaded = true)
            val native = nativeSticker(f.emoji)
            when (change) {
                0 -> native.fullType = TdApi.StickerFullTypeRegular()
                1 -> native.fullType = TdApi.StickerFullTypeCustomEmoji(f.emoji.customEmojiId + 1, f.emoji.needsRepainting)
                2 -> native.fullType = TdApi.StickerFullTypeCustomEmoji(f.emoji.customEmojiId, !f.emoji.needsRepainting)
                3 -> native.id++
                4 -> native.setId++
                5 -> native.width++
                6 -> native.height++
                7 -> native.format = TdApi.StickerFormatTgs()
                8 -> native.sticker!!.id++
                9 -> native.sticker!!.remote!!.uniqueId = "replaced-full"
                10 -> native.thumbnail!!.format = TdApi.ThumbnailFormatJpeg()
                11 -> native.thumbnail!!.file!!.id++
                12 -> native.thumbnail!!.file!!.remote!!.uniqueId = "replaced-thumb"
            }
            f.api.sticker = native
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
            assertNotNull(results.single().error, "Native fingerprint replacement $change must be rejected.")
            assertEquals(1, f.api.calls.size)
            assertTrue(f.brokerNative.downloads.isEmpty())
            assertTrue(f.brokerNative.reads.isEmpty())
        }
    }

    @Test fun invalidRolesBudgetsAndKnownOversizeFilesCannotAcquireNativeBytes() {
        val invalidInputs = listOf(0, -1, 24 * 1024 * 1024 + 1)
        invalidInputs.forEach { maximum ->
            val f = Fixture(downloaded = true)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, maximum, { true }, results::add)
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.api.calls.isEmpty())
            assertTrue(f.brokerNative.reads.isEmpty())
        }
        for (role in ProfileEmojiFileRole.entries) {
            val base = sampleEmoji(downloaded = false)
            val file = if (role == ProfileEmojiFileRole.FULL) base.sticker.media.file
                else assertNotNull(base.sticker.media.thumbnail)
            val oversized = base.withSelectedFile(role, file.copy(size = 65, expectedSize = 65))
            val f = Fixture(downloaded = false, initialEmoji = oversized)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, role, 64, { true }, results::add)
            assertNotNull(results.single().error)
            assertEquals(1, f.api.calls.size, "The selected file size is checked after direct native identity confirmation.")
            assertTrue(f.brokerNative.downloads.isEmpty())
            assertTrue(f.brokerNative.reads.isEmpty())
        }

        val full = sampleEmoji(downloaded = false)
        val blankUnique = full.withSelectedFile(ProfileEmojiFileRole.FULL, full.sticker.media.file.copy(uniqueId = "  "))
        val noThumb = full.withMedia(full.sticker.media.copy(thumbnail = null))
        for ((emoji, role) in listOf(blankUnique to ProfileEmojiFileRole.FULL, noThumb to ProfileEmojiFileRole.THUMBNAIL)) {
            val f = Fixture(downloaded = false, initialEmoji = emoji)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), emoji, role, 64, { true }, results::add)
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
            assertTrue(f.catalog.loads.isEmpty())
            assertTrue(f.api.calls.isEmpty())
        }
    }

    @Test fun unknownSizeUsesOnlyTheCallerBudgetAndNeedsAConfirmedBoundedReadyFile() {
        for (actualSize in listOf(8, 9)) {
            val f = Fixture(downloaded = false, size = 0)
            val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
            f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 8, { true }, results::add)
            val command = f.brokerNative.downloads.single()
            assertEquals(f.emoji.sticker.media.file.id, command.fileId)
            assertEquals(0L, command.offset)
            assertEquals(8L, command.limit)
            command.accept()
            assertTrue(results.isEmpty(), "Download acceptance alone is not evidence that file bytes are ready.")
            assertTrue(f.brokerNative.reads.isEmpty())

            val ready = f.emoji.sticker.media.file.copy(size = actualSize.toLong(), expectedSize = actualSize.toLong(),
                downloaded = true, downloadedSize = actualSize.toLong(), localPath = "private-ready-path")
            f.brokerNative.put(ready, ByteArray(actualSize) { (it + 11).toByte() })
            f.downloads.observe(ready)
            f.clock.runCurrent()

            if (actualSize == 8) {
                val encoded = assertNotNull(results.single().value)
                assertEquals(8, encoded.size)
                assertNull(encoded.file.localPath)
                assertContentEquals(ByteArray(8) { (it + 11).toByte() }, encoded.bytes())
                assertEquals(listOf(8), f.brokerNative.reads.map { it.count })
            } else {
                assertNotNull(results.single().error)
                assertNull(results.single().value)
                assertTrue(f.brokerNative.reads.isEmpty(), "A confirmed file larger than the cap must not be read.")
            }
            assertTrue(f.brokerNative.downloads.all { it.offset + it.limit <= 8 })
        }
    }

    @Test fun encodedBytesAreReadInSixtyFourKiBPartsAndRemainOwnedByTheResult() {
        val f = Fixture(downloaded = true, size = 150_000)
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 200_000, { true }, results::add)
        f.clock.runCurrent()

        val encoded = assertNotNull(results.single().value)
        assertEquals(150_000, encoded.size)
        assertEquals(listOf(65_536, 65_536, 18_928), f.brokerNative.reads.map { it.count })
        assertEquals(listOf(0L, 65_536L, 131_072L), f.brokerNative.reads.map { it.offset })
        assertTrue(f.brokerNative.reads.all { it.fileId == f.emoji.sticker.media.file.id })
        assertContentEquals(f.brokerNative.content.getValue(f.emoji.sticker.media.file.id), encoded.bytes())
        val escaped = encoded.bytes()
        escaped[0] = 99
        assertEquals(0, encoded.bytes()[0].toInt())
        assertFalse(encoded.toString().contains("private"))
        assertFalse(encoded.toString().contains("full-unique"))
    }

    @Test fun metadataIsResolvedAgainBeforePublicationAndAReplacementInvalidatesReadBytes() {
        val f = Fixture(downloaded = true)
        f.catalog.resolveValue = { index, ids ->
            if (index == 1) SettingsResult(listOf(f.emoji))
            else SettingsResult(listOf(f.emoji.copy(needsRepainting = !f.emoji.needsRepainting)))
        }
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
        f.clock.runCurrent()

        assertNull(results.single().value)
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(2, f.catalog.loads.size)
        assertEquals(2, f.catalog.resolves.size)
        assertEquals(1, f.api.calls.size)
        assertTrue(f.brokerNative.reads.isNotEmpty(), "The replacement is detected at the publication recheck.")
    }

    @Test fun finalNativeFullTypeReplacementCannotPublishAlreadyReadBytes() {
        val f = Fixture(downloaded = true)
        f.api.onRequest = { call ->
            val sticker = nativeSticker(f.emoji)
            if (f.api.calls.size == 2) sticker.fullType = TdApi.StickerFullTypeCustomEmoji(f.emoji.customEmojiId, false)
            call.reply(TdApi.Stickers(arrayOf(sticker)))
        }
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
        f.clock.runCurrent()

        assertNull(results.single().value)
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(2, f.catalog.loads.size)
        assertEquals(2, f.catalog.resolves.size)
        assertEquals(2, f.api.calls.size)
        assertTrue(f.brokerNative.reads.isNotEmpty())
    }

    @Test fun aChangedSelfAccountAfterReadingCannotPublishTheOldAccountsBytes() {
        val f = Fixture(downloaded = true)
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
        f.catalog.loadedUserId = 999
        f.clock.runCurrent()
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertNull(results.single().value)
        assertEquals(2, f.catalog.loads.size)
        assertEquals(1, f.api.calls.size)
        assertTrue(f.brokerNative.reads.isNotEmpty())
    }

    @Test fun losingPageActivitySettlesAPendingOwnerAndIgnoresItsLateDownload() {
        val f = Fixture(downloaded = false); var active = true
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { active }, results::add)
        active = false
        f.clock.advance(100)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        val ready = f.emoji.sticker.media.file.copy(downloaded = true, localPath = "private-late")
        f.brokerNative.put(ready, ByteArray(16))
        f.downloads.observe(ready)
        f.clock.runCurrent()
        assertEquals(1, results.size)
        assertTrue(f.brokerNative.reads.isEmpty())
    }

    @Test fun anotherOwnersRetirementCannotStopTheProfilesSharedWindow() {
        val f = Fixture(downloaded = false)
        val other = MediaDownloadOwner(ChatToken(1, 2, 3), 900, MediaDownloadPurpose.ARTWORK, 701)
        f.downloads.full(other, f.emoji.sticker.media.file)
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add)
        f.downloads.release(other, f.emoji.sticker.media.file.id)
        assertTrue(f.brokerNative.cancelled.isEmpty())
        val ready = f.emoji.sticker.media.file.copy(downloaded = true, localPath = "private-shared")
        f.brokerNative.put(ready, ByteArray(16) { it.toByte() })
        f.downloads.observe(ready)
        f.clock.runCurrent()
        assertEquals(16, results.single().value?.size)
        assertEquals(1, f.brokerNative.downloads.size)
    }

    @Test fun duplicateCallbacksLateRepliesCloseRetireAndDeadlineSettleExactlyOnce() {
        val duplicate = Fixture(downloaded = false).apply { catalog.deferLoads = true }
        val duplicateResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        val duplicateLease = duplicate.files.read(duplicate.token(), duplicate.emoji, ProfileEmojiFileRole.FULL, 64, { true }, duplicateResults::add)
        val firstLoad = duplicate.catalog.loads.single()
        firstLoad.reply(SettingsResult(duplicate.catalog.data()))
        firstLoad.reply(SettingsResult(duplicate.catalog.data()))
        assertEquals(1, duplicate.catalog.resolves.size)
        assertEquals(1, duplicate.api.calls.size)
        assertEquals(1, duplicate.brokerNative.downloads.size)
        duplicateLease.close()
        assertEquals(listOf(SettingsError.NOT_READY), duplicateResults.map { it.error })

        val closed = Fixture(downloaded = false).apply { catalog.deferLoads = true }
        val closeResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        val lease = closed.files.read(closed.token(), closed.emoji, ProfileEmojiFileRole.FULL, 64, { true }, closeResults::add)
        lease.close(); lease.close()
        closed.catalog.loads.single().reply(SettingsResult(closed.catalog.data()))
        closed.catalog.loads.single().reply(SettingsResult(closed.catalog.data()))
        assertEquals(listOf(SettingsError.NOT_READY), closeResults.map { it.error })
        assertTrue(closed.api.calls.isEmpty())
        assertTrue(closed.brokerNative.downloads.isEmpty())

        val retired = Fixture(downloaded = false).apply { catalog.deferLoads = true }
        val retiredResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        retired.files.read(retired.token(ownerId = 8), retired.emoji, ProfileEmojiFileRole.FULL, 64, { true }, retiredResults::add)
        retired.files.read(retired.token(ownerId = 9), retired.emoji, ProfileEmojiFileRole.FULL, 64, { true }, retiredResults::add)
        retired.files.retire(); retired.files.retire()
        retired.catalog.loads.forEach { it.reply(SettingsResult(retired.catalog.data())) }
        assertEquals(2, retiredResults.size)
        assertTrue(retiredResults.all { it.error == SettingsError.NOT_READY })
        assertTrue(retired.api.calls.isEmpty())

        val timed = Fixture(downloaded = false).apply { catalog.deferLoads = true }
        val timedResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        timed.files.read(timed.token(), timed.emoji, ProfileEmojiFileRole.FULL, 64, { true }, timedResults::add)
        timed.clock.advance(30_000)
        timed.catalog.loads.single().reply(SettingsResult(timed.catalog.data()))
        assertEquals(listOf(SettingsError.REQUEST_FAILED), timedResults.map { it.error })
        assertTrue(timed.api.calls.isEmpty())
        assertTrue(timed.brokerNative.downloads.isEmpty())
    }

    @Test fun eightPendingReadersAreTheLimitAndAProfileLeaseCannotCancelAnotherBrokerOwner() {
        val bounded = Fixture(downloaded = false).apply { catalog.deferLoads = true }
        val results = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        repeat(9) { bounded.files.read(bounded.token(ownerId = it + 1L), bounded.emoji, ProfileEmojiFileRole.FULL, 64, { true }, results::add) }
        assertEquals(8, bounded.catalog.loads.size)
        assertEquals(1, results.size)
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        bounded.files.retire()
        assertEquals(9, results.size)

        val f = Fixture(downloaded = false)
        val otherOwner = MediaDownloadOwner(ChatToken(1, 2, 3), 900, MediaDownloadPurpose.ARTWORK, 700)
        val otherResults = mutableListOf<ChatResult<MediaFile>>()
        f.downloads.full(otherOwner, f.emoji.sticker.media.file, otherResults::add)
        assertEquals(1, f.brokerNative.downloads.size)
        val profileResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        val profile = f.files.read(f.token(), f.emoji, ProfileEmojiFileRole.FULL, 64, { true }, profileResults::add)
        profile.close()
        assertEquals(SettingsError.NOT_READY, profileResults.single().error)
        assertTrue(f.brokerNative.cancelled.isEmpty())
        val ready = f.emoji.sticker.media.file.copy(downloaded = true, downloadedSize = 16, localPath = "private-ready")
        f.brokerNative.put(ready, ByteArray(16))
        f.downloads.observe(ready)
        assertTrue(otherResults.single().value?.downloaded == true)
        assertTrue(f.brokerNative.cancelled.isEmpty())
    }

    @Test fun activePredicatesAndCallbacksMayThrowOrReenterWithoutLeakingTheirOwnLease() {
        val inactive = Fixture(downloaded = true)
        val inactiveResults = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        inactive.files.read(inactive.token(), inactive.emoji, ProfileEmojiFileRole.FULL, 64,
            { throw IllegalStateException("predicate") }, inactiveResults::add)
        assertEquals(SettingsError.NOT_READY, inactiveResults.single().error)
        assertTrue(inactive.catalog.loads.isEmpty())
        assertTrue(inactive.api.calls.isEmpty())

        val reentrant = Fixture(downloaded = true)
        val first = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        val second = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        reentrant.files.read(reentrant.token(ownerId = 1), reentrant.emoji, ProfileEmojiFileRole.FULL, 64, { true }) {
            first += it
            reentrant.files.read(reentrant.token(ownerId = 2), reentrant.emoji, ProfileEmojiFileRole.FULL, 64, { true }, second::add)
        }
        reentrant.clock.runCurrent()
        assertNotNull(first.single().value)
        assertNotNull(second.single().value)

        val throwing = Fixture(downloaded = true)
        throwing.files.read(throwing.token(ownerId = 1), throwing.emoji, ProfileEmojiFileRole.FULL, 64, { true }) {
            throw IllegalStateException("consumer")
        }
        val recovered = mutableListOf<SettingsResult<ProfileEmojiEncoded>>()
        throwing.files.read(throwing.token(ownerId = 2), throwing.emoji, ProfileEmojiFileRole.FULL, 64, { true }, recovered::add)
        throwing.clock.runCurrent()
        assertNotNull(recovered.single().value)
    }

    @Test fun encodedConstructionCopiesBytesEnforcesItsLimitAndRedactsNativeDetails() {
        val emoji = sampleEmoji(downloaded = true)
        val file = emoji.sticker.media.file
        val source = byteArrayOf(3, 4, 5)
        val encoded = ProfileEmojiEncoded(emoji, ProfileEmojiFileRole.FULL, file, source)
        source[0] = 88
        assertContentEquals(byteArrayOf(3, 4, 5), encoded.bytes())
        val escaped = encoded.bytes()
        escaped[1] = 77
        assertContentEquals(byteArrayOf(3, 4, 5), encoded.bytes())
        assertEquals(3, encoded.size)
        assertFailsWith<IllegalArgumentException> { ProfileEmojiEncoded(emoji, ProfileEmojiFileRole.FULL, file, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> {
            ProfileEmojiEncoded(emoji, ProfileEmojiFileRole.FULL, file, ByteArray(24 * 1024 * 1024 + 1))
        }
        assertFalse(encoded.toString().contains("private"))
        assertFalse(encoded.toString().contains("full-unique"))
    }

    private class Fixture(
        downloaded: Boolean,
        size: Int = 16,
        initialEmoji: SettingsBackgroundEmoji? = null,
    ) {
        val clock = Clock()
        val emoji = initialEmoji ?: sampleEmoji(downloaded, size)
        val catalog = Catalog(emoji)
        val brokerNative = BrokerNative()
        val api = FakeTdApi(emoji)
        val downloads = TdDownloadBroker(brokerNative, clock::schedule, { clock.now })
        val files = OfficialTdProfileEmojiFiles(catalog, downloads,
            { command, reply, failed -> api.send(command, reply, failed) }, clock::schedule, { clock.now })

        init {
            brokerNative.register(emoji)
            api.onRequest = { call -> api.sticker?.let { call.reply(TdApi.Stickers(arrayOf(it))) } ?: call.failed() }
        }

        fun token(account: Long = 43, ownerId: Long = 7, revision: Long = 3) =
            ProfileEmojiResourceToken(10, account, ProfileEmojiResourceDomain.PROFILE, ownerId, revision)
    }

    private class Catalog(initial: SettingsBackgroundEmoji) : TdSettingsProfileColorsSession {
        data class LoadCall(val userId: Long, val active: () -> Boolean,
            val reply: (SettingsResult<SettingsProfileColorsData>) -> Unit)
        data class ResolveCall(val ids: List<Long>, val active: () -> Boolean,
            val reply: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit)

        var value = initial
        var loadedUserId = 43L
        var deferLoads = false
        var deferResolves = false
        var resolveValue: ((Int, List<Long>) -> SettingsResult<List<SettingsBackgroundEmoji>>)? = null
        val loads = mutableListOf<LoadCall>()
        val resolves = mutableListOf<ResolveCall>()
        private var resolveCount = 0

        fun data(userId: Long = loadedUserId) = SettingsProfileColorsData(userId, true,
            SettingsProfileColorsValues(SettingsProfileColorValue(0), SettingsProfileColorValue(0)), SettingsProfileColorsCatalog())

        override fun observe(observer: (() -> Unit)?) = Unit
        override fun catalog() = SettingsProfileColorsCatalog()
        override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) {
            val call = LoadCall(userId, active, result)
            loads += call
            if (!deferLoads) result(SettingsResult(data()))
        }
        override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart,
            desired: SettingsProfileColorValue, replaceGift: Boolean, active: () -> Boolean,
            result: (SettingsProfileColorMutationResult) -> Unit) = error("No mutation is expected")
        override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) =
            error("The file reader must resolve only its selected emoji")
        override fun resolveEmojis(ids: List<Long>, active: () -> Boolean,
            result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            val call = ResolveCall(ids.toList(), active, result)
            resolves += call
            resolveCount++
            val response = resolveValue?.invoke(resolveCount, ids) ?: SettingsResult(listOf(value))
            if (!deferResolves) result(response)
        }
        override fun retire() = Unit
    }

    private class FakeTdApi(initial: SettingsBackgroundEmoji) {
        data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
        val calls = mutableListOf<Call>()
        var sticker: TdApi.Sticker? = nativeSticker(initial)
        var onRequest: ((Call) -> Unit)? = null

        fun send(command: TdApi.Function<*>, reply: (TdApi.Object) -> Unit, failed: () -> Unit) {
            val call = Call(command, reply, failed)
            calls += call
            if (command is TdApi.GetCustomEmojiStickers) onRequest?.invoke(call) ?: failed()
            else failed()
        }
    }

    private class BrokerNative : DownloadBrokerNative {
        class Download(val fileId: Int, val offset: Long, val limit: Long, val accept: () -> Unit)
        data class Read(val fileId: Int, val offset: Long, val count: Int)
        val files = linkedMapOf<Int, MediaFile>()
        val content = linkedMapOf<Int, ByteArray>()
        val downloads = mutableListOf<Download>()
        val cancelled = mutableListOf<Int>()
        val reads = mutableListOf<Read>()

        fun register(emoji: SettingsBackgroundEmoji) {
            put(emoji.sticker.media.file, ByteArray(emoji.sticker.media.file.size.toInt()) { it.toByte() })
            emoji.sticker.media.thumbnail?.let { put(it, ByteArray(it.size.toInt()) { (it + 1).toByte() }) }
        }

        fun put(file: MediaFile, bytes: ByteArray) {
            files[file.id] = file
            content[file.id] = bytes.copyOf()
        }

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            val file = files[fileId] ?: MediaFile(fileId, size = 0, uniqueId = "native-$fileId")
            downloads += Download(fileId, offset, limit) { completed(ChatResult(file)) }
            downloads.last().accept()
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) {
            val file = files[fileId]
            val bytes = content[fileId]
            val available = if (file?.downloaded == true && bytes != null) (bytes.size.toLong() - offset).coerceAtLeast(0) else 0L
            completed(ChatResult(available))
        }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            reads += Read(fileId, offset, count)
            val bytes = content[fileId]
            if (bytes == null || offset < 0 || offset + count > bytes.size) completed(ChatResult(error = ChatError.REQUEST_FAILED))
            else completed(ChatResult(bytes.copyOfRange(offset.toInt(), offset.toInt() + count)))
        }
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) {
            val file = files[fileId]
            completed(if (file == null) ChatResult(error = ChatError.REQUEST_FAILED) else ChatResult(file))
        }
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) {
            cancelled += fileId
            completed(ChatResult(Unit))
        }
    }

    private class Clock {
        var now = 0L
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit) { scheduled += now + delay to action }
        fun runCurrent() = advance(0)
        fun advance(delta: Long) {
            val target = now + delta
            repeat(20_000) {
                val next = scheduled.filter { it.first <= target }.minByOrNull { it.first } ?: run { now = target; return }
                scheduled.remove(next)
                now = next.first
                next.second()
            }
            error("Unbounded profile-emoji scheduling")
        }
    }

}

private fun SettingsBackgroundEmoji.withMedia(media: MessageMedia) = copy(sticker = sticker.copy(media = media))

private fun SettingsBackgroundEmoji.withSelectedFile(role: ProfileEmojiFileRole, file: MediaFile): SettingsBackgroundEmoji {
    val media = sticker.media
    return withMedia(if (role == ProfileEmojiFileRole.FULL) media.copy(file = file) else media.copy(thumbnail = file))
}

private fun sampleEmoji(downloaded: Boolean = false, size: Int = 16): SettingsBackgroundEmoji {
    val full = MediaFile(7, size.toLong(), size.toLong(), if (downloaded) "private-full-path" else null,
        downloaded, downloadedSize = if (downloaded) size.toLong() else 0L, canDownload = true, uniqueId = "full-unique")
    val thumb = MediaFile(8, 8, 8, if (downloaded) "private-thumb-path" else null,
        downloaded, downloadedSize = if (downloaded) 8L else 0L, canDownload = true, uniqueId = "thumb-unique")
    return SettingsBackgroundEmoji(-55, StickerItem(700, 77, "x", MessageMedia(
        full, thumb, 128, 96, MediaFormat.WEBP, thumbnailFormat = MediaFormat.WEBP,
    )), needsRepainting = true)
}

private fun nativeSticker(emoji: SettingsBackgroundEmoji): TdApi.Sticker {
    val media = emoji.sticker.media
    val format = when (media.format) {
        MediaFormat.WEBP -> TdApi.StickerFormatWebp()
        MediaFormat.TGS -> TdApi.StickerFormatTgs()
        MediaFormat.WEBM -> TdApi.StickerFormatWebm()
        else -> TdApi.StickerFormatWebp()
    }
    val thumbnailFormat = when (media.thumbnailFormat) {
        MediaFormat.PHOTO -> TdApi.ThumbnailFormatJpeg()
        MediaFormat.WEBP -> TdApi.ThumbnailFormatWebp()
        MediaFormat.TGS -> TdApi.ThumbnailFormatTgs()
        MediaFormat.WEBM -> TdApi.ThumbnailFormatWebm()
        else -> TdApi.ThumbnailFormatWebp()
    }
    return TdApi.Sticker().apply {
        id = emoji.sticker.id
        setId = emoji.sticker.setId
        width = media.width
        height = media.height
        this.emoji = emoji.sticker.emoji
        this.format = format
        fullType = TdApi.StickerFullTypeCustomEmoji(emoji.customEmojiId, emoji.needsRepainting)
        sticker = nativeFile(media.file)
        media.thumbnail?.let { thumbnail = TdApi.Thumbnail(thumbnailFormat, 32, 32, nativeFile(it)) }
    }
}

private fun nativeFile(media: MediaFile) = TdApi.File().apply {
    id = media.id
    size = media.size
    expectedSize = media.expectedSize
    local = TdApi.LocalFile().apply {
        canBeDownloaded = media.canDownload
        isDownloadingCompleted = media.downloaded
        isDownloadingActive = media.downloading
        downloadedSize = media.downloadedSize
        path = media.localPath.orEmpty()
    }
    remote = TdApi.RemoteFile().apply { uniqueId = media.uniqueId }
}
