package com.badnng.moe.tdlib

import com.badnng.moe.chat.MessageEntityKind
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialRichTextTransportTest {
    @Test fun expandableBlockquoteAndCustomEmojiSurviveDisplayAndEditRoundTrip() {
        val native = TdApi.FormattedText("引用🙂\n后文", arrayOf(
            TdApi.TextEntity(0, 4, TdApi.TextEntityTypeExpandableBlockQuote()),
            TdApi.TextEntity(2, 2, TdApi.TextEntityTypeCustomEmoji(456)),
        ))
        val content = mapRichText(native)
        assertEquals(MessageEntityKind.EXPANDABLE_BLOCK_QUOTE, content.entities.first().kind)
        val roundTrip = content.toNative()
        assertEquals(native.text, roundTrip.text)
        assertIs<TdApi.TextEntityTypeExpandableBlockQuote>(roundTrip.entities.first().type)
        assertEquals(456L, assertIs<TdApi.TextEntityTypeCustomEmoji>(roundTrip.entities.last().type).customEmojiId)
    }
}
