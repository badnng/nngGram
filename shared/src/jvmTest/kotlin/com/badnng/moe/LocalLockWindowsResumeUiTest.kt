package com.badnng.moe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.settings.LocalLockActions
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.settings.LocalLockSnapshot
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic snapshots and offscreen semantics only; no real Tao or power event. */
class LocalLockWindowsResumeUiTest {
    @Test
    fun resumeAndSystemIdleWarningsAreIndependentlyProjectedForEveryCapabilityCombination() {
        for (enabled in listOf(false, true)) for (resumeUnavailable in listOf(false, true)) {
            for (idleFallback in listOf(false, true)) {
                val f = Fixture(enabled, resumeUnavailable, idleFallback, authorized = enabled)
                f.scene().use { scene ->
                    scene.settledFrame()
                    assertEquals(if (enabled && resumeUnavailable) 1 else 0, scene.texts(RESUME_WARNING).size)
                    assertEquals(if (enabled && idleFallback) 1 else 0, scene.texts(HOME_IDLE_WARNING).size)
                    assertEquals(0, f.timeoutWrites)
                }
            }
        }
    }

    @Test
    fun verifyManagementAndAutomaticPagesShowOneWarningAndTogglingDoesNotSaveADeadline() {
        for (page in listOf("verify", "management", "automatic")) {
            val f = Fixture(enabled = true, resumeUnavailable = true, idleFallback = true, authorized = page != "verify")
            f.scene().use { scene ->
                scene.settledFrame()
                if (page == "automatic") {
                    scene.click(assertNotNull(scene.texts("自动锁").firstOrNull()))
                    scene.settledFrame()
                    assertEquals(1, scene.texts(AUTO_IDLE_WARNING).size)
                }
                assertEquals(1, scene.texts(RESUME_WARNING).size, page)
                f.onUi { f.snapshot = f.snapshot.copy(resumeNotificationsUnavailable = false) }
                scene.settledFrame()
                assertTrue(scene.texts(RESUME_WARNING).isEmpty(), page)
                f.onUi { f.snapshot = f.snapshot.copy(resumeNotificationsUnavailable = true) }
                scene.settledFrame()
                assertEquals(1, scene.texts(RESUME_WARNING).size, page)
                assertEquals(0, f.timeoutWrites)
                assertEquals(3_600L, f.snapshot.autoLockSeconds)
            }
        }
    }

    @Test
    fun warningRemainsReachableInSmallLightAndDarkSurfacesAtBothDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (scale in listOf(1f, 1.25f)) {
            val width = ceil(350 * scale).toInt()
            val height = ceil(300 * scale).toInt()
            Fixture(enabled = true, resumeUnavailable = true, idleFallback = false, authorized = false)
                .scene(width, height, scale, mode).use { scene ->
                    scene.settledFrame()
                    val bounds = assertNotNull(scene.texts(RESUME_WARNING).singleOrNull())
                    assertTrue(bounds.width > 0 && bounds.height > 0)
                    assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                }
        }
    }

    private class Fixture(enabled: Boolean, resumeUnavailable: Boolean, idleFallback: Boolean, authorized: Boolean) {
        var snapshot by mutableStateOf(LocalLockSnapshot(
            phase = if (enabled) LocalLockPhase.UNLOCKED else LocalLockPhase.UNPROTECTED,
            enabled = enabled, epoch = 2, managementAuthorized = authorized, canManage = authorized,
            canChange = authorized, canRemove = authorized, canAutoLock = authorized,
            resumeNotificationsUnavailable = resumeUnavailable, idleFallback = idleFallback,
        ))
        var timeoutWrites = 0
        private val actions = LocalLockActions(setAutoLockSeconds = { timeoutWrites++ })

        fun scene(width: Int = 640, height: Int = 900, scale: Float = 1f,
            mode: ColorSchemeMode = ColorSchemeMode.Light) = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SettingsLocalLockPage(snapshot, actions, active = true, onBack = {})
                    }
                }
            }
        }
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }

    private fun ComposePixelScene.texts(label: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0 && it.height > 0 }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }

    private companion object {
        const val RESUME_WARNING = "系统恢复通知暂不可用；恢复后将在下次窗口活动时检查锁定"
        const val HOME_IDLE_WARNING = "系统空闲信息暂不可用，自动锁使用应用内活动计时。"
        const val AUTO_IDLE_WARNING = "系统空闲信息暂不可用，当前使用应用内活动计时。"
    }
}
