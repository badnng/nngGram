package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real text-link pointer input and Miuix modal; the external browser is never opened. */
class MessageBodyInteractionRenderTest {
    @Test fun hiddenTextConfirmsItsActualUrlWhileVisibleUrlsAndCurrentControlClickOpenDirectly() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density)
            fixture.scene.use { scene ->
                scene.settledFrame()
                scene.click(scene.text(BODY))
                val frame = scene.settledFrame()
                assertTrue(fixture.opened.isEmpty())
                assertTrue(scene.hasText(URL))
                assertTrue(fixture.backdrop.isExpanded)
                export(frame, "body-confirm-${mode.name.lowercase()}-$density")
                scene.click(scene.button("打开链接"))
                scene.settledFrame()
                assertEquals(listOf(URL), fixture.opened)
                assertFalse(fixture.backdrop.hasMountedMenus)
                // The provider reads WindowInfo at the actual link click, without rebuilding its annotations.
                fixture.control = true
                scene.click(scene.text(BODY))
                scene.settledFrame()
                assertEquals(listOf(URL, URL), fixture.opened)
                assertFalse(fixture.backdrop.hasMountedMenus)
                fixture.control = false
                scene.click(scene.text(BODY))
                scene.settledFrame()
                assertEquals(2, fixture.opened.size)
                assertTrue(fixture.backdrop.isExpanded)
                scene.click(scene.button("取消"))
                scene.settledFrame()
                fixture.renderedMessage = fixture.message.copy(text = URL,
                    entities = listOf(MessageEntity(0, URL.length, MessageEntityKind.URL)))
                fixture.snapshot = fixture.snapshot.copy(messages = listOf(fixture.renderedMessage))
                scene.settledFrame()
                scene.click(scene.text(URL))
                scene.settledFrame()
                assertEquals(listOf(URL, URL, URL), fixture.opened)
                assertFalse(fixture.backdrop.hasMountedMenus)
            }
        }
    }

    @Test fun currentSourcePageAndDisposalRevokeTheModalWithoutDependingOnTimelineActivity() {
        for (reason in listOf("delete", "selection", "entity", "page", "unmount")) {
            val fixture = Fixture(ColorSchemeMode.Light, 1f)
            fixture.scene.use { scene ->
                scene.settledFrame()
                scene.click(scene.text(BODY))
                scene.settledFrame()
                val confirm = scene.button("打开链接")
                when (reason) {
                    "delete" -> fixture.snapshot = fixture.snapshot.copy(messages = emptyList())
                    "selection" -> fixture.snapshot = fixture.snapshot.copy(token = fixture.snapshot.token.copy(selection = 2))
                    "entity" -> fixture.snapshot = fixture.snapshot.copy(messages = listOf(fixture.message.copy(
                        entities = listOf(fixture.message.entities.single().copy(value = "https://example.org/edited")))))
                    "page" -> fixture.interactive = false
                    "unmount" -> fixture.mounted = false // The captured native snapshot intentionally stays valid.
                }
                if (reason == "page" || reason == "unmount") scene.settledFrame(1)
                scene.click(confirm)
                scene.settledFrame()
                assertTrue(fixture.opened.isEmpty(), "Stale $reason confirmation must not authorize browser opening.")
                assertFalse(scene.hasText(PROMPT))
                assertFalse(fixture.backdrop.hasMountedMenus)
                if (reason == "page" || reason == "unmount") {
                    fixture.interactive = true
                    fixture.mounted = true
                    scene.settledFrame()
                    assertFalse(scene.hasText(PROMPT), "Returning to the same native source cannot revive a canceled modal.")
                }
            }
        }
    }

    private class Fixture(mode: ColorSchemeMode, density: Float) {
        val message = ChatMessage(10, 1, text = BODY,
            entities = listOf(MessageEntity(0, BODY.length, MessageEntityKind.TEXT_URL, URL)), canBeSaved = true)
        var renderedMessage by mutableStateOf(message)
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
            selected = ChatItem(1, "synthetic"), messages = listOf(message)))
        var interactive by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var control by mutableStateOf(false)
        val backdrop = ContextMenuBackdropState()
        val opened = mutableListOf<String>()
        val scene = ComposePixelScene((560 * density).roundToInt(), (440 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val host = LocalWindowInfo.current
            val window = remember(host) { object : WindowInfo by host {
                override val keyboardModifiers get() = PointerKeyboardModifiers(isCtrlPressed = control)
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalContextMenuBackdrop provides backdrop) {
                MiuixTheme(controller = theme) {
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            if (mounted) MessageBodyInteractionProvider(renderedMessage, snapshot, interactive,
                                currentSnapshot = { snapshot }, openLink = { opened += it; true }) {
                                RichMessageBody(renderedMessage.richText())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), label)
    private fun ComposePixelScene.hasText(label: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.button(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "message-body-links")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
    private companion object {
        const val BODY = "查看实际地址"
        const val URL = "https://example.org/synthetic/actual"
        const val PROMPT = "请核对以下实际地址："
    }
}
