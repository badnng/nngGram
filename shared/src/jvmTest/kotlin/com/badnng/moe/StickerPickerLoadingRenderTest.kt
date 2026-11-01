package com.badnng.moe

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatError
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

/** Delayed native metadata must preserve the reader's real row at the bottom of the lazy grid. */
class StickerPickerLoadingRenderTest {
    @Test
    fun adjacentSectionsCompletingBeforeTheNextFrameKeepTheBottomRowAndFooter() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val state = mutableStateOf(unloadedSnapshot())
            val gridState = LazyGridState()
            val downloads = mutableListOf<Int>()
            val sends = mutableListOf<Long>()
            val refreshes = mutableListOf<Long?>()
            val ensures = mutableListOf<Long?>()
            scene(density, mode) {
                StickerPickerPanel(state.value, ChatFeatureActions(
                    ensureStickerSection = { _, id -> ensures += id },
                    loadStickers = { _, id -> refreshes += id },
                    downloadFile = { _, id -> downloads += id },
                    sendSticker = { _, sticker -> sends += sticker.id }), true, {}, gridState = gridState)
            }.use { scene ->
                scene.settledFrame()
                val grid = scene.description("贴纸网格")
                val footer = scene.description("贴纸包栏")
                scene.scrollToBottom(grid)
                assertFalse(gridState.canScrollForward, "Real wheel input must reach the final native section before its metadata completes.")
                val bottom = scene.lastRowBottom(gridState, grid)
                val demands = ensures.toList()
                scene.settledFrame().save("bottom-${mode.name.lowercase()}-$density-pending")

                // Two actor results may be coalesced into one Compose frame. Both old placeholders disappear together.
                state.value = loaded(state.value, 11, 22)
                val ready = scene.settledFrame()
                val last = scene.description("发送贴纸 B200")
                assertTrue(abs(last.bottom - bottom) <= 1f,
                    "Completing the earlier and current native sections together must keep the same last row, rather than jumping into an earlier pack.")
                assertFalse(gridState.canScrollForward)
                assertEquals(footer, scene.description("贴纸包栏"))
                assertTrue(scene.selected("贴纸包 Bottom"))
                assertEquals(demands, ensures, "A metadata completion must not manufacture another viewport demand.")
                assertTrue(refreshes.isEmpty(), "Viewport loading must never turn into an explicit native refresh.")
                assertTrue(downloads.isEmpty() && sends.isEmpty(), "Structural placeholders cannot request files or send fabricated stickers.")
                ready.save("bottom-${mode.name.lowercase()}-$density-ready")

                scene.scrollAt(grid.center.x, grid.center.y, deltaY = -3f)
                scene.settledFrame()
                scene.scrollToBottom(grid)
                assertEquals(footer, scene.description("贴纸包栏"))
                assertTrue(refreshes.isEmpty(), "Returning to loaded native rows must not refresh their catalogs.")
                assertTrue(abs(scene.description("发送贴纸 B200").bottom - bottom) <= 1f)
            }
        }
    }

    @Test
    fun eitherAdjacentSectionCanFinishFirstWithoutMovingTheScrolledReader() {
        for (density in listOf(1f, 1.25f)) for (first in listOf(11L, 22L)) {
            val state = mutableStateOf(unloadedSnapshot())
            val gridState = LazyGridState()
            scene(density, ColorSchemeMode.Light) {
                StickerPickerPanel(state.value, ChatFeatureActions(), true, {}, gridState = gridState)
            }.use { scene ->
                scene.settledFrame()
                val grid = scene.description("贴纸网格")
                val footer = scene.description("贴纸包栏")
                scene.scrollToBottom(grid)
                scene.scrollAt(grid.center.x, grid.center.y, deltaY = -2f)
                scene.settledFrame()
                val anchor = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                state.value = loaded(state.value, first)
                scene.settledFrame()
                assertEquals(anchor, gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset,
                    "Loading either the offscreen or current section must retain the row and fractional scroll offset.")
                state.value = loaded(state.value, if (first == 11L) 22 else 11)
                scene.settledFrame()
                assertEquals(anchor, gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset)
                assertEquals(footer, scene.description("贴纸包栏"))
            }
        }
    }

    @Test
    fun footerNavigationAndAFailedBottomSectionUseTheSameSlotsUntilExplicitRetry() {
        for (density in listOf(1f, 1.25f)) {
            val state = mutableStateOf(unloadedSnapshot())
            val gridState = LazyGridState()
            val refreshes = mutableListOf<Long?>()
            val sends = mutableListOf<Long>()
            val downloads = mutableListOf<Int>()
            scene(density, ColorSchemeMode.Dark) {
                StickerPickerPanel(state.value, ChatFeatureActions(
                    loadStickers = { _, id -> refreshes += id },
                    sendSticker = { _, sticker -> sends += sticker.id },
                    downloadFile = { _, id -> downloads += id }), true, {}, gridState = gridState)
            }.use { scene ->
                scene.settledFrame()
                val grid = scene.description("贴纸网格")
                val footer = scene.description("贴纸包栏")
                scene.click(scene.description("贴纸包 Bottom"))
                scene.settledFrame()
                val anchor = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                state.value = loaded(state.value, 11)
                scene.settledFrame()
                assertEquals(anchor, gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset,
                    "A footer target must include all reserved slots in preceding unloaded packs.")
                assertTrue(scene.selected("贴纸包 Bottom"))
                scene.scrollToBottom(grid)
                val bottom = scene.lastRowBottom(gridState, grid)
                state.value = state.value.copy(stickerSections = state.value.stickerSections.map {
                    if (it.setId == 22L) it.copy(loading = false, error = ChatError.REQUEST_FAILED) else it
                })
                scene.settledFrame()
                assertEquals(bottom, scene.lastRowBottom(gridState, grid))
                assertTrue(refreshes.isEmpty(), "A native error remains stable until the user explicitly retries it.")

                val retries = scene.descriptions("重新加载贴纸包 Bottom").filter {
                    it.top >= grid.top && it.bottom <= grid.bottom
                }
                assertTrue(retries.isNotEmpty(), "The failed native section must remain retryable at the bottom of its viewport.")
                scene.click(retries.last())
                scene.settledFrame()
                assertEquals(listOf<Long?>(22), refreshes)
                state.value = loaded(state.value, 22)
                scene.settledFrame()
                assertTrue(abs(scene.description("发送贴纸 B200").bottom - bottom) <= 1f)
                assertEquals(footer, scene.description("贴纸包栏"))
                assertTrue(sends.isEmpty() && downloads.isEmpty(), "Retrying known section metadata does not grant a placeholder a sticker identity.")
            }
        }
    }

    @Test
    fun nativeCountCorrectionsKeepAnExistingRowAndClampOnlyWhenThatRowNoLongerExists() {
        for (actualCount in listOf(230, 7)) {
            val state = mutableStateOf(unloadedSnapshot())
            val gridState = LazyGridState()
            scene(1f, ColorSchemeMode.Light) {
                StickerPickerPanel(state.value, ChatFeatureActions(), true, {}, gridState = gridState)
            }.use { scene ->
                scene.settledFrame()
                val grid = scene.description("贴纸网格")
                scene.scrollToBottom(grid)
                val anchor = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                val earlierLoaded = loaded(state.value, 11)
                state.value = earlierLoaded.copy(stickerSections = earlierLoaded.stickerSections.map {
                    if (it.setId == 22L) it.copy(stickers = stickers(22, actualCount, "B"), loading = false, loaded = true) else it
                })
                scene.settledFrame()
                if (actualCount > 200) {
                    assertEquals(anchor, gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset,
                        "A larger real native list preserves an existing row instead of forcing the reader to its new bottom.")
                    assertTrue(gridState.canScrollForward)
                } else {
                    assertFalse(gridState.canScrollForward)
                    scene.description("发送贴纸 B7")
                    assertTrue(scene.descriptions("发送贴纸 B8").isEmpty(), "Estimated slots disappear when the native list is shorter.")
                }
            }
        }
    }

    private fun unloadedSnapshot() = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 1),
        selected = ChatItem(1, "Synthetic lazy sticker sections", canSendStickers = true),
        stickerSets = listOf(StickerPack(11, "Earlier", 40), StickerPack(22, "Bottom", 200)),
        stickerSections = listOf(StickerSection(null, "最近", loaded = true),
            StickerSection(11, "Earlier", loading = true), StickerSection(22, "Bottom", loading = true)))

    private fun loaded(snapshot: ChatSnapshot, vararg ids: Long) = snapshot.copy(stickerSections = snapshot.stickerSections.map {
        if (it.setId != null && it.setId in ids) it.copy(stickers = stickers(it.setId, if (it.setId == 11L) 40 else 200,
            if (it.setId == 11L) "A" else "B"), loading = false, loaded = true, error = null) else it
    })

    private fun stickers(setId: Long, count: Int, prefix: String) = (1..count).map {
        val id = setId * 1000 + it
        StickerItem(id, setId, "$prefix$it", MessageMedia(MediaFile(id.toInt())))
    }

    private fun scene(density: Float, mode: ColorSchemeMode, content: @Composable () -> Unit) =
        ComposePixelScene((345 * density).roundToInt(), (460 * density).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) { MiuixTheme(controller = controller) { content() } }
        }

    private fun ComposePixelScene.scrollToBottom(grid: Rect) {
        repeat(3) { scrollAt(grid.center.x, grid.center.y, deltaY = 1000f); settledFrame() }
    }

    private fun ComposePixelScene.lastRowBottom(state: LazyGridState, grid: Rect): Float = grid.top +
        state.layoutInfo.visibleItemsInfo.maxOf { it.offset.y + it.size.height }

    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).singleOrNull(), label)

    private fun ComposePixelScene.selected(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() && it.getOrNull(SemanticsProperties.Selected) == true
    }.size == 1

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "sticker-picker-loading")
        check(directory.isDirectory || directory.mkdirs())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, IntArray(width * height) { index -> this[index % width, index / width] }, 0, width)
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }
}
