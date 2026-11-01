package com.badnng.moe.security

import com.badnng.moe.settings.LocalLockIssue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An open wrap deliberately contains the key and offers no password secrecy. */
class LocalOpenWrapTest {
    @Test
    fun openWrapPreservesTheDatabaseKeyAndTransfersItsOwnerOnlyOnce() {
        val identity = ByteArray(32) { (it + 1).toByte() }
        val source = LocalDatabaseKey.copyOf(ByteArray(32) { 42 })
        val record = assertIs<LocalCryptoResult.Success<ByteArray>>(LocalOpenWrap.encode(source, identity, 2)).value
        try {
            val present = assertIs<LocalRecordRead.Present>(LocalOpenWrap.inspect(record, identity))
            assertEquals(LocalStoredKind.OPEN, present.envelope.kind)
            assertEquals(2L, present.envelope.generation)
            assertTrue(present.envelope.bytes.isEmpty(), "The public envelope view must not carry the open key.")
            assertFalse(present.toString().contains("42"))
            val owner = assertNotNull(present.takeOpenKey())
            assertEquals(null, present.takeOpenKey())
            present.close()
            assertIs<LocalCryptoResult.Success<Unit>>(owner.borrow { bytes ->
                assertContentEquals(ByteArray(32) { 42 }, bytes)
                LocalCryptoResult.Success(Unit)
            })
            owner.close()
            assertEquals(LocalCryptoResult.KeyUnavailable, owner.borrow { LocalCryptoResult.Success(Unit) })
        } finally {
            record.fill(0)
            source.close()
        }
    }

    @Test
    fun corruptionAndOtherRootDoNotBecomeAbsentOrOpen() {
        val identity = ByteArray(32) { (it + 1).toByte() }
        val source = LocalDatabaseKey.copyOf(ByteArray(32) { 42 })
        val record = assertIs<LocalCryptoResult.Success<ByteArray>>(LocalOpenWrap.encode(source, identity, 1)).value
        try {
            assertEquals(LocalLockIssue.ROOT_IDENTITY_UNAVAILABLE,
                assertIs<LocalRecordRead.Failed>(LocalOpenWrap.inspect(record, ByteArray(32))).issue)
            for (offset in listOf(0, 52, 83, 84, record.lastIndex)) {
                val corrupt = record.copyOf()
                corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
                assertIs<LocalRecordRead.Failed>(LocalOpenWrap.inspect(corrupt, identity))
                corrupt.fill(0)
            }
            assertIs<LocalRecordRead.Failed>(LocalOpenWrap.inspect(record.copyOf(record.size - 1), identity))
        } finally {
            record.fill(0)
            source.close()
        }
    }
}
