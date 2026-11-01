package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageReactionOptions
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real popup input and painted surfaces, including the quick-to-full reaction transition. */
class MessageActionsReactionRenderTest {
    @Test
    fun quickReactionsShareTheActionSurfaceAndExpandInTheSamePopup() {
        for (density in listOf(1f, 1.25f)) verifyReactionExpansion(density, nearRightEdge = false)
    }

    @Test
    fun expandingNearTheRightEdgeOnlyMovesEnoughToKeepThePickerInsideTheViewport() {
        verifyReactionExpansion(1.25f, nearRightEdge = true)
    }

    @Test
    fun aShortProtectedMenuNearTheBottomStillExpandsToAClickableReactionGrid() {
        verifyReactionExpansion(1.25f, nearRightEdge = false, shortNearBottom = true)
    }

    private fun verifyReactionExpansion(density: Float, nearRightEdge: Boolean, shortNearBottom: Boolean = false) {
        val width = (900 * density).roundToInt()
        val height = (650 * density).roundToInt()
        val fixture = "density=$density, nearRightEdge=$nearRightEdge, shortNearBottom=$shortNearBottom"
        val message = ChatMessage(100, 10, text = if (shortNearBottom) "" else "合成消息，只用于菜单回归")
        val token = ChatToken(1, 1, message.chatId)
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = token,
            selected = ChatItem(message.chatId, "合成聊天", hasProtectedContent = shortNearBottom), messages = listOf(message),
            actionsMessageId = message.id, messageActions = MessageActions(canForward = !shortNearBottom),
            reactionMessageId = message.id,
            reactionOptions = MessageReactionOptions(
                listOf("👍", "❤️", "😂", "🔥", "🎉", "🤔", "😍", "👏", "🙏", "👀", "💯", "🤝"), canAdd = true)))
        var expanded by mutableStateOf(false)
        var reactionLoads = 0
        var actionReloads = 0
        var externalPickerRequests = 0
        var dismissals = 0
        var surfaceColor = 0
        val selected = mutableListOf<Pair<String, Boolean>>()
        val features = ChatFeatureActions(
            loadReactions = { requestedToken, messageId ->
                assertEquals(token, requestedToken)
                assertEquals(message.id, messageId)
                reactionLoads++
            },
            setReaction = { requestedToken, messageId, emoji, add ->
                assertEquals(token, requestedToken)
                assertEquals(message.id, messageId)
                selected += emoji to add
            },
        )
        ComposePixelScene(width, height) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = controller) {
                    surfaceColor = MiuixTheme.colorScheme.surfaceContainer.toArgb()
                    Box(Modifier.fillMaxSize().background(Color(0xff26403c))) {
                        Box(Modifier.offset(if (nearRightEdge) 700.dp else 420.dp,
                            if (shortNearBottom) 560.dp else 160.dp).size(180.dp, 80.dp)) {
                            Box(Modifier.fillMaxSize().semantics { contentDescription = "打开测试消息菜单" }
                                .clickable(role = Role.Button) { expanded = true })
                            MessageActionsMenu(
                                message = message, snapshot = snapshot, canReply = true, canQuote = false,
                                onReply = {}, onQuote = {}, onReact = { externalPickerRequests++ },
                                onReadDetails = {}, onReload = { actionReloads++ }, onEdit = {},
                                onForward = {}, onDelete = {}, onDismiss = {
                                    dismissals++
                                    expanded = false
                                    // The real actor clears these immediately, before the retained Popup exits.
                                    snapshot = snapshot.copy(reactionMessageId = null, reactionOptions = null,
                                        actionsMessageId = null, messageActions = null)
                                },
                                expanded = expanded, features = features,
                            )
                        }
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val trigger = assertNotNull(scene.buttonDescriptionBounds("打开测试消息菜单").firstOrNull(), fixture)
            scene.click(trigger)
            val quickFrame = scene.settledFrame()
            val more = assertNotNull(scene.buttonDescriptionBounds("全部表情").firstOrNull(), fixture)
            val reply = assertNotNull(scene.buttonTextBounds("回复").firstOrNull(), fixture)
            val lastAction = assertNotNull(scene.buttonTextBounds(if (shortNearBottom) "拷贝消息链接" else "转发").firstOrNull(), fixture)
            val menu = Rect(reply.left - 6f * density, more.top - 6f * density,
                reply.right + 6f * density, lastAction.bottom + 6f * density)
            assertEquals(1, reactionLoads, "Opening the real menu loads native reaction options once: $fixture")
            assertTrue(menu.left > 250f * density, "The menu must actually be anchored in the conversation: $menu, $fixture")
            if (shortNearBottom) {
                assertTrue(scene.buttonTextBounds("复制文本").isEmpty() && scene.buttonTextBounds("转发").isEmpty(),
                    "The protected fixture must retain its native copy/forward restrictions.")
                assertTrue(menu.height < 170f * density,
                    "This fixture must reproduce a menu shorter than the full picker's fixed header: $menu")
            }
            // Sample the whole join along an empty column, away from emoji and row text.
            // Separate capsule/body surfaces with a 2dp gap expose the dark scene here.
            val joinX = (menu.left + 2f * density).roundToInt()
            for (y in (more.bottom + 3f * density).roundToInt() until reply.top.roundToInt()) {
                assertTrue(colorDistance(quickFrame[joinX, y], surfaceColor) <= 2,
                    "Quick reactions and action rows must share one continuous opaque surface at ($joinX,$y): $fixture")
            }

            scene.click(more)
            val fullFrame = scene.settledFrame()
            val title = assertNotNull(scene.textBounds("表情回应").firstOrNull(), fixture)
            val close = assertNotNull(scene.buttonDescriptionBounds("关闭").firstOrNull(), fixture)
            val pickerLeft = title.left - 12f * density
            val pickerTop = close.top - 12f * density
            val pickerRight = close.right + 12f * density
            val footer = assertNotNull(scene.textBounds("再次选择已选表情可取消回应").firstOrNull(), fixture)
            val pickerBottom = footer.bottom + 12f * density
            val pickerHeight = pickerBottom - pickerTop
            val shadowSpace = ChatShortcutShadowPadding.value * density
            val expectedLeft = min(menu.left, width - shadowSpace - 320f * density)
            val expectedTop = min(menu.top, height - shadowSpace - pickerHeight)
            assertTrue(abs(pickerLeft - expectedLeft) <= 2f,
                "Expanding must reuse the opening position, moving only for the right viewport edge: $menu -> ($pickerLeft,$pickerTop), $fixture")
            assertTrue(abs(pickerTop - expectedTop) <= 2f,
                "The full picker may move up only enough to clear the viewport's bottom edge: $menu -> ($pickerLeft,$pickerTop), $fixture")
            assertTrue(pickerLeft >= 0f && pickerRight <= width && pickerTop >= 0f && pickerBottom <= height,
                "The full picker must stay inside the viewport: $fixture")
            if (shortNearBottom) {
                assertTrue(pickerTop < menu.top - 10f * density && abs(pickerBottom - (height - shadowSpace)) <= 2f,
                    "A bottom-edge expansion must gain height by moving up, rather than clipping its grid: $fixture")
            }
            assertTrue(scene.buttonTextBounds("回复").isEmpty(), "The full picker replaces the menu body in place: $fixture")
            assertEquals(0, externalPickerRequests, "The embedded transition must not launch a separate popup: $fixture")
            assertEquals(1, reactionLoads, "Expanding cached options must not issue another native load: $fixture")
            assertEquals(0, actionReloads)
            val paintX = (pickerLeft + 4f * density).roundToInt()
            val paintY = (close.bottom + 6f * density).roundToInt()
            assertTrue(colorDistance(fullFrame[paintX, paintY], surfaceColor) <= 2,
                "The expanded picker must really paint at its retained origin: $fixture")

            val emoji = assertNotNull(scene.buttonDescriptionBounds("🤝").firstOrNull(), fixture)
            assertTrue(emoji.width > 0f && emoji.height >= 30f * density &&
                emoji.top >= pickerTop && emoji.bottom <= pickerBottom,
                "The full grid's final reaction must remain visible and have a real click target: $fixture")
            scene.click(emoji)
            repeat(2) {
                val exiting = scene.settledFrame(1)
                val sampleX = (pickerLeft + 30f * density).roundToInt()
                val sampleY = (pickerBottom - 18f * density).roundToInt()
                assertTrue(colorDistance(exiting[sampleX, sampleY], 0xff26403c.toInt()) > 20,
                    "Clearing the actor's native options must retain the full-height painted panel during exit: $fixture/frame=$it")
            }
            scene.settledFrame()
            assertEquals(listOf("🤝" to true), selected, "A real cell click must dispatch exactly one native reaction: $fixture")
            assertEquals(1, dismissals, "Selection must dismiss the same menu once: $fixture")
            assertTrue(!expanded && scene.textBounds("表情回应").isEmpty(),
                "The full panel must complete its exit after selection: $fixture")
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

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
