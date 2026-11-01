package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.RichMessageText
import java.awt.event.KeyEvent
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Commands enter the actual Compose text-input session, never a preconfigured composition value. */
@OptIn(ExperimentalComposeUiApi::class)
class ComposerImeInputRenderTest {
    @Test fun clearingASentDraftAndRefocusingTheSameTargetKeepsTheNextChineseCommitConnected() {
        val fixture = Fixture(clearOnSend = true)
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.commitChinese(scene, "nihao", "你好")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame(3, 16)
            assertEquals(listOf(RichMessageText("你好")), fixture.sent)
            assertEquals("", fixture.value.text)
            assertEquals("", assertNotNull(fixture.ime.current.get()).value().text,
                "The live input session must observe the cleared field after send and same-target requestFocus.")
            fixture.commitChinese(scene, "xia", "下")
            assertEquals("下", fixture.value.text, "The next native commit must still reach the pending composer.")
            assertEquals(1, fixture.ime.active.get(), "Sending must leave one active current input session.")
        }
        assertEquals(0, fixture.ime.active.get())
    }

    @Test fun aNativeMessagePopupCanCloseAndReturnToALiveComposerInputSession() {
        val fixture = Fixture(withMessageMenu = true)
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.commitChinese(scene, "ni", "你")
            fixture.menuExpanded = true
            scene.settledFrame()
            assertEquals(1, scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
            }.size, "The fixture must mount the actual native message Popup.")
            scene.click(Rect(600f, 360f, 630f, 390f))
            scene.settledFrame(24, 16)
            assertEquals(false, fixture.menuExpanded)
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
            }.isEmpty())
            fixture.focusComposer()
            scene.settledFrame(3, 16)
            fixture.commitChinese(scene, "hao", "好")
            assertEquals("你好", fixture.value.text, "Popup removal and explicit return focus must leave a usable current request.")
            assertEquals(1, fixture.ime.active.get())
        }
        assertEquals(0, fixture.ime.active.get())
    }

    @Test fun windowReactivationFieldSwitchAndRecreatedComposerUseOnlyTheCurrentInputRequest() {
        val fixture = Fixture(TextFieldValue("原稿", TextRange(2)), withOtherField = true)
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.windowFocused = false
            scene.settledFrame(3, 16)
            fixture.windowFocused = true
            scene.settledFrame(3, 16)
            fixture.commitChinese(scene, "ni", "你")
            assertEquals("原稿你", fixture.value.text)
            val composerRequest = assertNotNull(fixture.ime.current.get())
            scene.click(assertNotNull(scene.unmergedSemanticsBounds {
                OtherFieldDescription in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    it.getOrNull(SemanticsProperties.EditableText) != null
            }.singleOrNull()))
            scene.settledFrame(3, 16)
            val otherRequest = assertNotNull(fixture.ime.current.get())
            assertTrue(otherRequest !== composerRequest)
            assertTrue(composerRequest in fixture.ime.finished, "Moving to another field must cancel the old session.")
            fixture.commitChinese(scene, "ce", "测")
            assertEquals("另一栏测", fixture.other.text)
            assertEquals("原稿你", fixture.value.text, "A request owned by the other field cannot edit the composer.")
            fixture.startInput(scene)
            val oldRequest = assertNotNull(fixture.ime.current.get())
            assertTrue(otherRequest in fixture.ime.finished)
            fixture.generation++ // A test key models a replaced composable owner during development reload.
            scene.settledFrame(3, 16)
            val oldUpdates = fixture.ownerUpdates.count { it.first == 0 }
            fixture.startInput(scene)
            val current = assertNotNull(fixture.ime.current.get())
            assertTrue(current !== oldRequest)
            assertTrue(oldRequest in fixture.ime.finished, "A recreated field cannot retain its disposed input request.")
            fixture.commitChinese(scene, "xin", "新")
            assertEquals("原稿你新", fixture.value.text)
            assertEquals(oldUpdates, fixture.ownerUpdates.count { it.first == 0 },
                "New preedit/commit callbacks must belong to the recreated field, not its old owner.")
            assertTrue(fixture.ownerUpdates.any { it.first == 1 && it.second.composition != null })
            assertEquals(1, fixture.ime.active.get())
        }
        assertEquals(0, fixture.ime.active.get())
    }

    @Test fun nativePreeditReplacementAndChineseCommitReachTheRichComposerDuringPending() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.ime.edit(SetComposingTextCommand("ni", 1))
            scene.settledFrame(1, 16)
            assertEquals("ni", fixture.value.text)
            assertEquals(TextRange(0, 2), fixture.value.composition)
            fixture.ime.edit(SetComposingTextCommand("你😀", 1))
            scene.settledFrame(1, 16)
            assertEquals("你😀", fixture.value.text)
            assertEquals(TextRange(0, 3), fixture.value.composition, "IME ranges remain UTF-16 offsets.")
            fixture.pending = true
            scene.settledFrame(3, 16)
            fixture.ime.edit(SetComposingTextCommand("你好", 1))
            scene.settledFrame(1, 16)
            assertEquals("你好", fixture.value.text)
            assertEquals(TextRange(0, 2), fixture.value.composition)
            fixture.ime.edit(CommitTextCommand("你好", 1), FinishComposingTextCommand())
            scene.settledFrame(2, 16)
            assertEquals("你好", fixture.value.text)
            assertNull(fixture.value.composition)
            assertEquals(TextRange(2), fixture.value.selection)
            fixture.pending = false
            scene.settledFrame(2, 16)
            scene.keyStroke(KeyEvent.VK_SPACE, character = ' ')
            scene.settledFrame(1, 16)
            assertEquals("你好 ", fixture.value.text, "The root input observer must not consume ordinary Space input.")
            fixture.ime.edit(SetComposingTextCommand("shijie", 1))
            scene.settledFrame(1, 16)
            fixture.ime.edit(CommitTextCommand("世界", 1), FinishComposingTextCommand())
            scene.settledFrame(2, 16)
            assertEquals("你好 世界", fixture.value.text)
            assertNull(fixture.value.composition)
            assertTrue(fixture.updates.any { it.composition != null }, "Preedit must pass through the real onValueChange callback.")
            assertTrue(assertNotNull(fixture.ime.current.get()).focusedRectInRoot()!!.height > 0f,
                "The actual session must expose the laid-out IME caret geometry.")
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame(2, 16)
            assertEquals(listOf(RichMessageText("你好 世界")), fixture.sent)
        }
    }

    @Test fun automaticFormattingWaitsForAnActualImeCommitRatherThanRewritingPreedit() {
        val fixture = Fixture(TextFieldValue("**", TextRange(2)))
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.ime.edit(SetComposingTextCommand("中文**", 1))
            scene.settledFrame(1, 16)
            assertEquals("**中文**", fixture.value.text)
            assertEquals(TextRange(2, 6), fixture.value.composition)
            assertTrue(fixture.value.richText().entities.isEmpty(), "Uncommitted candidate text must stay literal.")
            fixture.ime.edit(CommitTextCommand("中文**", 1), FinishComposingTextCommand())
            scene.settledFrame(2, 16)
            assertEquals("中文", fixture.value.text)
            assertNull(fixture.value.composition)
            assertEquals(TextRange(2), fixture.value.selection)
            assertEquals(listOf(MessageEntity(0, 2, MessageEntityKind.BOLD)), fixture.value.richText().entities)
            assertEquals("中文", assertNotNull(fixture.ime.current.get()).value().text,
                "The text-input session must receive the formatted committed value back through updateState.")
        }
    }

    @Test fun enterWhileARealPreeditIsActiveCannotSubmitTheUncommittedCandidate() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            fixture.startInput(scene)
            fixture.ime.edit(SetComposingTextCommand("nihao", 1))
            scene.settledFrame(1, 16)
            assertEquals(TextRange(0, 5), fixture.value.composition)
            scene.keyStroke(KeyEvent.VK_ENTER)
            scene.settledFrame(2, 16)
            assertTrue(fixture.sent.isEmpty(), "The composer shortcut must pass composition Enter to the text system.")
        }
    }

    private inner class Fixture(initial: TextFieldValue = TextFieldValue(), val clearOnSend: Boolean = false,
        val withMessageMenu: Boolean = false, val withOtherField: Boolean = false) {
        var value by mutableStateOf(initial)
        var pending by mutableStateOf(false)
        var windowFocused by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        var menuExpanded by mutableStateOf(false)
        var other by mutableStateOf(TextFieldValue("另一栏", TextRange(3)))
        private var currentFocus: FocusRequester? = null
        val ime = ImeProbe()
        val sent = mutableListOf<RichMessageText>()
        val updates = mutableListOf<TextFieldValue>()
        val ownerUpdates = mutableListOf<Pair<Int, TextFieldValue>>()
        private val focusReturn = ComposerModalFocusReturn()

        fun scene() = ComposePixelScene(640, 400) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused get() = windowFocused } }
            CompositionLocalProvider(LocalWindowInfo provides focused) {
                MiuixTheme(controller = theme) {
                    InterceptPlatformTextInput(ime) {
                        Column(Modifier.observeComposerModalFocusInput(focusReturn)) {
                            key(generation) {
                                val owner = generation
                                val focus = remember { FocusRequester() }
                                SideEffect { currentFocus = focus }
                                ConversationComposer(value, { value = it; updates += it; ownerUpdates += owner to it },
                                    true, pending, true, true, true, true, focus, {
                                        sent += value.richText()
                                        if (clearOnSend) value = TextFieldValue()
                                        pending = true
                                    }, null, {}, {}, {}, {}, {})
                            }
                            if (withOtherField) BasicTextField(other, { other = it }, Modifier.width(320.dp).height(40.dp)
                                .semantics { contentDescription = OtherFieldDescription })
                        }
                        if (withMessageMenu) {
                            val message = remember { ChatMessage(10, 100, text = "Native menu source") }
                            val snapshot = remember { ChatSnapshot(enabled = true, token = ChatToken(1, 1, 100),
                                selected = ChatItem(100, "Synthetic IME lifecycle"), messages = listOf(message),
                                actionsMessageId = message.id, messageActions = MessageActions(canForward = true)) }
                            MessageActionsMenu(message, snapshot, true, false, {}, {}, {}, {}, {}, {}, {}, {},
                                onDismiss = { menuExpanded = false }, expanded = menuExpanded)
                        }
                    }
                }
            }
        }

        fun startInput(scene: ComposePixelScene) {
            scene.settledFrame()
            val field = assertNotNull(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.EditableText) != null &&
                    OtherFieldDescription !in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull())
            scene.click(field)
            repeat(8) {
                scene.settledFrame(1, 16)
                if (ime.current.get()?.value()?.text == value.text) return
            }
            assertNotNull(ime.current.get(), "Focusing the actual editor must establish a Compose input-method session.")
        }

        fun focusComposer() = onUi { assertNotNull(currentFocus).requestFocus() }

        fun commitChinese(scene: ComposePixelScene, preedit: String, committed: String) {
            ime.edit(SetComposingTextCommand(preedit, 1))
            scene.settledFrame(1, 16)
            assertNotNull(assertNotNull(ime.current.get()).value().composition,
                "The current field must receive native preedit, rather than only a final committed string.")
            ime.edit(CommitTextCommand(committed, 1), FinishComposingTextCommand())
            scene.settledFrame(2, 16)
        }
    }

    private class ImeProbe : PlatformTextInputInterceptor {
        val current = AtomicReference<PlatformTextInputMethodRequest?>()
        val active = AtomicInteger()
        val finished = ConcurrentLinkedQueue<PlatformTextInputMethodRequest>()
        override suspend fun interceptStartInputMethod(request: PlatformTextInputMethodRequest,
            nextHandler: PlatformTextInputSession): Nothing {
            current.set(request)
            active.incrementAndGet()
            try { awaitCancellation() }
            finally { current.compareAndSet(request, null); active.decrementAndGet(); finished.add(request) }
        }

        fun edit(vararg commands: EditCommand) = onUi {
            assertNotNull(current.get(), "No actual focused Compose text-input session.").onEditCommand(commands.toList())
        }
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private companion object {
        const val OtherFieldDescription = "Other IME input"
        fun <T> onUi(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
