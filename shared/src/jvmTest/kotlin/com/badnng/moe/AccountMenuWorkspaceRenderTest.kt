package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Exercise the real workspace parent constraints and opening animation, not an isolated card. */
class AccountMenuWorkspaceRenderTest {
    @Test
    fun clickingTheWorkspaceAvatarPaintsItsMenuAndOpensTheActualAccountProfile() {
        for (densityScale in listOf(1f, 1.25f)) {
            for (widthDp in listOf(1160, 380)) {
                for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                    verifyAccountMenu(widthDp, densityScale, mode)
                }
            }
        }
    }

    private fun verifyAccountMenu(widthDp: Int, densityScale: Float, mode: ColorSchemeMode) {
        val width = (widthDp * densityScale).roundToInt()
        val height = (796 * densityScale).roundToInt()
        val fixture = "width=$widthDp, density=$densityScale, theme=$mode"
        var menuFill = 0
        ComposePixelScene(width, height) {
            val controller = remember { ThemeController(mode) }
            val snapshot = remember {
                ChatSnapshot(
                    enabled = true,
                    token = ChatToken(1, 0, null),
                    account = AccountProfile(900001, "合成测试账号", "synthetic_profile", "00000000000"),
                    listEnd = true,
                )
            }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
                MiuixTheme(controller = controller) {
                    menuFill = MiuixTheme.colorScheme.surfaceContainer.toArgb()
                    ChatWorkspace(
                        snapshot = snapshot, connection = ConnectionPhase.READY, drafts = drafts,
                        onSettings = {}, onSelectChat = { _, _ -> }, onLoadChats = {}, onLoadOlder = {},
                        onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                        windowFocused = true, features = ChatFeatureActions(),
                        darkTheme = mode == ColorSchemeMode.Dark, onDarkTheme = {},
                        persistUiPreferences = false,
                    )
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val avatar = assertNotNull(scene.descriptionBounds("打开账号菜单").firstOrNull(), fixture)
            assertTrue(scene.buttonTextBounds("我的资料").isEmpty(), "The menu must initially be closed: $fixture")

            scene.click(avatar)
            val opened = scene.settledFrame()
            val profile = assertNotNull(scene.buttonTextBounds("我的资料").firstOrNull(),
                "Clicking the actual workspace avatar must expose the profile action: $fixture")
            assertTrue(profile.width >= 200f * densityScale && profile.height > 0f,
                "The menu body must have usable width and height: $profile, $fixture")
            assertTrue(profile.left >= 0f && profile.right <= width && profile.top >= avatar.bottom && profile.bottom <= height,
                "The profile action must remain below the avatar and inside the viewport: $profile, $fixture")
            // This point is inside the row's empty leading padding, away from the icon and
            // squircle corner. A composed but transparent/clipped card must not pass this check.
            val fillX = (profile.left + 2f * densityScale).roundToInt()
            val fillY = profile.center.y.roundToInt()
            assertTrue(colorDistance(opened[fillX, fillY], menuFill) <= 2,
                "The real menu must paint its opaque surface above the blurred workspace: $fixture, " +
                    "bounds=$profile, sample=($fillX,$fillY), actual=${opened[fillX, fillY].toUInt().toString(16)}, expected=${menuFill.toUInt().toString(16)}")

            // Reopening follows the real animated exit and reuses the same workspace.
            scene.moveTo(width - 8f, height - 8f)
            scene.pressAt(width - 8f, height - 8f)
            scene.releaseAt(width - 8f, height - 8f)
            scene.settledFrame()
            assertTrue(scene.buttonTextBounds("我的资料").isEmpty(), "An outside click must finish dismissing the menu: $fixture")
            val restoredAvatar = assertNotNull(scene.descriptionBounds("打开账号菜单").firstOrNull(), fixture)
            scene.click(restoredAvatar)
            val reopened = scene.settledFrame()
            val reopenedProfile = assertNotNull(scene.buttonTextBounds("我的资料").firstOrNull(),
                "The account menu must remain available after closing and reopening: $fixture")
            assertTrue(colorDistance(reopened[fillX, fillY], menuFill) <= 2,
                "The reopened menu must still paint above the blurred workspace: $fixture")

            scene.click(reopenedProfile)
            scene.settledFrame()
            assertTrue(scene.buttonTextBounds("我的资料").isEmpty(), "Selecting profile must dismiss the account menu: $fixture")
            assertTrue(scene.textBounds("Telegram 账号资料").isNotEmpty(),
                "The real profile dialog must open from the menu action: $fixture")
            assertTrue(scene.textBounds("合成测试账号").isNotEmpty(),
                "The dialog must display the selected account's synthetic name: $fixture")
            assertTrue(scene.textBounds("@synthetic_profile").isNotEmpty(),
                "The dialog must display the selected account's synthetic username: $fixture")
        }
    }

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
