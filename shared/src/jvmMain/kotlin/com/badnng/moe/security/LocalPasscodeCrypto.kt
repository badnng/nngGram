package com.badnng.moe.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * This is a synchronous primitive, not a lock controller or a persistence API.
 * The owner must call it on a bounded single-flight worker, retain the root lease,
 * validate the independently supplied identity/generation, and discard stale results.
 * The caller retains and must clear its input CharArray after the call. Inputs must
 * not be mutated concurrently. No method reads disk or creates persistent state.
 */
internal object LocalPasscodeCrypto {
    private val core = LocalPasscodeCryptoCore.production()

    fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> =
        core.create(password, rootIdentity, generation)

    fun open(
        password: CharArray,
        record: ByteArray,
        expectedRootIdentity: ByteArray,
        expectedGeneration: Long,
    ): LocalCryptoResult<LocalDatabaseKey> = core.open(password, record, expectedRootIdentity, expectedGeneration)

    fun rewrap(
        password: CharArray,
        key: LocalDatabaseKey,
        rootIdentity: ByteArray,
        generation: Long,
    ): LocalCryptoResult<ByteArray> = core.rewrap(password, key, rootIdentity, generation)
}

internal sealed interface LocalCryptoResult<out T> {
    class Success<T>(val value: T) : LocalCryptoResult<T> {
        override fun toString(): String = "LocalCryptoResult.Success[redacted]"
    }

    data object InvalidInput : LocalCryptoResult<Nothing>
    data object InvalidPassword : LocalCryptoResult<Nothing>
    data object CorruptRecord : LocalCryptoResult<Nothing>
    data object UnsupportedFormat : LocalCryptoResult<Nothing>
    data object UnsupportedParameters : LocalCryptoResult<Nothing>
    data object BindingMismatch : LocalCryptoResult<Nothing>
    data object AuthenticationFailed : LocalCryptoResult<Nothing>
    data object KeyUnavailable : LocalCryptoResult<Nothing>
    data object CryptoUnavailable : LocalCryptoResult<Nothing>
    data object ResourceUnavailable : LocalCryptoResult<Nothing>
}

internal class WrappedDatabaseKey(val record: ByteArray, val key: LocalDatabaseKey) {
    override fun toString(): String = "WrappedDatabaseKey[redacted]"
}

/** Short-lived owner; borrowing makes a temporary copy which is cleared after use. */
internal class LocalDatabaseKey private constructor(private var bytes: ByteArray?) : AutoCloseable {
    internal fun <T> borrow(block: (ByteArray) -> LocalCryptoResult<T>): LocalCryptoResult<T> {
        val copy = synchronized(this) { bytes?.copyOf() } ?: return LocalCryptoResult.KeyUnavailable
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun close() {
        synchronized(this) {
            bytes?.fill(0)
            bytes = null
        }
    }

    override fun toString(): String = "LocalDatabaseKey[redacted]"

    internal companion object {
        fun copyOf(bytes: ByteArray): LocalDatabaseKey {
            require(bytes.size == LocalEnvelopeFormat.KEY_BYTES)
            return LocalDatabaseKey(bytes.copyOf())
        }
    }
}

/* Injectable only at this internal boundary for synthetic tests. Production uses the object above. */
internal fun interface PasscodeKdf {
    fun derive(passwordUtf8: ByteArray, salt: ByteArray): ByteArray
}

internal fun interface CryptoEntropy {
    fun fill(bytes: ByteArray)
}

private object SystemEntropy : CryptoEntropy {
    override fun fill(bytes: ByteArray) = SecureRandom().nextBytes(bytes)
}

private object ProductionArgon2id : PasscodeKdf {
    override fun derive(passwordUtf8: ByteArray, salt: ByteArray): ByteArray {
        val result = ByteArray(LocalEnvelopeFormat.KEY_BYTES)
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(LocalEnvelopeFormat.MEMORY_KIB)
            .withIterations(LocalEnvelopeFormat.ITERATIONS)
            .withParallelism(LocalEnvelopeFormat.LANES)
            .withSalt(salt)
        var params: Argon2Parameters? = null
        var complete = false
        try {
            params = builder.build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            generator.generateBytes(passwordUtf8, result)
            complete = true
            return result
        } finally {
            builder.clear()
            params?.clear()
            if (!complete) result.fill(0)
        }
    }
}

/* All integer fields are big-endian. Exactly one version/algorithm/cost is supported. */
internal object LocalEnvelopeFormat {
    const val KEY_BYTES = 32
    const val ROOT_BYTES = 32
    const val SALT_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 110
    const val RECORD_BYTES = HEADER_BYTES + KEY_BYTES + TAG_BYTES
    const val MAX_PASSWORD_BYTES = 1024
    const val MEMORY_KIB = 65_536
    const val ITERATIONS = 8
    const val LANES = 1
    const val GENERATION_OFFSET = 24
    const val ROOT_OFFSET = 32
    const val SALT_OFFSET = 64
    const val NONCE_OFFSET = 96

    val magic: ByteArray get() = byteArrayOf(0x4e, 0x4e, 0x47, 0x4c, 0x4f, 0x43, 0x4b, 0x31)
    val purpose: ByteArray get() = "nngGram/local-database-key-wrap/v1\u0000".toByteArray(StandardCharsets.US_ASCII)
}

internal class LocalPasscodeCryptoCore private constructor(
    private val kdf: PasscodeKdf,
    private val entropy: CryptoEntropy,
) {
    companion object {
        internal fun production(): LocalPasscodeCryptoCore = LocalPasscodeCryptoCore(ProductionArgon2id, SystemEntropy)

        // This explicit seam is for synthetic tests only; it is never a production default.
        internal fun forTesting(kdf: PasscodeKdf, entropy: CryptoEntropy): LocalPasscodeCryptoCore =
            LocalPasscodeCryptoCore(kdf, entropy)
    }

    fun create(password: CharArray, rootIdentity: ByteArray, generation: Long): LocalCryptoResult<WrappedDatabaseKey> =
        attempt {
            if (!validBinding(rootIdentity, generation)) return@attempt LocalCryptoResult.InvalidInput
            val databaseKey = ByteArray(LocalEnvelopeFormat.KEY_BYTES)
            try {
                entropy.fill(databaseKey)
                when (val wrapped = seal(password, databaseKey, rootIdentity.copyOf(), generation)) {
                    is LocalCryptoResult.Success -> LocalCryptoResult.Success(
                        WrappedDatabaseKey(wrapped.value, LocalDatabaseKey.copyOf(databaseKey)),
                    )
                    else -> wrapped.failure()
                }
            } finally {
                databaseKey.fill(0)
            }
        }

    fun rewrap(
        password: CharArray,
        key: LocalDatabaseKey,
        rootIdentity: ByteArray,
        generation: Long,
    ): LocalCryptoResult<ByteArray> = attempt {
        if (!validBinding(rootIdentity, generation)) return@attempt LocalCryptoResult.InvalidInput
        key.borrow { seal(password, it, rootIdentity.copyOf(), generation) }
    }

    fun open(
        password: CharArray,
        record: ByteArray,
        expectedRootIdentity: ByteArray,
        expectedGeneration: Long,
    ): LocalCryptoResult<LocalDatabaseKey> = attempt {
        if (!validBinding(expectedRootIdentity, expectedGeneration)) return@attempt LocalCryptoResult.InvalidInput
        // Reject before copying. The file reader must itself enforce the same bound while reading.
        if (record.size != LocalEnvelopeFormat.RECORD_BYTES) return@attempt LocalCryptoResult.CorruptRecord
        val snapshot = record.copyOf()
        val root = expectedRootIdentity.copyOf()
        when (val parsed = parse(snapshot, root, expectedGeneration)) {
            is LocalCryptoResult.Success -> withPassword(password) { passwordBytes ->
                val key = kdf.derive(passwordBytes, parsed.value.salt)
                try {
                    if (key.size != LocalEnvelopeFormat.KEY_BYTES) return@withPassword LocalCryptoResult.CryptoUnavailable
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parsed.value.nonce))
                    cipher.updateAAD(LocalEnvelopeFormat.purpose)
                    cipher.updateAAD(snapshot, 0, LocalEnvelopeFormat.HEADER_BYTES)
                    val plain = try {
                        cipher.doFinal(snapshot, LocalEnvelopeFormat.HEADER_BYTES, LocalEnvelopeFormat.KEY_BYTES + LocalEnvelopeFormat.TAG_BYTES)
                    } catch (_: BadPaddingException) {
                        return@withPassword LocalCryptoResult.AuthenticationFailed
                    }
                    try {
                        if (plain.size != LocalEnvelopeFormat.KEY_BYTES) LocalCryptoResult.CryptoUnavailable
                        else LocalCryptoResult.Success(LocalDatabaseKey.copyOf(plain))
                    } finally {
                        plain.fill(0)
                    }
                } finally {
                    key.fill(0)
                }
            }
            else -> parsed.failure()
        }
    }

    private fun seal(
        password: CharArray,
        databaseKey: ByteArray,
        rootIdentity: ByteArray,
        generation: Long,
    ): LocalCryptoResult<ByteArray> = withPassword(password) { passwordBytes ->
        val salt = ByteArray(LocalEnvelopeFormat.SALT_BYTES)
        val nonce = ByteArray(LocalEnvelopeFormat.NONCE_BYTES)
        entropy.fill(salt)
        entropy.fill(nonce)
        val header = header(rootIdentity, generation, salt, nonce)
        val key = kdf.derive(passwordBytes, salt)
        try {
            if (key.size != LocalEnvelopeFormat.KEY_BYTES) return@withPassword LocalCryptoResult.CryptoUnavailable
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(LocalEnvelopeFormat.purpose)
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(databaseKey)
            if (ciphertext.size != LocalEnvelopeFormat.KEY_BYTES + LocalEnvelopeFormat.TAG_BYTES) {
                return@withPassword LocalCryptoResult.CryptoUnavailable
            }
            LocalCryptoResult.Success(header + ciphertext)
        } finally {
            key.fill(0)
        }
    }

    private fun header(root: ByteArray, generation: Long, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(LocalEnvelopeFormat.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(LocalEnvelopeFormat.magic)
            put(1) // envelope version
            put(1) // Argon2id v1.3
            put(1) // AES-256-GCM
            put(0) // reserved flags
            putInt(LocalEnvelopeFormat.MEMORY_KIB)
            putInt(LocalEnvelopeFormat.ITERATIONS)
            putInt(LocalEnvelopeFormat.LANES)
            putLong(generation)
            put(root)
            put(salt)
            put(nonce)
            putShort((LocalEnvelopeFormat.KEY_BYTES + LocalEnvelopeFormat.TAG_BYTES).toShort())
        }.array()

    private class Parsed(val salt: ByteArray, val nonce: ByteArray)

    private fun parse(record: ByteArray, root: ByteArray, generation: Long): LocalCryptoResult<Parsed> {
        val input = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(8).also { input.get(it) }
        if (!magic.contentEquals(LocalEnvelopeFormat.magic)) return LocalCryptoResult.CorruptRecord
        val version = input.get().toInt() and 0xff
        val kdfId = input.get().toInt() and 0xff
        val aeadId = input.get().toInt() and 0xff
        if (version != 1 || kdfId != 1 || aeadId != 1) return LocalCryptoResult.UnsupportedFormat
        if (input.get().toInt() != 0) return LocalCryptoResult.UnsupportedFormat
        val memory = input.int
        val iterations = input.int
        val lanes = input.int
        if (memory != LocalEnvelopeFormat.MEMORY_KIB || iterations != LocalEnvelopeFormat.ITERATIONS || lanes != LocalEnvelopeFormat.LANES) {
            return LocalCryptoResult.UnsupportedParameters
        }
        val recordedGeneration = input.long
        if (recordedGeneration <= 0) return LocalCryptoResult.CorruptRecord
        val recordedRoot = ByteArray(LocalEnvelopeFormat.ROOT_BYTES).also { input.get(it) }
        val salt = ByteArray(LocalEnvelopeFormat.SALT_BYTES).also { input.get(it) }
        val nonce = ByteArray(LocalEnvelopeFormat.NONCE_BYTES).also { input.get(it) }
        val ciphertextLength = input.short.toInt() and 0xffff
        if (ciphertextLength != LocalEnvelopeFormat.KEY_BYTES + LocalEnvelopeFormat.TAG_BYTES) return LocalCryptoResult.CorruptRecord
        if (recordedGeneration != generation || !MessageDigest.isEqual(recordedRoot, root)) return LocalCryptoResult.BindingMismatch
        return LocalCryptoResult.Success(Parsed(salt, nonce))
    }

    private fun validBinding(root: ByteArray, generation: Long): Boolean =
        root.size == LocalEnvelopeFormat.ROOT_BYTES && generation > 0

    private inline fun <T> withPassword(password: CharArray, block: (ByteArray) -> LocalCryptoResult<T>): LocalCryptoResult<T> {
        if (password.isEmpty() || password.size > LocalEnvelopeFormat.MAX_PASSWORD_BYTES) return LocalCryptoResult.InvalidPassword
        val input = password.copyOf()
        try {
            val buffer = ByteArray(LocalEnvelopeFormat.MAX_PASSWORD_BYTES)
            try {
                val encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val output = ByteBuffer.wrap(buffer)
                val encoded = encoder.encode(CharBuffer.wrap(input), output, true)
                if (encoded.isError || encoded.isOverflow) return LocalCryptoResult.InvalidPassword
                val flushed = encoder.flush(output)
                if (flushed.isError || flushed.isOverflow || output.position() == 0) return LocalCryptoResult.InvalidPassword
                val bytes = buffer.copyOf(output.position())
                try {
                    return block(bytes)
                } finally {
                    bytes.fill(0)
                }
            } finally {
                buffer.fill(0)
            }
        } finally {
            input.fill('\u0000')
        }
    }

    private inline fun <T> attempt(block: () -> LocalCryptoResult<T>): LocalCryptoResult<T> = try {
        block()
    } catch (_: OutOfMemoryError) {
        LocalCryptoResult.ResourceUnavailable
    } catch (_: GeneralSecurityException) {
        LocalCryptoResult.CryptoUnavailable
    } catch (_: LinkageError) {
        LocalCryptoResult.CryptoUnavailable
    } catch (_: RuntimeException) {
        LocalCryptoResult.CryptoUnavailable
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> LocalCryptoResult<*>.failure(): LocalCryptoResult<T> = this as LocalCryptoResult<T>
}
