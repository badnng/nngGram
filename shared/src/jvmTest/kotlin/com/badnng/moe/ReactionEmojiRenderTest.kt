package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.MessageReactionOptions
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ReactionEmojiRenderTest {
    @Test
    fun colorGlyphsStayInsideCellsAndKeepTheirOriginalEmojiSequenceWhenClicked() {
        for (densityScale in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                val emojiChoices = listOf("👍🏽", "❤️", "👩‍💻", "🔥", "🤯")
                val message = ChatMessage(id = 10, chatId = 20)
                val snapshot = ChatSnapshot(token = ChatToken(1, 1, message.chatId),
                    reactionMessageId = message.id, reactionOptions = MessageReactionOptions(emojiChoices, canAdd = true))
                var sent: String? = null
                ComposePixelScene((320 * densityScale).roundToInt(), (440 * densityScale).roundToInt()) {
                    val controller = remember { ThemeController(mode) }
                    CompositionLocalProvider(LocalDensity provides Density(densityScale, fontScale = 1.25f)) {
                        MiuixTheme(controller = controller) {
                            Box(Modifier.fillMaxSize()) {
                                MessageReactionPickerPanel(message, snapshot,
                                    ChatFeatureActions(setReaction = { _, _, emoji, _ -> sent = emoji }), {}, interactive = true)
                            }
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame()
                    val thumbs = assertNotNull(scene.emojiBounds("👍🏽").singleOrNull())
                    var coloredPixels = 0
                    for (y in thumbs.top.roundToInt() until thumbs.bottom.roundToInt()) {
                        for (x in thumbs.left.roundToInt() until thumbs.right.roundToInt()) {
                            if (isChromatic(frame[x, y])) coloredPixels++
                        }
                    }
                    assertTrue(coloredPixels > 20, "The cached emoji must still paint its color-font glyph: $mode, $densityScale")
                    // The font can exceed a nominal single-character width/height. The renderer
                    // fits the whole measured sequence with a small inset instead of clipping it.
                    for (emoji in emojiChoices) {
                        val cell = assertNotNull(scene.emojiBounds(emoji).singleOrNull(), "Missing original sequence: $emoji")
                        assertTrue(cell.width > 20 * densityScale && cell.height >= 36 * densityScale)
                        val left = cell.left.roundToInt()
                        val right = cell.right.roundToInt() - 1
                        val top = cell.top.roundToInt()
                        val bottom = cell.bottom.roundToInt() - 1
                        for (x in left..right) {
                            assertTrue(!isChromatic(frame[x, top]) && !isChromatic(frame[x, bottom]), "Glyph touches horizontal cell edge: $emoji")
                        }
                        for (y in top..bottom) {
                            assertTrue(!isChromatic(frame[left, y]) && !isChromatic(frame[right, y]), "Glyph touches vertical cell edge: $emoji")
                        }
                    }
                    val zwj = assertNotNull(scene.emojiBounds("👩‍💻").singleOrNull())
                    scene.click(zwj)
                    scene.settledFrame()
                    assertEquals("👩‍💻", sent, "Selection must preserve the complete native emoji sequence.")
                }
            }
        }
    }

    @Test
    fun cachedChoicesRefreshAndOnlyChosenReactionsRemainRemovableWithoutAddPermission() {
        val message = ChatMessage(id = 10, chatId = 20,
            reactions = listOf(MessageReaction("❤️", isChosen = true), MessageReaction(customEmojiId = 7), MessageReaction(isPaid = true)))
        val snapshot = mutableStateOf(ChatSnapshot(token = ChatToken(1, 1, message.chatId), reactionMessageId = message.id,
            reactionOptions = MessageReactionOptions(listOf("👍", "🔥"), canAdd = false)))
        val sent = mutableListOf<Pair<String, Boolean>>()
        ComposePixelScene(320, 440) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                MessageReactionPickerPanel(message, snapshot.value,
                    ChatFeatureActions(setReaction = { _, _, emoji, add -> sent += emoji to add }), {}, interactive = true)
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(scene.emojiBounds("自定义表情").isEmpty() && scene.emojiBounds("⭐").isEmpty())
            scene.click(assertNotNull(scene.emojiBounds("👍").singleOrNull()))
            scene.settledFrame()
            assertTrue(sent.isEmpty(), "Native addition permissions must still apply to cached cells.")
            scene.click(assertNotNull(scene.emojiBounds("❤️").singleOrNull()))
            scene.settledFrame()
            assertEquals(listOf("❤️" to false), sent)

            snapshot.value = snapshot.value.copy(reactionOptions = MessageReactionOptions(listOf("🥰"), canAdd = true))
            scene.settledFrame()
            assertTrue(scene.emojiBounds("👍").isEmpty() && scene.emojiBounds("🔥").isEmpty(), "A new native choice list must replace the old cached list.")
            val newChoice = assertNotNull(scene.emojiBounds("🥰").singleOrNull())
            snapshot.value = snapshot.value.copy(reactionPending = true)
            scene.settledFrame()
            scene.click(newChoice)
            scene.settledFrame()
            assertEquals(listOf("❤️" to false), sent, "A pending update must disable duplicate sends.")
            snapshot.value = snapshot.value.copy(reactionPending = false)
            scene.settledFrame()
            scene.click(assertNotNull(scene.emojiBounds("🥰").singleOrNull()))
            scene.settledFrame()
            assertEquals(listOf("❤️" to false, "🥰" to true), sent)
        }
    }

    private fun ComposePixelScene.emojiBounds(emoji: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            emoji in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun isChromatic(pixel: Int): Boolean {
        val channels = listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
        return channels.max() - channels.min() > 45
    }
}
