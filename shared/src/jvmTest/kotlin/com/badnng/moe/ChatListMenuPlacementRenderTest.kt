package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.event.KeyEvent
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real anchor, native Popup and workspace blur, with a diagnostic source card drawn only once. */
class ChatListMenuPlacementRenderTest {
    private enum class Placement(val topDp: Int, val above: Boolean, val partial: Boolean = false) {
        TOP(20, false),
        MIDDLE(260, false),
        BOTTOM(558, true),
        CLIPPED_TOP(-28, false, true),
        CLIPPED_BOTTOM(598, true, true),
    }

    @Test
    fun onlyTheMenuChangesSidesWhileEveryVisibleSourceCardKeepsItsOriginalBounds() {
        for (density in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                for (placement in Placement.entries) verifyPlacement(placement, density, mode)
            }
        }
    }

    private fun verifyPlacement(placement: Placement, density: Float, mode: ColorSchemeMode) {
        val width = (620 * density).roundToInt()
        val height = (700 * density).roundToInt()
        val fixture = "$placement/$density/$mode"
        val backdrop = ContextMenuBackdropState()
        val chat = ChatItem(1, "固定源卡片")
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), items = listOf(chat))
        var mountedSources = 0
        var sourceMounts = 0
        var sourceClicks = 0
        ComposePixelScene(width, height) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop) {
                MiuixTheme(controller = controller) {
                    // The magenta edge bars expose a second blurred draw as a coloured halo.
                    // A neutral popup shadow cannot satisfy that detector.
                    Box(Modifier.fillMaxSize().background(Color(0xffb8b8b8))) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            renderEffect = if (backdrop.hasMountedMenus) BlurEffect(6f * density, 6f * density) else null
                        }) {
                            Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp).clipToBounds()) {
                                Box(Modifier.offset(64.dp, placement.topDp.dp).width(290.dp)) {
                                    ChatListActionsAnchor(chat, snapshot, ChatFeatureActions()) {
                                        DisposableEffect(Unit) {
                                            sourceMounts++
                                            mountedSources++
                                            onDispose { mountedSources-- }
                                        }
                                        Box(Modifier.fillMaxWidth().height(62.dp)
                                            .background(Color(0xffeeeeee))
                                            .drawBehind {
                                                val bar = 7.dp.toPx()
                                                drawRect(Color.Magenta, size = Size(bar, size.height))
                                                drawRect(Color.Magenta, topLeft = Offset(size.width - bar, 0f),
                                                    size = Size(bar, size.height))
                                                drawRect(Color(0xff202020), topLeft = Offset(28.dp.toPx(), 18.dp.toPx()),
                                                    size = Size(150.dp.toPx(), 8.dp.toPx()))
                                                drawRect(Color(0xff303030), topLeft = Offset(28.dp.toPx(), 36.dp.toPx()),
                                                    size = Size(110.dp.toPx(), 6.dp.toPx()))
                                            }
                                            .semantics { contentDescription = "固定源卡片" }
                                            .clickable(interactionSource = remember { MutableInteractionSource() },
                                                indication = null) { sourceClicks++ })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            scene.moveTo(width - 4f, height - 4f)
            val before = scene.settledFrame()
            saveFrame(before, "${placement.name}-$density-$mode-before")
            val source = assertNotNull(scene.descriptionBounds("固定源卡片").singleOrNull(), fixture)
            assertTrue(source.height > 12f * density, "A partial source must still have a real visible click target: $fixture")
            if (placement.partial) assertTrue(source.height < 62f * density - 1f,
                "The fixture must clip the original source rather than manufacture a complete row: $source/$fixture")
            scene.secondaryClickAt(source.center.x, source.center.y)
            val opening = scene.settledFrame(6, 16)
            saveFrame(opening, "${placement.name}-$density-$mode-opening")
            assertCardStillOriginal(scene, source, before, opening, density, fixture)
            val opened = scene.settledFrame()
            saveFrame(opened, "${placement.name}-$density-$mode-opened")
            val card = assertCardStillOriginal(scene, source, before, opened, density, fixture)
            val menu = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "聊天操作"
            }.singleOrNull(), fixture)
            if (placement.above) {
                assertTrue(menu.bottom <= card.top - 6f * density,
                    "A lower source keeps its position while only its actions open above: $menu/$card/$fixture")
            } else {
                assertTrue(menu.top >= card.bottom + 6f * density,
                    "A top or middle source keeps its position while its actions open below: $menu/$card/$fixture")
            }
            assertTrue(menu.left >= 0 && menu.top >= 0 && menu.right <= width && menu.bottom <= height,
                "The action surface must fit the actual window: $menu/$fixture")
            assertEquals(1, mountedSources, "The original card composition owns its resources once: $fixture")
            assertEquals(1, sourceMounts, "Opening a menu must not create another row composition: $fixture")
            scene.pressAt(card.center.x, card.center.y)
            scene.releaseAt(card.center.x, card.center.y)
            scene.settledFrame(1, 16)
            assertEquals(0, sourceClicks, "The raised source keeps menu ownership of input: $fixture")
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            val exiting = scene.settledFrame(6, 16)
            saveFrame(exiting, "${placement.name}-$density-$mode-exiting")
            assertTrue(backdrop.hasMountedMenus, "The source remains raised until the menu exit finishes: $fixture")
            // Closed semantics may disappear immediately, but the actual card pixels must
            // remain exactly at their source coordinates throughout the retained exit.
            assertSameSourcePixels(before, exiting, source, density, fixture)
            assertNoBlurredSourceHalo(exiting, source, density, fixture)
            val restored = scene.settledFrame()
            saveFrame(restored, "${placement.name}-$density-$mode-restored")
            assertTrue(scene.descriptionBounds("会话卡片").isEmpty(), fixture)
            assertTrue(!backdrop.hasMountedMenus, fixture)
            assertSameSourcePixels(before, restored, source, density, fixture)
            assertEquals(1, mountedSources, fixture)
            assertEquals(1, sourceMounts, fixture)
        }
        assertEquals(0, mountedSources, "Closing the scene releases its single source owner: $fixture")
    }

    @Test
    fun aMountedMenuRepositionsOnViewportResizeAndCannotRetainAnotherFoldersSourceIdentity() {
        // ImageComposeScene has a fixed raster surface. Resize the actual content and the
        // WindowInfo together inside it, preserving the existing anchor and graphics layer.
        val viewport = mutableStateOf(IntSize(620, 700))
        val firstChat = ChatItem(1, "原分类源卡片")
        val nextChat = ChatItem(2, "新分类源卡片")
        val snapshot = mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = listOf(firstChat), knownChats = listOf(firstChat, nextChat)))
        val backdrop = ContextMenuBackdropState()
        var sourceMounts = 0
        var mountedSources = 0
        var dispatched: Pair<Long, ChatListRef>? = null
        ComposePixelScene(620, 700) {
            val host = LocalWindowInfo.current
            val resizedWindow = remember(host) { object : WindowInfo by host {
                override val containerSize get() = viewport.value
                override val containerDpSize get() = DpSize(viewport.value.width.dp, viewport.value.height.dp)
            } }
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalWindowInfo provides resizedWindow,
                LocalContextMenuBackdrop provides backdrop) {
                MiuixTheme(controller = controller) {
                    val current = snapshot.value
                    val chat = current.items.single()
                    Box(Modifier.requiredSize(viewport.value.width.dp, viewport.value.height.dp)
                        .clipToBounds().background(Color(0xffb8b8b8)).graphicsLayer {
                            renderEffect = if (backdrop.hasMountedMenus) BlurEffect(6f, 6f) else null
                        }) {
                        Box(Modifier.offset(64.dp, 300.dp).width(290.dp)) {
                            ChatListActionsAnchor(chat, current, ChatFeatureActions(chatListAction = { _, id, list, _, completed ->
                                dispatched = id to list
                                completed(ChatResult(Unit))
                            })) {
                                DisposableEffect(chat.id) {
                                    sourceMounts++
                                    mountedSources++
                                    onDispose { mountedSources-- }
                                }
                                Box(Modifier.fillMaxWidth().height(62.dp)
                                    .background(if (chat.id == firstChat.id) Color.Magenta else Color.Cyan)
                                    .semantics { contentDescription = chat.title })
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            val before = scene.settledFrame()
            saveFrame(before, "resize-before")
            val source = assertNotNull(scene.descriptionBounds(firstChat.title).singleOrNull())
            scene.secondaryClickAt(source.center.x, source.center.y)
            saveFrame(scene.settledFrame(), "resize-original-opened")
            val below = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "聊天操作"
            }.singleOrNull())
            assertTrue(below.top >= source.bottom + 6f, "The original tall viewport has room below the fixed card.")
            viewport.value = IntSize(400, 520)
            val resized = scene.settledFrame()
            saveFrame(resized, "resize-shorter-opened")
            val raised = assertNotNull(scene.descriptionBounds("会话卡片").singleOrNull())
            assertEquals(source, raised, "Only a real content layout change can move the source during a resize.")
            val above = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "聊天操作"
            }.singleOrNull())
            assertTrue(above.bottom <= source.top - 6f && above.left >= 0 && above.right <= 400f,
                "The mounted actions must remeasure and flip above within the resized viewport: $above")
            assertSameSourcePixels(before, resized, source, 1f, "resized mounted source")
            assertEquals(1, sourceMounts, "Resizing must preserve the original source composition.")
            assertEquals(1, mountedSources)

            val nextList = ChatListRef(7)
            snapshot.value = snapshot.value.copy(activeList = nextList, items = listOf(nextChat))
            val switched = scene.settledFrame()
            saveFrame(switched, "resize-folder-source-replaced")
            assertTrue(scene.descriptionBounds("会话卡片").isEmpty() && !backdrop.hasMountedMenus,
                "Changing the list and row identity must dispose the earlier menu and its raised layer.")
            assertTrue(scene.descriptionBounds(firstChat.title).isEmpty())
            val replacement = assertNotNull(scene.descriptionBounds(nextChat.title).singleOrNull())
            assertEquals(Color.Cyan.toArgb(), switched[replacement.center.x.roundToInt(), replacement.center.y.roundToInt()],
                "The replacement row must draw its own artwork rather than an earlier recorded layer.")
            assertEquals(1, mountedSources)
            assertEquals(2, sourceMounts)
            scene.secondaryClickAt(replacement.center.x, replacement.center.y)
            scene.settledFrame()
            val pin = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "置顶聊天" }
            }.singleOrNull())
            scene.pressAt(pin.center.x, pin.center.y)
            scene.releaseAt(pin.center.x, pin.center.y)
            scene.settledFrame()
            assertEquals(nextChat.id to nextList, dispatched,
                "Actions after a list change must use the replacement row's actual identity and list.")
        }
        assertEquals(0, mountedSources)
    }

    private fun assertCardStillOriginal(scene: ComposePixelScene, source: Rect, before: ComposePixelFrame,
        frame: ComposePixelFrame, density: Float, fixture: String): Rect {
        val card = assertNotNull(scene.descriptionBounds("会话卡片").singleOrNull(),
            "There must be exactly one raised source card: $fixture")
        assertTrue(abs(card.left - source.left) <= 1f && abs(card.top - source.top) <= 1f &&
            abs(card.right - source.right) <= 1f && abs(card.bottom - source.bottom) <= 1f,
            "The source card must keep all four window edges in every frame: $source -> $card/$fixture")
        assertSameSourcePixels(before, frame, source, density, fixture)
        assertNoBlurredSourceHalo(frame, source, density, fixture)
        return card
    }

    private fun assertSameSourcePixels(before: ComposePixelFrame, frame: ComposePixelFrame,
        source: Rect, density: Float, fixture: String) {
        var samples = 0
        var retained = 0
        val inset = (3f * density).roundToInt().coerceAtLeast(1)
        for (y in source.top.roundToInt() + inset until source.bottom.roundToInt() - inset) {
            for (x in source.left.roundToInt() + inset until source.right.roundToInt() - inset) {
                samples++
                if (colorDistance(before[x, y], frame[x, y]) <= 2) retained++
            }
        }
        assertTrue(samples >= 100 && retained >= samples * 0.995f,
            "The actual source pixels must stay sharp and unmoved during menu entry and exit: $retained/$samples/$fixture")
    }

    private fun assertNoBlurredSourceHalo(frame: ComposePixelFrame, source: Rect, density: Float, fixture: String) {
        val distance = (6f * density).roundToInt().coerceAtLeast(1)
        val y = source.center.y.roundToInt()
        val samples = (1..distance).flatMap { delta ->
            listOf(frame[source.left.roundToInt() - delta, y], frame[source.right.roundToInt() + delta, y])
        }
        assertTrue(samples.none {
            val red = it ushr 16 and 255
            val green = it ushr 8 and 255
            val blue = it and 255
            red - green > 12 && blue - green > 12
        }, "The original row must not leave a second blurred coloured draw behind its raised card: $fixture")
    }

    private fun ComposePixelScene.descriptionBounds(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun saveFrame(frame: ComposePixelFrame, name: String) {
        val directory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "list-card-menu", "placement"))
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
}
