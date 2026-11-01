package com.badnng.moe.folders

import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.SettingsError

data class FolderSettingsToken(val generation: Long = 0, val owner: Long = 0, val pageId: Long = 0)

/** The account's native list, independent of a currently selected conversation. */
data class FolderNativeState(
    val folders: List<ChatFolderInfo> = emptyList(),
    val mainPosition: Int = 0,
    val tagsEnabled: Boolean = false,
) { override fun toString() = "FolderNativeState(<redacted>)" }

data class FolderCapabilities(val premium: Boolean, val folderLimit: Int, val chosenChatLimit: Int)
data class FolderRecommendation(val key: Long, val title: String, val description: String) {
    override fun toString() = "FolderRecommendation(<redacted>)"
}

/** A recommendation never pretends to have a native folder identifier before creation. */
sealed interface FolderDraftEntry {
    data object Main : FolderDraftEntry
    data class Existing(val id: Int) : FolderDraftEntry { override fun toString() = "Existing(<redacted>)" }
    data class Recommended(val key: Long) : FolderDraftEntry { override fun toString() = "Recommended(<redacted>)" }
}

data class FolderOverviewDraft(
    val source: FolderNativeState,
    val order: List<FolderDraftEntry>,
    val removed: Set<Int> = emptySet(),
    val revision: Long = 0,
) {
    val dirty: Boolean get() = removed.isNotEmpty() || order != entries(source)
    override fun toString() = "FolderOverviewDraft(<redacted>)"
    companion object {
        fun entries(source: FolderNativeState): List<FolderDraftEntry> = source.folders
            .map { FolderDraftEntry.Existing(it.id) as FolderDraftEntry }.toMutableList().apply {
                add(source.mainPosition.coerceIn(0, size), FolderDraftEntry.Main)
            }
        fun from(source: FolderNativeState, revision: Long = 0) = FolderOverviewDraft(source, entries(source), revision = revision)
    }
}

enum class FolderSettingsOperation { APPLY, TAGS, COLOR }
data class FolderSettingsSnapshot(
    val token: FolderSettingsToken = FolderSettingsToken(),
    val opened: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val nativeState: FolderNativeState = FolderNativeState(),
    val nativeRevision: Long = 0,
    val capabilities: FolderCapabilities? = null,
    val recommendations: List<FolderRecommendation> = emptyList(),
    val draft: FolderOverviewDraft? = null,
    val conflict: Boolean = false,
    val error: SettingsError? = null,
    val operation: FolderSettingsOperation? = null,
    /** Counts accepted native operations; it does not establish cloud synchronization. */
    val acceptedSteps: Int = 0,
    val completedRevision: Long = 0,
) { override fun toString() = "FolderSettingsSnapshot(<redacted>, busy=$busy)" }

data class FolderSettingsActions(
    val open: (ChatToken, Long, () -> Boolean) -> Unit = { _, _, _ -> },
    val close: (FolderSettingsToken) -> Unit = {},
    val refresh: (FolderSettingsToken) -> Unit = {},
    val stageRecommendation: (FolderSettingsToken, Long) -> Unit = { _, _ -> },
    val move: (FolderSettingsToken, FolderDraftEntry, Int) -> Unit = { _, _, _ -> },
    val remove: (FolderSettingsToken, Int) -> Unit = { _, _ -> },
    val restore: (FolderSettingsToken, Int) -> Unit = { _, _ -> },
    val reset: (FolderSettingsToken) -> Unit = {},
    val apply: (FolderSettingsToken, Long) -> Unit = { _, _ -> },
    val toggleTags: (FolderSettingsToken, Boolean) -> Unit = { _, _ -> },
    val color: (FolderSettingsToken, ChatFolderInfo, Int) -> Unit = { _, _, _ -> },
)
