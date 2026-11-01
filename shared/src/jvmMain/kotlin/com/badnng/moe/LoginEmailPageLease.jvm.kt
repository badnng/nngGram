package com.badnng.moe

import java.util.concurrent.atomic.AtomicBoolean

internal actual fun loginEmailPageLease(): LoginEmailPageLease = object : LoginEmailPageLease {
    private val opened = AtomicBoolean(true)
    override fun active(): Boolean = opened.get()
    override fun close() { opened.set(false) }
}
