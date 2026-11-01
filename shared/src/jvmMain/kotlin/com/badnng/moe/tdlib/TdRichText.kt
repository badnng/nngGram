package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi

internal fun formattedContent(content: TdApi.MessageContent?): TdApi.FormattedText? = when (content) {
    is TdApi.MessageText -> content.text
    is TdApi.MessageAnimatedEmoji -> animatedEmojiText(content)
    is TdApi.MessagePhoto -> content.caption
    is TdApi.MessageVideo -> content.caption
    is TdApi.MessageDocument -> content.caption
    is TdApi.MessageAudio -> content.caption
    is TdApi.MessageAnimation -> content.caption
    is TdApi.MessageVoiceNote -> content.caption
    else -> null
}

/** TDLib exposes a standalone emoji as presentation content while its message remains editable text. */
internal fun animatedEmojiText(content: TdApi.MessageAnimatedEmoji): TdApi.FormattedText {
    val text = content.emoji.orEmpty()
    val identity = (content.animatedEmoji?.sticker?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: 0L
    val entities = if (identity != 0L && text.isNotEmpty())
        arrayOf(TdApi.TextEntity(0, text.length, TdApi.TextEntityTypeCustomEmoji(identity))) else emptyArray()
    return TdApi.FormattedText(text, entities)
}

internal fun mapRichText(value: TdApi.FormattedText?): RichMessageText {
    val text = value?.text.orEmpty()
    val entities = value?.entities.orEmpty().mapNotNull { entity ->
        entity ?: return@mapNotNull null
        var target = ""
        var id = 0L
        val kind = when (val type = entity.type) {
            is TdApi.TextEntityTypeBold -> MessageEntityKind.BOLD
            is TdApi.TextEntityTypeItalic -> MessageEntityKind.ITALIC
            is TdApi.TextEntityTypeUnderline -> MessageEntityKind.UNDERLINE
            is TdApi.TextEntityTypeStrikethrough -> MessageEntityKind.STRIKETHROUGH
            is TdApi.TextEntityTypeSpoiler -> MessageEntityKind.SPOILER
            is TdApi.TextEntityTypeCode -> MessageEntityKind.CODE
            is TdApi.TextEntityTypePre -> MessageEntityKind.PRE
            is TdApi.TextEntityTypePreCode -> { target = type.language.orEmpty(); MessageEntityKind.PRE }
            is TdApi.TextEntityTypeBlockQuote -> MessageEntityKind.BLOCK_QUOTE
            is TdApi.TextEntityTypeExpandableBlockQuote -> MessageEntityKind.EXPANDABLE_BLOCK_QUOTE
            is TdApi.TextEntityTypeUrl -> MessageEntityKind.URL
            is TdApi.TextEntityTypeTextUrl -> { target = type.url.orEmpty(); MessageEntityKind.TEXT_URL }
            is TdApi.TextEntityTypeEmailAddress -> MessageEntityKind.EMAIL
            is TdApi.TextEntityTypePhoneNumber -> MessageEntityKind.PHONE
            is TdApi.TextEntityTypeMention -> MessageEntityKind.MENTION
            is TdApi.TextEntityTypeMentionName -> { id = type.userId; MessageEntityKind.MENTION_NAME }
            is TdApi.TextEntityTypeHashtag -> MessageEntityKind.HASHTAG
            is TdApi.TextEntityTypeCashtag -> MessageEntityKind.CASHTAG
            is TdApi.TextEntityTypeCustomEmoji -> { id = type.customEmojiId; MessageEntityKind.CUSTOM_EMOJI }
            else -> return@mapNotNull null
        }
        MessageEntity(entity.offset, entity.length, kind, target, id)
    }
    return RichMessageText(text, entities).let { it.copy(entities = it.validEntities()) }
}

internal fun RichMessageText.toNative(): TdApi.FormattedText {
    require(validEntities().size == entities.size)
    return TdApi.FormattedText(text, normalized().entities.map { entity ->
        val type = when (entity.kind) {
            MessageEntityKind.BOLD -> TdApi.TextEntityTypeBold()
            MessageEntityKind.ITALIC -> TdApi.TextEntityTypeItalic()
            MessageEntityKind.UNDERLINE -> TdApi.TextEntityTypeUnderline()
            MessageEntityKind.STRIKETHROUGH -> TdApi.TextEntityTypeStrikethrough()
            MessageEntityKind.SPOILER -> TdApi.TextEntityTypeSpoiler()
            MessageEntityKind.CODE -> TdApi.TextEntityTypeCode()
            MessageEntityKind.PRE -> TdApi.TextEntityTypePreCode(entity.value)
            MessageEntityKind.BLOCK_QUOTE -> TdApi.TextEntityTypeBlockQuote()
            MessageEntityKind.EXPANDABLE_BLOCK_QUOTE -> TdApi.TextEntityTypeExpandableBlockQuote()
            MessageEntityKind.URL -> TdApi.TextEntityTypeUrl()
            MessageEntityKind.TEXT_URL -> {
                require(safeExternalLink(entity.value) != null)
                TdApi.TextEntityTypeTextUrl(checkNotNull(safeExternalLink(entity.value)))
            }
            MessageEntityKind.EMAIL -> TdApi.TextEntityTypeEmailAddress()
            MessageEntityKind.PHONE -> TdApi.TextEntityTypePhoneNumber()
            MessageEntityKind.MENTION -> TdApi.TextEntityTypeMention()
            MessageEntityKind.MENTION_NAME -> TdApi.TextEntityTypeMentionName(entity.id)
            MessageEntityKind.HASHTAG -> TdApi.TextEntityTypeHashtag()
            MessageEntityKind.CASHTAG -> TdApi.TextEntityTypeCashtag()
            MessageEntityKind.CUSTOM_EMOJI -> TdApi.TextEntityTypeCustomEmoji(entity.id)
        }
        TdApi.TextEntity(entity.offset, entity.length, type)
    }.toTypedArray())
}

/** Read the original native entities so quotes also retain types not rendered by this client. */
internal fun nativeQuote(source: TdApi.FormattedText, quote: MessageQuote): TdApi.InputTextQuote? {
    if (!quote.matches(source.text.orEmpty())) return null
    val start = quote.position
    val end = start + quote.content.text.length
    val entities = source.entities.orEmpty().mapNotNull { entity ->
        entity ?: return@mapNotNull null
        when (entity.type) {
            is TdApi.TextEntityTypeBold, is TdApi.TextEntityTypeItalic, is TdApi.TextEntityTypeUnderline,
            is TdApi.TextEntityTypeStrikethrough, is TdApi.TextEntityTypeSpoiler,
            is TdApi.TextEntityTypeCustomEmoji, is TdApi.TextEntityTypeDateTime -> Unit
            else -> return@mapNotNull null
        }
        val left = maxOf(start, entity.offset)
        val right = minOf(end.toLong(), entity.offset.toLong() + entity.length).toInt()
        if (right <= left) null else TdApi.TextEntity(left - start, right - left, entity.type)
    }
    return TdApi.InputTextQuote(TdApi.FormattedText(quote.content.text, entities.toTypedArray()), emptyArray(), start)
}
