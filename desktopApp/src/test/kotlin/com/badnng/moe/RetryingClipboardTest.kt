package com.badnng.moe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalComposeUiApi::class)
class RetryingClipboardTest {
    @Test
    fun transientLockPreservesChineseContent() = runBlocking {
        val entry = ClipEntry(StringSelection("你好，nngGram！"))
        var reads = 0
        val platform = FakeClipboard(read = {
            if (++reads < 3) throw IllegalStateException("cannot open system clipboard")
            entry
        })
        assertSame(entry, RetryingClipboard(platform).getClipEntry())
        assertEquals(3, reads)
    }

    @Test
    fun persistentLockDoesNotCrashPaste() = runBlocking {
        var reads = 0
        val platform = FakeClipboard(read = {
            reads++
            throw IllegalStateException("cannot open system clipboard")
        })
        assertNull(RetryingClipboard(platform).getClipEntry())
        assertEquals(6, reads)
    }

    @Test
    fun copyRetriesTheSameEntry() = runBlocking {
        val entry = ClipEntry(StringSelection("中文"))
        var writes = 0
        val platform = FakeClipboard(write = {
            assertSame(entry, it)
            if (++writes == 1) throw IllegalStateException("cannot open system clipboard")
        })
        RetryingClipboard(platform).setClipEntry(entry)
        assertEquals(2, writes)
    }

    @Test
    fun cancellationAndUnrelatedFailuresAreNotSwallowed(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            RetryingClipboard(FakeClipboard(read = { throw CancellationException() })).getClipEntry()
        }
        assertFailsWith<IllegalStateException> {
            RetryingClipboard(FakeClipboard(read = { error("unrelated failure") })).getClipEntry()
        }
    }

    private class FakeClipboard(
        val read: () -> ClipEntry? = { null },
        val write: (ClipEntry?) -> Unit = {},
    ) : Clipboard {
        override suspend fun getClipEntry() = read()
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = write(clipEntry)
    }
}
