package com.badnng.moe

import com.badnng.moe.chat.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** Owned synthetic PNGs only. No configuration, account directories or remote resources are read. */
internal class DesktopPreviewMediaAssets private constructor(
    private val directory: Path,
    val photos: List<MediaFile>,
    val videoThumbnails: List<MediaFile>,
    private val paths: List<Path>,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val shutdownCleanup = Thread({ close() }, "nnggram-offline-preview-cleanup")

    init { Runtime.getRuntime().addShutdownHook(shutdownCleanup) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (Thread.currentThread() !== shutdownCleanup) runCatching { Runtime.getRuntime().removeShutdownHook(shutdownCleanup) }
        paths.forEach { runCatching { Files.deleteIfExists(it) } }
        runCatching { Files.deleteIfExists(directory) }
    }

    companion object {
        internal fun create(writeImage: (Path, Int) -> Unit = ::writeSyntheticPreview): DesktopPreviewMediaAssets {
            val directory = Files.createTempDirectory("nnggram-offline-preview-")
            val created = mutableListOf<Path>()
            try {
                val files = (0 until 9).map { index ->
                    val path = directory.resolve("synthetic-$index.png")
                    created.add(path)
                    writeImage(path, index)
                    val size = Files.size(path)
                    MediaFile(200_000 + index, size = size, localPath = path.toAbsolutePath().toString(),
                        downloaded = true, downloadedSize = size)
                }
                return DesktopPreviewMediaAssets(directory, files.take(6), files.drop(6), created.toList())
            } catch (failure: Exception) {
                created.forEach { runCatching { Files.deleteIfExists(it) } }
                runCatching { Files.deleteIfExists(directory) }
                throw IllegalStateException("Synthetic preview image generation failed.", failure)
            }
        }

        private fun writeSyntheticPreview(path: Path, index: Int) {
            val palettes = listOf(
                intArrayOf(0xFFE0EAF8.toInt(), 0xFF758FB5.toInt(), 0xFF3C6384.toInt(), 0xFFFFD69B.toInt()),
                intArrayOf(0xFFF4E4D6.toInt(), 0xFFD5AA88.toInt(), 0xFF8C6A5C.toInt(), 0xFFFFEBC4.toInt()),
                intArrayOf(0xFFDDECE4.toInt(), 0xFF89B3A5.toInt(), 0xFF49796C.toInt(), 0xFFF7E7BC.toInt()),
            )
            val palette = palettes[index % palettes.size]
            Surface.makeRasterN32Premul(720, 480).use { surface ->
                val canvas = surface.canvas
                canvas.clear(palette[0])
                Paint().use { paint ->
                    paint.isAntiAlias = true
                    paint.color = palette[3]
                    canvas.drawCircle(520f - index % 3 * 92f, 118f, 60f, paint)
                    paint.color = palette[1]
                    canvas.drawCircle(110f + index % 3 * 60f, 480f, 280f, paint)
                    canvas.drawCircle(620f, 440f, 240f, paint)
                    paint.color = palette[2]
                    canvas.drawCircle(370f, 620f - index % 2 * 30f, 330f, paint)
                    paint.color = 0xCCFFFFFF.toInt()
                    canvas.drawRect(Rect.makeXYWH(42f, 40f, 78f, 8f), paint)
                    canvas.drawRect(Rect.makeXYWH(42f, 58f, 46f, 5f), paint)
                    paint.color = 0x99FFFFFF.toInt()
                    repeat(4) { line -> canvas.drawRect(Rect.makeXYWH(510f, 360f + line * 16f, 150f - line * 18f, 3f), paint) }
                }
                surface.makeImageSnapshot().use { image ->
                    checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        Files.write(path, data.bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                }
            }
        }
    }
}

/** In-memory stand-in for native indexed pages, explicitly restricted to synthetic fixture sources. */
internal class DesktopPreviewMediaIndex(
    private val catalog: Map<Long, Map<ChatMediaCategory, List<ChatMessage>>>,
    private val pageSize: Int = 6,
) {
    private var selection: ChatToken? = null
    private val pages = mutableMapOf<ChatMediaCategory, ChatMediaIndexState>()

    fun load(snapshot: ChatSnapshot, token: ChatToken, category: ChatMediaCategory, more: Boolean): ChatSnapshot {
        val chatId = token.chatId ?: return snapshot
        if (!snapshot.enabled || token != snapshot.token || snapshot.selected?.id != chatId) return snapshot
        if (selection != token) { selection = token; pages.clear() }
        val known = catalog[chatId].orEmpty()
        val matching = known[category].orEmpty()
        val current = pages[category]
        if (more && (snapshot.mediaIndex.category != category || current?.loaded != true || current.nextOffset.isEmpty())) return snapshot
        val offset = if (more) current?.nextOffset?.toIntOrNull()?.takeIf { it in 0 until matching.size } ?: return snapshot else 0
        val page = if (!more && current?.loaded == true) current else {
            val end = (offset + pageSize).coerceAtMost(matching.size)
            ChatMediaIndexState(chatId = chatId, category = category,
                messages = ((if (more) current?.messages.orEmpty() else emptyList()) + matching.subList(offset, end)).distinctBy { it.id },
                totalCount = matching.size, nextOffset = if (end < matching.size) end.toString() else "",
                loaded = true, revision = snapshot.mediaIndex.revision + 1,
                counts = ChatMediaCategory.entries.associateWith { known[it].orEmpty().size })
        }
        pages[category] = page
        return snapshot.copy(mediaIndex = page, mediaFiles = snapshot.mediaFiles + files(page.messages))
    }

    fun open(snapshot: ChatSnapshot, token: ChatToken, messageId: Long): ChatSnapshot {
        val chatId = token.chatId ?: return snapshot
        if (!snapshot.enabled || token != snapshot.token || snapshot.selected?.id != chatId || snapshot.mediaIndex.chatId != chatId ||
            snapshot.mediaIndex.messages.none { it.id == messageId && it.chatId == chatId }) return snapshot
        val history = catalog[chatId].orEmpty().values.flatten().distinctBy { it.id }
        if (history.none { it.id == messageId && it.chatId == chatId }) return snapshot
        return snapshot.copy(messages = (snapshot.messages + history).distinctBy { it.id }.sortedBy { it.id },
            jumpMessageId = messageId, jumpRevision = snapshot.jumpRevision + 1,
            historyLoading = false, historyError = null, historyEnd = true,
            historyHasNewer = false, newerLoading = false, newerError = null,
            mediaFiles = snapshot.mediaFiles + files(history))
    }

    private fun files(messages: List<ChatMessage>): Map<Int, MediaFile> = messages.flatMap {
        listOfNotNull(it.media?.file, it.media?.thumbnail)
    }.filter { it.id > 0 }.associateBy { it.id }
}

internal fun desktopPreviewMediaCatalog(
    chatId: Long,
    date: Int,
    assets: DesktopPreviewMediaAssets,
    existingFiles: List<ChatMessage>,
): Map<ChatMediaCategory, List<ChatMessage>> {
    val photos = assets.photos.mapIndexed { index, file ->
        ChatMessage(500L + index, chatId, senderId = 900002, date = date - (12 - index) * 3600,
            text = "离线合成图片 ${index + 1}", kind = MessageKind.PHOTO,
            media = MessageMedia(file, width = 720, height = 480, format = MediaFormat.PHOTO))
    }
    val videos = assets.videoThumbnails.mapIndexed { index, thumbnail ->
        ChatMessage(510L + index, chatId, senderId = 900003, date = date - (5 - index) * 3600,
            text = "离线合成视频缩略图（不提供播放）", kind = MessageKind.VIDEO,
            media = MessageMedia(MediaFile(210_000 + index), thumbnail = thumbnail, width = 720, height = 480,
                format = MediaFormat.VIDEO, thumbnailFormat = MediaFormat.PHOTO, durationSeconds = 18 + index * 7,
                fileName = "离线视频示例-${index + 1}.mp4", mimeType = "video/mp4"))
    }
    return mapOf(
        ChatMediaCategory.PHOTOS_AND_VIDEOS to (photos + videos).sortedByDescending { it.id },
        ChatMediaCategory.FILES to existingFiles + ChatMessage(520, chatId, senderId = 900003, date = date - 86_400,
            text = "离线文件记录", kind = MessageKind.FILE,
            media = MessageMedia(MediaFile(220_000), format = MediaFormat.FILE, fileName = "离线布局笔记.txt", mimeType = "text/plain")),
        ChatMediaCategory.LINKS to listOf(ChatMessage(521, chatId, senderId = 900002, date = date - 86_400,
            text = "离线链接示例：https://example.invalid/nnggram-preview")),
        ChatMediaCategory.AUDIO to listOf(ChatMessage(522, chatId, senderId = 900002, date = date - 86_400,
            text = "离线音乐记录（不提供播放）", kind = MessageKind.FILE,
            media = MessageMedia(MediaFile(220_001), format = MediaFormat.FILE, fileName = "离线音乐示例.mp3", mimeType = "audio/mpeg"))),
        ChatMediaCategory.VOICE to listOf(ChatMessage(523, chatId, senderId = 900003, date = date - 86_400,
            text = "离线语音记录（不提供播放）", kind = MessageKind.VOICE)),
    )
}
