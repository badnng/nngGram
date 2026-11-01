package com.badnng.moe.chat

/** Reaction content is transient account data, like message text. */
data class MessageReaction(
    val emoji: String = "",
    val totalCount: Int = 0,
    val isChosen: Boolean = false,
    val customEmojiId: Long = 0,
    val isPaid: Boolean = false,
    val recentSenders: List<MessageReactionSender> = emptyList(),
) {
    val canToggle: Boolean get() = !isPaid && (emoji.isNotBlank() || customEmojiId != 0L)
    val key: ReactionKey get() = ReactionKey(emoji, customEmojiId)
    override fun toString() = "MessageReaction(<redacted>)"
}

/** Native user and chat sender identities stay separate. Missing avatars are never synthesized. */
data class MessageReactionSender(val id: Long, val senderIsChat: Boolean = false) {
    override fun toString() = "MessageReactionSender(<redacted>)"
}

data class MessageReactionOptions(
    val emojis: List<String> = emptyList(),
    val canAdd: Boolean = false,
    val choices: List<ReactionChoice> = emptyList(),
    val allowCustomEmoji: Boolean = false,
) {
    override fun toString() = "MessageReactionOptions(<redacted>)"
}

/** Identity is typed: a custom emoji identifier is never encoded into user-visible text. */
data class ReactionKey(val emoji: String = "", val customEmojiId: Long = 0) {
    val valid: Boolean get() = if (customEmojiId != 0L) emoji.isEmpty() else emoji.isNotBlank() && emoji.length <= 128
    override fun toString() = "ReactionKey(<redacted>)"
}

enum class ReactionSource { TOP, RECENT, POPULAR, PACK }

data class ReactionChoice(val key: ReactionKey, val needsPremium: Boolean = false, val source: ReactionSource = ReactionSource.TOP) {
    override fun toString() = "ReactionChoice(<redacted>)"
}

/** A selected, joined source group supplies artwork; the target message still controls sending. */
data class ReactionGroupPack(val sourceChatId: Long, val pack: StickerPack, val choices: List<ReactionChoice>) {
    override fun toString() = "ReactionGroupPack(<redacted>)"
}

/** All artwork comes from TDLib's official reaction/sticker responses. */
data class ReactionVisual(val key: ReactionKey, val staticIcon: MessageMedia? = null,
    val selectAnimation: MessageMedia? = null, val appearAnimation: MessageMedia? = null,
    val centerAnimation: MessageMedia? = null, val aroundAnimation: MessageMedia? = null) {
    override fun toString() = "ReactionVisual(<redacted>)"
}

internal fun MessageReactionOptions.allChoices(): List<ReactionChoice> =
    if (choices.isNotEmpty()) choices else emojis.map { ReactionChoice(ReactionKey(it)) }
