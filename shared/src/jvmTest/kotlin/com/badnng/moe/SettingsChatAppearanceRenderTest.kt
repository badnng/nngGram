package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.RichMessageText
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Native Miuix tone generation and real message/control consumers, with synthetic content only. */
class SettingsChatAppearanceRenderTest {
    @Test
    fun defaultPaletteStaysExactAndEveryExplicitSeedUsesReadableNativeLightDarkAndSystemTones() {
        for ((mode, dark) in listOf(ColorSchemeMode.Light to false, ColorSchemeMode.Dark to true,
            ColorSchemeMode.System to false, ColorSchemeMode.System to true)) {
            for (accent in SettingsAccentColor.entries) {
                var observed: Palette? = null
                var controllerMode: ColorSchemeMode? = null
                ComposePixelScene(200, 100) {
                    val controller = rememberSettingsThemeController(mode, accent, systemDark = dark)
                    MiuixTheme(controller = controller) {
                        val colors = MiuixTheme.colorScheme
                        SideEffect {
                            observed = Palette(colors.primary, colors.onPrimary, colors.surface, colors.onSurface)
                            controllerMode = controller.colorSchemeMode
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(colors.surface)
                            drawRect(colors.primary, size = androidx.compose.ui.geometry.Size(100f, size.height))
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame(3, 16)
                    val palette = assertNotNull(observed)
                    assertEquals(palette.primary.toArgb(), frame[20, 40])
                    assertEquals(palette.surface.toArgb(), frame[150, 40])
                    if (accent == SettingsAccentColor.DEFAULT) {
                        val original = if (dark) darkColorScheme() else lightColorScheme()
                        assertEquals(original.primary, palette.primary)
                        assertEquals(original.onPrimary, palette.onPrimary)
                        assertEquals(original.surface, palette.surface)
                        assertEquals(original.onSurface, palette.onSurface)
                        assertEquals(mode, controllerMode)
                    } else {
                        val nativeMode = when (mode) {
                            ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
                            ColorSchemeMode.Dark -> ColorSchemeMode.MonetDark
                            else -> ColorSchemeMode.MonetSystem
                        }
                        assertEquals(nativeMode, controllerMode)
                        assertTrue(contrast(palette.primary, palette.onPrimary) >= 4.5f,
                            "The native on-accent pair must remain readable: $mode/$dark/$accent/$palette")
                    }
                    assertTrue(contrast(palette.surface, palette.onSurface) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun selectingAccentRepaintsRealNativeLinkSendAndReactionConsumersWithoutChangingTextGeometryOrDraft() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density)
            fixture.scene().use { scene ->
                val before = scene.settledFrame()
                val initialPalette = fixture.palette
                val bodyBefore = scene.bodyLayout()
                val bodyBounds = scene.textBounds(MESSAGE_TEXT)
                val metadataBefore = scene.textBounds(messageClock(MESSAGE.date))
                val composerBefore = scene.actualDraft()
                assertEquals(DRAFT_TEXT, composerBefore.first.text)
                assertEquals(TextRange(2, 10), composerBefore.second)
                assertTrue(before.countColor(scene.description("发送消息"), initialPalette.primary) > 5)

                scene.click(scene.description("强调色 绿色"))
                val green = scene.settledFrame(12, 64)
                assertEquals(SettingsAccentColor.GREEN, fixture.preferences.accentColor)
                assertNotEquals(initialPalette.primary, fixture.palette.primary)
                assertLinkUses(body = scene.bodyLayout(), accent = fixture.palette.primary)
                assertTrue(green.countColor(scene.description("发送消息"), fixture.palette.primary) > 5,
                    "The actual composer send artwork must consume the new native primary color.")
                assertTrue(green.countColor(scene.description("👍 3 条回应"),
                    reactionSelectedSurface(fixture.palette.primary, fixture.palette.onPrimary)) > 20,
                    "An already selected native reaction must consume its new readable accent surface.")
                assertEquals(bodyBounds, scene.textBounds(MESSAGE_TEXT))
                assertEquals(metadataBefore, scene.textBounds(messageClock(MESSAGE.date)))
                assertEquals(bodyBefore.layoutInput.text.text, scene.bodyLayout().layoutInput.text.text)
                assertEquals(bodyBefore.layoutInput.style.fontSize, scene.bodyLayout().layoutInput.style.fontSize)
                assertEquals(13f, scene.bodyLayout().layoutInput.style.fontSize.value)
                assertEquals(composerBefore, scene.actualDraft(),
                    "Changing colors cannot rewrite rich draft content or its selection.")
                green.save("accent-green-${mode.name.lowercase()}-$density")

                scene.click(scene.description("强调色 紫色"))
                scene.settledFrame(12, 64)
                assertEquals(SettingsAccentColor.PURPLE, fixture.preferences.accentColor)
                assertLinkUses(scene.bodyLayout(), fixture.palette.primary)
                assertEquals(composerBefore, scene.actualDraft())
                scene.click(scene.description("强调色 默认"))
                val restored = scene.settledFrame(12, 64)
                assertEquals(SettingsAccentColor.DEFAULT, fixture.preferences.accentColor)
                assertEquals(initialPalette, fixture.palette)
                assertEquals(bodyBounds, scene.textBounds(MESSAGE_TEXT))
                assertEquals(metadataBefore, scene.textBounds(messageClock(MESSAGE.date)))
                assertTrue(restored.countColor(scene.description("发送消息"), initialPalette.primary) > 5)
                assertTrue(fixture.sent.isEmpty())
            }
        }
    }

    @Test
    fun rejectedPersistenceAndInactiveControlsCannotClaimOrDispatchANewAccent() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f)
        fixture.scene().use { scene ->
            val before = scene.settledFrame()
            val original = fixture.palette
            fixture.acceptChanges = false
            scene.click(scene.description("强调色 金色"))
            scene.settledFrame()
            assertEquals(listOf(SettingsAccentColor.GOLD), fixture.requests.map { it.accentColor })
            assertEquals(SettingsAccentColor.DEFAULT, fixture.preferences.accentColor)
            assertEquals(original, fixture.palette)
            assertTrue(before.countColor(scene.description("发送消息"), original.primary) > 5)
            fixture.acceptChanges = true
            fixture.enabled = false
            scene.settledFrame()
            scene.click(scene.description("强调色 绿色"))
            scene.settledFrame()
            assertEquals(1, fixture.requests.size)
            assertEquals(original, fixture.palette)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    private data class Palette(val primary: Color, val onPrimary: Color, val surface: Color, val onSurface: Color)

    private class Fixture(mode: ColorSchemeMode, val density: Float) {
        var preferences by mutableStateOf(SettingsPreferences(theme = mode))
        var enabled by mutableStateOf(true)
        var acceptChanges = true
        val requests = CopyOnWriteArrayList<SettingsPreferences>()
        val sent = CopyOnWriteArrayList<Unit>()
        var palette = Palette(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
        var draft by mutableStateOf(RichMessageText(DRAFT_TEXT,
            listOf(MessageEntity(0, 9, MessageEntityKind.BOLD))).editorValue(TextRange(2, 10)))
        private val chat = ChatItem(10, "Synthetic appearance", canSendStickers = false)
        private val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10), selected = chat,
            messages = listOf(MESSAGE), historyEnd = true)

        fun scene() = ComposePixelScene((1_180 * density).roundToInt(), (800 * density).roundToInt()) {
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalSettingsPreferences provides preferences,
                LocalDesktopChatLayout provides desktopChatLayout(1_180.dp, 800.dp)) {
                val controller = rememberSettingsThemeController(preferences.theme, preferences.accentColor)
                MiuixTheme(controller = controller) {
                    val colors = MiuixTheme.colorScheme
                    SideEffect { palette = Palette(colors.primary, colors.onPrimary, colors.surface, colors.onSurface) }
                    Row(Modifier.fillMaxSize().background(colors.surface)) {
                        Column(Modifier.width(320.dp)) {
                            SettingsAccentColorControls(preferences, { next ->
                                requests += next
                                if (acceptChanges) preferences = next
                            }, enabled = enabled)
                        }
                        Column(Modifier.weight(1f)) {
                            ConversationPane(snapshot = snapshot, chat = chat, draft = draft, onDraft = { draft = it },
                                onBack = null, onLoadOlder = {}, onSend = { sent += Unit }, onRetry = {}, onMarkRead = {},
                                features = ChatFeatureActions(), active = false, onSearch = {}, onSearchSender = {},
                                onAdmins = {}, onInfo = {}, showInfoButton = false, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), "Missing actual appearance consumer: $label")

    private fun ComposePixelScene.textBounds(text: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.filter { it.width > 0f && it.height > 0f }.singleOrNull(), text)

    private fun ComposePixelScene.bodyLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds {
            val match = it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == MESSAGE_TEXT }
            if (match) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            false
        }
        return assertNotNull(layouts.singleOrNull(), "The actual native rich body must expose one measured paragraph.")
    }

    private fun ComposePixelScene.actualDraft(): Pair<AnnotatedString, TextRange> {
        val values = mutableListOf<Pair<AnnotatedString, TextRange>>()
        unmergedSemanticsBounds {
            val text = it.getOrNull(SemanticsProperties.EditableText)
            val selection = it.getOrNull(SemanticsProperties.TextSelectionRange)
            if (text != null && selection != null) values += text to selection
            false
        }
        return assertNotNull(values.singleOrNull())
    }

    private fun assertLinkUses(body: TextLayoutResult, accent: Color) {
        assertEquals(MESSAGE_TEXT, body.layoutInput.text.text)
        assertTrue(body.layoutInput.text.spanStyles.any { it.start == 0 && it.end == MESSAGE_TEXT.length && it.item.color == accent },
            "The real native TEXT_URL entity must retain its range and use the current Miuix accent.")
        assertEquals(MESSAGE_TEXT.length, body.getLineEnd(body.lineCount - 1))
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun ComposePixelFrame.countColor(rect: Rect, color: Color): Int {
        val target = color.toArgb()
        var found = 0
        for (y in max(0, floor(rect.top).toInt()) until min(height, ceil(rect.bottom).toInt()))
            for (x in max(0, floor(rect.left).toInt()) until min(width, ceil(rect.right).toInt())) {
                val pixel = this[x, y]
                if (pixel ushr 24 >= 240 && abs((pixel ushr 16 and 255) - (target ushr 16 and 255)) <= 8 &&
                    abs((pixel ushr 8 and 255) - (target ushr 8 and 255)) <= 8 && abs((pixel and 255) - (target and 255)) <= 8) found++
            }
        return found
    }

    private fun ComposePixelFrame.save(name: String) {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) output.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/frames/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(output, "png", target))
    }

    private fun contrast(first: Color, second: Color): Float =
        (max(first.luminance(), second.luminance()) + .05f) / (min(first.luminance(), second.luminance()) + .05f)

    companion object {
        private const val MESSAGE_TEXT = "Native docs"
        private const val DRAFT_TEXT = "Preserved rich draft"
        private val MESSAGE = ChatMessage(101, 10, outgoing = true, date = 1_700_000_000, text = MESSAGE_TEXT,
            canBeSaved = true, entities = listOf(MessageEntity(0, MESSAGE_TEXT.length, MessageEntityKind.TEXT_URL,
                "https://example.invalid/native")), reactions = listOf(MessageReaction("👍", 3, isChosen = true)))
    }
}
