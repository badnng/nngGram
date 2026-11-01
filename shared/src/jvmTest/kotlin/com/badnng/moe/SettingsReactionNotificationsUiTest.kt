package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
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
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Miuix controls and pointer/wheel input; synthetic state only, no OS windows or account. */
class SettingsReactionNotificationsUiTest {
    @Test fun capturedSourceConfirmCannotWriteAfterCancelInTheSameFrame() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealText("反应和投票", f))
            scene.click(scene.revealText("消息反应", f))
            scene.click(scene.revealChoice("所有人", f))
            val staleConfirm = scene.captureClick { it.hasLabel("确定") }
            val cancel = scene.captureClick { it.hasLabel("取消") }
            f.onUi {
                cancel()
                staleConfirm()
            }
            assertTrue(f.saves.isEmpty(), "Cancel immediately revokes the captured confirmation, before recomposition.")
            scene.settledFrame()
            assertFalse(scene.hasText("反应通知来源"))
        }
    }

    @Test fun capturedParentAndChildMutationsRespectLatestNavigationBeforeRecomposition() {
        val f = Fixture(value = value(message = ReactionNotificationSource.NONE))
        f.scene().use { scene ->
            scene.settledFrame()
            val staleParentToggle = scene.captureClick {
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("反应和投票 开关")
            }
            val enter = scene.captureClick { it.hasLabel("反应和投票") }
            f.onUi {
                enter()
                staleParentToggle()
            }
            assertTrue(f.saves.isEmpty(), "Entering details immediately revokes the old parent quick-toggle.")
            scene.settledFrame()
            val sourceToggle = scene.captureClick {
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("消息反应 开关")
            }
            val noneSourceBody = scene.captureClick { it.hasLabel("消息反应") }
            val pollToggle = scene.captureClick {
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("投票 开关")
            }
            val senderToggle = scene.captureClick { it.hasLabel("显示发送者") }
            val back = scene.captureClick { it.hasLabel("返回通知设置") }
            f.onUi {
                back()
                sourceToggle()
                noneSourceBody()
                pollToggle()
                senderToggle()
            }
            assertTrue(f.saves.isEmpty(), "Returning immediately revokes every captured child mutation, before recomposition.")
            scene.settledFrame()
            assertFalse(scene.hasText("返回通知设置"))
            assertEquals(1, f.opens.size, "These navigation gates retain the same native owner.")
        }
    }

    @Test fun leaseAdoptsOnlyANewerInitialOwnerAndNeverReplacesItsOwnedToken() {
        for (initiallyOpened in listOf(false, true)) {
            val initial = ReactionNotificationsToken(7, 5)
            val lease = ReactionNotificationsPageLease(7, initial, active = true)
            val snapshot = ReactionNotificationsSnapshot(token = initial, opened = initiallyOpened)
            assertNull(lease.observe(snapshot))
            assertNull(lease.observe(snapshot.copy(token = ReactionNotificationsToken(7, 4), opened = true)))
            assertNull(lease.observe(snapshot.copy(opened = true)))
            assertNull(lease.observe(snapshot.copy(token = ReactionNotificationsToken(8, 6), opened = true)))
            assertNull(lease.observe(snapshot.copy(token = ReactionNotificationsToken(7, 6), opened = false)))
            val owned = ReactionNotificationsToken(7, 6)
            assertEquals(owned, lease.observe(snapshot.copy(token = owned, opened = true)))
            assertEquals(owned, lease.observe(snapshot.copy(token = ReactionNotificationsToken(7, 7), opened = true)))
            assertEquals(owned, lease.close())
            assertFalse(lease.active())
            assertNull(lease.close())
        }
    }

    @Test fun parentSwitchOnlyChangesMessageAndPollWhileBodyNavigatesWithOneLease() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealSwitch("反应和投票", f))
            assertEquals(1, f.saves.size)
            val edit = f.saves.single().second
            assertEquals(ReactionNotificationPatch(message = ReactionNotificationSource.NONE, poll = ReactionNotificationSource.NONE), edit.patch)
            assertEquals(f.snapshot.native, edit.expected)
            assertFalse(scene.hasText("返回通知设置"), "Quick toggle must not navigate.")
            scene.click(scene.revealSwitch("反应和投票", f))
            assertEquals(1, f.saves.size, "Local gate blocks duplicate input before actor saving publication.")
            f.fail()
            scene.settledFrame()
            scene.click(scene.revealText("反应和投票", f))
            assertTrue(scene.hasText("返回通知设置"))
            assertEquals(1, f.opens.size)
            scene.click(scene.revealText("返回通知设置", f))
            assertEquals(1, f.opens.size)
            assertTrue(f.closes.isEmpty(), "Returning from details retains the same owner.")
            assertEquals(1, f.saves.size, "Neither navigation target may submit a patch.")
        }
        assertEquals(listOf(ReactionNotificationsToken(7, 1)), f.closes)
    }

    @Test fun noneBodyEnablesAllAndSourceDialogRequiresConfirmation() {
        val f = Fixture(value = value(message = ReactionNotificationSource.NONE))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealText("反应和投票", f))
            scene.click(scene.revealText("消息反应", f))
            assertEquals(ReactionNotificationPatch(message = ReactionNotificationSource.ALL), f.saves.single().second.patch)
            assertFalse(scene.hasText("反应通知来源"), "None body directly enables All.")
            f.accept()
            scene.settledFrame()
            scene.click(scene.revealText("投票", f))
            assertTrue(scene.hasText("反应通知来源"))
            scene.click(scene.revealChoice("所有人", f))
            assertEquals(1, f.saves.size, "Radio selection is a local dialog draft.")
            scene.click(scene.revealText("取消", f))
            assertEquals(1, f.saves.size)
            scene.click(scene.revealText("投票", f))
            scene.click(scene.revealChoice("所有人", f))
            scene.click(scene.revealText("确定", f))
            assertEquals(ReactionNotificationPatch(poll = ReactionNotificationSource.ALL), f.saves.last().second.patch)
            assertEquals(2, f.saves.size)
            assertTrue(f.saves.last().second.contentRevision > f.saves.first().second.contentRevision)
            assertEquals(ReactionNotificationSource.CONTACTS, f.saves.last().second.expected.value?.story)
            assertEquals(901L, f.saves.last().second.expected.value?.soundId)
            f.accept()
            scene.settledFrame()
            scene.click(scene.revealText("消息反应", f))
            scene.click(scene.revealChoice("我的联系人", f))
            scene.click(scene.revealText("取消", f))
            assertEquals(2, f.saves.size)
            scene.click(scene.revealText("消息反应", f))
            scene.click(scene.revealChoice("我的联系人", f))
            scene.click(scene.revealText("确定", f))
            assertEquals(ReactionNotificationPatch(message = ReactionNotificationSource.CONTACTS), f.saves.last().second.patch)
            assertEquals(3, f.saves.size)
        }
    }

    @Test fun showSenderRemainsEditableWhenBothSourcesAreNone() {
        val f = Fixture(value = value(message = ReactionNotificationSource.NONE, poll = ReactionNotificationSource.NONE))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealText("反应和投票", f))
            scene.click(scene.revealText("显示发送者", f))
            assertEquals(ReactionNotificationPatch(showSender = true), f.saves.single().second.patch)
        }
    }

    @Test fun sourceDialogIsInvalidatedByRemoteRevisionAndLosingPageOrOwner() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealText("反应和投票", f))
            scene.click(scene.revealText("消息反应", f))
            assertTrue(scene.hasText("反应通知来源"))
            f.onUi { f.snapshot = f.snapshot.copy(native = f.snapshot.native.copy(revision = 2)) }
            scene.settledFrame()
            assertFalse(scene.hasText("反应通知来源"))
            assertTrue(f.saves.isEmpty())
            scene.click(scene.revealText("消息反应", f))
            f.onUi { f.snapshot = f.snapshot.copy(token = ReactionNotificationsToken(7, 99)) }
            scene.settledFrame()
            assertFalse(scene.hasText("确定"))
            scene.click(scene.revealText("消息反应", f))
            assertTrue(f.saves.isEmpty(), "An unrelated owner cannot authorize this page.")
            val lease = f.leases.single()
            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(lease())
            assertEquals(listOf(ReactionNotificationsToken(7, 1)), f.closes)
            f.onUi { f.active = true }
            scene.settledFrame()
            assertEquals(2, f.opens.size)
            assertEquals(ReactionNotificationsToken(7, 2), f.snapshot.token)
            assertEquals(1, f.closes.size)
        }
    }

    @Test fun unknownInvalidSavingAwaitingAndConflictNeverAuthorizeWrites() {
        val f = Fixture(unknown = true)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("反应通知设置尚未读取。"))
            assertTrue(scene.switches("反应和投票").isEmpty(), "Unknown must not display a fabricated disabled value.")
            scene.click(scene.revealText("反应和投票", f))
            assertTrue(scene.switches("消息反应").isEmpty())
            scene.click(scene.revealText("查看当前反应设置", f))
            assertEquals(listOf(f.snapshot.token), f.refreshes)
            assertTrue(f.saves.isEmpty())
            val states = listOf(
                f.snapshot.copy(native = ReactionNotificationNativeState(value(), 1, SettingsError.INVALID_VALUE)),
                f.snapshot.copy(native = ReactionNotificationNativeState(value(), 1), saving = true),
                f.snapshot.copy(native = ReactionNotificationNativeState(value(), 1), activeAttempt = 9),
                f.snapshot.copy(native = ReactionNotificationNativeState(value(), 1), awaitingUpdate = true),
                f.snapshot.copy(native = ReactionNotificationNativeState(value(), 1), refreshRequired = true, error = SettingsError.CONFLICT),
            )
            states.forEach { state ->
                f.onUi { f.snapshot = state }
                scene.settledFrame()
                scene.click(scene.revealSwitch("消息反应", f))
                scene.click(scene.revealText("投票", f))
                scene.click(scene.revealText("显示发送者", f))
                assertTrue(f.saves.isEmpty())
            }
        }
    }

    @Test fun failureRetainsExactPatchForExplicitRebasedRetry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.revealSwitch("反应和投票", f))
            val original = f.saves.single().second
            f.fail()
            scene.settledFrame()
            assertTrue(scene.hasText("重试反应通知更改"))
            assertEquals(1, f.saves.size)
            f.onUi { f.snapshot = f.snapshot.copy(native = f.snapshot.native.copy(
                value = f.snapshot.native.value!!.copy(soundId = 777, story = ReactionNotificationSource.ALL), revision = 2)) }
            scene.settledFrame()
            scene.click(scene.revealText("重试反应通知更改", f))
            assertEquals(original.patch, f.saves.last().second.patch)
            assertEquals(2, f.saves.last().second.expected.revision)
            assertEquals(777L, f.saves.last().second.expected.value?.soundId)
            assertTrue(f.saves.last().second.contentRevision > original.contentRevision)
            f.onUi { f.snapshot = f.snapshot.copy(outcome = ReactionNotificationsOutcome(1, original, locallyAccepted = true)) }
            scene.settledFrame()
            scene.click(scene.revealSwitch("反应和投票", f))
            assertEquals(2, f.saves.size, "Late outcome cannot release the newer request's local gate.")
        }
    }

    @Test fun contentIdentityAndAcceptedAwaitingConflictsPreserveOnlyMatchingIntent() {
        val ui = ReactionNotificationsUiIntent()
        val ready = ReactionNotificationsSnapshot(opened = true, native = ReactionNotificationNativeState(value(), 1))
        val patch = ReactionNotificationPatch(showSender = true)
        val first = assertNotNull(ui.submit(ready, patch))
        assertNull(ui.submit(ready, patch), "Same-frame local gate works before actor state changes.")
        ui.reconcile(ready.copy(outcome = ReactionNotificationsOutcome(1, first, error = SettingsError.REQUEST_FAILED)))
        assertEquals(patch, ui.failed)
        val second = assertNotNull(ui.submit(ready.copy(outcome = ReactionNotificationsOutcome(1, first)), patch))
        ui.reconcile(ready.copy(outcome = ReactionNotificationsOutcome(1, first, locallyAccepted = true)))
        assertEquals(second, ui.pending)
        ui.reconcile(ready.copy(outcome = ReactionNotificationsOutcome(2, second, locallyAccepted = true), awaitingUpdate = true))
        assertNull(ui.pending)
        assertNull(ui.failed)
        ui.reconcile(ready.copy(native = ready.native.copy(revision = 2), refreshRequired = true))
        assertEquals(patch, ui.failed, "A mismatching later native update preserves explicit retry intent.")
        val third = assertNotNull(ui.submit(ready, patch))
        assertTrue(third.contentRevision > second.contentRevision)
    }

    @Test fun wideAndLowViewportsRenderBothThemesAndDensitiesWithActualPointerAndScroll() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                for (density in listOf(1f, 1.25f)) {
                    val f = Fixture(width, height, density, mode, value(message = ReactionNotificationSource.NONE))
                    val prefix = "${width}x$height-${mode.name}-$density"
                    f.scene(context = true).use { scene ->
                        scene.settledFrame()
                        scene.revealText("反应和投票", f)
                        scene.settledFrame().save("$prefix-entry")
                        scene.click(scene.revealSwitch("反应和投票", f))
                        assertEquals(ReactionNotificationPatch(message = ReactionNotificationSource.NONE, poll = ReactionNotificationSource.NONE), f.saves.single().second.patch)
                        assertFalse(scene.hasText("返回通知设置"))
                        f.fail()
                        scene.settledFrame()
                        scene.revealText("重试反应通知更改", f)
                        scene.settledFrame().save("$prefix-failure")
                        scene.click(scene.revealText("反应和投票", f))
                        scene.revealText("消息反应", f)
                        scene.settledFrame().save("$prefix-details")
                        scene.click(scene.revealText("消息反应", f))
                        assertEquals(ReactionNotificationPatch(message = ReactionNotificationSource.ALL), f.saves.last().second.patch)
                        f.accept()
                        scene.settledFrame()
                        scene.click(scene.revealText("投票", f))
                        scene.revealText("确定", f)
                        scene.settledFrame().save("$prefix-picker")
                        scene.click(scene.revealChoice("所有人", f))
                        scene.click(scene.revealText("取消", f))
                        assertEquals(2, f.saves.size)
                        scene.click(scene.revealText("投票", f))
                        scene.click(scene.revealChoice("所有人", f))
                        scene.click(scene.revealText("确定", f))
                        assertEquals(ReactionNotificationPatch(poll = ReactionNotificationSource.ALL), f.saves.last().second.patch)
                        f.accept()
                        scene.settledFrame()
                        scene.click(scene.revealText("显示发送者", f))
                        assertEquals(ReactionNotificationPatch(showSender = true), f.saves.last().second.patch)
                        f.accept()
                        scene.settledFrame()
                        scene.revealText("显示发送者", f)
                        scene.settledFrame().save("$prefix-sender")
                        assertEquals(1, f.opens.size)
                        assertTrue(f.scrollSamples > 0 || height == 900)
                    }
                }
            }
        }
    }

    private class Fixture(
        widthDp: Int = 620, heightDp: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        value: ReactionNotificationValue = value(), unknown: Boolean = false,
    ) {
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        var snapshot by mutableStateOf(ReactionNotificationsSnapshot(native =
            if (unknown) ReactionNotificationNativeState() else ReactionNotificationNativeState(value, 1)))
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var accountToken by mutableStateOf(SettingsToken(7, 1))
        val opens = mutableListOf<SettingsToken>()
        val leases = mutableListOf<() -> Boolean>()
        val closes = mutableListOf<ReactionNotificationsToken>()
        val saves = mutableListOf<Pair<ReactionNotificationsToken, ReactionNotificationsEdit>>()
        val refreshes = mutableListOf<ReactionNotificationsToken>()
        var scrollSamples = 0
        private var nextOwner = 0L
        val actions = ReactionNotificationsActions(open = { token, lease ->
            opens += token; leases += lease
            if (lease()) snapshot = snapshot.copy(token = ReactionNotificationsToken(token.generation, ++nextOwner), opened = true)
        }, close = { closes += it }, refreshFromCache = { refreshes += it }, save = { token, edit -> saves += token to edit })

        fun fail() = onUi {
            val (_, edit) = saves.last()
            snapshot = snapshot.copy(saving = false, activeAttempt = null,
                outcome = ReactionNotificationsOutcome(saves.size.toLong(), edit, error = SettingsError.REQUEST_FAILED),
                error = SettingsError.REQUEST_FAILED)
        }
        fun accept() = onUi {
            val (_, edit) = saves.last()
            snapshot = snapshot.copy(native = ReactionNotificationNativeState(edit.patch.applyTo(snapshot.native.value!!), snapshot.native.revision + 1),
                saving = false, activeAttempt = null, error = null, refreshRequired = false, awaitingUpdate = false,
                outcome = ReactionNotificationsOutcome(saves.size.toLong(), edit, locallyAccepted = true))
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene(context: Boolean = false) = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation) {
                MiuixTheme(controller = theme) {
                    if (mounted) Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SettingsReactionNotificationsHost(accountToken, snapshot, actions, active) { entry ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                if (context) listOf("私聊", "群组", "频道").forEach { label ->
                                    SettingsGroup(label) { SettingsRow("通知", "开启"); SettingsRow("提示音", "默认") }
                                }
                                entry()
                            }
                        }
                    }
                }
            }
        }
    }

    /** The production host is Miuix WindowDialog; render its identical body in CPU scene without an OS window. */
    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                title?.let { Text(it) }; summary?.let { Text(it) }; content()
            }
        }
    }

    private fun ComposePixelScene.hasText(label: String) = semanticsBounds { it.hasLabel(label) }.isNotEmpty()
    private fun ComposePixelScene.captureClick(predicate: (SemanticsConfiguration) -> Boolean): () -> Boolean {
        var action: (() -> Boolean)? = null
        semanticsBounds { config ->
            if (predicate(config)) config.getOrNull(SemanticsActions.OnClick)?.action?.let { action = it }
            false
        }
        return assertNotNull(action, "The real composed control must expose a click action.")
    }
    private fun SemanticsConfiguration.hasLabel(label: String) = getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    private fun ComposePixelScene.switches(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("$label 开关")
    }
    private fun ComposePixelScene.revealText(label: String, f: Fixture, last: Boolean = false) = reveal(f, last) { it.hasLabel(label) }
    private fun ComposePixelScene.revealChoice(label: String, f: Fixture) = reveal(f) {
        it.hasLabel(label) && it.getOrNull(SemanticsProperties.Role) == Role.RadioButton
    }
    private fun ComposePixelScene.revealSwitch(label: String, f: Fixture) = reveal(f) {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("$label 开关")
    }
    private fun ComposePixelScene.reveal(f: Fixture, last: Boolean = false, predicate: (SemanticsConfiguration) -> Boolean): Rect {
        val inset = 4f * f.density
        fun visible(): Rect? {
            val values = semanticsBounds(predicate).filter {
                it.width > 0 && it.height >= 12 * f.density && it.top >= inset && it.bottom <= f.height - inset
            }
            return if (last) values.lastOrNull() else values.firstOrNull()
        }
        fun range(): Pair<Float, Float>? {
            var found: Pair<Float, Float>? = null
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { found = it.value() to it.maxValue() }
                false
            }
            return found
        }
        var direction = if ((range()?.first ?: 0f) > 1f) -1 else 1
        var reversals = 0
        var stalls = 0
        repeat(512) {
            visible()?.let { return it }
            val before = range()
            if (before != null && (direction < 0 && before.first <= .5f || direction > 0 && before.first >= before.second - .5f)) {
                direction = -direction
                if (++reversals > 2) return@repeat
            }
            val candidates = semanticsBounds(predicate)
            val target = if (last) candidates.lastOrNull { it.height >= 12 * f.density } else candidates.firstOrNull { it.height >= 12 * f.density }
            val delta = if (target != null && target.top < inset) -4f else if (target != null && target.bottom > f.height - inset) 4f else direction * 12f
            scrollAt(f.width / 2f, f.height * .5f, deltaY = delta)
            f.scrollSamples++
            settledFrame(5, 32)
            val after = range()
            if (after == before) stalls++ else stalls = 0
            if (stalls >= 3) { direction = -direction; stalls = 0 }
        }
        return assertNotNull(visible(), "Pointer scrolling failed to reveal target: ${semanticsBounds(predicate)}; range=${range()}; ${f.width}×${f.height}")
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y)
        settledFrame()
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-reaction-notifications-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }

    companion object {
        private fun value(message: ReactionNotificationSource = ReactionNotificationSource.CONTACTS,
            poll: ReactionNotificationSource = ReactionNotificationSource.CONTACTS) = ReactionNotificationValue(
            message, ReactionNotificationSource.CONTACTS, poll, 901, showSender = false)
    }
}
