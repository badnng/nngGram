package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class PrivacyCommonUiTest {
    @Test fun unknownNativeValuesAreNotDrawnAsOffAndClosedPageRetiresPendingOpeningLease() {
        val f = Fixture(data = PrivacyCommonData())
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.ToggleableState) != null }.isEmpty())
            assertTrue(f.writes.isEmpty())
            assertEquals(1, f.opens.size)
            assertTrue(f.opens.single()())
            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(f.opens.single()())
            assertTrue(f.writes.isEmpty())
        }
        assertTrue(f.closes.isNotEmpty())
    }

    @Test fun topPeersToggleSubmitsTheActualExpectedBooleanAndBusyStateBlocksASecondClick() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.text("建议常用联系人"))
            scene.settledFrame()
            assertEquals(listOf(Write("top", true, false)), f.writes)
            f.onUi { f.snapshot = f.snapshot.copy(busy = true) }
            scene.settledFrame()
            scene.click(scene.text("建议常用联系人"))
            scene.settledFrame()
            assertEquals(1, f.writes.size)
        }
    }

    @Test fun deniedArchiveCapabilityCannotEnableButCanStillDisableTheRealEnabledValue() {
        val f = Fixture(data = data().copy(archiveCapability = PrivacyArchiveCapability.DENIED))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("归档并静音", f)
            scene.click(scene.text("归档并静音"))
            scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            val enabled = PrivacyArchiveSettings(true, true, false)
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data.copy(archive = enabled)) }
            scene.settledFrame()
            scene.click(scene.text("归档并静音"))
            scene.settledFrame()
            assertEquals(listOf(Write("archive", enabled, enabled.copy(archiveAndMuteNewChats = false))), f.writes)
        }
    }

    @Test fun lowHeightAccountTtlRetains730AndNeedsAVisibleFrozenDangerConfirmation() {
        val f = Fixture(width = 390, height = 360, density = 1.25f, data = data().copy(accountTtlDays = 730))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("若离线时间超过", f)
            scene.click(scene.text("若离线时间超过"))
            scene.settledFrame()
            assertEquals(730, f.ui.ttlExpected)
            scene.reveal("30 天", f)
            scene.click(scene.text("30 天"))
            scene.settledFrame()
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            scene.reveal("确认", f)
            val confirmation = scene.text("确认")
            assertTrue(confirmation.height > 0 && confirmation.top >= 0 && confirmation.bottom <= f.height)
            scene.settledFrame().save("account-ttl-390x360-1.25")
            scene.click(confirmation)
            scene.settledFrame()
            assertEquals(listOf(Write("ttl", 730, 30)), f.writes)
        }
    }

    @Test fun staleTtlConfirmationAndReadbackFailureCannotWriteButTheDayDraftSurvives() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.reveal("若离线时间超过", f)
            scene.click(scene.text("若离线时间超过"))
            scene.settledFrame()
            scene.reveal("30 天", f)
            scene.click(scene.text("30 天"))
            scene.settledFrame()
            scene.reveal("保存", f)
            scene.click(scene.text("保存"))
            scene.settledFrame()
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data.copy(accountTtlDays = 720),
                refreshRequired = setOf(PrivacyCommonField.ACCOUNT_TTL)) }
            scene.settledFrame()
            scene.reveal("确认", f)
            scene.click(scene.text("确认"))
            scene.settledFrame()
            assertTrue(f.writes.isEmpty())
            assertEquals(30, f.ui.ttlDays)
            f.onUi { f.active = false }
            scene.settledFrame()
            assertNull(f.ui.confirmingTtl)
            assertTrue(f.ui.choosingTtl)
            assertEquals(30, f.ui.ttlDays)
            assertTrue(f.writes.isEmpty())
        }
    }

    private data class Write(val kind: String, val expected: Any, val desired: Any)
    private class Fixture(val width: Int = 640, val height: Int = 800, val density: Float = 1f, data: PrivacyCommonData = data()) {
        var active by mutableStateOf(true)
        var snapshot by mutableStateOf(PrivacyCommonSnapshot(PrivacyCommonToken(31, 4), opened = true,
            data = data, loaded = PrivacyCommonField.entries.toSet()))
        val ui = PrivacyCommonUiState()
        val writes = mutableListOf<Write>()
        val opens = mutableListOf<() -> Boolean>()
        val closes = mutableListOf<PrivacyCommonToken>()
        private val actions = PrivacyCommonActions(open = { _, lease -> opens += lease }, close = { closes += it },
            setTopPeers = { _, expected, desired -> writes += Write("top", expected, desired) },
            setArchive = { _, expected, desired -> writes += Write("archive", expected, desired) },
            setAccountTtl = { _, expected, desired -> writes += Write("ttl", expected, desired) })
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmation((height / density).dp)) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        PrivacyCommonContent(SettingsToken(31, 1), snapshot, actions, ui, enabled = true)
                    }
                }
            }
        }
    }
    private class OfflineConfirmation(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 24.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(10.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).firstOrNull { it.width > 0 && it.height > 0 }, label)
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture) {
        repeat(80) {
            val rects = texts(label)
            if (rects.any { it.width > 0 && it.height >= 12 && it.top >= 4 && it.bottom <= f.height - 4 }) return
            val scrolls = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val scroll = scrolls.lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < f.height }
            val direction = if (rects.any { it.width > 0 && it.height > 0 && it.bottom < 4 }) -4f else 4f
            scrollAt(scroll?.center?.x ?: f.width / 2f, scroll?.center?.y?.coerceIn(4f, f.height - 4f) ?: f.height * .7f, deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(texts(label).any { it.width > 0 && it.height >= 12 && it.top >= 4 && it.bottom <= f.height - 4 },
            "Actual scrolling must reveal $label: ${texts(label)}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val output = File("build/privacy-common-validation/frames/$name.png")
        output.parentFile.mkdirs()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        ImageIO.write(image, "png", output)
    }
    private companion object {
        fun data() = PrivacyCommonData(true, PrivacyArchiveSettings(false, true, false), PrivacyArchiveCapability.ALLOWED, 365)
    }
}
