package com.badnng.moe.notifications

import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Offline decode of the pinned Telegram Desktop msg_incoming.mp3; see third_party/telegram-sounds. */
internal class OfficialNotificationPcm(private val openResource: () -> InputStream?) {
    private val original: ByteArray by lazy {
        val bytes = checkNotNull(openResource()) { "The bundled notification recording is missing." }.use {
            it.readNBytes(PCM_BYTES + 1)
        }
        check(bytes.size == PCM_BYTES && sha256(bytes) == PCM_SHA256) {
            "The bundled notification recording does not match the pinned source."
        }
        bytes
    }

    /** One private immutable recording, one bounded owned output buffer; never changes timing or channels. */
    fun samples(volumePercent: Int): ByteArray {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume == 0) return ByteArray(PCM_BYTES)
        val source = original
        if (volume == 100) return source.copyOf()
        return ByteArray(source.size).also { output ->
            for (offset in source.indices step 2) {
                val sample = ((source[offset].toInt() and 255) or (source[offset + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * volume / 100.0).roundToInt()
                output[offset] = scaled.toByte()
                output[offset + 1] = (scaled shr 8).toByte()
            }
        }
    }

    private companion object {
        // 65,603 frames, 48 kHz, signed 16-bit little-endian stereo (about 1.36673 seconds).
        const val PCM_BYTES = 262_412
        const val PCM_SHA256 = "b85015e0270d12da67eedac5a8812056bee0b60e34392fa7b9f6985e3c43dfda"
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}

private val officialDefaultNotificationPcm = OfficialNotificationPcm {
    OfficialNotificationPcm::class.java.getResourceAsStream("/telegram-sounds/msg_incoming.s16le")
}

/** Preserves the existing WinMM format; no network, decoder process, Java Sound or device is opened here. */
internal fun deviceDefaultNotificationPcm(volumePercent: Int): ByteArray =
    officialDefaultNotificationPcm.samples(volumePercent)
