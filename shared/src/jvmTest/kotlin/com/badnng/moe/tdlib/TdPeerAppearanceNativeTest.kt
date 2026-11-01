package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real pinned native objects and recorded requests, without a client, account, history or JNI. */
class TdPeerAppearanceNativeTest {
    @Test fun userRawPairsRetainZeroNameHiddenProfileSignedEmojiAndEveryGiftField() {
        val gift = gift()
        val native = user().apply { upgradedGiftColors = gift }
        val profile = mapAccountProfile(native)
        assertEquals(17L, profile.userId)
        assertEquals("Native User", profile.displayName)
        assertTrue(profile.isPremium)
        assertEquals(expectedUser(giftValue()), profile.appearance)
        gift.lightThemeColors[0] = 999
        gift.darkThemeColors[0] = 998
        assertEquals(listOf(2, 3, 4), profile.appearance?.gift?.lightColors)
        assertEquals(listOf(6, 7), profile.appearance?.gift?.darkColors)
        assertFailsWith<UnsupportedOperationException> {
            (assertNotNull(profile.appearance?.gift).lightColors as MutableList<Int>).clear()
        }
    }

    @Test fun chatAndIncrementalColorsPreserveAllRawValuesAndCopyGiftArrays() {
        val native = chat(-17)
        val initial = mapPeerAppearance(native)
        assertEquals(expectedChat(giftValue()), initial)
        val update = TdApi.UpdateChatAccentColors().apply {
            chatId = -17
            accentColorId = 999
            backgroundCustomEmojiId = Long.MIN_VALUE
            profileAccentColorId = -1
            profileBackgroundCustomEmojiId = -300
            upgradedGiftColors = gift()
        }
        val mapped = mapPeerAppearance(update)
        assertEquals(SettingsProfileColorValue(999, Long.MIN_VALUE), mapped.name)
        assertEquals(SettingsProfileColorValue(-1, -300), mapped.profile)
        assertEquals(giftValue(), mapped.gift)
        native.upgradedGiftColors.lightThemeColors[0] = 111
        update.upgradedGiftColors.darkThemeColors[0] = 222
        assertEquals(giftValue(), initial.gift)
        assertEquals(giftValue(), mapped.gift)
    }

    @Test fun absentGiftRemainsAbsentAndNameProfileNeverBorrowValuesFromEachOther() {
        val native = user().apply { upgradedGiftColors = null; profileAccentColorId = -1; profileBackgroundCustomEmojiId = 0 }
        val mapped = mapPeerAppearance(native)
        assertEquals(SettingsProfileColorValue(0, -100), mapped.name)
        assertEquals(SettingsProfileColorValue(-1, 0), mapped.profile)
        assertNull(mapped.gift)
    }

    @Test fun newChatUserAndAccentEventsRetainSeparateNativeIdentities() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(chat(17)))
        mapper.accept(TdApi.UpdateUser(user().apply { upgradedGiftColors = gift() }))
        mapper.accept(TdApi.UpdateChatAccentColors().apply {
            chatId = 17; accentColorId = 8; backgroundCustomEmojiId = -9
            profileAccentColorId = -1; profileBackgroundCustomEmojiId = -10
            upgradedGiftColors = gift()
        })
        val newChat = assertIs<ChatEvent.NewChat>(events[0]).chat
        val sender = assertIs<ChatEvent.SenderName>(events[1])
        val appearance = assertIs<ChatEvent.ChatAppearance>(events[2])
        assertEquals(17L, newChat.id)
        assertEquals(expectedChat(giftValue()), newChat.appearance)
        assertEquals(17L, sender.id)
        assertEquals(expectedUser(giftValue()), sender.profile?.appearance)
        assertEquals(17L, appearance.chatId)
        assertEquals(SettingsProfileColorValue(8, -9), appearance.appearance.name)
        assertEquals(SettingsProfileColorValue(-1, -10), appearance.appearance.profile)
        assertEquals(giftValue(), appearance.appearance.gift)
        assertEquals(3, events.size, "A user update emits metadata only, never an account selection.")
    }

    @Test fun catalogForwardingKeepsTheHelpersExactUnknownEmptyAndErrorSnapshots() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        val helper = OfficialTdSettingsProfileColors { _, _, _ -> fail("Catalog delivery must not send requests") }
        val unknown = helper.catalog()
        mapper.acceptProfileColorsCatalog(unknown)
        assertSame(unknown, assertIs<ChatEvent.AppearanceCatalog>(events.last()).catalog)
        assertNull(unknown.nameColors)
        assertNull(unknown.profileColors)
        helper.accept(TdApi.UpdateAccentColors(emptyArray(), intArrayOf()))
        val knownEmpty = helper.catalog()
        mapper.acceptProfileColorsCatalog(knownEmpty)
        assertSame(knownEmpty, assertIs<ChatEvent.AppearanceCatalog>(events.last()).catalog)
        assertEquals(emptyList(), knownEmpty.nameColors)
        assertNull(knownEmpty.profileColors)
        helper.accept(TdApi.UpdateAccentColors())
        val invalid = helper.catalog()
        mapper.acceptProfileColorsCatalog(invalid)
        assertSame(invalid, assertIs<ChatEvent.AppearanceCatalog>(events.last()).catalog)
        assertEquals(SettingsError.INVALID_VALUE, invalid.nameError)
        assertEquals(3, events.size)
    }

    @Test fun getMeReturnsActualUserAppearanceWithoutAdditionalRequests() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<AccountProfile>>()
        f.session.getMe(results::add)
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.done(user().apply { upgradedGiftColors = gift() })
        assertEquals(17L, results.single().value?.userId)
        assertEquals(expectedUser(giftValue()), results.single().value?.appearance)
        assertEquals(1, f.requests.size)
    }

    @Test fun presentationUsesExactlyOneRequestAndKeepsSameNumericUserAndChatSeparate() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<PeerAppearancePresentation>>()
        f.session.forwardSourcePresentation(ForwardSourceIdentity.User(17), results::add)
        assertEquals(17L, assertIs<TdApi.GetUser>(f.last.command).userId)
        f.last.done(user().apply { upgradedGiftColors = gift() })
        f.session.forwardSourcePresentation(ForwardSourceIdentity.Chat(17), results::add)
        assertEquals(17L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.done(chat(17))
        assertEquals(PeerAppearancePresentation(ForwardSourceIdentity.User(17), "Native User", expectedUser(giftValue())), results[0].value)
        assertEquals(PeerAppearancePresentation(ForwardSourceIdentity.Chat(17), "Native Chat", expectedChat(giftValue())), results[1].value)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.command is TdApi.GetUser || it.command is TdApi.GetChat })
    }

    @Test fun signedChatIdentityAndNegativeCustomEmojiIdsRemainNative() {
        val f = Fixture()
        var result = ChatResult<PeerAppearancePresentation>()
        f.session.forwardSourcePresentation(ForwardSourceIdentity.Chat(-200), { result = it })
        assertEquals(-200L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.done(chat(-200))
        assertEquals(ForwardSourceIdentity.Chat(-200), result.value?.identity)
        assertEquals(-200L, result.value?.appearance?.name?.backgroundCustomEmojiId)
        assertEquals(-201L, result.value?.appearance?.profile?.backgroundCustomEmojiId)
    }

    @Test fun wrongNamespaceWrongIdentityBlankNamesErrorsAndDuplicateResultsAreRejected() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<PeerAppearancePresentation>>()
        listOf<TdApi.Object>(chat(17), user().apply { id = 18 }, user().apply { firstName = ""; lastName = "" }).forEach { response ->
            f.session.forwardSourcePresentation(ForwardSourceIdentity.User(17), results::add)
            f.last.done(response)
            assertEquals(ChatError.REQUEST_FAILED, results.last().error)
            assertNull(results.last().value)
        }
        f.session.forwardSourcePresentation(ForwardSourceIdentity.Chat(17), results::add)
        val pending = f.last
        pending.done(TdApi.Error(403, "private native details"))
        pending.done(chat(17))
        pending.failed()
        assertEquals(4, results.size)
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertEquals(403, results.last().code)
        assertFalse(results.toString().contains("private"))
    }

    @Test fun failedOrThrowingDispatcherCompletesOnceAndOldNameApiStillReturnsOnlyName() {
        val f = Fixture()
        val names = mutableListOf<ChatResult<String>>()
        f.session.forwardSourceName(ForwardSourceIdentity.Chat(-17), names::add)
        f.last.done(chat(-17))
        f.last.failed()
        assertEquals("Native Chat", names.single().value)
        val failed = mutableListOf<ChatResult<PeerAppearancePresentation>>()
        f.session.forwardSourcePresentation(ForwardSourceIdentity.User(17), failed::add)
        f.last.failed()
        f.last.done(user())
        assertEquals(ChatError.REQUEST_FAILED, failed.single().error)
        val throwing = OfficialTdChatSession { _, _, _ -> throw IllegalStateException("private native details") }
        throwing.forwardSourcePresentation(ForwardSourceIdentity.User(17), failed::add)
        assertEquals(2, failed.size)
        assertEquals(ChatError.REQUEST_FAILED, failed.last().error)
    }

    @Test fun defaultPresentationKeepsLegacyNameSessionsCompatibleAndPropagatesErrors() {
        val session = LegacyNameSession()
        val source = ForwardSourceIdentity.User(17)
        val results = mutableListOf<ChatResult<PeerAppearancePresentation>>()
        session.forwardSourcePresentation(source, results::add)
        assertEquals(source, session.source)
        session.complete(ChatResult("Legacy native name"))
        assertEquals(PeerAppearancePresentation(source, "Legacy native name"), results.single().value)
        session.forwardSourcePresentation(source, results::add)
        session.complete(ChatResult(error = ChatError.FORBIDDEN, code = 403))
        assertNull(results.last().value)
        assertEquals(ChatError.FORBIDDEN, results.last().error)
        assertEquals(403, results.last().code)
    }

    @Test fun hiddenUnknownAndInvalidIdentitiesNeverBecomeMetadataRequests() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<PeerAppearancePresentation>>()
        listOf(TdApi.MessageOriginHiddenUser("Native hidden"), null).forEach { origin ->
            val forward = assertNotNull(mapMessageForwardInfo(TdApi.MessageForwardInfo(origin, 1, null, "")))
            assertNull(forward.origin.sourceIdentity())
            forward.origin.sourceIdentity()?.let { f.session.forwardSourcePresentation(it, results::add) }
        }
        listOf(ForwardSourceIdentity.User(0), ForwardSourceIdentity.User(-1), ForwardSourceIdentity.Chat(0)).forEach {
            f.session.forwardSourcePresentation(it, results::add)
        }
        assertTrue(f.requests.isEmpty())
        assertEquals(3, results.size)
        assertTrue(results.all { it.value == null && it.error == ChatError.REQUEST_FAILED })
    }

    @Test fun nativeSearchOverviewSummariesCarryAppearanceThroughTheSharedChatMapper() {
        val f = Fixture()
        var result = ChatResult<Pair<List<ChatItem>, List<ChatItem>>>()
        f.session.searchOverview { result = it }
        assertEquals("disable_top_chats", assertIs<TdApi.GetOption>(f.last.command).name)
        f.last.done(TdApi.OptionValueBoolean(true))
        assertIs<TdApi.SearchChats>(f.last.command)
        f.last.done(TdApi.Chats(1, longArrayOf(-17)))
        assertEquals(-17L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.done(chat(-17))
        assertEquals(expectedChat(giftValue()), result.value?.second?.single()?.appearance)
        assertEquals(3, f.requests.size)
    }

    @Test fun nativeSearchTitleSummariesAndSupportResolutionAlsoKeepAppearance() {
        val f = Fixture()
        var result = ChatResult<ChatSearchPage>()
        f.session.search(ChatSearchQuery("Native", category = ChatSearchCategory.CHANNELS), "") { result = it }
        assertIs<TdApi.SearchChats>(f.last.command)
        f.last.done(TdApi.Chats(1, longArrayOf(-17)))
        assertIs<TdApi.SearchChatsOnServer>(f.last.command)
        f.last.done(TdApi.Chats(0, longArrayOf()))
        assertIs<TdApi.SearchPublicChats>(f.last.command)
        f.last.done(TdApi.Chats(0, longArrayOf()))
        assertEquals(-17L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.done(chat(-17).apply { type = TdApi.ChatTypeSupergroup(1, true) })
        assertEquals(expectedChat(giftValue()), result.value?.chats?.single()?.appearance)
        assertEquals(4, f.requests.size)

        val support = Fixture()
        var target = ChatResult<TdSupportChat>()
        support.session.helpSupport.resolve({ true }) { target = it }
        assertIs<TdApi.GetSupportUser>(support.last.command)
        support.last.done(user().apply { isSupport = true })
        assertEquals(17L, assertIs<TdApi.CreatePrivateChat>(support.last.command).userId)
        support.last.done(chat(17))
        assertEquals(17L, assertIs<TdApi.GetChat>(support.last.command).chatId)
        support.last.done(chat(17))
        assertEquals(expectedChat(giftValue()), target.value?.chat?.appearance)
        assertEquals(3, support.requests.size)
    }

    private class Fixture {
        data class Request(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit, val failed: () -> Unit)
        val requests = mutableListOf<Request>()
        val last get() = requests.last()
        val session = OfficialTdChatSession { command, done, failed -> requests += Request(command, done, failed) }
    }

    private class LegacyNameSession : TdChatSession {
        var source: ForwardSourceIdentity? = null
        var complete: (ChatResult<String>) -> Unit = {}
        override fun forwardSourceName(source: ForwardSourceIdentity, result: (ChatResult<String>) -> Unit) {
            this.source = source; complete = result
        }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = error("Unrelated request")
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("Unrelated request")
        override fun open(chatId: Long) = error("Unrelated request")
        override fun close(chatId: Long) = error("Unrelated request")
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = error("Unrelated request")
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = error("Unrelated request")
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = error("Unrelated request")
    }

    private companion object {
        fun gift() = TdApi.UpgradedGiftColors(-11, -12, -13, 1, intArrayOf(2, 3, 4), 5, intArrayOf(6, 7))
        fun giftValue() = SettingsUpgradedGiftColors(-11, -12, -13, 1, listOf(2, 3, 4), 5, listOf(6, 7))
        fun expectedUser(gift: SettingsUpgradedGiftColors? = null) = SettingsProfileColorsValues(
            SettingsProfileColorValue(0, -100), SettingsProfileColorValue(99, -101), gift)
        fun expectedChat(gift: SettingsUpgradedGiftColors? = null) = SettingsProfileColorsValues(
            SettingsProfileColorValue(8, -200), SettingsProfileColorValue(109, -201), gift)
        fun user() = TdApi.User().apply {
            id = 17; firstName = "Native"; lastName = "User"; isPremium = true
            accentColorId = 0; backgroundCustomEmojiId = -100
            profileAccentColorId = 99; profileBackgroundCustomEmojiId = -101
        }
        fun chat(id: Long) = TdApi.Chat().apply {
            this.id = id; title = "Native Chat"; type = TdApi.ChatTypePrivate(17)
            accentColorId = 8; backgroundCustomEmojiId = -200
            profileAccentColorId = 109; profileBackgroundCustomEmojiId = -201
            upgradedGiftColors = gift()
        }
    }
}
