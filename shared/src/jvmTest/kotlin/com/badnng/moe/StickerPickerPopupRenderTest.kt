package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class StickerPickerPopupRenderTest {
    @Test fun anchoredOpenAndRetainedCloseReleaseAllBackdropAndMountedOwnership() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val file = Files.createTempFile("synthetic-sticker-popup-", ".png")
            greenPng(file)
            val show = mutableStateOf(false)
            val installed = mutableStateOf(true)
            val state = mutableStateOf(snapshot(listOf(sticker(1, "🙂", MediaFile(101, localPath = file.toString(), downloaded = true)))))
            val backdrop = ContextMenuBackdropState()
            var mounted = false
            val anchor = Rect(700 * density, 550 * density, 732 * density, 582 * density)
            try {
                ComposePixelScene((800 * density).roundToInt(), (620 * density).roundToInt()) {
                    val theme = remember { ThemeController(mode) }
                    CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop) {
                        MiuixTheme(controller = theme) {
                            Box(Modifier.fillMaxSize().background(Color(0xffd1d2d3))) {
                                Box(Modifier.offset(700.dp, 550.dp).size(32.dp).semantics { contentDescription = "展开贴纸" }
                                    .clickable { show.value = true })
                                if (installed.value) StickerPicker(show.value, state.value, ChatFeatureActions(),
                                    active = !backdrop.hasMountedMenus, onDismiss = {
                                        show.value = false
                                        state.value = state.value.copy(stickers = emptyList(), mediaFiles = emptyMap())
                                    }, anchor = anchor, onMountedChange = { mounted = it })
                            }
                        }
                    }
                }.use { scene ->
                    scene.settledFrame()
                    assertFalse(backdrop.hasMountedMenus || backdrop.isExpanded || mounted,
                        "An initially hidden picker must not suppress the timeline's read receipts or playback.")
                    scene.click(scene.description("展开贴纸"))
                    scene.settledFrame()
                    val panel = scene.description("贴纸选择面板")
                    assertTrue(panel.bottom <= anchor.top - 7 * density && panel.right <= anchor.right + density)
                    assertTrue(panel.left >= 0 && panel.top >= 0 && panel.right <= 800 * density && panel.bottom <= 620 * density)
                    assertTrue(backdrop.isExpanded && backdrop.hasMountedMenus && mounted)
                    scene.awaitPixels(panel) { green(it) }
                    Files.delete(file)
                    scene.click(scene.description("关闭贴纸"))
                    val firstExit = scene.settledFrame(1)
                    assertFalse(backdrop.isExpanded)
                    assertTrue(backdrop.hasMountedMenus && mounted, "The exit frame still owns its source and read suppression.")
                    assertTrue(count(firstExit, panel, ::green) > 20, "Closing must retain the native raster even after the source file is gone.")
                    val closed = scene.settledFrame()
                    assertEquals(0, count(closed, panel, ::green))
                    assertFalse(backdrop.hasMountedMenus || mounted)

                    show.value = true
                    scene.settledFrame()
                    assertTrue(backdrop.hasMountedMenus && mounted)
                    installed.value = false
                    scene.settledFrame()
                    assertFalse(backdrop.hasMountedMenus || backdrop.isExpanded || mounted,
                        "Unmounting an open picker must also remove every ownership flag.")
                }
            } finally { Files.deleteIfExists(file) }
        }
    }

    @Test fun thePopupsOwnBackdropDoesNotDisableNativePackSelectionOrPermissionCheckedSending() {
        val backdrop = ContextMenuBackdropState()
        val show = mutableStateOf(true)
        val state = mutableStateOf(snapshot(listOf(sticker(1, "🙂"))).copy(
            stickerSets = listOf(StickerPack(300, "原生贴纸包")), selected = ChatItem(1, "synthetic", canSendStickers = false)))
        val packs = mutableListOf<Pair<ChatToken, Long?>>()
        val sent = mutableListOf<Pair<ChatToken, Long>>()
        val features = ChatFeatureActions(loadStickers = { token, set ->
            packs += token to set
            state.value = state.value.copy(selectedStickerSetId = set, stickers = listOf(sticker(2, "👍")))
        }, sendSticker = { token, item -> sent += token to item.id })
        focusedScene(620, 620, backdrop = backdrop) {
            StickerPicker(show.value, state.value, features, active = !backdrop.hasMountedMenus,
                onDismiss = { show.value = false }, anchor = Rect(520f, 550f, 552f, 582f))
        }.use { scene ->
            scene.settledFrame()
            assertTrue(backdrop.hasMountedMenus)
            scene.click(scene.description("贴纸包 原生贴纸包"))
            scene.settledFrame()
            assertEquals(listOf<Pair<ChatToken, Long?>>(state.value.token to 300L), packs)
            scene.click(scene.description("发送贴纸 👍"))
            scene.settledFrame()
            assertTrue(sent.isEmpty(), "Native sticker permission must still disable submission.")
            state.value = state.value.copy(selected = state.value.selected?.copy(canSendStickers = true))
            scene.settledFrame()
            scene.click(scene.description("发送贴纸 👍"))
            scene.settledFrame()
            assertEquals(listOf(state.value.token to 2L), sent,
                "The parent's timeline is suspended by this popup, but the popup must still submit its selected native item once.")
            assertFalse(show.value || backdrop.hasMountedMenus)

            show.value = true
            scene.settledFrame()
            state.value = state.value.copy(token = ChatToken(1, 2, 9), selected = ChatItem(9, "other"))
            scene.settledFrame()
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
            assertFalse(backdrop.hasMountedMenus || show.value)
            assertEquals(1, sent.size, "Changing the target cannot retain an actionable old catalog.")
        }
    }

    @Test fun nativeThumbnailsRemainStillUntilHoverAndReturnWithoutReloadingWhenFocusIsLost() {
        val thumbnail = Files.createTempFile("synthetic-sticker-thumb-", ".png")
        val animation = Files.createTempFile("synthetic-sticker-hover-", ".tgs")
        greenPng(thumbnail)
        GZIPOutputStream(Files.newOutputStream(animation)).use { it.write(MOVING_CIRCLE.toByteArray()) }
        val focused = mutableStateOf(true)
        val item = StickerItem(1, 300, "🙂", MessageMedia(MediaFile(101, localPath = animation.toString(), downloaded = true),
            thumbnail = MediaFile(102, localPath = thumbnail.toString(), downloaded = true), thumbnailFormat = MediaFormat.PHOTO,
            format = MediaFormat.TGS))
        try {
            focusedScene(392, 460, focused = focused) { StickerPickerPanel(snapshot(listOf(item)), ChatFeatureActions(), true, {}) }.use { scene ->
                scene.settledFrame()
                val cell = scene.description("发送贴纸 🙂")
                scene.awaitPixels(cell, ::green)
                val idle = scene.settledFrame()
                Thread.sleep(140)
                assertEquals(0, changes(idle, scene.settledFrame(3), cell), "Unhovered native cells must not run animation clocks.")
                Files.delete(thumbnail)
                scene.moveTo(cell.center.x, cell.center.y)
                scene.awaitPixels(cell, ::red)
                Files.delete(animation)
                val first = scene.settledFrame(1)
                Thread.sleep(140)
                assertTrue(changes(first, scene.settledFrame(3), cell) > 8, "The hover must advance the supplied native TGS.")
                focused.value = false
                scene.awaitPixels(cell, ::green)
                val unfocused = scene.settledFrame()
                Thread.sleep(140)
                assertEquals(0, changes(unfocused, scene.settledFrame(3), cell), "Window focus loss must stop the hover clock.")
            }
        } finally { Files.deleteIfExists(thumbnail); Files.deleteIfExists(animation) }
    }

    @Test fun aLargeCatalogDownloadsOnlyVisibleThumbnailsUntilItsCellIsHovered() {
        val downloads = mutableSetOf<Int>()
        val items = (1..200).map { index -> StickerItem(index.toLong(), 300, "🙂", MessageMedia(
            MediaFile(2000 + index, canDownload = true), thumbnail = MediaFile(1000 + index, canDownload = true),
            format = MediaFormat.WEBM, thumbnailFormat = MediaFormat.WEBP)) }
        focusedScene(392, 460) {
            StickerPickerPanel(snapshot(emptyList()).copy(
                stickerSets = listOf(StickerPack(300, "200-entry native installed pack", size = 200)),
                stickerSections = listOf(StickerSection(300, "200-entry native installed pack", items, loaded = true))),
                ChatFeatureActions(downloadFile = { _, id -> downloads += id }), true, {})
        }.use { scene ->
            scene.settledFrame()
            assertTrue(downloads.size in 1..32, "A composed page must not download the complete 200-entry native catalog.")
            assertTrue(downloads.all { it in 1001..1200 }, "Idle cells prefer thumbnails rather than downloading every full video.")
            val cell = scene.description("发送贴纸 🙂", first = true)
            scene.moveTo(cell.center.x, cell.center.y)
            scene.settledFrame()
            assertEquals(1, downloads.count { it in 2001..2200 }, "Only the hovered native video is requested.")
        }
    }

    @Test fun aNarrowShortViewportKeepsItsCloseTargetInsideTheWindow() {
        val show = mutableStateOf(true)
        focusedScene(260, 220) {
            StickerPicker(show.value, snapshot(emptyList()), ChatFeatureActions(), true, { show.value = false },
                anchor = Rect(218f, 172f, 250f, 204f))
        }.use { scene ->
            scene.settledFrame()
            val panel = scene.description("贴纸选择面板")
            val close = scene.description("关闭贴纸")
            assertTrue(panel.left >= 0 && panel.top >= 0 && panel.right <= 260 && panel.bottom <= 220)
            assertTrue(close.left >= panel.left && close.top >= panel.top && close.right <= panel.right && close.bottom <= panel.bottom)
            scene.click(close)
            scene.settledFrame()
            assertFalse(show.value)
        }
    }

    @Test fun nativePackNavigationStaysBelowTheGridAndLoadingOrFailureDoesNotMoveTheFooter() {
        for ((width, height) in listOf(392 to 460, 260 to 220)) {
            val state = mutableStateOf(snapshot(listOf(sticker(1, "🙂"))).copy(
                stickerSets = listOf(StickerPack(300, "原生贴纸包"))))
            val loads = mutableListOf<Long?>()
            focusedScene(width, height) {
                StickerPickerPanel(state.value, ChatFeatureActions(loadStickers = { _, pack -> loads += pack }), true, {})
            }.use { scene ->
                scene.settledFrame()
                val panel = scene.description("贴纸选择面板")
                val footer = scene.description("贴纸包栏")
                val grid = scene.description("贴纸网格")
                val pack = scene.description("贴纸包 原生贴纸包")
                assertTrue(grid.bottom <= footer.top && footer.bottom == panel.bottom,
                    "The native pack footer must stay below the scrolling grid at the panel's bottom edge.")
                assertTrue(pack.top >= footer.top && pack.bottom <= footer.bottom)
                if (height >= 300) {
                    val search = scene.description("筛选当前贴纸")
                    assertTrue(search.top >= grid.top && search.bottom <= grid.bottom,
                        "The native selector's search row belongs to the scrolling content above its sections.")
                }
                state.value = state.value.copy(stickersLoading = true)
                scene.settledFrame()
                assertEquals(footer, scene.description("贴纸包栏"), "Loading must not displace native pack navigation.")
                scene.text("正在加载贴纸……")
                scene.click(scene.text("重新加载"))
                scene.settledFrame()
                assertTrue(loads.isEmpty(), "An active native load must not be dispatched again.")
                state.value = state.value.copy(stickersLoading = false, stickersError = ChatError.REQUEST_FAILED)
                scene.settledFrame()
                assertEquals(footer, scene.description("贴纸包栏"), "A native error must leave the footer visible and fixed.")
                scene.click(scene.text("重新加载"))
                scene.settledFrame()
                assertEquals(listOf<Long?>(null), loads, "Retry must still request the current native recent catalog.")
                scene.click(scene.description("贴纸包 原生贴纸包"))
                scene.settledFrame()
                assertEquals(listOf(null, 300L), loads, "The footer must retain native set selection after a failed load.")
            }
        }
    }

    @Test fun popupHoverReportsEnterLeaveAndDisposalWhileTheComposerButtonRemainsReachable() {
        val mounted = mutableStateOf(true)
        val hover = mutableListOf<Boolean>()
        var buttonHovered = false
        var dismissed = 0
        focusedScene(800, 620) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.offset(700.dp, 550.dp).size(32.dp).semantics { contentDescription = "贴纸入口" }
                    .onPointerEvent(PointerEventType.Enter) { buttonHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { buttonHovered = false })
                if (mounted.value) StickerPicker(true, snapshot(listOf(sticker(1, "🙂"))), ChatFeatureActions(), true, { dismissed++ },
                    anchor = Rect(700f, 550f, 732f, 582f), onHoverChanged = { hover += it })
            }
        }.use { scene ->
            scene.settledFrame()
            val panel = scene.description("贴纸选择面板")
            val button = scene.description("贴纸入口")
            assertTrue(kotlin.math.abs(panel.bottom + StickerPickerShadowPadding.value - (button.top - 10f)) <= 1f,
                "The complete native Popup's shadow window must end 10dp before the real composer button.")
            scene.moveTo(panel.center.x, panel.center.y)
            scene.settledFrame(2)
            assertTrue(hover.lastOrNull() == true, "The panel's real pointer entry must cancel its caller's pending hide.")
            scene.moveTo(button.center.x, button.center.y)
            scene.settledFrame(2)
            assertTrue(hover.lastOrNull() == false, "Leaving the panel must report the start of the caller's hide delay.")
            assertTrue(buttonHovered, "A hover panel must not block the composer's own pointer-entry events.")
            scene.moveTo(panel.center.x, panel.center.y)
            scene.settledFrame(2)
            assertTrue(hover.lastOrNull() == true)
            scene.click(scene.description("筛选当前贴纸"))
            scene.keyStroke(java.awt.event.KeyEvent.VK_X, 'x')
            scene.settledFrame()
            scene.text("当前贴纸没有匹配项")
            scene.keyStroke(java.awt.event.KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertEquals(1, dismissed, "The hover popup must retain keyboard input and Escape dismissal.")
            mounted.value = false
            scene.settledFrame()
            assertFalse(hover.last(), "Disposal while hovered must clear the caller's retained hover flag.")
            assertTrue(scene.descriptions("贴纸选择面板").isEmpty())
        }
    }

    private fun snapshot(items: List<StickerItem>) = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
        selected = ChatItem(1, "synthetic", canSendStickers = true), stickers = items)
    private fun sticker(id: Long, emoji: String, file: MediaFile = MediaFile(id.toInt() + 100)) =
        StickerItem(id, 300, emoji, MessageMedia(file, format = MediaFormat.PHOTO))

    private fun focusedScene(width: Int, height: Int, focused: State<Boolean> = mutableStateOf(true),
        backdrop: ContextMenuBackdropState? = null, content: @Composable () -> Unit) = ComposePixelScene(width, height) {
        val theme = remember { ThemeController(ColorSchemeMode.Light) }
        MiuixTheme(controller = theme) {
            val window = LocalWindowInfo.current
            val focus = remember(window) { object : WindowInfo by window { override val isWindowFocused get() = focused.value } }
            CompositionLocalProvider(LocalWindowInfo provides focus, LocalContextMenuBackdrop provides backdrop, content = content)
        }
    }
    private fun greenPng(file: java.nio.file.Path) {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, 0xff24bd76.toInt())
        check(ImageIO.write(image, "png", file.toFile()))
    }
    private fun ComposePixelScene.descriptions(value: String) = semanticsBounds { value in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.description(value: String, first: Boolean = false) =
        assertNotNull(descriptions(value).let { if (first) it.firstOrNull() else it.singleOrNull() }, value)
    private fun ComposePixelScene.text(value: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
    }.singleOrNull(), value)
    private fun ComposePixelScene.click(bounds: Rect) { moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y) }
    private fun ComposePixelScene.awaitPixels(bounds: Rect, predicate: (Int) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do { if (count(settledFrame(2), bounds, predicate) > 20) return; Thread.sleep(5) } while (System.nanoTime() < deadline)
        fail("The native sticker raster did not reach its expected static/hover state.")
    }
    private fun count(frame: ComposePixelFrame, bounds: Rect, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(frame.height))
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(frame.width)) if (predicate(frame[x, y])) count++
        return count
    }
    private fun changes(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (first[x, y] != second[x, y]) count++
        return count
    }
    private fun green(pixel: Int): Boolean {
        val red = pixel ushr 16 and 255; val green = pixel ushr 8 and 255; val blue = pixel and 255
        return green > 120 && green > red + 35 && green > blue + 20
    }
    private fun red(pixel: Int) = (pixel ushr 16 and 255) > 170 && (pixel ushr 8 and 255) < 100 && (pixel and 255) < 100
    private companion object {
        const val MOVING_CIRCLE = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":32,"h":32,"nm":"synthetic-sticker","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"circle","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[7,16,0],"e":[25,16,0],"i":{"x":[0.67],"y":[1]},"o":{"x":[0.33],"y":[0]}},{"t":30,"s":[25,16,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[10,10]},"d":1},{"ty":"fl","c":{"a":0,"k":[0.9,0.1,0.1,1]},"o":{"a":0,"k":100},"r":1}],"ip":0,"op":30,"st":0,"bm":0}]}"""
    }
}
