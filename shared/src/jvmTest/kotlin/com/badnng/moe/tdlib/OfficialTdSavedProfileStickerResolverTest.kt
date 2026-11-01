package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.chat.MediaFile
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Read-only lookup and physical-callback boundaries; no account is used. */
class OfficialTdSavedProfileStickerResolverTest {
    @Test fun regularAndMaskResolveFromTheRealUninstalledArchivedSet() {
        for (mask in listOf(false, true)) {
            val f = Fixture(); f.resolve(regular)
            assertEquals(-10L, assertIs<TdApi.GetStickerSet>(f.command).setId)
            f.reply(TdApi.StickerSet().apply {
                id = -10; isInstalled = false; isArchived = true
                stickerType = if (mask) TdApi.StickerTypeMask() else TdApi.StickerTypeRegular()
                stickers = arrayOf(item(-20, -10, if (mask) TdApi.StickerFullTypeMask() else TdApi.StickerFullTypeRegular()))
            })
            val found = assertNotNull(f.results.single().value)
            assertEquals(7, found.full.file.id); assertEquals("actual", found.full.file.uniqueId)
            assertFalse(found.repaint)
        }
    }

    @Test fun setTypeIdentityAndDuplicatesCannotBeGuessed() {
        for (variant in 0..2) {
            val f = Fixture(); f.resolve(regular)
            val a = item(-20, -10, if (variant == 1) TdApi.StickerFullTypeMask() else TdApi.StickerFullTypeRegular())
            f.reply(TdApi.StickerSet().apply {
                id = if (variant == 0) -11 else -10; stickerType = TdApi.StickerTypeRegular()
                stickers = if (variant == 2) arrayOf(a, a) else arrayOf(a)
            })
            assertEquals(SettingsError.CONFLICT, f.results.single().error)
        }
    }

    @Test fun customMatchesItsOwnIdNotArrayOrderAndRetainsNativeRepainting() {
        val f = Fixture(); f.resolve(emoji)
        assertContentEquals(longArrayOf(-30), assertIs<TdApi.GetCustomEmojiStickers>(f.command).customEmojiIds)
        f.reply(TdApi.Stickers(arrayOf(item(1, 2, TdApi.StickerFullTypeCustomEmoji(999, false)),
            item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)))))
        assertTrue(assertNotNull(f.results.single().value).repaint)
    }

    @Test fun unknownUniqueIsRetryableAndLateReplyCannotPublish() {
        val f = Fixture(); f.resolve(emoji)
        f.reply(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)).apply { sticker.remote.uniqueId = "" })))
        assertEquals(SettingsError.NOT_READY, f.results.single().error)
        val old = Fixture(); var active = true; old.resolve(emoji) { active }; active = false
        old.reply(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)))))
        assertEquals(SettingsError.NOT_READY, old.results.single().error)
    }

    @Test fun knownIdentityAndSizeAreCheckedBeforeNativeValidation() {
        val first = item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)).apply { sticker.size = 128 }
        val facts = SavedProfileStickerFacts()
        assertNotNull(facts.accept(checkNotNull(profileStickerAvatarFingerprint(first))).value)
        val unknown = item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)).apply { sticker.remote.uniqueId = "" }
        val changed = item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)).apply { sticker.size = 25L * 1024 * 1024 }
        val compatible = item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)).apply { sticker.size = 0 }
        assertEquals(SettingsError.CONFLICT, facts.checkNative(unknown))
        assertEquals(SettingsError.CONFLICT, facts.checkNative(changed))
        assertNull(facts.checkNative(compatible))
        assertEquals(SettingsError.CONFLICT, facts.accept(checkNotNull(profileStickerAvatarFingerprint(changed))).error)
        assertEquals(SettingsError.INVALID_VALUE, SavedProfileStickerFacts().accept(checkNotNull(profileStickerAvatarFingerprint(changed))).error)
    }

    @Test fun unknownSizeMayBecomeKnownAndTransferEstimatesDoNotChangeIdentity() {
        val facts = SavedProfileStickerFacts()
        val initial = item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true))
        assertNotNull(facts.accept(checkNotNull(profileStickerAvatarFingerprint(initial))).value)
        val observed = MediaFile(7, size = 100, uniqueId = "actual")
        assertEquals(Unit, facts.observe(observed).value)
        assertEquals(Unit, facts.observe(observed.copy(expectedSize = 500, localPath = "moved", downloadedSize = 17)).value)
        assertNull(facts.checkNative(initial), "an older metadata snapshot may still omit the known size")
        assertEquals(100L, facts.accept(checkNotNull(profileStickerAvatarFingerprint(initial))).value?.asset?.file?.size)
        assertTrue(facts.current())
    }

    @Test fun uncertainDispatchRetainsTerminalUntilTheActualCallback() {
        lateinit var late: (TdApi.Object) -> Unit
        val resolver = OfficialTdSavedProfileStickerResolver { _, callback, _ -> late = callback; error("sent before throw") }
        var terminal = 0
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarMediaFingerprint>>()
        resolver.resolveOwned(emoji, { true }, { terminal++ }, result = results::add)
        assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
        assertEquals(0, terminal)
        late(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)))))
        late(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)))))
        assertEquals(1, terminal); assertEquals(1, results.size)

        lateinit var lateFile: (TdApi.Object) -> Unit
        val fileResolver = OfficialTdSavedProfileStickerResolver { _, callback, _ -> lateFile = callback; error("sent before throw") }
        var fileTerminal = 0; val files = mutableListOf<SettingsResult<MediaFile>>()
        fileResolver.freshFileOwned(7, { true }, { fileTerminal++ }, files::add)
        assertEquals(SettingsError.REQUEST_FAILED, files.single().error); assertEquals(0, fileTerminal)
        lateFile(TdApi.File().apply { id = 7 }); lateFile(TdApi.File().apply { id = 7 })
        assertEquals(1, fileTerminal); assertEquals(1, files.size)
    }

    @Test fun conflictFromLaterNativeLookupRevokesCapturedSourceBeforeConsumerDelivery() {
        for (known in listOf(false, true)) {
            val facts = SavedProfileStickerFacts()
            if (known) assertNotNull(facts.accept(checkNotNull(profileStickerAvatarFingerprint(
                item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true))))).value)
            var active = true
            val captured = SavedProfileStickerCaptured(emoji, { active }, { active }, facts, { active = false })
            lateinit var reply: (TdApi.Object) -> Unit
            val resolver = OfficialTdSavedProfileStickerResolver { _, callback, _ -> reply = callback }
            val pending = ArrayDeque<() -> Unit>()
            val async = object : ProfileStickerAvatarMediaAsync {
                override fun reserveConsumer() = object : ProfileStickerAvatarMediaConsumerPermit {
                    override fun deliver(action: () -> Unit, exited: () -> Unit) {
                        pending.addLast { try { action() } finally { exited() } }
                    }
                }
                override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) {
                    pending.addLast { try { action() } finally { exited() } }
                }
            }
            val source = OfficialTdSavedProfileStickerFileSource(captured, { active }, { active }, resolver, async)
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
            var terminal = 0
            assertEquals(ProfileStickerAvatarMediaAdmission.ACCEPTED, source.resolve({ active }, { terminal++ }, results::add))
            reply(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true))
                .apply { sticker.remote.uniqueId = "" })))
            assertEquals(!known, active, "known source is invalid before the consumer runs")
            assertTrue(results.isEmpty()); assertEquals(0, terminal)
            while (pending.isNotEmpty()) pending.removeFirst()()
            assertEquals(if (known) SettingsError.CONFLICT else SettingsError.NOT_READY, results.single().error)
            assertEquals(1, terminal)
        }
    }

    @Test fun freshFilesAndBrokerFactsUseTheSameKnownSizeAcrossReads() {
        val calls = mutableListOf<Pair<TdApi.Function<*>, (TdApi.Object) -> Unit>>()
        val resolver = OfficialTdSavedProfileStickerResolver { request, reply, _ -> calls += request to reply }
        val pending = ArrayDeque<() -> Unit>()
        val async = object : ProfileStickerAvatarMediaAsync {
            override fun reserveConsumer() = object : ProfileStickerAvatarMediaConsumerPermit {
                override fun deliver(action: () -> Unit, exited: () -> Unit) { pending.addLast { try { action() } finally { exited() } } }
            }
            override fun dispatch(action: () -> Unit, exited: () -> Unit, rejected: () -> Unit) = Unit
        }
        var live = true; var terminals = 0
        val facts = SavedProfileStickerFacts()
        val captured = SavedProfileStickerCaptured(emoji, { live }, { live }, facts, { live = false })
        val source = OfficialTdSavedProfileStickerFileSource(captured, { live }, { live }, resolver, async)
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarOwnedMedia>>()
        fun resolve() {
            assertEquals(ProfileStickerAvatarMediaAdmission.ACCEPTED, source.resolve({ live }, { terminals++ }, results::add))
            assertIs<TdApi.GetCustomEmojiStickers>(calls.last().first)
            calls.last().second(TdApi.Stickers(arrayOf(item(3, 4, TdApi.StickerFullTypeCustomEmoji(-30, true)))))
            assertEquals(7, assertIs<TdApi.GetFile>(calls.last().first).fileId)
        }
        fun file(size: Long) = TdApi.File().apply {
            id = 7; this.size = size; remote = TdApi.RemoteFile().apply { uniqueId = "actual" }
        }
        resolve(); calls.last().second(file(0)); while (pending.isNotEmpty()) pending.removeFirst()()
        assertEquals(0L, results.single().value?.asset?.file?.size); assertEquals(1, terminals)
        assertEquals(Unit, source.observe(MediaFile(7, size = 100, uniqueId = "actual")).value)
        resolve(); calls.last().second(file(200))
        assertFalse(live); assertEquals(1, results.size)
        while (pending.isNotEmpty()) pending.removeFirst()()
        assertEquals(SettingsError.CONFLICT, results.last().error); assertEquals(2, terminals)
    }

    private class Fixture {
        lateinit var command: TdApi.Function<*>; lateinit var reply: (TdApi.Object) -> Unit
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarMediaFingerprint>>()
        private val api = OfficialTdSavedProfileStickerResolver { request, callback, _ -> command = request; reply = callback }
        fun resolve(photo: SettingsProfilePhotoSticker, active: () -> Boolean = { true }) = api.resolve(photo, active, results::add)
    }
    private companion object {
        val background = SettingsProfilePhotoSticker.Background.Solid(0)
        val regular = SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.RegularOrMask(-10, -20), background)
        val emoji = SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.CustomEmoji(-30), background)
        fun item(id: Long, set: Long, type: TdApi.StickerFullType) = TdApi.Sticker().apply {
            this.id = id; setId = set; width = 160; height = 160; fullType = type; format = TdApi.StickerFormatWebp()
            sticker = TdApi.File().apply { this.id = 7; remote = TdApi.RemoteFile().apply { uniqueId = "actual" } }
        }
    }
}
