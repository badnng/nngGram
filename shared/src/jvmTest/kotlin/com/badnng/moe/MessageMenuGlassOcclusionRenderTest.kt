package com.badnng.moe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageActions
import com.badnng.moe.chat.MessageReactionOptions
import com.badnng.moe.chat.MessageReply
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Glass occludes interaction, but only an actual parent/window clip may cut the raised message. */
class MessageMenuGlassOcclusionRenderTest {
    private enum class Placement(val top: Int) {
        HEADER(62), COMPOSER(500), PARTIAL_TOP(-32), PARTIAL_BOTTOM(560), RESIZE_BOTTOM(350), GLASS_ONLY(4),
    }

    @Test
    fun aRealSecondaryClickStillWorksWhenTheWholeMessageIsBehindOnlyGlass() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density, Placement.GLASS_ONLY)
            fixture.scene.use { scene ->
                val reference = fixture.clearReference(scene)
                val source = fixture.bubble.intersect(fixture.paintViewport())
                assertTrue(source.bottom < fixture.safeViewport().top,
                    "The entire real message must be outside the menu's safe area, but inside the actual pane: $source/${fixture.safeViewport()}")
                fixture.glass = true
                scene.settledFrame()
                scene.secondaryClickAt(source.center.x, source.center.y)
                val opened = scene.settledFrame()
                fixture.assertMenuAndOwnership(scene, source)
                assertSource(reference, opened, source, density, "whole-message-behind-glass/$mode/$density")
                save(opened, "GLASS-ONLY-$mode-$density-opened")
            }
            assertEquals(0, fixture.sourceOwners)
        }
    }

    @Test
    fun quoteMessagesRemainWholeAcrossHeaderAndComposerGlassWhileTheMenuUsesTheSafeArea() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (placement in listOf(Placement.HEADER, Placement.COMPOSER)) {
                val fixture = Fixture(mode, density, placement)
                fixture.scene.use { scene ->
                    val reference = fixture.clearReference(scene)
                    save(reference, "$placement-$mode-$density-clear")
                    fixture.glass = true
                    val occluded = scene.settledFrame()
                    save(occluded, "$placement-$mode-$density-glass")
                    val visible = fixture.bubble.intersect(fixture.paintViewport())
                    val glassPart = if (placement == Placement.HEADER)
                        Rect(visible.left, visible.top, visible.right, minOf(visible.bottom, fixture.safeViewport().top))
                    else Rect(visible.left, maxOf(visible.top, fixture.safeViewport().bottom), visible.right, visible.bottom)
                    assertTrue(glassPart.height >= 12f * density,
                        "The real quote/body must extend through the glass band, not merely sit next to it: $mode/$density/$placement")
                    assertTrue(changedPixels(reference, occluded, inset(glassPart, 14f * density, 3f * density)) > 20 * density * density,
                        "The actual progressive blur must visibly cover source pixels before the menu opens.")
                    fixture.open(scene)
                    val opening = scene.settledFrame(6, 16)
                    val openingGeometry = scene.menuGeometry()
                    val opened = scene.settledFrame()
                    save(opening, "$placement-$mode-$density-opening")
                    save(opened, "$placement-$mode-$density-opened")
                    saveDiagnostic("$placement-$mode-$density", fixture, visible, openingGeometry, scene.menuGeometry(), reference, opening)
                    assertSource(reference, opening, visible, density, "opening/$mode/$density/$placement")
                    assertSource(reference, opened, visible, density, "opened/$mode/$density/$placement")
                    assertSource(reference, opened, glassPart, density, "glass-band/$mode/$density/$placement")
                    fixture.assertMenuAndOwnership(scene, visible)
                }
                assertEquals(0, fixture.sourceOwners)
            }
        }
    }

    @Test
    fun partiallyVisibleQuoteMessagesKeepTheRealParentClipWithoutAnotherGlassBoundary() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (placement in listOf(Placement.PARTIAL_TOP, Placement.PARTIAL_BOTTOM)) {
                val fixture = Fixture(mode, density, placement)
                fixture.scene.use { scene ->
                    val reference = fixture.clearReference(scene)
                    val visible = fixture.bubble.intersect(fixture.paintViewport())
                    assertTrue(visible.height >= 50f * density && visible.height < fixture.bubble.height - 10f * density,
                        "This source must genuinely cross the parent viewport boundary: $placement/$visible/${fixture.bubble}")
                    fixture.glass = true
                    scene.settledFrame()
                    fixture.open(scene)
                    val opened = scene.settledFrame()
                    assertSource(reference, opened, visible, density, "partial/$mode/$density/$placement")
                    fixture.assertMenuAndOwnership(scene, visible)
                    assertExterior(opened, fixture, placement == Placement.PARTIAL_TOP)
                    save(opened, "$placement-$mode-$density-opened")
                }
                assertEquals(0, fixture.sourceOwners)
            }
        }
    }

    @Test
    fun resizingTheRealParentUpdatesItsClipWhileItsChangingGlassBandNeverCutsTheSource() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val fixture = Fixture(mode, density, Placement.RESIZE_BOTTOM)
            fixture.scene.use { scene ->
                val reference = fixture.clearReference(scene)
                val original = fixture.bubble
                fixture.glass = true
                scene.settledFrame()
                fixture.open(scene)
                scene.settledFrame()
                fixture.width = 800
                fixture.height = 500
                val resized = scene.settledFrame()
                val visible = fixture.bubble.intersect(fixture.paintViewport())
                assertEquals(original, fixture.bubble, "Resizing a wider-than-bubble pane must retain the original source coordinates.")
                assertTrue(visible.height < original.height - 8f * density,
                    "The resized parent must really clip the lower source pixels, independently of the glass band.")
                assertTrue(visible.bottom > fixture.safeViewport().bottom + 16f * density,
                    "The retained source must still cross the new composer's safe-area boundary.")
                assertSource(reference, resized, visible, density, "resize/$mode/$density")
                fixture.assertMenuAndOwnership(scene, visible)
                assertExterior(resized, fixture, above = false)
                save(resized, "RESIZED-$mode-$density-opened")
            }
            assertEquals(0, fixture.sourceOwners)
        }
    }

    private class Fixture(val mode: ColorSchemeMode, val density: Float, val placement: Placement) {
        var width by mutableStateOf(936)
        var height by mutableStateOf(700)
        var glass by mutableStateOf(false)
        var expanded by mutableStateOf(false)
        var bubble = Rect.Zero
        var sourceMounts = 0
        var sourceOwners = 0
        private var currentBlur: () -> Float = { 0f }
        val backdrop = ContextMenuBackdropState()
        private val message = ChatMessage(100, 10, senderId = 20, date = 1_700_000_000,
            text = if (placement == Placement.GLASS_ONLY) "玻璃后仍可操作" else
                (1..6).joinToString("\n") { "第${it}行：引用和正文保持原位清晰" },
            replyTo = if (placement == Placement.GLASS_ONLY) null else MessageReply(10, 80,
                text = "原生引用的第一行\n第二行也保留原始字形", senderId = 30, isQuote = true),
            canBeSaved = true)
        private val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic glass clip group", basicGroupId = 7), messages = listOf(message),
            actionsMessageId = message.id, messageActions = MessageActions(canForward = true),
            reactionMessageId = message.id, reactionOptions = MessageReactionOptions(listOf("👍"), canAdd = true))

        fun paintViewport() = Rect(PANE_LEFT * density, PANE_TOP * density,
            (PANE_LEFT + width) * density, (PANE_TOP + height) * density)
        fun safeViewport() = paintViewport().let { Rect(it.left, it.top + HEADER_BAND * density,
            it.right, it.bottom - COMPOSER_BAND * density) }

        val scene = ComposePixelScene((1000 * density).roundToInt(), (760 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val blur by animateFloatAsState(if (backdrop.hasExpandedBlurOwners) 1f else 0f,
                tween(180, easing = FastOutSlowInEasing), label = "occlusion fixture context blur")
            SideEffect { currentBlur = { blur } }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true),
                LocalContextMenuBackdrop provides backdrop, LocalContextMenuBlurProgress provides { blur },
                LocalConversationMessageMenuViewport provides safeViewport()) {
                MiuixTheme(controller = theme) {
                    val sampled = assertNotNull(rememberChatBackdrop(), "The regression requires actual Miuix progressive glass sampling.")
                    CompositionLocalProvider(LocalChatBackdrop provides sampled, LocalWorkspaceMenuBackdrop provides sampled) {
                        Box(Modifier.requiredSize(1000.dp, 760.dp).background(Color(OUTSIDE))) {
                            Box(Modifier.offset(PANE_LEFT.dp, PANE_TOP.dp).requiredSize(width.dp, height.dp).clipToBounds()) {
                                Box(Modifier.fillMaxSize().graphicsLayer {
                                    clip = true
                                    val radius = 14.dp.toPx() * blur
                                    renderEffect = if (radius > .01f) BlurEffect(radius, radius) else null
                                }) {
                                    Box(Modifier.fillMaxSize().layerBackdrop(sampled)) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            val stripe = 10.dp.toPx()
                                            repeat((size.width / stripe).toInt() + 1) { index ->
                                                drawRect(if (index % 2 == 0) Color(0xff75a584) else Color(0xffd4dca1),
                                                    Offset(index * stripe, 0f), Size(stripe, size.height))
                                            }
                                        }
                                        Box(Modifier.offset(30.dp, placement.top.dp).width((width - 60).dp)) {
                                            DisposableEffect(Unit) {
                                                sourceMounts++; sourceOwners++
                                                onDispose { sourceOwners-- }
                                            }
                                            MessageBubble(message, "合成发送者", showSender = true, endsGroup = true,
                                                onBounds = { if (it != null) bubble = it }, retrying = false, highlighted = false,
                                                read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
                                                visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                                                onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                                                playbackSession = null, onExpandVideo = {}, menuOpen = expanded,
                                                onMenuOpen = { expanded = true }, onMenuDismiss = { expanded = false },
                                                onReply = { expanded = false }, onQuote = {}, onJumpToReply = {}, onReact = {},
                                                onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                                                reserveAvatarSpace = true)
                                        }
                                    }
                                    if (glass) {
                                        ChatProgressiveBlur(sampled, bottom = false,
                                            Modifier.align(Alignment.TopStart).fillMaxWidth().height(HEADER_BAND.dp))
                                        ChatProgressiveBlur(sampled, bottom = true,
                                            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(COMPOSER_BAND.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun clearReference(scene: ComposePixelScene): ComposePixelFrame {
            scene.moveTo(995f * density, 755f * density)
            return scene.settledFrame()
        }

        fun open(scene: ComposePixelScene) {
            // ReplyReference has its own native Miuix hover feedback. Hit the actual plain body so
            // its transient hover state cannot be mistaken for a source-cropping pixel regression.
            // A glass band does not own pointer input, so the action-placement safe area is irrelevant.
            val click = assertNotNull(scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == message.text }
            }.map { it.intersect(paintViewport()) }.firstOrNull { !it.isEmpty },
                "The real secondary click must hit visible, non-interactive source body pixels inside the actual parent.")
            scene.secondaryClickAt(click.center.x, click.center.y)
        }

        fun assertMenuAndOwnership(scene: ComposePixelScene, visible: Rect) {
            assertTrue(expanded && backdrop.hasMountedMenus && currentBlur() > .99f)
            val menu = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
            }.singleOrNull(), "One real native action menu must be open.")
            val safe = safeViewport()
            assertTrue(menu.left >= safe.left - 1f && menu.top >= safe.top - 1f &&
                menu.right <= safe.right + 1f && menu.bottom <= safe.bottom + 1f,
                "Only the actions keep using header/composer-safe placement: $menu/$safe")
            assertTrue(!menu.overlaps(visible), "This roomy fixture must leave source pixels free of actual action rows: $menu/$visible")
            val lifted = assertNotNull(scene.unmergedSemanticsBounds {
                "原位消息" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull())
            assertTrue(lifted.top <= visible.top + 1f && lifted.bottom >= visible.bottom - 1f,
                "The raised source must include its true visible pixels, even through glass: $lifted/$visible")
            assertEquals(1, sourceMounts)
            assertEquals(1, sourceOwners)
            assertEquals(1, scene.unmergedSemanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == message.text }
            }.size, "Lifting cannot compose a replacement body or another quote/media owner.")
        }
    }

    private fun assertSource(reference: ComposePixelFrame, actual: ComposePixelFrame, visible: Rect,
        density: Float, label: String) {
        val core = inset(visible, 14f * density, 3f * density)
        var count = 0
        var same = 0
        for (y in core.top.roundToInt().coerceAtLeast(0) until core.bottom.roundToInt().coerceAtMost(actual.height)) {
            for (x in core.left.roundToInt().coerceAtLeast(0) until core.right.roundToInt().coerceAtMost(actual.width)) {
                count++
                if (distance(reference[x, y], actual[x, y]) <= 2) same++
            }
        }
        assertTrue(count > 100 && same >= count * .995f,
            "Real quote/body pixels must stay clear at their original coordinates without a horizontal glass cut: $same/$count/$label")
    }

    private fun assertExterior(frame: ComposePixelFrame, fixture: Fixture, above: Boolean) {
        val viewport = fixture.paintViewport()
        val source = fixture.bubble
        val top = if (above) maxOf(source.top, viewport.top - 12f * fixture.density) else viewport.bottom + 2f * fixture.density
        val bottom = if (above) viewport.top - 2f * fixture.density else minOf(source.bottom, viewport.bottom + 12f * fixture.density)
        var checked = 0
        for (y in top.roundToInt().coerceAtLeast(0) until bottom.roundToInt().coerceAtMost(frame.height)) {
            for (x in (source.left + 16f * fixture.density).roundToInt() until (source.right - 16f * fixture.density).roundToInt()) {
                checked++
                assertTrue(distance(frame[x, y], OUTSIDE) <= 1,
                    "The Popup must retain the real parent viewport clip instead of revealing off-pane source pixels at $x,$y.")
            }
        }
        assertTrue(checked > 100, "The true clipped part must include a measurable exterior strip.")
    }

    private fun changedPixels(reference: ComposePixelFrame, actual: ComposePixelFrame, region: Rect): Int {
        var changed = 0
        for (y in region.top.roundToInt().coerceAtLeast(0) until region.bottom.roundToInt().coerceAtMost(actual.height))
            for (x in region.left.roundToInt().coerceAtLeast(0) until region.right.roundToInt().coerceAtMost(actual.width))
                if (distance(reference[x, y], actual[x, y]) > 4) changed++
        return changed
    }

    private fun inset(rect: Rect, horizontal: Float, vertical: Float) =
        Rect(rect.left + horizontal, rect.top + vertical, rect.right - horizontal, rect.bottom - vertical)

    private fun distance(first: Int, second: Int) = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun save(frame: ComposePixelFrame, label: String) {
        val output: Path = Path.of(System.getProperty("java.io.tmpdir"), "message-menu-glass-occlusion")
        Files.createDirectories(output)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, frame.width, frame.height,
            IntArray(frame.width * frame.height) { index -> frame[index % frame.width, index / frame.width] }, 0, frame.width)
        check(ImageIO.write(image, "png", output.resolve("$label.png").toFile()))
    }

    private fun ComposePixelScene.menuGeometry() = "menus=" + semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
    } + "; lifted=" + unmergedSemanticsBounds {
        "原位消息" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun saveDiagnostic(label: String, fixture: Fixture, visible: Rect, openingGeometry: String,
        openedGeometry: String, reference: ComposePixelFrame, opening: ComposePixelFrame) {
        val output = Path.of(System.getProperty("java.io.tmpdir"), "message-menu-glass-occlusion")
        Files.createDirectories(output)
        val core = inset(visible, 14f * fixture.density, 3f * fixture.density)
        val rows = (core.top.roundToInt().coerceAtLeast(0) until core.bottom.roundToInt().coerceAtMost(opening.height)).joinToString("\n") { y ->
            val changed = (core.left.roundToInt().coerceAtLeast(0) until core.right.roundToInt().coerceAtMost(opening.width)).count { x ->
                distance(reference[x, y], opening[x, y]) > 2
            }
            "$y: $changed"
        }
        Files.writeString(output.resolve("$label-geometry.txt"),
            "bubble=${fixture.bubble}\nvisible=$visible\npaint=${fixture.paintViewport()}\nsafe=${fixture.safeViewport()}\n" +
                "opening $openingGeometry\nopened $openedGeometry\nchanged pixels per core row:\n$rows\n")
    }

    private companion object {
        const val PANE_LEFT = 32
        const val PANE_TOP = 24
        const val HEADER_BAND = 104
        const val COMPOSER_BAND = 112
        const val OUTSIDE = 0xff20324b.toInt()
    }
}
