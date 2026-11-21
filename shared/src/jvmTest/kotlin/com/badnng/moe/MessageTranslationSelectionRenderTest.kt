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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageDelivery
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real bubble gestures and a memory clipboard; no native account or system clipboard. */
@Suppress("DEPRECATION")
class MessageTranslationSelectionRenderTest {
    @Test
    fun translatedMouseSelectionSurvivesNativeUpdatesAndCopiesTheDisplayedOccurrence() {
        for (showOriginal in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(showOriginal, density)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val start = scene.caret(TRANSLATION, START)
                val middle = scene.caret(TRANSLATION, MIDDLE)
                val end = scene.caret(TRANSLATION, END)
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

                scene.secondaryClickAt(end.x, end.y)
                scene.settledFrame()
                assertTrue(scene.buttons("复制文本").isEmpty(),
                    "A translated drag must not silently fall back to copying the whole message: $showOriginal/$density")
                assertTrue(scene.buttons("引文回复").isEmpty(),
                    "Translated selection must not become a native quote with original UTF-16 offsets.")
                scene.clickButton("复制选中文本")
                assertEquals(TRANSLATION.substring(START, END), fixture.clipboard.copiedText?.text,
                    "The actual menu must freeze the translated drag through an unrelated update: $showOriginal/$density")
            }
        }
    }

    @Test
    fun rightClickWithoutSelectionCopiesTranslationInEitherDisplayMode() {
        for (showOriginal in listOf(false, true)) {
            val fixture = Fixture(showOriginal)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val target = scene.caret(TRANSLATION, MIDDLE)
                scene.secondaryClickAt(target.x, target.y)
                scene.settledFrame()
                assertTrue(scene.buttons("引文回复").isEmpty())
                scene.clickButton("复制文本")
                assertEquals(TRANSLATION, fixture.clipboard.copiedText?.text,
                    "Right-clicking translated text must not copy the original: showOriginal=$showOriginal")
            }
        }
    }

    private class Fixture(val showOriginal: Boolean, val density: Float = 1f) {
        val clipboard = MemoryClipboard()
        var expanded by mutableStateOf(false)
        var snapshot by mutableStateOf(ChatSnapshot(
            enabled = true,
            token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic translation chat", privateUserId = 20),
            messages = listOf(ChatMessage(id = 201, chatId = 10, senderId = 20, outgoing = true,
                text = ORIGINAL, delivery = MessageDelivery.SENT, canBeSaved = true)),
            actionsMessageId = 201,
            messageActions = MessageActions(),
        ))

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (480 * density).roundToInt()) {
            CompositionLocalProvider(
                LocalDensity provides Density(density),
                LocalClipboardManager provides clipboard,
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true),
                LocalConversationMessageMenuViewport provides Rect(0f, 0f, 900f * density, 480f * density),
            ) {
                MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                    val focusReturn = remember { ComposerModalFocusReturn() }
                    Box(Modifier.fillMaxSize().observeComposerModalFocusInput(focusReturn)
                        .background(MiuixTheme.colorScheme.surface)) {
                        Box(Modifier.offset(60.dp, 70.dp).width(600.dp)) {
                            MessageBubble(snapshot.messages.single(), "Synthetic sender", showSender = false, endsGroup = true,
                                onBounds = {}, retrying = false, highlighted = false, read = false, onRetry = {},
                                snapshot = snapshot, features = ChatFeatureActions(), visible = true, animate = false,
                                onEnlarge = {}, onSave = { _, _ -> }, onPlay = {}, playing = false,
                                onStopPlaying = {}, onVideoBounds = {}, playbackSession = null, onExpandVideo = {},
                                menuOpen = expanded, onMenuOpen = { expanded = true }, onMenuDismiss = { expanded = false },
                                onReply = {}, onQuote = { error("Translated text cannot dispatch a native quote") },
                                onJumpToReply = {}, onReact = {}, onReadDetails = {}, onEdit = {}, onForward = {},
                                onDelete = {}, onSearchSender = {}, reserveAvatarSpace = false,
                                translation = TRANSLATION, showTranslationOriginal = showOriginal)
                        }
                    }
                }
            }
        }
    }

    private class MemoryClipboard : ClipboardManager {
        var copiedText: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) { copiedText = annotatedString }
        override fun getText(): AnnotatedString? = copiedText
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

    private fun ComposePixelScene.clickButton(label: String) {
        val target = assertNotNull(buttons(label).singleOrNull(), "Expected actual menu action: $label")
        moveTo(target.center.x, target.center.y)
        pressAt(target.center.x, target.center.y)
        releaseAt(target.center.x, target.center.y)
        settledFrame()
    }

    private companion object {
        const val ORIGINAL = "The native source must never replace translated clipboard content."
        const val TRANSLATION = "repeated words stay selected, repeated words stay translated."
        const val START = 29
        const val MIDDLE = 37
        const val END = 43
    }
}
