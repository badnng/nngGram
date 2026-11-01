package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class ChatListHeaderGlassRenderTest {
    @Test
    fun topFolderSelectionMovesAsAShortMarkAboveSourceResponsiveGlassTabs() {
        for (density in listOf(1f, 1.25f)) {
            var background by mutableStateOf(Color(0xff30b050))
            var snapshot by mutableStateOf(ChatSnapshot(enabled = true,
                folders = listOf(ChatFolderInfo(1, "工作分类"), ChatFolderInfo(2, "朋友"))))
            val preferences = ChatFolderPreferences(placement = FolderTabPlacement.TOP, appearance = FolderTabAppearance.TEXT_ONLY)
            var primary = 0
            ComposePixelScene((380 * density).roundToInt(), (140 * density).roundToInt()) {
                val colors = remember { lightColorScheme() }
                primary = colors.primary.toArgb()
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(colors = colors) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(background))
                                ChatFolderTopTabs(snapshot, { snapshot = snapshot.copy(activeList = it) }, preferences,
                                    modifier = Modifier.padding(start = 24.dp, top = 30.dp).width(320.dp))
                            }
                        }
                    }
                }
            }.use { scene ->
                val initial = scene.settledFrame()
                val tabs = scene.semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab }.sortedBy { it.left }
                assertTrue(tabs.size == 3)
                val markerY = (tabs.first().bottom - density).roundToInt()
                fun markerCenter(frame: ComposePixelFrame): Float {
                    val pixels = (tabs.first().left.toInt()..tabs.last().right.toInt()).filter { x -> distance(frame[x, markerY], primary) <= 12 }
                    assertTrue(pixels.isNotEmpty(), "The selection must paint an actual short primary mark.")
                    assertTrue(pixels.size < (tabs.last().right - tabs.first().left) / 2,
                        "The selection mark must stay local to a tab rather than forming a divider across the header.")
                    return pixels.average().toFloat()
                }
                val start = markerCenter(initial)
                val target = tabs.last()
                scene.moveTo(target.center.x, target.center.y)
                scene.pressAt(target.center.x, target.center.y)
                scene.releaseAt(target.center.x, target.center.y)
                scene.moveTo(370f * density, 130f * density)
                val centers = List(7) { markerCenter(scene.settledFrame(1)) }
                val final = scene.settledFrame()
                val end = markerCenter(final)
                assertTrue(end > start + 100 * density, "The actual selected folder must change after its click.")
                assertTrue(centers.filter { it > start + density && it < end - density }
                    .map { (it / density).roundToInt() }.distinct().size >= 2,
                    "The short selection mark must travel through multiple intermediate positions: $centers")
                assertTrue(centers.zipWithNext().all { (before, after) -> after >= before - density },
                    "The real marker must travel continuously toward the target tab.")
                background = Color(0xffc040d0)
                val recolored = scene.settledFrame()
                tabs.forEach { tab ->
                    val x = (tab.left + 4 * density).roundToInt()
                    val y = tab.center.y.roundToInt()
                    assertTrue(distance(final[x, y], recolored[x, y]) > 10,
                        "Each real folder tab must resample the changed source below its own glass body.")
                    assertTrue(distance(recolored[x, y], background.toArgb()) > 10,
                        "The folder controls must mix the sampled source with the glass tint.")
                }
            }
        }
    }

    @Test
    fun searchAndFolderControlsBlurTheirOwnSourceWhileKeepingSharpTextAndExteriorShadows() {
        for (density in listOf(1f, 1.25f)) for (dark in listOf(false, true)) {
            var source by mutableStateOf(Color.Green)
            ComposePixelScene((360 * density).roundToInt(), (260 * density).roundToInt()) {
                val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(colors = colors) {
                        val backdrop = assertNotNull(rememberChatBackdrop())
                        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                    val stripe = 2.dp.toPx()
                                    repeat((size.width / stripe).toInt() + 1) { index ->
                                        drawRect(if (index % 2 == 0) source else Color.White,
                                            topLeft = Offset(index * stripe, 0f), size = Size(stripe, size.height))
                                    }
                                }
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    ChatListSearch(TextFieldValue(), {}, Modifier.width(280.dp))
                                    ChatListTab("全部", true) {}
                                    ChatListTab("工作", false) {}
                                }
                            }
                        }
                    }
                }
            }.use { scene ->
                fun verify(frame: ComposePixelFrame) {
                    val search = assertNotNull(scene.semanticsBounds {
                        "搜索当前聊天列表" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.singleOrNull())
                    val tabs = scene.semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab }
                    assertTrue(tabs.size == 2)
                    (tabs + search).forEach { control ->
                        val body = ((control.left + 4 * density).roundToInt()..(control.left + 11 * density).roundToInt())
                            .map { x -> frame[x, control.center.y.roundToInt()] }
                        assertTrue(body.maxOf { luminance(it) } - body.minOf { luminance(it) } < 14,
                            "The search and folder controls must blur the fine source stripes inside their own boundaries.")
                        val near = frame[control.center.x.roundToInt(), (control.bottom + 3 * density).roundToInt()]
                        val far = frame[control.center.x.roundToInt(), (control.bottom + 11 * density).roundToInt()]
                        val unshadowed = frame[control.center.x.roundToInt(), (control.bottom + 15 * density).roundToInt()]
                        assertTrue(distance(near, unshadowed) >= 3,
                            "The actual search/tab control must cast a visible soft shadow below its body: $density/$dark/$control")
                        assertTrue(distance(far, unshadowed) <= 2,
                            "The downward shadow must fade inside the reserved exterior space.")
                    }
                    val text = assertNotNull(scene.unmergedSemanticsBounds {
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "全部" }
                    }.singleOrNull())
                    val textPixels = (text.top.roundToInt() until text.bottom.roundToInt()).flatMap { y ->
                        (text.left.roundToInt() until text.right.roundToInt()).map { x -> luminance(frame[x, y]) }
                    }
                    assertTrue(textPixels.maxOrNull()!! - textPixels.minOrNull()!! > 60,
                        "Only the background is blurred; the real control text must retain sharp contrast.")
                }
                val before = scene.settledFrame()
                verify(before)
                source = Color.Magenta
                val after = scene.settledFrame()
                verify(after)
                scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Role) == Role.Tab ||
                        "搜索当前聊天列表" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.forEach { control ->
                    val x = (control.left + 6 * density).roundToInt()
                    val y = control.center.y.roundToInt()
                    assertTrue(distance(before[x, y], after[x, y]) > 10,
                        "Every search/folder body must react to a changed source rather than keeping a flat static fill.")
                }
            }
        }
    }

    @Test
    fun theActualScrolledHeaderShowsItsSourceAndKeepsSearchIconReadable() {
        for (density in listOf(1f, 1.25f)) {
            val path = Files.createTempFile("header-blur-source-", ".png")
            try {
                val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 48) for (x in 0 until 48)
                    image.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt())
                check(ImageIO.write(image, "png", path.toFile()))
                val photo = MediaFile(700, downloaded = true, localPath = path.toString())
                val chats = (1L..30L).map { ChatItem(it, "滚动源标题-$it", order = 100 - it, avatar = photo) }
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null), items = chats,
                    knownChats = chats, listEnd = true, mediaFiles = mapOf(photo.id to photo))
                ComposePixelScene((354 * density).roundToInt(), (500 * density).roundToInt()) {
                    val colors = remember { lightColorScheme() }
                    CompositionLocalProvider(LocalDensity provides Density(density),
                        LocalDesktopChatLayout provides DesktopChatLayout(compact = true, showRail = true)) {
                        MiuixTheme(colors = colors) {
                            ChatListPane(snapshot, {}, {}, {}, {}, features = ChatFeatureActions(), modifier = Modifier.fillMaxSize())
                        }
                    }
                }.use { scene ->
                    var frame = scene.settledFrame()
                    val firstRow = assertNotNull(scene.semanticsBounds {
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == chats.first().title }
                    }.singleOrNull())
                    val decodedDeadline = System.nanoTime() + 3_000_000_000L
                    var sourceDecoded = false
                    while (!sourceDecoded && System.nanoTime() < decodedDeadline) {
                        val avatar = scene.unmergedSemanticsBounds {
                            "聊天图片" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        }.firstOrNull { it.center.y >= firstRow.top && it.center.y <= firstRow.bottom }
                        // Probe checker-cell centers in source coordinates. The former
                        // fixed window x values landed on a cell edge after 40 -> 46dp.
                        if (avatar != null) {
                            val sourceY = (avatar.top + avatar.height * 18.5f / 48f).roundToInt()
                            val black = frame[(avatar.left + avatar.width * 18.5f / 48f).roundToInt(), sourceY]
                            val white = frame[(avatar.left + avatar.width * 22.5f / 48f).roundToInt(), sourceY]
                            sourceDecoded = listOf(0, 8, 16).all { (black ushr it and 255) < 20 && (white ushr it and 255) > 235 }
                        }
                        if (!sourceDecoded) {
                            Thread.sleep(5)
                            frame = scene.settledFrame(2)
                        }
                    }
                    assertTrue(sourceDecoded, "The real checkerboard avatar must finish decoding before the blur measurement.")
                    val selectedTab = assertNotNull(scene.semanticsBounds {
                        it.getOrNull(SemanticsProperties.Role) == Role.Tab && it.getOrNull(SemanticsProperties.Selected) == true
                    }.singleOrNull())
                    fun bodyLuminance(pixels: ComposePixelFrame): Double {
                        val samples = mutableListOf<Int>()
                        for (y in ((selectedTab.center.y - 3 * density).roundToInt())..((selectedTab.center.y + 3 * density).roundToInt()))
                            for (x in ((selectedTab.left + 5 * density).roundToInt())..((selectedTab.left + 9 * density).roundToInt()))
                                samples += luminance(pixels[x, y])
                        return samples.average()
                    }
                    val emptySourceLuminance = bodyLuminance(frame)
                    var aligned = false
                    repeat(24) {
                        if (!aligned) {
                            val rows = scene.semanticsBounds { config ->
                                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("滚动源标题-") }
                            }
                            aligned = rows.any { abs(it.center.y - selectedTab.center.y) <= 7 * density }
                            if (!aligned) {
                                scene.scrollAt(180f * density, 350f * density, deltaY = 0.25f)
                                frame = scene.settledFrame(6)
                            }
                        }
                    }
                    assertTrue(aligned, "Real wheel input must move a source avatar behind the folder controls.")
                    frame = scene.settledFrame()
                    val avatarSourceLuminance = bodyLuminance(frame)
                    // The fixed glass tint lifts the blurred avatar's edge toward white. Compare
                    // the same real probe before/after scrolling instead of requiring an old tint.
                    assertTrue(emptySourceLuminance - avatarSourceLuminance > 4,
                        "The actual avatar must visibly darken the mixed glass tab at density=$density: " +
                            "empty=$emptySourceLuminance avatar=$avatarSourceLuminance " +
                            "darkening=${emptySourceLuminance - avatarSourceLuminance} (required > 4).")
                    val search = assertNotNull(scene.semanticsBounds {
                        "搜索当前聊天列表" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.singleOrNull())
                    val iconPixels = mutableListOf<Int>()
                    for (y in ((search.center.y - 8 * density).roundToInt())..((search.center.y + 8 * density).roundToInt()))
                        for (x in ((search.left + 16 * density).roundToInt())..((search.left + 32 * density).roundToInt()))
                            iconPixels += luminance(frame[x, y])
                    assertTrue(iconPixels.maxOrNull()!! - iconPixels.minOrNull()!! >= 100,
                        "The actual search icon must retain a clear foreground contrast over the scrolled source.")
                }
            } finally { Files.deleteIfExists(path) }
        }
    }

    private fun distance(first: Int, second: Int) = listOf(0, 8, 16).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
    private fun luminance(pixel: Int) = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
}
