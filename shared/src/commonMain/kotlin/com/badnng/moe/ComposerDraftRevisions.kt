package com.badnng.moe

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.RichMessageText

/** In-memory content ownership, independent of cursor movements and native send counters. */
@Stable
internal class ComposerDraftRevisions {
    private data class Submission(val revision: Long, val content: RichMessageText, val acceptedIdAtDispatch: Long)

    private val revisions = mutableStateMapOf<Long, Long>()
    private val submissions = mutableMapOf<Long, Submission>()

    fun revision(chatId: Long): Long = revisions[chatId] ?: 0L

    fun changed(chatId: Long, previous: TextFieldValue?, next: TextFieldValue) {
        if ((previous ?: TextFieldValue()).richText() != next.richText()) {
            revisions[chatId] = revision(chatId) + 1
        }
    }

    fun submitted(token: ChatToken, content: RichMessageText, acceptedIdAtDispatch: Long) {
        token.chatId?.let { submissions[it] = Submission(revision(it), content, acceptedIdAtDispatch) }
    }

    fun consumeAccepted(chatId: Long, acceptedId: Long, content: RichMessageText?, current: TextFieldValue?): Boolean {
        val submitted = submissions[chatId] ?: return false
        if (acceptedId <= submitted.acceptedIdAtDispatch || content != submitted.content) return false
        submissions.remove(chatId)
        return revision(chatId) == submitted.revision && consumesAcceptedComposerDraft(current, content)
    }

    fun clear() {
        revisions.clear()
        submissions.clear()
    }

    override fun toString() = "ComposerDraftRevisions(<redacted>)"
}
