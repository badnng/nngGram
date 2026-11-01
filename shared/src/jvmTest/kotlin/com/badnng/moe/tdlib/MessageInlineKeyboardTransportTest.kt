package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class MessageInlineKeyboardTransportTest {
    @Test fun nativeUrlRowsAreMappedWithoutParsingMessageText() {
        val markup = keyboard(
            arrayOf(button("download", TdApi.InlineKeyboardButtonTypeUrl("https://example.org/artifact")),
                button("details", TdApi.InlineKeyboardButtonTypeUrl("https://example.org/build"))),
            arrayOf(button("callback", TdApi.InlineKeyboardButtonTypeCallback(byteArrayOf(1, 2)))),
        )
        val mapped = mapChatMessage(message(markup))
        assertEquals(listOf(2, 1), mapped.inlineKeyboard?.rows?.map { it.size })
        assertEquals("https://example.org/build", mapped.inlineKeyboard?.rows?.first()?.get(1)?.externalUrl())
        assertNull(mapped.inlineKeyboard?.rows?.last()?.single()?.externalUrl())
        assertNull(mapChatMessage(message(null)).inlineKeyboard)
    }

    @Test fun authenticatedWebAppPaymentAndCallbackButtonsNeverBecomeUrlActions() {
        val types = listOf(TdApi.InlineKeyboardButtonTypeLoginUrl("https://example.org/login", 7, "login"),
            TdApi.InlineKeyboardButtonTypeWebApp("https://example.org/app"), TdApi.InlineKeyboardButtonTypeBuy(),
            TdApi.InlineKeyboardButtonTypeCallback(byteArrayOf(1)), TdApi.InlineKeyboardButtonTypeCallbackGame(),
            TdApi.InlineKeyboardButtonTypeDisabled())
        val mapped = assertNotNull(mapMessageInlineKeyboard(keyboard(types.mapIndexed { index, type -> button("button$index", type) }.toTypedArray())))
        assertEquals(types.size, mapped.rows.single().size)
        assertTrue(mapped.rows.single().all { it.kind == MessageInlineButtonKind.UNSUPPORTED && it.url == null })
        assertNull(mapMessageInlineKeyboard(TdApi.ReplyMarkupRemoveKeyboard()))
    }

    @Test fun nativeEditedUpdateAddsReplacesAndRemovesKeyboardIndependentlyOfContent() {
        val events = mutableListOf<ChatEvent>()
        val mapper = TdChatUpdateMapper(events::add)
        mapper.accept(TdApi.UpdateMessageEdited(1, 10, 50, keyboard(arrayOf(button("link", TdApi.InlineKeyboardButtonTypeUrl("https://example.org"))))))
        assertEquals(50, assertIs<ChatEvent.Edited>(events[0]).editDate)
        assertEquals("https://example.org", assertIs<ChatEvent.ReplyMarkup>(events[1]).inlineKeyboard?.rows?.single()?.single()?.url)
        mapper.accept(TdApi.UpdateMessageEdited(1, 10, 51, null))
        assertNull(assertIs<ChatEvent.ReplyMarkup>(events.last()).inlineKeyboard)
    }

    private fun button(text: String, type: TdApi.InlineKeyboardButtonType) = TdApi.InlineKeyboardButton().apply { this.text = text; this.type = type }
    private fun keyboard(vararg rows: Array<TdApi.InlineKeyboardButton>) = TdApi.ReplyMarkupInlineKeyboard().apply { this.rows = arrayOf(*rows) }
    private fun message(markup: TdApi.ReplyMarkup?) = TdApi.Message().apply {
        id = 10; chatId = 1; content = TdApi.MessageText(TdApi.FormattedText("[label](https://fake.example)", emptyArray()), null, null)
        replyMarkup = markup
    }
}
