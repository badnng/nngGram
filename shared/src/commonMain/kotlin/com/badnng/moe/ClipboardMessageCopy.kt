package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.media.AttachmentClipboard
import com.badnng.moe.media.clipboardImagePng

internal data class ClipboardMessageSource(val token: ChatToken, val messageId: Long, val fileId: Int, val path: String, val isPhoto: Boolean) {
    override fun toString() = "ClipboardMessageSource(<redacted>)"
}

internal fun clipboardMessageSource(snapshot: ChatSnapshot, messageId: Long): ClipboardMessageSource? {
    val chat = snapshot.selected ?: return null
    if (!snapshot.enabled || chat.id != snapshot.token.chatId || chat.hasProtectedContent) return null
    val message = snapshot.messages.firstOrNull { it.id == messageId && it.chatId == snapshot.token.chatId } ?: return null
    if (!message.canBeSaved) return null
    if (message.kind.isTextContent) return null
    val media = message.media ?: return null
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val path = file.localPath?.takeIf { file.downloaded && file.id > 0 && it.isNotBlank() } ?: return null
    return ClipboardMessageSource(snapshot.token, messageId, file.id, path, media.format == MediaFormat.PHOTO)
}

/** Recheck permissions/source after conversion, before writing the user's clipboard. */
internal suspend fun copyMessageAttachment(
    messageId: Long, asImage: Boolean, clipboard: AttachmentClipboard,
    current: () -> ChatSnapshot, active: () -> Boolean = { true },
    imagePng: suspend (String) -> ByteArray = ::clipboardImagePng,
): Boolean {
    val source = clipboardMessageSource(current(), messageId) ?: return false
    if (!active() || asImage && !source.isPhoto) return false
    val image = if (asImage) imagePng(source.path) else null
    if (!active() || clipboardMessageSource(current(), messageId) != source) return false
    if (image != null) clipboard.writePng(image) else clipboard.writeFiles(listOf(source.path))
    return true
}
