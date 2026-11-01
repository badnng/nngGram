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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Observe the actual menu surface while real pointer input drives its morph. */
class ReactionPickerTransitionRenderTest {
    @Test
    fun quickActionsMorphThroughIntermediateSizesBeforeTheReactionGridAcceptsInput() {
        for (density in listOf(1f, 1.25f)) verifyMorph(density, nearBottom = false)
    }

    @Test
    fun aShortBottomMenuMovesUpWithItsGrowingSurfaceWithoutLeavingTheViewport() {
        verifyMorph(1.25f, nearBottom = true)
    }

    private fun verifyMorph(density: Float, nearBottom: Boolean) {
        val width = (900 * density).roundToInt()
        val height = (650 * density).roundToInt()
        val label = "density=$density, nearBottom=$nearBottom"
        val message = ChatMessage(100, 10, text = if (nearBottom) "" else "合成动画消息")
        val token = ChatToken(1, 1, message.chatId)
        val snapshot = ChatSnapshot(enabled = true, token = token,
            selected = ChatItem(message.chatId, "合成动画会话", hasProtectedContent = nearBottom),
            messages = listOf(message), actionsMessageId = message.id,
            messageActions = MessageActions(canForward = !nearBottom), reactionMessageId = message.id,
            reactionOptions = MessageReactionOptions(
                listOf("👍", "❤️", "😂", "🔥", "🎉", "🤔", "😍", "👏", "🙏", "👀", "💯", "🤝"),
                canAdd = true))
        var open by mutableStateOf(false)
        var loads = 0
        var replies = 0
        var separatePickers = 0
        var dismissals = 0
        val submitted = mutableListOf<String>()
        val features = ChatFeatureActions(
            loadReactions = { requested, id ->
                assertEquals(token, requested)
                assertEquals(message.id, id)
                loads++
            },
            setReaction = { requested, id, emoji, add ->
                assertEquals(token, requested)
                assertEquals(message.id, id)
                assertTrue(add)
                submitted += emoji
            },
        )
        ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(Color(0xff26403c))) {
                        Box(Modifier.offset(420.dp, if (nearBottom) 560.dp else 160.dp).size(180.dp, 80.dp)) {
                            Box(Modifier.fillMaxSize().semantics { contentDescription = "打开动画消息菜单" }
                                .clickable(role = Role.Button) { open = true })
                            MessageActionsMenu(message, snapshot, canReply = true, canQuote = false,
                                onReply = { replies++ }, onQuote = {}, onReact = { separatePickers++ },
                                onReadDetails = {}, onReload = {}, onEdit = {}, onForward = {}, onDelete = {},
                                onDismiss = { dismissals++; open = false }, expanded = open, features = features)
                        }
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.descriptionBounds("打开动画消息菜单").firstOrNull()))
            val resting = scene.settledFrame()
            val opening = scene.panelBounds()
            val reply = assertNotNull(scene.buttonTextBounds("回复").firstOrNull())
            val more = assertNotNull(scene.descriptionBounds("全部表情").firstOrNull())
            assertEquals(1, loads)

            scene.click(more)
            val frames = mutableListOf<Pair<Rect, ComposePixelFrame>>()
            frames += scene.panelBoundsAfterFrame()
            assertTrue(scene.buttonTextBounds("回复").isEmpty(),
                "Outgoing action rows must leave the accessible action tree immediately: $label")
            scene.click(reply)
            assertEquals(0, replies, "The disappearing reply row must not accept an old-position click: $label")
            repeat(7) { frames += scene.panelBoundsAfterFrame() }

            val completed = frames.last().first
            assertTrue(abs(opening.width - 248f * density) <= 2f && abs(completed.width - 320f * density) <= 2f,
                "The fixture must morph from the compact action width into the full picker: $opening -> $completed, $label")
            assertTrue(completed.height > opening.height + 20f * density,
                "The full picker must gain real surface height: $opening -> $completed, $label")
            val intermediate = frames.filter { (bounds, _) ->
                bounds.width > opening.width + 2f && bounds.width < completed.width - 2f &&
                    bounds.height > opening.height + 2f && bounds.height < completed.height - 2f
            }
            assertTrue(intermediate.map { it.first.width.roundToInt() to it.first.height.roundToInt() }.distinct().size >= 2,
                "A real size animation must produce several intermediate layouts, not switch directly to its target: ${frames.map { it.first }}, $label")
            val animatedBounds = opening.union(completed)
            assertTrue(changedPixels(resting, intermediate.first().second, animatedBounds) > 100,
                "The intermediate layout must actually paint its changing surface: $label")
            assertTrue(changedPixels(intermediate.first().second, frames.last().second, animatedBounds) > 100,
                "The final surface must continue changing after the observed middle frame: $label")
            for ((bounds, _) in frames) {
                assertTrue(bounds.left >= 11f * density && bounds.top >= 11f * density &&
                    bounds.right <= width - 11f * density && bounds.bottom <= height - 11f * density,
                    "Every animated frame must remain inside the popup margins: $bounds, $label")
            }
            if (nearBottom) {
                assertTrue(completed.top < opening.top - 10f * density,
                    "A growing bottom menu must gradually gain space above its opening point.")
                assertTrue(intermediate.any { it.first.top < opening.top - 1f && it.first.top > completed.top + 1f },
                    "The required upward avoidance must track the animated height rather than jump at the end.")
            } else {
                assertTrue(frames.all { abs(it.first.left - opening.left) <= 2f && abs(it.first.top - opening.top) <= 2f },
                    "A menu with sufficient space must keep its opening origin throughout the morph.")
            }
            assertEquals(1, loads, "The in-place animation must reuse native reaction options.")
            assertEquals(0, separatePickers)
            assertEquals(0, replies)
            assertTrue(submitted.isEmpty(), "Moving content must not submit a reaction before the transition completes.")

            scene.click(assertNotNull(scene.descriptionBounds("🤝").firstOrNull()))
            scene.settledFrame()
            assertEquals(listOf("🤝"), submitted)
            assertEquals(1, dismissals)
            assertTrue(scene.descriptionBounds("🤝").isEmpty())
        }
    }

    private fun ComposePixelScene.panelBoundsAfterFrame(): Pair<Rect, ComposePixelFrame> {
        val frame = settledFrame(1)
        return panelBounds() to frame
    }

    private fun ComposePixelScene.panelBounds(): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
    }.singleOrNull(), "The one popup surface must expose its actual animated bounds.")

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = semanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun Rect.union(other: Rect) = Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

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
