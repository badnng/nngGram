package com.badnng.moe

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import com.badnng.moe.media.AttachmentClipboard
import com.badnng.moe.settings.SettingsProxyQrClipboard
import com.badnng.moe.settings.SettingsProxyTextClipboard

/** Unifies the platform-specific clipboard host behind one type for the main entry point. */
internal interface DesktopClipboardHost : Clipboard, AttachmentClipboard, SettingsProxyQrClipboard, SettingsProxyTextClipboard {
    val manager: ClipboardManager
}
