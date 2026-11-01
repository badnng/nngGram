package com.badnng.moe.chat

import com.badnng.moe.settings.SettingsProfileColorsValues

/** Metadata for an actual referenced peer; never message history or an authority to select self. */
data class PeerAppearancePresentation(
    val identity: ForwardSourceIdentity,
    val name: String,
    val appearance: SettingsProfileColorsValues? = null,
) {
    override fun toString() = "PeerAppearancePresentation(<redacted>)"
}

/** User and chat IDs live in distinct domains even when their numeric values coincide. */
fun ChatSnapshot.peerAppearance(id: Long, isChat: Boolean): SettingsProfileColorsValues? = when {
    id == 0L -> null
    isChat -> knownChats.firstOrNull { it.id == id }?.appearance
        ?: selected?.takeIf { it.id == id }?.appearance
        ?: items.firstOrNull { it.id == id }?.appearance
    else -> senderAppearances[id] ?: account?.takeIf { it.userId == id }?.appearance
}
