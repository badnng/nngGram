package com.badnng.moe.notifications

import kotlin.math.roundToInt

/** Canonical raw 48 kHz stereo signed-16 little-endian frames; no caller retains the backing array. */
internal class NotificationSoundPcm(samples: ByteArray) {
    private val original: ByteArray
    init {
        require(samples.isNotEmpty() && samples.size % FRAME_BYTES == 0 && samples.size <= MAX_BYTES)
        original = samples.copyOf()
    }

    val sizeBytes: Int get() = original.size
    val durationMillis: Long get() = original.size.toLong() / FRAME_BYTES * 1_000 / SAMPLE_RATE

    fun samples(volumePercent: Int): ByteArray {
        val volume = volumePercent.coerceIn(0, 100)
        if (volume == 100) return original.copyOf()
        val result = ByteArray(original.size)
        if (volume == 0) return result
        for (offset in original.indices step 2) {
            val value = ((original[offset].toInt() and 255) or (original[offset + 1].toInt() shl 8)).toShort().toInt()
            val scaled = (value * volume / 100.0).roundToInt()
            result[offset] = scaled.toByte()
            result[offset + 1] = (scaled shr 8).toByte()
        }
        return result
    }

    override fun toString() = "NotificationSoundPcm(sizeBytes=$sizeBytes)"

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_BYTES = 4
        const val MAX_DURATION_MILLIS = 30_000L
        const val BYTES_PER_MILLISECOND = 192
        const val MAX_BYTES = 5_760_000
    }
}
