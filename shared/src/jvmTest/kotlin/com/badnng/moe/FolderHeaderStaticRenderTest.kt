package com.badnng.moe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** The supplied 366px recording requires one stationary header above the changing folder body. */
class FolderHeaderStaticRenderTest {
    @Test
    fun actualFolderSelectionMovesOnlyTheListBodyAndKeepsOneFullyPaintedInteractiveHeader() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val source = chats(100, "来源")
            val target = chats(200, "目标")
            var snapshot by mutableStateOf(ChatSnapshot(
                enabled = true, token = ChatToken(1, 0, null), activeList = ChatListRef(1),
                folders = listOf(ChatFolderInfo(1, "My Github"), ChatFolderInfo(2, "科技")),
                items = source, knownChats = source + target, listEnd = true,
                account = AccountProfile(900001, "合成测试账号"),
                unreadChats = mapOf(ChatListRef() to 125, ChatListRef(2) to 24),
            ))
            val selectedLists = mutableListOf<ChatListRef>()
            val selectedChats = mutableListOf<Long>()
            var controlInk = 0
            var accountInk = 0
            val sample = "${if (dark) "dark" else "light"}-$density"
            ComposePixelScene((366 * density).roundToInt(), (800 * density).roundToInt()) {
                val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(controller = theme) {
                        controlInk = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
                        accountInk = MiuixTheme.colorScheme.onPrimary.toArgb()
                        ChatWorkspace(snapshot, ConnectionPhase.READY, drafts, onSettings = {},
                            onSelectChat = { _, id -> selectedChats += id }, onLoadChats = {}, onLoadOlder = {},
                            onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                            windowFocused = true,
                            features = ChatFeatureActions(selectList = { _, list ->
                                selectedLists += list
                                snapshot = snapshot.copy(activeList = list, items = if (list.folderId == 2) target else source)
                            }), darkTheme = dark, onDarkTheme = {}, persistUiPreferences = false)
                    }
                }
            }.use { scene ->
                scene.moveTo(360 * density, 790 * density)
                val initial = scene.settledFrame()
                initial.save("$sample-source")
                val header = scene.headerBounds()
                val sourceRow = scene.row(source.first().title)
                val masks = listOf(
                    inkMask("search", initial, header.getValue("search"), controlInk, 7f * density),
                    inkMask("manage", initial, header.getValue("manage"), controlInk, 3f * density),
                    inkMask("avatar", initial, header.getValue("avatar"), accountInk, 12f * density),
                )
                scene.click(header.getValue("tab:科技"))
                scene.moveTo(360 * density, 790 * density)
                assertEquals(listOf(ChatListRef(2)), selectedLists)
                var enteringRow: Rect? = null
                var intermediate: ComposePixelFrame? = null
                repeat(5) { index ->
                    val frame = scene.settledFrame(1)
                    if (index == 0 || index == 2 || index == 4) frame.save("$sample-switch-${index + 1}")
                    assertEquals(header, scene.headerBounds(),
                        "The actual search/avatar/manage/tab hit regions must stay fixed with one copy at frame ${index + 1}, $sample.")
                    masks.forEach { it.assertStillPainted(frame, "Header glyphs must not fade or replay entry at frame ${index + 1}, $sample") }
                    val candidate = if (index >= 1 && enteringRow == null) scene.row(target.first().title) else null
                    if (candidate != null && candidate.left > sourceRow.left + 3f * density &&
                        bodyChanges(initial, frame, header.getValue("tab:科技").bottom) > 500) {
                        enteringRow = candidate
                        intermediate = frame
                        frame.save("$sample-moving-body")
                        assertTrue(scene.rows(source.first().title).isEmpty(),
                            "The retained old body must immediately leave the interactive/accessibility tree.")
                        // This x is within the departing source row, before the entering
                        // target row's shifted left edge, so it cannot accidentally click B.
                        val oldX = sourceRow.left + 1.5f * density
                        assertTrue(oldX < candidate.left,
                            "The sampled target row must still be genuinely moving in from the right.")
                        scene.clickAt(oldX, sourceRow.center.y)
                        assertTrue(selectedChats.isEmpty(), "The painted outgoing folder body must reject clicks.")
                        scene.click(candidate)
                        assertEquals(listOf(target.first().id), selectedChats,
                            "Only the current target folder's real rows may accept input during the body animation.")
                        scene.moveTo(360 * density, 790 * density)
                    }
                }
                val final = scene.settledFrame()
                final.save("$sample-target")
                val finalRow = scene.row(target.first().title)
                assertTrue(checkNotNull(enteringRow).left > finalRow.left + density,
                    "Only the actual list body must pass through intermediate horizontal positions.")
                assertEquals(header, scene.headerBounds(), "The final target list must retain the same single stationary header.")
                masks.forEach { it.assertStillPainted(final, "The settled header must retain its original foregrounds, $sample") }
                assertTrue(scene.rows(source.first().title).isEmpty())
                assertEquals(1, scene.rows(target.first().title).size)
                assertTrue(bodyChanges(initial, checkNotNull(intermediate), header.getValue("tab:科技").bottom) > 500)
                assertTrue(bodyChanges(checkNotNull(intermediate), final, header.getValue("tab:科技").bottom) > 500,
                    "The saved intermediate body must differ from both its source and target endpoints.")
                assertEquals(listOf(target.first().id), selectedChats)
            }
        }
    }

    private fun chats(firstId: Long, prefix: String) = (1L..10L).map { index ->
        ChatItem(firstId + index, "$prefix 合成会话 $index", order = 100L - index)
    }

    private fun ComposePixelScene.headerBounds(): Map<String, Rect> = buildMap {
        put("search", description("搜索当前聊天列表"))
        put("avatar", description("打开账号菜单"))
        put("manage", description("管理文件夹"))
        assertTrue(semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "未读" }
        }.isEmpty(), "The real header must not retain an unread filter tab during folder transitions.")
        for (label in listOf("全部", "My Github", "科技", "已归档")) {
            val bounds = semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            }
            put("tab:$label", assertNotNull(bounds.singleOrNull(), "Expected exactly one persistent $label folder tab; got ${bounds.size}."))
        }
    }

    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), "Expected exactly one persistent header control: $label")

    private fun ComposePixelScene.rows(title: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == title }
    }

    private fun ComposePixelScene.row(title: String): Rect =
        assertNotNull(rows(title).singleOrNull(), "Expected exactly one current row: $title")

    private fun ComposePixelScene.click(bounds: Rect) = clickAt(bounds.center.x, bounds.center.y)

    private fun ComposePixelScene.clickAt(x: Float, y: Float) {
        moveTo(x, y)
        pressAt(x, y)
        releaseAt(x, y)
    }

    private data class InkPixel(val x: Int, val y: Int, val color: Int)
    private class InkMask(val pixels: List<InkPixel>) {
        fun assertStillPainted(frame: ComposePixelFrame, message: String) {
            val persistent = pixels.count { colorDistance(it.color, frame[it.x, it.y]) <= 12 }
            assertTrue(persistent >= pixels.size * 0.97f, "$message; $persistent / ${pixels.size} solid foreground pixels persisted.")
        }
    }

    private fun inkMask(label: String, frame: ComposePixelFrame, bounds: Rect, color: Int, inset: Float): InkMask {
        val region = buildList {
            for (y in ceil(bounds.top + inset).toInt() until floor(bounds.bottom - inset).toInt()) {
                for (x in ceil(bounds.left + inset).toInt() until floor(bounds.right - inset).toInt()) {
                    add(InkPixel(x, y, frame[x, y]))
                }
            }
        }
        val background = assertNotNull(region.groupingBy { it.color }.eachCount().maxByOrNull { it.value }?.key,
            "The $label control must provide a nonempty interior: $bounds, inset=$inset")
        // Miuix's summary foreground carries alpha. Compare its actual composited
        // raster ink with the sampled control fill, rather than treating RGB=0 with
        // alpha=.6 as opaque black (the actual light-theme glyph is RGB=101).
        val renderedInk = compositeArgb(color, background)
        val pixels = region.filter { colorDistance(it.color, renderedInk) <= 8 }
        assertTrue(pixels.size >= 6,
            "The synthetic $label control must render real glyphs before checking persistent foregrounds: " +
                "${pixels.size} pixels, foreground=${color.toUInt().toString(16)}, " +
                "fill=${background.toUInt().toString(16)}, raster=${renderedInk.toUInt().toString(16)}, bounds=$bounds.")
        return InkMask(pixels)
    }

    private fun bodyChanges(first: ComposePixelFrame, second: ComposePixelFrame, headerBottom: Float): Int {
        var count = 0
        for (y in ceil(headerBottom + 30).toInt() until first.height - 30) {
            for (x in 0 until first.width) if (first[x, y] != second[x, y]) count++
        }
        return count
    }

    private fun ComposePixelFrame.save(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "folder-static-header")
        check(directory.isDirectory || directory.mkdirs())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, IntArray(width * height) { index -> this[index % width, index / width] }, 0, width)
        check(ImageIO.write(image, "png", File(directory, "$name.png")))
    }

    companion object {
        private fun compositeArgb(foreground: Int, background: Int): Int {
            val alpha = (foreground ushr 24 and 255) / 255f
            fun channel(shift: Int): Int = (((foreground ushr shift and 255) * alpha) +
                ((background ushr shift and 255) * (1f - alpha))).roundToInt()
            return 0xff000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun colorDistance(first: Int, second: Int): Int = maxOf(
            abs((first ushr 16 and 255) - (second ushr 16 and 255)),
            abs((first ushr 8 and 255) - (second ushr 8 and 255)),
            abs((first and 255) - (second and 255)),
        )
    }
}
