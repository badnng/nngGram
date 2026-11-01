package com.badnng.moe

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.chat.StickerSection
import kotlin.test.Test
import kotlin.test.assertEquals

class StickerPickerSlotCountTest {
    @Test
    fun nativeEstimatesReserveBoundedLayoutWhileUnknownCountsUseOneRow() {
        val pending = StickerSection(11, "Native pack")
        assertEquals(200, stickerSectionSlotCount(pending, 200, 5))
        assertEquals(4096, stickerSectionSlotCount(pending, Int.MAX_VALUE, 5))
        assertEquals(5, stickerSectionSlotCount(pending, 0, 5))
        assertEquals(3, stickerSectionSlotCount(pending, -1, 3))
        assertEquals(1, stickerSectionSlotCount(pending, 0, 0))
    }

    @Test
    fun aRealNativeListOwnsItsCountIncludingEmptyAndRefreshingSections() {
        val item = StickerItem(10, 11, "🙂", MessageMedia(MediaFile(10)))
        assertEquals(1, stickerSectionSlotCount(StickerSection(11, "Native pack", loaded = true), 200, 5))
        assertEquals(2, stickerSectionSlotCount(StickerSection(11, "Native pack", listOf(item, item.copy(id = 11)),
            loaded = true, loading = true), 200, 5))
    }
}
