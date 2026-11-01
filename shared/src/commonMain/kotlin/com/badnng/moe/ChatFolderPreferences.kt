package com.badnng.moe

import androidx.compose.runtime.Immutable

internal enum class FolderTabPlacement { LEFT, TOP }

/** Default adapts to the placement: labelled icons at the side, text across the top. */
internal enum class FolderTabAppearance { DEFAULT, TEXT_ONLY, ICON_TEXT, ICON_ONLY }

/** Local presentation only; it does not change the account's native folder definitions. */
@Immutable
internal data class ChatFolderPreferences(
    val placement: FolderTabPlacement = FolderTabPlacement.LEFT,
    val appearance: FolderTabAppearance = FolderTabAppearance.DEFAULT,
)

internal expect fun loadFolderPreferences(): ChatFolderPreferences
internal expect fun saveFolderPreferences(preferences: ChatFolderPreferences): Boolean
