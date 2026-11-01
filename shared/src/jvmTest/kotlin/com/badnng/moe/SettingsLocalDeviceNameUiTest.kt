package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsLocalDeviceNameUiTest {
    @Test fun actualPointerSaveWritesTheFileAndKeepsTheNativeSessionLabel() {
        Fixture().use { f -> f.scene().use { scene ->
            scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
            scene.replaceEditor("Office Laptop", f)
            scene.clickLabel("保存", f, dialog = true); scene.settledFrame()
            assertEquals("Office Laptop", readSettingsPreferences(f.path).customDeviceModel)
            assertEquals(SettingsWallpaper.NIGHT, readSettingsPreferences(f.path).wallpaper)
            assertEquals(1, f.attempts)
            assertTrue(scene.hasText("Native name"))
            assertEquals("Native name", f.security.sessions.single().deviceModel)
            assertTrue(scene.hasText("已保存，将在下次启动生效。"))
        } }
    }

    @Test fun pointerCancelDoesNotWriteAndFailedAtomicReplaceRetainsDraftAndOldFile() {
        Fixture().use { f -> f.scene().use { scene ->
            val before = Files.readString(f.path)
            scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
            scene.replaceEditor("Never Saved", f); scene.clickLabel("取消", f, dialog = true)
            assertEquals(0, f.attempts); assertEquals(before, Files.readString(f.path))
            scene.clickLabel("编辑当前设备名称", f); scene.replaceEditor("New Name", f)
            f.onUi { f.fail = true }
            scene.clickLabel("保存", f, dialog = true); scene.settledFrame()
            assertTrue(scene.hasText("保存失败，请重试。"))
            assertEquals(before, Files.readString(f.path))
            assertTrue(scene.editorText().contains("New Name"))
            assertEquals("New Name", scene.editorText(), "The retained failed draft must be the complete replacement")
            f.onUi { f.fail = false }
            scene.clickLabel("保存", f, dialog = true); scene.settledFrame()
            assertEquals("New Name", readSettingsPreferences(f.path).customDeviceModel)
            assertEquals(2, f.attempts)
        } }
    }

    @Test fun cancelReopenTokenAndPageChangesRevokeCapturedRealCallbacks() {
        Fixture().use { f -> f.scene().use { scene ->
            scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
            val old = assertNotNull(f.callbacks)
            scene.replaceEditor("Old", f); scene.clickLabel("取消", f, dialog = true)
            scene.clickLabel("编辑当前设备名称", f)
            f.onUi { assertNotNull(old.save)() }
            assertEquals(0, f.attempts)
            val reopened = assertNotNull(f.callbacks)
            f.onUi { f.security = f.security.copy(token = SettingsSecurityToken(22, 2)) }
            scene.settledFrame()
            f.onUi { assertNotNull(reopened.save)() }
            assertEquals(0, f.attempts)
            scene.clickLabel("编辑当前设备名称", f)
            val next = assertNotNull(f.callbacks)
            f.onUi { f.active = false }; scene.settledFrame()
            f.onUi { f.active = true }; scene.settledFrame()
            f.onUi { assertNotNull(next.save)() }
            assertEquals(0, f.attempts)
            assertEquals("Old Local", readSettingsPreferences(f.path).customDeviceModel)
        } }
    }

    @Test fun lateCompletionAndDeferredCommitCannotAffectANewerOwner() {
        Fixture().use { f -> f.scene().use { scene ->
            f.onUi { f.defer = true }
            scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
            scene.replaceEditor("First", f); scene.clickLabel("保存", f, dialog = true)
            val pending = assertNotNull(f.pending)
            f.onUi { f.security = f.security.copy(token = SettingsSecurityToken(22, 2)); f.defer = false }
            scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
            scene.replaceEditor("Second", f)
            f.onUi { assertFalse(pending.current()) }
            val worker = Thread { pending.reply(true); pending.reply(false) }
            worker.start(); worker.join(); scene.settledFrame()
            assertTrue(scene.editorText().contains("Second"))
            assertEquals("Second", scene.editorText(), "A late result cannot restore an older editor's text")
            assertFalse(scene.hasText("已保存，将在下次启动生效。"))
            scene.clickLabel("保存", f, dialog = true); scene.settledFrame()
            assertEquals("Second", readSettingsPreferences(f.path).customDeviceModel)
            assertEquals(1, f.attempts)
        } }
    }

    @Test fun dialogMatrixUsesRealScrollAndFullyVisiblePointerTargets() {
        for ((width, height) in listOf(620 to 900, 350 to 300))
            for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                Fixture(width, height, density, mode, fullPage = false).use { f -> f.scene().use { scene ->
                    scene.settledFrame(); scene.clickLabel("编辑当前设备名称", f)
                    scene.settledFrame().save("${width}x$height-$density-$mode-top")
                    assertTrue(scene.hasText("当前设备名称"))
                    scene.replaceEditor("Matrix", f)
                    scene.scrollDialogToEnd(f)
                    scene.fullDialogButton("保存", f)
                    scene.fullDialogButton("取消", f)
                    scene.settledFrame().save("${width}x$height-$density-$mode-actions")
                    scene.clickLabel("保存", f, dialog = true); scene.settledFrame()
                    assertEquals("Matrix", readSettingsPreferences(f.path).customDeviceModel)
                } }
            }
    }

    private class Pending(val current: () -> Boolean, val reply: (Boolean) -> Unit)
    private class Fixture(val width: Int = 720, val height: Int = 1600, val density: Float = 1f,
        private val mode: ColorSchemeMode = ColorSchemeMode.Light, private val fullPage: Boolean = true) : AutoCloseable {
        private val root = Files.createTempDirectory("device-name-ui-")
        val path = root.resolve("ui-settings.properties")
        var preferences by mutableStateOf(SettingsPreferences(wallpaper = SettingsWallpaper.NIGHT, customDeviceModel = "Old Local"))
        var active by mutableStateOf(true)
        var security by mutableStateOf(SettingsSecuritySnapshot(token = SettingsSecurityToken(22, 1),
            section = SettingsSecuritySection.SESSIONS, sessions = listOf(SettingsSession(0, true, "Native name",
                "App", "1", "OS", "1", 123, "192.0.2.1", "Example", 123))))
        var callbacks: SettingsDeviceNameUiCallbacks? = null
        var fail = false
        var defer = false
        var attempts = 0
        var pending: Pending? = null
        init { assertTrue(writeSettingsPreferences(path, preferences)) }
        val pixelWidth get() = (width * density).roundToInt()
        val pixelHeight get() = (height * density).roundToInt()
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(pixelWidth, pixelHeight) {
            val theme = remember { ThemeController(mode) }
            val host = SettingsDeviceNameHost(preferences.customDeviceModel, { active }, {}, { name, current, reply ->
                if (defer) pending = Pending(current, reply)
                else if (!current()) reply(false)
                else {
                    attempts++
                    val next = preferences.copy(customDeviceModel = name)
                    val saved = if (fail) writeSettingsPreferences(path, next,
                        atomicReplace = { _, _ -> throw IOException("synthetic") }) else writeSettingsPreferences(path, next)
                    if (saved) preferences = next
                    reply(saved)
                }
            })
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineHost(height.dp),
                LocalSettingsDeviceNameHost provides host, LocalSettingsDeviceNameUiCallbacks provides { callbacks = it }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            if (fullPage) SettingsSecurityPage(SettingsSnapshot(token = SettingsToken(22, 1), enabled = true,
                                security = security), SettingsActions())
                            else SettingsLocalDeviceNameControl(security, active)
                        }
                    }
                }
            }
        }
        override fun close() { root.toFile().deleteRecursively() }
    }

    private class OfflineHost(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) {
                Column(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = maxWidth).heightIn(max = viewport - 24.dp)
                    .semantics { testTag = "device-name-dialog" }
                    .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                    title?.let { Text(it) }; content()
                }
            }
        }
    }

    private fun ComposePixelScene.bounds(label: String) = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
            config.getOrNull(SemanticsActions.OnClick)?.action != null &&
            config.getOrNull(SemanticsProperties.Disabled) == null
    }
    private fun ComposePixelScene.hasText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.editorBounds() = semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }
    private fun ComposePixelScene.editorText(): String {
        var result = ""
        semanticsBounds { config -> config.getOrNull(SemanticsProperties.EditableText)?.let { result = it.text }; false }
        return result
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture, dialog: Boolean) =
        revealBounds(f, dialog, fullButton = dialog && label in setOf("保存", "取消")) { bounds(label) }
    private data class DialogScroll(val bounds: Rect, val value: Float, val maximum: Float)
    private fun ComposePixelScene.dialogHost(): Rect? =
        semanticsBounds { it.getOrNull(SemanticsProperties.TestTag) == "device-name-dialog" }.lastOrNull()
    private fun ComposePixelScene.dialogScroll(host: Rect): DialogScroll? {
        val ranges = mutableListOf<Pair<Float, Float>>()
        val rects = semanticsBounds { config ->
            val range = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range == null) false else { ranges += range.value() to range.maxValue(); true }
        }
        val index = rects.indices.lastOrNull { rects[it].height > 20f && rects[it].overlaps(host) } ?: return null
        return DialogScroll(rects[index], ranges[index].first, ranges[index].second)
    }
    private fun ComposePixelScene.scrollDialogToEnd(f: Fixture) {
        var host = assertNotNull(dialogHost())
        var scroll = assertNotNull(dialogScroll(host), "The dialog body must expose its real scroll range")
        repeat(160) {
            if (scroll.value >= scroll.maximum - .5f) return
            val viewport = scroll.bounds
            assertTrue(viewport.width > 0 && viewport.height > 0 && viewport.top >= 0 && viewport.bottom <= f.pixelHeight,
                "The scroll viewport must be visible before pointer scrolling: $viewport")
            scrollAt(viewport.center.x, viewport.center.y, deltaY = 5f); settledFrame(5, 32)
            host = assertNotNull(dialogHost())
            scroll = assertNotNull(dialogScroll(host))
        }
        assertTrue(scroll.value >= scroll.maximum - .5f, "Pointer scrolling must reach the dialog body's real end")
    }
    private fun ComposePixelScene.fullDialogButton(label: String, f: Fixture): Rect {
        val host = assertNotNull(dialogHost())
        val viewport = assertNotNull(dialogScroll(host)).bounds
        val button = assertNotNull(bounds(label).lastOrNull(), label)
        assertTrue(button.height >= 35f * f.density - 1f, "$label must retain its full 35 dp height: $button")
        assertTrue(button.left >= viewport.left - 1f && button.right <= viewport.right + 1f &&
            button.top >= viewport.top - 1f && button.bottom <= viewport.bottom + 1f,
            "$label must fit inside the actual dialog body: button=$button body=$viewport")
        assertTrue(button.left >= host.left - 1f && button.right <= host.right + 1f &&
            button.top >= host.top - 1f && button.bottom <= host.bottom + 1f &&
            button.left >= 0f && button.top >= 0f && button.right <= f.pixelWidth && button.bottom <= f.pixelHeight &&
            host.bottom - button.bottom >= 11f * f.density - 1f,
            "$label must fit in the host and screen with bottom inset: button=$button host=$host")
        return button
    }
    private fun ComposePixelScene.revealBounds(f: Fixture, dialog: Boolean, fullButton: Boolean = false,
        targets: () -> List<Rect>): Rect {
        repeat(80) {
            val host = if (dialog) dialogHost()
                else Rect(0f, 0f, f.pixelWidth.toFloat(), f.pixelHeight.toFloat())
            val target = targets().lastOrNull()
            val scroll = host?.let { if (dialog) dialogScroll(it)?.bounds else null }
                ?: semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                    .lastOrNull { rect -> host != null && rect.height > 20 && rect.overlaps(host) }
            val minimumHeight = if (fullButton) 35f * f.density - 1f else 30f * f.density
            val insideBody = !dialog || scroll != null && target != null &&
                target.left >= scroll.left - 1f && target.right <= scroll.right + 1f &&
                target.top >= scroll.top - 1f && target.bottom <= scroll.bottom + 1f
            if (host != null && target != null && target.width >= 20 * f.density && target.height >= minimumHeight && insideBody &&
                target.left >= host.left - 1f && target.right <= host.right + 1f &&
                target.top >= host.top - 1f && target.bottom <= host.bottom + 1f &&
                target.left >= 0f && target.top >= 0f && target.right <= f.pixelWidth && target.bottom <= f.pixelHeight) return target
            val direction = if (target != null && scroll != null && target.bottom < scroll.top) -5f else 5f
            val x = scroll?.center?.x ?: (host?.center?.x ?: f.pixelWidth / 2f)
            val y = scroll?.center?.y ?: (host?.center?.y ?: f.pixelHeight / 2f)
            scrollAt(x.coerceIn(8f, f.pixelWidth - 8f), y.coerceIn(8f, f.pixelHeight - 8f), deltaY = direction)
            settledFrame(4, 32)
        }
        error("Fully visible target not reached")
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.clickLabel(label: String, f: Fixture, dialog: Boolean = false) {
        click(reveal(label, f, dialog)); settledFrame()
    }
    private fun ComposePixelScene.replaceEditor(value: String, f: Fixture) {
        replaceDeviceNameEditorUsingKeys(revealBounds(f, true) { editorBounds() }, value)
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val file = File("build/settings-local-device-name-validation/frames/$name.png")
        file.parentFile.mkdirs(); ImageIO.write(image, "png", file)
    }
}
