package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Offline actor/native-boundary checks: no client, account, downloads or window. */
class PeerAppearanceControllerTest {
    @Test
    fun sameNumericUserAndChatKeepIndependentRawPairsAndLiveCatalog() {
        val f = Fixture()
        f.message(1, sender = 17)
        f.message(2, sender = 17, isChat = true)
        f.controller.event(ChatEvent.NewChat(ChatItem(17, "chat peer", appearance = values(12, 52))))
        f.profile(17, values(8, 41))
        val catalog = SettingsProfileColorsCatalog(availableNameColorIds = listOf(8, 12), revision = 2)
        f.controller.event(ChatEvent.AppearanceCatalog(catalog))
        assertEquals(values(8, 41), f.state.peerAppearance(17, false))
        assertEquals(values(12, 52), f.state.peerAppearance(17, true))
        assertSame(catalog, f.state.appearanceCatalog)
        f.profile(17, values(9, 42))
        f.controller.event(ChatEvent.ChatAppearance(17, values(13, 53)))
        assertEquals(values(9, 42), f.state.peerAppearance(17, false))
        assertEquals(values(13, 53), f.state.peerAppearance(17, true))
    }

    @Test
    fun onlyGetMeEstablishesSelfAndOutgoingNeverSubstitutesItsAppearance() {
        val f = Fixture()
        f.profile(18, values(4, 30))
        f.profile(17, values(5, 31))
        assertNull(f.state.account)
        f.me.done(user(17, 1, 20))
        f.flush()
        assertEquals(17, f.state.account?.userId)
        assertEquals(values(5, 31), f.state.account?.appearance)
        f.message(1, sender = 18, outgoing = true)
        assertEquals(values(4, 30), f.state.peerAppearance(18, false))
        f.profile(18, values(6, 32))
        assertEquals(17, f.state.account?.userId)
        assertEquals(values(5, 31), f.state.account?.appearance)
    }

    @Test
    fun referencedReplyUsesOriginalMessageIdentityInsteadOfFallbackOrigin() {
        val f = Fixture()
        f.profile(17, values(1, 21))
        f.profile(18, values(2, 22))
        f.message(1, sender = 17)
        f.message(2, sender = 0, reply = MessageReply(10, 1, "preview", senderId = 18))
        f.visible(2)
        assertTrue(f.metadata.isEmpty())
        assertEquals(values(1, 21), f.state.senderAppearances[17])
        assertNull(f.state.senderAppearances[18])
    }

    @Test
    fun getUserAndGetChatForwardRequestsReturnNameAndAppearanceWithoutExtraRequests() {
        val f = Fixture()
        f.message(1, origin = MessageForwardOrigin.User(17))
        f.message(2, origin = MessageForwardOrigin.Chat(17))
        assertEquals(2, f.metadata.size)
        f.metadata.single { it.command is TdApi.GetUser }.done(user(17, 8, 45))
        f.metadata.single { it.command is TdApi.GetChat }.done(TdApi.Chat().apply {
            id = 17; title = "native chat"; accentColorId = 9; profileAccentColorId = 46
        })
        f.flush()
        assertEquals(values(8, 45), f.state.peerAppearance(17, false))
        assertEquals(values(9, 46), f.state.peerAppearance(17, true))
        assertEquals("native user", f.state.forwardSourceNames[ForwardSourceIdentity.User(17)])
        assertEquals("native chat", f.state.forwardSourceNames[ForwardSourceIdentity.Chat(17)])
        assertEquals(2, f.metadata.size)
        assertNull(f.state.account)
    }

    @Test
    fun hiddenAndUnknownIdentitiesNeverBecomeMetadataRequests() {
        val f = Fixture()
        f.message(1, sender = 0, origin = MessageForwardOrigin.HiddenUser("hidden"),
            reply = MessageReply(10, 0, "hidden preview"))
        f.message(2, sender = 0, origin = MessageForwardOrigin.Unknown)
        f.visible(1, 2)
        assertTrue(f.metadata.isEmpty())
        assertTrue(f.state.senderAppearances.isEmpty())
        assertTrue(f.state.forwardSourceNames.isEmpty())
    }

    @Test
    fun privateMessagesDoNotAcquireAnOrdinarySenderSlotOrMetadata() {
        val f = Fixture(group = false)
        f.message(1, sender = 17)
        f.visible(1)
        assertTrue(f.metadata.isEmpty())
        assertEquals(listOf(1L), f.state.messages.map { it.id })
    }

    @Test
    fun newSenderAndReplyMetadataRequireVisibleActiveOwnership() {
        val f = Fixture()
        f.message(1, sender = 17, reply = MessageReply(10, 500, "preview", senderId = 18))
        assertTrue(f.metadata.isEmpty())
        f.controller.visibleMessages(f.state.token, listOf(1), false)
        assertTrue(f.metadata.isEmpty())
        f.visible(1)
        assertEquals(setOf(17L, 18L), f.metadata.map { assertIs<TdApi.GetUser>(it.command).userId }.toSet())
        f.metadata.forEach { it.done(user(assertIs<TdApi.GetUser>(it.command).userId, 8, 40)) }
        f.flush()
        assertEquals(setOf(17L, 18L), f.state.senderAppearances.keys)
    }

    @Test
    fun offscreenAndInactiveResultsDoNotPopulatePeerCachesAndCanRetryOnReturn() {
        val f = Fixture()
        f.message(1, sender = 17)
        f.visible(1)
        val first = f.metadata.single()
        f.controller.visibleMessages(f.state.token, emptyList(), false)
        first.done(user(17, 8, 40))
        f.flush()
        assertTrue(f.state.senderAppearances.isEmpty())
        f.visible(1)
        assertEquals(2, f.metadata.size)
        f.metadata.last().done(user(17, 9, 41))
        f.flush()
        assertEquals(values(9, 41), f.state.senderAppearances[17])
    }

    @Test
    fun changedSenderAndDeletedOriginalCannotAcceptOldVisibleMetadata() {
        val f = Fixture()
        f.message(1, sender = 17)
        f.visible(1)
        val first = f.metadata.single()
        f.message(1, sender = 18)
        first.done(user(17, 8, 40))
        f.flush()
        assertNull(f.state.senderAppearances[17])
        val second = f.metadata.last()
        f.controller.event(ChatEvent.Deleted(10, listOf(1), true))
        second.done(user(18, 9, 41))
        f.flush()
        assertTrue(f.state.senderAppearances.isEmpty())
        assertTrue(f.state.messages.isEmpty())
    }

    @Test
    fun selectionChangeAndRetirementRejectLateMetadata() {
        val f = Fixture()
        f.message(1, sender = 17)
        f.visible(1)
        val old = f.metadata.single()
        f.controller.event(ChatEvent.NewChat(ChatItem(20, "other", order = 10, basicGroupId = 20)))
        f.controller.select(f.state.token, 20)
        old.done(user(17, 8, 40))
        f.flush()
        assertTrue(f.state.senderAppearances.isEmpty())
        f.message(2, sender = 18, chatId = 20)
        f.visible(2)
        val last = f.metadata.last()
        val published = f.state
        f.controller.deactivate()
        last.done(user(18, 9, 41))
        f.flush()
        assertSame(published, f.state)
    }

    @Test
    fun newerNativeRawUpdateWinsOverOlderMetadataResult() {
        val f = Fixture()
        f.message(1, sender = 17)
        f.visible(1)
        f.profile(17, values(12, 44))
        f.metadata.single().done(user(17, 8, 40))
        f.flush()
        assertEquals(values(12, 44), f.state.senderAppearances[17])
        assertEquals("live user", f.state.senderNames[17])
    }

    @Test
    fun fullChatUpdatesOverrideRawButNullSummariesDoNotEraseNewerColors() {
        val f = Fixture()
        f.controller.event(ChatEvent.ChatAppearance(17, values(10, 40)))
        f.controller.event(ChatEvent.NewChat(ChatItem(17, "summary")))
        assertEquals(values(10, 40), f.state.peerAppearance(17, true))
        f.controller.event(ChatEvent.NewChat(ChatItem(17, "native full", appearance = values(11, 41))))
        assertEquals(values(11, 41), f.state.peerAppearance(17, true))
        f.controller.event(ChatEvent.NewChat(ChatItem(17, "another summary")))
        assertEquals(values(11, 41), f.state.peerAppearance(17, true))
    }

    @Test
    fun giftAndSignedBackgroundIdsSurviveRegistryWithoutGrantingFiles() {
        val f = Fixture()
        f.message(1, sender = 17)
        val gift = SettingsUpgradedGiftColors(-100, -101, -102, 0x112233, listOf(1, 2, 3), 0x445566, listOf(4, 5))
        val appearance = SettingsProfileColorsValues(SettingsProfileColorValue(0, -200), SettingsProfileColorValue(-1, -201), gift)
        f.profile(17, appearance)
        assertEquals(appearance, f.state.senderAppearances[17])
        assertTrue(f.state.mediaFiles.isEmpty())
        assertTrue(f.requests.none { it.command is TdApi.DownloadFile })
    }

    @Test
    fun metadataDoesNotInsertHistoryOpenChatsReadMessagesOrAuthorizeProfileDownloads() {
        val f = Fixture()
        f.message(1, sender = 17)
        val before = f.requests.count { it.command is TdApi.GetChatHistory || it.command is TdApi.OpenChat || it.command is TdApi.ViewMessages }
        f.visible(1)
        f.metadata.single().done(user(17, 8, 40))
        f.flush()
        assertEquals(before, f.requests.count { it.command is TdApi.GetChatHistory || it.command is TdApi.OpenChat || it.command is TdApi.ViewMessages })
        assertEquals(listOf(1L), f.state.messages.map { it.id })
        f.controller.event(ChatEvent.NewMessage(ChatMessage(2, 10, outgoing = true, kind = MessageKind.PHOTO,
            media = MessageMedia(MediaFile(70, size = 1024, canDownload = true)))))
        assertTrue(70 in f.state.mediaFiles)
        f.controller.acquireOwnedMedia(MediaDownloadOwner(f.state.token, 1, MediaDownloadPurpose.PROFILE_EMOJI, 1), 70)
        assertTrue(f.requests.none { it.command is TdApi.DownloadFile })
        f.controller.acquireOwnedMedia(MediaDownloadOwner(f.state.token, 1, MediaDownloadPurpose.PROFILE_PHOTO_HISTORY, 2), 70)
        assertTrue(f.requests.none { it.command is TdApi.DownloadFile }, "Message membership does not authorize profile history files.")
    }

    @Test
    fun visibleMetadataConcurrencyAndPublishedUserCacheRemainBounded() {
        val f = Fixture()
        (1L..600L).forEach { f.message(it, sender = it + 1000) }
        f.controller.visibleMessages(f.state.token, (1L..600L).toList(), true)
        assertEquals(8, f.metadata.size)
        var completed = 0
        while (completed < f.metadata.size) {
            val request = f.metadata[completed++]
            request.done(user(assertIs<TdApi.GetUser>(request.command).userId, 8, 40))
            f.flush()
            assertTrue(f.metadata.size - completed <= 8)
        }
        assertEquals(512, f.metadata.size)
        assertTrue(f.state.senderAppearances.size <= 512)
        assertEquals(600, f.state.messages.size)
    }

    private class Fixture(group: Boolean = true) {
        data class Request(val command: TdApi.Function<*>, val done: (TdApi.Object) -> Unit)
        val requests = mutableListOf<Request>()
        val metadata get() = requests.filter { it.command is TdApi.GetUser || it.command is TdApi.GetChat }
        val me get() = requests.single { it.command is TdApi.GetMe }
        private val queued = ArrayDeque<() -> Unit>()
        var state = ChatSnapshot()
        val session = OfficialTdChatSession { command, done, _ -> requests += Request(command, done) }
        val controller = ChatController(1, session, queued::addLast, { state = it })

        init {
            controller.start()
            controller.event(ChatEvent.NewChat(ChatItem(10, "current", order = 100,
                basicGroupId = if (group) 10 else null, privateUserId = if (group) null else 17)))
            controller.select(state.token, 10)
        }

        fun message(id: Long, sender: Long = 0, isChat: Boolean = false, outgoing: Boolean = true,
            reply: MessageReply? = null, origin: MessageForwardOrigin? = null, chatId: Long = 10,
        ) = controller.event(ChatEvent.NewMessage(ChatMessage(id, chatId, senderId = sender,
            senderIsChat = isChat, outgoing = outgoing, text = "offline", replyTo = reply,
            forwardInfo = origin?.let { MessageForwardInfo(it) })))

        fun profile(id: Long, appearance: SettingsProfileColorsValues) = controller.event(
            ChatEvent.SenderName(id, "live user", profile = AccountProfile(id, "live user", appearance = appearance)))

        fun visible(vararg ids: Long) = controller.visibleMessages(state.token, ids.toList(), true)
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private companion object {
        fun values(name: Int, profile: Int) = SettingsProfileColorsValues(SettingsProfileColorValue(name), SettingsProfileColorValue(profile))
        fun user(id: Long, name: Int, profile: Int) = TdApi.User().apply {
            this.id = id; firstName = "native user"; accentColorId = name; profileAccentColorId = profile
            type = TdApi.UserTypeRegular()
        }
    }
}
