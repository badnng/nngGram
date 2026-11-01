package com.badnng.moe.tdlib

import com.badnng.moe.settings.ProfileStickerAvatarIdentity
import com.badnng.moe.settings.ProfileStickerAvatarPackKind
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic in-memory native values; no account, transport, or media download. */
class ProfileStickerAvatarCatalogScanTest {
    @Test fun orderedWindowsReachEveryRealItemBeyondTheFormerLimitsAndCanGoBack() {
        val input = Array(4097) { custom(it.toLong() + 1) }
        val all = mutableListOf<Long>()
        var offset = 0
        var last: ProfileAvatarScanPage<ProfileAvatarScanCandidate>
        do {
            last = scanProfileAvatarStickers(input, null, null, offset) { true }
            assertTrue(last.values.size <= 64)
            assertFalse(last.conflicts)
            all += last.values.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId }
            offset = last.next ?: input.size
        } while (last.next != null)
        assertEquals((1L..4097L).toList(), all)
        val previous = assertNotNull(last.previous)
        val prior = scanProfileAvatarStickers(input, null, null, previous) { true }
        assertEquals(all.takeLast(65).take(64), prior.values.map { (it.identity as ProfileStickerAvatarIdentity.CustomEmoji).customEmojiId })
        assertEquals(input.size - 1, prior.next)
        assertTrue(last.revision.contentEquals(prior.revision))
    }

    @Test fun completePackNameAndShortNameMatchBeyondOldBoundsAndConflictsAreNotAuthorized() {
        val sets = Array(300) { index -> pack(index.toLong() + 1, "pack-$index", "name-$index") }
        sets[270].title = "a".repeat(128) + "needle"
        sets[271].name = "long-real-short-name-needle"
        val page = scanProfileAvatarPacks(sets, ProfileStickerAvatarPackKind.REGULAR, "NEEDLE", 0) { true }
        assertEquals(listOf(271L, 272L), page.values.map { it.id })
        assertEquals(sets[270].title, page.values[0].title)
        assertEquals(sets[271].name, page.values[1].shortName)
        assertNull(page.next)

        val original = Array(130) { custom(it.toLong() + 1) }
        original[90] = custom(3)
        val first = scanProfileAvatarStickers(original, null, null, 0) { true }
        assertTrue(first.conflicts)
        assertTrue(first.values.none { it.identity == ProfileStickerAvatarIdentity.CustomEmoji(3) },
            "An ambiguous duplicate receives no candidate or media authority.")
        val changed = original.copyOf()
        changed[100] = custom(10000)
        val next = scanProfileAvatarStickers(changed, null, null, assertNotNull(first.next)) { true }
        assertFalse(first.revision.contentEquals(next.revision), "A later-window change must invalidate an old cursor.")
    }

    @Test fun cancellationAndMetadataBudgetFailExplicitly() {
        val values = Array(1024) { custom(it.toLong() + 1) }
        var checks = 0
        assertFailsWith<ProfileAvatarScanStopped> {
            scanProfileAvatarStickers(values, null, null, 0) { ++checks < 2 }
        }
        val oversized = arrayOf(pack(1, "x".repeat(65_537), "name"))
        assertFailsWith<ProfileAvatarScanBudget> {
            scanProfileAvatarPacks(oversized, ProfileStickerAvatarPackKind.REGULAR, "", 0) { true }
        }
    }

    private fun custom(id: Long) = TdApi.Sticker().apply {
        this.id = id; setId = 0; width = 100; height = 100
        fullType = TdApi.StickerFullTypeCustomEmoji(id, false)
    }
    private fun pack(id: Long, title: String, name: String) = TdApi.StickerSetInfo().apply {
        this.id = id; this.title = title; this.name = name; size = 1; isInstalled = true
        stickerType = TdApi.StickerTypeRegular()
    }
}
