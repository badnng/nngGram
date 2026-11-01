package com.badnng.moe

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerDraftRevisionsTest {
    private val token = ChatToken(1, 2, 3)

    @Test fun retypingTheSameTextCannotBeConsumedByThePreviousRequest() {
        val owner = ComposerDraftRevisions()
        val first = TextFieldValue("same")
        owner.changed(3, null, first)
        owner.submitted(token, first.richText(), 5)
        owner.changed(3, first, TextFieldValue())
        owner.changed(3, TextFieldValue(), TextFieldValue("same"))
        assertFalse(owner.consumeAccepted(3, 6, first.richText(), TextFieldValue("same")))
        assertFalse(owner.consumeAccepted(3, 7, first.richText(), first), "A consumed receipt cannot regain ownership.")
    }

    @Test fun movingTheCursorAndSelectionPreservesOwnership() {
        val owner = ComposerDraftRevisions()
        val original = TextFieldValue("caption", selection = TextRange(7))
        owner.changed(3, null, original)
        owner.submitted(token, original.richText(), 10)
        val selected = original.copy(selection = TextRange(1, 5))
        owner.changed(3, original, selected)
        assertEquals(1L, owner.revision(3))
        assertTrue(owner.consumeAccepted(3, 11, original.richText(), selected))
    }

    @Test fun staleReceiptsOtherChatsAndCompositionDoNotConsumeTheDraft() {
        val owner = ComposerDraftRevisions()
        val original = TextFieldValue("文字")
        owner.changed(3, null, original)
        owner.submitted(token, original.richText(), 10)
        assertFalse(owner.consumeAccepted(3, 10, original.richText(), original))
        assertFalse(owner.consumeAccepted(4, 11, original.richText(), original))
        assertFalse(owner.consumeAccepted(3, 11, original.richText(), original.copy(composition = TextRange(0, 2))))
    }

    @Test fun changingEntitiesCountsAsAnEditAndAuthorizationResetDropsAllOwnership() {
        val owner = ComposerDraftRevisions()
        val original = TextFieldValue("rich")
        owner.changed(3, null, original)
        owner.submitted(token, original.richText(), 0)
        val bold = RichMessageText("rich", listOf(MessageEntity(0, 4, MessageEntityKind.BOLD))).editorValue()
        owner.changed(3, original, bold)
        assertEquals(2L, owner.revision(3))
        assertFalse(owner.consumeAccepted(3, 1, original.richText(), bold))
        owner.submitted(token, bold.richText(), 1)
        owner.clear()
        assertEquals(0L, owner.revision(3))
        assertFalse(owner.consumeAccepted(3, 2, bold.richText(), bold))
    }
}
