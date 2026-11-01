package com.badnng.moe.chat

/** A native inline keyboard, kept separate from message text and composer reply keyboards. */
data class MessageInlineKeyboard(val rows: List<List<MessageInlineButton>>) {
    override fun toString() = "MessageInlineKeyboard(redacted)"
}

enum class MessageInlineButtonKind { URL, UNSUPPORTED }

/** Unsupported native actions keep their cell and label, without an executable payload. */
data class MessageInlineButton(
    val text: String,
    val kind: MessageInlineButtonKind = MessageInlineButtonKind.UNSUPPORTED,
    val url: String? = null,
) {
    override fun toString() = "MessageInlineButton(redacted, kind=$kind)"
}

/** The rendered keyboard is captured so edits cannot silently retarget an outstanding click. */
data class MessageInlineButtonClick(
    val chatId: Long,
    val messageId: Long,
    val row: Int,
    val column: Int,
    val keyboard: MessageInlineKeyboard,
) {
    override fun toString() = "MessageInlineButtonClick(redacted)"
}

/** URL buttons open only network targets; native login, web-app, callback and payment actions stay inert. */
fun MessageInlineButton.externalUrl(): String? {
    if (kind != MessageInlineButtonKind.URL) return null
    val target = url ?: return null
    if (!target.startsWith("https://", ignoreCase = true) && !target.startsWith("http://", ignoreCase = true)) return null
    return safeExternalLink(target)
}

/** Resolve against current selection and current native row/column immediately before explicit opening. */
fun inlineMessageButtonUrl(
    snapshot: ChatSnapshot,
    token: ChatToken,
    click: MessageInlineButtonClick,
    pageInteractive: Boolean,
): String? {
    if (!pageInteractive || !snapshot.enabled || snapshot.token != token || token.chatId != click.chatId ||
        snapshot.selected?.id != click.chatId || click.messageId <= 0) return null
    val message = snapshot.messages.firstOrNull { it.chatId == click.chatId && it.id == click.messageId } ?: return null
    if (message.delivery != MessageDelivery.SENT || message.inlineKeyboard != click.keyboard) return null
    return message.inlineKeyboard?.rows?.getOrNull(click.row)?.getOrNull(click.column)?.externalUrl()
}
