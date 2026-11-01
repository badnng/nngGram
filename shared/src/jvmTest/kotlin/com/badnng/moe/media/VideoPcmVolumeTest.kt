package com.badnng.moe.media

import kotlin.test.*

class VideoPcmVolumeTest {
    @Test
    fun attenuationPreservesSignedLittleEndianSamplesIncludingMinimumValue() {
        val bytes = pcm(-32768, -30000, -3, 0, 3, 30000, 32767)
        scalePcmVolume(bytes, 0.5f)
        assertEquals(listOf(-16384, -15000, -1, 0, 1, 15000, 16383), samples(bytes))
    }

    @Test
    fun gainIsClampedToSilenceOrUnityWithoutAmplification() {
        val original = pcm(-32768, -1, 0, 1, 32767)
        for (gain in listOf(0f, -1f)) {
            val bytes = original.copyOf()
            scalePcmVolume(bytes, gain)
            assertEquals(List(5) { 0 }, samples(bytes))
        }
        for (gain in listOf(1f, 2f)) {
            val bytes = original.copyOf()
            scalePcmVolume(bytes, gain)
            assertContentEquals(original, bytes)
        }
    }

    @Test
    fun emptyBuffersAndIncompleteTrailingSamplesAreSafe() {
        scalePcmVolume(byteArrayOf(), 0f)
        val bytes = pcm(100, -100) + byteArrayOf(0x5a)
        scalePcmVolume(bytes, 0f)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0x5a), bytes)
    }

    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun samples(bytes: ByteArray) = (0 until bytes.size - 1 step 2).map { index ->
        ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
    }
}
