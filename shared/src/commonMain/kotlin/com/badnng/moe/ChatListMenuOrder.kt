package com.badnng.moe

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.badnng.moe.chat.ChatItem

/** Holds list geometry during a menu's complete lifetime while native row content stays current. */
@Stable
internal class ChatListMenuOrder {
    private class Hold(val owner: Any, val items: List<ChatItem>)

    private var held by mutableStateOf<Hold?>(null)

    /** Capture the actual displayed sequence before opening, including any existing hold. */
    fun hold(owner: Any, displayed: List<ChatItem>) {
        held = Hold(owner, displayed.toList())
    }

    fun isHeldBy(owner: Any): Boolean = held?.owner === owner

    /** An older popup's exit must not release a newer popup's hold. */
    fun release(owner: Any) {
        if (isHeldBy(owner)) held = null
    }

    fun display(items: List<ChatItem>, latestItems: List<ChatItem> = items): List<ChatItem> {
        val current = held ?: return items
        val latestById = latestItems.associateBy { it.id }
        // Missing rows retain their slot until exit finishes; new rows wait until release.
        // Native membership validation remains separate from this presentation-only sequence.
        return current.items.map { latestById[it.id] ?: it }
    }

    override fun toString() = "ChatListMenuOrder(<redacted>)"
}
