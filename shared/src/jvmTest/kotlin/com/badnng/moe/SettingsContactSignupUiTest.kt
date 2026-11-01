package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.ContactSignupSnapshot
import com.badnng.moe.settings.ContactSignupToken
import com.badnng.moe.settings.ContactSignupValue
import com.badnng.moe.settings.ContactSignupValueSource
import com.badnng.moe.settings.NotificationPreferences
import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsContactSignupUiTest {
    @Test fun unknownBusyAndReadbackStatesBlockWritesUntilAnExplicitFreshRead() {
        val f = Fixture(snapshot = ContactSignupSnapshot(
            token = ContactSignupToken(7, 0), loading = true, value = null))
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("正在读取联系人加入提醒…"))
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertTrue(f.saves.isEmpty(), "An unread value cannot be submitted.")

            f.onUi {
                f.snapshot = f.snapshot.copy(loading = false, saving = true, revision = 3,
                    value = ContactSignupValue(true, ContactSignupValueSource.DEFAULT))
            }
            scene.settledFrame()
            assertEquals(ToggleableState.On, scene.toggleState("联系人加入 Telegram 时通知我"))
            assertTrue(scene.hasText("当前读取值使用 Telegram 默认通知设置。"))
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertTrue(f.saves.isEmpty(), "An actual default value is still disabled during the controller's save.")

            f.onUi {
                f.snapshot = f.snapshot.copy(loading = false, saving = true, revision = 4,
                    value = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT))
            }
            scene.settledFrame()
            assertEquals(ToggleableState.Off, scene.toggleState("联系人加入 Telegram 时通知我"))
            assertTrue(scene.hasText("当前读取值来自此账号的单独设置。"))
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertTrue(f.saves.isEmpty(), "A controller-owned save remains disabled.")

            f.onUi {
                f.snapshot = f.snapshot.copy(saving = false, readbackPending = true,
                    desiredEnabled = true, refreshRequired = true, error = SettingsError.REQUEST_FAILED)
            }
            scene.settledFrame()
            assertTrue(scene.hasText("已提交开启联系人加入提醒，尚未读回本次设置。Telegram 可能需要一些时间在其他设备上显示更改。"))
            assertTrue(scene.hasText("请求未完成，请检查连接后重试。"))
            assertEquals(ToggleableState.Off, scene.toggleState("联系人加入 Telegram 时通知我"),
                "The switch shows the last actual reading while explaining the pending requested direction.")
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertTrue(f.saves.isEmpty(), "Readback uncertainty blocks writes without an optimistic local pending flag.")
            scene.click(scene.reveal("刷新联系人加入提醒", f))
            assertEquals(listOf(ContactSignupToken(7, 1)), f.refreshes)
            assertTrue(scene.hasText("已提交开启联系人加入提醒，尚未读回本次设置。Telegram 可能需要一些时间在其他设备上显示更改。"),
                "A refresh request alone is not a fresh value.")

            f.onUi {
                f.snapshot = f.snapshot.copy(loading = false, saving = false, readbackPending = false,
                    desiredEnabled = null, refreshRequired = false, error = null, revision = 5,
                    value = ContactSignupValue(true, ContactSignupValueSource.EXPLICIT))
            }
            scene.settledFrame()
            assertTrue(scene.hasText("当前读取值来自此账号的单独设置。"))
            assertEquals(ToggleableState.On, scene.toggleState("联系人加入 Telegram 时通知我"))
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertEquals(listOf(Save(ContactSignupToken(7, 1), 5,
                ContactSignupValue(true, ContactSignupValueSource.EXPLICIT), false)), f.saves)
            assertEquals(ToggleableState.On, scene.toggleState("联系人加入 Telegram 时通知我"))
            assertFalse(scene.hasText("正在提交更改…"), "The UI does not invent a pending spinner when admission has no snapshot update.")
        }
    }

    @Test fun pageLeaseOpensPerMountClosesItsMatchingOwnerAndSurvivesSnapshotTokenUpdates() {
        val f = Fixture(initialRevision = 0, openAdmissions = listOf(false, true, true, true, true),
            snapshot = ContactSignupSnapshot(token = ContactSignupToken(7, 0),
                value = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT)))
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(1, f.opened.size)
            assertEquals(SettingsToken(7, 0), f.opened[0], "The initial open can be rejected while settings are not ready.")
            val rejectedLease = f.leases[0]
            assertTrue(rejectedLease())
            assertFalse(f.snapshot.opened)

            f.onUi { f.accountToken = SettingsToken(7, 1) }
            scene.settledFrame()
            assertFalse(rejectedLease())
            assertEquals(2, f.opened.size, "A new SettingsToken revision retries the page open.")
            assertEquals(SettingsToken(7, 1), f.opened[1])
            val firstToken = f.snapshot.token
            val firstLease = f.leases[1]
            assertTrue(firstLease())
            assertTrue(f.closed.isEmpty(), "Publishing the first native owner does not dispose the active page lease.")

            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(firstLease())
            assertEquals(listOf(firstToken), f.closed)
            scene.click(scene.reveal("联系人加入 Telegram 时通知我", f))
            assertTrue(f.saves.isEmpty(), "An inactive page cannot dispatch a stale write.")

            f.onUi { f.active = true }
            scene.settledFrame()
            assertEquals(3, f.opened.size)
            val secondToken = f.snapshot.token
            val secondLease = f.leases[2]
            assertTrue(secondLease())
            assertEquals(listOf(firstToken), f.closed, "The new snapshot token is not closed by the old owner.")

            f.onUi { f.mounted = false }
            scene.settledFrame()
            assertFalse(secondLease())
            assertEquals(listOf(firstToken, secondToken), f.closed)

            f.onUi { f.mounted = true }
            scene.settledFrame()
            assertEquals(4, f.opened.size)
            assertTrue(f.leases[3]())
            f.onUi { f.accountToken = SettingsToken(8, 1) }
            scene.settledFrame()
            assertFalse(f.leases[3]())
            assertEquals(listOf(firstToken, secondToken, ContactSignupToken(7, 3)), f.closed)
            assertEquals(SettingsToken(8, 1), f.opened.last())
            assertEquals(5, f.opened.size)
            assertTrue(f.leases[4]())
        }
    }

    @Test fun actualToggleAndRefreshStayReachableAtWideAndLowViewportsInBothThemes() {
        val cases = listOf(
            Triple(620, 900, ColorSchemeMode.Light),
            Triple(620, 900, ColorSchemeMode.Dark),
            Triple(350, 300, ColorSchemeMode.Light),
            Triple(350, 300, ColorSchemeMode.Dark),
        )
        cases.forEach { (width, height, mode) ->
            val f = Fixture(width = width, height = height, mode = mode,
                snapshot = ContactSignupSnapshot(token = ContactSignupToken(7, 0),
                    value = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT)))
            f.scene(withNotificationContext = true).use { scene ->
                scene.settledFrame()
                val toggle = scene.reveal("联系人加入 Telegram 时通知我", f)
                assertTrue(toggle.top >= 4 && toggle.bottom <= height - 4)
                if (width == 350) scene.settledFrame().save("contact-signup-offline-${mode.name}-${width}x$height")
                scene.click(toggle)
                assertEquals(listOf(Save(ContactSignupToken(7, 1), 0,
                    ContactSignupValue(false, ContactSignupValueSource.EXPLICIT), true)), f.saves)

                val refresh = scene.reveal("刷新联系人加入提醒", f)
                assertTrue(refresh.top >= 4 && refresh.bottom <= height - 4)
                scene.click(refresh)
                assertEquals(listOf(ContactSignupToken(7, 1)), f.refreshes)
            }
        }
    }

    private data class Save(
        val token: ContactSignupToken,
        val revision: Long,
        val expected: ContactSignupValue,
        val desired: Boolean,
    )

    private class Fixture(
        val width: Int = 520,
        val height: Int = 720,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        initialGeneration: Long = 7,
        initialRevision: Long = 1,
        openAdmissions: List<Boolean> = emptyList(),
        snapshot: ContactSignupSnapshot = ContactSignupSnapshot(token = ContactSignupToken(initialGeneration, 0),
            value = ContactSignupValue(false, ContactSignupValueSource.EXPLICIT)),
    ) {
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var accountToken by mutableStateOf(SettingsToken(initialGeneration, initialRevision))
        var snapshot by mutableStateOf(snapshot)
        private var nextOwner = 0L
        private var nextOpenAdmission = 0
        private val openAdmissionPlan = openAdmissions.toMutableList()
        val opened = mutableListOf<SettingsToken>()
        val leases = mutableListOf<() -> Boolean>()
        val closed = mutableListOf<ContactSignupToken>()
        val refreshes = mutableListOf<ContactSignupToken>()
        val saves = mutableListOf<Save>()
        val actions = com.badnng.moe.settings.ContactSignupActions(
            open = { token, lease ->
                opened += token
                leases += lease
                val admitted = openAdmissionPlan.getOrNull(nextOpenAdmission++) ?: true
                if (admitted && lease()) {
                    nextOwner++
                    this.snapshot = this.snapshot.copy(token = ContactSignupToken(token.generation, nextOwner), opened = true)
                }
            },
            close = { closed += it },
            refresh = { refreshes += it },
            save = { token, revision, expected, desired -> saves += Save(token, revision, expected, desired) },
        )

        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }

        fun scene(withNotificationContext: Boolean = false) = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active) {
                MiuixTheme(controller = theme) {
                    if (mounted) Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (withNotificationContext) {
                            SettingsNotificationsPage(SettingsSnapshot(
                                token = accountToken,
                                enabled = true,
                                loaded = setOf(SettingsSection.NOTIFICATIONS),
                                notifications = NotificationScope.entries.associateWith { NotificationPreferences() },
                                contactSignup = snapshot,
                            ), SettingsActions(contactSignup = actions))
                        } else {
                            SettingsContactSignupControls(accountToken, snapshot, actions, active)
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

    private fun ComposePixelScene.toggleState(label: String): ToggleableState? {
        val states = mutableListOf<ToggleableState>()
        semanticsBounds { config ->
            if (config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }) {
                config.getOrNull(SemanticsProperties.ToggleableState)?.let(states::add)
            }
            false
        }
        return states.singleOrNull()
    }

    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        val inset = 4f
        val visibleBottom = f.height - inset
        val scanStep = 12f
        val recentRanges = mutableListOf<Pair<Float, Float>>()
        var lastScroller: Rect? = null
        var lastDelta = 0f

        fun range(): Pair<Float, Float>? {
            var value: Pair<Float, Float>? = null
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { axis ->
                    value = axis.value() to axis.maxValue()
                }
                false
            }
            return value
        }

        fun target() = texts(label).firstOrNull { it.width > 0 && it.height > 0 }
        fun visible() = texts(label).firstOrNull {
            it.width > 0 && it.height >= 12 && it.top >= inset && it.bottom <= visibleBottom
        }

        fun send(delta: Float) {
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < f.height }
            lastScroller = scroller
            lastDelta = delta
            scrollAt(scroller?.center?.x ?: f.width / 2f,
                scroller?.center?.y?.coerceIn(inset, visibleBottom) ?: f.height * .65f, deltaY = delta)
            settledFrame(5, 32)
            range()?.let {
                recentRanges += it
                if (recentRanges.size > 12) recentRanges.removeAt(0)
            }
        }

        fun budget(distance: Float) = if (distance.isFinite() && distance > 0f)
            ((distance / scanStep).toInt() + 4).coerceIn(1, 512) else 1

        fun precise(): Rect? {
            repeat(128) {
                visible()?.let { return it }
                val bounds = target() ?: return null
                val delta = when {
                    bounds.top < inset -> -4f
                    bounds.bottom > visibleBottom -> 4f
                    else -> return null
                }
                val current = range()
                if (current != null && (delta < 0 && current.first <= .5f ||
                        delta > 0 && current.first >= current.second - .5f)) return null
                send(delta)
            }
            return visible()
        }

        visible()?.let { return it }
        var current = range()
        val initial = current
        if (target() == null && current != null) {
            var stalled = 0
            for (step in 0 until budget(current.first)) {
                visible()?.let { return it }
                val before = range() ?: break
                if (before.first <= .5f) break
                send(-scanStep)
                current = range()
                if (current == null || current!!.first >= before.first - .5f) stalled++ else stalled = 0
                if (target() != null) {
                    precise()?.let { return it }
                    break
                }
                if (stalled >= 3) break
            }
            visible()?.let { return it }
            if (target() == null) {
                current = range()
                val distance = current?.let { (it.second - it.first).coerceAtLeast(0f) } ?: 0f
                stalled = 0
                for (step in 0 until budget(distance)) {
                    visible()?.let { return it }
                    if (target() != null) {
                        precise()?.let { return it }
                        break
                    }
                    val before = range() ?: break
                    if (before.first >= before.second - .5f) break
                    send(scanStep)
                    current = range()
                    if (current == null || current!!.first <= before.first + .5f) stalled++ else stalled = 0
                    if (stalled >= 3) break
                }
            }
        }
        precise()?.let { return it }
        val final = texts(label)
        return assertNotNull(final.firstOrNull { it.width > 0 && it.height >= 12 && it.top >= inset && it.bottom <= visibleBottom },
            "Actual scrolling must reveal $label; bounds=$final; initialRange=$initial; finalRange=${range()}; " +
                "recentRanges=$recentRanges; scroller=$lastScroller; deltaY=$lastDelta; viewportY=$inset..$visibleBottom")
    }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
        settledFrame()
    }

    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-contact-signup-validation/frames/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(image, "png", output)
    }
}
