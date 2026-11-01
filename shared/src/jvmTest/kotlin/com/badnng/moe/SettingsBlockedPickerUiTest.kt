package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Real Compose pointer input with an inline confirmation host; no platform window/account. */
class SettingsBlockedPickerUiTest {
    @Test fun actualAddAndCancelOpenTheSecurityPickerWithoutDispatchingAMutation() {
        for (variant in variants) {
            val f = Fixture(variant)
            f.scene().use { scene ->
                scene.settledFrame()
                scene.click(scene.reachableText("添加用户", variant))
                scene.settledFrame().save("${variant.name}-add")
                assertEquals(listOf(f.token), f.opens)
                assertNotNull(scene.text("添加黑名单用户"))
                // Cancel belongs to the real scrolling dialog body. Semantics presence alone
                // does not establish that its clipped coordinates can receive pointer input.
                val cancel = scene.reachableText("取消", variant)
                scene.settledFrame().save("${variant.name}-cancel-visible")
                scene.click(cancel)
                scene.settledFrame()
                assertTrue(f.closes.isNotEmpty())
                assertTrue(f.closes.all { it == f.pickerToken && it.security == f.token })
                assertTrue(f.writes.isEmpty())
                assertTrue(scene.labels("添加黑名单用户").isEmpty())
            }
        }
    }

    @Test fun aBlockedRowCannotWriteAndChoosingARealRowClosesAndSubmitsOnce() {
        for (variant in variants) {
            val f = Fixture(variant)
            f.scene().use { scene ->
                scene.settledFrame()
                scene.click(scene.reachableText("添加用户", variant))
                scene.settledFrame()
                scene.click(scene.reachableText("Native blocked user", variant))
                scene.settledFrame()
                assertTrue(f.writes.isEmpty(), "Already blocked candidates are disabled in the actual picker.")
                val selectable = scene.reachableText("Native available user", variant)
                scene.settledFrame().save("${variant.name}-choose-visible")
                scene.click(selectable)
                scene.click(selectable) // Before a native result/snapshot arrives.
                scene.settledFrame().save("${variant.name}-choose-closed")
                assertEquals(listOf(51L), f.writes.map { it.second })
                assertEquals(f.pickerToken, f.writes.single().first)
                assertTrue(f.closes.contains(f.pickerToken))
                assertTrue(scene.labels("添加黑名单用户").isEmpty())
            }
        }
    }

    private data class Variant(val name: String, val width: Int, val height: Int,
        val density: Float, val theme: ColorSchemeMode)
    private val variants = listOf(
        Variant("wide-light-1x", 640, 1000, 1f, ColorSchemeMode.Light),
        Variant("wide-dark-125x", 800, 1000, 1.25f, ColorSchemeMode.Dark),
        Variant("narrow-light-125x", 390, 700, 1.25f, ColorSchemeMode.Light),
        Variant("narrow-dark-1x", 390, 700, 1f, ColorSchemeMode.Dark),
    )

    private class Fixture(val variant: Variant) {
        val token = SettingsSecurityToken(17, 9)
        val pickerToken = SettingsBlockedPickerToken(token, 1)
        var settings by mutableStateOf(SettingsSnapshot(token = SettingsToken(17, 1), enabled = true,
            loaded = setOf(SettingsSection.PRIVACY), security = SettingsSecuritySnapshot(
                token = token, section = SettingsSecuritySection.BLOCKED, blockedEnd = true)))
        val opens = mutableListOf<SettingsSecurityToken>()
        val closes = mutableListOf<SettingsBlockedPickerToken>()
        val writes = mutableListOf<Pair<SettingsBlockedPickerToken, Long>>()
        private val actions = SettingsActions(
            openBlockedPicker = { owner ->
                opens += owner
                settings = settings.copy(security = settings.security.copy(blockedPicker = SettingsBlockedPickerState(
                    pickerToken, SettingsBlockedPeerQuery(SettingsBlockedPeerSource.CONTACTS),
                    entries = listOf(SettingsBlockedCandidate(51, "Native available user", false),
                        SettingsBlockedCandidate(52, "Native blocked user", true)), end = true)))
            },
            closeBlockedPicker = { closes += it },
            blockPickerUser = { picker, userId, _ -> writes += picker to userId },
        )
        fun scene() = ComposePixelScene(variant.width, variant.height) {
            val theme = remember { ThemeController(variant.theme) }
            CompositionLocalProvider(LocalDensity provides Density(variant.density), LocalSettingsPageActive provides true,
                LocalSettingsSecurityConfirmationHost provides InlineHost) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SettingsSecurityPage(settings, actions)
                    }
                }
            }
        }
    }
    private object InlineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = 820.dp)) {
                title?.let { top.yukonga.miuix.kmp.basic.Text(it) }
                summary?.let { top.yukonga.miuix.kmp.basic.Text(it) }
                content()
            }
        }
    }
    private fun ComposePixelScene.labels(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(labels(label).firstOrNull(), label)
    /** Scroll actual pointer-owned viewports, including their parent when the inner one is exhausted. */
    private fun ComposePixelScene.reachableText(label: String, variant: Variant): Rect {
        fun visible(): Rect? = labels(label).firstOrNull {
            it.width > 0 && it.height >= 12f * variant.density && it.top >= 4f && it.bottom <= variant.height - 4f
        }
        data class ScrollRange(val value: Float, val max: Float)
        fun scrollers(): List<Pair<Rect, ScrollRange>> {
            val ranges = mutableListOf<ScrollRange>()
            val bounds = semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                    ranges += ScrollRange(it.value(), it.maxValue())
                    true
                } ?: false
            }
            return bounds.zip(ranges)
        }
        val initiallyVisible = visible() != null
        val initial = scrollers().map { it.second.value }
        var moved = false
        repeat(40) {
            val current = scrollers()
            if (current.map { it.second.value }.zip(initial).any { (now, before) -> kotlin.math.abs(now - before) > .5f }) moved = true
            visible()?.let {
                assertTrue(initiallyVisible || moved, "Clipped $label must be reached by actual scrolling.")
                return it
            }
            val target = labels(label).firstOrNull { it.width > 0 && it.height > 0 }
            val direction = if (target != null && target.bottom < 4f) -1f else 1f
            val scroll = current.lastOrNull { (bounds, range) ->
                bounds.width > 0 && bounds.height > 20 && bounds.bottom > 4 && bounds.top < variant.height - 4 &&
                    if (direction > 0) range.value < range.max - .5f else range.value > .5f
            }
            assertNotNull(scroll, "No movable viewport can reveal $label; bounds=${labels(label)}; ranges=$current")
            scrollAt(scroll.first.center.x, scroll.first.center.y.coerceIn(4f, variant.height - 4f), deltaY = direction * 5f)
            settledFrame(5, 32)
        }
        error("$label remains clipped after actual scrolling: ${labels(label)}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-blocked-picker-validation/frames/$name.png")
        output.parentFile.mkdirs()
        check(ImageIO.write(image, "png", output))
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
}
