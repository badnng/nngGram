package com.badnng.moe.tdlib

import com.badnng.moe.chat.MessageInlineButton
import com.badnng.moe.chat.MessageInlineButtonKind
import com.badnng.moe.chat.MessageInlineKeyboard
import org.drinkless.tdlib.TdApi

/** Never infer buttons from text, or reinterpret authenticated/payment actions as ordinary URLs. */
internal fun mapMessageInlineKeyboard(markup: TdApi.ReplyMarkup?): MessageInlineKeyboard? {
    val inline = markup as? TdApi.ReplyMarkupInlineKeyboard ?: return null
    val rows = inline.rows.orEmpty().map { row ->
        row.orEmpty().map { button ->
            val type = button?.type
            MessageInlineButton(
                text = button?.text.orEmpty(),
                kind = if (type is TdApi.InlineKeyboardButtonTypeUrl) MessageInlineButtonKind.URL else MessageInlineButtonKind.UNSUPPORTED,
                url = (type as? TdApi.InlineKeyboardButtonTypeUrl)?.url,
            )
        }
    }
    return MessageInlineKeyboard(rows).takeIf { rows.any { it.isNotEmpty() } }
}
