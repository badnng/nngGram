package com.badnng.moe.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

internal interface VoicePcmInput : AutoCloseable {
    fun read(destination: ByteArray): Int
    fun stop()
}

internal fun interface WaveInDriver {
    fun call(name: String, vararg arguments: Any?): Int
}

/** Bounded, polling waveIn buffers avoid a callback pointer or Java Sound in Native Image. */
internal class WinMmPcmInput(private val driver: WaveInDriver = NativeWaveInDriver()) : VoicePcmInput {
    private val guard = Any()
    private val handle: Pointer
    private var stopped = false
    private var closed = false
    private var nextReadIndex = 0
    private val buffers = mutableListOf<Buffer>()

    init {
        require(Native.POINTER_SIZE == 8)
        Memory(18).use { format ->
            format.clear()
            format.setShort(0, 1)
            format.setShort(2, 1)
            format.setInt(4, 48_000)
            format.setInt(8, 96_000)
            format.setShort(12, 2)
            format.setShort(14, 16)
            Memory(8).use { result ->
                check(driver.call("waveInOpen", result, -1, format, Pointer.NULL, Pointer.NULL, 0) == 0)
                handle = checkNotNull(result.getPointer(0))
            }
        }
        try {
            repeat(4) {
                val buffer = Buffer()
                buffers += buffer
                buffer.header.clear()
                buffer.header.setPointer(0, buffer.data)
                buffer.header.setInt(8, BLOCK_BYTES)
                check(driver.call("waveInPrepareHeader", handle, buffer.header, HEADER_BYTES) == 0)
                buffer.prepared = true
                queue(buffer)
            }
            check(driver.call("waveInStart", handle) == 0)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun read(destination: ByteArray): Int = synchronized(guard) {
        if (closed) return@synchronized 0
        repeat(buffers.size) {
            val buffer = buffers[nextReadIndex]
            if (!buffer.queued) { nextReadIndex = (nextReadIndex + 1) % buffers.size; return@repeat }
            if (buffer.header.getInt(24) and 1 == 0) return@synchronized 0
            val count = buffer.header.getInt(12)
            check(count in 0..BLOCK_BYTES && count % 2 == 0 && destination.size >= count)
            if (count > 0) buffer.data.read(0, destination, 0, count)
            buffer.queued = false
            nextReadIndex = (nextReadIndex + 1) % buffers.size
            if (!stopped) queue(buffer)
            if (count > 0) return@synchronized count
        }
        0
    }

    private fun queue(buffer: Buffer) {
        check(driver.call("waveInAddBuffer", handle, buffer.header, HEADER_BYTES) == 0)
        buffer.queued = true
    }

    override fun stop() = synchronized(guard) {
        if (stopped || closed) return@synchronized
        check(driver.call("waveInStop", handle) == 0)
        check(driver.call("waveInReset", handle) == 0)
        stopped = true
    }

    override fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        try {
            // Even a faulty reset should first stop capture. Keep native pointers owned if
            // reset/unprepare cannot prove that the driver has returned the buffers.
            runCatching { driver.call("waveInStop", handle) }
            check(driver.call("waveInReset", handle) == 0)
            buffers.filter { it.prepared }.forEach {
                check(driver.call("waveInUnprepareHeader", handle, it.header, HEADER_BYTES) == 0)
                it.prepared = false
            }
            check(driver.call("waveInClose", handle) == 0)
            buffers.forEach { it.header.close(); it.data.close() }
        } catch (failure: Throwable) {
            FailedVoiceInputs.retain(this)
            throw failure
        }
    }

    internal fun retainedForNativeSafety(): Boolean = FailedVoiceInputs.contains(this)

    private class Buffer {
        val data = Memory(BLOCK_BYTES.toLong())
        val header = Memory(HEADER_BYTES.toLong())
        var prepared = false
        var queued = false
    }
    companion object { const val BLOCK_BYTES = 1_920; const val HEADER_BYTES = 48 }
}

private object FailedVoiceInputs {
    private val inputs = mutableSetOf<WinMmPcmInput>()
    @Synchronized fun retain(input: WinMmPcmInput) { inputs += input }
    @Synchronized fun contains(input: WinMmPcmInput) = input in inputs
}

private class NativeWaveInDriver : WaveInDriver {
    private val api = NativeLibrary.getInstance("winmm")
    override fun call(name: String, vararg arguments: Any?): Int =
        api.getFunction(name, Function.ALT_CONVENTION).invokeInt(arguments)
}
