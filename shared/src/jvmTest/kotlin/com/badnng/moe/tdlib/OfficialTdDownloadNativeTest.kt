package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdDownloadNativeTest {
    @Test fun commandsUseFalseAcceptanceAndPositiveFiniteNativeWindows() {
        val f = Fixture()
        var result: ChatResult<MediaFile>? = null
        f.native.download(7, 24, 100, 262144) { result = it }
        val command = assertIs<TdApi.DownloadFile>(f.command)
        assertFalse(command.synchronous)
        assertEquals(100L, command.offset)
        assertEquals(262144L, command.limit)
        f.reply(TdApi.File().apply { id = 7; local = TdApi.LocalFile() })
        assertEquals(7, result?.value?.id)
    }

    @Test fun cancellationErrorsAreNotInventedAcknowledgementsAndDuplicateResultsAreIgnored() {
        val f = Fixture()
        val results = mutableListOf<ChatResult<Unit>>()
        f.native.cancel(7, results::add)
        assertFalse(assertIs<TdApi.CancelDownloadFile>(f.command).onlyIfPending)
        f.reply(TdApi.Error(403, "private"))
        f.reply(TdApi.Ok())
        f.error()
        assertEquals(listOf(ChatResult<Unit>(error = ChatError.FORBIDDEN)), results)
    }

    @Test fun prefixAndReadResultsCannotUseSparseLengthOrOversizedNativeData() {
        val f = Fixture()
        var prefix: ChatResult<Long>? = null
        f.native.prefix(7, 100) { prefix = it }
        val command = assertIs<TdApi.GetFileDownloadedPrefixSize>(f.command)
        assertEquals(100L, command.offset)
        f.reply(TdApi.FileDownloadedPrefixSize(-1))
        assertEquals(ChatError.REQUEST_FAILED, prefix?.error)
        var read: ChatResult<ByteArray>? = null
        f.native.read(7, 100, 4) { read = it }
        assertEquals(4L, assertIs<TdApi.ReadFilePart>(f.command).count)
        f.reply(TdApi.Data(ByteArray(5)))
        assertEquals(ChatError.REQUEST_FAILED, read?.error)
    }

    private class Fixture {
        lateinit var command: TdApi.Function<*>
        lateinit var reply: (TdApi.Object) -> Unit
        lateinit var error: () -> Unit
        val native = OfficialTdDownloadNative { function, result, failure -> command = function; reply = result; error = failure }
    }
}
