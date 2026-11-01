package com.badnng.moe.tdlib

import com.badnng.moe.settings.ProfileStickerAvatarIdentity
import com.badnng.moe.settings.ProfileStickerAvatarPackKind
import org.drinkless.tdlib.TdApi
import java.security.MessageDigest

/** Only immutable window records survive the scan; no TDLib objects are retained. */
internal data class ProfileAvatarScanCandidate(
    val identity: ProfileStickerAvatarIdentity, val stickerId: Long, val setId: Long,
    val width: Int, val height: Int, val repaint: Boolean, val media: ProfileStickerAvatarMediaFingerprint?,
) { override fun toString() = "ProfileAvatarScanCandidate(<redacted>)" }

internal data class ProfileAvatarScanPack(val id: Long, val kind: ProfileStickerAvatarPackKind,
    val title: String, val shortName: String, val size: Int,
) { override fun toString() = "ProfileAvatarScanPack(<redacted>)" }

internal data class ProfileAvatarScanPage<T>(val values: List<T>, val revision: ByteArray,
    val previous: Int?, val next: Int?, val conflicts: Boolean,
) { override fun toString() = "ProfileAvatarScanPage(<redacted>)" }

internal class ProfileAvatarScanStopped : Exception()
internal class ProfileAvatarScanBudget : Exception()

/** Hash UTF-16 incrementally so a native title does not allocate another full string. */
private class CatalogDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun number(value: Long) { for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte()) }
    fun text(value: String?) {
        if (value == null) { number(-1); return }
        if (value.length > MAX_CATALOG_TITLE_UNITS) throw ProfileAvatarScanBudget()
        number(value.length.toLong())
        for (ch in value) { digest.update((ch.code ushr 8).toByte()); digest.update(ch.code.toByte()) }
    }
    fun native(value: TdApi.Object?) = number(value?.getConstructor()?.toLong() ?: -1)
    fun finish(): ByteArray = digest.digest()
}

private const val CATALOG_WINDOW_SIZE = 64
private const val MAX_CATALOG_TITLE_UNITS = 65_536

private fun checkpoint(index: Int, active: () -> Boolean) {
    if (index % 256 == 0 && !active()) throw ProfileAvatarScanStopped()
}

private fun fileDigest(hash: CatalogDigest, file: TdApi.File?) {
    hash.number(file?.id?.toLong() ?: -1)
    hash.text(file?.remote?.uniqueId)
}

private fun stickerDigest(hash: CatalogDigest, sticker: TdApi.Sticker?) {
    if (sticker == null) { hash.number(-1); return }
    hash.number(sticker.id); hash.number(sticker.setId)
    hash.number(sticker.width.toLong()); hash.number(sticker.height.toLong())
    hash.text(sticker.emoji)
    hash.native(sticker.fullType); hash.native(sticker.format)
    val custom = sticker.fullType as? TdApi.StickerFullTypeCustomEmoji
    hash.number(custom?.customEmojiId ?: 0); hash.number(if (custom?.needsRepainting == true) 1 else 0)
    fileDigest(hash, sticker.sticker)
    val thumb = sticker.thumbnail
    hash.native(thumb?.format); hash.number(thumb?.width?.toLong() ?: -1); hash.number(thumb?.height?.toLong() ?: -1)
    fileDigest(hash, thumb?.file)
}

/** Revalidate the exact full source without retaining another page or TDLib object. */
internal fun profileAvatarStickerRevision(stickers: Array<out TdApi.Sticker?>, active: () -> Boolean): ByteArray {
    val hash = CatalogDigest()
    hash.number(stickers.size.toLong())
    for ((index, sticker) in stickers.withIndex()) {
        checkpoint(index, active)
        stickerDigest(hash, sticker)
    }
    return hash.finish()
}

private fun validSticker(sticker: TdApi.Sticker?, kind: ProfileStickerAvatarPackKind?,
    packId: Long?,
): ProfileStickerAvatarIdentity? {
    if (sticker == null) return null
    if (kind == null) {
        val id = (sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: return null
        return id.takeIf { it != 0L }?.let { ProfileStickerAvatarIdentity.CustomEmoji(it) }
    }
    val setId = checkNotNull(packId)
    return when (kind) {
        ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> {
            val id = (sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: return null
            if (id == 0L || sticker.setId != 0L && sticker.setId != setId) null
            else ProfileStickerAvatarIdentity.CustomEmoji(id)
        }
        ProfileStickerAvatarPackKind.REGULAR, ProfileStickerAvatarPackKind.MASK -> {
            val rightType = if (kind == ProfileStickerAvatarPackKind.REGULAR)
                sticker.fullType is TdApi.StickerFullTypeRegular else sticker.fullType is TdApi.StickerFullTypeMask
            if (!rightType || sticker.id == 0L || sticker.setId != setId) null
            else ProfileStickerAvatarIdentity.RegularOrMask(setId, sticker.id, kind)
        }
    }
}

/** Fixed-size ring records only the preceding window's actual raw positions. */
private class PreviousPositions {
    private val positions = IntArray(CATALOG_WINDOW_SIZE)
    private var count = 0L
    fun add(value: Int) { positions[(count % positions.size).toInt()] = value; count++ }
    fun start(): Int? = if (count == 0L) null else positions[((count - minOf(count, positions.size.toLong())) % positions.size).toInt()]
}

internal fun scanProfileAvatarStickers(stickers: Array<out TdApi.Sticker?>, kind: ProfileStickerAvatarPackKind?,
    packId: Long?, offset: Int, active: () -> Boolean,
): ProfileAvatarScanPage<ProfileAvatarScanCandidate> {
    require(offset in 0..stickers.size)
    val hash = CatalogDigest()
    hash.number(stickers.size.toLong())
    val previous = PreviousPositions()
    val selected = ArrayList<ProfileAvatarScanCandidate>(CATALOG_WINDOW_SIZE)
    var next: Int? = null
    for ((index, sticker) in stickers.withIndex()) {
        checkpoint(index, active)
        stickerDigest(hash, sticker)
        val identity = validSticker(sticker, kind, packId) ?: continue
        if (index < offset) previous.add(index)
        else if (selected.size < CATALOG_WINDOW_SIZE) {
            val native = checkNotNull(sticker)
            val custom = native.fullType as? TdApi.StickerFullTypeCustomEmoji
            selected += ProfileAvatarScanCandidate(identity, native.id, native.setId, native.width, native.height,
                custom?.needsRepainting ?: false, runCatching { profileStickerAvatarFingerprint(native) }.getOrNull())
        } else if (next == null) next = index
    }
    val watched = selected.associate { it.identity to 0 }.toMutableMap()
    for ((index, sticker) in stickers.withIndex()) {
        checkpoint(index, active)
        val identity = if (kind == null || kind == ProfileStickerAvatarPackKind.CUSTOM_EMOJI) {
            val id = (sticker?.fullType as? TdApi.StickerFullTypeCustomEmoji)?.customEmojiId ?: 0
            if (id == 0L) null else ProfileStickerAvatarIdentity.CustomEmoji(id)
        } else {
            val id = sticker?.id ?: 0
            if (id == 0L) null else ProfileStickerAvatarIdentity.RegularOrMask(checkNotNull(packId), id, kind)
        } ?: continue
        if (identity in watched) watched[identity] = checkNotNull(watched[identity]) + 1
    }
    val conflicts = watched.values.any { it != 1 }
    val unique = selected.filter { watched[it.identity] == 1 }
    return ProfileAvatarScanPage(unique, hash.finish(), previous.start(), next, conflicts)
}

internal fun scanProfileAvatarPacks(sets: Array<out TdApi.StickerSetInfo?>, kind: ProfileStickerAvatarPackKind,
    query: String, offset: Int, active: () -> Boolean,
): ProfileAvatarScanPage<ProfileAvatarScanPack> {
    require(offset in 0..sets.size)
    val hash = CatalogDigest()
    hash.number(sets.size.toLong())
    val previous = PreviousPositions()
    val selected = ArrayList<ProfileAvatarScanPack>(CATALOG_WINDOW_SIZE)
    var next: Int? = null
    fun valid(info: TdApi.StickerSetInfo?): Boolean = info != null && info.id != 0L && info.isInstalled &&
        !info.isArchived && info.size >= 0 && when (kind) {
            ProfileStickerAvatarPackKind.REGULAR -> info.stickerType is TdApi.StickerTypeRegular
            ProfileStickerAvatarPackKind.MASK -> info.stickerType is TdApi.StickerTypeMask
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI -> info.stickerType is TdApi.StickerTypeCustomEmoji
        }
    for ((index, info) in sets.withIndex()) {
        checkpoint(index, active)
        if (info == null) { hash.number(-1); continue }
        hash.number(info.id); hash.native(info.stickerType); hash.number(info.size.toLong())
        hash.number(if (info.isInstalled) 1 else 0); hash.number(if (info.isArchived) 1 else 0)
        hash.text(info.title); hash.text(info.name)
        if (!valid(info)) continue
        val title = info.title.orEmpty(); val name = info.name.orEmpty()
        if (!title.contains(query, ignoreCase = true) && !name.contains(query, ignoreCase = true)) continue
        if (index < offset) previous.add(index)
        else if (selected.size < CATALOG_WINDOW_SIZE) {
            if (title.length > MAX_CATALOG_TITLE_UNITS || name.length > MAX_CATALOG_TITLE_UNITS) throw ProfileAvatarScanBudget()
            selected += ProfileAvatarScanPack(info.id, kind, title, name, info.size)
        } else if (next == null) next = index
    }
    val watched = selected.associate { it.id to 0 }.toMutableMap()
    for ((index, info) in sets.withIndex()) {
        checkpoint(index, active)
        if (valid(info) && info!!.id in watched) watched[info.id] = checkNotNull(watched[info.id]) + 1
    }
    val unique = selected.filter { watched[it.id] == 1 }
    return ProfileAvatarScanPage(unique, hash.finish(), previous.start(), next, unique.size != selected.size)
}
