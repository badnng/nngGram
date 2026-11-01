package com.badnng.moe

import androidx.compose.ui.text.AnnotatedString
import com.badnng.moe.chat.*
import kotlin.test.*

class AnimatedEmojiTextSemanticsTest {
    private val emoji = "✌🏽"
    private val content = RichMessageText(emoji, listOf(MessageEntity(0, emoji.length, MessageEntityKind.CUSTOM_EMOJI, id = 77)))
    private val token = ChatToken(2, 3, 10)
    private val message = ChatMessage(100, 10, text = content.text, kind = MessageKind.ANIMATED_EMOJI,
        entities = content.entities, canBeSaved = true,
        media = MessageMedia(MediaFile(42, localPath = "C:/synthetic/emoji.webp", downloaded = true),
            format = MediaFormat.WEBP, animatedEmoji = AnimatedEmojiMedia(100, 100, 5, false)))
    private val snapshot = ChatSnapshot(enabled = true, token = token, selected = ChatItem(10, "synthetic"), messages = listOf(message))

    @Test fun standaloneEmojiRetainsTextIdentityForPreviewAndEditingWithoutAStickerLabel() {
        assertTrue(MessageKind.TEXT.isTextContent)
        assertTrue(MessageKind.ANIMATED_EMOJI.isTextContent)
        assertFalse(MessageKind.STICKER.isTextContent)
        assertFalse(MessageKind.ANIMATION.isTextContent)
        assertEquals(content, message.richText())
        assertEquals(emoji, messagePreview(message))
        assertEquals(emoji, messagePreview(message.copy(media = null)))
        assertEquals("[等待发送] $emoji", messagePreview(message.copy(delivery = MessageDelivery.PENDING)))
        assertEquals("[发送失败] $emoji", messagePreview(message.copy(delivery = MessageDelivery.FAILED)))
    }

    @Test fun copyAndQuoteKeepTheCompleteUtf16EmojiAndItsActualCustomIdentity() {
        val selected = AnnotatedString(emoji).withQuoteSourceOffsets()
        val copy = messageTextCopyRequest(token, message, listOf(selected))
        assertEquals(content, validatedMessageTextCopy(snapshot, copy, true))
        assertEquals(content, validatedMessageTextCopy(snapshot, messageTextCopyRequest(token, message, emptyList()), true))
        assertEquals(MessageQuote(0, content), content.quoteFromSelection(listOf(selected)))
        assertNull(content.quoteFromSelection(listOf(selected.subSequence(0, emoji.length - 1))),
            "A selection ending inside a surrogate pair must not create a native quote")
    }

    @Test fun textCopyKeepsProtectionAndCurrentSourceChecksEvenWhenTheEmojiArtworkIsDownloaded() {
        val copy = messageTextCopyRequest(token, message, emptyList())
        assertNull(validatedMessageTextCopy(snapshot.copy(selected = snapshot.selected!!.copy(hasProtectedContent = true)), copy, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(messages = listOf(message.copy(entities = emptyList()))), copy, true))
        assertNull(validatedMessageTextCopy(snapshot.copy(token = token.copy(selection = 4)), copy, true))
        assertNull(validatedMessageTextCopy(snapshot, copy, false))
    }

    @Test fun anEmojiPictureNeverBecomesAGenericClipboardAttachmentInsteadOfItsText() {
        assertNull(clipboardMessageSource(snapshot, message.id))
        val photo = message.copy(kind = MessageKind.PHOTO, text = "", entities = emptyList(),
            media = MessageMedia(MediaFile(43, localPath = "C:/synthetic/photo.png", downloaded = true)))
        assertNotNull(clipboardMessageSource(snapshot.copy(messages = listOf(photo)), photo.id),
            "The standalone text rule must not disable native photo attachments")
    }
}
