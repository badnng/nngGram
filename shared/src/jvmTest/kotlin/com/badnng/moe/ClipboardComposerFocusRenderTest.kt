package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import java.awt.event.KeyEvent
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ClipboardComposerFocusRenderTest {
    @Test fun aConfirmedClipboardFileReturnsTypingAfterItsActualModalContentUnmounts() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.composer())
            scene.keyStroke(KeyEvent.VK_V, control = true)
            scene.settledFrame()
            scene.click(scene.button("发送"))
            scene.settledFrame(2, 16)
            assertEquals(1, fixture.completions.size)
            fixture.completions.single()(ChatResult(Unit))
            scene.settledFrame()
            assertTrue(scene.composerFocused(), "This confirmation's disposal must return to its current registered field.")
            assertEquals("", fixture.draft.text)
            scene.keyStroke(KeyEvent.VK_N, character = 'n')
            scene.settledFrame(2, 16)
            assertEquals("n", fixture.draft.text)
        }
    }

    @Test fun stoppingTheQueueRevokesFocusEvenWhenItsAlreadyDispatchedFileLaterSucceeds() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.composer())
            scene.keyStroke(KeyEvent.VK_V, control = true)
            scene.settledFrame()
            scene.click(scene.button("发送"))
            scene.settledFrame(2, 16)
            scene.click(scene.button("停止后续发送"))
            scene.settledFrame()
            scene.click(scene.description("Other input"))
            scene.keyStroke(KeyEvent.VK_S, character = 's')
            scene.settledFrame(2, 16)
            fixture.completions.single()(ChatResult(Unit))
            scene.settledFrame()
            assertFalse(scene.composerFocused(), "Stopping owns cancellation; the file's late success cannot reclaim focus.")
            scene.keyStroke(KeyEvent.VK_X, character = 'x')
            scene.settledFrame(2, 16)
            assertEquals("sx", fixture.other.text)
        }
    }

    private inner class Fixture {
        val token = ChatToken(1, 1, 100)
        val snapshot = ChatSnapshot(enabled = true, token = token, selected = ChatItem(100, "Synthetic paste focus"), historyEnd = true)
        var draft by mutableStateOf(TextFieldValue("caption", TextRange(7)))
        var other by mutableStateOf(TextFieldValue())
        val completions = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val features = ChatFeatureActions(submitClipboardFile = { _, _, _, completed -> completions += completed })
        val batch = PreparedClipboardBatch(listOf(PreparedClipboardItem(
            LocalAttachment(AttachmentKind.FILE, "synthetic/document.txt", "document.txt", 20), false)))
        val clipboard = object : AttachmentClipboard {
            override fun hasAttachments() = true
            override suspend fun readAttachments() = ClipboardAttachmentContent.FileList(listOf("synthetic/document.txt"))
            override suspend fun writeFiles(paths: List<String>) = Unit
            override suspend fun writePng(bytes: ByteArray) = Unit
        }

        fun scene() = ComposePixelScene(780, 800) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalAttachmentClipboard provides clipboard) {
                MiuixTheme(controller = theme) {
                    ClipboardPasteHost(snapshot, features, prepare = { batch }, composerDraft = { draft },
                        onComposerDraftAccepted = { transfer -> if (transfer.canConsume(token, draft)) draft = TextFieldValue() }) {
                        Box(Modifier.fillMaxSize()) {
                            ConversationPane(snapshot, snapshot.selected!!, draft, { draft = it }, null,
                                {}, {}, {}, {}, features, false, {}, {}, {}, {}, false)
                            BasicTextField(other, { other = it }, Modifier.align(Alignment.TopEnd).width(200.dp).height(40.dp)
                                .semantics { contentDescription = "Other input" })
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.composer(): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null &&
            "发送消息…" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.composerFocused() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null && it.getOrNull(SemanticsProperties.Focused) == true &&
            "发送消息…" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.isNotEmpty()
    private fun ComposePixelScene.button(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull())
    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
