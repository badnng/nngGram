package com.badnng.moe

import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.media.ClipboardAttachmentContent
import com.badnng.moe.media.StagedClipboardImage
import com.badnng.moe.media.stageClipboardImage

internal data class PreparedClipboardItem(val attachment: LocalAttachment, val asPhoto: Boolean, val lease: AttachmentLease? = null) {
    override fun toString() = "PreparedClipboardItem(<redacted>)"
}

internal class PreparedClipboardBatch(val items: List<PreparedClipboardItem>) : AutoCloseable {
    override fun close() { items.forEach { it.lease?.release() } }
    fun retainForDisplay() = PreparedClipboardBatch(items.map { it.copy(lease = it.lease?.retain()) })
    override fun toString() = "PreparedClipboardBatch(<redacted>)"
}

internal expect suspend fun inspectClipboardFiles(paths: List<String>): List<PreparedClipboardItem>

/** A late/cancelled staged screenshot has exactly one UI lease until native sending retains it. */
internal suspend fun prepareClipboardBatch(
    content: ClipboardAttachmentContent,
    inspect: suspend (List<String>) -> List<PreparedClipboardItem> = ::inspectClipboardFiles,
    stage: suspend (ByteArray) -> StagedClipboardImage = ::stageClipboardImage,
): PreparedClipboardBatch = when (content) {
    is ClipboardAttachmentContent.FileList -> {
        require(content.paths.size in 1..20)
        PreparedClipboardBatch(inspect(content.paths).also { require(it.isNotEmpty()) })
    }
    is ClipboardAttachmentContent.PngImage -> {
        val staged = stage(content.bytes)
        try {
            val item = inspect(listOf(staged.path)).single()
            require(item.asPhoto)
            PreparedClipboardBatch(listOf(item.copy(attachment = item.attachment.copy(fileName = "截图.png"),
                lease = ownedAttachmentLease(staged::close))))
        } catch (error: Throwable) {
            staged.close()
            throw error
        }
    }
}
