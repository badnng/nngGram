package com.badnng.moe.chat

internal enum class ReactionEmojiCategory(val label: String, val searchName: String) {
    ALL("全部", "all"), FACES("表情", "face smile emotion"), GESTURES("手势", "hand gesture"),
    ANIMALS("动物", "animal nature"), FOOD("食物", "food drink"), ACTIVITIES("活动", "activity sport"),
    TRAVEL("旅行", "travel place"), OBJECTS("物品", "object"), SYMBOLS("符号", "symbol heart"), OTHER("其他", "other"),
}

/** Categories describe only the native allowed list; this metadata never adds a reaction. */
internal fun reactionEmojiCategory(emoji: String): ReactionEmojiCategory {
    val first = emoji.firstOrNull() ?: return ReactionEmojiCategory.OTHER
    val second = emoji.getOrNull(1)?.code ?: 0
    val code = if (first.code in 0xD800..0xDBFF && second in 0xDC00..0xDFFF)
        0x10000 + ((first.code - 0xD800) shl 10) + (emoji[1].code - 0xDC00) else first.code
    return when {
        code in 0x1F44A..0x1F450 || code in 0x1F590..0x1F596 || code in 0x1F64C..0x1F64F ||
            code in 0x1F90C..0x1F90F || code in 0x1F918..0x1F91F || code == 0x1F932 ||
            code in 0x1FAF0..0x1FAFF || code == 0x270A || code == 0x270B || code == 0x270C -> ReactionEmojiCategory.GESTURES
        code in 0x1F600..0x1F64F || code in 0x1F910..0x1F92F || code in 0x1F970..0x1F97F ||
            code in 0x1FAE0..0x1FAEF || code == 0x1F4A9 || code == 0x1F480 || code == 0x1F47B -> ReactionEmojiCategory.FACES
        code in 0x1F32D..0x1F32F || code in 0x1F34E..0x1F37F || code in 0x1F950..0x1F96F || code in 0x1F9C0..0x1F9CF -> ReactionEmojiCategory.FOOD
        code in 0x1F400..0x1F43F || code in 0x1F980..0x1F9AF || code in 0x1F330..0x1F343 -> ReactionEmojiCategory.ANIMALS
        code in 0x1F3A0..0x1F3DF || code in 0x1F940..0x1F94F -> ReactionEmojiCategory.ACTIVITIES
        code in 0x1F680..0x1F6FF || code in 0x1F300..0x1F32C || code in 0x1F3E0..0x1F3FF -> ReactionEmojiCategory.TRAVEL
        code in 0x1F4A0..0x1F5FF || code in 0x1F9F0..0x1FA7F -> ReactionEmojiCategory.OBJECTS
        code in 0x2000..0x2BFF || code in 0x1F1E6..0x1F1FF || code in 0x1F7E0..0x1F7EF -> ReactionEmojiCategory.SYMBOLS
        else -> ReactionEmojiCategory.OTHER
    }
}

internal fun filterReactionEmojis(
    available: List<String>,
    chosen: Set<String> = emptySet(),
    query: String = "",
    category: ReactionEmojiCategory = ReactionEmojiCategory.ALL,
): List<String> {
    val needle = query.trim()
    return (available + chosen).filter(String::isNotBlank).distinct().filter { emoji ->
        val group = reactionEmojiCategory(emoji)
        (category == ReactionEmojiCategory.ALL || group == category) && (needle.isEmpty() ||
            emoji.contains(needle) || group.label.contains(needle, ignoreCase = true) ||
            group.searchName.contains(needle, ignoreCase = true) ||
            reactionEmojiAliases[emoji.replace("\uFE0F", "")]?.contains(needle, ignoreCase = true) == true)
    }
}

private val reactionEmojiAliases = mapOf(
    "❤" to "爱 心 红心 love heart", "👍" to "赞 好 支持 thumbs up like", "👎" to "踩 不赞 thumbs down dislike",
    "🔥" to "火 热 fire hot", "🥰" to "喜欢 爱 心 love smiling", "😍" to "喜欢 爱 眼 love heart eyes",
    "😂" to "笑 哭 大笑 laugh joy tears", "🤣" to "笑 大笑 laugh rolling", "😁" to "笑 开心 grin smile",
    "😀" to "笑 开心 grin smile", "😊" to "笑 开心 smile", "😅" to "汗 笑 sweat smile", "🙂" to "微笑 smile",
    "🤔" to "思考 想 thinking", "😢" to "哭 伤心 cry sad", "😭" to "哭 伤心 sob cry", "😱" to "惊讶 害怕 scream",
    "😡" to "生气 愤怒 angry rage", "🤯" to "爆炸 惊讶 exploding mind", "🤩" to "星星 惊喜 star eyes",
    "🤡" to "小丑 clown", "💩" to "便便 poop", "👻" to "鬼 ghost", "💀" to "骷髅 skull",
    "👏" to "鼓掌 clap applause", "🙏" to "谢谢 祈祷 thanks pray", "👌" to "好 ok", "✌" to "胜利 耶 victory peace",
    "🤝" to "握手 合作 handshake", "🤗" to "抱抱 hug", "😘" to "亲亲 kiss", "🥳" to "庆祝 party",
    "🎉" to "庆祝 彩带 party celebration", "🏆" to "奖杯 胜利 trophy", "⭐" to "星星 star", "💯" to "满分 一百 hundred",
    "🍾" to "香槟 庆祝 champagne", "🍌" to "香蕉 banana", "🌚" to "月亮 moon", "⚡" to "闪电 lightning",
)
