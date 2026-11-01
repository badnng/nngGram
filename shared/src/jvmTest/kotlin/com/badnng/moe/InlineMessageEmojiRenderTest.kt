package com.badnng.moe

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic native-shaped resources exercise the actual paragraph and bounded PNG/TGS renderer. */
@OptIn(ExperimentalComposeUiApi::class)
class InlineMessageEmojiRenderTest {
    @Test
    fun loadingVisibilityAndFocusKeepLayoutStableWhileOnlyActiveVisibleEmojiAnimate() {
        val thumbnail = Files.createTempFile("nnggram-inline-emoji-static-", ".png")
        val animation = Files.createTempFile("nnggram-inline-emoji-animation-", ".tgs")
        try {
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xff24bd76.toInt())
            check(ImageIO.write(bitmap, "png", thumbnail.toFile()))
            GZIPOutputStream(Files.newOutputStream(animation)).use { it.write(MOVING_CIRCLE.toByteArray()) }
            val source = RichMessageText("前😀文字后续换行", listOf(MessageEntity(1, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42)))
            val key = ReactionKey(customEmojiId = 42)
            val snapshot = mutableStateOf(ChatSnapshot(token = ChatToken(1, 1, 10)))
            val visible = mutableStateOf(false)
            val active = mutableStateOf(false)
            val focused = mutableStateOf(true)
            val requested = mutableListOf<ReactionKey>()
            var layout: TextLayoutResult? = null
            ComposePixelScene(220, 160) {
                val theme = remember { ThemeController(ColorSchemeMode.Light) }
                val window = LocalWindowInfo.current
                val testWindow = remember(window) { object : WindowInfo by window {
                    override val isWindowFocused get() = focused.value
                } }
                CompositionLocalProvider(LocalWindowInfo provides testWindow) {
                    MiuixTheme(controller = theme) {
                        InlineMessageEmojiProvider(snapshot.value, ChatFeatureActions(
                            loadReactionVisual = { _, value -> requested += value }), visible.value, active.value) {
                            Text(AnnotatedString(source.text).withInlineMessageEmoji(source), color = Color.Black,
                                fontSize = 32.sp, lineHeight = 40.sp, modifier = Modifier.width(170.dp),
                                inlineContent = rememberInlineMessageEmojiContent(source, fontSize = 32.sp, foreground = Color.Black),
                                onTextLayout = { layout = it })
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val initial = assertNotNull(layout)
                val cell = assertNotNull(initial.placeholderRects.single())
                assertTrue(requested.isEmpty(), "An offscreen native emoji must not initiate an artwork request.")
                visible.value = true
                scene.settledFrame()
                assertEquals(listOf(key), requested)
                snapshot.value = snapshot.value.copy(reactionVisuals = mapOf(key to ReactionVisual(key,
                    MessageMedia(MediaFile(70, localPath = thumbnail.toString(), downloaded = true), format = MediaFormat.PHOTO),
                    MessageMedia(MediaFile(71, localPath = animation.toString(), downloaded = true), format = MediaFormat.TGS))))
                scene.awaitPixels(cell, ::green)
                fun assertStableLayout() {
                    assertEquals(initial.placeholderRects, assertNotNull(layout).placeholderRects,
                        "Loading, visibility and focus must retain the exact native emoji placeholder geometry.")
                    assertEquals(initial.lineCount, assertNotNull(layout).lineCount)
                    assertEquals(initial.size, assertNotNull(layout).size)
                }
                assertStableLayout()
                active.value = true
                scene.awaitPixels(cell, ::red)
                val first = scene.settledFrame(1)
                Thread.sleep(180)
                assertTrue(changes(first, scene.settledFrame(3), cell) > 8,
                    "A visible, active paragraph must advance the supplied native TGS without a hover action.")
                focused.value = false
                scene.awaitPixels(cell, ::green)
                assertStableLayout()
                val paused = scene.settledFrame()
                Thread.sleep(140)
                assertEquals(0, changes(paused, scene.settledFrame(3), cell), "Unfocused paragraphs must stop animating.")
                focused.value = true
                scene.awaitPixels(cell, ::red)
                visible.value = false
                scene.awaitPixels(cell, ::green)
                assertStableLayout()
                val offscreen = scene.settledFrame()
                Thread.sleep(140)
                assertEquals(0, changes(offscreen, scene.settledFrame(3), cell), "Offscreen paragraphs must stop animating.")
                visible.value = true
                active.value = false
                scene.awaitPixels(cell, ::green)
                assertStableLayout()
            }
        } finally {
            Files.deleteIfExists(thumbnail)
            Files.deleteIfExists(animation)
        }
    }

    @Test
    fun hiddenSpoilersKeepRevealGeometryWithoutRequestingOrDrawingEmojiAndFallbackNeedsNoProvider() {
        val customs = (0 until 4).map { MessageEntity(it * 2, 2, MessageEntityKind.CUSTOM_EMOJI, id = 42) }
        val hidden = RichMessageText("😀😀😀😀", customs + MessageEntity(0, 8, MessageEntityKind.SPOILER))
        val requested = mutableListOf<ReactionKey>()
        val reveal = mutableStateOf(false)
        var layout: TextLayoutResult? = null
        var inlineCount = -1
        ComposePixelScene(180, 200) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                InlineMessageEmojiProvider(ChatSnapshot(), ChatFeatureActions(loadReactionVisual = { _, value -> requested += value }), true) {
                    val inline = rememberInlineMessageEmojiContent(hidden, reveal.value, fontSize = 32.sp, foreground = Color.Black)
                    inlineCount = inline.size
                    Text(AnnotatedString(hidden.text).withInlineMessageEmoji(hidden, reveal.value), fontSize = 32.sp,
                        modifier = Modifier.width(90.dp), inlineContent = inline, onTextLayout = { layout = it })
                }
            }
        }.use { scene ->
            val frame = scene.settledFrame()
            assertTrue(requested.isEmpty())
            assertEquals(4, inlineCount, "Spoilers retain layout placeholders but never mount media drawing children.")
            val before = assertNotNull(layout)
            before.placeholderRects.filterNotNull().forEach { cell ->
                for (y in cell.top.roundToInt() until cell.bottom.roundToInt())
                    for (x in cell.left.roundToInt() until cell.right.roundToInt())
                        assertEquals(0, frame[x, y] ushr 24, "A hidden custom emoji must not draw even its Unicode fallback.")
            }
            reveal.value = true
            scene.settledFrame()
            val after = assertNotNull(layout)
            assertEquals(before.placeholderRects, after.placeholderRects)
            assertEquals(before.lineCount, after.lineCount)
            assertEquals(before.size, after.size)
            assertEquals(listOf(ReactionKey(customEmojiId = 42)), requested,
                "Only an explicit reveal may make the native emoji a resource candidate.")
        }
        ComposePixelScene(180, 200) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                val source = hidden.copy(entities = customs)
                val inline = rememberInlineMessageEmojiContent(source, fontSize = 32.sp, foreground = Color.Black)
                inlineCount = inline.size
                Text(AnnotatedString(source.text).withInlineMessageEmoji(source), fontSize = 32.sp, inlineContent = inline)
            }
        }.use { scene ->
            scene.settledFrame()
            assertEquals(4, inlineCount, "An offline text preview keeps native emoji geometry and draws its Unicode fallback.")
        }
    }

    private fun ComposePixelScene.awaitPixels(bounds: Rect, predicate: (Int) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            var pixels = 0
            for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
                for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (predicate(frame[x, y])) pixels++
            if (pixels > 20) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The synthetic inline native emoji did not reach its expected rendered frame.")
    }

    private fun green(pixel: Int) = (pixel ushr 8 and 255) > 130 && (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170
    private fun red(pixel: Int) = (pixel ushr 16 and 255) > 170 && (pixel ushr 8 and 255) < 100 && (pixel and 255) < 100
    private fun changes(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var count = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) if (first[x, y] != second[x, y]) count++
        return count
    }

    private companion object {
        const val MOVING_CIRCLE = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":32,"h":32,"nm":"synthetic-inline-native-emoji","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"circle","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[7,16,0],"e":[25,16,0],"i":{"x":[0.67],"y":[1]},"o":{"x":[0.33],"y":[0]}},{"t":30,"s":[25,16,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[10,10]},"d":1},{"ty":"fl","c":{"a":0,"k":[0.9,0.1,0.1,1]},"o":{"a":0,"k":100},"r":1}],"ip":0,"op":30,"st":0,"bm":0}]}"""
    }
}
