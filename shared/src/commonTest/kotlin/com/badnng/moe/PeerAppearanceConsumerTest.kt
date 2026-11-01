package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Actual consumer resolution, separate from the lower-level native/palette mapping tests. */
class PeerAppearanceConsumerTest {
    @Test fun identicalUserAndChatNumbersRemainDifferentAtTheSenderAndReplyConsumers() {
        val snapshot = snapshot()
        for (dark in listOf(false, true)) {
            val user = ChatMessage(1, 100, senderId = 7)
            val chat = user.copy(senderIsChat = true)
            assertEquals(if (dark) 0xe0d000 else 0xe00000,
                assertNotNull(snapshot.messageSenderNamePalette(user, dark)).nameRgb)
            assertEquals(if (dark) 0x00d0e0 else 0x0040e0,
                assertNotNull(snapshot.messageSenderNamePalette(chat, dark)).nameRgb)
            assertEquals(snapshot.messageSenderNamePalette(user, dark),
                snapshot.replyNamePalette(MessageReply(100, 1, senderId = 7), null, dark))
            assertEquals(snapshot.messageSenderNamePalette(chat, dark),
                snapshot.replyNamePalette(MessageReply(100, 1, senderId = 7, senderIsChat = true), null, dark))
        }
        assertNull(snapshot.copy(senderAppearances = emptyMap()).messageSenderNamePalette(ChatMessage(1, 100, senderId = 7), false),
            "A known Chat(7) must never satisfy a missing User(7).")
        assertNull(snapshot.copy(knownChats = emptyList()).messageSenderNamePalette(
            ChatMessage(1, 100, senderId = 7, senderIsChat = true), false))
    }

    @Test fun outgoingOwnershipDoesNotReplaceTheActualSenderWithSelfAppearance() {
        val snapshot = snapshot()
        val message = ChatMessage(1, 100, senderId = 7, outgoing = true)
        assertEquals(snapshot.messageSenderNamePalette(message.copy(outgoing = false), false),
            snapshot.messageSenderNamePalette(message, false))
        assertEquals(0xe00000, assertNotNull(snapshot.messageSenderNamePalette(message, false)).nameRgb)
        assertNull(snapshot.messageSenderNamePalette(message.copy(senderId = 999), false))
    }

    @Test fun nameConsumerUsesGiftSymbolAndModelWhileProfilePairRemainsIndependent() {
        val gift = SettingsUpgradedGiftColors(5, -500, -501, 0x123456, listOf(0x234567, 0x345678),
            0x654321, listOf(0x765432, 0x876543, 0x987654))
        val base = snapshot()
        val value = assertNotNull(base.senderAppearances[7])
        val gifted = base.copy(senderAppearances = mapOf(7L to value.copy(gift = gift)))
        val message = ChatMessage(1, 100, senderId = 7)
        val light = assertNotNull(gifted.messageSenderNamePalette(message, false))
        assertEquals(0x123456, light.nameRgb)
        assertEquals(listOf(0x234567, 0x345678), light.replyColors)
        assertEquals(-501L, light.backgroundCustomEmojiId)
        assertEquals(-500L, light.giftModelCustomEmojiId)
        val dark = assertNotNull(gifted.messageSenderNamePalette(message, true))
        assertEquals(0x654321, dark.nameRgb)
        assertEquals(listOf(0x765432, 0x876543, 0x987654), dark.replyColors)
        val changedProfile = gifted.copy(senderAppearances = mapOf(7L to value.copy(
            profile = SettingsProfileColorValue(999, -999), gift = gift)))
        assertEquals(light, changedProfile.messageSenderNamePalette(message, false))
    }

    @Test fun forwardedReplyUsesItsOriginalAuthorRatherThanItsForwarderOrImmediateSource() {
        val snapshot = snapshot()
        val message = ChatMessage(1, 100, senderId = 7, forwardInfo = MessageForwardInfo(
            MessageForwardOrigin.Chat(7), source = MessageForwardSource(88, 20, senderId = 7)))
        val reply = MessageReply(100, 1, senderId = 7)
        for (dark in listOf(false, true)) {
            assertEquals(if (dark) 0x00d0e0 else 0x0040e0,
                assertNotNull(snapshot.messageContentNamePalette(message, dark)).nameRgb)
            assertEquals(snapshot.messageContentNamePalette(message, dark), snapshot.replyNamePalette(reply, message, dark))
            assertEquals(if (dark) 0xe0d000 else 0xe00000,
                assertNotNull(snapshot.messageSenderNamePalette(message, dark)).nameRgb)
        }
        val unresolvedOriginal = message.copy(forwardInfo = MessageForwardInfo(MessageForwardOrigin.User(99)))
        assertNull(snapshot.replyNamePalette(reply, unresolvedOriginal, false),
            "An unresolved original identity must not borrow the forwarding sender's color.")
    }

    @Test fun hiddenForwardHasNoPeerColorAndAccountAppearanceCannotCreatePrivateSenderSlots() {
        val snapshot = snapshot()
        val message = ChatMessage(1, 100, senderId = 7, forwardInfo = MessageForwardInfo(
            MessageForwardOrigin.HiddenUser("Hidden author"), source = MessageForwardSource(7, 9, senderId = 7)))
        assertNull(snapshot.messageContentNamePalette(message, false))
        assertNull(snapshot.replyNamePalette(MessageReply(100, 1, senderId = 7), message, false))
        val privateChat = ChatItem(100, "Private", privateUserId = 7, appearance = values(11))
        for (outgoing in listOf(false, true)) {
            val presentation = conversationMessagePresentation(message.copy(forwardInfo = null, outgoing = outgoing),
                privateChat, startsGroup = true, endsGroup = true)
            assertFalse(presentation.showSenderName)
            assertFalse(presentation.showAvatar)
            assertFalse(presentation.reserveAvatarSpace)
        }
    }

    @Test fun offWindowReplyUsesItsKnownTypedContentAuthorInsteadOfItsImmediateForwarder() {
        val snapshot = snapshot()
        val reply = MessageReply(100, 1, senderId = 7, contentAuthorKnown = true)
        for (dark in listOf(false, true)) {
            val user = reply.copy(senderIsChat = true, contentAuthor = ForwardSourceIdentity.User(7))
            val chat = reply.copy(contentAuthor = ForwardSourceIdentity.Chat(7))
            assertEquals(if (dark) 0xe0d000 else 0xe00000,
                assertNotNull(snapshot.replyNamePalette(user, null, dark)).nameRgb)
            assertEquals(if (dark) 0x00d0e0 else 0x0040e0,
                assertNotNull(snapshot.replyNamePalette(chat, null, dark)).nameRgb)
            assertNull(snapshot.replyNamePalette(reply, null, dark),
                "A known hidden/null content author must not borrow the forwarder's native appearance.")
            assertNull(snapshot.replyNamePalette(reply.copy(contentAuthor = ForwardSourceIdentity.User(99)), null, dark),
                "An unresolved known content author must not fall back to the forwarding sender.")
            assertEquals(snapshot.messageSenderNamePalette(ChatMessage(1, 100, senderId = 7), dark),
                snapshot.replyNamePalette(reply.copy(contentAuthorKnown = false), null, dark),
                "Legacy replies without a native content-author result retain their prior sender fallback.")
            val original = ChatMessage(1, 100, senderId = 7,
                forwardInfo = MessageForwardInfo(MessageForwardOrigin.Chat(7)))
            assertEquals(snapshot.messageContentNamePalette(original, dark),
                snapshot.replyNamePalette(user, original, dark),
                "The exact visible original remains authoritative over stored reply metadata.")
        }
    }

    private fun snapshot() = ChatSnapshot(
        account = AccountProfile(77, "Self", appearance = values(0)),
        senderAppearances = mapOf(7L to values(10)),
        knownChats = listOf(ChatItem(7, "Chat seven", appearance = values(11))),
        appearanceCatalog = SettingsProfileColorsCatalog(nameColors = listOf(
            SettingsNameAccentColor(10, 0, listOf(0xe00000, 0x00a000, 0x0000e0), listOf(0xe0d000, 0xd000e0)),
            SettingsNameAccentColor(11, 1, listOf(0x0040e0), listOf(0x00d0e0)),
        ), availableNameColorIds = emptyList()),
    )
    private fun values(nameId: Int) = SettingsProfileColorsValues(SettingsProfileColorValue(nameId, -91),
        SettingsProfileColorValue(80, -92))
}
