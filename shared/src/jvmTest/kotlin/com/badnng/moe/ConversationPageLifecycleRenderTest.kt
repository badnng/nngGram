package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ConversationPageLifecycleRenderTest {
    @Test
    fun leavingThePageClosesTheRealStickerPopupAndRetainsOnlyItsNoninteractiveExit() {
        for ((mode, density) in appearances) {
            val interactive = mutableStateOf(true)
            val backdrop = ContextMenuBackdropState()
            val sent = mutableListOf<Long>()
            var loads = 0
            val chat = ChatItem(1, "Synthetic popup lifecycle")
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1), selected = chat,
                historyEnd = true, stickers = listOf(StickerItem(2, 3, "🙂",
                    MessageMedia(MediaFile(102), format = MediaFormat.PHOTO))))
            val features = ChatFeatureActions(loadStickers = { _, _ -> loads++ },
                sendSticker = { _, sticker -> sent += sticker.id })
            scene(mode, density, backdrop) {
                // A popup's backdrop normally makes activity false. Navigation ownership
                // must remain independent, so that state still permits opening the picker.
                ConversationPane(snapshot, chat, TextFieldValue(), {}, {}, {}, {}, {}, {}, features,
                    active = false, onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {},
                    showInfoButton = false, pageInteractive = interactive.value)
            }.use { scene ->
                scene.settledFrame()
                val trigger = scene.description("贴纸")
                scene.moveTo(trigger.center.x, trigger.center.y)
                scene.settledFrame()
                val sendCell = scene.description("发送贴纸 🙂")
                assertEquals(1, loads)
                assertTrue(backdrop.hasMountedMenus && backdrop.isExpanded)

                interactive.value = false
                scene.settledFrame(1)
                assertFalse(backdrop.isExpanded, "Departure closes the popup immediately.")
                assertTrue(backdrop.hasMountedMenus, "Its raster/backdrop ownership survives the exit frames.")
                assertTrue(scene.descriptions("发送贴纸 🙂").isEmpty())
                scene.click(sendCell)
                scene.settledFrame()
                assertTrue(sent.isEmpty(), "An old native popup cannot submit during navigation.")
                assertFalse(backdrop.hasMountedMenus)
                scene.moveTo(0f, 0f)
                scene.moveTo(trigger.center.x, trigger.center.y)
                scene.settledFrame()
                assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
                assertEquals(1, loads, "Hover on a departing composer must not reopen its picker.")
            }
        }
    }

    @Test
    fun leavingTheHoveredAttachmentMenuCannotOpenAPickerOrReopenTheMenu() {
        for ((mode, density) in appearances) {
            val interactive = mutableStateOf(true)
            var pickerRequests = 0
            var sends = 0
            scene(mode, density) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ConversationComposer(TextFieldValue(), {}, true, false, true, true, true, true,
                        remember { FocusRequester() }, { sends++ }, null, {}, { pickerRequests++ }, {},
                        { pickerRequests++ }, { pickerRequests++ }, modifier = Modifier.fillMaxWidth(),
                        interactive = interactive.value)
                }
            }.use { scene ->
                scene.settledFrame()
                val trigger = scene.description("附件")
                scene.moveTo(trigger.center.x, trigger.center.y)
                scene.settledFrame()
                assertTrue(scene.attachmentMenus().isNotEmpty())
                val oldFile = scene.text("文件")

                interactive.value = false
                scene.settledFrame(1)
                assertTrue(scene.attachmentMenus().isEmpty(), "The retained native menu loses actions immediately.")
                scene.click(oldFile)
                scene.settledFrame()
                scene.moveTo(0f, 0f)
                scene.moveTo(trigger.center.x, trigger.center.y)
                scene.settledFrame()
                assertTrue(scene.attachmentMenus().isEmpty(), "Hover cannot reopen a departing page's popup.")
                assertEquals(0, pickerRequests)
                assertEquals(0, sends)
            }
        }
    }

    @Test
    fun leavingWhileTheMicrophoneIsHeldCancelsTheGestureInsteadOfSubmitting() {
        val interactive = mutableStateOf(true)
        var presses = 0
        val releases = mutableListOf<Boolean>()
        scene(ColorSchemeMode.Light, 1f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                ConversationComposer(TextFieldValue(), {}, true, false, false, false, false, false,
                    remember { FocusRequester() }, {}, null, {}, {}, {}, {}, {},
                    modifier = Modifier.fillMaxWidth(), canSendVoiceNotes = true,
                    onVoicePress = { presses++ }, onVoiceRelease = { releases += it },
                    interactive = interactive.value)
            }
        }.use { scene ->
            scene.settledFrame()
            val mic = scene.description("录制语音")
            scene.moveTo(mic.center.x, mic.center.y)
            scene.pressAt(mic.center.x, mic.center.y)
            scene.settledFrame(1)
            assertEquals(1, presses)
            interactive.value = false
            scene.settledFrame(1)
            scene.releaseAt(mic.center.x, mic.center.y)
            scene.settledFrame()
            assertTrue(releases.isNotEmpty())
            assertTrue(releases.all { it }, "Changing navigation ownership can only cancel the held gesture.")
        }
    }

    private val appearances = listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)

    private fun scene(mode: ColorSchemeMode, density: Float, backdrop: ContextMenuBackdropState? = null,
        content: @Composable () -> Unit) = ComposePixelScene((620 * density).roundToInt(), (700 * density).roundToInt()) {
        val theme = remember { ThemeController(mode) }
        CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop,
            LocalDesktopChatLayout provides desktopChatLayout(620.dp, 700.dp)) {
            MiuixTheme(controller = theme, content = content)
        }
    }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)
    private fun ComposePixelScene.text(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull(), label)
    private fun ComposePixelScene.attachmentMenus() = semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "附件菜单"
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
