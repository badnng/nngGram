package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import kotlin.test.*

class MessageReactionsControllerTest {
    @Test fun reactionsAreUpdatedOnlyFromServerEventsAndDuplicateSubmissionsAreBlocked() {
        val fixture = Fixture()
        fixture.message()
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        assertEquals(1, fixture.session.changes.size)
        assertTrue(fixture.state.reactionPending)
        assertTrue(fixture.state.messages.single().reactions.isEmpty())
        fixture.event(ChatEvent.Reactions(1, 2, listOf(reaction())))
        fixture.session.changes.single()(ChatResult(Unit))
        fixture.flush()
        assertFalse(fixture.state.reactionPending)
        assertTrue(fixture.state.messages.single().reactions.single().isChosen)
    }

    @Test fun lateHistoryCannotEraseANewerReactionUpdate() {
        val fixture = Fixture()
        fixture.event(ChatEvent.Reactions(1, 2, listOf(reaction())))
        fixture.session.histories.single()(ChatResult(listOf(ChatMessage(2, 1, text = "original"))))
        fixture.flush()
        assertEquals(listOf(reaction()), fixture.state.messages.single().reactions)
    }

    @Test fun closingThePickerAndChangingChatsIgnoreLateResults() {
        val fixture = Fixture()
        fixture.message()
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.controller.closeReactions(token)
        fixture.session.options.single()(ChatResult(MessageReactionOptions(listOf("👍"), true)))
        fixture.flush()
        assertNull(fixture.state.reactionMessageId)
        assertNull(fixture.state.reactionOptions)
        fixture.controller.setReaction(token, 2, "👍", true)
        fixture.event(ChatEvent.NewChat(ChatItem(9, "other", order = 100)))
        fixture.controller.select(token, 9)
        fixture.session.changes.single()(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertFalse(fixture.state.reactionPending)
        assertNull(fixture.state.reactionError)
        assertTrue(fixture.state.messages.isEmpty())
        fixture.controller.setReaction(token, 2, "👍", true)
        assertEquals(1, fixture.session.changes.size)
    }

    @Test fun reactionCompletionCannotOverwriteEditsOrReviveDeletedMessages() {
        val fixture = Fixture()
        fixture.message()
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        fixture.event(ChatEvent.Content(1, 2, "edited", MessageKind.TEXT))
        fixture.session.changes.single()(ChatResult(Unit))
        fixture.flush()
        assertEquals("edited", fixture.state.messages.single().text)
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        fixture.event(ChatEvent.Deleted(1, listOf(2), true))
        fixture.session.changes.last()(ChatResult(Unit))
        fixture.event(ChatEvent.Reactions(1, 2, listOf(reaction())))
        fixture.flush()
        assertTrue(fixture.state.messages.isEmpty())
        assertFalse(fixture.state.reactionPending)
    }

    @Test fun failedAndPendingMessagesCannotLoadOrChangeReactions() {
        val fixture = Fixture()
        fixture.event(ChatEvent.NewMessage(ChatMessage(2, 1, delivery = MessageDelivery.PENDING)))
        fixture.controller.loadReactions(fixture.state.token, 2)
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        assertTrue(fixture.session.options.isEmpty())
        assertTrue(fixture.session.changes.isEmpty())
    }

    @Test fun deletingAPendingReactionTargetClearsPendingAfterAnotherPickerLoads() {
        val fixture = Fixture()
        fixture.message()
        fixture.event(ChatEvent.NewMessage(ChatMessage(3, 1, text = "other")))
        fixture.controller.setReaction(fixture.state.token, 2, "👍", true)
        fixture.controller.loadReactions(fixture.state.token, 3)
        fixture.session.options.single()(ChatResult(MessageReactionOptions(listOf("👍"), true)))
        fixture.flush()
        assertNull(fixture.state.reactionErrorMessageId)
        fixture.event(ChatEvent.Deleted(1, listOf(2), true))
        assertFalse(fixture.state.reactionPending)
        fixture.session.changes.single()(ChatResult(Unit))
        fixture.flush()
        fixture.controller.setReaction(fixture.state.token, 3, "👍", true)
        assertEquals(2, fixture.session.changes.size)
    }

    private fun reaction() = MessageReaction("👍", 1, true)
    @Test fun visualRequestsOnlyUseNativeLearnedKeysAndFilesAndDeduplicateVisibleItems() {
        val fixture = Fixture()
        fixture.message()
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.session.options.single()(ChatResult(MessageReactionOptions(listOf("👍"), true)))
        fixture.flush()
        fixture.controller.loadReactionVisual(token, ReactionKey("🔥"))
        assertTrue(fixture.session.visuals.isEmpty(), "An arbitrary UI key must not request native media.")
        fixture.controller.loadReactionVisual(token, ReactionKey("👍"))
        fixture.controller.loadReactionVisual(token, ReactionKey("👍"))
        assertEquals(1, fixture.session.visuals.size)
        val key = ReactionKey("👍")
        val file = MediaFile(700, canDownload = true)
        fixture.session.visuals.single().second(ChatResult(ReactionVisual(key, MessageMedia(file))))
        fixture.flush()
        assertEquals(file, fixture.state.mediaFiles[700])
        assertEquals(key, fixture.state.reactionVisuals[key]?.key)
        fixture.controller.downloadFile(token, 700)
        assertEquals(listOf(700), fixture.session.downloads)
        fixture.controller.loadReactionVisual(token, key)
        assertEquals(1, fixture.session.visuals.size)
    }

    @Test fun closingOrChangingThePickerRejectsLateArtworkAndCustomMutationResults() {
        val fixture = Fixture()
        fixture.message()
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.session.options.single()(ChatResult(MessageReactionOptions(listOf("👍"), true)))
        fixture.flush()
        fixture.controller.loadReactionVisual(token, ReactionKey("👍"))
        fixture.controller.closeReactions(token)
        fixture.session.visuals.single().second(ChatResult(ReactionVisual(ReactionKey("👍"), MessageMedia(MediaFile(701)))))
        fixture.flush()
        assertTrue(fixture.state.reactionVisuals.isEmpty())
        assertFalse(701 in fixture.state.mediaFiles)

        fixture.controller.setCustomReaction(token, 2, 900L, true)
        fixture.controller.setCustomReaction(token, 2, 900L, true)
        assertEquals(listOf(900L), fixture.session.customChanges.map { it.first })
        assertTrue(fixture.state.messages.single().reactions.isEmpty())
        fixture.event(ChatEvent.NewChat(ChatItem(9, "other", order = 99)))
        fixture.controller.select(token, 9)
        fixture.session.customChanges.single().second(ChatResult(error = ChatError.FORBIDDEN))
        fixture.flush()
        assertFalse(fixture.state.reactionPending)
        assertNull(fixture.state.reactionError)
    }

    @Test fun inlineCustomEmojiArtworkMustComeFromAValidCurrentNativeEntity() {
        val fixture = Fixture()
        val key = ReactionKey(customEmojiId = 42)
        fixture.event(ChatEvent.NewMessage(ChatMessage(2, 1, text = "a😀b", entities = listOf(
            MessageEntity(1, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42),
            MessageEntity(2, 1, MessageEntityKind.CUSTOM_EMOJI, id = 43),
        ))))
        val token = fixture.state.token
        fixture.controller.loadReactionVisual(token, ReactionKey(customEmojiId = 43))
        fixture.controller.loadReactionVisual(token, ReactionKey(customEmojiId = 99))
        assertTrue(fixture.session.visuals.isEmpty(), "Unknown IDs and split-surrogate entities cannot resolve artwork.")
        fixture.controller.loadReactionVisual(token, key)
        fixture.controller.loadReactionVisual(token, key)
        assertEquals(listOf(key), fixture.session.visuals.map { it.first })
        val file = MediaFile(710, canDownload = true)
        fixture.session.visuals.single().second(ChatResult(ReactionVisual(key, MessageMedia(file))))
        fixture.flush()
        assertEquals(key, fixture.state.reactionVisuals[key]?.key)
        fixture.controller.downloadFile(token, file.id)
        assertEquals(listOf(file.id), fixture.session.downloads)
        assertTrue(fixture.session.customChanges.isEmpty(), "Displaying an emoji does not authorize adding a reaction.")
    }

    @Test fun removingAnInlineEmojiEntityRejectsItsOutstandingArtwork() {
        val fixture = Fixture()
        val key = ReactionKey(customEmojiId = 42)
        fixture.event(ChatEvent.NewMessage(ChatMessage(2, 1, text = "😀", entities = listOf(
            MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42),
        ))))
        fixture.controller.loadReactionVisual(fixture.state.token, key)
        fixture.event(ChatEvent.Content(1, 2, "ordinary text", MessageKind.TEXT))
        fixture.session.visuals.single().second(ChatResult(ReactionVisual(key, MessageMedia(MediaFile(711)))))
        fixture.flush()
        assertTrue(fixture.state.reactionVisuals.isEmpty())
        assertFalse(711 in fixture.state.mediaFiles)
    }

    @Test fun changingChatsRejectsInlineEmojiArtworkFromTheOldSelection() {
        val fixture = Fixture()
        val key = ReactionKey(customEmojiId = 42)
        fixture.event(ChatEvent.NewMessage(ChatMessage(2, 1, text = "😀", entities = listOf(
            MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42),
        ))))
        val token = fixture.state.token
        fixture.controller.loadReactionVisual(token, key)
        fixture.event(ChatEvent.NewChat(ChatItem(9, "other", order = 99)))
        fixture.controller.select(token, 9)
        fixture.session.visuals.single().second(ChatResult(ReactionVisual(key, MessageMedia(MediaFile(712)))))
        fixture.flush()
        assertTrue(fixture.state.reactionVisuals.isEmpty())
        assertFalse(712 in fixture.state.mediaFiles)
        fixture.controller.loadReactionVisual(token, key)
        fixture.controller.loadReactionVisual(fixture.state.token, key)
        assertEquals(1, fixture.session.visuals.size)
    }

    @Test fun selectedCustomPackLoadsOnlyFromNativeCatalogAndRejectsLateSelectionResults() {
        val fixture = Fixture()
        fixture.message()
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.controller.loadReactionPacks(token, null)
        fixture.session.packs.single()(ChatResult(listOf(StickerPack(100, "native one"), StickerPack(101, "native two"))))
        fixture.flush()
        fixture.controller.loadReactionPacks(token, 999)
        assertTrue(fixture.session.packContents.isEmpty())
        fixture.controller.loadReactionPacks(token, 100)
        fixture.controller.loadReactionPacks(token, 101)
        fixture.session.packContents[0].second(ChatResult(listOf(ReactionChoice(ReactionKey(customEmojiId = 42)))))
        fixture.flush()
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
        fixture.session.packContents[1].second(ChatResult(listOf(ReactionChoice(ReactionKey(customEmojiId = 43)))))
        fixture.flush()
        assertEquals(43L, fixture.state.reactionPackEmojis.single().key.customEmojiId)
        fixture.controller.closeReactions(token)
        assertTrue(fixture.state.reactionPacks.isEmpty() && fixture.state.reactionPackEmojis.isEmpty())
    }

    @Test fun nativeAvailabilityUpdatesDuringAnOutstandingRequestAreCoalescedAndRetainPreviousChoices() {
        val fixture = Fixture()
        fixture.message()
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.event(ChatEvent.ReactionAvailabilityChanged(1))
        fixture.event(ChatEvent.ReactionAvailabilityChanged(1))
        assertEquals(1, fixture.session.options.size)
        fixture.session.options[0](ChatResult(MessageReactionOptions(listOf("👍"), true)))
        fixture.flush()
        assertEquals(2, fixture.session.options.size)
        assertEquals(listOf("👍"), fixture.state.reactionOptions?.emojis)
        fixture.session.options[1](ChatResult(MessageReactionOptions(listOf("🔥"), true)))
        fixture.flush()
        assertEquals(listOf("🔥"), fixture.state.reactionOptions?.emojis)
        fixture.event(ChatEvent.ReactionAvailabilityChanged(99))
        assertEquals(2, fixture.session.options.size, "Updates for other chats cannot replace the current options.")
    }

    @Test fun otherGroupPacksAreRequestedOnlyForKnownJoinedSupergroupsAndDoNotChangeTheTargetTimelineOrPermissions() {
        val fixture = Fixture()
        fixture.message()
        fixture.event(ChatEvent.NewChat(ChatItem(10, "source", supergroupId = 110, isJoinedGroup = true)))
        fixture.event(ChatEvent.NewChat(ChatItem(11, "left", supergroupId = 111)))
        fixture.event(ChatEvent.NewChat(ChatItem(12, "channel", supergroupId = 112, isChannel = true, isJoinedGroup = true)))
        fixture.event(ChatEvent.NewChat(ChatItem(13, "basic", basicGroupId = 113, isJoinedGroup = true)))
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.session.options.single()(ChatResult(MessageReactionOptions(listOf("👍"), true, allowCustomEmoji = false)))
        fixture.flush()
        for (id in listOf(1L, 11L, 12L, 13L, 999L)) fixture.controller.loadReactionGroupPack(token, id)
        assertTrue(fixture.session.groupPacks.isEmpty())
        val timeline = fixture.state.messages
        fixture.controller.loadReactionGroupPack(token, 10)
        assertEquals(10L, fixture.session.groupPacks.single().first)
        assertTrue(fixture.state.reactionGroupLoading)
        fixture.session.groupPacks.single().second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertEquals(900L, fixture.state.reactionGroupPack?.id)
        assertEquals(42L, fixture.state.reactionPackEmojis.single().key.customEmojiId)
        assertEquals(timeline, fixture.state.messages)
        assertEquals(false, fixture.state.reactionOptions?.allowCustomEmoji)
        assertEquals(1L, fixture.state.token.chatId)
        fixture.controller.loadReactionVisual(token, ReactionKey(customEmojiId = 42))
        assertEquals(42L, fixture.session.visuals.single().first.customEmojiId)
        assertEquals(1, fixture.session.histories.size, "Selecting a source group must not load its history.")
    }

    @Test fun groupPackCallbacksCannotCrossSourceMessageTargetOrInstalledPackSelection() {
        val fixture = Fixture()
        fixture.message()
        for (id in listOf(10L, 11L)) fixture.event(ChatEvent.NewChat(ChatItem(id, "source", order = 80,
            supergroupId = 100 + id, isJoinedGroup = true)))
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.controller.loadReactionGroupPack(token, 11)
        fixture.session.groupPacks[0].second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertEquals(11L, fixture.state.reactionGroupSourceId)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
        fixture.session.groupPacks[1].second(ChatResult(groupPack(11, 901, 43)))
        fixture.flush()
        assertEquals(43L, fixture.state.reactionPackEmojis.single().key.customEmojiId)

        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.controller.loadReactionPacks(token, null)
        fixture.session.packs.single()(ChatResult(listOf(StickerPack(700, "installed"))))
        fixture.flush()
        fixture.controller.loadReactionPacks(token, 700)
        fixture.session.groupPacks[2].second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertNull(fixture.state.reactionGroupSourceId)
        assertEquals(700L, fixture.state.reactionPackId)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())

        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.event(ChatEvent.NewMessage(ChatMessage(3, 1)))
        fixture.controller.loadReactions(token, 3)
        fixture.session.groupPacks[3].second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertNull(fixture.state.reactionGroupSourceId)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.controller.closeReactions(token)
        fixture.session.groupPacks[4].second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertNull(fixture.state.reactionMessageId)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())

        fixture.controller.loadReactions(token, 3)
        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.controller.select(token, 11)
        assertEquals(11L, fixture.state.token.chatId, "The target must actually change before testing rejection of its old source callback.")
        fixture.session.groupPacks[5].second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertNull(fixture.state.reactionGroupSourceId)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
    }

    @Test fun leavingASourceGroupInvalidatesItsDisplayedOrPendingPackAndResetRejectsLateResults() {
        val fixture = Fixture()
        fixture.message()
        fixture.event(ChatEvent.NewChat(ChatItem(10, "source", supergroupId = 110, isJoinedGroup = true)))
        val token = fixture.state.token
        fixture.controller.loadReactions(token, 2)
        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.event(ChatEvent.GroupMembership(10, false))
        fixture.session.groupPacks.single().second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertFalse(fixture.state.reactionGroupLoading)
        assertEquals(ChatError.FORBIDDEN, fixture.state.reactionGroupError)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
        fixture.controller.loadReactionGroupPack(token, 10)
        assertEquals(1, fixture.session.groupPacks.size)
        fixture.event(ChatEvent.GroupMembership(10, true))
        fixture.controller.loadReactionGroupPack(token, 10)
        fixture.controller.loadReactionGroupPack(token, null)
        fixture.session.groupPacks.last().second(ChatResult(groupPack(10, 900, 42)))
        fixture.flush()
        assertNull(fixture.state.reactionGroupSourceId)
        assertFalse(fixture.state.reactionGroupLoading)
        assertTrue(fixture.state.reactionPackEmojis.isEmpty())
    }

    private fun groupPack(source: Long, setId: Long, emoji: Long) = ReactionGroupPack(source, StickerPack(setId, "native group pack"),
        listOf(ReactionChoice(ReactionKey(customEmojiId = emoji), source = ReactionSource.PACK)))
    private class Fixture {
        val session = FakeSession()
        var state = ChatSnapshot()
        private val queue = ArrayDeque<() -> Unit>()
        val controller = ChatController(3, session, queue::addLast, { state = it })
        init {
            controller.start()
            event(ChatEvent.NewChat(ChatItem(1, "synthetic", order = 100)))
            controller.select(state.token, 1)
        }
        fun message() = event(ChatEvent.NewMessage(ChatMessage(2, 1, text = "original")))
        fun event(value: ChatEvent) = controller.event(value)
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }
    private class FakeSession : TdChatSession {
        val histories = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        val options = mutableListOf<(ChatResult<MessageReactionOptions>) -> Unit>()
        val changes = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val visuals = mutableListOf<Pair<ReactionKey, (ChatResult<ReactionVisual>) -> Unit>>()
        val customChanges = mutableListOf<Pair<Long, (ChatResult<Unit>) -> Unit>>()
        val downloads = mutableListOf<Int>()
        val packs = mutableListOf<(ChatResult<List<StickerPack>>) -> Unit>()
        val packContents = mutableListOf<Pair<Long, (ChatResult<List<ReactionChoice>>) -> Unit>>()
        val groupPacks = mutableListOf<Pair<Long, (ChatResult<ReactionGroupPack>) -> Unit>>()
        override fun reactionVisual(key: ReactionKey, result: (ChatResult<ReactionVisual>) -> Unit) { visuals += key to result }
        override fun setCustomReaction(chatId: Long, messageId: Long, customEmojiId: Long, add: Boolean, result: (ChatResult<Unit>) -> Unit) { customChanges += customEmojiId to result }
        override fun download(fileId: Int, result: (ChatResult<MediaFile>) -> Unit) { downloads += fileId }
        override fun reactionPacks(chatId: Long, result: (ChatResult<List<StickerPack>>) -> Unit) { packs += result }
        override fun reactionPack(setId: Long, result: (ChatResult<List<ReactionChoice>>) -> Unit) { packContents += setId to result }
        override fun reactionGroupPack(sourceChatId: Long, result: (ChatResult<ReactionGroupPack>) -> Unit) { groupPacks += sourceChatId to result }
        override fun availableReactions(chatId: Long, messageId: Long, result: (ChatResult<MessageReactionOptions>) -> Unit) { options += result }
        override fun setReaction(chatId: Long, messageId: Long, emoji: String, add: Boolean, result: (ChatResult<Unit>) -> Unit) { changes += result }
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) { histories += result }
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) {}
        override fun open(chatId: Long) {}
        override fun close(chatId: Long) {}
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) {}
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) {}
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) {}
    }
}
