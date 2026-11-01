package com.badnng.moe.media

import com.badnng.moe.chat.AttachmentKind
import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.LocalAttachmentThumbnail
import com.badnng.moe.chat.ownedAttachmentLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.max
import kotlin.math.roundToInt

private val attachmentPreparationSlots = Semaphore(2)

/** Own delivery before Compose applies the result, including close racing with a late worker. */
internal class AttachmentSelectionOwner : AutoCloseable {
    private var selection: LocalAttachment? = null
    private var closed = false

    @Synchronized
    fun replace(value: LocalAttachment?): Boolean {
        if (closed) { value?.lease?.release(); return false }
        val previous = selection
        selection = value
        if (previous !== value) previous?.lease?.release()
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val previous = selection
        selection = null
        previous?.lease?.release()
    }
}

/** Cancellation can discard an IO result before its lease reaches the confirmation dialog. */
internal suspend fun prepareAttachmentSelection(path: String, kind: AttachmentKind,
    inspect: (String, AttachmentKind) -> LocalAttachment = AttachmentFiles::inspect): LocalAttachment {
    var prepared: LocalAttachment? = null
    try {
        return attachmentPreparationSlots.withPermit {
            runInterruptible(Dispatchers.IO) { inspect(path, kind).also { prepared = it } }
        }
    } catch (error: Throwable) {
        prepared?.lease?.release()
        throw error
    }
}

/** One bounded frame; no playback, audio device, transcode or download is started. */
internal fun prepareVideoThumbnail(file: Path, info: AttachmentFiles.VideoInfo): Pair<LocalAttachmentThumbnail, AttachmentLease> {
    val executable = MediaDecoder.findFfmpeg() ?: throw AttachmentFailure(AttachmentFailure.Reason.VIDEO_TOOLS_MISSING)
    val target = Files.createTempFile("nnggram-video-thumbnail-", ".jpg")
    val lease = ownedAttachmentLease { runCatching { Files.deleteIfExists(target) } }
    try {
        val scale = minOf(1.0, 320.0 / max(info.displayWidth, info.displayHeight))
        val width = max(1, (info.displayWidth * scale).roundToInt())
        val height = max(1, (info.displayHeight * scale).roundToInt())
        MediaTools.capture(executable, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,pipe",
            "-threads", "1", "-i", file.toString(), "-map", "0:${info.streamIndex}", "-frames:v", "1", "-an", "-sn", "-dn",
            "-vf", "scale=$width:$height,setsar=1", "-threads", "1", "-c:v", "mjpeg", "-q:v", "3", "-y", target.toString()))
        val size = Files.size(target)
        require(size in 1 until 200_000L)
        Data.makeFromBytes(Files.readAllBytes(target)).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.encodedImageFormat == EncodedImageFormat.JPEG && codec.frameCount == 1 &&
                    codec.width == width && codec.height == height)
            }
        }
        return LocalAttachmentThumbnail(target.toString(), width, height, size, AttachmentFiles.identity(target)) to lease
    } catch (error: Throwable) {
        lease.release()
        if (error is AttachmentFailure) throw error
        throw AttachmentFailure(AttachmentFailure.Reason.INVALID_VIDEO)
    }
}

internal data class Mp4Layout(val isMp4: Boolean, val supportsStreaming: Boolean)

internal fun isFastStartMp4(path: Path): Boolean = inspectMp4Layout(path).supportsStreaming

/** Container identity and early-playback layout are independent; a trailing moov is still MP4. */
internal fun inspectMp4Layout(path: Path): Mp4Layout = runCatching {
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        val invalid = Mp4Layout(false, false)
        val length = channel.size()
        val header = ByteBuffer.allocate(16)
        var offset = 0L
        var mp4 = false
        var moov = false
        repeat(4096) {
            if (offset > length - 8) return@use invalid
            header.clear().limit(8)
            while (header.hasRemaining()) if (channel.read(header, offset + header.position()) <= 0) return@use invalid
            header.flip()
            val size32 = header.int.toLong() and 0xffff_ffffL
            val type = header.int
            val headerSize = if (size32 == 1L) 16L else 8L
            val size = when (size32) {
                0L -> length - offset
                1L -> {
                    if (offset > length - 16) return@use invalid
                    header.clear().limit(8)
                    while (header.hasRemaining()) if (channel.read(header, offset + 8 + header.position()) <= 0) return@use invalid
                    header.flip()
                    header.long
                }
                else -> size32
            }
            if (size < headerSize || size > length - offset) return@use invalid
            when (type) {
                0x66747970 -> { // ftyp: major brand, minor version, compatible brands.
                    val payload = size - headerSize
                    if (payload < 8 || payload > 4096 || payload % 4 != 0L) return@use invalid
                    val brands = ByteBuffer.allocate(payload.toInt())
                    while (brands.hasRemaining()) if (channel.read(brands, offset + headerSize + brands.position()) <= 0) return@use invalid
                    brands.flip()
                    var index = 0
                    var anotherContainer = false
                    while (brands.hasRemaining()) {
                        val brand = brands.int
                        if (index == 0) anotherContainer = brand == 0x71742020 || (brand ushr 8) in setOf(0x336770, 0x336732)
                        if (index++ != 1 && (brand == 0x69736f6d || brand in 0x69736f32..0x69736f39 ||
                                brand == 0x6d703431 || brand == 0x6d703432 || brand == 0x61766331 || brand == 0x4d345620)) mp4 = true
                    }
                    if (anotherContainer) mp4 = false
                }
                0x6d6f6f76 -> moov = size > headerSize
                0x6d646174 -> return@use Mp4Layout(mp4, mp4 && moov && size > headerSize)
            }
            offset += size
        }
        invalid
    }
}.getOrDefault(Mp4Layout(false, false))
