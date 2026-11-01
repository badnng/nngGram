package com.badnng.moe

import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ClipboardMessageCopyTest {
    private fun snapshot() = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 100), selected = ChatItem(100, "synthetic"),
        messages = listOf(ChatMessage(10, 100, kind = MessageKind.PHOTO, canBeSaved = true,
            media = MessageMedia(MediaFile(70, localPath = "synthetic-native.png", downloaded = true)))))

    @Test fun explicitCopiesUseNativeSavePermissionAndOnlyWriteTheChosenClipboardFormat() = runBlocking {
        val clipboard = FakeClipboard()
        assertTrue(copyMessageAttachment(10, true, clipboard, { snapshot() }, imagePng = { byteArrayOf(3, 4) }))
        assertContentEquals(byteArrayOf(3, 4), clipboard.image)
        assertTrue(clipboard.files.isEmpty())
        assertTrue(copyMessageAttachment(10, false, clipboard, { snapshot() }))
        assertEquals(listOf("synthetic-native.png"), clipboard.files)
        assertFalse(clipboardMessageSource(snapshot(), 10).toString().contains("synthetic-native"))
    }

    @Test fun protectedOrUnsavableMessagesAndLateSourceChangesNeverOverwriteTheClipboard() = runBlocking {
        for (state in listOf(snapshot().copy(selected = ChatItem(100, "synthetic", hasProtectedContent = true)),
            snapshot().let { it.copy(messages = it.messages.map { message -> message.copy(canBeSaved = false) }) })) {
            assertNull(clipboardMessageSource(state, 10))
        }
        var state = snapshot()
        val clipboard = FakeClipboard()
        assertFalse(copyMessageAttachment(10, true, clipboard, { state }, imagePng = {
            state = state.copy(token = ChatToken(1, 2, 101))
            byteArrayOf(1)
        }))
        assertNull(clipboard.image)
        state = snapshot()
        assertFalse(copyMessageAttachment(10, true, clipboard, { state }, imagePng = {
            state = state.copy(selected = ChatItem(100, "synthetic", hasProtectedContent = true))
            byteArrayOf(1)
        }))
        assertNull(clipboard.image)
        assertTrue(clipboard.files.isEmpty())
    }

    private class FakeClipboard : AttachmentClipboard {
        var files = emptyList<String>()
        var image: ByteArray? = null
        override fun hasAttachments() = false
        override suspend fun readAttachments(): ClipboardAttachmentContent? = null
        override suspend fun writeFiles(paths: List<String>) { files = paths }
        override suspend fun writePng(bytes: ByteArray) { image = bytes }
    }
}
