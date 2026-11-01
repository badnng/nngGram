package com.badnng.moe

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.ChatListRef

/** In-memory list presentation state, owned by the workspace rather than its visible pane. */
@Stable
internal class ChatListPaneState(initialList: ChatListRef) {
    val tabScroll = ScrollState(0)
    val topTabsState = ChatFolderTopTabsState()
    internal var headerHasMounted = false
    private var activeList = initialList
    private val lists = mutableMapOf<ChatListRef, ChatListPaneListState>()

    internal fun selectList(list: ChatListRef) {
        if (activeList == list) return
        activeList = list
        // A genuine folder switch starts that list at its top. Keep separate entries
        // while AnimatedContent is still drawing the outgoing folder.
        lists[list] = ChatListPaneListState()
    }

    internal fun forList(list: ChatListRef): ChatListPaneListState =
        lists.getOrPut(list) { ChatListPaneListState() }
}

@Stable
internal class ChatListPaneListState {
    val listState = LazyListState()
    var query by mutableStateOf(TextFieldValue())
    internal var appliedQuery: String? = null
}

@Composable
internal fun rememberChatListPaneState(generation: Long, activeList: ChatListRef): ChatListPaneState {
    val state = remember(generation) { ChatListPaneState(activeList) }
    return remember(state, activeList) { state.apply { selectList(activeList) } }
}
