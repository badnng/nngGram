package com.badnng.moe.chat

/** 0 is the main list, -1 the archive, and positive IDs are account folders. */
data class ChatListRef(val folderId: Int = 0)

enum class MediaFormat { PHOTO, WEBP, TGS, WEBM, VIDEO, ANIMATION, FILE }

data class MediaFile(
    val id: Int,
    val size: Long = 0,
    val expectedSize: Long = 0,
    val localPath: String? = null,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    val downloadedSize: Long = 0,
    val canDownload: Boolean = false,
    val uploading: Boolean = false,
    val uploadedSize: Long = 0,
    /** Stable native remote identity; never the temporary remote id or a local path. */
    val uniqueId: String = "",
) {
    override fun toString() = "MediaFile(<redacted>)"
}

/** Native inline JPEG bytes; immutable account content, never a file or a download request. */
class MediaMiniThumbnail(val width: Int, val height: Int, encoded: ByteArray) {
    private val bytes: ByteArray
    private val contentHash: Int

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION)
        require(encoded.size in 1..MAX_ENCODED_BYTES)
        bytes = encoded.copyOf()
        contentHash = 31 * (31 * width + height) + bytes.contentHashCode()
    }

    /** Decoders receive their own copy so a worker cannot mutate a published native snapshot. */
    fun encodedCopy(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = this === other || other is MediaMiniThumbnail &&
        width == other.width && height == other.height && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = contentHash

    override fun toString() = "MediaMiniThumbnail(<redacted>)"

    companion object {
        /** The pinned TDLib reconstructs dimensions from unsigned bytes, not a strict 40 px cap. */
        const val MAX_DIMENSION = 255

        /** Application memory bound; TDLib documents JPEG data without a byte-count guarantee. */
        const val MAX_ENCODED_BYTES = 65_536
    }
}

data class MessageMedia(
    val file: MediaFile,
    val thumbnail: MediaFile? = null,
    val width: Int = 0,
    val height: Int = 0,
    val format: MediaFormat = MediaFormat.PHOTO,
    val emoji: String = "",
    val setId: Long = 0,
    val thumbnailFormat: MediaFormat? = null,
    val hasSpoiler: Boolean = false,
    val fileName: String = "",
    val mimeType: String = "",
    val durationSeconds: Int = 0,
    val animatedEmoji: AnimatedEmojiMedia? = null,
    val minithumbnail: MediaMiniThumbnail? = null,
) {
    override fun toString() = "MessageMedia(<redacted>)"
}

/** Picture metadata only. Animated-emoji sounds and click effects have separate native ownership. */
data class AnimatedEmojiMedia(
    val stickerWidth: Int,
    val stickerHeight: Int,
    val fitzpatrickType: Int,
    val isRegularEmoji: Boolean,
)

data class StickerItem(val id: Long, val setId: Long, val emoji: String, val media: MessageMedia) {
    override fun toString() = "StickerItem(<redacted>)"
}

/** Native, offline sticker minithumbnail path in the sticker's original coordinate space. */
data class StickerOutline(val svgPath: String, val width: Int, val height: Int) {
    override fun toString() = "StickerOutline(<redacted>)"
}

data class StickerPack(val id: Long, val title: String, val size: Int = 0, val cover: StickerItem? = null) {
    override fun toString() = "StickerPack(<redacted>)"
}

/** A native regular-sticker section; null identifies unattached recent stickers. */
data class StickerSection(
    val setId: Long? = null,
    val title: String = "",
    val stickers: List<StickerItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: ChatError? = null,
) {
    override fun toString() = "StickerSection(<redacted>)"
}

data class ChatFolderInfo(
    val id: Int,
    val name: String,
    val iconName: String? = null,
    val colorId: Int = -1,
    val isShareable: Boolean = false,
    val hasMyInviteLinks: Boolean = false,
) {
    override fun toString() = "ChatFolderInfo(<redacted>)"
}

data class ChatFolder(
    val id: Int = 0,
    val name: String,
    val iconName: String? = null,
    val colorId: Int = -1,
    val isShareable: Boolean = false,
    val pinnedChatIds: List<Long> = emptyList(),
    val includedChatIds: List<Long> = emptyList(),
    val excludedChatIds: List<Long> = emptyList(),
    val excludeMuted: Boolean = false,
    val excludeRead: Boolean = false,
    val excludeArchived: Boolean = false,
    val includeContacts: Boolean = false,
    val includeNonContacts: Boolean = false,
    val includeBots: Boolean = false,
    val includeGroups: Boolean = false,
    val includeChannels: Boolean = false,
    /** Opaque immutable native opening source; never persisted outside this engine session. */
    val sourceId: Long = 0,
    val editorRevision: Long = 0,
    val nameEntities: List<MessageEntity> = emptyList(),
    val animateCustomEmoji: Boolean = false,
    val nameEditable: Boolean = true,
) {
    override fun toString() = "ChatFolder(<redacted>)"
}
