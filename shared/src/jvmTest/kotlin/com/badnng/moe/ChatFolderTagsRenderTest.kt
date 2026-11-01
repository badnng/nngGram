package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ChatFolderTagsRenderTest {
    @Test fun actualTagsAloneUseTheOfficialSeparateHeightAndDisablingReturnsTheOriginal62And66Pitch() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
            val f = Fixture(dark = dark, density = density)
            f.scene().use { scene ->
                scene.settledFrame()
                val before = scene.row("Synthetic A")
                val next = scene.row("Synthetic B")
                // Compose rounds each measured dp dimension, including the separate real gap,
                // to physical pixels. At 1.25x this is 78px + 5px, rather than a 82.5px pitch.
                val normalHeight = with(Density(density)) { 62.dp.roundToPx().toFloat() }
                val taggedHeight = with(Density(density)) { 72.dp.roundToPx().toFloat() }
                val gap = with(Density(density)) { 4.dp.roundToPx().toFloat() }
                val tagOffset = with(Density(density)) { 52.dp.roundToPx().toFloat() }
                assertEquals(normalHeight, before.height)
                assertEquals(gap, next.top - before.bottom)
                assertEquals(normalHeight + gap, next.top - before.top)
                val header = scene.description("管理文件夹")
                f.state = f.state.copy(folderTagsEnabled = true)
                scene.settledFrame()
                assertEquals(taggedHeight, scene.row("Synthetic A").height)
                assertEquals(gap, scene.row("Synthetic B").top - scene.row("Synthetic A").bottom)
                assertEquals(taggedHeight + gap, scene.row("Synthetic B").top - scene.row("Synthetic A").top)
                assertEquals(header, scene.description("管理文件夹"))
                assertEquals(13f, scene.layout("Synthetic A").layoutInput.style.fontSize.value)
                assertEquals(13f, scene.layout("Native preview A").layoutInput.style.fontSize.value)
                val tag = scene.tag("文件夹标签 WORK")
                assertEquals(scene.row("Synthetic A").top + tagOffset, tag.top)
                assertTrue(tag.bottom <= scene.row("Synthetic A").bottom)
                scene.settledFrame().save("tags-${if (dark) "dark" else "light"}-$density")
                f.state = f.state.copy(folderTagsEnabled = false)
                scene.settledFrame()
                assertEquals(before, scene.row("Synthetic A"))
                assertEquals(next, scene.row("Synthetic B"))
                assertTrue(scene.tags().isEmpty())
            }
        }
    }

    @Test fun premiumChangesDoNotInventADisplayRuleWhileNativeFlagsAndMembershipRemainAuthoritative() {
        val f = Fixture(enabled = true)
        f.scene().use { scene ->
            scene.settledFrame()
            val row = scene.row("Synthetic A")
            val tag = scene.tag("文件夹标签 WORK")
            f.state = f.state.copy(account = f.state.account!!.copy(isPremium = false))
            scene.settledFrame()
            assertEquals(row, scene.row("Synthetic A"))
            assertEquals(tag, scene.tag("文件夹标签 WORK"))
            assertTrue(f.state.folderTagsEnabled)
            f.state = f.state.copy(folderTagsEnabled = false)
            scene.settledFrame()
            assertTrue(scene.tags().isEmpty())
            assertEquals(62f, scene.row("Synthetic A").height)
        }
    }

    @Test fun unknownMembershipPositionsAndTheCurrentlySelectedFolderNeverFabricateRenderedTags() {
        val f = Fixture(enabled = true)
        f.state = f.state.copy(items = f.state.items.map { it.copy(chatLists = null,
            positions = mapOf(ChatListRef(2) to 200L)) })
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.tags().isEmpty())
            assertEquals(62f, scene.row("Synthetic A").height)
            f.state = f.state.copy(items = f.state.items.map { it.copy(chatLists = setOf(ChatListRef(2)), positions = emptyMap()) })
            scene.settledFrame()
            assertNotNull(scene.tag("文件夹标签 WORK"))
            f.state = f.state.copy(activeList = ChatListRef(2))
            scene.settledFrame(16, 40)
            assertTrue(scene.tags().isEmpty())
            assertEquals(62f, scene.row("Synthetic A").height)
            f.state = f.state.copy(activeList = ChatListRef(), folders = emptyList())
            scene.settledFrame(16, 40)
            assertTrue(scene.tags().isEmpty())
        }
    }

    @Test fun oneMeasuredTagLineUsesPlusNAndARealColorUpdateRepaintsWithoutChangingGeometry() {
        val f = Fixture(width = 280, enabled = true)
        f.state = f.state.copy(folders = listOf(ChatFolderInfo(2, "LongfolderAA", colorId = 2),
            ChatFolderInfo(3, "LongfolderBB", colorId = 3), ChatFolderInfo(4, "LongfolderCC", colorId = 4),
            ChatFolderInfo(5, "LongfolderDD", colorId = 5)), items = f.state.items.take(1).map {
                it.copy(chatLists = (2..5).map(::ChatListRef).toSet())
            })
        f.scene().use { scene ->
            val before = scene.settledFrame()
            val tags = scene.tags()
            assertTrue(tags.size in 1..4)
            val more = scene.tagDescriptions().filter { it.startsWith("文件夹标签 +") }
            assertEquals(1, more.size)
            assertTrue(tags.map { it.top }.distinct().size == 1, "Tags are one measured line, never wrapped under the row.")
            assertTrue(tags.all { it.left >= 0 && it.right <= f.width && it.bottom <= scene.row("Synthetic A").bottom })
            val first = scene.tag("文件夹标签 LONGFOLDERAA")
            val row = scene.row("Synthetic A")
            f.state = f.state.copy(folders = f.state.folders.map { if (it.id == 2) it.copy(colorId = 5) else it })
            val after = scene.settledFrame()
            assertEquals(first, scene.tag("文件夹标签 LONGFOLDERAA"))
            assertEquals(row, scene.row("Synthetic A"))
            var changed = 0
            for (y in first.top.roundToInt() until first.bottom.roundToInt()) for (x in first.left.roundToInt() until first.right.roundToInt())
                if (before[x, y] != after[x, y]) changed++
            assertTrue(changed > 10, "A native color update must repaint actual tag pixels.")
        }
    }

    private class Fixture(val width: Int = 400, val density: Float = 1f, val dark: Boolean = false, enabled: Boolean = false) {
        var state by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(7, 1, null),
            folders = listOf(ChatFolderInfo(2, "Work", colorId = 2)), folderTagsEnabled = enabled,
            account = AccountProfile(43, "Synthetic", isPremium = true), listEnd = true,
            items = listOf(ChatItem(10, "Synthetic A", order = 200,
                lastMessage = ChatMessage(1, 10, text = "Native preview A"), chatLists = setOf(ChatListRef(2))),
                ChatItem(11, "Synthetic B", order = 100,
                    lastMessage = ChatMessage(2, 11, text = "Native preview B"), chatLists = setOf(ChatListRef(2))))))
        fun scene() = ComposePixelScene((width * density).roundToInt(), (650 * density).roundToInt()) {
            val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, 650.dp)) {
                MiuixTheme(controller = theme) {
                    ChatListPane(state, {}, {}, {}, {}, Modifier.fillMaxSize(), features = ChatFeatureActions())
                }
            }
        }
    }
    private fun ComposePixelScene.row(title: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Selected) != null && it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == title }
    }.singleOrNull(), title)
    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), label)
    private fun ComposePixelScene.tags() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith("文件夹标签 ") }
    }
    private fun ComposePixelScene.tag(label: String) = assertNotNull(unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.firstOrNull(), label)
    private fun ComposePixelScene.tagDescriptions(): List<String> {
        val labels = mutableListOf<String>()
        unmergedSemanticsBounds { labels += it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().filter { value -> value.startsWith("文件夹标签 ") }; false }
        return labels
    }
    private fun ComposePixelScene.layout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds {
            if (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label })
                it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            false
        }
        return assertNotNull(layouts.singleOrNull(), label)
    }
    private fun ComposePixelFrame.save(name: String) {
        val target = File("build/settings-folders-followup-validation/frames/$name.png")
        target.parentFile.mkdirs()
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) output.setRGB(x, y, this[x, y])
        assertTrue(ImageIO.write(output, "png", target))
    }
}
