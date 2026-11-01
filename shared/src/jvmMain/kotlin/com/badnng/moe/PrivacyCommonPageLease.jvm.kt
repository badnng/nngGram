package com.badnng.moe

import java.util.concurrent.atomic.AtomicBoolean

internal actual fun privacyCommonPageLease(): PrivacyCommonPageLease = object : PrivacyCommonPageLease {
    private val current = AtomicBoolean(true)
    override fun active() = current.get()
    override fun close() { current.set(false) }
}
