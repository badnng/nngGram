package com.badnng.moe.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Native Windows PCM output; avoids loading Java Sound or an AWT toolkit in Native Image. */
internal class WinMmAudioOutput(private val driver: WaveOutDriver = NativeWaveOutDriver()) : VideoAudioOutput {
    private val guard = Any()
    private val handle: Pointer
    private var closed = false
    private val buffers = mutableListOf<Buffer>() // Four 10 ms blocks bound seek/stop latency.

    init {
        require(Native.POINTER_SIZE == 8) { "The media bundle requires Windows x64." }
        Memory(18).use { format ->
            format.clear()
            format.setShort(0, 1) // WAVE_FORMAT_PCM
            format.setShort(2, 2)
            format.setInt(4, 48_000)
            format.setInt(8, 192_000)
            format.setShort(12, 4)
            format.setShort(14, 16)
            Memory(8).use { result ->
                check(driver.call("waveOutOpen", result, -1, format, Pointer.NULL, Pointer.NULL, 0) == 0)
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
                check(driver.call("waveOutPrepareHeader", handle, buffer.header, HEADER_BYTES) == 0)
                buffer.prepared = true
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun start() = Unit

    override fun write(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size && length % 4 == 0)
        var offset = 0
        while (offset < length) {
            val written = synchronized(guard) {
                if (closed) return
                val buffer = buffers.firstOrNull { !it.queued || it.done() }
                if (buffer == null) 0 else {
                    val count = minOf(BLOCK_BYTES, length - offset)
                    buffer.data.write(0, bytes, offset, count)
                    buffer.header.setInt(8, count)
                    check(driver.call("waveOutWrite", handle, buffer.header, HEADER_BYTES) == 0)
                    buffer.queued = true
                    count
                }
            }
            if (written == 0) Thread.sleep(2) else offset += written
        }
    }

    override fun drain() {
        while (synchronized(guard) { !closed && buffers.any { it.queued && !it.done() } }) Thread.sleep(2)
    }

    override fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        try {
            // Reset returns queued buffers before their backing memory is released.
            check(driver.call("waveOutReset", handle) == 0)
            buffers.filter { it.prepared }.forEach {
                check(driver.call("waveOutUnprepareHeader", handle, it.header, HEADER_BYTES) == 0)
                it.prepared = false
            }
            check(driver.call("waveOutClose", handle) == 0)
            buffers.forEach { it.header.close(); it.data.close() }
        } catch (failure: Throwable) {
            // A faulty/disconnected driver may still retain prepared/queued pointers. The player
            // drops failed sinks, so retain the owner for process lifetime rather than letting
            // JNA's Cleaner free memory the native device could still dereference.
            FailedAudioDevices.retain(this)
            throw failure
        }
        // NativeLibrary is cached/shared by JNA; do not close the process-wide library here.
    }

    internal fun retainedForNativeSafety(): Boolean = FailedAudioDevices.contains(this)

    private class Buffer {
        val data = Memory(BLOCK_BYTES.toLong())
        val header = Memory(HEADER_BYTES.toLong())
        var prepared = false
        var queued = false
        fun done() = header.getInt(24) and 1 != 0 // WAVEHDR.dwFlags & WHDR_DONE (Win64 layout).
    }

    private companion object {
        const val BLOCK_BYTES = 1_920
        const val HEADER_BYTES = 48
    }
}

private object FailedAudioDevices {
    private val retained = mutableSetOf<WinMmAudioOutput>()
    @Synchronized fun retain(output: WinMmAudioOutput) { retained += output }
    @Synchronized fun contains(output: WinMmAudioOutput) = output in retained
}

/** Injectable boundary keeps tests independent of the system audio device. */
internal fun interface WaveOutDriver {
    fun call(name: String, vararg arguments: Any?): Int
}

private class NativeWaveOutDriver : WaveOutDriver {
    private val api = NativeLibrary.getInstance("winmm")
    override fun call(name: String, vararg arguments: Any?): Int =
        api.getFunction(name, Function.ALT_CONVENTION).invokeInt(arguments)
}
