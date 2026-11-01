package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageDelivery
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.MessageQuote
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real mouse gestures and the bubble's frozen quote expose selection without a test-only UI hook. */
class MessageNativeIdentitySelectionRenderTest {
    @Test
    fun pendingMessageKeepsItsActiveMouseSelectionWhenNativeIdChanges() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(mode, density, pending = true)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val start = scene.caret(SOURCE_TEXT, START)
                val middle = scene.caret(SOURCE_TEXT, MIDDLE)
                val end = scene.caret(SOURCE_TEXT, END)
                scene.moveTo(start.x, start.y)
                scene.pressAt(start.x, start.y)
                scene.dragTo(middle.x, middle.y)
                scene.settledFrame(2, stepMillis = 16)

                val old = fixture.message
                fixture.replaceMessage(old.copy(id = FINAL_MESSAGE_ID, delivery = MessageDelivery.SENT))
                scene.settledFrame(2, stepMillis = 16)
                assertEquals(old.presentationId, fixture.message.presentationId,
                    "Native delivery replaces an id while retaining the actual rendered message identity.")
                scene.dragTo(end.x, end.y)
                scene.releaseAt(end.x, end.y)
                scene.settledFrame()

                scene.assertSelectedQuote(fixture, SOURCE_TEXT, START, END, "$mode/$density/native-id")
            }
        }
    }

    @Test
    fun unrelatedNativeSnapshotKeepsTheCurrentMouseDragAndFrozenSelection() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            val start = scene.caret(SOURCE_TEXT, START)
            val middle = scene.caret(SOURCE_TEXT, MIDDLE)
            val end = scene.caret(SOURCE_TEXT, END)
            scene.moveTo(start.x, start.y)
            scene.pressAt(start.x, start.y)
            scene.dragTo(middle.x, middle.y)
            scene.settledFrame(2, stepMillis = 16)

            fixture.snapshot = fixture.snapshot.copy(
                selected = assertNotNull(fixture.snapshot.selected).copy(unreadCount = 3),
                actionsLoading = true,
            )
            scene.settledFrame(2, stepMillis = 16)
            scene.dragTo(end.x, end.y)
            scene.releaseAt(end.x, end.y)
            scene.settledFrame()

            scene.assertSelectedQuote(fixture, SOURCE_TEXT, START, END, "unrelated-native-snapshot")
        }
    }

    @Test
    fun changedRichContentReleasesOldSelectionAndAcceptsANewMouseDrag() {
        for (changeEntitiesOnly in listOf(false, true)) {
            val fixture = Fixture()
            fixture.scene().use { scene ->
                scene.settledFrame()
                scene.select(SOURCE_TEXT, START, END)

                val changed = if (changeEntitiesOnly) fixture.message.copy(
                    entities = listOf(MessageEntity(START, END - START, MessageEntityKind.BOLD)),
                ) else fixture.message.copy(text = CHANGED_TEXT, editDate = 1)
                fixture.replaceMessage(changed)
                scene.settledFrame()
                val end = scene.caret(changed.text, END)
                scene.secondaryClickAt(end.x, end.y)
                scene.settledFrame()

                assertTrue(scene.buttons("复制选中文本").isEmpty(),
                    "Actual rich content changes must not retain the old text selection: entitiesOnly=$changeEntitiesOnly")
                assertTrue(scene.buttons("引文回复").isEmpty(),
                    "A replaced source cannot expose its old frozen quote: entitiesOnly=$changeEntitiesOnly")
                assertEquals(1, scene.buttons("复制文本").size)
                scene.keyStroke(java.awt.event.KeyEvent.VK_ESCAPE)
                scene.settledFrame()

                scene.select(changed.text, START, END)
                scene.assertSelectedQuote(fixture, changed.text, START, END, "changed-content/$changeEntitiesOnly")
            }
        }
    }

    private class Fixture(
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        val density: Float = 1f,
        pending: Boolean = false,
    ) {
        var snapshot by mutableStateOf(ChatSnapshot(
            enabled = true,
            token = ChatToken(1, 1, CHAT_ID),
            selected = ChatItem(CHAT_ID, "Synthetic selection chat", privateUserId = 20),
            messages = listOf(ChatMessage(
                id = if (pending) PENDING_MESSAGE_ID else FINAL_MESSAGE_ID,
                chatId = CHAT_ID,
                senderId = 20,
                outgoing = true,
                text = SOURCE_TEXT,
                delivery = if (pending) MessageDelivery.PENDING else MessageDelivery.SENT,
                canBeSaved = true,
                presentationId = PENDING_MESSAGE_ID,
            )),
            actionsMessageId = if (pending) PENDING_MESSAGE_ID else FINAL_MESSAGE_ID,
            messageActions = MessageActions(),
        ))
        val message: ChatMessage get() = snapshot.messages.single()
        var expanded by mutableStateOf(false)
        var quote: MessageQuote? = null

        fun replaceMessage(message: ChatMessage) {
            snapshot = snapshot.copy(messages = listOf(message), actionsMessageId = message.id)
        }

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (480 * density).roundToInt()) {
            CompositionLocalProvider(
                LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true),
                LocalConversationMessageMenuViewport provides Rect(0f, 0f, 900f * density, 480f * density),
            ) {
                MiuixTheme(controller = remember { ThemeController(mode) }) {
                    val focusReturn = remember { ComposerModalFocusReturn() }
                    Box(Modifier.fillMaxSize().observeComposerModalFocusInput(focusReturn)
                        .background(MiuixTheme.colorScheme.surface)) {
                        Box(Modifier.offset(60.dp, 70.dp).width(600.dp)) {
                            MessageBubble(message, "Synthetic sender", showSender = false, endsGroup = true,
                                onBounds = {}, retrying = false, highlighted = false, read = false, onRetry = {},
                                snapshot = snapshot, features = ChatFeatureActions(), visible = true, animate = false,
                                onEnlarge = {}, onSave = { _, _ -> }, onPlay = {}, playing = false,
                                onStopPlaying = {}, onVideoBounds = {}, playbackSession = null, onExpandVideo = {},
                                menuOpen = expanded, onMenuOpen = { expanded = true }, onMenuDismiss = { expanded = false },
                                onReply = {}, onQuote = { quote = it; expanded = false }, onJumpToReply = {}, onReact = {},
                                onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                                reserveAvatarSpace = false)
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.assertSelectedQuote(
        fixture: Fixture,
        text: String,
        start: Int,
        end: Int,
        stage: String,
    ) {
        val caret = caret(text, end)
        secondaryClickAt(caret.x, caret.y)
        settledFrame()
        assertEquals(1, buttons("复制选中文本").size,
            "Right-click must freeze the actual selected text after $stage.")
        assertTrue(buttons("复制文本").isEmpty(), "A partial selection cannot silently become the full message after $stage.")
        val quoteButton = assertNotNull(buttons("引文回复").singleOrNull(),
            "The actual bubble must expose its frozen selected-text quote after $stage.")
        moveTo(quoteButton.center.x, quoteButton.center.y)
        pressAt(quoteButton.center.x, quoteButton.center.y)
        releaseAt(quoteButton.center.x, quoteButton.center.y)
        settledFrame()
        val quoted = assertNotNull(fixture.quote, "The real quote action must dispatch after $stage.")
        assertEquals(start, quoted.position, "The frozen selection keeps its original UTF-16 start after $stage.")
        assertEquals(text.substring(start, end), quoted.content.text,
            "The mouse drag must continue from its original start after $stage.")
    }

    private fun ComposePixelScene.select(text: String, startOffset: Int, endOffset: Int) {
        val start = caret(text, startOffset)
        val end = caret(text, endOffset)
        moveTo(start.x, start.y)
        pressAt(start.x, start.y)
        dragTo(end.x, end.y)
        releaseAt(end.x, end.y)
        settledFrame()
    }

    private fun ComposePixelScene.caret(text: String, offset: Int): Offset {
        val layouts = mutableListOf<TextLayoutResult>()
        val bounds = unmergedSemanticsBounds { config ->
            val matches = config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            if (matches) config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            matches
        }.single()
        val caret = layouts.single().getCursorRect(offset)
        return Offset(bounds.left + caret.left, bounds.top + caret.center.y)
    }

    private fun ComposePixelScene.buttons(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }

    private companion object {
        const val CHAT_ID = 10L
        const val PENDING_MESSAGE_ID = 101L
        const val FINAL_MESSAGE_ID = 201L
        const val SOURCE_TEXT = "Mouse selection survives native delivery updates"
        const val CHANGED_TEXT = "Mouse selection follows actual rich text updates"
        const val START = 6
        const val MIDDLE = 15
        const val END = 30
    }
}
