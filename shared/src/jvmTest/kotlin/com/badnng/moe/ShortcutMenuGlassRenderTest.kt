package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real menu hosts, separated source layers and synthetic pixels; no account or native device access. */
class ShortcutMenuGlassRenderTest {
    @Test
    fun conversationOperationsBlurDetailAndTrackTheSourceThroughTheRootScaffoldHost() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val colored = mutableStateOf(false)
            val expanded = mutableStateOf(false)
            val chat = ChatItem(10, "Synthetic group", basicGroupId = 20)
            val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id))
            ComposePixelScene((900 * density).roundToInt(), (480 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        // The source local is deliberately below the Scaffold. The relocated native
                        // host must receive the captured backdrop instead of reading a null local.
                        Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                            val backdrop = assertNotNull(rememberChatBackdrop())
                            CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                                Box(Modifier.fillMaxSize()) {
                                    StripedSource(backdrop, colored)
                                    ConversationHeader(
                                        chat = chat, snapshot = snapshot, features = ChatFeatureActions(), active = true,
                                        onBack = null, menuOpen = expanded.value, onMenuOpen = { expanded.value = it },
                                        onMarkRead = {}, onBounds = {}, onSearch = {}, onDetails = {}, onMedia = {},
                                        showInfoButton = true, onPinned = {}, onTogglePinned = {}, pinnedHidden = false,
                                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val source = scene.settledFrame()
                expanded.value = true
                val opened = scene.settledFrame()
                val row = assertNotNull(scene.buttonTextBounds("查看群组信息").singleOrNull())
                verifyMixedBlur(scene, source, opened, row, density, colored, "operations/$density/$mode")
            }
        }
    }

    @Test
    fun chatListPopupSamplesTheChangingSourceAtItsRealWindowPosition() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val colored = mutableStateOf(false)
            val expanded = mutableStateOf(false)
            ComposePixelScene((900 * density).roundToInt(), (660 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            // Non-zero source origin catches sampling in Popup-local coordinates.
                            Box(Modifier.offset(80.dp, 40.dp).size(700.dp, 560.dp)) {
                                StripedSource(backdrop, colored)
                                Box(Modifier.size(1.dp)) {
                                    ChatListActionsMenu(ChatItem(1, "Synthetic chat"), ChatSnapshot(enabled = true),
                                        ChatFeatureActions(), position = Offset(340f * density, 180f * density),
                                        expanded = expanded.value, onDismiss = { expanded.value = false })
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val source = scene.settledFrame()
                expanded.value = true
                val opened = scene.settledFrame()
                val row = assertNotNull(scene.buttonTextBounds("归档聊天").singleOrNull())
                verifyMixedBlur(scene, source, opened, row, density, colored, "list/$density/$mode")
            }
        }
    }

    @Test
    fun aMenuWiderThanTheCollapsedChatListKeepsSamplingTheWorkspaceBeyondThatList() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val colored = mutableStateOf(false)
            val expanded = mutableStateOf(false)
            ComposePixelScene((900 * density).roundToInt(), (660 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        val workspaceBackdrop = assertNotNull(rememberChatBackdrop())
                        val listBackdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalWorkspaceMenuBackdrop provides workspaceBackdrop,
                            LocalChatBackdrop provides listBackdrop) {
                            Box(Modifier.fillMaxSize()) {
                                StripedSource(workspaceBackdrop, colored)
                                Box(Modifier.offset(490.dp, 160.dp).size(72.dp, 450.dp)) {
                                    Canvas(Modifier.fillMaxSize().layerBackdrop(listBackdrop)) { drawRect(Color.Gray) }
                                    ChatListActionsMenu(ChatItem(1, "Synthetic collapsed chat"), ChatSnapshot(enabled = true),
                                        ChatFeatureActions(), position = Offset.Zero, expanded = expanded.value,
                                        onDismiss = { expanded.value = false })
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                val source = scene.settledFrame()
                expanded.value = true
                val opened = scene.settledFrame()
                val row = assertNotNull(scene.buttonTextBounds("归档聊天").singleOrNull())
                assertTrue(row.right > 562f * density + 100f * density,
                    "The test must actually sample well outside the collapsed list's source layer.")
                verifyMixedBlur(scene, source, opened, row, density, colored, "collapsed/$density/$mode")
            }
        }
    }

    @Composable
    private fun StripedSource(backdrop: LayerBackdrop, colored: MutableState<Boolean>) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            val stripe = 4.dp.toPx()
            repeat((size.width / stripe).toInt() + 1) { index ->
                // Only the right half changes. An incorrectly sampled source origin remains
                // black/white, so a transparent or wrongly positioned fake cannot pass.
                val color = if (index % 2 != 0) Color.White else if (index * stripe < size.width / 2) Color.Black
                    else if (colored.value) Color.Blue else Color.Red
                drawRect(color, topLeft = Offset(index * stripe, 0f), size = Size(stripe, size.height))
            }
        }
    }

    private fun verifyMixedBlur(scene: ComposePixelScene, source: ComposePixelFrame, opened: ComposePixelFrame,
        row: Rect, density: Float, colored: MutableState<Boolean>, fixture: String) {
        val x = (row.right - 14f * density).roundToInt()
        val otherX = (x - 4f * density).roundToInt()
        val y = row.center.y.roundToInt()
        assertTrue(distance(source[x, y], source[otherX, y]) >= 150,
            "The comparison must observe real high-contrast source stripes: $fixture")
        assertTrue(distance(opened[x, y], opened[otherX, y]) <= 22,
            "The menu must soften high-frequency source detail below its action rows: $fixture")
        colored.value = true
        val changed = scene.settledFrame()
        val bodyX = row.center.x.roundToInt()
        val bodyY = (row.top - 4f * density).roundToInt()
        assertTrue(distance(opened[x, y], changed[x, y]) > 10,
            "The actual row must retain the changing backdrop through its transparent feedback surface: $fixture")
        assertTrue(distance(opened[bodyX, bodyY], changed[bodyX, bodyY]) > 10,
            "The menu padding must sample the same changing backdrop instead of an opaque native fill: $fixture")
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun distance(a: Int, b: Int): Int = listOf(0, 8, 16).maxOf {
        abs((a ushr it and 255) - (b ushr it and 255))
    }
}
