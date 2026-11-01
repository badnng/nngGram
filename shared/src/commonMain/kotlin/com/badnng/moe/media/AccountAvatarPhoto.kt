package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.MediaFile

/** One workspace-owned, static account photo, shared by its entrance, menu and profile. */
internal interface AccountAvatarPhoto {
    @Composable
    fun Content(modifier: Modifier, contentDescription: String)
}

/** Identity changes replace the single in-memory photo; no process-wide photo cache is used. */
@Composable
internal expect fun rememberAccountAvatarPhoto(
    generation: Long,
    userId: Long?,
    file: MediaFile?,
): AccountAvatarPhoto?
