package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Metadata fixtures only; never instantiate JNI or access an account. */
class OfficialTdNotificationExceptionsTest {
    @Test fun listUsesActualScopeAndSoundComparisonWithAnExplicitBoundInsteadOfInventedPages() {
        for (scope in NotificationScope.entries) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<List<Long>>>()
            f.api.list(scope, f::active, results::add)
            val request = assertIs<TdApi.GetChatNotificationSettingsExceptions>(f.last.command)
            assertTrue(request.compareSound); assertEquals(scope.native().javaClass, request.scope.javaClass)
            f.last.reply(TdApi.Chats(3, longArrayOf(10, 11, 10)))
            assertEquals(listOf(10L, 11L), results.single().value); assertEquals(1, f.requests.size)
        }
        for (ids in listOf(LongArray(32769) { it + 1L }, longArrayOf(0))) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<List<Long>>>()
            f.api.list(NotificationScope.PRIVATE_CHATS, f::active, results::add); f.last.reply(TdApi.Chats(ids.size, ids))
            assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        }
    }

    @Test fun allSixteenFieldsRoundTripAndEverySparseGroupPreservesFreshUnrelatedFields() {
        val base = rich()
        assertEquals(base, nativeChatNotificationValues(base.native()))
        for (field in NotificationExceptionField.entries) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>()
            val fresh = base.copy(useDefaultMuteStories = true, muteStories = false,
                useDefaultStorySound = true, storySoundId = Long.MIN_VALUE, useDefaultShowStoryPoster = true, showStoryPoster = true)
            val desired = when (field) {
                NotificationExceptionField.MUTE -> base.copy(muteFor = 0)
                NotificationExceptionField.SOUND -> base.copy(soundId = Long.MIN_VALUE)
                NotificationExceptionField.PREVIEW -> base.copy(showPreview = !base.showPreview)
                NotificationExceptionField.PINNED -> base.copy(disablePinnedMessageNotifications = !base.disablePinnedMessageNotifications)
                NotificationExceptionField.MENTIONS -> base.copy(disableMentionNotifications = !base.disableMentionNotifications)
            }
            val expectedWritten = when (field) {
                NotificationExceptionField.MUTE -> fresh.copy(muteFor = desired.muteFor)
                NotificationExceptionField.SOUND -> fresh.copy(soundId = desired.soundId)
                NotificationExceptionField.PREVIEW -> fresh.copy(showPreview = desired.showPreview)
                NotificationExceptionField.PINNED -> fresh.copy(disablePinnedMessageNotifications = desired.disablePinnedMessageNotifications)
                NotificationExceptionField.MENTIONS -> fresh.copy(disableMentionNotifications = desired.disableMentionNotifications)
            }
            f.api.save(NotificationExceptionEdit(peer(base), desired, setOf(field)), f::active, results::add)
            f.readSteps(fresh)
            val native = assertIs<TdApi.SetChatNotificationSettings>(f.last.command)
            assertEquals(10L, native.chatId); assertEquals(expectedWritten, nativeChatNotificationValues(native.notificationSettings))
            f.last.reply(TdApi.Ok()); assertTrue(results.isEmpty())
            f.readSteps(expectedWritten)
            assertTrue(results.single().locallyAccepted); assertEquals(expectedWritten, results.single().actual?.values)
            f.assertMetadataOnly()
        }
    }

    @Test fun resetTouchesOnlyFiveNewMessageDefaultFlagsAndPreservesStoryChoicesAndHiddenFreshValues() {
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
        f.api.save(NotificationExceptionEdit(peer(base), base.newMessageDefaults(), NotificationExceptionField.entries.toSet()), f::active, results::add)
        f.readSteps(base)
        val written = nativeChatNotificationValues(assertIs<TdApi.SetChatNotificationSettings>(f.last.command).notificationSettings)
        assertEquals(base.newMessageDefaults(), written)
        assertFalse(written.hasNewMessageException)
        f.last.reply(TdApi.Ok()); f.readSteps(written)
        assertEquals(written, results.single().actual?.values)
        assertFalse(ChatNotificationValues(useDefaultMuteStories = false, muteStories = true).hasNewMessageException)
        assertTrue(ChatNotificationValues(useDefaultSound = false, soundId = Long.MIN_VALUE).hasNewMessageException)
    }

    @Test fun signedSoundIdsAreOpaqueAndMinusOneIsCanonicalInheritanceRatherThanAnIndependentOverride() {
        for (sound in listOf(Long.MIN_VALUE, -2L, -1L, 0L, Long.MAX_VALUE)) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
            f.api.save(NotificationExceptionEdit(peer(base), base.copy(soundId = sound), setOf(NotificationExceptionField.SOUND)), f::active, results::add)
            f.readSteps(base)
            val written = assertIs<TdApi.SetChatNotificationSettings>(f.last.command).notificationSettings
            assertEquals(sound == -1L, written.useDefaultSound)
            assertEquals(if (sound == -1L) base.soundId else sound, written.soundId)
            f.last.reply(TdApi.Ok()); f.readSteps(nativeChatNotificationValues(written))
            assertTrue(results.single().locallyAccepted)
        }
        val raw = rich().native().apply { useDefaultSound = false; soundId = -1 }
        assertTrue(nativeChatNotificationValues(raw).useDefaultSound)
        assertFalse(nativeChatNotificationValues(raw).copy(useDefaultMuteFor = true, useDefaultShowPreview = true,
            useDefaultDisablePinnedMessageNotifications = true, useDefaultDisableMentionNotifications = true).hasNewMessageException)
    }

    @Test fun onlyDirtyFieldsConflictWhileOrdinaryMuteCountdownAndNativeForeverDeadlineRemainEquivalent() {
        val now = System.currentTimeMillis()
        for ((expectedSeconds, sampled, freshSeconds, succeeds) in listOf(
            Quad(60, now - 10_000, 50, true), Quad(Int.MAX_VALUE, now - 10_000, 400_000_000, true),
            Quad(60, now - 10_000, 60, false), Quad(0, now, 60, false))) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>()
            val base = rich().copy(muteFor = expectedSeconds)
            f.api.save(NotificationExceptionEdit(peer(base, sampled), base.copy(muteFor = 0), setOf(NotificationExceptionField.MUTE)), f::active, results::add)
            f.readSteps(base.copy(muteFor = freshSeconds))
            if (succeeds) {
                assertIs<TdApi.SetChatNotificationSettings>(f.last.command)
                f.last.reply(TdApi.Error(500, "synthetic"))
            } else { assertEquals(SettingsError.CONFLICT, results.single().error); assertFalse(results.single().locallyAccepted) }
        }
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
        f.api.save(NotificationExceptionEdit(peer(base), base.copy(showPreview = !base.showPreview), setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
        f.readSteps(base.copy(soundId = Long.MIN_VALUE, muteFor = 1))
        val written = assertIs<TdApi.SetChatNotificationSettings>(f.last.command).notificationSettings
        assertEquals(Long.MIN_VALUE, written.soundId); assertEquals(1, written.muteFor)
    }

    @Test fun freshAndSetGapRejectsExternalNotificationOrProtectedContextUpdatesBeforeDispatch() {
        for (update in listOf<TdApi.Object>(TdApi.UpdateChatNotificationSettings(10, rich().native()),
            TdApi.UpdateScopeNotificationSettings(NotificationScope.PRIVATE_CHATS.native(), TdApi.ScopeNotificationSettings()),
            TdApi.UpdateUser(user(71).apply { type = TdApi.UserTypeDeleted() }),
            TdApi.UpdateOption("replies_bot_chat_id", TdApi.OptionValueInteger(999)))) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
            f.api.save(NotificationExceptionEdit(peer(base), base.copy(showPreview = !base.showPreview), setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
            f.context(); f.last.reply(chat(10, NotificationScope.PRIVATE_CHATS, base))
            assertIs<TdApi.GetUser>(f.last.command)
            f.api.accept(update); f.last.reply(user(71))
            assertEquals(SettingsError.CONFLICT, results.single().error)
            assertTrue(f.requests.none { it.command is TdApi.SetChatNotificationSettings })
        }
    }

    @Test fun actualPrivateGroupAndChannelIdentitiesStayChatIdsAndUseCurrentMembership() {
        for (scope in NotificationScope.entries) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
            f.api.read(scope, 10, f::active, results::add); f.readSteps(rich(), scope)
            val peer = assertNotNull(results.single().value)
            assertEquals(10L, peer.chatId); assertEquals(scope, peer.scope); assertTrue(peer.editable)
            assertTrue(f.requests.any { it.command is TdApi.GetChat && it.command.chatId == 10L })
            f.assertMetadataOnly()
        }
        val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
        f.api.read(NotificationScope.GROUP_CHATS, 10, f::active, results::add)
        f.context(); f.last.reply(chat(10, NotificationScope.GROUP_CHATS, rich()))
        f.last.reply(TdApi.BasicGroup().apply { id = 72; isActive = true; status = TdApi.ChatMemberStatusLeft() })
        assertEquals(SettingsError.FORBIDDEN, results.single().error)
    }

    @Test fun savedMessagesSpecialChatsWrongScopeDeletedUsersAndUnknownProtectedContextAreNeverWritable() {
        for (kind in 0..5) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
            f.api.read(NotificationScope.PRIVATE_CHATS, if (kind == 0) 900 else 10, f::active, results::add)
            if (kind == 5) {
                f.last.reply(user(1)); f.last.reply(TdApi.OptionValueEmpty())
            } else {
                f.context()
                if (kind != 0) {
                    val native = chat(10, if (kind == 2) NotificationScope.CHANNEL_CHATS else NotificationScope.PRIVATE_CHATS, rich())
                    if (kind == 1) native.type = TdApi.ChatTypePrivate(1)
                    if (kind == 4) native.id = 11
                    f.last.reply(native)
                    if (kind == 3) f.last.reply(user(71).apply { type = TdApi.UserTypeDeleted() })
                }
            }
            assertNull(results.single().value); assertNotNull(results.single().error)
            assertTrue(f.requests.none { it.command is TdApi.SetChatNotificationSettings })
        }
    }

    @Test fun selectorUsesOnlyBoundedMainArchiveOrExplicitServerSearchAndResolveBatchesStayBounded() {
        for (remote in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<List<NotificationExceptionRow>>>()
            f.api.search(NotificationScope.PRIVATE_CHATS, "needle", remote, f::active, results::add); f.context()
            if (remote) assertEquals(64, assertIs<TdApi.SearchChatsOnServer>(f.last.command).limit)
            else assertEquals(64, assertIs<TdApi.SearchChats>(f.last.command).limit)
            f.last.reply(TdApi.Chats(1, longArrayOf(10)))
            f.last.reply(chat(10, NotificationScope.PRIVATE_CHATS, rich())); f.last.reply(user(71))
            assertEquals(10L, results.single().value?.single()?.peer?.chatId); f.assertMetadataOnly()
        }
        val f = Fixture(); val results = mutableListOf<SettingsResult<List<NotificationExceptionRow>>>()
        f.api.search(NotificationScope.PRIVATE_CHATS, "", false, f::active, results::add); f.context()
        assertIs<TdApi.ChatListMain>(assertIs<TdApi.GetChats>(f.last.command).chatList)
        f.last.reply(TdApi.Chats(0, longArrayOf()))
        assertIs<TdApi.ChatListArchive>(assertIs<TdApi.GetChats>(f.last.command).chatList)
        f.last.reply(TdApi.Chats(0, longArrayOf())); assertTrue(assertNotNull(results.single().value).isEmpty())
        val rejected = Fixture(); val errors = mutableListOf<SettingsResult<List<NotificationExceptionRow>>>()
        rejected.api.resolve(NotificationScope.PRIVATE_CHATS, (1L..33L).toList(), rejected::active, errors::add)
        rejected.api.search(NotificationScope.PRIVATE_CHATS, "x".repeat(129), false, rejected::active, errors::add)
        rejected.api.search(NotificationScope.PRIVATE_CHATS, "", true, rejected::active, errors::add)
        assertTrue(errors.all { it.error == SettingsError.INVALID_VALUE }); assertTrue(rejected.requests.isEmpty())
    }

    @Test fun readbackFailureAndLateSetOkKeepActualLocalAcceptanceWithoutPretendingRollback() {
        for (retire in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
            f.api.save(NotificationExceptionEdit(peer(base), base.copy(soundId = 0), setOf(NotificationExceptionField.SOUND)), f::active, results::add)
            f.readSteps(base); val set = f.last
            if (retire) f.api.retire()
            set.reply(TdApi.Ok()); set.reply(TdApi.Ok()); set.failed()
            if (!retire) f.last.reply(TdApi.Error(500, "private"))
            assertTrue(results.single().locallyAccepted); assertNull(results.single().actual)
            assertEquals(if (retire) SettingsError.NOT_READY else SettingsError.REQUEST_FAILED, results.single().error)
        }
    }

    @Test fun nativeErrorsDiscardsDuplicatesAndCancellationTerminateMetadataChainsOnce() {
        for (stage in 0..4) for (discard in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
            f.api.read(NotificationScope.PRIVATE_CHATS, 10, f::active, results::add)
            if (stage >= 1) f.last.reply(user(1))
            if (stage >= 2) f.last.reply(TdApi.OptionValueInteger(900))
            if (stage >= 3) f.last.reply(TdApi.OptionValueInteger(901))
            if (stage >= 4) f.last.reply(chat(10, NotificationScope.PRIVATE_CHATS, rich()))
            val held = f.last
            if (discard) held.failed() else held.reply(TdApi.Error(500, "private"))
            held.failed(); held.reply(TdApi.Ok())
            assertNull(results.single().value); assertNotNull(results.single().error)
            assertEquals(stage + 1, f.requests.size)
        }
        val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
        f.api.read(NotificationScope.PRIVATE_CHATS, 10, f::active, results::add)
        f.enabled = false; f.last.reply(user(1))
        assertEquals(SettingsError.NOT_READY, results.single().error); assertEquals(1, f.requests.size)
        val throwing = OfficialTdNotificationExceptions(NativeChatNotificationMutations { _, _, _ -> error("synthetic") }) { _, _, _ -> error("synthetic") }
        val failed = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
        throwing.read(NotificationScope.PRIVATE_CHATS, 10, { true }, failed::add)
        assertEquals(SettingsError.REQUEST_FAILED, failed.single().error)
    }

    @Test fun observedNativeChangesAreTypedAndRetirementDetachesWithoutNativeWrites() {
        val f = Fixture(); val updates = mutableListOf<Long?>()
        f.api.observe(updates::add)
        f.api.accept(TdApi.UpdateChatNotificationSettings(10, rich().native()))
        f.api.accept(TdApi.UpdateScopeNotificationSettings(NotificationScope.GROUP_CHATS.native(), TdApi.ScopeNotificationSettings()))
        assertEquals(listOf(10L, null), updates); assertTrue(f.requests.isEmpty())
        f.api.retire(); f.api.retire(); f.api.accept(TdApi.UpdateChatNotificationSettings(10, rich().native()))
        assertEquals(2, updates.size)
    }

    @Test fun ownSetUpdateBeforeReadbackIsANewBaselineButUpdatesDuringMetadataValidationAreConflicts() {
        for (duringReadback in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>()
            val base = rich(); val desired = base.copy(showPreview = true)
            f.api.save(NotificationExceptionEdit(peer(base), desired, setOf(NotificationExceptionField.PREVIEW)), f::active, results::add)
            f.readSteps(base)
            f.api.accept(TdApi.UpdateChatNotificationSettings(10, desired.native()))
            f.last.reply(TdApi.Ok())
            f.context(); f.last.reply(chat(10, NotificationScope.PRIVATE_CHATS, desired))
            if (duringReadback) f.api.accept(TdApi.UpdateChatNotificationSettings(10, base.native()))
            f.last.reply(user(71))
            assertTrue(results.single().locallyAccepted)
            assertEquals(if (duringReadback) SettingsError.CONFLICT else null, results.single().error)
            assertEquals(desired, results.single().actual?.values)
        }
    }

    @Test fun acceptedReadbackWithTheOldDirtyValueIsAConflictAndReadOnlyMetadataCannotHideANewerUpdate() {
        val f = Fixture(); val results = mutableListOf<NotificationExceptionWriteResult>(); val base = rich()
        f.api.save(NotificationExceptionEdit(peer(base), base.copy(soundId = 0), setOf(NotificationExceptionField.SOUND)), f::active, results::add)
        f.readSteps(base); f.last.reply(TdApi.Ok()); f.readSteps(base)
        assertTrue(results.single().locallyAccepted); assertEquals(SettingsError.CONFLICT, results.single().error)
        assertEquals(base, results.single().actual?.values)
        val read = mutableListOf<SettingsResult<NotificationExceptionPeer>>()
        f.api.read(NotificationScope.PRIVATE_CHATS, 10, f::active, read::add)
        f.context(); f.last.reply(chat(10, NotificationScope.PRIVATE_CHATS, base))
        f.api.accept(TdApi.UpdateUser(user(71).apply { type = TdApi.UserTypeDeleted() }))
        f.last.reply(user(71))
        assertEquals(SettingsError.CONFLICT, read.single().error)
    }

    @Test fun boundedResolveKeepsPerIdentityFailuresWithoutInventingRowsOrUserChatConversions() {
        val f = Fixture(); val results = mutableListOf<SettingsResult<List<NotificationExceptionRow>>>()
        f.api.resolve(NotificationScope.PRIVATE_CHATS, listOf(10, 11), f::active, results::add)
        f.context(); f.last.reply(TdApi.Error(404, "missing"))
        assertEquals(11L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.reply(chat(11, NotificationScope.PRIVATE_CHATS, rich())); f.last.reply(user(71))
        val rows = assertNotNull(results.single().value)
        assertEquals(listOf(10L, 11L), rows.map { it.chatId })
        assertNull(rows.first().peer); assertNotNull(rows.first().error)
        assertEquals(11L, rows.last().peer?.chatId)
    }

    private data class Quad(val seconds: Int, val sampled: Long, val fresh: Int, val succeeds: Boolean)
    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>()
        val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { command, reply, failed -> requests += Pending(command, reply, failed) }
        val coordinator = NativeChatNotificationMutations(send)
        val api = OfficialTdNotificationExceptions(coordinator, send)
        val last get() = requests.last()
        fun active() = enabled
        fun context() {
            assertIs<TdApi.GetMe>(last.command); last.reply(user(1))
            assertEquals("replies_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name); last.reply(TdApi.OptionValueInteger(900))
            assertEquals("verification_codes_bot_chat_id", assertIs<TdApi.GetOption>(last.command).name); last.reply(TdApi.OptionValueInteger(901))
        }
        fun readSteps(values: ChatNotificationValues, scope: NotificationScope = NotificationScope.PRIVATE_CHATS) {
            context(); assertEquals(10L, assertIs<TdApi.GetChat>(last.command).chatId)
            last.reply(chat(10, scope, values))
            when (scope) {
                NotificationScope.PRIVATE_CHATS -> { assertEquals(71L, assertIs<TdApi.GetUser>(last.command).userId); last.reply(user(71)) }
                NotificationScope.GROUP_CHATS -> { assertEquals(72L, assertIs<TdApi.GetBasicGroup>(last.command).basicGroupId)
                    last.reply(TdApi.BasicGroup().apply { id = 72; isActive = true; status = TdApi.ChatMemberStatusMember() }) }
                NotificationScope.CHANNEL_CHATS -> { assertEquals(73L, assertIs<TdApi.GetSupergroup>(last.command).supergroupId)
                    last.reply(TdApi.Supergroup().apply { id = 73; isChannel = true; status = TdApi.ChatMemberStatusMember() }) }
            }
        }
        fun assertMetadataOnly() { assertTrue(requests.all { it.command is TdApi.GetMe || it.command is TdApi.GetOption ||
            it.command is TdApi.GetChat || it.command is TdApi.GetUser || it.command is TdApi.GetBasicGroup || it.command is TdApi.GetSupergroup ||
            it.command is TdApi.GetChats || it.command is TdApi.SearchChats || it.command is TdApi.SearchChatsOnServer ||
            it.command is TdApi.GetChatNotificationSettingsExceptions || it.command is TdApi.SetChatNotificationSettings }) }
    }
    private companion object {
        fun user(id: Long) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular() }
        fun chat(id: Long, scope: NotificationScope, values: ChatNotificationValues) = TdApi.Chat().apply {
            this.id = id; title = "synthetic peer"; notificationSettings = values.native()
            type = when (scope) { NotificationScope.PRIVATE_CHATS -> TdApi.ChatTypePrivate(71)
                NotificationScope.GROUP_CHATS -> TdApi.ChatTypeBasicGroup(72)
                NotificationScope.CHANNEL_CHATS -> TdApi.ChatTypeSupergroup(73, true) }
        }
        fun peer(values: ChatNotificationValues, sampled: Long = System.currentTimeMillis()) = NotificationExceptionPeer(10,
            NotificationScope.PRIVATE_CHATS, "synthetic peer", values, sampled)
        fun rich() = ChatNotificationValues(false, 60, false, -2, false, false, false, true, false, 88,
            false, false, false, true, false, true)
    }
}
