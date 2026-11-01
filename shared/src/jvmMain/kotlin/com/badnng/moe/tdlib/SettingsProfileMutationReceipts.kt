package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsProfileExtrasOperation
import com.badnng.moe.settings.SettingsProfileExtrasToken
import com.badnng.moe.settings.SettingsResult
import java.util.Collections

/**
 * Bounded, process-memory facts returned by profile operations. This is not an authorization
 * source, a queue, or a claim that a successful operation necessarily sent a server mutation.
 */
internal class SettingsProfileMutationReceipts {
    private val lock = Any()
    private val issuer = Any()
    private val results = LinkedHashMap<Key, SettingsResult<Unit>>()

    /** Every controller incarnation receives a distinct reference, even within one generation. */
    class Scope internal constructor(private val issuer: Any) {
        internal fun issuedBy(candidate: Any): Boolean = issuer === candidate
        override fun toString() = "ProfileMutationScope(<redacted>)"
    }

    data class Key(
        val scope: Scope,
        val token: SettingsProfileExtrasToken,
        val revision: Long,
        val operation: SettingsProfileExtrasOperation,
    ) {
        override fun toString() = "ProfileMutationKey(<redacted>)"
    }

    data class Receipt(val key: Key, val result: SettingsResult<Unit>) {
        override fun toString() = "ProfileMutationReceipt(<redacted>)"
    }

    fun issueScope(): Scope = Scope(issuer)

    /** Retains the exact first result for this key; a duplicate does not change eviction order. */
    fun record(
        scope: Scope,
        token: SettingsProfileExtrasToken,
        revision: Long,
        operation: SettingsProfileExtrasOperation,
        result: SettingsResult<Unit>,
    ): Boolean {
        if (!scope.issuedBy(issuer)) return false
        val key = Key(scope, token, revision, operation)
        return synchronized(lock) {
            if (results.containsKey(key)) false else {
                results[key] = result
                if (results.size > MAX_RECEIPTS) {
                    val iterator = results.entries.iterator()
                    iterator.next()
                    iterator.remove()
                }
                true
            }
        }
    }

    fun find(
        scope: Scope,
        token: SettingsProfileExtrasToken,
        revision: Long,
        operation: SettingsProfileExtrasOperation,
    ): SettingsResult<Unit>? {
        if (!scope.issuedBy(issuer)) return null
        val key = Key(scope, token, revision, operation)
        return synchronized(lock) { results[key] }
    }

    /** A detached oldest-to-newest snapshot; no callback executes under the store lock. */
    fun snapshot(): List<Receipt> = synchronized(lock) {
        Collections.unmodifiableList(ArrayList(results.map { (key, result) -> Receipt(key, result) }))
    }

    private companion object { const val MAX_RECEIPTS = 64 }
}
