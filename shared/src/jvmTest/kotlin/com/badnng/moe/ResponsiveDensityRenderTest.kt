package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.*
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Same physical scene and identities throughout; only the provided density changes. */
class ResponsiveDensityRenderTest {
    @Test
    fun searchFiltersReflowAtTheActualDpBreakpointWithoutReplacingTheRichSearchDraft() {
        val query = ChatSearchQuery(text = "native draft", chatId = 10)
        val session = ConversationSearchSession(query, synchronized = true)
        session.text.value = TextFieldValue(query.text, TextRange(2, 7))
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic search"), search = ChatSearchState(query = query))
        var submissions = 0
        val density = mutableStateOf(1.25f)
        scene(600, 700, density) {
            ConversationSearchPane(snapshot, ChatFeatureActions(search = { _, _, _ -> submissions++ }),
                onSelectChat = {}, onJump = {}, onClose = {}, modifier = Modifier.fillMaxSize(), session = session)
        }.use { scene ->
            scene.settledFrame()
            assertFalse(scene.hasText("筛选消息"), "600px / 1.25 is 480dp and keeps one filter row.")
            val before = scene.searchEditor()
            assertEquals(query.text, before.first)
            assertEquals(TextRange(2, 7), before.second)
            SwingUtilities.invokeAndWait { density.value = 2.1875f }
            scene.settledFrame()
            assertTrue(scene.hasText("筛选消息"), "The same 600px is now 274dp and must stack its filter actions.")
            assertEquals(before, scene.searchEditor())
            assertEquals(TextFieldValue(query.text, TextRange(2, 7)), session.text.value)
            assertEquals(0, submissions, "A density change cannot become a new native search request.")
            SwingUtilities.invokeAndWait { density.value = 1.25f }
            scene.settledFrame()
            assertFalse(scene.hasText("筛选消息"))
            assertEquals(before, scene.searchEditor())
        }
    }

    @Test
    fun videoPosterRecomputesItsClampedAspectHeightWithinTheSamePhysicalWidth() {
        val media = MessageMedia(MediaFile(1), width = 800, height = 400, format = MediaFormat.VIDEO,
            fileName = "synthetic.mp4", durationSeconds = 10)
        val density = mutableStateOf(1f)
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 10))
        var downloads = 0
        scene(400, 650, density) {
            AttachmentMedia(media, snapshot, ChatFeatureActions(downloadFile = { _, _ -> downloads++ }),
                visible = false, canSave = false, onSave = { _, _ -> }, onPlay = {}, messageId = 100,
                playing = false, active = false, onStopPlaying = {}, playbackSession = null, onExpand = {},
                modifier = Modifier.semantics { contentDescription = "实际视频附件" })
        }.use { scene ->
            scene.settledFrame()
            assertClose(400f, scene.description("实际视频附件").width)
            assertClose(200f, scene.description("实际视频附件").height)
            SwingUtilities.invokeAndWait { density.value = 2f }
            scene.settledFrame()
            assertClose(400f, scene.description("实际视频附件").width)
            assertClose(240f, scene.description("实际视频附件").height,
                "400px / 2 density / 2 aspect is 100dp, so the real 120dp minimum yields 240px.")
            assertEquals(0, downloads)
            SwingUtilities.invokeAndWait { density.value = 1f }
            scene.settledFrame()
            assertClose(200f, scene.description("实际视频附件").height)
        }
    }

    @Test
    fun theNativeEmojiOutlineKeepsItsAspectWhenOnlyTheDensityOfItsFixedCanvasChanges() {
        val density = mutableStateOf(1f)
        val file = MediaFile(1, downloading = true)
        val media = MessageMedia(file, width = 200, height = 100, format = MediaFormat.TGS,
            animatedEmoji = AnimatedEmojiMedia(200, 100, 0, isRegularEmoji = false))
        val message = ChatMessage(100, 10, text = "🙂", kind = MessageKind.ANIMATED_EMOJI, media = media)
        val snapshot = ChatSnapshot(token = ChatToken(1, 1, 10), stickerOutlines = mapOf(1 to
            StickerOutline("M0 0 L200 0 L200 100 L0 100 Z", 200, 100)))
        scene(112, 112, density) {
            AnimatedEmojiMessageContent(message, snapshot, ChatFeatureActions(), visible = false, active = false,
                modifier = Modifier.fillMaxSize())
        }.use { scene ->
            scene.settledFrame()
            val before = scene.description("贴纸加载轮廓")
            assertClose(112f, before.width)
            assertClose(56f, before.height)
            SwingUtilities.invokeAndWait { density.value = 2f }
            val frame = scene.settledFrame()
            val after = scene.description("贴纸加载轮廓")
            assertClose(112f, after.width)
            assertClose(56f, after.height,
                "A 2:1 native outline cannot turn into a square because its old dp width survived remeasurement.")
            assertEquals(before, after)
            assertNotEquals(Color(0xffd1d2d3).toArgb(), frame[56, 28], "The native path must still paint its upper rectangular area.")
            assertEquals(Color(0xffd1d2d3).toArgb(), frame[56, 84], "The lower canvas half must remain transparent after density reflow.")
        }
    }

    @Test
    fun stickerColumnsReflowWithoutResettingTheSearchDraftOrTheVisibleGridAnchor() {
        val density = mutableStateOf(1f)
        val grid = LazyGridState()
        val items = (1L..100L).map { id -> StickerItem(id, 99, "p$id", MessageMedia(MediaFile(id.toInt()))) }
        val pack = StickerPack(99, "Synthetic native pack", items.size, items.first())
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = ChatItem(10, "Synthetic stickers"), stickerSets = listOf(pack),
            stickerSections = listOf(StickerSection(99, pack.title, items, loaded = true)))
        scene(345, 650, density) {
            StickerPickerPanel(snapshot, ChatFeatureActions(), interactive = true, onDismiss = {}, gridState = grid)
        }.use { scene ->
            scene.settledFrame()
            assertEquals(5, scene.firstStickerRow().size)
            scene.click(scene.description("筛选当前贴纸"))
            scene.keyStroke(java.awt.event.KeyEvent.VK_P, 'p')
            scene.settledFrame()
            assertEquals("p", scene.stickerQuery())
            val viewport = scene.description("贴纸网格")
            scene.scrollAt(viewport.center.x, viewport.center.y, deltaY = 8f)
            scene.settledFrame()
            val anchor = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                it.offset.y + it.size.height > grid.layoutInfo.viewportStartOffset
            }?.key
            assertTrue(grid.firstVisibleItemIndex > 0)
            val footerBottom = scene.description("贴纸包栏").bottom
            SwingUtilities.invokeAndWait { density.value = 1.25f }
            scene.settledFrame()
            assertEquals(4, scene.firstStickerRow().size)
            assertTrue(grid.firstVisibleItemIndex > 0, "Density must not replace the retained native grid session.")
            assertTrue(grid.layoutInfo.visibleItemsInfo.any { it.key == anchor }, "The prior visible anchor must remain in the measured grid window.")
            assertClose(footerBottom, scene.description("贴纸包栏").bottom)
            scene.scrollAt(viewport.center.x, viewport.center.y, deltaY = -80f)
            scene.settledFrame()
            assertEquals("p", scene.stickerQuery(), "The selector's private search draft must survive reflow.")
            assertEquals(4, scene.firstStickerRow().size)
        }
    }

    private fun scene(width: Int, height: Int, density: State<Float>, content: @Composable () -> Unit) =
        ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(density.value)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(Color(0xffd1d2d3))) { content() }
                }
            }
        }

    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), label)
    private fun ComposePixelScene.hasText(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { part -> part.text == text }
    }.any { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.searchEditor(): Pair<String, TextRange?> {
        var value: Pair<String, TextRange?>? = null
        semanticsBounds { config ->
            if ("搜索消息" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                config.getOrNull(SemanticsProperties.EditableText)?.let {
                    value = it.text to config.getOrNull(SemanticsProperties.TextSelectionRange)
                }
            false
        }
        return assertNotNull(value)
    }
    private fun ComposePixelScene.stickerQuery(): String {
        var text: String? = null
        semanticsBounds { config ->
            if ("筛选当前贴纸" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                text = config.getOrNull(SemanticsProperties.EditableText)?.text
            false
        }
        return assertNotNull(text)
    }
    private fun ComposePixelScene.firstStickerRow(): List<Rect> {
        val viewport = description("贴纸网格")
        val cells = semanticsBounds { config -> config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
            it.startsWith("发送贴纸 ")
        } }.filter { it.height > 0 && it.bottom > viewport.top && it.top < viewport.bottom }
        val firstTop = assertNotNull(cells.minOfOrNull { it.top.roundToInt() })
        return cells.filter { it.top.roundToInt() == firstTop }
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    private fun assertClose(expected: Float, actual: Float, message: String = "") =
        assertTrue(abs(expected - actual) <= 1f, "$message expected=$expected actual=$actual")
}
