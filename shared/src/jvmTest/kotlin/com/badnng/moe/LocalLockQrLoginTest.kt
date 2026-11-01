package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.status.AuthorizationDetails
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.LoginOtherStep
import com.badnng.moe.status.LoginPresentation
import com.badnng.moe.status.LoginState
import com.badnng.moe.status.LoginToken
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class LocalLockQrLoginTest {
    @Test
    fun protectedQrSessionCannotSwitchToAnUnsafeNewPhoneDirectory() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            val phone = scene.text("使用手机号登录")
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "使用手机号登录" } &&
                    it.getOrNull(SemanticsProperties.Disabled) != null
            }.isNotEmpty(), "The actual phone-switch control must expose its disabled state.")
            assertNotNull(scene.text("本机保护已启用，当前不能安全切换到新的手机号登录目录。可继续扫码登录。"))
            scene.click(phone)
            scene.settledFrame()
            assertTrue(f.cancelled.isEmpty())
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.isEmpty())

            f.onUi { f.allowed = true }
            scene.settledFrame()
            scene.click(scene.text("使用手机号登录"))
            scene.settledFrame()
            assertEquals(listOf(LoginToken(7, 1)), f.cancelled)
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.isEmpty(),
                "The UI waits for native state instead of fabricating a phone session.")
        }
    }

    private class Fixture {
        var allowed by mutableStateOf(false)
        val cancelled = mutableListOf<LoginToken>()
        private val login = LoginState(AuthorizationDetails(AuthorizationPhase.OTHER_STEP,
            otherStep = LoginOtherStep.DEVICE_CONFIRMATION,
            presentation = LoginPresentation(qrLink = "tg://login?token=synthetic_fixture")), revision = 1)

        fun scene() = ComposePixelScene(800, 720) {
            val window = LocalWindowInfo.current
            val focused = remember(window) { object : WindowInfo by window { override val isWindowFocused = true } }
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalWindowInfo provides focused) {
                MiuixTheme(controller = theme) {
                    AuthenticationLoginPage(login, LoginToken(7, 1), { true }, { _, _, _ -> },
                        { cancelled += it }, qrPhoneSwitchAvailable = allowed)
                }
            }
        }
        fun onUi(work: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) work() else SwingUtilities.invokeAndWait(work)
        }
    }

    private fun ComposePixelScene.text(label: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.firstOrNull(), label)

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
}
