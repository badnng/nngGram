package com.badnng.moe.media

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WinMmAudioOutputTest {
    @Test
    fun pcmWritesPreserveSamplesAndReleaseBuffersAfterReset() {
        val driver = FakeDriver()
        val output = WinMmAudioOutput(driver)
        val samples = ByteArray(1_920) { (it % 251).toByte() }
        output.start()
        output.write(samples, samples.size)
        assertContentEquals(samples, driver.samples.single())
        output.close()
        output.close()
        assertEquals(listOf("waveOutReset") + List(4) { "waveOutUnprepareHeader" } + "waveOutClose",
            driver.operations.takeLast(6))
    }

    @Test
    fun boundedAudioQueueUnblocksWhenPlaybackIsClosed() {
        val driver = FakeDriver()
        val output = WinMmAudioOutput(driver)
        val completed = CountDownLatch(1)
        val writer = thread(isDaemon = true) {
            try { output.write(ByteArray(1_920 * 8), 1_920 * 8) }
            finally { completed.countDown() }
        }
        assertTrue(driver.fullQueue.await(2, TimeUnit.SECONDS))
        assertFalse(completed.await(30, TimeUnit.MILLISECONDS))
        output.close()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        writer.join(2_000)
        assertEquals(4, driver.samples.size)
    }

    @Test
    fun resetFailureRetainsNativeBuffersAfterThePlayerDropsTheSink() {
        val driver = FakeDriver(fail = "waveOutReset")
        val output = WinMmAudioOutput(driver)
        output.write(ByteArray(1_920), 1_920)
        assertFailsWith<IllegalStateException> { output.close() }
        assertTrue(output.retainedForNativeSafety())
        assertEquals("waveOutReset", driver.operations.last())
        assertEquals(1_920, driver.headers.single { it.getInt(24) == 0 }.getInt(8))
    }

    @Test
    fun unprepareFailureRetainsMemoryAndDoesNotCloseTheNativeDevice() {
        val driver = FakeDriver(fail = "waveOutUnprepareHeader", failOccurrence = 2)
        val output = WinMmAudioOutput(driver)
        assertFailsWith<IllegalStateException> { output.close() }
        assertTrue(output.retainedForNativeSafety())
        assertFalse("waveOutClose" in driver.operations)
        assertEquals(1_920, driver.headers.first().getInt(8))
    }

    @Test
    fun openFailureDoesNotAllocateOrPrepareDeviceBuffers() {
        val driver = FakeDriver(fail = "waveOutOpen")
        assertFailsWith<IllegalStateException> { WinMmAudioOutput(driver) }
        assertEquals(listOf("waveOutOpen"), driver.operations)
        assertTrue(driver.headers.isEmpty())
    }

    private class FakeDriver(val fail: String? = null, val failOccurrence: Int = 1) : WaveOutDriver {
        val operations = mutableListOf<String>()
        val samples = mutableListOf<ByteArray>()
        val fullQueue = CountDownLatch(1)
        val headers = mutableListOf<Pointer>()

        override fun call(name: String, vararg arguments: Any?): Int {
            operations += name
            if (name == fail && operations.count { it == name } == failOccurrence) return 1
            when (name) {
                "waveOutOpen" -> {
                    val format = arguments[2] as Memory
                    assertEquals(1, format.getShort(0).toInt())
                    assertEquals(2, format.getShort(2).toInt())
                    assertEquals(48_000, format.getInt(4))
                    assertEquals(192_000, format.getInt(8))
                    assertEquals(4, format.getShort(12).toInt())
                    assertEquals(16, format.getShort(14).toInt())
                    (arguments[0] as Memory).setPointer(0, Pointer(1))
                }
                "waveOutPrepareHeader" -> {
                    assertEquals(48, arguments[2])
                    headers += arguments[1] as Pointer
                    (arguments[1] as Pointer).setInt(24, 1)
                }
                "waveOutWrite" -> {
                    val header = arguments[1] as Pointer
                    header.setInt(24, 0)
                    samples += header.getPointer(0).getByteArray(0, header.getInt(8))
                    if (samples.size == 4) fullQueue.countDown()
                }
                "waveOutReset" -> headers.forEach { it.setInt(24, 1) }
            }
            return 0
        }
    }
}
