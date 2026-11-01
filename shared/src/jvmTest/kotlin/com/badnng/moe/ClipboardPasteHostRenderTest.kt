package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ClipboardPasteHostRenderTest {
    private val token = ChatToken(1, 1, 100)
    private fun snapshot() = ChatSnapshot(enabled = true, token = token, selected = ChatItem(100, "synthetic"))

    @Test fun cancellingTheConfirmationKeepsTheScreenshotUntilItsExitContentIsDisposed() {
        val path = Files.createTempFile("nnggram-paste-ui-", ".png")
        var deleted = 0
        try {
            check(ImageIO.write(BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB), "png", path.toFile()))
            val batch = PreparedClipboardBatch(listOf(PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE,
                path.toString(), "截图.png", Files.size(path)), true, ownedAttachmentLease { Files.deleteIfExists(path); deleted++ })))
            var sends = 0
            hostScene(batch, ChatFeatureActions(submitClipboardPhoto = { _, _, _, _, _ -> sends++ })).use { scene ->
                scene.settledFrame()
                scene.click(scene.button("粘贴测试"))
                scene.settledFrame()
                assertEquals(0, sends, "Reading/presenting clipboard content must not send an account message.")
                scene.click(scene.button("取消"))
                scene.settledFrame(1)
                assertTrue(Files.exists(path), "Retained exit content must keep its own display lease.")
                scene.settledFrame()
                assertFalse(Files.exists(path))
                assertEquals(1, deleted)
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun fileBatchesAdvanceOnlyFromTheirOwnCallbacksAndRetryOnlyTheUnacceptedItem() {
        val items = listOf("one.txt", "two.txt").map { name ->
            PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic/$name", name, 20), false)
        }
        val requests = mutableListOf<Pair<String, (ChatResult<Unit>) -> Unit>>()
        hostScene(PreparedClipboardBatch(items), ChatFeatureActions(submitClipboardFile = { _, attachment, _, complete ->
            requests += attachment.fileName to complete
        })).use { scene ->
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            assertTrue(requests.isEmpty())
            scene.click(scene.button("发送"))
            scene.settledFrame()
            assertEquals(listOf("one.txt"), requests.map { it.first })
            requests[0].second(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(listOf("one.txt", "two.txt"), requests.map { it.first })
            requests[0].second(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(2, requests.size)
            requests[1].second(ChatResult(error = ChatError.NOT_READY))
            scene.settledFrame()
            scene.click(scene.button("重试未发送部分"))
            scene.settledFrame()
            assertEquals(listOf("one.txt", "two.txt", "two.txt"), requests.map { it.first })
            requests[2].second(ChatResult(Unit))
            scene.settledFrame()
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "粘贴附件" } }.isEmpty())
        }
    }

    @Test fun aPhotoOnlyReadonlyComposerStillReceivesAttachmentPasteAndRejectsTypedText() {
        var value by mutableStateOf(TextFieldValue())
        var pasted = 0
        val clipboard = FakeClipboard()
        ComposePixelScene(780, 260) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val focus = remember { FocusRequester() }
            MiuixTheme(controller = theme) {
                val window = LocalWindowInfo.current
                val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides focused, LocalAttachmentClipboard provides clipboard,
                    LocalAttachmentPasteRequest provides { pasted++ }) {
                    ConversationComposer(value, { value = it }, canSend = false, sendPending = false,
                        canSendPhotos = true, canSendStickers = false, canSendVideos = false, canSendDocuments = false,
                        focusRequester = focus, onSend = {}, onEscape = null, onEditLast = {}, onPhoto = {}, onSticker = {},
                        onVideo = {}, onFile = {})
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val editor = assertNotNull(scene.semanticsBounds {
                "文字只读" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.firstOrNull())
            scene.click(editor)
            scene.settledFrame()
            assertTrue(scene.unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.Focused) == true }.isNotEmpty())
            scene.keyStroke(KeyEvent.VK_V, control = true)
            scene.settledFrame()
            assertEquals(1, pasted)
            scene.keyStroke(KeyEvent.VK_A, character = 'a')
            scene.settledFrame()
            assertEquals("", value.text)
        }
    }

    @Test fun aSharedCaptionIsNotRepeatedAfterTheFirstFileOrWhenRetryingLaterFiles() {
        val original = RichMessageText("仅第一张 😀", listOf(MessageEntity(0, 4, MessageEntityKind.BOLD)))
        var draft = original.editorValue()
        val requests = mutableListOf<Pair<RichMessageText, (ChatResult<Unit>) -> Unit>>()
        val items = listOf("one.txt", "two.txt", "three.txt").map { name ->
            PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic/$name", name, 20), false)
        }
        var accepted = 0
        hostScene(PreparedClipboardBatch(items), ChatFeatureActions(submitClipboardFile = { _, _, caption, complete ->
            requests += caption to complete
        }), composerDraft = { draft }, onDraftAccepted = { transfer ->
            if (transfer.canConsume(token, draft)) { draft = TextFieldValue(); accepted++ }
        }).use { scene ->
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            scene.click(scene.button("发送"))
            scene.settledFrame()
            assertEquals(original, requests.single().first)
            requests[0].second(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(RichMessageText(""), requests[1].first)
            assertEquals(1, accepted)
            requests[1].second(ChatResult(error = ChatError.REQUEST_FAILED))
            scene.settledFrame()
            scene.click(scene.button("重试未发送部分"))
            scene.settledFrame()
            assertEquals(RichMessageText(""), requests[2].first, "Retrying a later file cannot repeat the accepted shared caption.")
            requests[2].second(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(RichMessageText(""), requests[3].first)
            requests[3].second(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(1, accepted, "Later files must not consume a new composer draft.")
        }
    }

    @Test fun editingDoesNotReadOrStageAttachmentsAndStopsAnAlreadyConfirmedBatch() {
        val editing = snapshot().copy(editTarget = ChatMessage(42, 100, text = "existing"))
        var state by mutableStateOf(editing)
        var reads = 0
        val requests = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val batch = PreparedClipboardBatch(listOf("one.txt", "two.txt").map { name ->
            PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic/$name", name, 20), false)
        })
        hostScene(batch, ChatFeatureActions(submitClipboardFile = { _, _, _, complete -> requests += complete }),
            state = { state }, clipboard = FakeClipboard { reads++ }).use { scene ->
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            assertEquals(0, reads, "Even a stale shortcut must not read attachment content in edit mode.")
            assertTrue(requests.isEmpty())
            state = snapshot()
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            assertEquals(1, reads)
            scene.click(scene.button("发送"))
            scene.settledFrame()
            assertEquals(1, requests.size)
            state = editing
            scene.settledFrame()
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "粘贴附件" }
            }.isEmpty(), "Entering edit must close a previously confirmed attachment flow.")
            requests.single()(ChatResult(Unit))
            state = snapshot()
            scene.settledFrame()
            assertEquals(1, requests.size, "The first item's late completion cannot submit the next file.")
        }
    }

    @Test fun pasteBorrowsTheRichComposerDraftAndOnlyItsOwnAcceptedItemConsumesIt() {
        val original = RichMessageText("原有图片说明 😀", listOf(MessageEntity(0, 6, MessageEntityKind.BOLD)))
            .editorValue(androidx.compose.ui.text.TextRange(2, 5))
        var draft = original
        var revision = 1L
        val captions = mutableListOf<RichMessageText>()
        val completions = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val batch = PreparedClipboardBatch(listOf(PreparedClipboardItem(
            LocalAttachment(AttachmentKind.FILE, "synthetic.txt", "file.txt", 20), false)))
        hostScene(batch, ChatFeatureActions(submitClipboardFile = { _, _, caption, completed ->
            captions += caption; completions += completed
        }), composerDraft = { draft }, composerDraftRevision = { revision }, onDraftAccepted = { transfer ->
            if (transfer.canConsume(token, draft, revision)) draft = TextFieldValue()
        }).use { scene ->
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text == original.text }.isNotEmpty())
            scene.click(scene.button("取消"))
            scene.settledFrame()
            assertEquals(original, draft, "Cancellation must retain text, entities and selection.")
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            scene.click(scene.button("发送"))
            scene.settledFrame()
            assertEquals(original.richText(), captions.single())
            completions.single()(ChatResult(error = ChatError.REQUEST_FAILED))
            scene.settledFrame()
            assertEquals(original, draft)
            scene.click(scene.button("重试未发送部分"))
            scene.settledFrame()
            // This new draft happens to have the same content; its changed revision owns it.
            revision += 2
            completions.last()(ChatResult(Unit))
            scene.settledFrame()
            assertEquals(original, draft)
        }
    }

    @Test fun stoppingTheQueueRetainsTheDraftUntilItsInFlightItemActuallySucceeds() {
        var draft = TextFieldValue("source caption")
        var accepted = 0
        val completions = mutableListOf<(ChatResult<Unit>) -> Unit>()
        val batch = PreparedClipboardBatch(listOf("one.txt", "two.txt").map {
            PreparedClipboardItem(LocalAttachment(AttachmentKind.FILE, "synthetic/$it", it, 20), false)
        })
        hostScene(batch, ChatFeatureActions(submitClipboardFile = { _, _, _, completed -> completions += completed }),
            composerDraft = { draft }, onDraftAccepted = { transfer ->
                if (transfer.canConsume(token, draft)) { draft = TextFieldValue(); accepted++ }
            }).use { scene ->
            scene.settledFrame()
            scene.click(scene.button("粘贴测试"))
            scene.settledFrame()
            scene.click(scene.button("发送"))
            scene.settledFrame()
            scene.click(scene.button("停止后续发送"))
            scene.settledFrame()
            assertEquals("source caption", draft.text)
            completions.single()(ChatResult(Unit))
            scene.settledFrame()
            assertEquals("", draft.text)
            assertEquals(1, completions.size, "Stopping must prevent the second dispatch.")
            draft = TextFieldValue("source caption")
            completions.single()(ChatResult(Unit))
            scene.settledFrame()
            assertEquals("source caption", draft.text, "A duplicate completion cannot consume a later draft.")
            assertEquals(1, accepted)
        }
    }

    private fun hostScene(batch: PreparedClipboardBatch, features: ChatFeatureActions,
        state: () -> ChatSnapshot = { snapshot() }, clipboard: AttachmentClipboard = FakeClipboard(),
        composerDraft: (ChatToken) -> TextFieldValue = { TextFieldValue() }, composerDraftRevision: (ChatToken) -> Long = { 0 },
        onDraftAccepted: (ComposerDraftTransfer) -> Unit = {}) = ComposePixelScene(780, 760) {
        val theme = remember { ThemeController(ColorSchemeMode.Light) }
        MiuixTheme(controller = theme) {
            CompositionLocalProvider(LocalAttachmentClipboard provides clipboard) {
                ClipboardPasteHost(state(), features, prepare = { batch }, composerDraft = composerDraft,
                    composerDraftRevision = composerDraftRevision, onComposerDraftAccepted = onDraftAccepted) {
                    val paste = LocalAttachmentPasteRequest.current
                    Box(Modifier.fillMaxSize()) { ChatButton("粘贴测试", onClick = { paste?.invoke() }) }
                }
            }
        }
    }
    private fun ComposePixelScene.button(text: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { label -> label.text == text }
    }.firstOrNull(), "Missing synthetic button $text")
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private class FakeClipboard(private val onRead: () -> Unit = {}) : AttachmentClipboard {
        override fun hasAttachments() = true
        override suspend fun readAttachments(): ClipboardAttachmentContent {
            onRead()
            return ClipboardAttachmentContent.FileList(listOf("synthetic"))
        }
        override suspend fun writeFiles(paths: List<String>) = Unit
        override suspend fun writePng(bytes: ByteArray) = Unit
    }
}
