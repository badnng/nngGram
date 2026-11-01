package com.badnng.moe

import java.util.concurrent.atomic.AtomicBoolean

internal actual fun folderSettingsPageLease(): FolderSettingsPageLease = object : FolderSettingsPageLease {
    private val opened = AtomicBoolean(true)
    override fun active(): Boolean = opened.get()
    override fun close() { opened.set(false) }
}
