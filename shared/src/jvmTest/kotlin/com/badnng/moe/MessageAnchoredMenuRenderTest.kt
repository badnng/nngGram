package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real message bubbles, secondary pointer events, native Popups and retained source pixels. */
class MessageAnchoredMenuRenderTest {
    private enum class Placement(val topDp: Int, val outgoing: Boolean = false, val partial: Boolean = false,
        val tall: Boolean = false) {
        TOP(12), BOTTOM(520, outgoing = true),
        CLIPPED_TOP(-28, partial = true), CLIPPED_BOTTOM(578, outgoing = true, partial = true),
        TALL_LEFT(44, tall = true), TALL_RIGHT(44, outgoing = true, tall = true),
    }

    @Test
    fun actionsAvoidTheVisibleMessageWhileItsActualPixelsRemainAtTheirOriginalCoordinates() {
        val contact = mutableListOf<Pair<String, BufferedImage>>()
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (placement in Placement.entries) {
                val fixture = Fixture(mode, density, placement)
                val label = "${placement.name}-$mode-$density"
                fixture.scene.use { scene ->
                    scene.moveTo(sceneWidth(density) - 3f, 3f)
                    val before = scene.settledFrame()
                    val original = fixture.bubble
                    val visible = original.intersect(fixture.messageViewport())
                    assertTrue(visible.width > 40f * density && visible.height > 8f * density, label)
                    if (placement.partial) assertTrue(visible.height < original.height - density,
                        "The fixture must really clip the bubble against the timeline: $label/$original/$visible")
                    scene.secondaryClickAt(visible.center.x, visible.center.y)
                    val opening = scene.settledFrame(6, 16)
                    assertSameBounds(original, fixture.bubble, "Opening cannot reposition the bubble: $label")
                    assertSourcePixels(before, opening, visible, density, label)
                    val opened = scene.settledFrame()
                    val menu = scene.menu()
                    val raised = scene.raisedSource()
                    assertTrue(!menu.overlaps(visible),
                        "The actions must use free space above, below or beside the visible message: $menu/$visible/$label")
                    assertTrue(raised.left <= visible.left + 1f && raised.right >= visible.right - 1f &&
                        raised.top <= visible.top + 1f && raised.bottom >= visible.bottom - 1f,
                        "One retained row must contain the original visible bubble: $raised/$visible/$label")
                    assertSourcePixels(before, opened, visible, density, label)
                    assertTrue(menu.left >= 0f && menu.right <= sceneWidth(density) && menu.top >= 0f &&
                        menu.bottom <= sceneHeight(density), "The menu body must fit the real window: $menu/$label")
                    assertEquals(1, fixture.sourceMounts, "Opening must retain one source composition: $label")
                    assertEquals(1, fixture.sourceOwners, label)
                    assertEquals(1, scene.textBounds(fixture.message.text).size,
                        "The actual message is composed once, even while its layer is lifted: $label")
                    assertTrue(fixture.backdrop.isExpanded && fixture.backdrop.hasMountedMenus, label)
                    assertEquals(0, fixture.replies, "Opening the popup cannot activate a message action: $label")
                    scene.keyStroke(KeyEvent.VK_ESCAPE)
                    val exiting = scene.settledFrame(6, 16)
                    assertTrue(fixture.backdrop.hasMountedMenus,
                        "The source must remain clear through the retained menu exit: $label")
                    assertSourcePixels(before, exiting, visible, density, label)
                    val restored = scene.settledFrame()
                    assertFalse(fixture.backdrop.hasMountedMenus, label)
                    assertTrue(scene.descriptionBounds("原位消息").isEmpty() && scene.menus().isEmpty(), label)
                    assertSourcePixels(before, restored, visible, density, label)
                    assertEquals(1, fixture.sourceMounts, label)
                    if ((placement == Placement.TOP && mode == ColorSchemeMode.Light && density == 1f) ||
                        (placement == Placement.BOTTOM && mode == ColorSchemeMode.Dark && density == 1.25f) ||
                        (placement == Placement.CLIPPED_TOP && mode == ColorSchemeMode.Light && density == 1.25f) ||
                        (placement == Placement.TALL_RIGHT && mode == ColorSchemeMode.Dark && density == 1f)) {
                        contact += label to image(opened)
                        save(opened, "$label-opened")
                        if (placement == Placement.TOP) {
                            save(before, "$label-before")
                            save(opening, "$label-opening")
                            save(exiting, "$label-exiting")
                            save(restored, "$label-restored")
                        }
                    }
                }
                assertEquals(0, fixture.sourceOwners, "Disposing the scene must release the single source: $label")
            }
        }
        saveContactSheet(contact)
    }

    @Test
    fun actualReplyAndQuickReactionActionsRemainInteractiveInTheSeparatedPopup() {
        for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(ColorSchemeMode.Light, density, Placement.TOP)
            fixture.scene.use { scene ->
                scene.settledFrame()
                scene.secondaryClickAt(fixture.bubble.center.x, fixture.bubble.center.y)
                scene.settledFrame()
                scene.click(scene.button("回复"))
                scene.settledFrame()
                assertEquals(1, fixture.replies, "A real reply action dispatches once at $density density.")
                assertTrue(scene.menus().isEmpty())
                scene.secondaryClickAt(fixture.bubble.center.x, fixture.bubble.center.y)
                scene.settledFrame()
                scene.click(assertNotNull(scene.buttonDescriptions("👍").singleOrNull()))
                scene.settledFrame()
                assertEquals(listOf("👍" to true), fixture.reactions,
                    "A quick reaction retains its current native message identity and remains clickable.")
                assertEquals(2, fixture.reactionLoads)
                assertTrue(scene.menus().isEmpty() && scene.descriptionBounds("原位消息").isEmpty())
            }
        }
    }

    @Test
    fun resizeReflowsOnlyTheMenuAndPageDeletionOrTokenChangeRevokeItsActions() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1f, Placement.TOP)
        fixture.scene.use { scene ->
            val before = scene.settledFrame()
            val original = fixture.bubble
            scene.secondaryClickAt(original.center.x, original.center.y)
            scene.settledFrame()
            fixture.viewport = IntSize(500, 480)
            val resized = scene.settledFrame()
            val visible = fixture.bubble.intersect(fixture.messageViewport())
            val menu = scene.menu()
            assertTrue(!menu.overlaps(visible) && menu.left >= 0 && menu.top >= 0 && menu.right <= 500 && menu.bottom <= 480,
                "A mounted menu must be remeasured inside the current timeline and window: $menu/$visible")
            assertSameBounds(original, fixture.bubble, "The fixed incoming source must stay in place on viewport resize.")
            assertSourcePixels(before, resized, visible, 1f, "resize")
            assertEquals(1, fixture.sourceMounts)
            save(resized, "resize-dark-opened")
        }
        for (reason in listOf("page", "delete", "token", "unmount")) {
            val current = Fixture(ColorSchemeMode.Light, 1f, Placement.TOP)
            current.scene.use { scene ->
                scene.settledFrame()
                scene.secondaryClickAt(current.bubble.center.x, current.bubble.center.y)
                scene.settledFrame()
                val oldReply = scene.button("回复")
                when (reason) {
                    "page" -> current.interactive = false
                    "delete" -> current.snapshot = current.snapshot.copy(messages = emptyList())
                    "token" -> current.snapshot = current.snapshot.copy(token = current.snapshot.token.copy(selection = 2))
                    "unmount" -> current.mounted = false
                }
                scene.settledFrame(1, 16)
                scene.click(oldReply)
                scene.settledFrame()
                assertEquals(0, current.replies, "A stale $reason action cannot dispatch from a retained native Popup.")
                assertTrue(scene.menus().isEmpty() && scene.descriptionBounds("原位消息").isEmpty(), reason)
                assertFalse(current.backdrop.hasMountedMenus, reason)
                if (reason == "page" || reason == "unmount") {
                    current.interactive = true
                    current.mounted = true
                    scene.settledFrame()
                    assertTrue(scene.menus().isEmpty(), "Returning cannot revive a canceled $reason menu.")
                }
            }
        }
    }

    @Test
    fun aWindowFillingMessageKeepsMostOfItsPixelsReadableBesideASmallScrollableActionPanel() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density, Placement.TALL_LEFT)
            fixture.scene.use { scene ->
                fixture.viewport = IntSize((360 * density).roundToInt(), (440 * density).roundToInt())
                val before = scene.settledFrame()
                val source = fixture.bubble.intersect(fixture.messageViewport())
                scene.secondaryClickAt(source.center.x, source.center.y)
                val opened = scene.settledFrame()
                val menu = scene.menu()
                assertTrue(menu.height <= 121f * density,
                    "When no side can fit, the actions must use the bounded scroll panel: $menu/$mode/$density")
                assertTrue(menu.left >= 0f && menu.right <= fixture.viewport.width && menu.top >= 0f &&
                    menu.bottom <= fixture.viewport.height)
                val uncovered = if (menu.center.y > source.center.y)
                    Rect(source.left, source.top, source.right, minOf(source.bottom, menu.top - 8f * density))
                else Rect(source.left, maxOf(source.top, menu.bottom + 8f * density), source.right, source.bottom)
                assertTrue(uncovered.height >= source.height * .45f,
                    "The fallback must leave a substantial stationary source region readable: $source/$menu")
                assertSourcePixels(before, opened, uncovered, density, "small-window-$mode-$density")
                assertEquals(1, fixture.sourceMounts)
                assertNotNull(scene.button("回复"))
                save(opened, "small-window-$mode-$density-opened")
            }
        }
    }

    @Test
    fun aDecodedPhotoKeepsItsOriginalResourceOwnerAfterOpeningAndDuringExit() {
        val path = Files.createTempFile("message-menu-retained-photo-", ".png")
        try {
            val photo = BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until photo.height) for (x in 0 until photo.width)
                photo.setRGB(x, y, if (x / 8 % 2 == 0) 0xffff0033.toInt() else 0xff00ddff.toInt())
            check(ImageIO.write(photo, "png", path.toFile()))
            val file = MediaFile(900, downloaded = true, localPath = path.toString())
            val message = baseMessage().copy(text = "图片和说明共用一个资源所有者", kind = MessageKind.PHOTO,
                media = MessageMedia(file, width = 200, height = 120))
            val fixture = Fixture(ColorSchemeMode.Light, 1.25f, Placement.TOP, message)
            fixture.scene.use { scene ->
                scene.settledFrame()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (scene.descriptionBounds("聊天图片").isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                    scene.settledFrame(2)
                }
                val photoBounds = assertNotNull(scene.descriptionBounds("聊天图片").singleOrNull(),
                    "The synthetic photo must really decode before its source file is removed.")
                val before = scene.settledFrame(2)
                assertTrue(saturatedPixels(before, photoBounds) > 1000,
                    "The fixture must compare real alternating source artwork.")
                Files.delete(path)
                val original = fixture.bubble
                scene.secondaryClickAt(original.center.x, original.center.y)
                val opened = scene.settledFrame()
                assertTrue(!scene.menu().overlaps(original), "The photo's caption and artwork must both remain readable.")
                assertSourcePixels(before, opened, photoBounds, 1.25f, "retained photo")
                assertEquals(1, scene.descriptionBounds("聊天图片").size,
                    "The recorded layer cannot create a second media composition.")
                scene.keyStroke(KeyEvent.VK_ESCAPE)
                assertSourcePixels(before, scene.settledFrame(6, 16), photoBounds, 1.25f, "photo exit")
                scene.settledFrame()
                assertEquals(0, fixture.downloads, "Lifting a downloaded photo never requests native bytes again.")
                assertEquals(1, fixture.sourceMounts)
                save(opened, "photo-light-1.25-opened")
            }
        } finally { Files.deleteIfExists(path) }
    }

    private class Fixture(mode: ColorSchemeMode, val density: Float, val placement: Placement,
        val message: ChatMessage = baseMessage(placement)) {
        var viewport by mutableStateOf(IntSize(sceneWidth(density).roundToInt(), sceneHeight(density).roundToInt()))
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, message.chatId),
            selected = ChatItem(message.chatId, "合成群聊", basicGroupId = 7), messages = listOf(message),
            actionsMessageId = message.id, messageActions = MessageActions(canForward = true),
            reactionMessageId = message.id, reactionOptions = MessageReactionOptions(listOf("👍", "❤️"), canAdd = true)))
        var menuOpen by mutableStateOf(false)
        var interactive by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var bubble = Rect.Zero
        var sourceMounts = 0
        var sourceOwners = 0
        var replies = 0
        var reactionLoads = 0
        var downloads = 0
        val reactions = mutableListOf<Pair<String, Boolean>>()
        val backdrop = ContextMenuBackdropState()
        private fun dismiss() { menuOpen = false }
        private val features = ChatFeatureActions(loadReactions = { token, id ->
            assertEquals(snapshot.token, token); assertEquals(message.id, id); reactionLoads++
        }, setReaction = { token, id, emoji, add ->
            assertEquals(snapshot.token, token); assertEquals(message.id, id); reactions += emoji to add
        }, downloadFile = { _, _ -> downloads++ })
        fun messageViewport() = Rect(0f, 40f * density, viewport.width.toFloat(), viewport.height - 60f * density)
        val scene = ComposePixelScene(viewport.width, viewport.height) {
            val theme = remember { ThemeController(mode) }
            val host = LocalWindowInfo.current
            val resizedWindow = remember(host) { object : WindowInfo by host {
                override val containerSize get() = viewport
                override val containerDpSize get() = DpSize((viewport.width / density).dp, (viewport.height / density).dp)
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides resizedWindow,
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true), LocalContextMenuBackdrop provides backdrop,
                LocalConversationMessageMenuViewport provides messageViewport()) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.requiredSize((viewport.width / density).dp, (viewport.height / density).dp)
                        .clipToBounds().background(Color(0xffadc99c))) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            renderEffect = if (backdrop.hasMountedMenus) BlurEffect(6f * density, 6f * density) else null
                        }.drawBehind {
                            for (index in 0..40) drawRect(if (index % 2 == 0) Color(0xff557b64) else Color(0xffc8d8b3),
                                topLeft = Offset(index * 24.dp.toPx(), 0f), size = Size(9.dp.toPx(), size.height))
                        }) {
                            Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 60.dp).clipToBounds()) {
                                Box(Modifier.offset(42.dp, placement.topDp.dp).width((viewport.width / density - 84).dp)) {
                                    if (mounted) {
                                        DisposableEffect(Unit) {
                                            sourceMounts++; sourceOwners++
                                            onDispose { sourceOwners-- }
                                        }
                                        MessageBubble(message, "合成发送者", showSender = !message.outgoing, endsGroup = true,
                                            onBounds = { if (it != null) bubble = it }, retrying = false, highlighted = false,
                                            read = false, onRetry = {}, snapshot = snapshot, features = features,
                                            visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                                            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                                            playbackSession = null, onExpandVideo = {}, menuOpen = menuOpen,
                                            onMenuOpen = { menuOpen = true }, onMenuDismiss = ::dismiss,
                                            onReply = { replies++; dismiss() }, onQuote = {}, onJumpToReply = {}, onReact = {},
                                            onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                                            pageInteractive = interactive, reserveAvatarSpace = !message.outgoing)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.menus() = semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
    }
    private fun ComposePixelScene.menu() = assertNotNull(menus().singleOrNull(), "Exactly one real message menu body is expected.")
    private fun ComposePixelScene.raisedSource() = assertNotNull(descriptionBounds("原位消息").singleOrNull(),
        "Exactly one display-list source must be lifted above the workspace blur.")
    private fun ComposePixelScene.descriptionBounds(label: String) = unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.buttonDescriptions(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button && label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.textBounds(label: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.button(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun assertSameBounds(expected: Rect, actual: Rect, label: String) {
        assertTrue(abs(expected.left - actual.left) <= 1f && abs(expected.top - actual.top) <= 1f &&
            abs(expected.right - actual.right) <= 1f && abs(expected.bottom - actual.bottom) <= 1f, "$label: $expected -> $actual")
    }
    private fun assertSourcePixels(before: ComposePixelFrame, after: ComposePixelFrame, bounds: Rect, density: Float, label: String) {
        // The bubble's transparent round corners reveal the intentionally changing
        // blurred backdrop; compare its opaque center, including real text glyphs.
        val horizontalInset = (15f * density).roundToInt().coerceAtLeast(1)
        val verticalInset = (3f * density).roundToInt().coerceAtLeast(1)
        var count = 0
        var same = 0
        for (y in (bounds.top.roundToInt() + verticalInset).coerceAtLeast(0) until
            (bounds.bottom.roundToInt() - verticalInset).coerceAtMost(before.height)) {
            for (x in (bounds.left.roundToInt() + horizontalInset).coerceAtLeast(0) until
                (bounds.right.roundToInt() - horizontalInset).coerceAtMost(before.width)) {
                count++
                if (colorDistance(before[x, y], after[x, y]) <= 2) same++
            }
        }
        assertTrue(count >= 100 && same >= count * .995f,
            "The real source artwork and text must remain clear and stationary: $same/$count/$label")
    }
    private fun saturatedPixels(frame: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                val color = frame[x, y]
                if (colorDistance(color, 0xffff0033.toInt()) < 5 || colorDistance(color, 0xff00ddff.toInt()) < 5) count++
            }
        return count
    }
    private fun colorDistance(first: Int, second: Int) = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }
    private fun image(frame: ComposePixelFrame) = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) for (x in 0 until width) setRGB(x, y, frame[x, y])
    }
    private fun save(frame: ComposePixelFrame, label: String) {
        Files.createDirectories(output)
        check(ImageIO.write(image(frame), "png", output.resolve("$label.png").toFile()))
    }
    private fun saveContactSheet(frames: List<Pair<String, BufferedImage>>) {
        Files.createDirectories(output)
        val sheet = BufferedImage(840, ((frames.size + 1) / 2) * 374, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.color = java.awt.Color(0xffeeeeee.toInt())
            graphics.fillRect(0, 0, sheet.width, sheet.height)
            frames.forEachIndexed { index, (label, frame) ->
                val left = index % 2 * 420
                val top = index / 2 * 374
                graphics.color = java.awt.Color.BLACK
                graphics.drawString(label, left + 12, top + 18)
                graphics.drawImage(frame, left, top + 24, 420, 350, null)
            }
        } finally { graphics.dispose() }
        check(ImageIO.write(sheet, "png", output.resolve("contact-sheet.png").toFile()))
    }
    private companion object {
        val output: Path = Path.of(System.getProperty("java.io.tmpdir"), "message-anchored-menu")
        fun sceneWidth(density: Float) = 840f * density
        fun sceneHeight(density: Float) = 700f * density
        fun baseMessage(placement: Placement = Placement.TOP) = ChatMessage(100, 10, senderId = 20,
            outgoing = placement.outgoing, date = 1_700_000_000, canBeSaved = true,
            text = if (placement.tall) (1..23).joinToString("\n") { "第${it}行原位消息" } else "右键后消息应清楚可读")
    }
}
