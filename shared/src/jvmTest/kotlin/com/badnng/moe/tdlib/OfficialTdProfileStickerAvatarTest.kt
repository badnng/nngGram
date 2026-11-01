package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Synthetic native request boundaries; no account or complete builder UI is exercised. */
class OfficialTdProfileStickerAvatarTest {
    @Test fun defaultProfileEmojiUsesActualCustomIdEvenWhenStickerAndSetIdsAreZero() {
        val f = Fixture()
        val actual = custom(-700, stickerId = 0, setId = 0, fileId = 8123)
        val page = f.defaults(arrayOf(actual, regular(82, 93)))
        val entry = page.candidates.single()
        assertEquals(ProfileStickerAvatarIdentity.CustomEmoji(-700), entry.identity)
        assertEquals(0L, entry.nativeStickerId)
        assertEquals(0L, entry.nativeSetId)
        assertTrue(entry.needsRepainting)
        val selection = f.choose(entry)
        val result = f.save(selection, background(0xAABBCC))
        f.me()
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
        // The complete source must stay unchanged during Save; its zero native IDs
        // still authorize the actual custom emoji identity rather than the file ID.
        f.last.answer(TdApi.Stickers(arrayOf(actual, regular(82, 93))))
        f.me()
        val write = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        assertFalse(write.isPublic)
        val sticker = assertIs<TdApi.InputChatPhotoSticker>(write.photo).sticker
        assertEquals(-700L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(sticker.type).customEmojiId)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, result.single().value)
        assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
    }

    @Test fun zeroMetadataBecomingKnownRevokesOldSelectionAndRequiresFreshCatalogBeforeSave() {
        for (installed in listOf(false, true)) {
            val f = Fixture()
            val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
            val actual = custom(-700, stickerId = 0, setId = 0, fileId = 2345)
            val nativePack = pack(-33, kind, arrayOf(actual))
            fun load(): ProfileStickerAvatarCandidatePage = if (installed) {
                val entry = f.packs(kind, arrayOf(packInfo(-33, kind))).packs.single()
                f.openPack(entry, nativePack)
            } else f.defaults(arrayOf(actual))
            fun source() {
                if (installed) {
                    assertEquals(-33L, assertIs<TdApi.GetStickerSet>(f.last.command).setId)
                    f.last.answer(nativePack)
                } else {
                    assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
                    f.last.answer(TdApi.Stickers(arrayOf(actual)))
                }
            }
            val entry = load().candidates.single()
            val old = f.choose(entry)
            val revision = f.owner.catalogRevision.value
            val denied = f.save(old, background(1))
            f.me()
            actual.id = 45; actual.setId = if (installed) -33 else 42; actual.sticker!!.id = 9876
            source()
            assertEquals(SettingsError.CONFLICT, denied.single().error)
            assertNull(denied.single().value)
            assertEquals(revision + 1, f.owner.catalogRevision.value)
            assertNull(f.api.select(f.owner, entry.ticket))
            assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })

            val refreshed = load().candidates.single()
            assertEquals(ProfileStickerAvatarIdentity.CustomEmoji(-700), refreshed.identity)
            val accepted = f.save(f.choose(refreshed), background(1))
            f.me(); source(); f.me()
            val write = assertIs<TdApi.SetProfilePhoto>(f.last.command)
            val identity = assertIs<TdApi.InputChatPhotoSticker>(write.photo).sticker.type
            assertEquals(-700L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(identity).customEmojiId)
            f.last.answer(TdApi.Ok())
            assertEquals(Unit, accepted.single().value)
            assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
        }
    }

    @Test fun backgroundUsesFrozenRgbValuesAndTheCanonicalNativeShapes() {
        val colors = listOf(listOf(0x123456), listOf(1, 0xFFFFFF), listOf(1, 2, 3), listOf(1, 2, 3, 4))
        for (input in colors) {
            val mutable = input.toMutableList()
            val frozen = assertNotNull(ProfileStickerAvatarBackground.fromColors(mutable))
            mutable[0] = 0xABCDEF
            val f = Fixture()
            val selection = f.choose(f.defaults().candidates.single())
            f.save(selection, frozen)
            f.completeDefaultPreflight()
            val write = assertIs<TdApi.SetProfilePhoto>(f.last.command)
            val fill = assertIs<TdApi.InputChatPhotoSticker>(write.photo).sticker.backgroundFill
            when (input.size) {
                1 -> assertEquals(input[0], assertIs<TdApi.BackgroundFillSolid>(fill).color)
                2 -> assertIs<TdApi.BackgroundFillGradient>(fill).also {
                    assertEquals(input[0], it.topColor); assertEquals(input[1], it.bottomColor); assertEquals(0, it.rotationAngle)
                }
                else -> assertContentEquals(input.toIntArray(), assertIs<TdApi.BackgroundFillFreeformGradient>(fill).colors)
            }
            assertEquals(input, frozen.colors())
        }
        listOf(emptyList(), listOf(-1), listOf(0x1000000), listOf(1, 2, 3, 4, 5)).forEach {
            assertNull(ProfileStickerAvatarBackground.fromColors(it))
        }
    }

    @Test fun regularAndMaskCandidatesRequireAnExplicitInstalledPackAndKeepTheirExactIds() {
        for (kind in listOf(ProfileStickerAvatarPackKind.REGULAR, ProfileStickerAvatarPackKind.MASK)) {
            val f = Fixture()
            val packs = f.packs(kind, arrayOf(packInfo(-20, kind), packInfo(21, kind).apply { isInstalled = false }))
            assertEquals(1, packs.packs.size)
            val request = assertIs<TdApi.GetInstalledStickerSets>(f.requests[1].command)
            when (kind) {
                ProfileStickerAvatarPackKind.REGULAR -> assertIs<TdApi.StickerTypeRegular>(request.stickerType)
                ProfileStickerAvatarPackKind.MASK -> assertIs<TdApi.StickerTypeMask>(request.stickerType)
                ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> error("This case covers regular and mask packs")
            }
            assertTrue(f.requests.none { it.command is TdApi.GetStickerSet })
            val wrongKind = if (kind == ProfileStickerAvatarPackKind.REGULAR) ProfileStickerAvatarPackKind.MASK
                else ProfileStickerAvatarPackKind.REGULAR
            val native = pack(-20, kind, arrayOf(packSticker(-20, -30, kind), packSticker(333, 1, kind),
                packSticker(-20, -31, wrongKind)))
            val page = f.openPack(packs.packs.single(), native)
            val candidate = page.candidates.single()
            assertEquals(ProfileStickerAvatarIdentity.RegularOrMask(-20, -30, kind), candidate.identity)
            val selection = f.choose(candidate)
            val result = f.save(selection, background(1, 2))
            f.me()
            assertEquals(-20L, assertIs<TdApi.GetStickerSet>(f.last.command).setId)
            f.last.answer(native)
            f.me()
            val input = assertIs<TdApi.InputChatPhotoSticker>(assertIs<TdApi.SetProfilePhoto>(f.last.command).photo)
            val nativeIdentity = assertIs<TdApi.ChatPhotoStickerTypeRegularOrMask>(input.sticker.type)
            assertEquals(-20L, nativeIdentity.stickerSetId)
            assertEquals(-30L, nativeIdentity.stickerId)
            f.last.answer(TdApi.Ok())
            assertEquals(Unit, result.single().value)
            assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
        }
    }

    @Test fun forgedCrossOwnerStaleAndReselectedTicketsCannotAuthorizeASave() {
        val f = Fixture()
        val entry = f.defaults().candidates.single()
        assertNull(f.api.select(f.owner, ProfileStickerAvatarCandidateTicket()))
        val other = Fixture()
        assertNull(other.api.select(other.owner, entry.ticket))
        val old = f.choose(entry)
        val invalidatedSave = f.save(old, background(1))
        val delayedSelf = f.last
        val latest = f.choose(entry)
        assertEquals(SettingsError.NOT_READY, invalidatedSave.single().error)
        delayedSelf.answer(user())
        assertNotSame(old, latest)
        val before = f.requests.size
        assertEquals(SettingsError.CONFLICT, f.save(old, background(1)).single().error)
        assertEquals(before, f.requests.size)
        val foreignPack = other.packs(ProfileStickerAvatarPackKind.REGULAR,
            arrayOf(packInfo(999, ProfileStickerAvatarPackKind.REGULAR))).packs.single().ticket
        for (packTicket in listOf(ProfileStickerAvatarPackTicket(), foreignPack)) {
            val denied = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            f.api.openPack(f.owner, packTicket, denied::add)
            assertEquals(SettingsError.CONFLICT, denied.single().error)
            assertEquals(before, f.requests.size)
        }
        val pending = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.loadDefault(f.owner, pending::add)
        assertNull(f.api.select(f.owner, entry.ticket))
        assertEquals(SettingsError.CONFLICT, f.save(latest, background(1)).single().error)
        assertIs<TdApi.GetMe>(f.last.command)
        assertTrue(pending.isEmpty())
    }

    @Test fun removedPackWrongTypeOrMissingStickerStopsBeforeAWrite() {
        for (change in 0..4) {
            val f = Fixture()
            val kind = ProfileStickerAvatarPackKind.REGULAR
            val page = f.packs(kind, arrayOf(packInfo(20, kind)))
            val valid = pack(20, kind, arrayOf(packSticker(20, 30, kind)))
            val selected = f.choose(f.openPack(page.packs.single(), valid).candidates.single())
            val result = f.save(selected, background(1))
            f.me()
            val changed = pack(20, kind, arrayOf(packSticker(20, 30, kind)))
            when (change) {
                0 -> changed.isInstalled = false
                1 -> changed.isArchived = true
                2 -> changed.id = 21
                3 -> changed.stickerType = TdApi.StickerTypeMask()
                4 -> changed.stickers = arrayOf(packSticker(20, 31, kind))
            }
            f.last.answer(changed)
            assertEquals(SettingsError.CONFLICT, result.single().error)
            assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
        }
    }

    @Test fun changedDefaultIdentityOrNativeErrorCannotReachTheWrite() {
        for (response in listOf<TdApi.Object>(
            TdApi.Stickers(arrayOf(custom(701))),
            TdApi.Stickers(arrayOf(regular(700, 10))),
            TdApi.Error(403, "private text"),
        )) {
            val f = Fixture()
            val selected = f.choose(f.defaults().candidates.single())
            val result = f.save(selected, background(1))
            f.me()
            f.last.answer(response)
            assertEquals(if (response is TdApi.Error) SettingsError.FORBIDDEN else SettingsError.CONFLICT, result.single().error)
            assertFalse(result.toString().contains("private text"))
            assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
        }
    }

    @Test fun bothSelfReadsEnforceActualAccountAndPhotoIncludingNullAndSignedIds() {
        val wrongCatalog = Fixture()
        val catalogResult = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        wrongCatalog.api.loadDefault(wrongCatalog.owner, catalogResult::add)
        wrongCatalog.me(id = 44)
        assertEquals(SettingsError.NOT_READY, catalogResult.single().error)
        assertTrue(wrongCatalog.requests.none { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
        for (failure in 0..3) {
            val f = Fixture()
            val selected = f.choose(f.defaults().candidates.single())
            val result = f.save(selected, background(1))
            if (failure == 0) f.me(id = 44)
            else if (failure == 1) f.me(photo = 92)
            else {
                f.me()
                f.last.answer(TdApi.Stickers(arrayOf(custom(700))))
                if (failure == 2) f.me(id = 44) else f.me(photo = 92)
            }
            assertEquals(if (failure == 0 || failure == 2) SettingsError.NOT_READY else SettingsError.CONFLICT, result.single().error)
            assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
        }
        for (photoId in listOf<Long?>(null, -923)) {
            val f = Fixture()
            val selected = f.choose(f.defaults().candidates.single())
            val result = f.save(selected, background(1), expectedPhotoId = photoId)
            f.completeDefaultPreflight(photoId)
            assertIs<TdApi.SetProfilePhoto>(f.last.command)
            f.last.answer(TdApi.Ok())
            assertEquals(Unit, result.single().value)
        }
        val f = Fixture()
        val selected = f.choose(f.defaults().candidates.single())
        val before = f.requests.size
        assertEquals(SettingsError.INVALID_VALUE, f.save(selected, background(1), 0).single().error)
        assertEquals(before, f.requests.size)
    }

    @Test fun ClosingRetiringAndNewOwnerStopLateReadsWithoutRevivingOldTickets() {
        val f = Fixture()
        val first = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.loadDefault(f.owner, first::add)
        val delayedSelf = f.last
        f.api.close(f.owner)
        assertEquals(SettingsError.NOT_READY, first.single().error)
        delayedSelf.answer(user())
        assertEquals(1, f.requests.size)
        val replacement = assertNotNull(f.api.open(43) { true })
        val second = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.loadDefault(replacement, second::add)
        f.me()
        val delayedCatalog = f.last
        f.api.retire()
        delayedCatalog.answer(TdApi.Stickers(arrayOf(custom(700))))
        assertEquals(SettingsError.NOT_READY, second.single().error)
        assertNull(f.api.open(43) { true })
        assertEquals(3, f.requests.size)

        val g = Fixture()
        val selected = g.choose(g.defaults().candidates.single())
        val pendingWrite = g.save(selected, background(1))
        g.me()
        g.last.answer(TdApi.Stickers(arrayOf(custom(700))))
        val delayedFinalSelf = g.last
        g.api.close(g.owner)
        assertEquals(SettingsError.NOT_READY, pendingWrite.single().error)
        delayedFinalSelf.answer(user())
        assertTrue(g.requests.none { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun admittedWriteRetainsActualLateNativeResultExactlyOnce() {
        for (success in listOf(true, false)) {
            val f = Fixture()
            val selection = f.choose(f.defaults().candidates.single())
            val result = f.save(selection, background(1))
            f.completeDefaultPreflight()
            val write = f.last
            assertIs<TdApi.SetProfilePhoto>(write.command)
            f.api.close(f.owner)
            f.api.retire()
            assertTrue(result.isEmpty())
            write.answer(if (success) TdApi.Ok() else TdApi.Error(429, "private detail"))
            write.answer(TdApi.Ok())
            write.failed()
            assertEquals(1, result.size)
            if (success) assertEquals(Unit, result.single().value)
            else assertEquals(SettingsError.RATE_LIMITED, result.single().error)
            assertFalse(result.toString().contains("private detail"))
        }

        // A send that throws after receiving the request has ambiguous dispatch status.
        // It must not manufacture failure, free the reservation, or suppress a real late Ok.
        val ambiguous = Fixture(throwOnWrite = true)
        val selection = ambiguous.choose(ambiguous.defaults().candidates.single())
        val result = ambiguous.save(selection, background(1))
        ambiguous.completeDefaultPreflight()
        val write = ambiguous.last
        assertIs<TdApi.SetProfilePhoto>(write.command)
        assertTrue(result.isEmpty())
        ambiguous.api.close(ambiguous.owner)
        ambiguous.api.retire()
        write.answer(TdApi.Ok()); write.failed()
        assertEquals(Unit, result.single().value)
    }

    @Test fun duplicateCallbacksAndThrowAfterCallbackDoNotAdvanceTwice() {
        val requests = mutableListOf<Request>()
        val api = OfficialTdProfileStickerAvatar({ command, done, failed ->
            requests += Request(command, done, failed)
            if (command is TdApi.GetMe) { done(user()); error("after callback") }
            if (command is TdApi.SetProfilePhoto) { done(TdApi.Ok()); error("after native callback") }
        })
        val owner = assertNotNull(api.open(43) { true })
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        api.loadDefault(owner, results::add)
        assertEquals(2, requests.size)
        val self = requests.first()
        self.answer(user()); self.failed()
        assertEquals(2, requests.size)
        val defaults = requests.last()
        defaults.answer(TdApi.Stickers(arrayOf(custom(700))))
        defaults.answer(TdApi.Error(500, "private")); defaults.failed()
        assertEquals(1, results.size)
        assertEquals(1, results.single().value?.candidates?.size)
        val selected = assertNotNull(api.select(owner, results.single().value!!.candidates.single().ticket))
        val saved = mutableListOf<SettingsResult<Unit>>()
        api.save(owner, selected, 91, background(1), saved::add)
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(requests.last().command)
        requests.last().answer(TdApi.Stickers(arrayOf(custom(700))))
        assertEquals(Unit, saved.single().value)
        assertEquals(1, requests.count { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun foreignLeaseSendAndResultCallbacksCanReenterWithoutHoldingTheHelperMonitor() {
        val reentered = AtomicBoolean()
        var trigger = false
        lateinit var first: OfficialTdProfileStickerAvatar
        first = OfficialTdProfileStickerAvatar({ _, _, _ -> error("must not send") })
        val owner = assertNotNull(first.open(43) {
            if (trigger) { reentered.set(onAnotherThread { first.retire() }); false } else true
        })
        trigger = true
        val result = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        first.loadDefault(owner, result::add)
        assertTrue(reentered.get())
        assertEquals(SettingsError.NOT_READY, result.single().error)

        lateinit var second: OfficialTdProfileStickerAvatar
        lateinit var secondOwner: ProfileStickerAvatarPickerTicket
        var sendReentered = false
        second = OfficialTdProfileStickerAvatar({ _, _, _ -> sendReentered = onAnotherThread { second.close(secondOwner) } })
        secondOwner = assertNotNull(second.open(43) { true })
        val secondResults = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        second.loadDefault(secondOwner, secondResults::add)
        assertTrue(sendReentered)
        assertEquals(SettingsError.NOT_READY, secondResults.single().error)

        val f = Fixture()
        var resultReentered = false
        f.api.loadDefault(f.owner) { resultReentered = onAnotherThread { f.api.retire() } }
        f.me()
        f.last.answer(TdApi.Stickers(arrayOf(custom(700))))
        assertTrue(resultReentered)
    }

    @Test fun BoundedCatalogsAndPendingOperationsNeverTriggerBulkDownloads() {
        val f = Fixture()
        val page = f.defaults(Array(513) { custom(it.toLong() + 1) })
        assertEquals(64, page.candidates.size)
        assertNotNull(page.next)
        assertFalse(page.limited)
        assertFailsWith<UnsupportedOperationException> { (page.candidates as MutableList<ProfileStickerAvatarCandidate>).clear() }
        val selection = f.choose(page.candidates.first())
        val pending = (1..9).map { f.save(selection, background(1)) }
        assertTrue(pending.take(8).all { it.isEmpty() })
        assertEquals(SettingsError.NOT_READY, pending.last().single().error)
        assertEquals(8, f.requests.count { it.command is TdApi.GetMe } - 1)
        f.api.close(f.owner)
        assertTrue(pending.take(8).all { it.single().error == SettingsError.NOT_READY })

        val g = Fixture()
        val large = Array(4097) { custom(it.toLong() + 1) }
        val first = g.defaults(large)
        val seen = first.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }.toMutableList()
        var cursor = first.next
        while (cursor != null) {
            val next = assertIs<ProfileStickerAvatarCatalogWindow.Candidates>(g.window(cursor, TdApi.Stickers(large))).page
            seen += next.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }
            cursor = next.next
        }
        assertEquals((1L..4097L).toList(), seen)
        assertTrue(g.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile || it.command is TdApi.GetStickerSet })

        val h = Fixture()
        val allPacks = Array(257) { packInfo(it.toLong() + 1, ProfileStickerAvatarPackKind.REGULAR) }
        val packs = h.packs(ProfileStickerAvatarPackKind.REGULAR, allPacks)
        assertEquals(64, packs.packs.size)
        val seenPacks = packs.packs.map { it.ticket }.toMutableList()
        var packCursor = packs.next
        while (packCursor != null) {
            val next = assertIs<ProfileStickerAvatarCatalogWindow.Packs>(h.window(packCursor, TdApi.StickerSets(allPacks.size, allPacks))).page
            seenPacks += next.packs.map { it.ticket }
            packCursor = next.next
        }
        assertEquals(257, seenPacks.size)
        assertEquals(257, seenPacks.distinct().size)
        assertTrue(h.requests.none { it.command is TdApi.GetStickerSet || it.command is TdApi.DownloadFile })

        val i = Fixture()
        val longTitle = packInfo(1, ProfileStickerAvatarPackKind.REGULAR).apply { title = "a".repeat(127) + "😀extra" }
        val titles = i.packs(ProfileStickerAvatarPackKind.REGULAR, arrayOf(longTitle))
        assertFalse(titles.limited)
        assertEquals(longTitle.title, titles.packs.single().title)
    }

    @Test fun canceledCatalogConsumersCannotFreeNativeSlotsOrDuplicateAnOldCompletion() {
        val f = Fixture()
        val completions = List(8) { mutableListOf<SettingsError?>() }
        repeat(8) { index ->
            if (index % 2 == 0) f.api.loadDefault(f.owner) { completions[index] += it.error }
            else f.api.loadInstalledPacks(f.owner, ProfileStickerAvatarPackKind.REGULAR) { completions[index] += it.error }
            assertEquals(index + 1, f.requests.size)
            assertIs<TdApi.GetMe>(f.last.command)
        }
        assertTrue(completions.take(7).all { it.single() == SettingsError.NOT_READY })
        assertTrue(completions.last().isEmpty())
        val refused = mutableListOf<SettingsError?>()
        f.api.loadDefault(f.owner) { refused += it.error }
        assertEquals(SettingsError.NOT_READY, completions.last().single())
        assertEquals(SettingsError.NOT_READY, refused.single())
        assertEquals(8, f.requests.size)

        // Only the first completion of the first physical request frees its own reservation.
        val old = f.requests.first()
        old.answer(user())
        val accepted = mutableListOf<SettingsError?>()
        f.api.loadDefault(f.owner) { accepted += it.error }
        assertEquals(9, f.requests.size)
        val ninth = f.last
        old.answer(user()); old.failed()
        val stillFull = mutableListOf<SettingsError?>()
        f.api.loadDefault(f.owner) { stillFull += it.error }
        assertEquals(SettingsError.NOT_READY, accepted.single())
        assertEquals(SettingsError.NOT_READY, stillFull.single())
        assertEquals(9, f.requests.size)

        // The canceled ninth consumer does not release its slot, but its later native failure does.
        ninth.failed(); ninth.failed(); ninth.answer(user())
        val retry = mutableListOf<SettingsError?>()
        f.api.loadDefault(f.owner) { retry += it.error }
        assertEquals(10, f.requests.size)
        assertTrue(retry.isEmpty())

        // A throwing send has unknown dispatch status: read consumers fail, but each token
        // stays reserved until that exact request delivers a native callback.
        val throwingRequests = mutableListOf<Request>()
        val throwing = OfficialTdProfileStickerAvatar({ command, done, failed ->
            throwingRequests += Request(command, done, failed)
            error("unknown dispatch status")
        })
        val throwingOwner = assertNotNull(throwing.open(43) { true })
        val throwResults = List(9) { mutableListOf<SettingsError?>() }
        repeat(9) { index -> throwing.loadDefault(throwingOwner) { throwResults[index] += it.error } }
        assertEquals(8, throwingRequests.size)
        assertTrue(throwResults.take(8).all { it.single() == SettingsError.REQUEST_FAILED })
        assertEquals(SettingsError.NOT_READY, throwResults.last().single())
        throwingRequests.first().answer(user())
        assertEquals(8, throwingRequests.size, "A late result of a failed consumer must not start its next request")
        throwing.loadDefault(throwingOwner) {}
        assertEquals(9, throwingRequests.size)
    }

    @Test fun reopeningOwnersCannotBypassTheGlobalPhysicalRequestLimit() {
        val requests = mutableListOf<Request>()
        val api = OfficialTdProfileStickerAvatar({ command, done, failed -> requests += Request(command, done, failed) })
        val outcomes = List(12) { mutableListOf<SettingsError?>() }
        repeat(12) { index ->
            val owner = assertNotNull(api.open(43) { true })
            api.loadDefault(owner) { outcomes[index] += it.error }
            assertTrue(requests.size <= 8)
        }
        assertEquals(8, requests.size)
        assertTrue(outcomes.all { it.single() == SettingsError.NOT_READY })
        api.retire()
        val count = outcomes.sumOf { it.size }
        requests.forEach { it.answer(user()); it.answer(user()); it.failed() }
        assertEquals(count, outcomes.sumOf { it.size })
        assertEquals(8, requests.size)
        assertNull(api.open(43) { true })
    }

    @Test fun synchronousStagesReuseOnlyTheirReleasedSlotWhileCanceledRequestsRemainHeld() {
        val f = Fixture()
        repeat(7) { f.api.loadDefault(f.owner) {} }
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.loadDefault(f.owner, results::add)
        assertEquals(8, f.requests.size)
        f.me()
        assertEquals(9, f.requests.size)
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
        f.last.answer(TdApi.Stickers(arrayOf(custom(700))))
        val selection = f.choose(assertNotNull(results.single().value).candidates.single())
        val saved = f.save(selection, background(1, 2))
        f.completeDefaultPreflight()
        assertIs<TdApi.SetProfilePhoto>(f.last.command)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, saved.single().value)
        assertTrue(f.requests.take(7).all { it.command is TdApi.GetMe })
        assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
    }

    @Test fun installedCustomEmojiPackKeepsActualEmojiIdentityWhenStickerMetadataIsZero() {
        val f = Fixture()
        val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
        val packs = f.packs(kind, arrayOf(packInfo(-33, kind)))
        val installed = assertIs<TdApi.GetInstalledStickerSets>(f.requests[1].command)
        assertIs<TdApi.StickerTypeCustomEmoji>(installed.stickerType)
        assertTrue(f.requests.none { it.command is TdApi.GetStickerSet })
        val actual = custom(-700, stickerId = 0, setId = 0, fileId = 2345)
        val page = f.openPack(packs.packs.single(), pack(-33, kind, arrayOf(actual)))
        val candidate = page.candidates.single()
        assertEquals(ProfileStickerAvatarIdentity.CustomEmoji(-700), candidate.identity)
        assertEquals(0L, candidate.nativeStickerId)
        assertEquals(0L, candidate.nativeSetId)
        assertTrue(candidate.needsRepainting)
        // Public presentation data can be copied; the helper uses only the authentic ticket's private record.
        val altered = candidate.copy(identity = ProfileStickerAvatarIdentity.CustomEmoji(123456), nativeStickerId = 8000)
        val result = f.save(f.choose(altered), background(0xABCDE0))
        f.me() // Fixture user is not Premium; profile selection is not gated on that flag.
        assertFalse(user().isPremium)
        assertEquals(-33L, assertIs<TdApi.GetStickerSet>(f.last.command).setId)
        // Preserve the exact loaded source during this successful Save.
        f.last.answer(pack(-33, kind, arrayOf(actual)))
        f.me()
        val write = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        assertFalse(write.isPublic)
        val identity = assertIs<TdApi.InputChatPhotoSticker>(write.photo).sticker.type
        assertEquals(-700L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(identity).customEmojiId)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, result.single().value)
        assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile ||
            it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
    }

    @Test fun customPackCatalogRejectsWrongSourcesAndAmbiguousNativeIdentities() {
        val f = Fixture()
        val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
        val packs = f.packs(kind, arrayOf(
            packInfo(20, kind),
            packInfo(21, ProfileStickerAvatarPackKind.REGULAR),
            packInfo(22, kind).apply { isInstalled = false },
            packInfo(23, kind).apply { isArchived = true },
            packInfo(0, kind),
        ))
        assertEquals(1, packs.packs.size)
        val native = pack(20, kind, arrayOf(
            custom(-1, 0, 0), custom(-1, 5, 999),
            custom(-2, 0, 20), custom(-3, 0, 999), custom(0, 0, 0), regular(44, 20),
        ))
        val page = f.openPack(packs.packs.single(), native)
        assertTrue(page.limited)
        assertEquals(listOf(ProfileStickerAvatarIdentity.CustomEmoji(-2)), page.candidates.map { it.identity })
        assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })

        for (change in 0..3) {
            val g = Fixture()
            val actualPack = g.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            g.api.openPack(g.owner, actualPack.ticket, result::add)
            g.me()
            val changed = pack(20, kind, arrayOf(custom(-2, 0, 0)))
            when (change) {
                0 -> changed.id = 21
                1 -> changed.stickerType = TdApi.StickerTypeMask()
                2 -> changed.isInstalled = false
                3 -> changed.isArchived = true
            }
            g.last.answer(changed)
            assertEquals(SettingsError.CONFLICT, result.single().error)
        }
    }

    @Test fun customPackSaveRechecksUniqueFullTypeAndInstalledSource() {
        val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
        for (change in 0..7) {
            val f = Fixture()
            val packs = f.packs(kind, arrayOf(packInfo(20, kind)))
            val opened = f.openPack(packs.packs.single(), pack(20, kind, arrayOf(custom(-700, 0, 0))))
            val result = f.save(f.choose(opened.candidates.single()), background(1, 2))
            f.me()
            assertEquals(20L, assertIs<TdApi.GetStickerSet>(f.last.command).setId)
            val changed = pack(20, kind, arrayOf(custom(-700, 0, 0)))
            when (change) {
                0 -> changed.isInstalled = false
                1 -> changed.isArchived = true
                2 -> changed.id = 21
                3 -> changed.stickerType = TdApi.StickerTypeRegular()
                4 -> changed.stickers = arrayOf(custom(-701, 0, 0))
                5 -> changed.stickers = arrayOf(regular(-700, 20))
                6 -> changed.stickers = arrayOf(custom(-700, 0, 0), custom(-700, 5, 999))
                7 -> changed.stickers = arrayOf(custom(-700, 0, 999))
            }
            f.last.answer(changed)
            assertEquals(SettingsError.CONFLICT, result.single().error, "change=$change")
            assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
        }
    }

    @Test fun customPackTicketsAndPendingSelectionsStayOwnedByTheirOriginalPicker() {
        val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
        val f = Fixture()
        val ownPack = f.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val foreign = Fixture()
        val foreignPack = foreign.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val denied = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        val before = f.requests.size
        f.api.openPack(f.owner, foreignPack.ticket, denied::add)
        assertEquals(SettingsError.CONFLICT, denied.single().error)
        assertEquals(before, f.requests.size)

        val pending = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.openPack(f.owner, ownPack.ticket, pending::add)
        f.me()
        val delayed = f.last
        assertIs<TdApi.GetStickerSet>(delayed.command)
        f.api.close(f.owner)
        assertEquals(SettingsError.NOT_READY, pending.single().error)
        delayed.answer(pack(20, kind, arrayOf(custom(-700, 0, 0))))
        assertEquals(1, pending.size)

        val g = Fixture()
        val selectedPack = g.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val candidate = g.openPack(selectedPack, pack(20, kind, arrayOf(custom(-700, 0, 0)))).candidates.single()
        val old = g.choose(candidate)
        val result = g.save(old, background(1))
        g.me()
        val delayedRevalidation = g.last
        g.choose(candidate) // Even reselection of the same identity is a new owner revision.
        assertEquals(SettingsError.NOT_READY, result.single().error)
        val prior = g.requests.size
        delayedRevalidation.answer(pack(20, kind, arrayOf(custom(-700, 0, 0))))
        assertEquals(prior, g.requests.size)
        assertTrue(g.requests.none { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun customPackCandidatesAndCanceledRequestsRemainBounded() {
        val kind = ProfileStickerAvatarPackKind.CUSTOM_EMOJI
        val f = Fixture()
        val ownPack = f.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val native = pack(20, kind, Array(513) { custom(it.toLong() + 1, 0, 0) })
        val page = f.openPack(ownPack, native)
        assertEquals(64, page.candidates.size)
        assertNotNull(page.next)
        assertFalse(page.limited)
        val selection = f.choose(page.candidates.first())
        val seen = page.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }.toMutableList()
        var cursor = page.next
        while (cursor != null) {
            val next = assertIs<ProfileStickerAvatarCatalogWindow.Candidates>(f.window(cursor, native)).page
            seen += next.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }
            cursor = next.next
        }
        assertEquals((1L..513L).toList(), seen)
        val saved = f.save(selection, background(1))
        f.me(); f.last.answer(native); f.me()
        val command = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        assertEquals(1L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(
            assertIs<TdApi.InputChatPhotoSticker>(command.photo).sticker.type).customEmojiId)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, saved.single().value)

        val g = Fixture()
        val selectedPack = g.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val oversized = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        g.api.openPack(g.owner, selectedPack.ticket, oversized::add)
        g.me()
        val oversizedPack = pack(20, kind, Array(4097) { custom(it.toLong() + 1, 0, 0) })
        g.last.answer(oversizedPack)
        val first = assertNotNull(oversized.single().value)
        assertEquals(64, first.candidates.size)
        assertNotNull(first.next)
        assertFalse(first.limited)
        val all = first.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }.toMutableList()
        var nextCursor = first.next
        while (nextCursor != null) {
            val next = assertIs<ProfileStickerAvatarCatalogWindow.Candidates>(g.window(nextCursor, oversizedPack)).page
            all += next.candidates.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }
            nextCursor = next.next
        }
        assertEquals((1L..4097L).toList(), all)
        assertTrue(g.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })

        val requests = mutableListOf<Request>()
        val api = OfficialTdProfileStickerAvatar({ command, done, failed -> requests += Request(command, done, failed) })
        val outcomes = List(9) { mutableListOf<SettingsError?>() }
        repeat(9) { index ->
            val owner = assertNotNull(api.open(43) { true })
            api.loadInstalledPacks(owner, kind) { outcomes[index] += it.error }
        }
        assertEquals(8, requests.size)
        assertTrue(outcomes.all { it.single() == SettingsError.NOT_READY })
        requests.first().answer(user()); requests.first().failed()
        val fresh = assertNotNull(api.open(43) { true })
        api.loadInstalledPacks(fresh, kind) {}
        assertEquals(9, requests.size)
    }

    @Test fun sameSourceWindowRetiresOldGrantsButKeepsTheExactSelectionForFullAndSave() {
        val f = Fixture()
        val firstSticker = custom(-700, stickerId = 0, setId = 0, fileId = 70).apply {
            format = TdApi.StickerFormatWebp()
            sticker = mediaFile(70, 64)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 32, 32, mediaFile(71, 24))
        }
        val native = Array(4097) { index -> if (index == 0) firstSticker else custom(index.toLong(), 0, 0) }
        val first = f.defaults(native)
        val oldCandidate = first.candidates.first()
        val selection = f.choose(oldCandidate)
        val oldThumbnail = assertNotNull(f.api.thumbnailMedia(f.owner, oldCandidate.ticket))
        val oldFull = assertNotNull(f.api.fullMedia(f.owner, selection))
        assertTrue(oldThumbnail.atomicCurrent()); assertTrue(oldFull.atomicCurrent())

        val second = assertIs<ProfileStickerAvatarCatalogWindow.Candidates>(f.window(assertNotNull(first.next), TdApi.Stickers(native))).page
        assertEquals(64, second.candidates.size)
        assertFalse(oldThumbnail.atomicCurrent()); assertFalse(oldFull.atomicCurrent())
        assertNull(f.api.select(f.owner, oldCandidate.ticket), "An evicted row cannot select again.")
        assertNotNull(f.api.fullMedia(f.owner, selection), "The selected full source owns a separate record after revision validation.")
        val outcome = f.save(selection, background(1))
        f.me()
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
        f.last.answer(TdApi.Stickers(native))
        f.me()
        val write = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        val sticker = assertIs<TdApi.InputChatPhotoSticker>(write.photo).sticker
        assertEquals(-700L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(sticker.type).customEmojiId)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, outcome.single().value)
        assertTrue(f.requests.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
    }

    @Test fun changedSourceRejectsTheCursorAndRevokesItsRetainedSelection() {
        val f = Fixture()
        val initial = Array(65) { custom(it.toLong() + 1, 0, 0) }
        val first = f.defaults(initial)
        val selection = f.choose(first.candidates.first())
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
        f.api.loadWindow(f.owner, assertNotNull(first.next), results::add)
        f.me()
        val changed = initial.copyOf().also { it[64] = custom(9999, 0, 0) }
        f.last.answer(TdApi.Stickers(changed))
        assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(SettingsError.CONFLICT, f.save(selection, background(1)).single().error)
        val stale = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
        f.api.loadWindow(f.owner, assertNotNull(first.next), stale::add)
        assertEquals(SettingsError.CONFLICT, stale.single().error)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun nativeStickerSetUpdateRevokesTheWindowAndSelectionBeforeNotification() {
        val f = Fixture()
        val firstSticker = custom(700, 0, 0, 70).apply {
            format = TdApi.StickerFormatWebp(); sticker = mediaFile(70, 64)
            thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 32, 32, mediaFile(71, 24))
        }
        val first = f.defaults(Array(65) { if (it == 0) firstSticker else custom(it.toLong() + 700, 0, 0) })
        val selected = f.choose(first.candidates.first())
        val full = assertNotNull(f.api.fullMedia(f.owner, selected))
        val before = f.owner.catalogInvalidations.value
        f.api.acceptCatalogUpdate(TdApi.UpdateStickerSet(TdApi.StickerSet()))
        assertEquals(before + 1, f.owner.catalogInvalidations.value)
        assertFalse(full.atomicCurrent())
        assertNull(f.api.select(f.owner, first.candidates.first().ticket))
        assertEquals(SettingsError.CONFLICT, f.save(selected, background(1)).single().error)
        val stale = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
        f.api.loadWindow(f.owner, assertNotNull(first.next), stale::add)
        assertEquals(SettingsError.CONFLICT, stale.single().error)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })
    }

    @Test fun nativeUpdateRevisesOwnershipBeforeDeliveringAnyCancelledRead() {
        val f = Fixture()
        val first = f.defaults(Array(65) { custom(it.toLong() + 1, 0, 0) })
        val before = f.owner.catalogRevision.value
        val observed = mutableListOf<Pair<SettingsError?, Long>>()
        f.api.loadWindow(f.owner, assertNotNull(first.next)) { observed += it.error to f.owner.catalogRevision.value }
        f.api.acceptCatalogUpdate(TdApi.UpdateStickerSet(TdApi.StickerSet()))
        assertEquals(listOf<Pair<SettingsError?, Long>>(SettingsError.NOT_READY to before + 1), observed)
        assertEquals(before + 1, f.owner.catalogInvalidations.value)
        f.last.answer(user())
        assertEquals(1, observed.size)
    }

    @Test fun firstInstalledPackUpdateTriggersARecheckBeforeAnyCandidateIsPublished() {
        val f = Fixture()
        val kind = ProfileStickerAvatarPackKind.REGULAR
        val chosen = f.packs(kind, arrayOf(packInfo(20, kind))).packs.single()
        val responses = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.openPack(f.owner, chosen.ticket, responses::add)
        f.me()
        val firstRead = f.last
        assertEquals(20L, assertIs<TdApi.GetStickerSet>(firstRead.command).setId)
        val loaded = pack(20, kind, arrayOf(regular(1, 20)))
        f.api.acceptCatalogUpdate(TdApi.UpdateStickerSet(loaded))
        assertTrue(responses.isEmpty(), "An initial loading update grants no pre-response candidate")
        firstRead.answer(loaded)
        assertTrue(responses.isEmpty(), "The dirtied response must not publish before rechecking")
        assertNotSame(firstRead, f.last)
        assertEquals(20L, assertIs<TdApi.GetStickerSet>(f.last.command).setId)
        f.last.answer(loaded)
        val page = assertNotNull(responses.single().value)
        assertEquals(1, page.candidates.size)
        assertNotNull(f.api.select(f.owner, page.candidates.single().ticket))
    }

    @Test fun firstInstalledListUpdateRechecksWithoutPublishingItsEarlierResult() {
        val f = Fixture()
        val kind = ProfileStickerAvatarPackKind.REGULAR
        val responses = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
        f.api.loadInstalledPacks(f.owner, kind, responses::add)
        f.me()
        val firstRead = f.last
        assertIs<TdApi.GetInstalledStickerSets>(firstRead.command)
        f.api.acceptCatalogUpdate(TdApi.UpdateInstalledStickerSets(TdApi.StickerTypeRegular(), longArrayOf(20)))
        val result = TdApi.StickerSets(1, arrayOf(packInfo(20, kind)))
        firstRead.answer(result)
        assertTrue(responses.isEmpty())
        assertNotSame(firstRead, f.last)
        assertIs<TdApi.GetInstalledStickerSets>(f.last.command)
        f.last.answer(result)
        assertEquals(1, assertNotNull(responses.single().value).packs.size)
    }

    @Test fun initialDefaultUpdateRechecksAndPersistentUpdatesReturnABoundedError() {
        val f = Fixture()
        val responses = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.api.loadDefault(f.owner, responses::add)
        f.me()
        repeat(3) {
            val read = f.last
            assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(read.command)
            f.api.acceptCatalogUpdate(TdApi.UpdateStickerSet(TdApi.StickerSet()))
            read.answer(TdApi.Stickers(arrayOf(custom(700))))
        }
        assertEquals(SettingsError.RATE_LIMITED, responses.single().error)
        assertNull(responses.single().value)
        assertEquals(3, f.requests.count { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
    }

    @Test fun saveLeaseRevokedDuringSourceReadCannotReviveOrCloseThePicker() {
        val f = Fixture()
        val selection = f.choose(f.defaults().candidates.single())
        val lease = AtomicBoolean(true)
        val original = mutableListOf<SettingsResult<Unit>>()
        f.api.save(f.owner, selection, 91, background(1), lease::get, original::add)
        f.me()
        val source = f.last
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(source.command)
        lease.set(false)
        assertTrue(original.isEmpty(), "The helper observes the lease at native boundaries without polling")
        source.answer(TdApi.Stickers(arrayOf(custom(700))))
        assertEquals(SettingsError.NOT_READY, original.single().error)
        val before = f.requests.size
        lease.set(true)
        source.answer(TdApi.Stickers(arrayOf(custom(700)))); source.failed()
        assertEquals(1, original.size)
        assertEquals(before, f.requests.size)

        // The selector and same selection remain valid for a new, independent save owner.
        val retry = mutableListOf<SettingsResult<Unit>>()
        f.api.save(f.owner, selection, 91, background(1), lease::get, retry::add)
        f.completeDefaultPreflight()
        assertIs<TdApi.SetProfilePhoto>(f.last.command)
        f.last.answer(TdApi.Ok())
        assertEquals(Unit, retry.single().value)
    }

    @Test fun saveLeaseIsRecheckedBeforeWriteAndLateOkStillBelongsToItsOriginalSave() {
        val f = Fixture()
        val selection = f.choose(f.defaults().candidates.single())
        var armed = false
        var armedChecks = 0
        val original = mutableListOf<SettingsResult<Unit>>()
        f.api.save(f.owner, selection, 91, background(1), {
            if (!armed) true else ++armedChecks == 1
        }, original::add)
        f.me()
        f.last.answer(TdApi.Stickers(arrayOf(custom(700))))
        armed = true
        f.me()
        assertEquals(2, armedChecks, "The final self callback and write admission each check the save lease")
        assertEquals(SettingsError.NOT_READY, original.single().error)
        assertTrue(f.requests.none { it.command is TdApi.SetProfilePhoto })

        val before = f.requests.size
        val thrown = mutableListOf<SettingsResult<Unit>>()
        f.api.save(f.owner, selection, 91, background(1), { error("inactive lease") }, thrown::add)
        assertEquals(SettingsError.NOT_READY, thrown.single().error)
        assertEquals(before, f.requests.size)

        val lease = AtomicBoolean(true)
        val retry = mutableListOf<SettingsResult<Unit>>()
        f.api.save(f.owner, selection, 91, background(1), lease::get, retry::add)
        f.completeDefaultPreflight()
        val dispatched = f.last
        assertIs<TdApi.SetProfilePhoto>(dispatched.command)
        lease.set(false)
        dispatched.answer(TdApi.Ok()); dispatched.failed()
        assertEquals(Unit, retry.single().value)
        assertEquals(1, retry.size)

        // A caller-supplied save lease can reenter from a different thread without helper-lock deadlock.
        val g = Fixture()
        val anotherSelection = g.choose(g.defaults().candidates.single())
        val reentered = AtomicBoolean(false)
        val canceled = mutableListOf<SettingsResult<Unit>>()
        g.api.save(g.owner, anotherSelection, 91, background(1), {
            reentered.set(onAnotherThread { g.api.close(g.owner) })
            false
        }, canceled::add)
        assertTrue(reentered.get())
        assertEquals(SettingsError.NOT_READY, canceled.single().error)
    }

    private class Fixture(private val throwOnWrite: Boolean = false) {
        var active = true
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        val api = OfficialTdProfileStickerAvatar({ command, done, failed ->
            requests += Request(command, done, failed)
            if (throwOnWrite && command is TdApi.SetProfilePhoto) error("unknown write dispatch status")
        })
        val owner = assertNotNull(api.open(43) { active })

        fun defaults(stickers: Array<TdApi.Sticker> = arrayOf(custom(700))): ProfileStickerAvatarCandidatePage {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            api.loadDefault(owner, results::add)
            me()
            assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(last.command)
            last.answer(TdApi.Stickers(stickers))
            return assertNotNull(results.single().value)
        }

        fun packs(kind: ProfileStickerAvatarPackKind, sets: Array<TdApi.StickerSetInfo>): ProfileStickerAvatarPackPage {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
            api.loadInstalledPacks(owner, kind, results::add)
            me()
            last.answer(TdApi.StickerSets(sets.size, sets))
            return assertNotNull(results.single().value)
        }

        fun openPack(pack: ProfileStickerAvatarPack, native: TdApi.StickerSet): ProfileStickerAvatarCandidatePage {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            api.openPack(owner, pack.ticket, results::add)
            me()
            assertEquals(native.id, assertIs<TdApi.GetStickerSet>(last.command).setId)
            last.answer(native)
            return assertNotNull(results.single().value)
        }

        fun window(cursor: ProfileStickerAvatarCatalogCursor, response: TdApi.Object): ProfileStickerAvatarCatalogWindow {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
            api.loadWindow(owner, cursor, results::add)
            me()
            last.answer(response)
            return assertNotNull(results.single().value)
        }

        fun choose(candidate: ProfileStickerAvatarCandidate): ProfileStickerAvatarSelectionTicket =
            assertNotNull(api.select(owner, candidate.ticket))

        fun save(selection: ProfileStickerAvatarSelectionTicket, background: ProfileStickerAvatarBackground,
            expectedPhotoId: Long? = 91,
        ): MutableList<SettingsResult<Unit>> {
            val results = mutableListOf<SettingsResult<Unit>>()
            api.save(owner, selection, expectedPhotoId, background, results::add)
            return results
        }

        fun me(photo: Long? = 91, id: Long = 43) {
            assertIs<TdApi.GetMe>(last.command)
            last.answer(user(photo, id))
        }

        fun completeDefaultPreflight(photo: Long? = 91) {
            me(photo)
            assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(last.command)
            last.answer(TdApi.Stickers(arrayOf(custom(700))))
            me(photo)
        }
    }

    private data class Request(val command: TdApi.Function<*>, val answer: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private companion object {
        fun user(photo: Long? = 91, id: Long = 43) = TdApi.User().apply {
            this.id = id
            profilePhoto = photo?.let { TdApi.ProfilePhoto().apply { this.id = it } }
        }
        fun background(vararg colors: Int) = assertNotNull(ProfileStickerAvatarBackground.fromColors(colors.toList()))
        fun custom(id: Long, stickerId: Long = 17, setId: Long = 18, fileId: Int = 19) = TdApi.Sticker().apply {
            this.id = stickerId; this.setId = setId; width = 100; height = 100
            fullType = TdApi.StickerFullTypeCustomEmoji(id, true)
            sticker = TdApi.File().apply { this.id = fileId }
        }
        fun regular(id: Long, setId: Long) = TdApi.Sticker().apply {
            this.id = id; this.setId = setId; width = 100; height = 100; fullType = TdApi.StickerFullTypeRegular()
        }
        fun mediaFile(fileId: Int, length: Long) = TdApi.File().apply {
            id = fileId; size = length; expectedSize = length
            local = TdApi.LocalFile().apply { canBeDownloaded = true; isDownloadingCompleted = true; downloadedSize = length }
            remote = TdApi.RemoteFile().apply { uniqueId = "synthetic-$fileId" }
        }
        fun packInfo(id: Long, kind: ProfileStickerAvatarPackKind) = TdApi.StickerSetInfo().apply {
            this.id = id; title = "synthetic"; size = 1; isInstalled = true; stickerType = type(kind)
        }
        fun pack(id: Long, kind: ProfileStickerAvatarPackKind, stickers: Array<TdApi.Sticker>) = TdApi.StickerSet().apply {
            this.id = id; isInstalled = true; stickerType = type(kind); this.stickers = stickers
        }
        fun packSticker(setId: Long, id: Long, kind: ProfileStickerAvatarPackKind) = TdApi.Sticker().apply {
            this.id = id; this.setId = setId; width = 100; height = 100
            fullType = when (kind) {
                ProfileStickerAvatarPackKind.REGULAR -> TdApi.StickerFullTypeRegular()
                ProfileStickerAvatarPackKind.MASK -> TdApi.StickerFullTypeMask()
                ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> TdApi.StickerFullTypeCustomEmoji(id, false)
            }
        }
        fun type(kind: ProfileStickerAvatarPackKind): TdApi.StickerType = when (kind) {
            ProfileStickerAvatarPackKind.REGULAR -> TdApi.StickerTypeRegular()
            ProfileStickerAvatarPackKind.MASK -> TdApi.StickerTypeMask()
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> TdApi.StickerTypeCustomEmoji()
        }
        fun onAnotherThread(action: () -> Unit): Boolean {
            val done = CountDownLatch(1)
            Thread({ try { action() } finally { done.countDown() } }, "profile-sticker-reentry-test")
                .apply { isDaemon = true; start() }
            return done.await(2, TimeUnit.SECONDS)
        }
    }
}
