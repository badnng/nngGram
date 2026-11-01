package com.badnng.moe.notifications

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfficialNotificationPcmTest {
    @Test
    fun bundledSourceAndDecodedRecordingMatchThePinnedOfficialHashesAndCarryTheirLicense() {
        assertEquals("b042883d8264409fe024a8d533d26d1aef5919c1eb6b96093eb07f8c001edd85",
            sha256(resource("msg_incoming.mp3")))
        val pcm = resource("msg_incoming.s16le")
        assertEquals(262_412, pcm.size)
        assertEquals("b85015e0270d12da67eedac5a8812056bee0b60e34392fa7b9f6985e3c43dfda", sha256(pcm))
        assertContentEquals(pcm, deviceDefaultNotificationPcm(100), "The actual consumer must use the unchanged prepared recording.")
        assertTrue(resource("LEGAL").decodeToString().contains("2014-2026 The Telegram Desktop Authors"))
        assertTrue(resource("LICENSE").decodeToString().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(resource("SOURCE.md").decodeToString().contains("a7f453612f4896ac04d0007a36ab0d377e5bcbcf"))
    }

    @Test
    fun volumeOutputsHaveIndependentOwnershipAndTheRecordingIsLoadedOnlyOnce() {
        var opens = 0
        val prepared = resource("msg_incoming.s16le")
        val pcm = OfficialNotificationPcm { opens++; ByteArrayInputStream(prepared) }
        val first = pcm.samples(100)
        first.fill(0)
        assertContentEquals(prepared, pcm.samples(100))
        val partial = pcm.samples(37)
        assertContentEquals(deviceDefaultNotificationPcm(37), partial)
        partial.fill(0)
        assertContentEquals(deviceDefaultNotificationPcm(37), pcm.samples(37))
        assertEquals(1, opens)
    }

    @Test
    fun missingTruncatedChangedAndOversizedRecordingsNeverBecomeSyntheticFallbacks() {
        assertFailsWith<IllegalStateException> { OfficialNotificationPcm { null }.samples(100) }
        val original = resource("msg_incoming.s16le")
        val invalid = listOf(original.copyOf(original.size - 4),
            original.copyOf().apply { this[4] = (this[4].toInt() xor 1).toByte() },
            original.copyOf(original.size + 4))
        for (bytes in invalid) {
            var closed = false
            val pcm = OfficialNotificationPcm {
                object : ByteArrayInputStream(bytes) {
                    override fun close() { closed = true; super.close() }
                }
            }
            assertFailsWith<IllegalStateException> { pcm.samples(100) }
            assertTrue(closed)
        }
    }

    @Test
    fun silentSamplesDoNotReadTheResourceAndVolumeCannotAmplifyPastTheOriginal() {
        var opens = 0
        val original = resource("msg_incoming.s16le")
        val pcm = OfficialNotificationPcm { opens++; ByteArrayInputStream(original) }
        assertTrue(pcm.samples(0).all { it == 0.toByte() })
        assertTrue(pcm.samples(-100).all { it == 0.toByte() })
        assertEquals(0, opens)
        assertContentEquals(original, pcm.samples(Int.MAX_VALUE))
        assertEquals(1, opens)
    }

    private fun resource(file: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/telegram-sounds/$file")).use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}
