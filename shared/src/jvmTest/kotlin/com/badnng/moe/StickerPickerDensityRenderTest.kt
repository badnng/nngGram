package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.chat.StickerPack
import com.badnng.moe.chat.StickerSection
import java.awt.image.BufferedImage
import java.io.File
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

/** Actual narrow Popup capacity, rather than only the existence of a scrolling grid. */
class StickerPickerDensityRenderTest {
    @Test
    fun narrowComposerShowsFiveColumnsAndTheFollowingNativePackWithoutMovingItsCompactFooter() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                val show = mutableStateOf(true)
                val recent = (1..20).map { sticker(it.toLong(), 300L, "R$it") }
                val packItems = (1..30).map { sticker(100L + it, 300L, "P$it") }
                val packs = (1..10).map { StickerPack(299L + it, "原生贴纸包 $it", 30, packItems.first()) }
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
                    selected = ChatItem(1, "Synthetic narrow sticker capacity", canSendStickers = true),
                    stickers = recent, stickerSets = packs,
                    stickerSections = listOf(StickerSection(null, "最近", recent, loaded = true),
                        StickerSection(300L, packs.first().title, packItems, loaded = true)))
                val requested = mutableListOf<Long?>()
                val sent = mutableListOf<Long>()
                val backdrop = ContextMenuBackdropState()
                val anchor = scaled(Rect(290f, 750f, 322f, 782f), density)
                scene(mode, density, backdrop) {
                    StickerPicker(show.value, snapshot,
                        ChatFeatureActions(loadStickers = { _, set -> requested += set },
                            sendSticker = { _, sticker -> sent += sticker.id }),
                        active = !backdrop.hasMountedMenus, onDismiss = { show.value = false }, anchor = anchor)
                }.use { scene ->
                    val initial = scene.settledFrame()
                    val panel = scene.description("贴纸选择面板")
                    val grid = scene.description("贴纸网格")
                    val footer = scene.description("贴纸包栏")
                    assertTrue(panel.left >= 0f && panel.top >= 0f && panel.right <= 366f * density && panel.bottom <= 800f * density)
                    assertTrue(panel.height > 460f * density,
                        "The screenshot's tall narrow viewport must use its available vertical space rather than the old 460dp cap: $panel")
                    val minimum = (44f * density).roundToInt()
                    val expectedHeight = minimum + ((anchor.top.roundToInt() - minimum) * .75f).roundToInt() - (20f * density).roundToInt()
                    assertTrue(abs(panel.height - expectedHeight) <= 1.5f,
                        "The body must use the official 44px minimum, .75 available-height ratio and 20px total shadow allocation: $panel, expected=$expectedHeight")
                    assertTrue(abs(panel.width - 345f * density) <= 1.5f,
                        "The official 345dp selector body must fit the screenshot's 366dp window.")
                    assertTrue(abs(panel.bottom + StickerPickerShadowPadding.value * density - (anchor.top - 10f * density)) <= 1.5f,
                        "The taller panel must retain the pointer gap outside its complete native shadow bounds.")
                    assertTrue(averageBelow(initial, panel, density, 26f) - averageBelow(initial, panel, density, 3f) >= 2.0,
                        "The narrow popup must retain a visible downward shadow inside its transparent native bounds.")
                    assertTrue(footer.height <= 40f * density && footer.bottom == panel.bottom,
                        "A compact icon strip belongs at the panel bottom: $footer")
                    val firstRow = (1..5).map { scene.description("发送贴纸 R$it") }
                    assertEquals(5, firstRow.map { it.left.roundToInt() }.distinct().size,
                        "The same five columns shown by the official selector must fit this narrow popup.")
                    assertTrue(firstRow.maxOf { it.top } - firstRow.minOf { it.top } <= 1f)
                    assertTrue(firstRow.all { abs(it.width - 65f * density) <= 1.5f && abs(it.height - 65f * density) <= 1.5f },
                        "Five official 65dp square cells must fit the selector's native 19dp side allocation.")
                    val secondRow = scene.description("发送贴纸 R6")
                    assertTrue(secondRow.top > firstRow.first().top && abs(secondRow.left - firstRow.first().left) <= 1f)
                    val recentBounds = (1..20).map { scene.description("发送贴纸 R$it") }
                    assertTrue(recentBounds.all { it.top >= grid.top && it.bottom <= grid.bottom },
                        "All four native recent rows must be fully visible at once.")
                    val header = scene.text(packs.first().title)
                    val firstPackRow = (1..5).map { scene.description("发送贴纸 P$it") }
                    assertTrue(header.top >= recentBounds.maxOf { it.bottom } && firstPackRow.all { it.top >= header.bottom && it.bottom <= grid.bottom },
                        "The next native pack's title and a complete first row must be visible without replacing recent stickers.")
                    val packTabs = scene.packTabs().filter { it.left >= footer.left && it.right <= footer.right }
                    assertTrue(packTabs.size >= 6, "Long pack titles must not reduce the icon footer to two text tabs: $packTabs")
                    assertTrue(packTabs.all { it.width <= 40f * density && it.top >= footer.top && it.bottom <= footer.bottom })
                    initial.save("${mode.name.lowercase()}-${density}-initial")

                    scene.click(scene.description("贴纸包 ${packs.first().title}"))
                    val selected = scene.settledFrame()
                    assertEquals(footer, scene.description("贴纸包栏"), "Scrolling to a section must keep the footer fixed.")
                    assertTrue(scene.description("发送贴纸 P1").top < firstPackRow.first().top,
                        "A pack icon scrolls the continuous native section list to that pack.")
                    assertTrue(scene.isSelected("贴纸包 ${packs.first().title}"),
                        "The footer's selected icon must follow the section currently at the top of the scroll area.")
                    assertTrue(requested.isEmpty(), "An already loaded pack is selected by scrolling, without a redundant replacement request.")
                    selected.save("${mode.name.lowercase()}-${density}-pack")

                    scene.click(scene.description("最近贴纸"))
                    scene.settledFrame()
                    assertTrue(abs(scene.description("发送贴纸 R1").top - grid.top - 11f * density) <= 1.5f,
                        "The official recent shortcut scrolls to its section, leaving its 11dp skip while the search row scrolls above it.")
                    assertTrue(scene.isSelected("最近贴纸"))
                    show.value = false
                    scene.settledFrame(1)
                    assertTrue(backdrop.hasMountedMenus, "The bigger panel retains only drawing/backdrop ownership through exit.")
                    assertFalse(backdrop.isExpanded)
                    assertTrue(scene.sendCells().isEmpty(), "Closing retained cells immediately lose their input and semantics.")
                    scene.click(firstRow.first())
                    scene.settledFrame()
                    assertTrue(sent.isEmpty())
                    assertFalse(backdrop.hasMountedMenus)
                }
            }
        }
    }

    @Test
    fun choosingAPackAfterANoMatchFilterClearsSearchAndNavigatesToThatPack() {
        val recent = (1..20).map { sticker(it.toLong(), 300L, "R$it") }
        val packItems = (1..30).map { sticker(100L + it, 300L, "P$it") }
        val pack = StickerPack(300L, "原生贴纸包", 30, packItems.first())
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
            selected = ChatItem(1, "Synthetic search-to-pack navigation", canSendStickers = true),
            stickers = recent, stickerSets = listOf(pack),
            stickerSections = listOf(StickerSection(null, "最近", recent, loaded = true),
                StickerSection(300L, pack.title, packItems, loaded = true)))
        val backdrop = ContextMenuBackdropState()
        scene(ColorSchemeMode.Light, 1f, backdrop) {
            StickerPicker(true, snapshot, ChatFeatureActions(), active = !backdrop.hasMountedMenus, onDismiss = {},
                anchor = Rect(290f, 750f, 322f, 782f))
        }.use { scene ->
            scene.settledFrame()
            val grid = scene.description("贴纸网格")
            scene.click(scene.description("筛选当前贴纸"))
            scene.keyStroke(java.awt.event.KeyEvent.VK_X, 'x')
            scene.settledFrame()
            scene.text("当前贴纸没有匹配项")
            assertTrue(scene.description("筛选当前贴纸").top >= grid.top,
                "An empty local result must retain its scrolling search field so the user can correct it.")
            scene.click(scene.description("贴纸包 ${pack.title}"))
            scene.settledFrame()
            assertTrue(scene.description("发送贴纸 P1").top < grid.top + 40f,
                "Clearing a no-match filter must recompute native section offsets before navigating to the requested pack.")
            assertTrue(scene.isSelected("贴纸包 ${pack.title}"))
        }
    }

    @Test
    fun emptyLoadedRecentDoesNotConsumeTheFirstNativePackViewportOrItsFooterSelection() {
        val packItems = (1..20).map { sticker(100L + it, 300L, "P$it") }
        val pack = StickerPack(300L, "原生贴纸包", 20, packItems.first())
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
            selected = ChatItem(1, "Synthetic empty-recent capacity", canSendStickers = true),
            stickerSets = listOf(pack), stickerSections = listOf(StickerSection(null, "最近", loaded = true),
                StickerSection(300L, pack.title, packItems, loaded = true)))
        ComposePixelScene(366, 554) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MiuixTheme(controller = controller) {
                    StickerPickerPanel(snapshot, ChatFeatureActions(), interactive = true, onDismiss = {})
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val grid = scene.description("贴纸网格")
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "暂无贴纸" }
            }.isEmpty(), "An empty loaded recent section must not reserve a whole status row before installed packs.")
            assertTrue(scene.descriptions("最近贴纸").isEmpty(),
                "A loaded empty recent section must not take a native footer icon slot.")
            assertTrue(abs(scene.description("发送贴纸 P1").top - grid.top - 49f - 33f) <= 1f,
                "The first pack starts immediately after the official 49px search row and 33px section header.")
            assertTrue(scene.isSelected("贴纸包 ${pack.title}"),
                "The first installed pack owns selection even while its search row is still visible.")
            scene.scrollAt(grid.center.x, grid.center.y, deltaY = 12f)
            scene.settledFrame()
            assertTrue(scene.isSelected("贴纸包 ${pack.title}"),
                "Scrolling within the same native section must keep its footer icon selected.")
        }
    }

    private fun scene(mode: ColorSchemeMode, density: Float, backdrop: ContextMenuBackdropState,
        content: @Composable () -> Unit) = ComposePixelScene((366 * density).roundToInt(), (800 * density).roundToInt()) {
        val controller = remember { ThemeController(mode) }
        CompositionLocalProvider(LocalDensity provides Density(density), LocalContextMenuBackdrop provides backdrop) {
            MiuixTheme(controller = controller) {
                Box(Modifier.fillMaxSize().background(Color(0xffd1d2d3))) { content() }
            }
        }
    }

    private fun sticker(id: Long, setId: Long, label: String) = StickerItem(id, setId, label,
        MessageMedia(MediaFile(id.toInt() + 1000)))
    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)
    private fun ComposePixelScene.text(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.singleOrNull(), label)
    private fun ComposePixelScene.packTabs() = semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("贴纸包 ") || label == "最近贴纸" }
    }
    private fun ComposePixelScene.sendCells() = semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("发送贴纸 ") }
    }
    private fun ComposePixelScene.isSelected(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
            it.getOrNull(SemanticsProperties.Selected) == true
    }.size == 1
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun scaled(bounds: Rect, density: Float) = Rect(bounds.left * density, bounds.top * density,
        bounds.right * density, bounds.bottom * density)
    private fun averageBelow(frame: ComposePixelFrame, body: Rect, density: Float, belowDp: Float): Double {
        val y = (body.bottom + belowDp * density).roundToInt().coerceIn(0, frame.height - 1)
        val center = body.center.x.roundToInt()
        val halfWidth = (body.width / 5f).roundToInt().coerceAtLeast(1)
        return (center - halfWidth..center + halfWidth).map { x ->
            val pixel = frame[x.coerceIn(0, frame.width - 1), y]
            ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3.0
        }.average()
    }
    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "sticker-picker-density")
        check(directory.isDirectory || directory.mkdirs())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, IntArray(width * height) { index -> this[index % width, index / width] }, 0, width)
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }
}
