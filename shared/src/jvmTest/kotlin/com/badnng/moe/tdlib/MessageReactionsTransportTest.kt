package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class MessageReactionsTransportTest {
    @Test fun recentReactorsRetainNativeUserAndChatIdentitiesWithoutInventingPortraits() {
        val native = message().interactionInfo.reactions.apply {
            reactions.single().recentSenderIds = arrayOf(TdApi.MessageSenderUser(7), TdApi.MessageSenderChat(7),
                TdApi.MessageSenderUser(7), TdApi.MessageSenderUser(0))
        }
        val senders = mapMessageReactions(native).single().recentSenders
        assertEquals(listOf(MessageReactionSender(7), MessageReactionSender(7, senderIsChat = true)), senders)
        assertFalse(senders.toString().contains("7"))
    }

    @Test fun historyAndInteractionUpdatesPreserveCountsAndChosenState() {
        val message = message(chosen = true)
        val mapped = mapChatMessage(message).reactions.single()
        assertEquals("👍", mapped.emoji)
        assertEquals(3, mapped.totalCount)
        assertTrue(mapped.isChosen)
        val events = mutableListOf<ChatEvent>()
        TdChatUpdateMapper(events::add).accept(TdApi.UpdateMessageInteractionInfo(1, 2, message.interactionInfo))
        assertEquals(listOf(mapped), assertIs<ChatEvent.Reactions>(events.single()).reactions)
        assertFalse(mapped.toString().contains("👍"))
    }

    @Test fun availableOptionsUseOnlyServerFreeEmojiAndDeduplicateAcrossGroups() {
        val available = options().apply {
            recentReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypeEmoji("👍"), false))
            popularReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypeEmoji("🔥"), true),
                TdApi.AvailableReaction(TdApi.ReactionTypeCustomEmoji(99), false), TdApi.AvailableReaction(TdApi.ReactionTypePaid(), false))
        }
        assertEquals(listOf("👍"), mapAvailableReactions(available).emojis)
        assertTrue(mapAvailableReactions(available).canAdd)
        available.unavailabilityReason = TdApi.ReactionUnavailabilityReasonRestricted()
        assertFalse(mapAvailableReactions(available).canAdd)
    }

    @Test fun addingRechecksMessageIdentityAndCurrentServerOptionsBeforeDispatch() {
        val fixture = Fixture()
        fixture.session.setReaction(1, 2, "👍", true) {}
        assertIs<TdApi.GetMessage>(fixture.last.command)
        fixture.last.reply(message())
        assertIs<TdApi.GetMessageAvailableReactions>(fixture.last.command)
        fixture.last.reply(options())
        val add = assertIs<TdApi.AddMessageReaction>(fixture.last.command)
        assertEquals("👍", assertIs<TdApi.ReactionTypeEmoji>(add.reactionType).emoji)
        assertFalse(add.isBig)
        assertTrue(add.updateRecentReactions)
        assertEquals(1L, add.chatId)
        assertEquals(2L, add.messageId)
    }

    @Test fun unavailableAndUnlistedReactionsCannotBeAdded() {
        for (available in listOf(options().apply { unavailabilityReason = TdApi.ReactionUnavailabilityReasonGuest() },
            options().apply { topReactions = emptyArray() })) {
            val fixture = Fixture()
            var result: ChatResult<Unit>? = null
            fixture.session.setReaction(1, 2, "👍", true) { result = it }
            fixture.last.reply(message())
            fixture.last.reply(available)
            assertEquals(ChatError.FORBIDDEN, result?.error)
            assertTrue(fixture.requests.none { it.command is TdApi.AddMessageReaction })
        }
    }

    @Test fun chosenReactionCanBeRemovedWithoutAdditionPermissionOrChangingOtherReactions() {
        val fixture = Fixture()
        fixture.session.setReaction(1, 2, "👍", false) {}
        fixture.last.reply(message(chosen = true))
        val remove = assertIs<TdApi.RemoveMessageReaction>(fixture.last.command)
        assertEquals("👍", assertIs<TdApi.ReactionTypeEmoji>(remove.reactionType).emoji)
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.none { it.command is TdApi.SetMessageReactions })
    }

    @Test fun mismatchedOrUnsentMessagesNeverDispatchReactionChanges() {
        for (invalid in listOf(message().apply { chatId = 9 }, message().apply { id = 3 },
            message().apply { sendingState = TdApi.MessageSendingStatePending() })) {
            val fixture = Fixture()
            var result: ChatResult<Unit>? = null
            fixture.session.setReaction(1, 2, "👍", true) { result = it }
            fixture.last.reply(invalid)
            assertEquals(ChatError.REQUEST_FAILED, result?.error)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun paidReactionsRemainReadOnlyWhileCustomReactionsRetainTheirNativeIdentity() {
        val native = TdApi.MessageReactions(arrayOf(
            TdApi.MessageReaction(TdApi.ReactionTypeCustomEmoji(42), 2, true, null, emptyArray()),
            TdApi.MessageReaction(TdApi.ReactionTypePaid(), 8, true, null, emptyArray())), false, emptyArray(), false)
        val mapped = mapMessageReactions(native)
        assertEquals(2, mapped.size)
        assertTrue(mapped.first().canToggle)
        assertFalse(mapped.last().canToggle)
        assertEquals(42L, mapped.first().customEmojiId)
        assertTrue(mapped.last().isPaid)
    }

    @Test fun officialChoicesKeepRecentCustomEmojiFromOtherChatsAndTheirPremiumRequirement() {
        val mapped = mapAvailableReactions(options().apply {
            recentReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypeCustomEmoji(41), false),
                TdApi.AvailableReaction(TdApi.ReactionTypeCustomEmoji(42), true))
            popularReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypePaid(), false))
            allowCustomEmoji = true
        })
        assertEquals(listOf(ReactionKey("👍"), ReactionKey(customEmojiId = 41), ReactionKey(customEmojiId = 42)), mapped.choices.map { it.key })
        assertEquals(ReactionSource.RECENT, mapped.choices[1].source)
        assertFalse(mapped.choices[1].needsPremium)
        assertTrue(mapped.choices[2].needsPremium)
        assertTrue(mapped.allowCustomEmoji)
        assertFalse(mapped.toString().contains("41"))
    }

    @Test fun customEmojiAdditionRechecksNativeMembershipAndCannotBypassPremiumOrChatRestrictions() {
        val fixture = Fixture()
        var result: ChatResult<Unit>? = null
        fixture.session.setCustomReaction(1, 2, 41, true) { result = it }
        fixture.last.reply(message())
        fixture.last.reply(options().apply { recentReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypeCustomEmoji(41), false)) })
        assertEquals(41L, assertIs<TdApi.ReactionTypeCustomEmoji>(assertIs<TdApi.AddMessageReaction>(fixture.last.command).reactionType).customEmojiId)
        fixture.last.reply(TdApi.Ok())
        assertNotNull(result?.value)

        for (allowed in listOf(false, true)) {
            val restricted = Fixture()
            var denied: ChatResult<Unit>? = null
            restricted.session.setCustomReaction(1, 2, 99, true) { denied = it }
            restricted.last.reply(message())
            restricted.last.reply(options().apply { allowCustomEmoji = allowed })
            if (allowed) {
                assertIs<TdApi.GetMe>(restricted.last.command)
                restricted.last.reply(TdApi.User().apply { isPremium = false })
            }
            assertEquals(ChatError.FORBIDDEN, denied?.error)
            assertTrue(restricted.requests.none { it.command is TdApi.AddMessageReaction })
        }
    }

    @Test fun premiumCustomPackReactionUsesTypedIdentityAndUpdatesNativeRecentReactions() {
        val fixture = Fixture()
        fixture.session.setCustomReaction(1, 2, 99, true) {}
        fixture.last.reply(message())
        fixture.last.reply(options().apply { allowCustomEmoji = true })
        fixture.last.reply(TdApi.User().apply { isPremium = true })
        val add = assertIs<TdApi.AddMessageReaction>(fixture.last.command)
        assertEquals(99L, assertIs<TdApi.ReactionTypeCustomEmoji>(add.reactionType).customEmojiId)
        assertTrue(add.updateRecentReactions)
        assertFalse(add.isBig)
    }

    @Test fun nativeEmojiArtworkUsesOfficialReactionResourcesAndRejectsMismatchedCustomEmoji() {
        val fixture = Fixture()
        var visual: ChatResult<ReactionVisual>? = null
        fixture.session.reactionVisual(ReactionKey("👍")) { visual = it }
        assertEquals("👍", assertIs<TdApi.GetEmojiReaction>(fixture.last.command).emoji)
        fixture.last.reply(TdApi.EmojiReaction().apply {
            emoji = "👍"
            staticIcon = sticker(10)
            selectAnimation = sticker(11).apply { format = TdApi.StickerFormatTgs() }
            centerAnimation = sticker(12).apply { format = TdApi.StickerFormatTgs() }
            aroundAnimation = sticker(13).apply { format = TdApi.StickerFormatTgs() }
        })
        assertEquals(10, visual?.value?.staticIcon?.file?.id)
        assertEquals(MediaFormat.TGS, visual?.value?.selectAnimation?.format)
        assertEquals(12, visual?.value?.centerAnimation?.file?.id)
        assertEquals(13, visual?.value?.aroundAnimation?.file?.id)
        fixture.session.reactionVisual(ReactionKey(customEmojiId = 42)) { visual = it }
        assertContentEquals(longArrayOf(42), assertIs<TdApi.GetCustomEmojiStickers>(fixture.last.command).customEmojiIds)
        fixture.last.reply(TdApi.Stickers(arrayOf(sticker(12).apply { fullType = TdApi.StickerFullTypeCustomEmoji(43, false) })))
        assertEquals(ChatError.REQUEST_FAILED, visual?.error)
    }

    @Test fun customVideoEmojiUsesTheOfficialThumbnailForItsStaticCellAndKeepsTheFullNativeAnimation() {
        for (withThumbnail in listOf(false, true)) {
            val fixture = Fixture()
            var result: ChatResult<ReactionVisual>? = null
            fixture.session.reactionVisual(ReactionKey(customEmojiId = 42)) { result = it }
            val sticker = sticker(80).apply {
                fullType = TdApi.StickerFullTypeCustomEmoji(42, false)
                format = TdApi.StickerFormatWebm()
                if (withThumbnail) thumbnail = TdApi.Thumbnail().apply {
                    format = TdApi.ThumbnailFormatWebp()
                    width = 32; height = 32
                    file = TdApi.File().apply { id = 81 }
                }
            }
            fixture.last.reply(TdApi.Stickers(arrayOf(sticker)))
            val visual = assertNotNull(result?.value)
            assertEquals(if (withThumbnail) 81 else 80, visual.staticIcon?.file?.id)
            assertEquals(if (withThumbnail) MediaFormat.WEBP else MediaFormat.WEBM, visual.staticIcon?.format)
            assertEquals(80, visual.selectAnimation?.file?.id)
            assertEquals(MediaFormat.WEBM, visual.selectAnimation?.format)
        }
    }

    private fun sticker(fileId: Int) = TdApi.Sticker().apply {
        sticker = TdApi.File().apply { id = fileId }
        format = TdApi.StickerFormatWebp()
    }

    @Test fun customEmojiPacksComeFromNativeInstalledAndCurrentSupergroupCatalogs() {
        val fixture = Fixture()
        var packs: ChatResult<List<StickerPack>>? = null
        fixture.session.reactionPacks(1) { packs = it }
        assertIs<TdApi.StickerTypeCustomEmoji>(assertIs<TdApi.GetInstalledStickerSets>(fixture.last.command).stickerType)
        fixture.last.reply(TdApi.StickerSets(1, arrayOf(TdApi.StickerSetInfo().apply {
            id = 100; title = "installed native"; stickerType = TdApi.StickerTypeCustomEmoji(); size = 2
        })))
        assertEquals(1L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
        fixture.last.reply(TdApi.Chat().apply { id = 1; type = TdApi.ChatTypeSupergroup(7, false) })
        assertEquals(7L, assertIs<TdApi.GetSupergroupFullInfo>(fixture.last.command).supergroupId)
        fixture.last.reply(TdApi.SupergroupFullInfo().apply { customEmojiStickerSetId = 101 })
        assertEquals(101L, assertIs<TdApi.GetStickerSet>(fixture.last.command).setId)
        fixture.last.reply(TdApi.StickerSet().apply { id = 101; title = "group native"; stickerType = TdApi.StickerTypeCustomEmoji() })
        assertEquals(listOf(101L, 100L), packs?.value?.map { it.id })

        var contents: ChatResult<List<ReactionChoice>>? = null
        fixture.session.reactionPack(101) { contents = it }
        fixture.last.reply(TdApi.StickerSet().apply {
            id = 101; stickerType = TdApi.StickerTypeCustomEmoji()
            stickers = arrayOf(sticker(800).apply { fullType = TdApi.StickerFullTypeCustomEmoji(42, false) },
                sticker(801).apply { fullType = TdApi.StickerFullTypeCustomEmoji(42, false) }, sticker(802))
        })
        assertEquals(listOf(ReactionKey(customEmojiId = 42)), contents?.value?.map { it.key })
        assertEquals(ReactionSource.PACK, contents?.value?.single()?.source)
    }

    @Test fun chosenCustomReactionCanBeRemovedEvenWhenAddingIsNoLongerAllowed() {
        val fixture = Fixture()
        fixture.session.setCustomReaction(1, 2, 42, false) {}
        fixture.last.reply(message().apply {
            interactionInfo.reactions.reactions = arrayOf(TdApi.MessageReaction(TdApi.ReactionTypeCustomEmoji(42), 2, true, null, emptyArray()))
        })
        val remove = assertIs<TdApi.RemoveMessageReaction>(fixture.last.command)
        assertEquals(42L, assertIs<TdApi.ReactionTypeCustomEmoji>(remove.reactionType).customEmojiId)
        assertEquals(2, fixture.requests.size)
    }

    @Test fun anotherJoinedGroupsEmojiPackIsResolvedOnDemandWithoutReadingItsMessages() {
        val joined = listOf(TdApi.ChatMemberStatusMember(), TdApi.ChatMemberStatusAdministrator(),
            TdApi.ChatMemberStatusCreator(false, true), TdApi.ChatMemberStatusRestricted().apply { isMember = true })
        for (status in joined) {
            val fixture = Fixture()
            var result: ChatResult<ReactionGroupPack>? = null
            fixture.session.reactionGroupPack(90) { result = it }
            assertEquals(90L, assertIs<TdApi.GetChat>(fixture.last.command).chatId)
            fixture.last.reply(sourceGroup())
            assertEquals(19L, assertIs<TdApi.GetSupergroup>(fixture.last.command).supergroupId)
            fixture.last.reply(TdApi.Supergroup().apply { id = 19; this.status = status })
            assertEquals(19L, assertIs<TdApi.GetSupergroupFullInfo>(fixture.last.command).supergroupId)
            fixture.last.reply(TdApi.SupergroupFullInfo().apply { customEmojiStickerSetId = 150 })
            assertEquals(150L, assertIs<TdApi.GetStickerSet>(fixture.last.command).setId)
            fixture.last.reply(sourcePack())
            val native = assertNotNull(result?.value)
            assertEquals(90L, native.sourceChatId)
            assertEquals(150L, native.pack.id)
            assertEquals("other joined group's native pack", native.pack.title)
            assertEquals(listOf(ReactionKey(customEmojiId = 401)), native.choices.map { it.key })
            assertEquals(ReactionSource.PACK, native.choices.single().source)
            assertEquals(4, fixture.requests.size, "Choosing a source must not enumerate other full-info objects or read any messages.")
            assertTrue(fixture.requests.all { it.command is TdApi.GetChat || it.command is TdApi.GetSupergroup ||
                it.command is TdApi.GetSupergroupFullInfo || it.command is TdApi.GetStickerSet })
            assertTrue(fixture.requests.none { it.command is TdApi.AddMessageReaction })
        }
    }

    @Test fun otherGroupPacksRejectNonGroupsAndMismatchedNativeSourceIdentities() {
        val cases = listOf(sourceGroup().apply { id = 91 } to ChatError.REQUEST_FAILED,
            sourceGroup().apply { type = TdApi.ChatTypePrivate(12) } to ChatError.UNSUPPORTED,
            sourceGroup().apply { type = TdApi.ChatTypeBasicGroup(12) } to ChatError.UNSUPPORTED,
            sourceGroup().apply { type = TdApi.ChatTypeSupergroup(19, true) } to ChatError.UNSUPPORTED)
        for ((chat, error) in cases) {
            val fixture = Fixture()
            var result: ChatResult<ReactionGroupPack>? = null
            fixture.session.reactionGroupPack(90) { result = it }
            fixture.last.reply(chat)
            assertEquals(error, result?.error)
            assertEquals(1, fixture.requests.size)
        }
        val fixture = Fixture()
        var result: ChatResult<ReactionGroupPack>? = null
        fixture.session.reactionGroupPack(0) { result = it }
        assertEquals(ChatError.REQUEST_FAILED, result?.error)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun otherGroupPacksRequireCurrentNativeMembershipAndMatchingSupergroup() {
        val nonmembers = listOf(null, TdApi.ChatMemberStatusLeft(), TdApi.ChatMemberStatusBanned(),
            TdApi.ChatMemberStatusCreator(false, false), TdApi.ChatMemberStatusRestricted().apply { isMember = false })
        for (status in nonmembers) {
            val fixture = Fixture()
            var result: ChatResult<ReactionGroupPack>? = null
            fixture.session.reactionGroupPack(90) { result = it }
            fixture.last.reply(sourceGroup())
            fixture.last.reply(TdApi.Supergroup().apply { id = 19; this.status = status })
            assertEquals(ChatError.FORBIDDEN, result?.error)
            assertEquals(2, fixture.requests.size, "Unjoined groups must never request a full-info object or pack.")
        }
        for ((nativeGroup, error) in listOf(
            TdApi.Supergroup().apply { id = 20; status = TdApi.ChatMemberStatusMember() } to ChatError.REQUEST_FAILED,
            TdApi.Supergroup().apply { id = 19; status = TdApi.ChatMemberStatusMember(); isChannel = true } to ChatError.UNSUPPORTED)) {
            val fixture = Fixture()
            var result: ChatResult<ReactionGroupPack>? = null
            fixture.session.reactionGroupPack(90) { result = it }
            fixture.last.reply(sourceGroup())
            fixture.last.reply(nativeGroup)
            assertEquals(error, result?.error)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun otherGroupPacksKeepMissingPacksAndNativeFailuresTypedAndRejectMismatchedSets() {
        val noPack = Fixture()
        var missing: ChatResult<ReactionGroupPack>? = null
        noPack.session.reactionGroupPack(90) { missing = it }
        noPack.last.reply(sourceGroup())
        noPack.last.reply(TdApi.Supergroup().apply { id = 19; status = TdApi.ChatMemberStatusMember() })
        noPack.last.reply(TdApi.SupergroupFullInfo())
        assertEquals(ChatError.UNSUPPORTED, missing?.error)
        assertEquals(3, noPack.requests.size)

        val forbidden = Fixture()
        var denied: ChatResult<ReactionGroupPack>? = null
        forbidden.session.reactionGroupPack(90) { denied = it }
        forbidden.last.reply(sourceGroup())
        forbidden.last.reply(TdApi.Supergroup().apply { id = 19; status = TdApi.ChatMemberStatusMember() })
        forbidden.last.reply(TdApi.Error(403, "synthetic private source"))
        assertEquals(ChatError.FORBIDDEN, denied?.error)
        assertEquals(403, denied?.code)
        assertEquals(3, forbidden.requests.size)

        for (invalid in listOf(sourcePack().apply { id = 151 },
            sourcePack().apply { stickerType = TdApi.StickerTypeRegular() })) {
            val fixture = Fixture()
            var result: ChatResult<ReactionGroupPack>? = null
            fixture.session.reactionGroupPack(90) { result = it }
            fixture.last.reply(sourceGroup())
            fixture.last.reply(TdApi.Supergroup().apply { id = 19; status = TdApi.ChatMemberStatusMember() })
            fixture.last.reply(TdApi.SupergroupFullInfo().apply { customEmojiStickerSetId = 150 })
            fixture.last.reply(invalid)
            assertEquals(ChatError.REQUEST_FAILED, result?.error)
            assertNull(result?.value)
        }
    }

    @Test fun resolvingAnotherGroupsPackNeverGrantsReactionPermissionInTheTargetChat() {
        val fixture = Fixture()
        fixture.session.reactionGroupPack(90) {}
        fixture.last.reply(sourceGroup())
        fixture.last.reply(TdApi.Supergroup().apply { id = 19; status = TdApi.ChatMemberStatusMember() })
        fixture.last.reply(TdApi.SupergroupFullInfo().apply { customEmojiStickerSetId = 150 })
        fixture.last.reply(sourcePack())
        var result: ChatResult<Unit>? = null
        fixture.session.setCustomReaction(1, 2, 401, true) { result = it }
        val target = assertIs<TdApi.GetMessage>(fixture.last.command)
        assertEquals(1L, target.chatId)
        assertEquals(2L, target.messageId)
        fixture.last.reply(message())
        val available = assertIs<TdApi.GetMessageAvailableReactions>(fixture.last.command)
        assertEquals(1L, available.chatId)
        assertEquals(2L, available.messageId)
        fixture.last.reply(options().apply { allowCustomEmoji = false })
        assertEquals(ChatError.FORBIDDEN, result?.error)
        assertTrue(fixture.requests.none { it.command is TdApi.AddMessageReaction || it.command is TdApi.GetMe })
    }

    @Test fun nativeMembershipArrivingBeforeTheChatIsPreservedInTheKnownSourceGroup() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 19; status = TdApi.ChatMemberStatusMember()
        }))
        mapper.accept(TdApi.UpdateNewChat(sourceGroup()))
        val known = events.filterIsInstance<ChatEvent.NewChat>().single().chat
        assertEquals(90L, known.id)
        assertTrue(known.isJoinedGroup)
        assertEquals(19L, known.supergroupId)
        assertFalse(known.isChannel)
    }

    @Test fun knownSourceMembershipTracksNativeJoinAndLeaveWhileChannelsNeverBecomeGroupSources() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateNewChat(sourceGroup()))
        assertFalse(events.filterIsInstance<ChatEvent.NewChat>().single().chat.isJoinedGroup)
        events.clear()
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 19; status = TdApi.ChatMemberStatusMember()
        }))
        assertEquals(ChatEvent.GroupMembership(90, true), events.filterIsInstance<ChatEvent.GroupMembership>().single())
        events.clear()
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 19; status = TdApi.ChatMemberStatusLeft()
        }))
        assertEquals(ChatEvent.GroupMembership(90, false), events.filterIsInstance<ChatEvent.GroupMembership>().single())

        events.clear()
        mapper.accept(TdApi.UpdateNewChat(TdApi.Chat().apply { id = 91; type = TdApi.ChatTypeSupergroup(20, true) }))
        mapper.accept(TdApi.UpdateSupergroup(TdApi.Supergroup().apply {
            id = 20; isChannel = true; status = TdApi.ChatMemberStatusMember()
        }))
        assertFalse(events.filterIsInstance<ChatEvent.NewChat>().single().chat.isJoinedGroup)
        assertTrue(events.none { it is ChatEvent.GroupMembership })
    }

    private fun sourceGroup() = TdApi.Chat().apply { id = 90; type = TdApi.ChatTypeSupergroup(19, false) }

    private fun sourcePack() = TdApi.StickerSet().apply {
        id = 150; title = "other joined group's native pack"; stickerType = TdApi.StickerTypeCustomEmoji()
        stickers = arrayOf(sticker(700).apply { fullType = TdApi.StickerFullTypeCustomEmoji(401, false) },
            sticker(701).apply { fullType = TdApi.StickerFullTypeCustomEmoji(401, false) },
            sticker(702).apply { fullType = TdApi.StickerFullTypeCustomEmoji(0, false) }, sticker(703))
    }

    private fun message(chosen: Boolean = false) = TdApi.Message().apply {
        id = 2; chatId = 1; content = TdApi.MessageText(TdApi.FormattedText("synthetic", emptyArray()), null, null)
        interactionInfo = TdApi.MessageInteractionInfo().apply {
            reactions = TdApi.MessageReactions(arrayOf(TdApi.MessageReaction(TdApi.ReactionTypeEmoji("👍"), 3, chosen, null, emptyArray())), false, emptyArray(), false)
        }
    }
    private fun options() = TdApi.AvailableReactions().apply {
        topReactions = arrayOf(TdApi.AvailableReaction(TdApi.ReactionTypeEmoji("👍"), false))
    }
    private class Fixture {
        val requests = mutableListOf<Request>()
        val session = OfficialTdChatSession { command, reply, _ -> requests.add(Request(command, reply)) }
        val last get() = requests.last()
    }
    private class Request(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit)
}
