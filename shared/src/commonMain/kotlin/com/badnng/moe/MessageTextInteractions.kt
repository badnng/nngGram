package com.badnng.moe

import androidx.compose.ui.text.AnnotatedString
import com.badnng.moe.chat.*

/** Captures the source before a focusable popup changes the live text selection. */
internal data class MessageTextCopyRequest(
    val token: ChatToken,
    val chatId: Long,
    val messageId: Long,
    val source: RichMessageText,
    val selectionOffset: Int? = null,
    val selection: RichMessageText? = null,
    val hadSelection: Boolean = selection != null,
) {
    override fun toString() = "MessageTextCopyRequest(redacted)"
}

internal fun messageTextCopyRequest(
    token: ChatToken,
    message: ChatMessage,
    selectedTexts: List<AnnotatedString>,
): MessageTextCopyRequest {
    val source = message.richText()
    val selection = source.sourceTextSelection(selectedTexts)
    return MessageTextCopyRequest(token, message.chatId, message.id, source, selection?.first, selection?.second,
        selectedTexts.any { it.isNotEmpty() })
}

internal fun validatedMessageTextCopy(
    snapshot: ChatSnapshot,
    request: MessageTextCopyRequest,
    pageInteractive: Boolean,
): RichMessageText? {
    val message = currentMessageTextSource(snapshot, request.token, request.chatId, request.messageId,
        request.source, pageInteractive) ?: return null
    if (snapshot.selected?.hasProtectedContent != false || !message.canBeSaved) return null
    val selection = request.selection ?: return request.source.takeUnless { request.hadSelection }
    val start = request.selectionOffset ?: return null
    return selection.takeIf { request.source.slice(start, start + it.text.length) == it }
}

internal data class MessageTextLinkRequest(
    val token: ChatToken,
    val chatId: Long,
    val messageId: Long,
    val source: RichMessageText,
    val entity: MessageEntity,
) {
    override fun toString() = "MessageTextLinkRequest(redacted)"
}

/** Paragraph/quote rendering may clip a text-URL label, but must retain its native target. */
internal fun RichMessageText.originalLinkEntity(rendered: MessageEntity): MessageEntity? =
    validEntities().filter { entity ->
        entity.kind == rendered.kind && entity.value == rendered.value && entity.id == rendered.id &&
            entity.offset <= rendered.offset && entity.end >= rendered.end
    }.minByOrNull { it.length }

internal fun validatedMessageTextLink(
    snapshot: ChatSnapshot,
    request: MessageTextLinkRequest,
    pageInteractive: Boolean,
): String? {
    currentMessageTextSource(snapshot, request.token, request.chatId, request.messageId,
        request.source, pageInteractive) ?: return null
    if (request.entity !in request.source.validEntities()) return null
    return request.entity.link(request.source.text)
}

private fun currentMessageTextSource(
    snapshot: ChatSnapshot,
    token: ChatToken,
    chatId: Long,
    messageId: Long,
    source: RichMessageText,
    pageInteractive: Boolean,
): ChatMessage? {
    if (!pageInteractive || !snapshot.enabled || snapshot.token != token || token.chatId != chatId ||
        snapshot.selected?.id != chatId) return null
    return snapshot.messages.firstOrNull { it.chatId == chatId && it.id == messageId && it.richText() == source }
}

// tdesktop a7f453, core/click_handler_types.cpp DefaultWhitelistedDomains and
// HiddenUrlRequiresConfirmation. Plain URLs enter that handler only when lib_ui
// UrlClickHandler::IsSuspicious reports a mixed/foreign-script hostname.
internal val OFFICIAL_DEFAULT_LINK_DOMAINS = setOf(
    "telegram.dog", "telegram.me", "telegram.org", "t.me", "telegra.ph", "te.legra.ph",
    "graph.org", "telesco.pe", "fragment.com",
)

internal fun messageLinkRequiresConfirmation(
    source: RichMessageText,
    entity: MessageEntity,
    url: String,
    controlPressed: Boolean = false,
    whitelistedDomains: Set<String> = OFFICIAL_DEFAULT_LINK_DOMAINS,
): Boolean {
    if (controlPressed) return false
    val hidden = entity.kind == MessageEntityKind.TEXT_URL &&
        entity.value != source.text.substring(entity.offset, entity.end)
    val suspicious = (entity.kind == MessageEntityKind.URL || entity.kind == MessageEntityKind.TEXT_URL) &&
        messageUrlIsSuspicious(url)
    if (!hidden && !suspicious) return false
    val host = messageUrlHost(url)?.lowercase().orEmpty()
    fun inDomain(domain: String): Boolean = domain.isNotEmpty() &&
        (host == domain.lowercase() || host.endsWith(".${domain.lowercase()}"))
    // This client has no Instant View fallback. Official HiddenUrlClickHandler
    // therefore confirms these two hosts before opening their external page.
    return host in setOf("telegra.ph", "te.legra.ph") || whitelistedDomains.none(::inDomain) ||
        setOf("telegram.me", "telegram.dog", "t.me").any(::inDomain)
}

internal expect fun messageUrlIsSuspicious(url: String): Boolean
internal expect fun messageUrlHost(url: String): String?
