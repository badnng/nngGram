package com.badnng.moe.media

/** A one-way, thread-safe source gate; observers receive invalidation without a scene frame. */
interface MediaOutputGate {
    fun active(): Boolean
    fun onInvalidated(callback: () -> Unit): () -> Unit
}
