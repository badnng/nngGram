package com.badnng.moe.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** IDs are metadata, not permission to select, download, or send. Signed native IDs are retained. */
sealed interface ProfileStickerAvatarIdentity {
    data class CustomEmoji(val customEmojiId: Long) : ProfileStickerAvatarIdentity {
        override fun toString() = "CustomEmoji(<redacted>)"
    }

    data class RegularOrMask(
        val stickerSetId: Long,
        val stickerId: Long,
        val kind: ProfileStickerAvatarPackKind,
    ) : ProfileStickerAvatarIdentity {
        override fun toString() = "RegularOrMask(<redacted>)"
    }
}

enum class ProfileStickerAvatarPackKind { REGULAR, MASK, CUSTOM_EMOJI }

/** Reference identity is checked against the helper's private current-owner maps. */
class ProfileStickerAvatarPickerTicket internal constructor() {
    /** Revision is written under the native owner lock; it has no collectors. */
    internal val catalogRevision = MutableStateFlow(0L)
    internal val catalogInvalidations = MutableStateFlow(0L)
    internal fun markCatalogInvalidated(): Long {
        catalogRevision.update { it + 1L }
        return catalogRevision.value
    }
    internal fun notifyCatalogInvalidated(revision: Long) { catalogInvalidations.update { maxOf(it, revision) } }
    internal fun invalidateCatalog() { notifyCatalogInvalidated(markCatalogInvalidated()) }
}
class ProfileStickerAvatarPackTicket internal constructor()
class ProfileStickerAvatarCandidateTicket internal constructor()
class ProfileStickerAvatarSelectionTicket internal constructor()
/** An issued source/window capability; the native owner validates object identity. */
class ProfileStickerAvatarCatalogCursor internal constructor()

data class ProfileStickerAvatarPack(
    val ticket: ProfileStickerAvatarPackTicket,
    val title: String,
    val size: Int,
    val kind: ProfileStickerAvatarPackKind,
    val shortName: String = "",
) { override fun toString() = "ProfileStickerAvatarPack(<redacted>)" }

data class ProfileStickerAvatarPackPage(
    val packs: List<ProfileStickerAvatarPack>,
    val limited: Boolean,
    val previous: ProfileStickerAvatarCatalogCursor? = null,
    val next: ProfileStickerAvatarCatalogCursor? = null,
)

/** These fields describe native metadata. No File, path, or artwork permission is exported. */
data class ProfileStickerAvatarCandidate(
    val ticket: ProfileStickerAvatarCandidateTicket,
    val identity: ProfileStickerAvatarIdentity,
    val nativeStickerId: Long,
    val nativeSetId: Long,
    val width: Int,
    val height: Int,
    val needsRepainting: Boolean,
) { override fun toString() = "ProfileStickerAvatarCandidate(<redacted>)" }

data class ProfileStickerAvatarCandidatePage(
    val candidates: List<ProfileStickerAvatarCandidate>,
    val limited: Boolean,
    val previous: ProfileStickerAvatarCatalogCursor? = null,
    val next: ProfileStickerAvatarCatalogCursor? = null,
)

sealed interface ProfileStickerAvatarCatalogWindow {
    data class Candidates(val page: ProfileStickerAvatarCandidatePage) : ProfileStickerAvatarCatalogWindow
    data class Packs(val page: ProfileStickerAvatarPackPage) : ProfileStickerAvatarCatalogWindow
}

/** Frozen values; no mutable list or color array is retained across native callbacks. */
class ProfileStickerAvatarBackground private constructor(
    private val first: Int,
    private val second: Int?,
    private val third: Int?,
    private val fourth: Int?,
) {
    fun colors(): List<Int> = listOfNotNull(first, second, third, fourth)

    companion object {
        fun fromColors(values: List<Int>): ProfileStickerAvatarBackground? {
            if (values.size !in 1..4) return null
            val copy = values.toList()
            if (copy.size !in 1..4 || copy.any { it !in 0..0xFFFFFF }) return null
            return ProfileStickerAvatarBackground(copy[0], copy.getOrNull(1), copy.getOrNull(2), copy.getOrNull(3))
        }
    }
}
