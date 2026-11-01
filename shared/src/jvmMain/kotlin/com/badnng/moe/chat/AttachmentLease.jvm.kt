package com.badnng.moe.chat

actual fun ownedAttachmentLease(cleanup: () -> Unit): AttachmentLease = LeaseOwner(cleanup).handle()

private class LeaseOwner(val cleanup: () -> Unit) {
    private var references = 0
    private var closed = false

    @Synchronized
    fun handle(): AttachmentLease {
        check(!closed)
        references++
        return Handle(this)
    }

    @Synchronized
    fun released() {
        check(references > 0)
        references--
        if (references == 0) {
            closed = true
            cleanup()
        }
    }

    private class Handle(private val owner: LeaseOwner) : AttachmentLease {
        private var released = false
        @Synchronized override fun retain(): AttachmentLease? = if (released) null else owner.handle()
        @Synchronized override fun release() {
            if (released) return
            released = true
            owner.released()
        }
        override fun toString() = "AttachmentLease(<redacted>)"
    }
}
