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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
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
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
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

/** Real composables and pointer input; all sessions, callbacks and addresses are synthetic. */
class SettingsSessionDetailsUiTest {
    @Test fun rowsKeepNativeOrderEvenWhenActivityAndTiesWouldSortDifferently() {
        val f = Fixture(sessions = listOf(remote(-4, "Native first", last = 5),
            current("Native current", last = 300), remote(-5, "Native third", last = 5)))
        f.scene().use { scene ->
            scene.settledFrame()
            val names = listOf("Native first", "Native current", "Native third").map { scene.text(it).top }
            assertTrue(names[0] < names[1] && names[1] < names[2], "The returned order must not be re-sorted")
        }
    }

    @Test fun currentAndOtherDetailsShowRealDatesAndCurrentNeverOffersTermination() {
        val f = Fixture(sessions = listOf(current("Current synthetic", login = 0, last = 0)))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickPage("Current synthetic", f); scene.settledFrame()
            assertTrue(scene.hasText("会话详情"))
            assertTrue(scene.hasText("当前设备"))
            assertTrue(scene.texts("未知").size >= 2, "Both missing timestamps are explicitly unknown")
            assertFalse(scene.hasText("终止此会话"))
            assertTrue(f.requests.isEmpty())
            scene.clickDialog("关闭", f); scene.settledFrame()
            assertFalse(scene.hasText("会话详情"))
        }
        val other = Fixture(sessions = listOf(remote(-7, "Other synthetic", pending = true, unconfirmed = true)))
        other.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", other); scene.settledFrame()
            assertTrue(scene.hasText("其他设备 · 等待两步验证 · 尚未从其他会话确认"))
            assertTrue(scene.hasText("2020-09-13 12:26 UTC"), "Login timestamp must include its date and UTC")
            assertTrue(scene.hasText("2023-11-14 22:13 UTC"), "Last activity is separately shown")
            assertTrue(scene.hasText("192.0.2.9"))
            assertTrue(other.requests.isEmpty())
        }
    }

    @Test fun staleConfirmationsCannotTerminateAChangedDeletedCurrentOrRefreshedTarget() {
        for (change in listOf("identity", "deleted", "current", "login", "authorization", "loading", "owner")) {
            val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
            f.scene().use { scene ->
                scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
                scene.clickDialog("终止此会话", f); scene.settledFrame()
                val oldConfirm = f.confirmCallback()
                when (change) {
                    "identity" -> f.update { it.copy(sessions = listOf(it.sessions.single().copy(deviceModel = "Changed device"))) }
                    "deleted" -> f.update { it.copy(sessions = emptyList()) }
                    "current" -> f.update { it.copy(sessions = listOf(it.sessions.single().copy(isCurrent = true))) }
                    "login" -> f.update { it.copy(sessions = listOf(it.sessions.single().copy(logInDate = 123))) }
                    "authorization" -> f.update { it.copy(sessions = listOf(it.sessions.single().copy(isUnconfirmed = true))) }
                    "loading" -> f.update { it.copy(loading = true) }
                    else -> f.update { it.copy(token = it.token.copy(owner = it.token.owner + 1)) }
                }
                scene.settledFrame()
                if (change == "loading") { f.update { it.copy(loading = false) }; scene.settledFrame() }
                f.onUi { oldConfirm() }; scene.settledFrame()
                assertTrue(f.requests.isEmpty(), change)
                assertFalse(scene.hasText("终止此会话？"), change)
            }
        }
    }

    @Test fun cancelAndReselectOwnDifferentConfirmationsAndOnlyNativeCallbackReportsSuccess() {
        val self = current("Current synthetic")
        val f = Fixture(sessions = listOf(self, remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            val oldConfirm = f.confirmCallback()
            scene.clickDialog("取消", f); scene.settledFrame()
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            f.onUi { oldConfirm() }; scene.settledFrame()
            assertTrue(f.requests.isEmpty())
            scene.clickDialog("终止会话", f); scene.settledFrame()
            assertEquals(listOf(-7L), f.requests.map { it.id })
            assertFalse(scene.hasText("会话已终止。"), "A submitted request is not yet a server success")
            f.update { it.copy(sessions = listOf(self), busy = false) }; scene.settledFrame()
            assertFalse(scene.hasText("会话已终止。"), "A refreshed snapshot is not itself an operation result")
            respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
            assertTrue(scene.hasText("会话已终止。"))
        }
    }

    @Test fun nativeResultAfterPublishedRefreshReportsSuccessAndRefreshFailureTruthfully() {
        for (refreshFailed in listOf(false, true)) {
            val self = current("Current synthetic")
            val other = remote(-7, "Other synthetic")
            val f = Fixture(sessions = listOf(self, other))
            f.scene().use { scene ->
                scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
                scene.clickDialog("终止此会话", f); scene.settledFrame()
                scene.clickDialog("终止会话", f); scene.settledFrame()
                assertEquals(1, f.requests.size)
                f.update { it.copy(busy = true) }; scene.settledFrame()
                f.update { snapshot ->
                    if (refreshFailed) snapshot.copy(busy = false, sessionsRefreshRequired = true,
                        error = SettingsError.REQUEST_FAILED)
                    else snapshot.copy(busy = false, sessions = listOf(self))
                }
                scene.settledFrame()
                assertTrue(scene.hasText("Current synthetic"))
                assertEquals(refreshFailed, scene.hasText("Other synthetic"), "Refresh failure retains the last list")
                assertFalse(scene.hasText("会话已终止。"))
                assertFalse(scene.hasText("会话已终止，列表刷新失败，请刷新列表。"))
                respondOffUi(f.requests.single().reply,
                    SettingsResult(Unit, if (refreshFailed) SettingsError.REQUEST_FAILED else null))
                scene.settledFrame()
                assertTrue(scene.hasText(if (refreshFailed) "会话已终止，列表刷新失败，请刷新列表。" else "会话已终止。"))
                assertFalse(scene.hasText(if (refreshFailed) "会话已终止。" else "会话已终止，列表刷新失败，请刷新列表。"))
            }
        }
    }

    @Test fun newSelectionAfterRemovalRevokesOldNoticeButItsCallbackReleasesSubmissionGate() {
        val self = current("Current synthetic")
        val terminated = remote(-7, "Other synthetic")
        val third = remote(-8, "Third synthetic")
        val f = Fixture(sessions = listOf(self, terminated, third))
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            scene.clickDialog("终止会话", f); scene.settledFrame()
            f.update { it.copy(busy = true) }; scene.settledFrame()
            f.update { it.copy(busy = false, sessions = listOf(self, third)) }; scene.settledFrame()
            scene.clickPage("Current synthetic", f); scene.settledFrame()
            assertTrue(scene.hasText("当前设备"))
            respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
            assertTrue(scene.hasText("会话详情"))
            assertFalse(scene.hasText("会话已终止。"))
            scene.clickDialog("关闭", f); scene.settledFrame()
            scene.clickPage("Third synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            assertTrue(scene.hasText("终止此会话？"), "The completed old request must release its submission gate")
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun refreshLoadingKeepsTheSamePagePendingResultOwner() {
        val self = current("Current synthetic")
        val f = Fixture(sessions = listOf(self, remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            scene.clickDialog("终止会话", f); scene.settledFrame()
            f.update { it.copy(busy = true) }; scene.settledFrame()
            f.update { it.copy(busy = false, sessions = listOf(self)) }; scene.settledFrame()
            f.update { it.copy(loading = true) }; scene.settledFrame()
            respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
            assertTrue(scene.hasText("会话已终止。"), "Refreshing the same page does not retire the accepted request")
            f.update { it.copy(loading = false) }; scene.settledFrame()
            assertTrue(scene.hasText("会话已终止。"))
        }
    }

    @Test fun staleDetailButtonsCannotActOnReselectionOrRemountedOwner() {
        val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
            val oldClose = f.closeCallback()
            val oldTerminate = f.terminateCallback()
            scene.clickDialog("关闭", f); scene.settledFrame()
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            f.onUi { oldClose(); oldTerminate() }; scene.settledFrame()
            assertTrue(scene.hasText("会话详情"))
            assertFalse(scene.hasText("终止此会话？"))
            f.onUi { f.mounted = false }; scene.settledFrame()
            f.onUi { f.mounted = true }; scene.settledFrame()
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            f.onUi { oldClose(); oldTerminate() }; scene.settledFrame()
            assertTrue(scene.hasText("会话详情"))
            assertFalse(scene.hasText("终止此会话？"))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun oldRowAndLateSubmissionCannotReviveAcrossRefreshOrPageReentry() {
        val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame()
            val oldRow = f.rowCallback(-7L)
            f.update { it.copy(loading = true) }; scene.settledFrame()
            f.update { it.copy(loading = false) }; scene.settledFrame()
            f.onUi { oldRow() }; scene.settledFrame()
            assertFalse(scene.hasText("会话详情"))
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            scene.clickDialog("终止会话", f); scene.settledFrame()
            assertEquals(1, f.requests.size)
            f.onUi { f.active = false }; scene.settledFrame()
            f.onUi { f.active = true }; scene.settledFrame()
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
            assertTrue(scene.hasText("会话详情"), "A late result must not close a new detail owner")
            assertFalse(scene.hasText("会话已终止。"), "A late result must not report into a reopened page")
        }
    }

    @Test fun workerResultAfterUnmountOrOwnerReplacementCannotWriteIntoNewDetails() {
        for (change in listOf("unmount", "token")) {
            val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
            f.scene().use { scene ->
                scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
                scene.clickDialog("终止此会话", f); scene.settledFrame()
                scene.clickDialog("终止会话", f); scene.settledFrame()
                assertEquals(1, f.requests.size)
                if (change == "unmount") {
                    f.onUi { f.mounted = false }; scene.settledFrame()
                    f.onUi { f.mounted = true }; scene.settledFrame()
                } else {
                    f.update { it.copy(token = it.token.copy(owner = it.token.owner + 1)) }; scene.settledFrame()
                }
                scene.clickPage("Other synthetic", f); scene.settledFrame()
                respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
                assertTrue(scene.hasText("会话详情"), change)
                assertFalse(scene.hasText("会话已终止。"), change)
            }
        }
    }

    @Test fun activityUpdatesStayVisibleButStableDeviceChangesCloseDetails() {
        val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickPage("Other synthetic", f); scene.settledFrame()
            f.update { it.copy(sessions = listOf(it.sessions.single().copy(lastActiveDate = 1_700_000_100))) }
            scene.settledFrame()
            assertTrue(scene.hasText("会话详情"))
            assertTrue(scene.hasText("2023-11-14 22:15 UTC"))
            f.update { it.copy(sessions = listOf(it.sessions.single().copy(location = "Changed location"))) }
            scene.settledFrame()
            assertFalse(scene.hasText("会话详情"))
        }
    }

    @Test fun stableTargetChangeRevokesOldRowAndPendingResultEvenIfTheOldValuesReturn() {
        val f = Fixture(sessions = listOf(remote(-7, "Other synthetic")))
        f.scene().use { scene ->
            scene.settledFrame()
            val oldRow = f.rowCallback(-7L)
            val original = f.security.sessions.single()
            f.update { it.copy(sessions = listOf(original.copy(location = "Changed location"))) }; scene.settledFrame()
            f.update { it.copy(sessions = listOf(original)) }; scene.settledFrame()
            f.onUi { oldRow() }; scene.settledFrame()
            assertFalse(scene.hasText("会话详情"), "A stale row cannot act after a stable target ABA")
            scene.clickPage("Other synthetic", f); scene.settledFrame()
            scene.clickDialog("终止此会话", f); scene.settledFrame()
            scene.clickDialog("终止会话", f); scene.settledFrame()
            assertEquals(1, f.requests.size)
            f.update { it.copy(sessions = listOf(original.copy(location = "Changed location"))) }; scene.settledFrame()
            f.update { it.copy(sessions = listOf(original)) }; scene.settledFrame()
            respondOffUi(f.requests.single().reply, SettingsResult(Unit)); scene.settledFrame()
            assertFalse(scene.hasText("会话已终止。"), "A result for the replaced target is not attributed to its successor")
        }
    }

    @Test fun detailBodyMatrixScrollsToItsRealActionsInBothThemesAndDensities() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (density in listOf(1f, 1.25f))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (isCurrent in listOf(true, false)) {
                val session = if (isCurrent) current("Current synthetic", login = 0, last = 0)
                    else remote(-7, "Other synthetic", pending = true)
                val f = DetailFixture(width, height, density, mode, session)
                f.scene().use { scene ->
                    scene.settledFrame().save("${width}x$height-$mode-$density-$isCurrent-top")
                    assertTrue(scene.hasText(if (isCurrent) "当前设备" else "其他设备 · 等待两步验证"))
                    scene.reveal("登录时间", f.pixelWidth, f.pixelHeight)
                    scene.reveal(if (isCurrent) "未知" else "2020-09-13 12:26 UTC", f.pixelWidth, f.pixelHeight)
                    scene.settledFrame().save("${width}x$height-$mode-$density-$isCurrent-date")
                    scene.scrollDetailsToEnd(f.pixelHeight)
                    val frame = scene.settledFrame()
                    frame.save("${width}x$height-$mode-$density-$isCurrent-bottom")
                    val close = scene.fullDetailButton("关闭", f)
                    val terminate = if (isCurrent) null else scene.fullDetailButton("终止此会话", f)
                    frame.assertCompleteButtonPixels(close, f.density)
                    terminate?.let { frame.assertCompleteButtonPixels(it, f.density) }
                    if (isCurrent) assertFalse(scene.hasText("终止此会话"))
                    else {
                        assertTrue(scene.hasText("终止此会话"))
                        scene.click(assertNotNull(terminate)); scene.settledFrame()
                        assertEquals(1, f.terminations)
                    }
                    scene.click(close); scene.settledFrame()
                    assertEquals(1, f.closes)
                    assertEquals(if (isCurrent) 0 else 1, f.terminations)
                }
            }
    }

    private data class Request(val token: SettingsSecurityToken, val id: Long, val reply: (SettingsResult<Unit>) -> Unit)

    private class Fixture(sessions: List<SettingsSession>) {
        val width = 700
        val height = 2400
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var security by mutableStateOf(SettingsSecuritySnapshot(token = SettingsSecurityToken(17, 9),
            section = SettingsSecuritySection.SESSIONS, sessions = sessions))
        val requests = mutableListOf<Request>()
        var callbacks: SettingsSessionUiCallbacks? = null
        val actions = SettingsActions(terminateSecuritySession = { token, id, reply -> requests += Request(token, id, reply) })
        fun rowCallback(id: Long) = assertNotNull(callbacks?.openRows?.get(id), "Missing actual row callback for $id")
        fun closeCallback() = assertNotNull(callbacks?.closeDetails, "Missing actual detail close callback")
        fun terminateCallback() = assertNotNull(callbacks?.terminateDetails, "Missing actual detail termination callback")
        fun confirmCallback() = assertNotNull(callbacks?.confirmTermination, "Missing actual confirmation callback")
        fun update(block: (SettingsSecuritySnapshot) -> SettingsSecuritySnapshot) = onUi { security = block(security) }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val host = remember { OfflineHost(height.dp) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides host,
                LocalSettingsSessionUiCallbacks provides { callbacks = it }) {
                MiuixTheme(controller = theme) {
                    if (mounted) Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SettingsSecurityPage(SettingsSnapshot(token = SettingsToken(17, 1), enabled = true,
                            security = security), actions)
                    }
                }
            }
        }
    }

    private class OfflineHost(private val height: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth).heightIn(max = height - 32.dp)
                .semantics { testTag = DIALOG_TAG }
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }
                summary?.let { Text(it) }
                content()
            }
        }
    }

    private class DetailFixture(width: Int, height: Int, val density: Float,
        private val mode: ColorSchemeMode, private val session: SettingsSession) {
        val pixelWidth = (width * density).roundToInt()
        val pixelHeight = (height * density).roundToInt()
        var closes = 0
        var terminations = 0
        fun scene() = ComposePixelScene(pixelWidth, pixelHeight) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 420.dp)
                            .semantics { testTag = DETAIL_CARD_TAG }
                            .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                            Text("会话详情")
                            SettingsSessionDetailContent(session, true, onClose = { closes++ }, onTerminate = { terminations++ })
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).firstOrNull(), label)
    private fun ComposePixelScene.visibleText(label: String, height: Int) = assertNotNull(texts(label).firstOrNull {
        it.width > 0 && it.height >= 12 && it.top >= 0 && it.bottom <= height
    }, label)
    private data class DetailScroll(val bounds: Rect, val value: Float, val maximum: Float)
    private fun ComposePixelScene.detailScroll(): DetailScroll {
        val values = mutableListOf<Pair<Float, Float>>()
        val bounds = semanticsBounds { config ->
            val range = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range == null) false else {
                values += range.value() to range.maxValue()
                true
            }
        }
        assertEquals(1, bounds.size, "The standalone detail body must have one real scroll range")
        return DetailScroll(bounds.single(), values.single().first, values.single().second)
    }
    private fun ComposePixelScene.scrollDetailsToEnd(height: Int) {
        var range = detailScroll()
        assertTrue(range.maximum > 0f, "The actual detail body must have scrollable content")
        repeat(160) {
            if (range.value >= range.maximum - .5f) return
            val viewport = range.bounds
            assertTrue(viewport.width > 0 && viewport.height > 0 && viewport.top >= 0 && viewport.bottom <= height,
                "The whole scroll viewport must remain in the test window")
            scrollAt(viewport.center.x, viewport.center.y, deltaY = 5f)
            settledFrame(5, 32)
            range = detailScroll()
        }
        assertTrue(range.value >= range.maximum - .5f, "Pointer scrolling must actually reach the end of the body")
    }
    private fun ComposePixelScene.fullDetailButton(label: String, f: DetailFixture): Rect {
        val button = visibleText(label, f.pixelHeight)
        val viewport = detailScroll().bounds
        val card = assertNotNull(semanticsBounds {
            it.getOrNull(SemanticsProperties.TestTag) == DETAIL_CARD_TAG
        }.singleOrNull(), "Missing actual detail card")
        assertTrue(button.height >= 35f * f.density - 1f, "$label must retain its complete 35 dp minimum height: $button")
        assertTrue(button.left >= viewport.left - 1f && button.right <= viewport.right + 1f &&
            button.top >= viewport.top - 1f && button.bottom <= viewport.bottom + 1f,
            "$label must fit in the full scroll viewport: button=$button viewport=$viewport")
        assertTrue(button.left >= card.left && button.right <= card.right && button.bottom <= f.pixelHeight &&
            card.bottom - button.bottom >= 11f * f.density - 1f,
            "$label needs the real card bottom inset: button=$button card=$card")
        return button
    }
    private fun ComposePixelFrame.assertCompleteButtonPixels(button: Rect, density: Float) {
        val x = (button.left + (button.width * .2f).coerceAtMost(20f * density)).roundToInt()
        val middle = (button.top + button.height * .5f).roundToInt()
        val bottomInside = (button.bottom - 2f * density).toInt()
        val bottomMargin = (button.bottom + 3f * density).toInt()
        assertTrue(x in 0 until width && middle in 0 until height && bottomInside in 0 until height && bottomMargin in 0 until height)
        fun distance(a: Int, b: Int) = listOf(0, 8, 16).maxOf { shift ->
            abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
        }
        val fill = this[x, middle]
        val lowerFill = this[x, bottomInside]
        val margin = this[x, bottomMargin]
        assertTrue(distance(fill, lowerFill) <= 12, "The button fill must reach its full bottom edge")
        assertTrue(distance(lowerFill, margin) >= 8, "A real card-colored margin must follow the button")
    }
    private fun ComposePixelScene.clickPage(label: String, f: Fixture) {
        reveal(label, f.width, f.height)
        click(visibleText(label, f.height))
    }
    private fun ComposePixelScene.clickDialog(label: String, f: Fixture) {
        repeat(60) {
            val host = assertNotNull(semanticsBounds { it.getOrNull(SemanticsProperties.TestTag) == DIALOG_TAG }.lastOrNull(),
                "Missing visible dialog for $label")
            // The session list also has a termination action; the dialog is composed after it.
            val target = texts(label).lastOrNull()
            if (target != null && target.width > 0 && target.height >= 30 && target.top >= 0 && target.bottom <= f.height &&
                target.left >= host.left && target.right <= host.right && target.top >= host.top && target.bottom <= host.bottom) {
                click(target)
                return
            }
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < f.height &&
                    it.right > host.left && it.left < host.right && it.bottom > host.top && it.top < host.bottom }
            val direction = if (target != null && target.width > 0 && target.bottom < host.top) -5f else 5f
            scrollAt(scroller?.center?.x ?: host.center.x.coerceIn(8f, f.width - 8f),
                scroller?.center?.y?.coerceIn(8f, f.height - 8f) ?: host.center.y.coerceIn(8f, f.height - 8f),
                deltaY = direction)
            settledFrame(5, 32)
        }
        throw AssertionError("Dialog action remains unreachable: $label")
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun respondOffUi(reply: (SettingsResult<Unit>) -> Unit, result: SettingsResult<Unit>) {
        var failure: Throwable? = null
        val worker = Thread { try { reply(result) } catch (error: Throwable) { failure = error } }
        worker.start()
        worker.join()
        failure?.let { throw it }
    }
    private fun ComposePixelScene.reveal(label: String, width: Int, height: Int) {
        repeat(50) {
            val candidates = texts(label)
            if (candidates.any { it.width > 0 && it.height >= 12 && it.top >= 0 && it.bottom <= height }) return
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < height }
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 0 }) -5f else 5f
            scrollAt(scroller?.center?.x ?: width / 2f, scroller?.center?.y?.coerceIn(8f, height - 8f) ?: height / 2f,
                deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(texts(label).any { it.width > 0 && it.height >= 12 && it.top >= 0 && it.bottom <= height }, label)
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-session-details-v4-recovery-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }
    private companion object {
        const val DIALOG_TAG = "settings-session-details-offline-dialog"
        const val DETAIL_CARD_TAG = "settings-session-details-matrix-card"
        fun current(name: String, login: Int = 1_600_000_000, last: Int = 1_700_000_000) =
            SettingsSession(0, true, name, "Synthetic app", "1.0", "Synthetic OS", "10", last,
                "192.0.2.9", "Synthetic location", login)
        fun remote(id: Long, name: String, login: Int = 1_600_000_000, last: Int = 1_700_000_000,
            pending: Boolean = false, unconfirmed: Boolean = false) =
            SettingsSession(id, false, name, "Synthetic app", "1.0", "Synthetic OS", "10", last,
                "192.0.2.9", "Synthetic location", login, pending, unconfirmed)
    }
}
