package com.badnng.moe.media

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A private loopback byte-range bridge. Only TDLib-confirmed cached bytes reach FFmpeg. */
internal class ProgressiveVideoSource(
    val size: Long,
    private val reader: MediaRangeReader,
    private val currentSize: () -> Long = { size },
) : AutoCloseable {
    constructor(size: Long, readPart: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit) : this(size, MediaRangeReader(readPart))
    init { require(size in 0..AttachmentFiles.MAX_BYTES) }
    private val closed = AtomicBoolean()
    private val leaseLock = Any()
    private val children = mutableMapOf<Socket, MediaRangeReader>()
    private var readerEpoch = 0L
    private val probeOpen = AtomicBoolean(true)
    val isOpen: Boolean get() = !closed.get()
    @Volatile var lastProgressNanos: Long = System.nanoTime()
        private set
    private val waiting = AtomicInteger()
    private val mutableBuffering = MutableStateFlow(false)
    val buffering = mutableBuffering.asStateFlow()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<ChatResult<ByteArray>>>()
    private val probeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val probePending = ConcurrentHashMap.newKeySet<CompletableFuture<ChatResult<ByteArray>>>()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val route = "/${UUID.randomUUID()}/video"
    val url: String get() = synchronized(leaseLock) { "http://127.0.0.1:${server.localPort}$route/$readerEpoch" }
    val probeUrl = "http://127.0.0.1:${server.localPort}$route/probe"
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "video-range-reader").apply { isDaemon = true } })

    init {
        Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                try { workers.execute { serve(socket) } }
                catch (_: RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        }, "video-range-server").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n") && header.length < 8192) {
                val value = input.read()
                if (value < 0) return
                header.append(value.toChar())
            }
            if (!header.endsWith("\r\n\r\n")) return
            val lines = header.toString().split("\r\n")
            val request = lines.first().split(' ')
            if (request.size != 3) { respond(socket, 404, "Not Found"); return }
            val isProbe = request[1].endsWith("/probe")
            val requestEpoch = if (!request[1].startsWith("$route/") || request[1] == "$route/probe") null
                else request[1].removePrefix("$route/").toLongOrNull()
            val validRoute = synchronized(leaseLock) { !closed.get() &&
                (isProbe && request[1] == "$route/probe" && probeOpen.get() || requestEpoch == readerEpoch) }
            if (!validRoute) { respond(socket, 404, "Not Found"); return }
            if (request[0] !in setOf("GET", "HEAD")) { respond(socket, 405, "Method Not Allowed"); return }
            val ranges = lines.filter { it.startsWith("Range:", ignoreCase = true) }
            val nativeSize = currentSize().takeIf { it in 1..AttachmentFiles.MAX_BYTES } ?: size
            val unknown = nativeSize == 0L
            val range = if (unknown) {
                if (ranges.isEmpty()) 0L..Long.MAX_VALUE else if (ranges.size == 1) parseUnknownRange(ranges.single().substringAfter(':').trim()) else null
            } else if (ranges.isEmpty()) 0L..nativeSize - 1 else if (ranges.size == 1) parseRange(ranges.single().substringAfter(':').trim(), nativeSize) else null
            if (range == null || unknown && range.last == Long.MAX_VALUE && range.first != 0L) {
                respond(socket, 416, "Range Not Satisfiable", "Content-Range: bytes */${if (unknown) "*" else nativeSize}\r\n"); return
            }
            val partial = ranges.isNotEmpty() && (!unknown || range.last != Long.MAX_VALUE)
            val output = socket.getOutputStream()
            val response = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\nConnection: close\r\n")
                if (!unknown) append("Accept-Ranges: bytes\r\n")
                if (range.last != Long.MAX_VALUE) append("Content-Length: ${range.last - range.first + 1}\r\n")
                if (partial) append("Content-Range: bytes ${range.first}-${range.last}/${if (unknown) "*" else nativeSize}\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            if (request[0] == "HEAD") return
            val connectionReader = synchronized(leaseLock) {
                if (closed.get() || socket.isClosed || isProbe && !probeOpen.get() || !isProbe && requestEpoch != readerEpoch) return
                (reader.fork?.invoke(isProbe) ?: reader).also {
                    children[socket] = it
                    if (isProbe) probeSockets.add(socket)
                }
            }
            val readPart: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit = { from, count, completed ->
                synchronized(leaseLock) {
                    if (closed.get() || socket.isClosed || children[socket] !== connectionReader ||
                        isProbe && !probeOpen.get() || !isProbe && requestEpoch != readerEpoch) completed(ChatResult(error = ChatError.NOT_READY))
                    else (if (isProbe) connectionReader.probe else connectionReader.read)(from, count, completed)
                }
            }
            var offset = range.first
            while (!closed.get() && offset <= range.last) {
                val count = if (range.last == Long.MAX_VALUE) 65_536 else minOf(65_536L, range.last - offset + 1).toInt()
                val bytes = awaitPart(offset, count, socket, readPart, isProbe)
                if (bytes.isEmpty()) break // Only native completed EOF is represented by an empty read.
                output.write(bytes)
                lastProgressNanos = System.nanoTime()
                offset += bytes.size
            }
            output.flush()
        } catch (_: Exception) {
            // Disconnects, seek cancellation and backend errors never log file paths or content.
        } finally {
            synchronized(leaseLock) {
                children.remove(socket)?.let { if (reader.fork != null) it.release() }
            }
            sockets.remove(socket)
            probeSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun awaitPart(offset: Long, count: Int, socket: Socket,
        readPart: (Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit, isProbe: Boolean): ByteArray {
        val deadline = System.nanoTime() + 15_000_000_000L
        var buffering = false
        try {
            while (!closed.get() && (!isProbe || probeOpen.get()) && !socket.isClosed && System.nanoTime() < deadline) {
                val future = CompletableFuture<ChatResult<ByteArray>>()
                pending.add(future)
                if (isProbe) probePending.add(future)
                val response = try {
                    readPart(offset, count) { future.complete(it) }
                    future.get(minOf(10_000_000_000L, (deadline - System.nanoTime()).coerceAtLeast(1)), TimeUnit.NANOSECONDS)
                } finally { pending.remove(future); probePending.remove(future) }
                val bytes = response.value
                if (response.error == null && bytes != null && bytes.size in 0..count) return bytes
                check(response.error == ChatError.NOT_READY)
                if (!buffering) {
                    buffering = true
                    waiting.incrementAndGet()
                    mutableBuffering.value = true
                }
                Thread.sleep(50)
            }
            error("Video buffering unavailable")
        } finally {
            if (buffering) mutableBuffering.value = waiting.decrementAndGet() > 0
        }
    }

    private fun respond(socket: Socket, code: Int, reason: String, extra: String = "") {
        socket.getOutputStream().write("HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        cancelReaders()
        workers.shutdownNow()
    }

    fun cancelReaders() {
        synchronized(leaseLock) {
            readerEpoch++
            pending.forEach { it.cancel(true) }
            sockets.forEach { runCatching { it.close() } }
            if (reader.fork != null) children.values.toList().forEach { it.release() }
            children.clear()
            reader.release()
        }
    }

    fun releaseProbe() {
        synchronized(leaseLock) {
            if (!probeOpen.compareAndSet(true, false)) return
            probePending.forEach { it.cancel(true) }
            probeSockets.forEach { socket ->
                runCatching { socket.close() }
                children.remove(socket)?.let { if (reader.fork != null) it.release() }
            }
            reader.releaseProbe()
        }
    }

    companion object {
        internal fun parseUnknownRange(value: String): LongRange? {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(value) ?: return null
            val start = match.groupValues[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val end = if (match.groupValues[2].isEmpty()) Long.MAX_VALUE else match.groupValues[2].toLongOrNull() ?: return null
            return if (end >= start && start < Long.MAX_VALUE) start..end else null
        }
        internal fun parseRange(value: String, size: Long): LongRange? {
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: return null
            val (left, right) = match.destructured
            if (size <= 0 || left.isEmpty() && right.isEmpty()) return null
            if (left.isEmpty()) {
                val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - minOf(suffix, size))..size - 1
            }
            val start = left.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val end = if (right.isEmpty()) size - 1 else right.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            return if (end >= start) start..end else null
        }
    }
}
