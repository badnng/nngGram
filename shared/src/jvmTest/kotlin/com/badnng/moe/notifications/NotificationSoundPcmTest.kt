package com.badnng.moe.notifications

import kotlin.test.*

class NotificationSoundPcmTest {
    @Test fun sourceAndEveryVolumeOutputHaveIndependentOwnershipAndCannotAmplify() {
        val original = byteArrayOf(-1, 127, 0, -128)
        val pcm = NotificationSoundPcm(original)
        original.fill(0)
        assertContentEquals(byteArrayOf(-1, 127, 0, -128), pcm.samples(100))
        assertContentEquals(byteArrayOf(0, 64, 0, -64), pcm.samples(50))
        assertTrue(pcm.samples(-1).all { it == 0.toByte() })
        val output = pcm.samples(Int.MAX_VALUE)
        output.fill(0)
        assertContentEquals(byteArrayOf(-1, 127, 0, -128), pcm.samples(100))
    }

    @Test fun onlyBoundedCompleteCanonicalFramesAreAcceptedAndDurationUsesActualFrameCount() {
        for (size in listOf(0, 1, 2, 3, 5, NotificationSoundPcm.MAX_BYTES + 4)) {
            assertFailsWith<IllegalArgumentException> { NotificationSoundPcm(ByteArray(size)) }
        }
        val oneMillisecond = NotificationSoundPcm(ByteArray(192))
        assertEquals(192, oneMillisecond.sizeBytes)
        assertEquals(1L, oneMillisecond.durationMillis)
        val prepared = checkNotNull(javaClass.getResourceAsStream("/telegram-sounds/msg_incoming.s16le")).use { it.readBytes() }
        val realRecording = NotificationSoundPcm(prepared)
        assertEquals(262_412, realRecording.sizeBytes)
        assertEquals(1_366L, realRecording.durationMillis)
        assertContentEquals(deviceDefaultNotificationPcm(37), realRecording.samples(37))
    }
}
