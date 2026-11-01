package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.chat.MessageInlineKeyboard as Keyboard
import kotlin.test.*

class MessageInlineKeyboardTest {
    private val keyboard = Keyboard(listOf(listOf(
        MessageInlineButton("下载产物", MessageInlineButtonKind.URL, "https://example.org/artifact"),
        MessageInlineButton("构建详情", MessageInlineButtonKind.URL, "https://example.org/build"),
    )))
    private val token = ChatToken(3, 7, 1)
    private val message = ChatMessage(10, 1, inlineKeyboard = keyboard)
    private val snapshot = ChatSnapshot(enabled = true, token = token, selected = ChatItem(1, "synthetic"), messages = listOf(message))
    private val click = MessageInlineButtonClick(1, 10, 0, 1, keyboard)

    @Test fun nativeColumnsResolveTheirOwnTarget() {
        assertEquals("https://example.org/build", inlineMessageButtonUrl(snapshot, token, click, true))
        assertEquals("https://example.org/artifact", inlineMessageButtonUrl(snapshot, token, click.copy(column = 0), true))
        assertNull(inlineMessageButtonUrl(snapshot, token, click.copy(row = -1), true))
        assertNull(inlineMessageButtonUrl(snapshot, token, click.copy(column = 2), true))
    }

    @Test fun changedSelectionDeletedMessageInactivePageAndEditedMarkupInvalidateClick() {
        assertNull(inlineMessageButtonUrl(snapshot, token, click, false))
        assertNull(inlineMessageButtonUrl(snapshot.copy(enabled = false), token, click, true))
        assertNull(inlineMessageButtonUrl(snapshot.copy(token = token.copy(selection = 8)), token, click, true))
        assertNull(inlineMessageButtonUrl(snapshot.copy(selected = ChatItem(2, "other")), token, click, true))
        assertNull(inlineMessageButtonUrl(snapshot.copy(messages = emptyList()), token, click, true))
        assertNull(inlineMessageButtonUrl(snapshot.copy(messages = listOf(message.copy(inlineKeyboard = null))), token, click, true))
        val edited = Keyboard(listOf(listOf(MessageInlineButton("new", MessageInlineButtonKind.URL, "https://example.org/new"))))
        assertNull(inlineMessageButtonUrl(snapshot.copy(messages = listOf(message.copy(inlineKeyboard = edited))), token, click.copy(column = 0), true))
        assertNull(inlineMessageButtonUrl(snapshot.copy(messages = listOf(message.copy(delivery = MessageDelivery.PENDING))), token, click, true))
    }

    @Test fun onlyExplicitNetworkUrlActionsAreExecutable() {
        listOf("file:///C:/app.exe", "javascript:alert(1)", "data:text/plain,hi", "tg://user?id=1", "mailto:x@example.org",
            "tel:123", "example.org/no-scheme", "https://", "https://user@example.org", "https://example.org\nrun").forEach {
            assertNull(MessageInlineButton("native label", MessageInlineButtonKind.URL, it).externalUrl())
        }
        assertNull(MessageInlineButton("callback", MessageInlineButtonKind.UNSUPPORTED, "https://example.org").externalUrl())
        assertEquals("https://example.org", MessageInlineButton("native label", MessageInlineButtonKind.URL, "https://example.org").externalUrl())
    }

    @Test fun allKeyboardContentIsRedacted() {
        listOf(keyboard.toString(), keyboard.rows.first().first().toString(), click.toString()).forEach {
            assertFalse(it.contains("example.org"))
            assertFalse(it.contains("下载产物"))
        }
    }
}
