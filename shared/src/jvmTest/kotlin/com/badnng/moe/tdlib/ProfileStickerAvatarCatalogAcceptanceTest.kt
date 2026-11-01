package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic native values only. This fake makes no claim about TDLib's receiver ordering. */
class ProfileStickerAvatarCatalogAcceptanceTest {
    @Test fun completeLocalSourcesReachFarSelectionAndRevalidateItsFullMediaAndSave() {
        // Every source contains an actual item beyond both former 512 and 4096 limits.
        for (kind in listOf(null, ProfileStickerAvatarPackKind.REGULAR,
            ProfileStickerAvatarPackKind.MASK, ProfileStickerAvatarPackKind.CUSTOM_EMOJI)) {
            val f = Fixture(kind, 4097)
            val first = f.openSource()
            assertNull(first.previous, "First window: $kind")
            val visited = mutableListOf<Long>()
            var page = first
            while (true) {
                visited += page.candidates.map { id(it.identity) }
                val cursor = page.next ?: break
                page = f.window(cursor)
            }
            assertEquals((1L..4097L).toList(), visited, "Native order and final item: $kind")
            assertEquals(listOf(4097L), page.candidates.map { id(it.identity) })
            assertNull(page.next)

            val candidate = page.candidates.single()
            val selection = assertNotNull(f.api.select(f.picker, candidate.ticket))
            val thumbnail = assertNotNull(f.api.thumbnailMedia(f.picker, candidate.ticket))
            val originalFull = assertNotNull(f.api.fullMedia(f.picker, selection))
            val preceding = f.window(assertNotNull(page.previous))
            assertEquals(4096L, id(preceding.candidates.last().identity))
            assertFalse(thumbnail.atomicCurrent(), "A row's thumbnail grant ends at its window")
            assertFalse(originalFull.atomicCurrent(), "The prior full grant must be reacquired")
            assertNull(f.api.select(f.picker, candidate.ticket), "An evicted row cannot be selected again")

            val full = assertNotNull(f.api.fullMedia(f.picker, selection), "Selection has independent ownership")
            val media = f.resolve(full)
            assertNull(media.error, "Full source revalidation after paging: $kind")
            assertEquals(ProfileStickerAvatarMediaRole.FULL, assertNotNull(media.value).role)
            assertEquals(FULL_FILE, media.value!!.asset.file.id)

            val saved = f.save(selection)
            assertEquals(Unit, saved.single().value, "The entire source must also be rechecked for Save: $kind")
            val native = assertIs<TdApi.InputChatPhotoSticker>(f.writes.single().photo).sticker.type
            when (kind) {
                null, ProfileStickerAvatarPackKind.CUSTOM_EMOJI ->
                    assertEquals(4097L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(native).customEmojiId)
                else -> {
                    val value = assertIs<TdApi.ChatPhotoStickerTypeRegularOrMask>(native)
                    assertEquals(PACK_ID, value.stickerSetId)
                    assertEquals(4097L, value.stickerId)
                }
            }
            assertTrue(f.commands.none { it is TdApi.GetFile || it is TdApi.DownloadFile },
                "Reading the directory and checking its grants must not download all items")
            f.api.retire()
        }
    }

    @Test fun emptySingleAndBoundaryWindowsExposeOnlyActualEntries() {
        for (count in listOf(0, 1, 63, 64, 65, 127, 128, 129)) {
            val f = Fixture(null, count)
            var page = f.openSource()
            assertNull(page.previous)
            val actual = mutableListOf<Long>()
            do {
                assertTrue(page.candidates.size <= 64)
                actual += page.candidates.map { id(it.identity) }
                val next = page.next
                if (next == null) break
                page = f.window(next)
            } while (true)
            assertEquals((1L..count.toLong()).toList(), actual, "count=$count")
            assertEquals(count > 64, page.previous != null, "count=$count")
            assertNull(page.next)
            assertTrue(f.commands.none { it is TdApi.GetFile || it is TdApi.DownloadFile })
            f.api.retire()
        }
    }

    @Test fun changedTailOrderSelectedFingerprintOrDuplicateIdentityCannotReuseTheOldSelection() {
        for (kind in listOf(null, ProfileStickerAvatarPackKind.REGULAR,
            ProfileStickerAvatarPackKind.MASK, ProfileStickerAvatarPackKind.CUSTOM_EMOJI)) {
            for (change in listOf("tail-order", "selected-fingerprint", "duplicate")) {
                val f = Fixture(kind, 4097)
                val first = f.openSource()
                val selection = assertNotNull(f.api.select(f.picker, first.candidates.first().ticket))
                val full = assertNotNull(f.api.fullMedia(f.picker, selection))
                val changed = f.stickers.copyOf()
                when (change) {
                    "tail-order" -> { val last = changed[4096]; changed[4096] = changed[4095]; changed[4095] = last }
                    "selected-fingerprint" -> changed[0] = sticker(1, kind, "changed")
                    "duplicate" -> changed[4096] = sticker(1, kind, "duplicate")
                }
                f.stickers = changed
                val media = f.resolve(full)
                assertNotNull(media.error, "Old media grant after $change: $kind")
                assertNull(media.value)
                val saved = f.save(selection)
                assertNotNull(saved.single().error, "Save must revalidate the whole original source after $change: $kind")
                assertTrue(f.writes.isEmpty(), "No native write may be dispatched with a changed source")
                assertTrue(f.commands.none { it is TdApi.GetFile || it is TdApi.DownloadFile })
                f.api.retire()
            }
        }
    }

    @Test fun unknownFileSizeBecomingKnownDoesNotInvalidateWindowsMediaOrSave() {
        for (kind in listOf(null, ProfileStickerAvatarPackKind.REGULAR)) {
            val f = Fixture(kind, 65)
            val original = checkNotNull(f.stickers.first().sticker)
            original.size = 0; original.expectedSize = 0
            val first = f.openSource()
            val candidate = first.candidates.first()
            val selection = assertNotNull(f.api.select(f.picker, candidate.ticket))
            original.size = 64; original.expectedSize = 64
            val thumb = assertNotNull(f.api.thumbnailMedia(f.picker, candidate.ticket))
            assertNull(f.resolve(thumb).error, "Unknown to known size preserves the same asset")
            val next = f.window(assertNotNull(first.next))
            assertEquals(65L, id(next.candidates.single().identity))
            val full = assertNotNull(f.api.fullMedia(f.picker, selection))
            assertNull(f.resolve(full).error)
            assertEquals(Unit, f.save(selection).single().value)
            assertEquals(1, f.writes.size)
            f.api.retire()
        }
    }

    @Test fun twoDifferentKnownSelectedFileSizesCannotAuthorizeMediaOrSave() {
        for (kind in listOf(null, ProfileStickerAvatarPackKind.REGULAR)) {
            val f = Fixture(kind, 65)
            val first = f.openSource()
            val selection = assertNotNull(f.api.select(f.picker, first.candidates.first().ticket))
            val full = assertNotNull(f.api.fullMedia(f.picker, selection))
            checkNotNull(f.stickers.first().sticker).size = 65
            assertNotNull(f.resolve(full).error)
            assertNotNull(f.save(selection).single().error)
            assertTrue(f.writes.isEmpty())
            f.api.retire()
        }
    }

    private class Fixture(private val kind: ProfileStickerAvatarPackKind?, count: Int) {
        var stickers = Array(count) { sticker(it + 1, kind) }
        val commands = mutableListOf<TdApi.Function<*>>()
        val writes = mutableListOf<TdApi.SetProfilePhoto>()
        private val async = ManualAsync()
        val api = OfficialTdProfileStickerAvatar(async) { command, reply, failed ->
            commands += command
            when (command) {
                is TdApi.GetMe -> reply(TdApi.User().apply {
                    id = 43; profilePhoto = TdApi.ProfilePhoto().apply { id = 91 }
                })
                is TdApi.GetDefaultProfilePhotoCustomEmojiStickers -> reply(TdApi.Stickers(stickers))
                is TdApi.GetInstalledStickerSets -> reply(TdApi.StickerSets(1, arrayOf(packInfo(checkNotNull(kind)))))
                is TdApi.GetStickerSet -> reply(pack(checkNotNull(kind), stickers))
                is TdApi.SetProfilePhoto -> { writes += command; reply(TdApi.Ok()) }
                else -> failed()
            }
        }
        val picker = assertNotNull(api.open(43) { true })

        fun openSource(): ProfileStickerAvatarCandidatePage {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            if (kind == null) api.loadDefault(picker, result::add)
            else {
                val packs = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
                api.loadInstalledPacks(picker, kind, packs::add)
                api.openPack(picker, assertNotNull(packs.single().value).packs.single().ticket, result::add)
            }
            return assertNotNull(result.single().value)
        }

        fun window(cursor: ProfileStickerAvatarCatalogCursor): ProfileStickerAvatarCandidatePage {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
            api.loadWindow(picker, cursor, result::add)
            return assertIs<ProfileStickerAvatarCatalogWindow.Candidates>(result.single().value).page
        }

        fun resolve(grant: ProfileStickerAvatarMediaGrant): SettingsResult<ProfileStickerAvatarOwnedMedia> {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
            api.resolveMedia(grant, { true }, result::add)
            async.drain()
            return result.single()
        }

        fun save(selection: ProfileStickerAvatarSelectionTicket): List<SettingsResult<Unit>> {
            val results = mutableListOf<SettingsResult<Unit>>()
            api.save(picker, selection, 91, checkNotNull(ProfileStickerAvatarBackground.fromColors(listOf(1))), results::add)
            async.drain()
            return results
        }
    }

    /** Tasks and their exit callbacks run separately from the fake transport's reply stack. */
    private class ManualAsync : ProfileStickerAvatarMediaAsync {
        private val queue = ArrayDeque<() -> Unit>()
        override fun reserveConsumer(): ProfileStickerAvatarMediaConsumerPermit = object : ProfileStickerAvatarMediaConsumerPermit {
            override fun deliver(action: () -> Unit, exited: () -> Unit) { post(action, exited) }
        }
        override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) = post(action, exited)
        private fun post(action: () -> Unit, exited: () -> Unit) {
            queue.addLast { try { action() } finally { exited() } }
        }
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(++count < 1000); queue.removeFirst().invoke() } }
    }

    private companion object {
        const val PACK_ID = 42L
        const val FULL_FILE = 70
        fun id(identity: ProfileStickerAvatarIdentity): Long = when (identity) {
            is ProfileStickerAvatarIdentity.CustomEmoji -> identity.customEmojiId
            is ProfileStickerAvatarIdentity.RegularOrMask -> identity.stickerId
        }
        fun sticker(id: Int, kind: ProfileStickerAvatarPackKind?, suffix: String = "original") = TdApi.Sticker().apply {
            this.id = if (kind == null || kind == ProfileStickerAvatarPackKind.CUSTOM_EMOJI) 0 else id.toLong()
            setId = if (kind == null) 0 else PACK_ID
            width = 128; height = 96; format = TdApi.StickerFormatWebp()
            fullType = when (kind) {
                null, ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> TdApi.StickerFullTypeCustomEmoji(id.toLong(), true)
                ProfileStickerAvatarPackKind.REGULAR -> TdApi.StickerFullTypeRegular()
                ProfileStickerAvatarPackKind.MASK -> TdApi.StickerFullTypeMask()
            }
            // Only explicit targets have assets; catalog traversal itself does not need media.
            if (id == 1 || id == 4097) {
                sticker = file(FULL_FILE, "full-$id-$suffix")
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatWebp(), 32, 32, file(71, "thumb-$id-$suffix"))
            }
        }
        fun file(id: Int, unique: String) = TdApi.File().apply {
            this.id = id; size = 64; expectedSize = 64
            local = TdApi.LocalFile().apply { canBeDownloaded = true; isDownloadingCompleted = true; downloadedSize = 64 }
            remote = TdApi.RemoteFile().apply { uniqueId = unique }
        }
        fun nativeType(kind: ProfileStickerAvatarPackKind): TdApi.StickerType = when (kind) {
            ProfileStickerAvatarPackKind.REGULAR -> TdApi.StickerTypeRegular()
            ProfileStickerAvatarPackKind.MASK -> TdApi.StickerTypeMask()
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> TdApi.StickerTypeCustomEmoji()
        }
        fun packInfo(kind: ProfileStickerAvatarPackKind) = TdApi.StickerSetInfo().apply {
            id = PACK_ID; title = "synthetic"; name = "synthetic_pack"; size = 4097
            isInstalled = true; stickerType = nativeType(kind)
        }
        fun pack(kind: ProfileStickerAvatarPackKind, stickers: Array<TdApi.Sticker>) = TdApi.StickerSet().apply {
            id = PACK_ID; isInstalled = true; stickerType = nativeType(kind); this.stickers = stickers
        }
    }
}
