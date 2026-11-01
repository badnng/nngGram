package com.badnng.moe.chat

import kotlin.test.*

class ChatFolderDraftTest {
    private val source = ChatFolder(7, "📁 W", nameEntities = listOf(
        MessageEntity(0, 2, MessageEntityKind.CUSTOM_EMOJI, id = 98765)), animateCustomEmoji = true,
        sourceId = 88, editorRevision = 4)

    @Test fun editingASuffixKeepsTheUnchangedCustomEmojiAndItsNativeOwnership() {
        val changed = source.withNameText("📁 Works")
        assertEquals(source.nameEntities, changed.nameEntities)
        assertTrue(changed.animateCustomEmoji)
        assertEquals(source.sourceId, changed.sourceId)
        assertEquals(source.editorRevision, changed.editorRevision)
    }

    @Test fun insertingUnicodeBeforeTheEmojiMovesItsUtf16SpanWithoutExpandingIt() {
        val changed = source.withNameText("🧸 📁 W")
        assertEquals(listOf(source.nameEntities.single().copy(offset = 3)), changed.nameEntities)
        assertEquals(2, changed.nameEntities.single().length)
    }

    @Test fun deletingOrReplacingTheActualEmojiRemovesOnlyThatEntityAndKeepsAnimationIndependent() {
        for (text in listOf("W", "🧸 W", " W")) {
            val changed = source.withNameText(text)
            assertTrue(changed.nameEntities.isEmpty())
            assertTrue(changed.animateCustomEmoji)
        }
    }

    @Test fun insertingAnIdenticalPlainGlyphBeforeTheCustomGlyphUsesTheActualCaret() {
        val changed = source.withNameText("📁📁 W", 0, 0, 2, 2)
        assertEquals(listOf(source.nameEntities.single().copy(offset = 2)), changed.nameEntities)
        assertTrue(changed.animateCustomEmoji)
        assertEquals(source.sourceId, changed.sourceId)
    }

    @Test fun deletingAnIdenticalPlainGlyphKeepsTheRemainingCustomGlyphAtItsActualLocation() {
        val two = source.copy(name = "📁📁 W", nameEntities = listOf(source.nameEntities.single().copy(offset = 2)))
        assertEquals(source.nameEntities, two.withNameText("📁 W", 0, 0, 0, 0).nameEntities)
        assertEquals(source.nameEntities, two.withNameText("📁 W", 2, 2, 0, 0).nameEntities)
        assertTrue(two.withNameText("📁 W", 2, 2, 2, 2).nameEntities.isEmpty(),
            "Delete at the custom glyph removes its identity rather than assigning it to the identical plain glyph.")
    }

    @Test fun replacingASelectedGlyphPreservesOnlyTheOtherCustomIdentity() {
        val two = source.copy(name = "📁📁 W", nameEntities = listOf(source.nameEntities.single().copy(offset = 2)))
        val changed = two.withNameText("🧸📁 W", 0, 2, 2, 2)
        assertEquals(two.nameEntities, changed.nameEntities)
        assertTrue(two.withNameText("📁🧸 W", 2, 4, 4, 4).nameEntities.isEmpty())
    }

    @Test fun ambiguousPlainTextOrOversizedChangesNeverGiveAnInsertedGlyphAnOldCustomIdentity() {
        assertTrue(source.withNameText("📁📁 W").nameEntities.isEmpty())
        val two = source.copy(name = "📁📁 W", nameEntities = listOf(source.nameEntities.single().copy(offset = 2)))
        assertTrue(two.withNameText("📁 W").nameEntities.isEmpty())
        val oversized = source.withNameText("x".repeat(257) + source.name)
        assertTrue(oversized.nameEntities.isEmpty())
        assertTrue(oversized.animateCustomEmoji)
    }
}
