package com.badnng.moe.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

actual suspend fun stageClipboardImage(png: ByteArray): StagedClipboardImage {
    var staged: OwnedClipboardImage? = null
    try {
        return withContext(Dispatchers.IO) {
            require(png.size in 8..64 * 1024 * 1024) { "Unsupported clipboard image" }
            val dimensions = Data.makeFromBytes(png).use { data ->
                Codec.makeFromData(data).use { codec ->
                    require(codec.encodedImageFormat == EncodedImageFormat.PNG && codec.frameCount <= 1 &&
                        codec.width > 0 && codec.height > 0 &&
                        codec.width.toLong() * codec.height <= 24_000_000L) { "Unsupported clipboard image" }
                    codec.width to codec.height
                }
            }
            // Force a bounded decode before creating a sendable file; header-only PNGs
            // and corrupt compressed data must not become attachments.
            Image.makeFromEncoded(png).use { image ->
                Bitmap.makeFromImage(image).use { decoded ->
                    require(decoded.width == dimensions.first && decoded.height == dimensions.second) { "Invalid clipboard image" }
                }
            }
            val target = Files.createTempFile("nnggram-clipboard-", ".png")
            try {
                Files.write(target, png)
                OwnedClipboardImage(target, dimensions.first, dimensions.second).also { staged = it }
            } catch (error: Throwable) {
                Files.deleteIfExists(target)
                throw error
            }
        }
    } catch (error: Throwable) {
        // withContext can discard a completed result when its caller is cancelled.
        staged?.close()
        throw error
    }
}

private class OwnedClipboardImage(
    private val file: Path,
    override val width: Int,
    override val height: Int,
) : StagedClipboardImage {
    override val path: String = file.toString()
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        Files.deleteIfExists(file)
        closed = true
    }

    override fun toString() = "StagedClipboardImage(<redacted>)"
}

actual suspend fun clipboardImagePng(path: String): ByteArray = withContext(Dispatchers.IO) {
    val source = Path.of(path)
    require(Files.isRegularFile(source) && Files.size(source) in 1..64L * 1024 * 1024) { "Unsupported clipboard image" }
    val bytes = Files.readAllBytes(source)
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            require(codec.frameCount <= 1 && codec.width > 0 && codec.height > 0 &&
                codec.width.toLong() * codec.height <= 24_000_000L) { "Unsupported clipboard image" }
        }
    }
    Image.makeFromEncoded(bytes).use { image ->
        checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { encoded ->
            require(encoded.size <= 64 * 1024 * 1024) { "Clipboard image is too large" }
            encoded.bytes
        }
    }
}
