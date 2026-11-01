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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.media.LocalNativeFilePicker
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AttachmentCaptionDraftRenderTest {
    @Test fun anExplicitNativeStickerCellSendReturnsRealTypingToTheComposer() = withSyntheticImage { path ->
        val item = StickerItem(71, 300, "🙂", MessageMedia(MediaFile(72, downloaded = true, localPath = path),
            width = 24, height = 16))
        val initial = snapshot().copy(stickers = listOf(item))
        var draft by mutableStateOf(TextFieldValue())
        val sent = mutableListOf<Pair<ChatToken, Long>>()
        scene(path, { initial }, { draft }, { draft = it },
            ChatFeatureActions(sendSticker = { token, sticker -> sent += token to sticker.id })).use { scene ->
            scene.settledFrame()
            val trigger = scene.description("贴纸")
            scene.moveTo(trigger.center.x, trigger.center.y)
            scene.settledFrame()
            scene.click(scene.description("筛选当前贴纸"))
            scene.settledFrame(1, 16)
            assertFalse(scene.composerFocused(), "The real Popup input must own focus before selecting a sticker.")
            scene.click(scene.description("发送贴纸 🙂"))
            scene.settledFrame(24, 16)
            assertEquals(listOf(initial.token to item.id), sent)
            assertTrue(scene.composerFocused(), "The explicit send action must return focus without a native acceptance.")
            scene.keyStroke(KeyEvent.VK_N, character = 'n')
            scene.settledFrame(2, 16)
            assertEquals("n", draft.text, "Typing after a real sticker-cell click must enter the conversation draft.")
        }
    }

    @Test fun anAcceptedPhotoOrFileConfirmationReturnsRealTypingToTheComposer() {
        for (photo in listOf(true, false)) withSyntheticImage { path ->
            val initial = snapshot()
            var draft by mutableStateOf(TextFieldValue("caption", TextRange(7)))
            val completions = mutableListOf<(ChatResult<Unit>) -> Unit>()
            val features = ChatFeatureActions(submitClipboardPhoto = { _, _, _, _, completed -> completions += completed },
                submitClipboardFile = { _, _, _, completed -> completions += completed })
            scene(path, { initial }, { draft }, { draft = it }, features).use { scene ->
                scene.settledFrame()
                scene.click(scene.composer())
                scene.settledFrame(1, 16)
                assertTrue(scene.composerFocused())
                scene.openAttachment(photo)
                scene.click(scene.button(if (photo) "发送图片" else "发送文件"))
                scene.settledFrame(2, 16)
                assertEquals(1, completions.size)
                completions.single()(ChatResult(Unit))
                scene.settledFrame()
                assertEquals("", draft.text)
                assertTrue(scene.composerFocused(), "Closing the accepted confirmation must return to its original current composer.")
                scene.keyStroke(KeyEvent.VK_N, character = 'n')
                scene.settledFrame(2, 16)
                assertEquals("n", draft.text, "The next real keystroke after confirmation must reach the draft.")
            }
        }
    }

    @Test fun laterInputAndLeavingThePageRevokeAConfirmationsPendingFocusReturn() {
        for (photo in listOf(true, false)) for (leavePage in listOf(false, true)) withSyntheticImage { path ->
            var draft by mutableStateOf(TextFieldValue("caption", TextRange(7)))
            var other by mutableStateOf(TextFieldValue())
            var interactive by mutableStateOf(true)
            val completions = mutableListOf<(ChatResult<Unit>) -> Unit>()
            val features = ChatFeatureActions(submitClipboardPhoto = { _, _, _, _, completed -> completions += completed },
                submitClipboardFile = { _, _, _, completed -> completions += completed })
            scene(path, { snapshot() }, { draft }, { draft = it }, features, pageInteractive = { interactive },
                otherValue = { other }, onOtherValue = { other = it }).use { scene ->
                scene.settledFrame()
                scene.openAttachment(photo)
                scene.click(scene.button(if (photo) "发送图片" else "发送文件"))
                scene.settledFrame(2, 16)
                if (leavePage) interactive = false else completions.single()(ChatResult(Unit))
                // Apply modal removal while the owned return still waits for its handoff frame.
                scene.settledFrame(1, 16)
                scene.click(scene.description("Other input"))
                scene.keyStroke(KeyEvent.VK_S, character = 's')
                scene.settledFrame(2, 16)
                if (leavePage) completions.single()(ChatResult(Unit))
                scene.settledFrame()
                assertFalse(scene.composerFocused(), "The user's later control or inactive page must keep focus.")
                scene.keyStroke(KeyEvent.VK_X, character = 'x')
                scene.settledFrame(2, 16)
                assertEquals("sx", other.text, "A late success cannot take the keyboard away from the other input.")
                interactive = true
                scene.settledFrame()
                assertFalse(scene.composerFocused(), "Page reactivation cannot replay the old confirmation intent.")
            }
        }
    }

    @Test fun pickerPhotoAndFileCaptionsBorrowTheDraftAndPreserveItOnCancelOrNativeFailure() {
        for (photo in listOf(true, false)) withSyntheticImage { path ->
            val initial = snapshot()
            val original = RichMessageText("已有的说明 😀", listOf(MessageEntity(0, 5, MessageEntityKind.BOLD)))
                .editorValue(TextRange(2, 4))
            var draft by mutableStateOf(original)
            val requests = mutableListOf<Pair<RichMessageText, (ChatResult<Unit>) -> Unit>>()
            val features = ChatFeatureActions(
                submitClipboardPhoto = { _, _, caption, _, completed -> requests += caption to completed },
                submitClipboardFile = { _, _, caption, completed -> requests += caption to completed })
            scene(path, { initial }, { draft }, { draft = it }, features).use { scene ->
                scene.settledFrame()
                scene.openAttachment(photo)
                assertTrue(scene.editableTexts().count { it == original.text } >= 2,
                    "The attachment caption must start with the existing composer text.")
                scene.click(scene.button("取消"))
                scene.settledFrame()
                assertEquals(original, draft)
                assertTrue(requests.isEmpty())
                scene.openAttachment(photo)
                scene.click(scene.button(if (photo) "发送图片" else "发送文件"))
                scene.settledFrame()
                assertEquals(original.richText(), requests.single().first)
                requests.single().second(ChatResult(error = ChatError.REQUEST_FAILED))
                scene.settledFrame()
                assertEquals(original, draft, "A failed native request cannot consume its source draft.")
                assertTrue(original.text in scene.editableTexts(), "The caption must remain editable after failure.")
                scene.click(scene.button(if (photo) "发送图片" else "发送文件"))
                scene.settledFrame()
                requests.last().second(ChatResult(Unit))
                scene.settledFrame()
                assertEquals("", draft.text)
                assertEquals(2, requests.size)
            }
        }
    }

    @Test fun latePhotoAcceptanceCannotConsumeANewerOrDifferentConversationDraft() = withSyntheticImage { path ->
        var state by mutableStateOf(snapshot())
        var draft by mutableStateOf(TextFieldValue("borrowed caption"))
        var revision by mutableLongStateOf(2)
        val requests = mutableListOf<(ChatResult<Unit>) -> Unit>()
        scene(path, { state }, { draft }, { draft = it },
            ChatFeatureActions(submitClipboardPhoto = { _, _, _, _, completed -> requests += completed }),
            { revision }).use { scene ->
            scene.settledFrame()
            scene.openAttachment(photo = true)
            scene.click(scene.button("发送图片"))
            scene.settledFrame()
            revision += 2 // Delete/retype the identical text while native submission is in flight.
            requests.single()(ChatResult(Unit))
            scene.settledFrame()
            assertEquals("borrowed caption", draft.text)
            scene.openAttachment(photo = true)
            scene.click(scene.button("发送图片"))
            scene.settledFrame()
            state = snapshot().copy(token = ChatToken(1, 2, 101), selected = ChatItem(101, "other synthetic"))
            draft = TextFieldValue("other conversation")
            revision++
            scene.settledFrame()
            requests.last()(ChatResult(Unit))
            scene.settledFrame()
            assertEquals("other conversation", draft.text)
        }
    }

    @Test fun openingAFileBeforeTheNextFrameBorrowsTheCurrentDraftRatherThanTheRenderedOne() = withSyntheticImage { path ->
        val initial = snapshot()
        var draft by mutableStateOf(TextFieldValue("previous frame"))
        var revision by mutableLongStateOf(1)
        scene(path, { initial }, { draft }, { draft = it }, ChatFeatureActions(), { revision }).use { scene ->
            scene.settledFrame()
            draft = TextFieldValue("just entered caption")
            revision++
            // The attachment click is processed before a new render can publish those props.
            scene.openAttachment(photo = false)
            assertEquals(2, scene.editableTexts().count { it == "just entered caption" })
        }
    }

    private fun scene(path: String, state: () -> ChatSnapshot, draft: () -> TextFieldValue,
        onDraft: (TextFieldValue) -> Unit, features: ChatFeatureActions, revision: () -> Long = { 0 },
        pageInteractive: () -> Boolean = { true }, otherValue: (() -> TextFieldValue)? = null,
        onOtherValue: (TextFieldValue) -> Unit = {}) = ComposePixelScene(740, 900) {
        val theme = remember { ThemeController(ColorSchemeMode.Light) }
        val focusReturn = remember { ComposerModalFocusReturn() }
        ComposerModalFocusReturnEffect(focusReturn)
        MiuixTheme(controller = theme) {
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalNativeFilePicker provides { _, _, _ -> path }, LocalWindowInfo provides focused,
                LocalComposerModalFocusReturn provides focusReturn) {
                val snapshot = state()
                Box(Modifier.fillMaxSize().observeComposerModalFocusInput(focusReturn)) {
                    ConversationPane(snapshot, checkNotNull(snapshot.selected), draft(), onDraft, null,
                        {}, {}, {}, {}, features, false, {}, {}, {}, {}, false, draftRevision = revision(),
                        readDraft = draft, readDraftRevision = revision, pageInteractive = pageInteractive())
                    otherValue?.let { read ->
                        BasicTextField(read(), onOtherValue, Modifier.align(Alignment.TopEnd).width(200.dp).height(40.dp)
                            .semantics { contentDescription = "Other input" })
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.openAttachment(photo: Boolean) {
        val action = assertNotNull(semanticsBounds { "附件" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.singleOrNull())
        if (photo) {
            moveTo(action.center.x, action.center.y)
            settledFrame()
            val item = assertNotNull(semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "图片" } }.singleOrNull())
            click(item)
        } else click(action)
        settledFrame(48)
    }

    private fun ComposePixelScene.button(text: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }.singleOrNull(), "Missing attachment action $text")
    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), "Missing real composer/picker action $label")
    private fun ComposePixelScene.composer(): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null &&
            "发送消息…" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.composerFocused(): Boolean = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText) != null && it.getOrNull(SemanticsProperties.Focused) == true &&
            "发送消息…" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.isNotEmpty()
    private fun ComposePixelScene.editableTexts(): List<String> = buildList {
        unmergedSemanticsBounds { it.getOrNull(SemanticsProperties.EditableText)?.text?.let(::add); false }
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun snapshot() = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 100), selected = ChatItem(100, "synthetic"), historyEnd = true)
    private fun withSyntheticImage(block: (String) -> Unit) {
        val path = Files.createTempFile("composer-caption-", ".png")
        try {
            assertTrue(ImageIO.write(BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", path.toFile()))
            block(path.toString())
        } finally { Files.deleteIfExists(path) }
    }
}
