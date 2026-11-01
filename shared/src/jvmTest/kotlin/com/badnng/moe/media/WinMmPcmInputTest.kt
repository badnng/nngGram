package com.badnng.moe.media

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Function
import kotlin.test.*

class WinMmPcmInputTest {
    @Test
    fun buffersStayInCaptureOrderEvenWhenTheFirstRequeuedBufferCompletesAgain() {
        val driver = FakeDriver()
        WinMmPcmInput(driver).use { input ->
            val block = ByteArray(WinMmPcmInput.BLOCK_BYTES)
            driver.complete(0, byteArrayOf(1, 0))
            driver.complete(1, byteArrayOf(2, 0))
            assertEquals(2, input.read(block))
            assertEquals(1, block[0].toInt())
            driver.complete(0, byteArrayOf(9, 0))
            assertEquals(2, input.read(block))
            assertEquals(2, block[0].toInt(), "An older completed buffer cannot be overtaken by a requeued one.")
        }
    }

    @Test
    fun windowsInputSymbolsAndWin64HeaderLayoutAreAvailableWithoutOpeningADevice() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        assertEquals(8, Native.POINTER_SIZE)
        val api = NativeLibrary.getInstance("winmm")
        listOf("waveInOpen", "waveInPrepareHeader", "waveInAddBuffer", "waveInStart", "waveInStop", "waveInReset",
            "waveInUnprepareHeader", "waveInClose").forEach {
            assertTrue(Pointer.nativeValue(api.getFunction(it, Function.ALT_CONVENTION)) != 0L)
        }
        val driver = FakeDriver()
        WinMmPcmInput(driver).use {
            val header = driver.headers.first() as Memory
            assertEquals(48L, header.size())
            assertTrue(Pointer.nativeValue(header.getPointer(0)) != 0L)
            assertEquals(WinMmPcmInput.BLOCK_BYTES, header.getInt(8))
            assertEquals(0, header.getInt(12))
            assertEquals(0L, header.getLong(16))
            assertEquals(0, header.getInt(24))
            assertEquals(0L, header.getLong(32))
            assertEquals(0L, header.getLong(40))
        }
    }

    @Test
    fun monoPcmComesFromCompletedNativeBuffersAndStopDrainsPartialData() {
        val driver = FakeDriver()
        val input = WinMmPcmInput(driver)
        val bytes = ByteArray(WinMmPcmInput.BLOCK_BYTES)
        assertEquals(0, input.read(bytes))
        val samples = byteArrayOf(1, 2, 3, 4, 5, 6)
        driver.complete(0, samples)
        assertEquals(samples.size, input.read(bytes))
        assertContentEquals(samples, bytes.take(samples.size).toByteArray())
        driver.complete(2, byteArrayOf(7, 8))
        input.stop()
        assertEquals(2, input.read(bytes), "Zero-length returned buffers must not hide later partial PCM.")
        assertContentEquals(byteArrayOf(7, 8), bytes.take(2).toByteArray())
        assertEquals(0, input.read(bytes))
        input.close()
        input.close()
        assertEquals(1, driver.calls.count { it == "waveInClose" })
        assertEquals(4, driver.calls.count { it == "waveInUnprepareHeader" })
    }

    @Test
    fun aDriverThatCannotReturnPointersKeepsItsBufferOwnerAlive() {
        val driver = FakeDriver(fail = "waveInReset")
        val input = WinMmPcmInput(driver)
        assertFailsWith<IllegalStateException> { input.close() }
        assertTrue(input.retainedForNativeSafety())
        assertTrue("waveInStop" in driver.calls)
        assertFalse("waveInClose" in driver.calls)
        assertEquals(WinMmPcmInput.BLOCK_BYTES, driver.headers.first().getInt(8))
    }

    @Test
    fun deviceRejectionDoesNotPrepareBuffers() {
        val driver = FakeDriver(fail = "waveInOpen")
        assertFailsWith<IllegalStateException> { WinMmPcmInput(driver) }
        assertEquals(listOf("waveInOpen"), driver.calls)
        assertTrue(driver.headers.isEmpty())
    }

    private class FakeDriver(private val fail: String? = null) : WaveInDriver {
        val calls = mutableListOf<String>()
        val headers = mutableListOf<Pointer>()
        fun complete(index: Int, bytes: ByteArray) {
            val header = headers[index]
            header.getPointer(0).write(0, bytes, 0, bytes.size)
            header.setInt(12, bytes.size)
            header.setInt(24, 1)
        }
        override fun call(name: String, vararg arguments: Any?): Int {
            calls += name
            if (name == fail) return 1
            when (name) {
                "waveInOpen" -> {
                    val format = arguments[2] as Memory
                    assertEquals(1, format.getShort(2).toInt())
                    assertEquals(48_000, format.getInt(4))
                    assertEquals(96_000, format.getInt(8))
                    assertEquals(2, format.getShort(12).toInt())
                    (arguments[0] as Memory).setPointer(0, Pointer(1))
                }
                "waveInPrepareHeader" -> headers += arguments[1] as Pointer
                "waveInAddBuffer" -> {
                    (arguments[1] as Pointer).setInt(12, 0)
                    (arguments[1] as Pointer).setInt(24, 0)
                }
                "waveInReset" -> headers.forEach { it.setInt(24, 1) }
            }
            return 0
        }
    }
}
