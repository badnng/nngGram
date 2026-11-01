package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual list measurement and Popup source capture, using synthetic chats and no account requests. */
class ChatListDensityRenderTest {
    @Test
    fun ordinaryCardsKeepOfficialInternalMetricsWithVisibleSpacingAcrossWindowSizesAndThemes() {
        for (width in listOf(280, 420)) for (density in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                val fixture = Fixture(width, density, mode)
                fixture.scene().use { scene ->
                    val initialFrame = scene.settledFrame()
                    val row = scene.rowBounds(fixture.chats[0].title)
                    val second = scene.rowBounds(fixture.chats[1].title)
                    val rowPixels = (62f * density).roundToInt().toFloat()
                    val gapPixels = (4f * density).roundToInt().toFloat()
                    close(rowPixels, row.height, "The complete source row must measure 62dp, including its children.")
                    close(gapPixels, second.top - row.bottom, "Independent Miuix cards retain a clear four-dp gap.")
                    val gapY = ((row.bottom + second.top) / 2f).roundToInt()
                    val background = initialFrame[(row.left / 2f).roundToInt(), gapY]
                    val gapStartX = (row.left + row.width * .3f).roundToInt()
                    val gapEndX = (row.left + row.width * .7f).roundToInt()
                    assertTrue((gapStartX..gapEndX).all { x ->
                        colorDistance(background, initialFrame[x, gapY]) <= 2
                    }, "The measured gap must expose the actual list background between the painted cards.")
                    val title = scene.textBounds(fixture.chats[0].title)
                    val preview = scene.textBounds(fixture.chats[0].lastMessage!!.text)
                    close(68f * density, title.left - row.left, "Official nameLeft is 68dp.")
                    close((10f * density).roundToInt().toFloat(), title.top - row.top, "Official nameTop is 10dp.")
                    close(68f * density, preview.left - row.left, "Official textLeft is 68dp.")
                    close((34f * density).roundToInt().toFloat(), preview.top - row.top, "Official textTop is 34dp.")
                    val photoCenterY = (row.top + 31f * density).roundToInt()
                    val rowFill = initialFrame[(row.left + 62f * density).roundToInt(), photoCenterY]
                    val avatarDiameter = ((row.left + 4f * density).roundToInt() until
                        (row.left + 62f * density).roundToInt()).count { x ->
                        colorDistance(initialFrame[x, photoCenterY], rowFill) > 4
                    }
                    assertTrue(abs(avatarDiameter - 46f * density) <= 2f,
                        "The actual circular avatar paints 46dp inside its 10/8 padding, diameter=$avatarDiameter.")
                    val titleLayout = scene.textLayout(fixture.chats[0].title)
                    val previewLayout = scene.textLayout(fixture.chats[0].lastMessage!!.text)
                    assertEquals(13.sp, titleLayout.layoutInput.style.fontSize)
                    assertEquals(FontWeight.SemiBold, titleLayout.layoutInput.style.fontWeight)
                    assertEquals(13.sp, previewLayout.layoutInput.style.fontSize)
                    assertEquals(FontWeight.Normal, previewLayout.layoutInput.style.fontWeight)
                    assertEquals(TextUnit.Unspecified, previewLayout.layoutInput.style.lineHeight,
                        "Content uses the official font's natural line metrics.")
                    val badge = assertNotNull(scene.descriptionBounds("1000 条未读").singleOrNull())
                    assertTrue(badge.top >= row.top && badge.bottom <= row.bottom && badge.right <= row.right,
                        "The complete capped badge remains inside the 62dp row.")
                    val completeVisible = fixture.chats.mapNotNull { scene.rowBoundsOrNull(it.title) }.count {
                        abs(it.height - rowPixels) <= 1.5f && it.top >= row.top - 1 && it.bottom <= fixture.heightPixels + 1
                    }
                    val expectedVisible = floor((fixture.heightPixels - row.top + gapPixels) /
                        (rowPixels + gapPixels)).toInt()
                    assertEquals(expectedVisible, completeVisible,
                        "Visible capacity includes each complete 62dp card and the four-dp spaces between them.")
                    val header = scene.headerBounds()
                    scene.scrollAt(width * density / 2, 600f * density, deltaY = 12f)
                    scene.settledFrame()
                    assertTrue(fixture.state.forList(ChatListRef()).listState.firstVisibleItemIndex > 0)
                    assertEquals(header, scene.headerBounds(), "Scrolling the denser list must leave the mounted header coordinates fixed.")
                    writeFrame(scene.settledFrame(), "rows-$width-$density-$mode")
                }
            }
        }
    }

    @Test
    fun rightClickKeepsTheOriginalSixtyTwoDpCardAtItsMeasuredPosition() {
        for (width in listOf(280, 420)) for (density in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
                val fixture = Fixture(width, density, mode)
                fixture.scene().use { scene ->
                    scene.settledFrame()
                    val source = scene.rowBounds(fixture.chats[1].title)
                    val header = scene.headerBounds()
                    scene.secondaryClickAt(source.center.x, source.center.y)
                    scene.settledFrame()
                    val lifted = assertNotNull(scene.descriptionBounds("会话卡片").singleOrNull())
                    close(source.left, lifted.left, "The original card keeps its horizontal position.")
                    close(source.top, lifted.top, "Opening the menu must leave the original card in place.")
                    close(source.width, lifted.width, "The actual source graphics layer retains its measured width.")
                    close(source.height, lifted.height, "The actual source graphics layer retains its 62dp height.")
                    close((62f * density).roundToInt().toFloat(), lifted.height, "Popup composition must not restore the former taller row.")
                    assertEquals(header, scene.headerBounds(), "Opening the row menu must not relayout the header.")
                    writeFrame(scene.settledFrame(), "lift-$width-$density-$mode")
                }
            }
        }
    }

    private class Fixture(val width: Int, val density: Float, val mode: ColorSchemeMode) {
        val heightPixels = (700f * density).roundToInt()
        val chats = (1L..30L).map { id -> ChatItem(id, "Synthetic chat $id", order = 100 - id,
            unreadCount = if (id == 1L) 1000 else 0,
            lastMessage = ChatMessage(id, id, date = 1_700_000_000, text = "Synthetic preview $id")) }
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), items = chats,
            knownChats = chats, selected = chats.first(), folders = listOf(ChatFolderInfo(1, "Work")), listEnd = true)
        val state = ChatListPaneState(ChatListRef())

        fun scene() = ComposePixelScene((width * density).roundToInt(), heightPixels) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = width < 320, showRail = width >= 320)) {
                MiuixTheme(controller = theme) {
                    ChatListPane(snapshot, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                        features = ChatFeatureActions(), state = state)
                }
            }
        }
    }

    private fun ComposePixelScene.rowBoundsOrNull(title: String): Rect? = semanticsBounds {
        it.getOrNull(SemanticsProperties.Selected) != null &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == title }
    }.singleOrNull()

    private fun ComposePixelScene.rowBounds(title: String): Rect = assertNotNull(rowBoundsOrNull(title))
    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = semanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.headerBounds(): List<Rect> = semanticsBounds {
        "搜索当前聊天列表" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() ||
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "全部" || text.text == "Work" }
    }

    private fun ComposePixelScene.textBounds(text: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull())

    private fun ComposePixelScene.textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds {
            val matches = it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
            if (matches) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            matches
        }
        return layouts.single()
    }

    private fun close(expected: Float, actual: Float, reason: String) =
        assertTrue(abs(expected - actual) <= 1.5f, "$reason expected=$expected, actual=$actual")

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }

    private fun writeFrame(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "behavior-alignment-chat-list")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
}
