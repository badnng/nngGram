package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real session/settings/helper composition, with only the terminal native send replaced. */
class OfficialTdProfileStickerAvatarSessionGateTest {
    @Test fun readySessionLoadsProfileDefaultsWithTheNativeCustomEmojiIdentity() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
        f.avatar.loadDefault(f.picker, results::add)
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.reply(self())
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
        f.last.reply(defaults())
        val candidate = assertNotNull(results.single().value).candidates.single()
        assertEquals(ProfileStickerAvatarIdentity.CustomEmoji(-700), candidate.identity)
        assertEquals(18L, candidate.nativeStickerId)
        assertEquals(0L, candidate.nativeSetId)
        assertTrue(candidate.needsRepainting)
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
    }

    @Test fun initialReadyMandatoryRetiredSettingsAndClosedSessionRejectBeforeGetMe() {
        for (gate in 0..3) {
            val f = Fixture()
            f.stop(gate)
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            f.avatar.loadDefault(f.picker, results::add)
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error }, "initial gate=$gate")
            assertTrue(f.calls.none { it.command is TdApi.GetMe || it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
            assertEquals(if (gate == 3) 1 else 0, f.calls.count { it.command is TdApi.Close })
            if (gate >= 2) assertNull(f.avatar.open(43) { true })
        }
    }

    @Test fun continuationAfterGetMeRechecksReadyMandatorySettingsAndSessionLifetimes() {
        for (gate in 0..3) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            f.avatar.loadDefault(f.picker, results::add)
            val getMe = f.last
            assertIs<TdApi.GetMe>(getMe.command)
            f.stop(gate)
            getMe.reply(self()); getMe.reply(self()); getMe.failed()
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error }, "continuation gate=$gate")
            assertEquals(1, f.calls.count { it.command is TdApi.GetMe })
            assertTrue(f.calls.none { it.command is TdApi.GetDefaultProfilePhotoCustomEmojiStickers })
            assertEquals(if (gate == 3) 1 else 0, f.calls.count { it.command is TdApi.Close })
        }
    }

    @Test fun readySaveUsesFreshSelfTwiceAndExactPrivateStickerMarkup() {
        val f = Fixture()
        val selection = f.selectDefault()
        val start = f.calls.size
        val results = f.save(selection)
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.reply(self())
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(f.last.command)
        f.last.reply(defaults())
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.reply(self())
        val set = assertIs<TdApi.SetProfilePhoto>(f.last.command)
        assertFalse(set.isPublic)
        val sticker = assertIs<TdApi.InputChatPhotoSticker>(set.photo).sticker
        assertEquals(-700L, assertIs<TdApi.ChatPhotoStickerTypeCustomEmoji>(sticker.type).customEmojiId)
        val gradient = assertIs<TdApi.BackgroundFillGradient>(sticker.backgroundFill)
        assertEquals(0x123456, gradient.topColor)
        assertEquals(0xFEDCBA, gradient.bottomColor)
        assertEquals(0, gradient.rotationAngle)
        assertTrue(results.isEmpty(), "Admission and fake dispatch are not native success")
        f.last.reply(TdApi.Ok())
        assertEquals(Unit, results.single().value)
        val commands = f.calls.drop(start).map { it.command }
        assertEquals(4, commands.size)
        assertIs<TdApi.GetMe>(commands[0])
        assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(commands[1])
        assertIs<TdApi.GetMe>(commands[2])
        assertIs<TdApi.SetProfilePhoto>(commands[3])
        assertTrue(f.calls.none { it.command is TdApi.GetFile || it.command is TdApi.DownloadFile })
    }

    @Test fun sourceRevalidationStopsAtEveryGateAndAtTheIndependentSaveLease() {
        for (gate in 0..4) {
            val f = Fixture()
            val selection = f.selectDefault()
            val saveLease = AtomicBoolean(true)
            val results = f.save(selection, saveLease)
            assertIs<TdApi.GetMe>(f.last.command)
            f.last.reply(self())
            val source = f.last
            assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(source.command)
            val before = f.calls.size
            if (gate == 4) saveLease.set(false) else f.stop(gate)
            source.reply(defaults()); source.reply(defaults()); source.failed()
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error }, "source gate=$gate")
            assertEquals(before + if (gate == 3) 1 else 0, f.calls.size)
            assertTrue(f.calls.none { it.command is TdApi.SetProfilePhoto })
            if (gate == 4) {
                // Save revocation must not retire the picker or its current selection.
                val retry = f.save(selection, AtomicBoolean(true))
                assertIs<TdApi.GetMe>(f.last.command)
                f.last.reply(self()); f.last.reply(defaults()); f.last.reply(self())
                assertIs<TdApi.SetProfilePhoto>(f.last.command)
                f.last.reply(TdApi.Ok())
                assertEquals(Unit, retry.single().value)
            }
        }
    }

    @Test fun finalSelfCallbackCannotDispatchAWriteAfterAnyGateOrSaveRevocation() {
        for (gate in 0..4) {
            val f = Fixture()
            val selection = f.selectDefault()
            val saveLease = AtomicBoolean(true)
            val results = f.save(selection, saveLease)
            f.last.reply(self())
            f.last.reply(defaults())
            val finalSelf = f.last
            assertIs<TdApi.GetMe>(finalSelf.command)
            val before = f.calls.size
            if (gate == 4) saveLease.set(false) else f.stop(gate)
            finalSelf.reply(self()); finalSelf.reply(self()); finalSelf.failed()
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error }, "final gate=$gate")
            assertEquals(before + if (gate == 3) 1 else 0, f.calls.size)
            assertTrue(f.calls.none { it.command is TdApi.SetProfilePhoto })
        }
    }

    @Test fun dispatchedWriteKeepsLateOkAcrossSettingsRetirementOrSessionClose() {
        for (closeSession in listOf(false, true)) {
            val f = Fixture()
            val selection = f.selectDefault()
            val saveLease = AtomicBoolean(true)
            val results = f.save(selection, saveLease)
            f.last.reply(self()); f.last.reply(defaults()); f.last.reply(self())
            val write = f.last
            assertIs<TdApi.SetProfilePhoto>(write.command)
            if (closeSession) { f.session.close(); f.session.close() } else f.settings.retire()
            saveLease.set(false)
            assertTrue(results.isEmpty(), "Retirement must not invent a result for the dispatched write")
            write.reply(TdApi.Ok()); write.reply(TdApi.Error(500, "private error")); write.failed()
            assertEquals(1, results.size)
            assertEquals(Unit, results.single().value)
            assertEquals(if (closeSession) 1 else 0, f.calls.count { it.command is TdApi.Close })
            val before = f.calls.size
            val after = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            f.avatar.loadDefault(f.picker, after::add)
            assertEquals(listOf(SettingsError.NOT_READY), after.map { it.error })
            assertNull(f.avatar.open(43) { true })
            assertEquals(before, f.calls.size)
        }
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private class Fixture {
        var ready = true
        val gate = TdUserInteractionGate()
        private val suggestions = TdLoginEmailSuggestions(gate)
        val calls = mutableListOf<Call>()
        val session = OfficialTdSession(offline = true, interactionGate = gate,
            loginEmailSuggestions = suggestions, isAuthorizationReady = { ready },
            send = { command, reply, failed -> calls += Call(command, reply, failed) })
        val settings = session.settings
        val avatar = checkNotNull(settings.profileExtras?.stickerAvatar)
        val picker = checkNotNull(avatar.open(43) { true })
        val last get() = calls.last()

        fun stop(which: Int) {
            when (which) {
                0 -> ready = false
                1 -> suggestions.accept(TdApi.UpdateSuggestedActions(
                    arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
                2 -> settings.retire()
                3 -> session.close()
                else -> error("Unknown synthetic gate")
            }
        }

        fun selectDefault(): ProfileStickerAvatarSelectionTicket {
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
            avatar.loadDefault(picker, results::add)
            assertIs<TdApi.GetMe>(last.command)
            last.reply(self())
            assertIs<TdApi.GetDefaultProfilePhotoCustomEmojiStickers>(last.command)
            last.reply(defaults())
            val candidate = assertNotNull(results.single().value).candidates.single()
            return assertNotNull(avatar.select(picker, candidate.ticket))
        }

        fun save(selection: ProfileStickerAvatarSelectionTicket,
            lease: AtomicBoolean = AtomicBoolean(true),
        ): MutableList<SettingsResult<Unit>> {
            val results = mutableListOf<SettingsResult<Unit>>()
            val background = checkNotNull(ProfileStickerAvatarBackground.fromColors(listOf(0x123456, 0xFEDCBA)))
            avatar.save(picker, selection, -911, background, lease::get, results::add)
            return results
        }
    }

    private companion object {
        fun self() = TdApi.User().apply {
            id = 43
            profilePhoto = TdApi.ProfilePhoto().apply { id = -911 }
        }

        fun defaults() = TdApi.Stickers(arrayOf(TdApi.Sticker().apply {
            id = 18
            setId = 0
            width = 100
            height = 100
            fullType = TdApi.StickerFullTypeCustomEmoji(-700, true)
            sticker = TdApi.File().apply { id = 3456 }
        }))
    }
}
