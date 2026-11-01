package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The only production writer to TDLib's public download slot. */
internal class OfficialTdDownloadNative(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : DownloadBrokerNative {
    override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) =
        request(TdApi.DownloadFile(fileId, priority, offset, limit, false), { value ->
            (value as? TdApi.File)?.takeIf { it.id == fileId }?.let { ChatResult(mapMediaFile(it)) } ?: failure(value)
        }, completed)
    override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) =
        request(TdApi.GetFile(fileId), { value ->
            (value as? TdApi.File)?.takeIf { it.id == fileId }?.let { ChatResult(mapMediaFile(it)) } ?: failure(value)
        }, completed)
    override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) =
        request(TdApi.GetFileDownloadedPrefixSize(fileId, offset), { value ->
            (value as? TdApi.FileDownloadedPrefixSize)?.size?.takeIf { it >= 0 }?.let { ChatResult(it) } ?: failure(value)
        }, completed)
    override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) =
        request(TdApi.ReadFilePart(fileId, offset, count.toLong()), { value ->
            (value as? TdApi.Data)?.data?.takeIf { it.size in 1..count }?.let { ChatResult(it) } ?: failure(value)
        }, completed)
    override fun ownedPrefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit, uncertain: () -> Unit) =
        requestOwned(TdApi.GetFileDownloadedPrefixSize(fileId, offset), { value ->
            (value as? TdApi.FileDownloadedPrefixSize)?.size?.takeIf { it >= 0 }?.let { ChatResult(it) } ?: failure(value)
        }, completed, uncertain)
    override fun ownedRead(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit,
        uncertain: () -> Unit,
    ) {
        if (count !in 1..65_536) { completed(ChatResult(error = ChatError.REQUEST_FAILED)); return }
        requestOwned(TdApi.ReadFilePart(fileId, offset, count.toLong()), { value ->
            (value as? TdApi.Data)?.data?.takeIf { it.size in 1..count }?.let { ChatResult(it) } ?: failure(value)
        }, completed, uncertain)
    }
    override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) =
        request(TdApi.CancelDownloadFile(fileId, false), { value -> if (value is TdApi.Ok) ChatResult(Unit) else failure(value) }, completed)
    private fun <T> request(function: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>, completed: (ChatResult<T>) -> Unit) {
        val once = AtomicBoolean()
        fun finish(value: ChatResult<T>) { if (once.compareAndSet(false, true)) completed(value) }
        try { send(function, { finish(runCatching { map(it) }.getOrElse { ChatResult(error = ChatError.REQUEST_FAILED) }) },
            { finish(ChatResult(error = ChatError.REQUEST_FAILED)) }) }
        catch (_: Exception) { finish(ChatResult(error = ChatError.REQUEST_FAILED)) }
    }
    /** A throw does not claim physical completion; a later real callback remains observable. */
    private fun <T> requestOwned(function: TdApi.Function<*>, map: (TdApi.Object) -> ChatResult<T>,
        completed: (ChatResult<T>) -> Unit, uncertain: () -> Unit,
    ) {
        val once = AtomicBoolean()
        fun finish(value: ChatResult<T>) { if (once.compareAndSet(false, true)) completed(value) }
        try {
            send(function, { finish(runCatching { map(it) }.getOrElse { ChatResult(error = ChatError.REQUEST_FAILED) }) },
                { finish(ChatResult(error = ChatError.REQUEST_FAILED)) })
        } catch (_: Exception) {
            if (!once.get()) runCatching { uncertain() }
        }
    }
    private fun <T> failure(value: TdApi.Object): ChatResult<T> = ChatResult(error = when ((value as? TdApi.Error)?.code) {
        403 -> ChatError.FORBIDDEN
        429 -> ChatError.RATE_LIMITED
        else -> ChatError.REQUEST_FAILED
    })
}

internal object DownloadBrokerScheduler {
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "native-download-broker").apply { isDaemon = true } }
    fun schedule(delay: Long, action: () -> Unit) { worker.schedule(action, delay, TimeUnit.MILLISECONDS) }
}
