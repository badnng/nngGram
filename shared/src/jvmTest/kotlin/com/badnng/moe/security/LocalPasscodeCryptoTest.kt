package com.badnng.moe.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class LocalPasscodeCryptoTest {
    private val root = ByteArray(32) { (it + 1).toByte() }
    private val password = "  中文 e\u0301 🔒  ".toCharArray()

    @Test
    fun rfc9106Argon2idVectorTestsRealLibraryWithPublishedParameters() {
        // RFC 9106 section 5.3: Argon2id v1.3, m=32 KiB, t=3, p=4,
        // password=01*32, salt=02*16, secret=03*8, associated data=04*12.
        // This is a vector only; production uses 65536 KiB, t=8, p=1 and no Argon2 secret/AD.
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(32)
            .withIterations(3)
            .withParallelism(4)
            .withSalt(ByteArray(16) { 2 })
            .withSecret(ByteArray(8) { 3 })
            .withAdditional(ByteArray(12) { 4 })
        val parameters = builder.build()
        val output = ByteArray(32)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            generator.generateBytes(ByteArray(32) { 1 }, output)
            assertContentEquals(
                hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"),
                output,
            )
        } finally {
            output.fill(0)
            parameters.clear()
            builder.clear()
        }
    }

    @Test
    fun productionConfigurationRoundTripsThroughRealArgon2AndAesGcm() {
        val created = success(LocalPasscodeCrypto.create(password, root, 1))
        try {
            // Independently use the recorded salt with the pinned production settings;
            // a mutually consistent but accidentally weakened wrapper would not pass.
            val header = ByteBuffer.wrap(created.record).order(ByteOrder.BIG_ENDIAN)
            assertEquals(65_536, header.getInt(12))
            assertEquals(8, header.getInt(16))
            assertEquals(1, header.getInt(20))
            val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(65_536)
                .withIterations(8)
                .withParallelism(1)
                .withSalt(created.record.copyOfRange(64, 96))
            val parameters = builder.build()
            val wrappingKey = ByteArray(32)
            val syntheticPassword = String(password).toByteArray(StandardCharsets.UTF_8)
            try {
                val generator = Argon2BytesGenerator()
                generator.init(parameters)
                generator.generateBytes(syntheticPassword, wrappingKey)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, created.record.copyOfRange(96, 108)))
                cipher.updateAAD("nngGram/local-database-key-wrap/v1\u0000".toByteArray(StandardCharsets.US_ASCII))
                cipher.updateAAD(created.record.copyOfRange(0, 110))
                val independentlyOpened = cipher.doFinal(created.record.copyOfRange(110, 158))
                try {
                    success(created.key.borrow {
                        assertContentEquals(it, independentlyOpened)
                        LocalCryptoResult.Success(Unit)
                    })
                } finally {
                    independentlyOpened.fill(0)
                }
            } finally {
                wrappingKey.fill(0)
                syntheticPassword.fill(0)
                parameters.clear()
                builder.clear()
            }
            val opened = success(LocalPasscodeCrypto.open(password, created.record, root, 1))
            try {
                assertKeysEqual(created.key, opened)
            } finally {
                opened.close()
            }
        } finally {
            created.key.close()
        }
    }

    @Test
    fun roundTripPasswordBytesAndRewrapPreserveTheSameKey() {
        val fake = FakeKdf()
        val crypto = LocalPasscodeCryptoCore.forTesting(fake, CountingEntropy())
        val before = password.copyOf()
        val created = success(crypto.create(password, root, 5))
        try {
            assertContentEquals(before, password)
            val opened = success(crypto.open(password, created.record, root, 5))
            try {
                assertKeysEqual(created.key, opened)
            } finally {
                opened.close()
            }
            assertSame(LocalCryptoResult.AuthenticationFailed, crypto.open("中文 e\u0301 🔒".toCharArray(), created.record, root, 5))
            assertSame(LocalCryptoResult.AuthenticationFailed, crypto.open("  中文 é 🔒  ".toCharArray(), created.record, root, 5))

            val replacement = "新口令 🔑 ".toCharArray()
            val nextRecord = success(crypto.rewrap(replacement, created.key, root, 6))
            assertFalse(created.record.copyOfRange(64, 108).contentEquals(nextRecord.copyOfRange(64, 108)))
            assertSame(LocalCryptoResult.AuthenticationFailed, crypto.open(password, nextRecord, root, 6))
            val rewrapped = success(crypto.open(replacement, nextRecord, root, 6))
            try {
                assertKeysEqual(created.key, rewrapped)
            } finally {
                rewrapped.close()
            }
        } finally {
            created.key.close()
        }
    }

    @Test
    fun everyRecordBitIsRejectedWhenItsOriginalBindingIsExpected() {
        val crypto = LocalPasscodeCryptoCore.forTesting(FakeKdf(), CountingEntropy())
        val created = success(crypto.create(password, root, 7))
        try {
            assertEquals(158, created.record.size)
            for (index in created.record.indices) {
                for (bit in 0..7) {
                    val altered = created.record.copyOf()
                    altered[index] = (altered[index].toInt() xor (1 shl bit)).toByte()
                    val result = crypto.open(password, altered, root, 7)
                    assertFalse(result is LocalCryptoResult.Success, "record byte $index bit $bit accepted")
                }
            }
        } finally {
            created.key.close()
        }
    }

    @Test
    fun headerRootAndGenerationRemainAuthenticatedEvenWhenExpectedBindingIsAlsoChanged() {
        val crypto = LocalPasscodeCryptoCore.forTesting(FakeKdf(), CountingEntropy())
        val created = success(crypto.create(password, root, 1))
        try {
            val changedRoot = root.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val rootRecord = created.record.copyOf().also {
                it[LocalEnvelopeFormat.ROOT_OFFSET] = changedRoot[0]
            }
            assertSame(LocalCryptoResult.BindingMismatch, crypto.open(password, rootRecord, root, 1))
            assertSame(LocalCryptoResult.AuthenticationFailed, crypto.open(password, rootRecord, changedRoot, 1))

            val generationRecord = created.record.copyOf()
            ByteBuffer.wrap(generationRecord).order(ByteOrder.BIG_ENDIAN).putLong(LocalEnvelopeFormat.GENERATION_OFFSET, 2)
            assertSame(LocalCryptoResult.BindingMismatch, crypto.open(password, generationRecord, root, 1))
            assertSame(LocalCryptoResult.AuthenticationFailed, crypto.open(password, generationRecord, root, 2))
        } finally {
            created.key.close()
        }
    }

    @Test
    fun badLengthAlgorithmsFlagsAndCostsNeverInvokeKdf() {
        val fake = FakeKdf()
        val crypto = LocalPasscodeCryptoCore.forTesting(fake, CountingEntropy())
        val created = success(crypto.create(password, root, 1))
        try {
            fake.calls = 0
            for (length in 0 until created.record.size) {
                assertSame(LocalCryptoResult.CorruptRecord, crypto.open(password, created.record.copyOf(length), root, 1))
            }
            assertSame(LocalCryptoResult.CorruptRecord, crypto.open(password, created.record.copyOf(1_000_000), root, 1))
            for (offset in 8..11) {
                val changed = created.record.copyOf().also { it[offset] = 127 }
                assertSame(LocalCryptoResult.UnsupportedFormat, crypto.open(password, changed, root, 1))
            }
            for (offset in listOf(12, 16, 20)) {
                for (cost in listOf(-1, 0, 2, Int.MAX_VALUE)) {
                    val changed = created.record.copyOf()
                    ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN).putInt(offset, cost)
                    assertSame(LocalCryptoResult.UnsupportedParameters, crypto.open(password, changed, root, 1))
                }
            }
            val wrongLength = created.record.copyOf().also { it[109] = 47 }
            assertSame(LocalCryptoResult.CorruptRecord, crypto.open(password, wrongLength, root, 1))
            assertSame(LocalCryptoResult.BindingMismatch, crypto.open(password, created.record, ByteArray(32), 1))
            assertSame(LocalCryptoResult.BindingMismatch, crypto.open(password, created.record, root, 2))
            assertSame(LocalCryptoResult.InvalidInput, crypto.open(password, created.record, ByteArray(31), 1))
            assertSame(LocalCryptoResult.InvalidInput, crypto.open(password, created.record, root, 0))
            assertEquals(0, fake.calls)
        } finally {
            created.key.close()
        }
    }

    @Test
    fun passwordLimitsUseStrictUtf8AndRejectUnpairedSurrogates() {
        val fake = FakeKdf()
        val crypto = LocalPasscodeCryptoCore.forTesting(fake, CountingEntropy())
        val valid = success(crypto.create(CharArray(1024) { 'a' }, root, 1))
        valid.key.close()
        val emojiAtByteLimit = success(crypto.create("🔒".repeat(256).toCharArray(), root, 1))
        emojiAtByteLimit.key.close()
        fake.calls = 0
        val invalidPasswords = listOf(
            charArrayOf(),
            CharArray(1025) { 'a' },
            "中".repeat(342).toCharArray(),
            charArrayOf('\ud800'),
            charArrayOf('\udc00'),
        )
        for (invalid in invalidPasswords) {
            assertSame(LocalCryptoResult.InvalidPassword, crypto.create(invalid, root, 1))
        }
        assertEquals(0, fake.calls)
    }

    @Test
    fun controlledSecretCopiesAreClearedAfterSuccessFailureAndClose() {
        var passwordReference: ByteArray? = null
        var generatedKeyReference: ByteArray? = null
        var derivedKeyReference: ByteArray? = null
        val entropy = CryptoEntropy { bytes ->
            bytes.fill(65)
            if (generatedKeyReference == null) generatedKeyReference = bytes
        }
        val successfulKdf = PasscodeKdf { bytes, _ ->
            passwordReference = bytes
            ByteArray(32) { 66 }.also { derivedKeyReference = it }
        }
        val crypto = LocalPasscodeCryptoCore.forTesting(successfulKdf, entropy)
        val createdResult = crypto.create(password, root, 1)
        val created = success(createdResult)
        assertTrue(passwordReference!!.all { it == 0.toByte() })
        assertTrue(generatedKeyReference!!.all { it == 0.toByte() })
        assertTrue(derivedKeyReference!!.all { it == 0.toByte() })
        val secretString = "A".repeat(32)
        for (text in listOf(createdResult.toString(), created.toString(), created.key.toString())) {
            assertFalse(text.contains(secretString))
            assertFalse(text.contains(String(password)))
        }
        var borrowed: ByteArray? = null
        assertSame(LocalCryptoResult.InvalidInput, created.key.borrow {
            borrowed = it
            LocalCryptoResult.InvalidInput
        })
        assertTrue(borrowed!!.all { it == 0.toByte() })
        created.key.close()
        assertSame(LocalCryptoResult.KeyUnavailable, crypto.rewrap(password, created.key, root, 2))

        var failedPassword: ByteArray? = null
        var failedGeneratedKey: ByteArray? = null
        val failingEntropy = CryptoEntropy { bytes ->
            bytes.fill(67)
            if (failedGeneratedKey == null) failedGeneratedKey = bytes
        }
        val failingKdf = PasscodeKdf { bytes, _ ->
            failedPassword = bytes
            throw IllegalStateException("synthetic failure")
        }
        assertSame(LocalCryptoResult.CryptoUnavailable, LocalPasscodeCryptoCore.forTesting(failingKdf, failingEntropy).create(password, root, 1))
        assertTrue(failedPassword!!.all { it == 0.toByte() })
        assertTrue(failedGeneratedKey!!.all { it == 0.toByte() })

        var invalidOutput: ByteArray? = null
        val badOutputKdf = PasscodeKdf { _, _ -> ByteArray(31) { 68 }.also { invalidOutput = it } }
        assertSame(LocalCryptoResult.CryptoUnavailable, LocalPasscodeCryptoCore.forTesting(badOutputKdf, CountingEntropy()).create(password, root, 1))
        assertTrue(invalidOutput!!.all { it == 0.toByte() })
    }

    private class CountingEntropy : CryptoEntropy {
        private var next = 0
        override fun fill(bytes: ByteArray) {
            for (index in bytes.indices) bytes[index] = (++next).toByte()
        }
    }

    // Deliberate non-security fake: it only makes envelope failure/call-order tests fast.
    private class FakeKdf : PasscodeKdf {
        var calls = 0
        override fun derive(passwordUtf8: ByteArray, salt: ByteArray): ByteArray {
            calls++
            return MessageDigest.getInstance("SHA-256").run {
                update(passwordUtf8)
                digest(salt)
            }
        }
    }

    private fun assertKeysEqual(first: LocalDatabaseKey, second: LocalDatabaseKey) {
        val firstBytes = success(first.borrow { LocalCryptoResult.Success(it.copyOf()) })
        val secondBytes = success(second.borrow { LocalCryptoResult.Success(it.copyOf()) })
        try {
            assertEquals(32, firstBytes.size)
            assertContentEquals(firstBytes, secondBytes)
        } finally {
            firstBytes.fill(0)
            secondBytes.fill(0)
        }
    }

    private fun <T> success(result: LocalCryptoResult<T>): T = when (result) {
        is LocalCryptoResult.Success -> result.value
        else -> fail("Expected success, received $result")
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
