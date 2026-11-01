package com.badnng.moe

import com.badnng.moe.chat.*
import kotlin.test.Test
import kotlin.test.assertEquals

class StickerPickerFilterTest {
    @Test fun filteringKeepsNativeIdentitiesAndMatchesOnlyTheCurrentLoadedEmojiLabels() {
        val heart = StickerItem(8, 300, "❤️", MessageMedia(MediaFile(108), emoji = "❤️"))
        val smile = StickerItem(9, 301, "🙂", MessageMedia(MediaFile(109), emoji = "🙂"))
        val loaded = listOf(heart, smile)
        assertEquals(loaded, filterLoadedStickers(loaded, "  "))
        assertEquals(listOf(heart), filterLoadedStickers(loaded, " ❤️ "))
        assertEquals(emptyList(), filterLoadedStickers(loaded, "unloaded native pack"))
    }
}
