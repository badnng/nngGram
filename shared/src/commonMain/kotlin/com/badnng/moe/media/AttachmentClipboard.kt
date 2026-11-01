package com.badnng.moe.media

import androidx.compose.runtime.staticCompositionLocalOf

/** Transient paste input. Neither clipboard bytes nor local paths enter diagnostics. */
sealed interface ClipboardAttachmentContent {
    data class FileList(val paths: List<String>) : ClipboardAttachmentContent {
        override fun toString() = "ClipboardAttachmentContent.FileList(<redacted>)"
    }

    data class PngImage(val bytes: ByteArray) : ClipboardAttachmentContent {
        override fun toString() = "ClipboardAttachmentContent.PngImage(<redacted>)"
    }
}

/** The Tao host supplies the native clipboard; a missing provider leaves text paste alone. */
interface AttachmentClipboard {
    fun hasAttachments(): Boolean
    suspend fun readAttachments(): ClipboardAttachmentContent?
    suspend fun writeFiles(paths: List<String>)
    suspend fun writePng(bytes: ByteArray)
}

val LocalAttachmentClipboard = staticCompositionLocalOf<AttachmentClipboard?> { null }

/** Owns only the generated screenshot file, never a file copied from Explorer. */
interface StagedClipboardImage {
    val path: String
    val width: Int
    val height: Int
    fun close()
}

/** Inspect and stage on an IO dispatcher; callers release cancelled or stale results. */
expect suspend fun stageClipboardImage(png: ByteArray): StagedClipboardImage

/** Read one explicitly selected, permitted local image for native PNG clipboard output. */
expect suspend fun clipboardImagePng(path: String): ByteArray
