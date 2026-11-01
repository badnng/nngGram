package com.badnng.moe

import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.RichMessageText

internal fun consumesAcceptedComposerDraft(current: TextFieldValue?, accepted: RichMessageText?): Boolean =
    current != null && accepted != null && current.composition == null && current.richText() == accepted

/** A confirmation borrows its originating draft; only its own accepted send may consume it. */
internal class ComposerDraftTransfer(val token: ChatToken, private val source: TextFieldValue, private val sourceRevision: Long = 0) {
    val caption: TextFieldValue get() = source.copy(composition = null)

    fun canConsume(currentToken: ChatToken, current: TextFieldValue, currentRevision: Long = 0): Boolean =
        currentToken == token && currentRevision == sourceRevision && consumesAcceptedComposerDraft(current, source.richText())

    override fun toString() = "ComposerDraftTransfer(<redacted>)"
}
