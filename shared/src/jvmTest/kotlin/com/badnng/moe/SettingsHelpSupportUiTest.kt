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
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSupportActions
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsHelpSupportUiTest {
    @Test fun cancelMakesNoRequestAndRepeatedTerminalCannotNavigateTwiceInNarrowLightAndDarkPages() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val f = Fixture(width = 350, height = 370, mode = mode)
            f.scene().use { scene ->
                scene.settledFrame()
                scene.click(scene.reveal("Telegram 常见问题", f))
                scene.click(scene.reveal("Telegram 功能介绍", f))
                assertEquals(listOf(SETTINGS_FAQ_URL, SETTINGS_FEATURES_URL), f.links)

                scene.click(scene.reveal("向志愿者提问", f))
                scene.settledFrame()
                assertEquals(0, f.calls.size, "Opening the confirmation has no native side effect.")
                scene.click(scene.reveal("取消", f))
                scene.settledFrame()
                assertEquals(0, f.calls.size, "Cancel must issue no native request.")

                val row = scene.reveal("向志愿者提问", f)
                assertTrue(row.top >= 0 && row.bottom <= f.height)
                scene.click(row)
                scene.click(scene.reveal("打开支持聊天", f))
                scene.settledFrame()
                assertEquals(1, f.calls.size)
                assertTrue(f.calls.single().lease())
                f.onUi {
                    f.complete(0, SettingsResult(value = ChatToken(7, 3, 88)))
                    f.complete(0, SettingsResult(value = ChatToken(7, 3, 88)))
                }
                scene.settledFrame()
                assertEquals(listOf(ChatToken(7, 3, 88)), f.opened)
            }
        }
    }

    @Test fun nativeFailureAndRejectedAdmissionBothLeaveAVisibleRetryPath() {
        val f = Fixture(admissions = listOf(true, false, true))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.confirmSupport(f)
            assertEquals(1, f.calls.size)
            f.onUi { f.complete(0, SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            scene.settledFrame()
            assertTrue(scene.hasText("请求未完成，请检查连接后重试。"))
            assertFalse(scene.hasText("synthetic native error"))

            scene.confirmSupport(f)
            scene.settledFrame()
            assertEquals(2, f.calls.size)
            assertFalse(assertNotNull(f.calls.lastOrNull()).lease())
            assertTrue(scene.hasText("账号尚未就绪，请稍后重试。"), "A false admission clears pending and reports a typed retryable error.")

            scene.confirmSupport(f)
            scene.settledFrame()
            assertEquals(3, f.calls.size)
            f.onUi { f.complete(2, SettingsResult(value = ChatToken(7, 5, 99))) }
            scene.settledFrame()
            assertEquals(listOf(ChatToken(7, 5, 99)), f.opened)
        }
    }

    @Test fun inactivePageAndNewGenerationRevokeOldCallbacksWithoutAStaleNavigation() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.confirmSupport(f)
            assertEquals(1, f.calls.size)
            val firstLease = f.calls[0].lease
            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(firstLease())
            f.onUi { f.active = true }
            scene.settledFrame()
            scene.confirmSupport(f)
            assertEquals(2, f.calls.size)

            f.onUi { f.complete(0, SettingsResult(value = ChatToken(7, 4, 70))) }
            scene.settledFrame()
            assertTrue(f.opened.isEmpty(), "A retired request cannot navigate after a newer request owns the page.")

            val secondLease = f.calls[1].lease
            f.onUi { f.token = ChatToken(8, 1, null) }
            scene.settledFrame()
            assertFalse(secondLease())
            f.onUi { f.complete(1, SettingsResult(value = ChatToken(7, 5, 71))) }
            scene.settledFrame()
            assertTrue(f.opened.isEmpty(), "A result from the old account generation is ignored.")

            scene.confirmSupport(f)
            assertEquals(3, f.calls.size)
            val thirdLease = f.calls[2].lease
            f.onUi { f.mounted = false }
            scene.settledFrame()
            assertFalse(thirdLease(), "Unmounting the Help page revokes its native lease.")
            f.onUi { f.complete(2, SettingsResult(value = ChatToken(8, 2, 72))) }
            scene.settledFrame()
            assertTrue(f.opened.isEmpty(), "A callback from an unmounted Help page cannot navigate.")
        }
    }

    @Test fun sameGenerationSelectionUpdateBeforeSuccessKeepsTheOwnedRequestAndAcceptsItsNewToken() {
        val f = Fixture(initialToken = ChatToken(7, 2, 22))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.confirmSupport(f)
            assertEquals(listOf(ChatToken(7, 2, 22)), f.calls.map { it.token })
            f.onUi { f.token = ChatToken(7, 3, 23) }
            scene.settledFrame()
            assertTrue(f.calls.single().lease(), "Same-generation selection updates are checked by the engine; they do not revoke a success token first.")
            f.onUi { f.complete(0, SettingsResult(value = ChatToken(7, 4, 88))) }
            scene.settledFrame()
            assertEquals(listOf(ChatToken(7, 4, 88)), f.opened)
        }
    }

    private class Fixture(
        val width: Int = 520,
        val height: Int = 700,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        admissions: List<Boolean> = emptyList(),
        initialToken: ChatToken = ChatToken(7, 1, null),
    ) {
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var token by mutableStateOf(initialToken)
        private val admissionPlan = admissions.toMutableList()
        private var nextAdmission = 0
        val links = mutableListOf<String>()
        val opened = mutableListOf<ChatToken>()
        val calls = mutableListOf<Call>()

        data class Call(val token: ChatToken, val lease: () -> Boolean,
            val completed: (SettingsResult<ChatToken>) -> Unit)

        private val support = SettingsSupportActions(open = { requestToken, lease, completed ->
            calls += Call(requestToken, lease, completed)
            val accepted = admissionPlan.getOrNull(nextAdmission++) ?: true
            if (!accepted) completed(SettingsResult(error = SettingsError.FORBIDDEN))
            accepted
        })

        fun complete(index: Int, result: SettingsResult<ChatToken>) = calls[index].completed(result)

        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }

        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmation(height.dp)) {
                MiuixTheme(controller = theme) {
                    if (mounted) Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SettingsHelpPage(SettingsPreferences(), openLink = { links += it; true }, chatToken = token,
                            support = support, onSupportOpened = { opened += it })
                    }
                }
            }
        }
    }

    private class OfflineConfirmation(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 24.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }
                content()
            }
        }
    }

    private fun ComposePixelScene.confirmSupport(f: Fixture) {
        click(reveal("向志愿者提问", f))
        click(reveal("打开支持聊天", f))
        settledFrame()
    }

    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }

    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        val inset = 4f
        val visibleBottom = f.height - inset
        val scanStep = 12f
        val exactStep = 4f
        var lastTarget: Rect? = null
        var lastCandidates: List<Rect> = emptyList()
        var lastScroller: Rect? = null
        var lastDeltaY = 0f
        var currentRange: Pair<Float, Float>? = null
        var initialRange: Pair<Float, Float>? = null
        val recentRanges = mutableListOf<Pair<Float, Float>>()

        fun readRange(): Pair<Float, Float>? {
            var result: Pair<Float, Float>? = null
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                    result = range.value() to range.maxValue()
                }
                false
            }
            return result
        }

        fun rememberRange() {
            currentRange = readRange()
            currentRange?.let { value ->
                recentRanges += value
                if (recentRanges.size > 12) recentRanges.removeAt(0)
            }
        }

        fun visibleTarget(): Rect? = texts(label).firstOrNull {
            it.width > 0 && it.height >= 12 && it.top >= inset && it.bottom <= visibleBottom
        }

        fun actualTarget(): Rect? = texts(label).firstOrNull { it.width > 0 && it.height > 0 }

        fun scroll(deltaY: Float) {
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < f.height }
            lastTarget = actualTarget()
            lastScroller = scroller
            lastDeltaY = deltaY
            scrollAt(scroller?.center?.x ?: f.width / 2f,
                scroller?.center?.y?.coerceIn(inset, visibleBottom) ?: f.height * .68f, deltaY = deltaY)
            settledFrame(5, 32)
            rememberRange()
            lastCandidates = texts(label)
            lastTarget = actualTarget()
        }

        fun boundedSteps(distance: Float): Int {
            if (!distance.isFinite() || distance <= 0f) return 1
            return ((distance / scanStep).toInt() + 4).coerceIn(1, 512)
        }

        fun moveTargetPrecisely(): Rect? {
            repeat(128) {
                visibleTarget()?.let { return it }
                val target = actualTarget() ?: return null
                val direction = when {
                    target.top < inset -> -exactStep
                    target.bottom > visibleBottom -> exactStep
                    else -> return null
                }
                val before = readRange()
                if (before != null && (direction < 0f && before.first <= .5f ||
                        direction > 0f && before.first >= before.second - .5f)) return null
                scroll(direction)
            }
            return visibleTarget()
        }

        visibleTarget()?.let { return it }
        rememberRange()
        initialRange = currentRange
        if (actualTarget() == null && currentRange != null) {
            // A fully clipped semantics node is Rect.Zero, so its side is unknowable.
            // First use real pointer input to reach the scroll origin, bounded by its native range.
            var stalls = 0
            for (step in 0 until boundedSteps(currentRange!!.first)) {
                visibleTarget()?.let { return it }
                val before = readRange() ?: break
                if (before.first <= .5f) break
                scroll(-scanStep)
                val after = currentRange
                if (after == null || after.first >= before.first - .5f) stalls++ else stalls = 0
                if (actualTarget() != null) {
                    moveTargetPrecisely()?.let { return it }
                    break
                }
                if (stalls >= 3) break
            }
            visibleTarget()?.let { return it }
            if (actualTarget() == null) {
                // From the origin, scan forward by real wheel events, bounded by maxValue.
                val start = readRange()
                val distance = start?.let { (it.second - it.first).coerceAtLeast(0f) } ?: 0f
                stalls = 0
                for (step in 0 until boundedSteps(distance)) {
                    visibleTarget()?.let { return it }
                    if (actualTarget() != null) {
                        moveTargetPrecisely()?.let { return it }
                        break
                    }
                    val before = readRange() ?: break
                    if (before.first >= before.second - .5f) break
                    scroll(scanStep)
                    val after = currentRange
                    if (after == null || after.first <= before.first + .5f) stalls++ else stalls = 0
                    if (stalls >= 3) break
                }
            }
        }

        moveTargetPrecisely()?.let { return it }
        val finalCandidates = texts(label)
        val finalRange = readRange()
        val finalScroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < f.height }
        val screenshotPath = runCatching {
            val frame = settledFrame()
            val output = File("build/settings-help-support-validation/frames/help-reveal-offline-${f.width}x${f.height}.png")
            output.parentFile.mkdirs()
            val bitmap = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) bitmap.setRGB(x, y, frame[x, y])
            check(ImageIO.write(bitmap, "png", output))
            output.path
        }.getOrElse { "capture failed: ${it.javaClass.simpleName}" }
        return assertNotNull(visibleTarget(), "Actual scrolling must reveal the complete control: $label; " +
            "lastTarget=$lastTarget; lastCandidates=$lastCandidates; lastScroller=$lastScroller; lastDeltaY=$lastDeltaY; " +
            "initialRange=$initialRange; recentRanges=$recentRanges; finalCandidates=$finalCandidates; " +
            "finalScroller=$finalScroller; finalRange=$finalRange; visibleY=$inset..$visibleBottom; screenshot=$screenshotPath")
    }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
        settledFrame()
    }
}
