package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.chat.MessageInlineKeyboard as Keyboard
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real Miuix buttons, modal overlay and pointer input; browser opening is the only fake boundary. */
class MessageInlineKeyboardRenderTest {
    @Test fun eachNativeColumnShowsItsActualUrlBeforeExplicitBrowserOpening() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density)
            fixture.scene.use { scene ->
                val initial = scene.settledFrame()
                assertFalse(fixture.backdrop.hasMountedMenus)
                val first = scene.button(DOWNLOAD)
                val second = scene.button(DETAILS)
                assertTrue(abs(first.width - second.width) <= 1f, "The native two-column row keeps equal button widths.")
                assertTrue(abs(second.left - first.right - 2f * density) <= 1f, "The native inline row uses its real 2dp gap.")
                assertTrue(first.height >= 36f * density - 1f && second.height >= 36f * density - 1f)
                export(initial, "keyboard-${mode.name.lowercase()}-$density")
                for ((label, url) in listOf(DOWNLOAD to ARTIFACT_URL, DETAILS to BUILD_URL)) {
                    val before = fixture.opened.size
                    scene.click(scene.button(label))
                    val dialog = scene.settledFrame()
                    assertTrue(fixture.backdrop.isExpanded, "The real confirmation must suspend timeline interaction while mounted.")
                    assertEquals(before, fixture.opened.size, "The message button must show the real destination before opening it.")
                    assertTrue(scene.hasText(url), "The actual native URL must be visible in the Miuix confirmation.")
                    if (label == DOWNLOAD) export(dialog, "keyboard-confirm-${mode.name.lowercase()}-$density")
                    scene.click(scene.button("打开链接"))
                    scene.settledFrame()
                    assertEquals(url, fixture.opened.last())
                    assertEquals(before + 1, fixture.opened.size)
                    assertFalse(fixture.backdrop.hasMountedMenus, "Explicit opening releases modal backdrop ownership.")
                }
                scene.click(scene.button(UNSUPPORTED))
                scene.settledFrame()
                assertEquals(listOf(ARTIFACT_URL, BUILD_URL), fixture.opened)
                assertFalse(scene.hasText("将使用默认浏览器打开以下地址："), "Unsupported native callbacks and purchases must remain inert.")
            }
        }
    }

    @Test fun deletionSelectionMarkupChangesAndPageDeactivationCancelAnOpenConfirmation() {
        for (change in listOf("delete", "selection", "markup", "page")) {
            val fixture = Fixture(ColorSchemeMode.Light, 1f)
            fixture.scene.use { scene ->
                scene.settledFrame()
                scene.click(scene.button(DOWNLOAD))
                scene.settledFrame()
                val confirm = scene.button("打开链接")
                when (change) {
                    "delete" -> fixture.snapshot = fixture.snapshot.copy(messages = emptyList())
                    "selection" -> fixture.snapshot = fixture.snapshot.copy(token = fixture.snapshot.token.copy(selection = 2, chatId = 2), selected = ChatItem(2, "other"))
                    "markup" -> {
                        fixture.renderedMessage = fixture.message.copy(inlineKeyboard = null)
                        fixture.snapshot = fixture.snapshot.copy(messages = listOf(fixture.renderedMessage))
                    }
                    "page" -> fixture.interactive = false
                }
                if (change == "page") scene.settledFrame(1)
                // Click the previously rendered confirmation before another frame: the final action
                // must check the callback's current snapshot even if recomposition has not happened.
                scene.click(confirm)
                scene.settledFrame()
                assertTrue(fixture.opened.isEmpty(), "The old confirmation must not open after $change.")
                assertFalse(scene.hasText("将使用默认浏览器打开以下地址："))
                assertFalse(fixture.backdrop.hasMountedMenus, "Invalidated confirmation must release backdrop ownership after $change.")
                if (change == "page") {
                    fixture.interactive = true
                    scene.settledFrame()
                    assertFalse(scene.hasText("将使用默认浏览器打开以下地址："), "Re-entering a page must not revive its canceled modal.")
                }
            }
        }
    }

    @Test fun removingTheWholeMessageUnmountsItsModalAndRemountCannotReviveAnOldConfirmation() {
        for (reason in listOf("delete", "chat")) {
            val fixture = Fixture(ColorSchemeMode.Dark, 1.25f)
            fixture.scene.use { scene ->
                val original = fixture.snapshot
                scene.settledFrame()
                scene.click(scene.button(DOWNLOAD))
                scene.settledFrame()
                val confirm = scene.button("打开链接")
                assertTrue(fixture.backdrop.hasMountedMenus)
                fixture.mounted = false
                fixture.snapshot = if (reason == "delete") fixture.snapshot.copy(messages = emptyList()) else
                    fixture.snapshot.copy(token = fixture.snapshot.token.copy(selection = 2, chatId = 2), selected = ChatItem(2, "other"), messages = emptyList())
                scene.click(confirm)
                scene.settledFrame()
                assertTrue(fixture.opened.isEmpty(), "The disposed $reason message must not authorize its retained native dialog.")
                assertFalse(scene.hasText("将使用默认浏览器打开以下地址："), "Miuix root overlay must remove the disposed message's content.")
                assertFalse(fixture.backdrop.hasMountedMenus, "Unmount must dispose modal backdrop ownership.")
                fixture.snapshot = original.copy(token = original.token.copy(selection = 3))
                fixture.mounted = true
                scene.settledFrame()
                scene.click(confirm)
                scene.settledFrame()
                assertTrue(fixture.opened.isEmpty(), "Remount must not revive the previous confirmation callback.")
                assertFalse(scene.hasText("将使用默认浏览器打开以下地址："))
                assertFalse(fixture.backdrop.hasMountedMenus)
                scene.click(scene.button(DETAILS))
                scene.settledFrame()
                scene.click(scene.button("打开链接"))
                scene.settledFrame()
                assertEquals(listOf(BUILD_URL), fixture.opened, "Only a fresh explicit action on the remounted message opens its current URL.")
            }
        }
    }

    @Test fun unmountRevokesConfirmationEvenWhenItsSnapshotProviderRetainsTheOldValidMessage() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f)
        fixture.scene.use { scene ->
            scene.settledFrame()
            scene.click(scene.button(DOWNLOAD))
            scene.settledFrame()
            val confirm = scene.button("打开链接")
            val oldSnapshot = fixture.snapshot
            fixture.mounted = false
            scene.settledFrame(1)
            assertEquals(oldSnapshot, fixture.snapshot, "The provider deliberately keeps the old valid account snapshot after component disposal.")
            scene.click(confirm)
            scene.settledFrame()
            assertTrue(fixture.opened.isEmpty(), "Source disposal independently revokes retained confirmation callbacks.")
            assertFalse(fixture.backdrop.hasMountedMenus)
            assertFalse(scene.hasText("将使用默认浏览器打开以下地址："))
            fixture.mounted = true
            scene.settledFrame()
            scene.click(confirm)
            scene.settledFrame()
            assertTrue(fixture.opened.isEmpty(), "An unchanged snapshot cannot revive a disposed source's confirmation on remount.")
            assertFalse(scene.hasText("将使用默认浏览器打开以下地址："))
        }
    }

    private class Fixture(mode: ColorSchemeMode, density: Float) {
        private val keyboard = Keyboard(listOf(
            listOf(MessageInlineButton(DOWNLOAD, MessageInlineButtonKind.URL, ARTIFACT_URL),
                MessageInlineButton(DETAILS, MessageInlineButtonKind.URL, BUILD_URL)),
            listOf(MessageInlineButton(UNSUPPORTED)),
        ))
        val message = ChatMessage(10, 1, text = "synthetic build", inlineKeyboard = keyboard)
        var renderedMessage by mutableStateOf(message)
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
            selected = ChatItem(1, "synthetic"), messages = listOf(message)))
        var interactive by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        val opened = mutableListOf<String>()
        val backdrop = ContextMenuBackdropState()
        val scene = ComposePixelScene((560 * density).roundToInt(), (440 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop) {
                MiuixTheme(controller = theme) {
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(24.dp)) {
                            if (mounted) MessageInlineKeyboard(renderedMessage, snapshot, interactive, modifier = Modifier.width(320.dp),
                                currentSnapshot = { snapshot }, openLink = { opened += it; true })
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.button(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull(), "Expected one real button labelled $label.")

    private fun ComposePixelScene.hasText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.isNotEmpty()

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "message-inline-keyboard")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private companion object {
        const val DOWNLOAD = "下载 Actions 产物"
        const val DETAILS = "构建详情"
        const val UNSUPPORTED = "原生回调（暂不支持）"
        const val ARTIFACT_URL = "https://example.org/synthetic/artifact"
        const val BUILD_URL = "https://example.org/synthetic/build"
    }
}
