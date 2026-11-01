package com.badnng.moe

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.*
import kotlin.test.*

class ComposerDraftTransferTest {
    private val token = ChatToken(1, 1, 100)
    private val content = RichMessageText("图片说明 😀", listOf(MessageEntity(0, 4, MessageEntityKind.BOLD)))

    @Test fun captionKeepsTheDraftTextEntitiesAndSelectionWithoutMovingItsImeComposition() {
        val source = content.editorValue(TextRange(2, 4), TextRange(4, 6))
        val transfer = ComposerDraftTransfer(token, source)
        assertEquals(content, transfer.caption.richText())
        assertEquals(source.selection, transfer.caption.selection)
        assertNull(transfer.caption.composition)
        assertEquals(TextRange(4, 6), source.composition)
        assertFalse(transfer.toString().contains(content.text))
    }

    @Test fun acceptedAttachmentConsumesOnlyTheUnchangedDraftInItsOriginalSelection() {
        val source = content.editorValue(TextRange(2))
        val transfer = ComposerDraftTransfer(token, source)
        assertTrue(transfer.canConsume(token, source))
        assertTrue(transfer.canConsume(token, source.copy(selection = TextRange(4))))
        assertFalse(transfer.canConsume(token.copy(selection = token.selection + 1), source))
        assertFalse(transfer.canConsume(token.copy(generation = token.generation + 1), source))
        assertFalse(transfer.canConsume(token, TextFieldValue("新的草稿")))
        assertFalse(transfer.canConsume(token, source.copy(composition = TextRange(1, 2))))
        assertFalse(transfer.canConsume(token, RichMessageText(content.text).editorValue()))
    }

    @Test fun lateTextAcceptancePreservesNewerContentAndActiveImeEvenWhenItsTextMatches() {
        val source = content.editorValue()
        assertTrue(consumesAcceptedComposerDraft(source, content))
        assertFalse(consumesAcceptedComposerDraft(TextFieldValue("下一条"), content))
        assertFalse(consumesAcceptedComposerDraft(source.copy(composition = TextRange(0, 2)), content))
        assertFalse(consumesAcceptedComposerDraft(source, RichMessageText(content.text)))
        assertFalse(consumesAcceptedComposerDraft(source, null), "A media receipt cannot clear a text draft.")
    }

    @Test fun retypingTheIdenticalCaptionAfterDispatchStillOwnsANewerDraft() {
        val source = content.editorValue()
        val transfer = ComposerDraftTransfer(token, source, sourceRevision = 5)
        assertTrue(transfer.canConsume(token, source.copy(selection = TextRange(1)), currentRevision = 5))
        assertFalse(transfer.canConsume(token, source, currentRevision = 7),
            "Delete and retype can return the same content while still creating a new draft.")
    }
}
