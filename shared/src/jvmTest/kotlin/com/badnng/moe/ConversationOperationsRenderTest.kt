package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ConversationOperationsRenderTest {
    @Test
    fun nativeOperationsOpenBelowTheToolbarAndFinishTheirExitAfterSelectingInformation() {
        val chat = ChatItem(10, "Synthetic group", basicGroupId = 20)
        val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id))
        var menuOpen by mutableStateOf(false)
        var toolbarBounds = Rect.Zero
        var informationRequests = 0
        ComposePixelScene(900, 480) {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = controller) {
                Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    Box(Modifier.fillMaxSize().background(Color(0xff17191c))) {
                        ConversationHeader(
                            chat = chat, snapshot = snapshot, features = ChatFeatureActions(), active = true,
                            onBack = null, menuOpen = menuOpen, onMenuOpen = { menuOpen = it },
                            onMarkRead = {}, onBounds = { toolbarBounds = it }, onSearch = {}, onDetails = {},
                            onMedia = { informationRequests++ }, showInfoButton = true, onPinned = {},
                            onTogglePinned = {}, pinnedHidden = false,
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
                        )
                    }
                }
            }
        }.use { scene ->
            val resting = scene.settledFrame()
            val toolbar = toolbarBounds
            assertTrue(toolbar.width > 500f && toolbar.height >= 64f)
            val title = assertNotNull(scene.textBounds("Synthetic group").firstOrNull())
            val trigger = assertNotNull(scene.buttonDescriptionBounds("群操作").firstOrNull())
            assertTrue(scene.buttonTextBounds("查看群组信息").isEmpty())

            scene.click(trigger)
            val opened = scene.settledFrame()
            assertTrue(menuOpen, "The real toolbar trigger must open the Scaffold popup host.")
            val information = assertNotNull(scene.buttonTextBounds("查看群组信息").firstOrNull(),
                "The native overlay must compose its real actionable rows.")
            val lastRow = assertNotNull(scene.buttonTextBounds("标为未读").firstOrNull())
            // ConversationHeader owns 8dp of container padding around its operation rows.
            val menu = Rect(information.left - 8f, information.top - 8f, information.right + 8f, lastRow.bottom + 8f)
            assertTrue(menu.top >= toolbar.bottom, "The menu must not cover the toolbar: $menu vs $toolbar.")
            assertTrue(menu.left > toolbar.center.x, "The operations must open at the right end of the toolbar.")
            assertTrue(abs(menu.right - trigger.right) <= 2f, "The native End alignment must follow the real trigger.")
            assertEquals(0, changedPixels(resting, opened, title), "The title must remain fully visible without window dimming.")
            assertTrue(changedPixels(resting, opened, menu) > 200, "The overlay must really paint into the scene.")

            scene.click(information)
            val exiting = scene.settledFrame(frameCount = 1)
            assertFalse(menuOpen)
            assertEquals(1, informationRequests, "Selecting the real information row must invoke its action once.")
            assertTrue(scene.buttonTextBounds("查看群组信息").isNotEmpty(),
                "The menu must retain its content while the native exit animation runs.")
            assertTrue(changedPixels(resting, exiting, menu) > 20, "The first exit frame must still contain the menu.")

            scene.moveTo(10f, 450f)
            val closed = scene.settledFrame()
            assertTrue(scene.buttonTextBounds("查看群组信息").isEmpty(), "The host must remove the menu after the exit completes.")
            assertEquals(0, changedPixels(resting, closed, menu), "The native exit must leave no painted menu residue.")
            assertEquals(0, changedPixels(resting, closed, title), "The toolbar must remain visible after dismissal.")

            scene.click(trigger)
            scene.settledFrame()
            scene.moveTo(menu.right + 12f, menu.center.y)
            scene.pressAt(menu.right + 12f, menu.center.y)
            scene.releaseAt(menu.right + 12f, menu.center.y)
            scene.settledFrame()
            assertFalse(menuOpen, "The transparent shadow margin is outside the card and must dismiss it.")
            assertTrue(scene.buttonTextBounds("查看群组信息").isEmpty(),
                "A click inside native Popup bounds but outside the glass must finish its exit.")
        }
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonDescriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var changed = 0
        for (y in max(0, bounds.top.toInt()) until min(first.height, bounds.bottom.toInt())) {
            for (x in max(0, bounds.left.toInt()) until min(first.width, bounds.right.toInt())) {
                if (first[x, y] != second[x, y]) changed++
            }
        }
        return changed
    }
}
