package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatReportSpamState
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic native states, real Miuix controls and real pointer input; no account requests. */
class ChatReportSpamRenderTest {
    @Test
    fun onlyCurrentNativeEligibilityAddsANaturallyMeasuredReadableHeaderChild() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(mode, density, available = false)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val initialHeader = fixture.header
                val composer = fixture.composer
                assertTrue(scene.buttons(REPORT_LABEL).isEmpty(), "A group title alone grants no native report action.")
                fixture.snapshot = fixture.snapshot.copy(reportSpam = ChatReportSpamState())
                scene.settledFrame()
                assertEquals(initialHeader, fixture.header)

                fixture.native { copy(chatId = CHAT_ID, available = true) }
                scene.settledFrame()
                val panel = fixture.panel
                assertEquals(1, scene.buttons(REPORT_LABEL).size)
                assertTrue(panel.top >= fixture.toolbar.bottom + 7f * density)
                assertTrue(panel.height >= 42f * density - 1f)
                assertTrue(abs(panel.left - fixture.toolbar.left) <= 1f && abs(panel.right - fixture.toolbar.right) <= 1f)
                assertTrue(fixture.header.height >= initialHeader.height + 42f * density,
                    "The report bar must increase the header's natural measured height.")
                assertEquals(composer, fixture.composer, "The naturally measured header must not displace the fixed composer.")

                fixture.phase = 2
                val bright = scene.settledFrame()
                assertReadable(scene, bright, REPORT_LABEL, density, "$mode/$density/bright")
                fixture.phase = 3
                val dark = scene.settledFrame()
                assertReadable(scene, dark, REPORT_LABEL, density, "$mode/$density/dark")
                val x = panel.center.x.roundToInt()
                val y = (panel.bottom - 4f * density).roundToInt()
                val mixedDifference = distance(bright[x, y], dark[x, y])
                assertTrue(mixedDifference in 3..50,
                    "One strong glass tint must retain bounded source mixing over white and black: $mode/$density/$mixedDifference")
                assertTrue(distance(bright[(8 * density).roundToInt(), (300 * density).roundToInt()],
                    dark[(8 * density).roundToInt(), (300 * density).roundToInt()]) >= 240,
                    "The independent captured Canvas must actually change beneath the controls.")
                save(bright, "$mode-${density}x-bar-bright")
                save(dark, "$mode-${density}x-bar-dark")

                scene.click(scene.button(REPORT_LABEL))
                val dialog = scene.settledFrame()
                assertEquals(0, fixture.reports.size, "Opening the confirmation is not report authorization.")
                assertReadable(scene, dialog, SUPERGROUP_CONFIRMATION, density, "$mode/$density/dialog")
                assertReadable(scene, dialog, "取消", density, "$mode/$density/cancel")
                // Keep the native Miuix primary button's white-on-accent colors.
                assertReadable(scene, dialog, "举报并离开", density, "$mode/$density/submit", minimumContrast = 3.0)
                assertEquals(composer, fixture.composer)
                save(dialog, "$mode-${density}x-confirmation")
                scene.click(scene.button("取消"))
                scene.settledFrame()

                fixture.native { copy(chatId = OTHER_CHAT_ID) }
                scene.settledFrame()
                assertTrue(scene.buttons(REPORT_LABEL).isEmpty(), "Another chat's eligibility cannot expose this bar.")
                assertEquals(initialHeader, fixture.header)
                assertEquals(composer, fixture.composer)
                assertTrue(fixture.reports.isEmpty() && fixture.dismissals.isEmpty())
            }
        }
    }

    @Test
    fun closeOnlyDismissesTheBarAndCancelNeverSubmitsAReport() {
        val closing = Fixture()
        closing.scene().use { scene ->
            scene.settledFrame()
            val close = scene.descriptionButton("关闭举报提示")
            scene.click(close)
            scene.click(close)
            scene.settledFrame()
            assertEquals(listOf(closing.snapshot.token), closing.dismissals,
                "Two clicks before the native snapshot responds must dispatch only one dismiss request.")
            assertTrue(closing.reports.isEmpty(), "Closing the report bar must not report or leave the group.")
            assertTrue(scene.buttons("举报并离开").isEmpty())
            closing.native { copy(available = false, dismissPending = true) }
            scene.settledFrame()
            assertEquals(1, scene.buttons("正在关闭举报提示……").size,
                "A native pending dismissal remains visible even after eligibility is removed.")
            closing.native { copy(dismissPending = false) }
            scene.settledFrame()
            assertTrue(scene.buttons("正在关闭举报提示……").isEmpty())
            assertTrue(scene.buttons(REPORT_LABEL).isEmpty())
        }

        val cancelling = Fixture()
        cancelling.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.button(REPORT_LABEL))
            scene.settledFrame()
            assertTrue(cancelling.reports.isEmpty())
            scene.click(scene.button("取消"))
            scene.settledFrame()
            assertTrue(scene.buttons("举报并离开").isEmpty())
            assertEquals(1, scene.buttons(REPORT_LABEL).size)
            assertTrue(cancelling.reports.isEmpty() && cancelling.dismissals.isEmpty())

            scene.click(scene.button(REPORT_LABEL))
            scene.settledFrame()
            assertTrue(cancelling.reports.isEmpty(), "Reopening also requires its own explicit second click.")
            scene.click(scene.button("举报并离开"))
            scene.settledFrame()
            assertEquals(listOf(cancelling.snapshot.token), cancelling.reports)
            assertTrue(cancelling.dismissals.isEmpty())
        }
    }

    @Test
    fun pendingClicksStaySingleAndPartialLeaveFailureRetriesTheSameNativeAction() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.button(REPORT_LABEL))
            scene.settledFrame()
            val submit = scene.button("举报并离开")
            scene.click(submit)
            scene.click(submit)
            scene.settledFrame()
            assertEquals(listOf(fixture.snapshot.token), fixture.reports,
                "Local dispatch ownership gates a second click before native pending arrives.")

            fixture.native { copy(reportPending = true) }
            scene.settledFrame()
            scene.click(submit)
            assertEquals(1, fixture.reports.size)
            fixture.native { copy(available = false, reportPending = false, reported = true, leavePending = true) }
            scene.settledFrame()
            assertTrue(scene.textNodes("正在离开群组……").isNotEmpty())
            fixture.native { copy(leavePending = false, error = ChatError.REQUEST_FAILED) }
            scene.settledFrame()
            assertTrue(scene.textNodes("此次将仅重试离开。").isNotEmpty())
            assertTrue(scene.descriptionButtons("关闭举报提示").isEmpty(),
                "The successful native report cannot be dismissed as if nothing happened.")
            val retry = scene.button("重试离开")
            scene.click(retry)
            scene.click(retry)
            scene.settledFrame()
            assertEquals(listOf(fixture.snapshot.token, fixture.snapshot.token), fixture.reports,
                "Retry uses the same actor action; the native reported state owns which unfinished step remains.")
            assertTrue(fixture.dismissals.isEmpty())
            fixture.native { copy(leavePending = true, error = null) }
            scene.settledFrame()
            fixture.native { copy(left = true, leavePending = false) }
            scene.settledFrame()
            assertTrue(scene.buttons("重试离开").isEmpty())
            assertTrue(scene.buttons("已举报 · 重试离开").isEmpty())
            assertTrue(scene.buttons(REPORT_LABEL).isEmpty(), "Only native leave completion hides a supergroup's bar.")
            assertEquals(2, fixture.reports.size)
        }
    }

    @Test
    fun basicGroupConfirmationExplainsOwnHistoryAndFailedCleanupRemainsRetryableAfterLeaving() {
        val fixture = Fixture(basicGroup = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.button(REPORT_LABEL))
            scene.settledFrame()
            val explanation = scene.allText().single { it.contains("确定向 Telegram") }
            assertTrue(explanation.contains("清除你自己的聊天记录"))
            assertTrue(explanation.contains("其他成员的聊天记录不会被删除"),
                "Basic-group confirmation must explain exactly whose history is removed.")
            assertTrue(fixture.reports.isEmpty())
            scene.click(scene.button("举报并离开"))
            scene.settledFrame()
            assertEquals(1, fixture.reports.size)
            fixture.native { copy(reportPending = true) }
            scene.settledFrame()
            fixture.native { copy(available = false, reportPending = false, reported = true, left = true,
                needsHistoryRemoval = true, clearPending = true) }
            scene.settledFrame()
            assertTrue(scene.textNodes("正在清除聊天记录……").isNotEmpty(),
                "Leaving alone must retain the actual native history-removal stage.")
            fixture.native { copy(clearPending = false, error = ChatError.REQUEST_FAILED) }
            scene.settledFrame()
            assertEquals(1, scene.buttons(CLEAR_RETRY_LABEL).size)
            assertTrue(scene.allText().any { it.contains("不会再次举报或离开群组") })
            val retry = scene.button("重试清除聊天记录")
            scene.click(retry)
            scene.click(retry)
            scene.settledFrame()
            assertEquals(listOf(fixture.snapshot.token, fixture.snapshot.token), fixture.reports)
            assertTrue(fixture.dismissals.isEmpty())
            fixture.native { copy(clearPending = true, error = null) }
            scene.settledFrame()
            fixture.native { copy(clearPending = false, cleared = true) }
            scene.settledFrame()
            assertTrue(scene.buttons(CLEAR_RETRY_LABEL).isEmpty())
            assertTrue(scene.buttons("重试清除聊天记录").isEmpty(),
                "The bar and confirmation disappear only after native own-history cleanup completes.")
            assertEquals(2, fixture.reports.size)
        }
    }

    @Test
    fun stalePageTokenOrNativeEligibilityCannotDispatchThroughAClosingConfirmation() {
        for (invalidation in listOf("page", "token", "eligibility", "eligibility-with-error", "other-chat", "engine")) {
            val fixture = Fixture()
            fixture.scene().use { scene ->
                scene.settledFrame()
                scene.click(scene.button(REPORT_LABEL))
                scene.settledFrame()
                val staleConfirm = scene.button("举报并离开")
                when (invalidation) {
                    "page" -> fixture.interactive = false
                    "token" -> fixture.snapshot = fixture.snapshot.copy(token = fixture.snapshot.token.copy(selection = 2))
                    "eligibility" -> fixture.native { copy(available = false) }
                    "eligibility-with-error" -> fixture.native { copy(available = false, error = ChatError.FORBIDDEN) }
                    "other-chat" -> fixture.snapshot = fixture.snapshot.copy(selected = ChatItem(OTHER_CHAT_ID, "Another synthetic group"))
                    "engine" -> fixture.snapshot = fixture.snapshot.copy(enabled = false)
                }
                scene.settledFrame(1, stepMillis = 16)
                scene.click(staleConfirm)
                scene.settledFrame()
                assertTrue(fixture.reports.isEmpty() && fixture.dismissals.isEmpty(),
                    "A retained or stale native confirmation cannot authorize dispatch after $invalidation.")
                if (invalidation == "eligibility-with-error") {
                    assertEquals(1, scene.buttons("举报未完成").size,
                        "An eligibility failure stays readable even though report permission is gone.")
                }
            }
        }
    }

    private class Fixture(
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        val density: Float = 1f,
        available: Boolean = true,
        basicGroup: Boolean = false,
    ) {
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, CHAT_ID),
            selected = ChatItem(CHAT_ID, "Synthetic native group", basicGroupId = 7.takeIf { basicGroup }?.toLong(),
                supergroupId = 8.takeUnless { basicGroup }?.toLong()),
            reportSpam = ChatReportSpamState(chatId = CHAT_ID, available = available, needsHistoryRemoval = basicGroup)))
        var interactive by mutableStateOf(true)
        var phase by mutableStateOf(0)
        var toolbar = Rect.Zero
        var panel = Rect.Zero
        var header = Rect.Zero
        var composer = Rect.Zero
        val reports = mutableListOf<ChatToken>()
        val dismissals = mutableListOf<ChatToken>()
        private val features = ChatFeatureActions(reportSpamAndLeave = { reports += it }, dismissSpamBar = { dismissals += it })

        fun native(update: ChatReportSpamState.() -> ChatReportSpamState) {
            val current = snapshot.reportSpam
            snapshot = snapshot.copy(reportSpam = current.update().copy(revision = current.revision + 1))
        }

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (600 * density).roundToInt()) {
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = remember { ThemeController(mode) }) {
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                // Capture the synthetic source alone. Neither header nor dialog can feed back into its glass.
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    val stripe = 4.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        val color = when (phase) {
                                            2 -> Color.White
                                            3 -> Color.Black
                                            else -> if ((index + phase) % 2 == 0) Color.White else Color(0xff164be8)
                                        }
                                        drawRect(color, Offset(index * stripe, 0f), Size(stripe, size.height))
                                    }
                                }
                                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)
                                    .onGloballyPositioned { header = it.boundsInWindow() },
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.fillMaxWidth().height(44.dp).chatGlassSurface(15.dp, shadow = false, tintAlpha = .94f)
                                        .onGloballyPositioned { toolbar = it.boundsInWindow() }, contentAlignment = Alignment.Center) {
                                        Text("Synthetic group header", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                                    }
                                    ChatReportSpamPanel(snapshot, features, interactive,
                                        Modifier.onGloballyPositioned { panel = it.boundsInWindow() })
                                }
                                Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().height(56.dp)
                                    .chatGlassSurface(15.dp, shadow = false, tintAlpha = .94f)
                                    .onGloballyPositioned { composer = it.boundsInWindow() }, contentAlignment = Alignment.Center) {
                                    Text("Synthetic fixed composer", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertReadable(scene: ComposePixelScene, frame: ComposePixelFrame, label: String, density: Float, stage: String,
        minimumContrast: Double = 4.5) {
        val bounds = assertNotNull(scene.textNodes(label).lastOrNull(), "Missing actual text at $stage: $label")
        assertTrue(bounds.height >= 12f * density, "The label must use a readable actual text size at $stage.")
        val backgroundX = (bounds.left - 6f * density).roundToInt().coerceIn(0, frame.width - 1)
        val backgroundY = bounds.center.y.roundToInt().coerceIn(0, frame.height - 1)
        val background = frame[backgroundX, backgroundY]
        var strongest = background
        var strongestContrast = 1.0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(frame.height))
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(frame.width)) {
                val candidate = frame[x, y]
                val contrast = contrast(candidate, background)
                if (contrast > strongestContrast) { strongestContrast = contrast; strongest = candidate }
            }
        var cores = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(frame.height))
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(frame.width)) {
                if (distance(frame[x, y], strongest) <= 16) cores++
            }
        assertTrue(cores >= 3 && strongestContrast >= minimumContrast,
            "Actual label pixels must retain $minimumContrast:1 contrast against their rendered surface at $stage: $label/$strongestContrast/$cores")
    }

    private fun ComposePixelScene.textNodes(label: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }

    private fun ComposePixelScene.allText(): List<String> = buildList {
        unmergedSemanticsBounds {
            addAll(it.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text })
            false
        }
    }

    private fun ComposePixelScene.buttons(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }

    private fun ComposePixelScene.button(label: String) = assertNotNull(buttons(label).singleOrNull(), "Missing actual button: $label")

    private fun ComposePixelScene.descriptionButtons(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button && label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.descriptionButton(label: String) =
        assertNotNull(descriptionButtons(label).singleOrNull(), "Missing actual icon action: $label")

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun save(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("nngGram.reportSpamFrames")
            ?: Path.of(System.getProperty("java.io.tmpdir"), "chat-report-spam").toString())
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        assertTrue(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private companion object {
        const val CHAT_ID = 10L
        const val OTHER_CHAT_ID = 20L
        const val REPORT_LABEL = "举报垃圾信息并离开"
        const val CLEAR_RETRY_LABEL = "已举报并离开 · 重试清除聊天记录"
        const val SUPERGROUP_CONFIRMATION = "确定向 Telegram 举报这个群组的垃圾信息并离开吗？"

        fun distance(a: Int, b: Int): Int = listOf(0, 8, 16).maxOf { abs((a ushr it and 255) - (b ushr it and 255)) }

        fun contrast(first: Int, second: Int): Double {
            fun luminance(pixel: Int): Double {
                fun linear(shift: Int): Double {
                    val channel = (pixel ushr shift and 255) / 255.0
                    return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
                }
                return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
            }
            val a = luminance(first)
            val b = luminance(second)
            return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
        }
    }
}
