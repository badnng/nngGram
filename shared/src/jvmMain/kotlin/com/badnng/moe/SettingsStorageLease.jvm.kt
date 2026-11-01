package com.badnng.moe

import java.util.concurrent.atomic.AtomicBoolean

internal actual fun settingsStoragePageLease(): SettingsStoragePageLease = object : SettingsStoragePageLease {
    private val opened = AtomicBoolean(true)
    override fun active(): Boolean = opened.get()
    override fun close() { opened.set(false) }
}
