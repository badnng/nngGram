package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.mapChatMessage
import com.badnng.moe.tdlib.mapMediaFile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import org.drinkless.tdlib.TdApi
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Native mapping, real message composition and real worker pixels share the same acceptance path. */
class MediaLoadingPipelineRenderTest {
    @Test
    fun miniOnlyNativePhotosDrawTheirPixelsAndOneRingInsideTheRealSenderBubble() = sources { source ->
        for ((mode, density) in APPEARANCES) {
            val fixture = PhotoFixture(source, mini = true)
            assertNotNull(fixture.snapshot.messages.single().media?.minithumbnail,
                "The native mapper must retain the actual offline source before Compose receives it.")
            fixture.scene(mode, density).use { scene ->
                val canvas = scene.awaitDescription("照片模糊底图")
                val ring = scene.awaitDescription("图片加载中")
                assertRing(ring, canvas, density)
                assertTrue(scene.textBounds(SENDER).isNotEmpty(), "The screenshot's sender header must use PhotoMessageContent.")
                assertTrue(scene.textBounds(SENDER).single().bottom <= canvas.top + density,
                    "The native sender header stays above the flush media pixels.")
                val first = scene.settledFrame(2)
                assertThumbnailPixels(first, canvas)
                scene.assertNoLegacyLabel()
                assertEquals(1, scene.descriptions("图片加载中").size)
                val later = scene.settledFrame(4)
                assertTrue(differs(first, later, ring), "The real Miuix arc must animate over the native miniature.")
                assertEquals(emptyList(), fixture.downloads,
                    "A miniature is already local, and the original is already in flight.")
                export(later, "native-mini-$mode-$density")

                fixture.update { active = false }
                scene.settledFrame(3)
                assertTrue(scene.descriptions("图片加载中").isEmpty())
                fixture.update { active = true; focused = false }
                scene.settledFrame(3)
                assertTrue(scene.descriptions("图片加载中").isEmpty())
                fixture.update { focused = true }
                scene.awaitDescription("图片加载中")
                fixture.update { snapshot = snapshot.copy(fileErrors = mapOf(FULL_ID to ChatError.REQUEST_FAILED)) }
                scene.settledFrame(3)
                assertTrue(scene.descriptions("图片加载中").isEmpty(), "A real native failure must stop the loading animation.")
                assertTrue(scene.textBounds("媒体加载失败").isNotEmpty())
                scene.assertNoLegacyLabel()
            }
        }
    }

    @Test
    fun missingAndRemoteOnlyNativeThumbnailsNeverPutTheWordImageUnderTheRing() = sources { source ->
        for ((mode, density) in APPEARANCES) for (remoteThumbnail in listOf(false, true)) {
            val fixture = PhotoFixture(source, mini = false, remoteThumbnail = remoteThumbnail)
            fixture.scene(mode, density).use { scene ->
                val ring = scene.awaitDescription("图片加载中")
                assertTrue(abs(ring.width - 30f * density) <= 1f && abs(ring.height - 30f * density) <= 1f)
                repeat(12) {
                    scene.settledFrame(1, 16)
                    scene.assertNoLegacyLabel()
                    assertEquals(1, scene.descriptions("图片加载中").size,
                        "No-source inline photos still have one dedicated loading layer.")
                }
                assertTrue(scene.descriptions("照片模糊底图").isEmpty(),
                    "A missing local source cannot fabricate a blurry image.")
                assertEquals(if (remoteThumbnail) listOf(THUMB_ID) else emptyList(), fixture.downloads)
                export(scene.settledFrame(2), "native-${if (remoteThumbnail) "remote-thumb" else "no-source"}-$mode-$density")

                if (remoteThumbnail) {
                    fixture.acceptNativeFile(THUMB_ID, source.thumbnail)
                    val canvas = scene.awaitDescription("照片模糊底图")
                    assertThumbnailPixels(scene.settledFrame(3), canvas)
                    scene.assertNoLegacyLabel()
                    assertEquals(listOf(THUMB_ID), fixture.downloads,
                        "Completing a thumbnail must not request it again or start a placeholder original download.")
                }
            }
        }
    }

    @Test
    fun miniatureToThumbnailToOriginalKeepsTheRealPaneCoveredAndItsGeometryStable() = sources { source ->
        for ((mode, density) in APPEARANCES) {
            val fixture = PhotoFixture(source, mini = true, remoteThumbnail = true)
            fixture.scene(mode, density).use { scene ->
                val canvas = scene.awaitDescription("照片模糊底图")
                val row = assertNotNull(fixture.measured[MESSAGE_ID])
                assertThumbnailPixels(scene.settledFrame(2), canvas)
                assertEquals(listOf(THUMB_ID), fixture.downloads)

                fixture.acceptNativeFile(THUMB_ID, source.thumbnail)
                repeat(16) {
                    val frame = scene.settledFrame(1, 16)
                    assertCovered(frame, canvas)
                    scene.assertNoLegacyLabel()
                    assertEquals(row, fixture.measured[MESSAGE_ID], "Replacing an offline source cannot resize its actual message row.")
                }
                scene.awaitThumbnailColor(canvas, source.thumbnailLeft)
                export(scene.settledFrame(2), "native-thumb-$mode-$density")

                fixture.acceptNativeFile(FULL_ID, source.full)
                repeat(16) {
                    val frame = scene.settledFrame(1, 16)
                    assertCovered(frame, canvas)
                    scene.assertNoLegacyLabel()
                    assertEquals(row, fixture.measured[MESSAGE_ID], "Native download acceptance cannot move the photo/header row.")
                }
                val clear = scene.awaitDescription("聊天图片")
                assertEquals(canvas, clear, "The ready image occupies the same photo pixels as its actual blurred source.")
                val ready = scene.settledFrame(4)
                assertTrue(distance(ready.at(clear, .25f, .55f), GREEN) <= 3)
                assertTrue(scene.descriptions("图片加载中").isEmpty())
                assertEquals(listOf(THUMB_ID), fixture.downloads,
                    "Only the missing native thumbnail is requested; background preparation never downloads an original.")
                export(ready, "native-ready-$mode-$density")
            }
        }
    }

    @Test
    fun nativeVideoMiniatureReachesTheActualAttachmentCoverWithoutOpeningAPlayer() = sources { source ->
        // Two opposite theme/density combinations cover the real attachment entry without a decoder or audio device.
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val native = nativeMessage(TdApi.MessageVideo().apply {
                video = TdApi.Video().apply {
                    width = 640; height = 320; duration = 5
                    minithumbnail = source.mini()
                    video = nativeFile(FULL_ID, size = 0)
                }
                caption = TdApi.FormattedText("", emptyArray())
            })
            val message = mapChatMessage(native)
            val media = assertNotNull(message.media)
            assertNotNull(media.minithumbnail)
            var active by mutableStateOf(true)
            var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = TOKEN, messages = listOf(message)))
            val requests = mutableListOf<Int>()
            var playRequests = 0
            mediaScene(mode, density, 430, 240) {
                AttachmentMedia(media, snapshot, ChatFeatureActions(downloadFile = { _, id -> requests += id }),
                    visible = true, canSave = false, onSave = { _, _ -> }, onPlay = { playRequests++ },
                    messageId = MESSAGE_ID, playing = false, active = active, onStopPlaying = {},
                    playbackSession = null, onExpand = {}, modifier = Modifier.width(430.dp))
            }.use { scene ->
                val cover = scene.awaitDescription("真实模糊缩略图")
                val ring = scene.awaitDescription("视频加载中")
                assertRing(ring, cover, density)
                assertThumbnailPixels(scene.settledFrame(3), cover)
                scene.assertNoLegacyLabel()
                assertEquals(0, playRequests)
                assertTrue(requests.isEmpty(), "A native miniature cover does not download video bytes.")
                export(scene.settledFrame(2), "native-video-mini-$mode-$density")
                onUi { active = false }
                scene.settledFrame(3)
                assertTrue(scene.descriptions("视频加载中").isEmpty())
                onUi {
                    active = true
                    snapshot = snapshot.copy(fileErrors = mapOf(FULL_ID to ChatError.REQUEST_FAILED))
                }
                scene.settledFrame(3)
                assertTrue(scene.descriptions("视频加载中").isEmpty())
                assertEquals(0, playRequests)
                assertTrue(requests.isEmpty())
            }
        }
    }

    @Test
    fun aNativeVideoPosterKeepsItsMiniatureUntilTheThumbnailDrawsAndWhenDownloadingResumes() = sources { source ->
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val message = mapChatMessage(nativeMessage(TdApi.MessageVideo().apply {
                video = TdApi.Video().apply {
                    width = 640; height = 320; duration = 5
                    minithumbnail = source.mini()
                    thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 120, 60,
                        nativeFile(THUMB_ID, downloading = false))
                    video = nativeFile(FULL_ID, downloading = false)
                }
                caption = TdApi.FormattedText("", emptyArray())
            }))
            val media = assertNotNull(message.media)
            var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = TOKEN, messages = listOf(message)))
            val requests = mutableListOf<Int>()
            var playRequests = 0
            mediaScene(mode, density, 430, 240) {
                AttachmentMedia(media, snapshot, ChatFeatureActions(downloadFile = { _, id -> requests += id }),
                    visible = true, canSave = false, onSave = { _, _ -> }, onPlay = { playRequests++ },
                    messageId = MESSAGE_ID, playing = false, active = true, onStopPlaying = {},
                    playbackSession = null, onExpand = {}, modifier = Modifier.width(430.dp))
            }.use { scene ->
                val cover = scene.awaitDescription("真实模糊缩略图")
                assertRing(scene.awaitDescription("视频加载中"), cover, density)
                val initialCover = scene.settledFrame(2)
                assertThumbnailPixels(initialCover, cover)
                val corners = listOf(
                    (cover.left + density).roundToInt() to (cover.top + density).roundToInt(),
                    (cover.right - 2 * density).roundToInt() to (cover.top + density).roundToInt(),
                    (cover.left + density).roundToInt() to (cover.bottom - 2 * density).roundToInt(),
                    (cover.right - 2 * density).roundToInt() to (cover.bottom - 2 * density).roundToInt(),
                )
                assertEquals(listOf(THUMB_ID), requests,
                    "An idle video's loading poster requests only its real thumbnail.")

                onUi {
                    snapshot = snapshot.copy(mediaFiles = mapOf(THUMB_ID to
                        mapMediaFile(nativeFile(THUMB_ID, downloading = false, path = source.thumbnail))))
                }
                repeat(16) {
                    assertCovered(scene.settledFrame(1, 16), cover)
                    scene.assertNoLegacyLabel()
                }
                val poster = scene.awaitDescription("视频缩略图")
                assertEquals(cover, poster, "The clear native poster must keep the actual cover's canvas.")
                val deadline = System.nanoTime() + 5_000_000_000L
                var clearReady = false
                do {
                    val frame = scene.settledFrame(1, 16)
                    assertCovered(frame, cover)
                    clearReady = distance(frame.at(poster, .25f, .55f), source.thumbnailLeft) <= 4
                    if (!clearReady) Thread.sleep(5)
                } while (!clearReady && System.nanoTime() < deadline)
                assertTrue(clearReady, "The poster must actually draw its clear pixels after preparation.")
                assertTrue(scene.descriptions("视频加载中").isEmpty())
                assertEquals(listOf(THUMB_ID), requests)
                val clearFrame = scene.settledFrame(2)
                for ((x, y) in corners) assertEquals(initialCover[x, y], clearFrame[x, y],
                    "The clear poster must retain the same Miuix corner boundary as its loading cover.")
                export(clearFrame, "native-video-poster-$mode-$density")

                onUi {
                    snapshot = snapshot.copy(mediaFiles = snapshot.mediaFiles +
                        (FULL_ID to mapMediaFile(nativeFile(FULL_ID, downloading = true))))
                }
                repeat(12) {
                    assertCovered(scene.settledFrame(1, 16), cover)
                    scene.assertNoLegacyLabel()
                }
                assertRing(scene.awaitDescription("视频加载中"), cover, density)
                scene.awaitThumbnailColor(cover, source.thumbnailLeft)
                val resumed = scene.settledFrame(3)
                assertTrue(distance(resumed.at(cover, .25f, .55f), darkened(source.thumbnailLeft)) <= 4,
                    "Resuming the full download reuses the prepared native blur instead of exposing an empty canvas.")
                assertEquals(listOf(THUMB_ID), requests)
                assertEquals(0, playRequests, "Poster transitions must never start the video player or an audio device.")
                export(resumed, "native-video-resumed-$mode-$density")
            }
        }
    }

    private inner class PhotoFixture(source: Source, mini: Boolean, remoteThumbnail: Boolean = false) {
        val native = nativeMessage(TdApi.MessagePhoto().apply {
            photo = TdApi.Photo(false, if (mini) source.mini() else null,
                buildList {
                    if (remoteThumbnail) add(TdApi.PhotoSize("s", nativeFile(THUMB_ID, downloading = false), 120, 60, intArrayOf()))
                    add(TdApi.PhotoSize("y", nativeFile(FULL_ID), 640, 320, intArrayOf()))
                }.toTypedArray())
            caption = TdApi.FormattedText("", emptyArray())
        })
        private val message = mapChatMessage(native)
        private val chat = ChatItem(CHAT_ID, "Synthetic native media", supergroupId = 55, isJoinedGroup = true,
            lastMessage = message)
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = TOKEN, selected = chat,
            items = listOf(chat), messages = listOf(message), senderNames = mapOf(SENDER_ID to SENDER), historyEnd = true))
        var active by mutableStateOf(true)
        var focused by mutableStateOf(true)
        val downloads = mutableListOf<Int>()
        val measured = ConcurrentHashMap<Long, Rect>()

        fun update(block: PhotoFixture.() -> Unit) = onUi { block() }
        fun acceptNativeFile(id: Int, path: Path) = update {
            val mapped = mapMediaFile(nativeFile(id, downloading = false, path = path))
            snapshot = snapshot.copy(mediaFiles = snapshot.mediaFiles + (id to mapped))
        }
        fun scene(mode: ColorSchemeMode, density: Float) = mediaScene(mode, density, 680, 620, { focused }) {
            CompositionLocalProvider(LocalDesktopChatLayout provides desktopChatLayout(680.dp, 620.dp),
                LocalConversationPreviewMessageBounds provides { id, rect ->
                    if (rect == null) measured.remove(id) else measured[id] = rect
                }) {
                ConversationPane(snapshot, checkNotNull(snapshot.selected), TextFieldValue(), {}, null, {}, {}, {}, {},
                    ChatFeatureActions(downloadFile = { _, id -> downloads += id }), active,
                    {}, {}, {}, {}, false, modifier = Modifier.fillMaxSize())
            }
        }
    }

    private class Source(val thumbnail: Path, val full: Path, val encodedMini: ByteArray, val thumbnailLeft: Int) {
        fun mini() = TdApi.Minithumbnail().apply { width = 40; height = 20; data = encodedMini.copyOf() }
    }

    private fun mediaScene(mode: ColorSchemeMode, density: Float, width: Int, height: Int,
        focused: () -> Boolean = { true }, content: @Composable () -> Unit) =
        ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val window = LocalWindowInfo.current
            val currentFocus by rememberUpdatedState(focused())
            val testWindow = remember(window) { object : WindowInfo by window {
                override val isWindowFocused get() = currentFocus
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides testWindow) {
                MiuixTheme(controller = theme) { Box(Modifier.width(width.dp).height(height.dp)) { content() } }
            }
        }

    private fun ComposePixelScene.descriptions(value: String) = unmergedSemanticsBounds {
        value in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.textBounds(value: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
    }
    private fun ComposePixelScene.assertNoLegacyLabel() {
        assertTrue(textBounds("图片").isEmpty(),
            "Inline PhotoMessageContent must never draw the old 20sp placeholder under its loading arc.")
    }
    private fun ComposePixelScene.awaitDescription(value: String): Rect {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(1, 16)
            assertNoLegacyLabel()
            descriptions(value).singleOrNull()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("The actual native-mapped media must produce $value in the real composition.")
    }
    private fun ComposePixelScene.awaitThumbnailColor(canvas: Rect, color: Int) {
        val expected = darkened(color)
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2, 16)
            assertCovered(frame, canvas)
            if (distance(frame.at(canvas, .25f, .55f), expected) <= 4) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("A real ready native thumbnail must replace the offline miniature after worker preparation.")
    }
    private fun assertRing(ring: Rect, canvas: Rect, density: Float) {
        assertTrue(abs(ring.width - 30f * density) <= 1f && abs(ring.height - 30f * density) <= 1f)
        assertTrue(abs(ring.center.x - canvas.center.x) <= 1f && abs(ring.center.y - canvas.center.y) <= 1f,
            "The single Miuix ring belongs at the actual media center.")
    }
    private fun assertThumbnailPixels(frame: ComposePixelFrame, canvas: Rect) {
        val warm = frame.at(canvas, .25f, .55f)
        val cool = frame.at(canvas, .75f, .55f)
        assertTrue(red(warm) > blue(warm) + 70 && blue(cool) > red(cool) + 70,
            "The native source's real warm/cool regions must survive crop, blur and darkening.")
    }
    private fun assertCovered(frame: ComposePixelFrame, canvas: Rect) {
        val warmOrClear = frame.at(canvas, .25f, .55f)
        val coolOrClear = frame.at(canvas, .75f, .55f)
        fun green(color: Int) = (color ushr 8 and 255) > red(color) + 70
        assertTrue((red(warmOrClear) > blue(warmOrClear) + 60 || green(warmOrClear)) &&
            (blue(coolOrClear) > red(coolOrClear) + 60 || green(coolOrClear)),
            "Every source-transition frame must retain the prepared native blur or clear pixels, never an empty white canvas.")
    }
    private fun ComposePixelFrame.at(bounds: Rect, x: Float, y: Float) =
        this[(bounds.left + bounds.width * x).roundToInt(), (bounds.top + bounds.height * y).roundToInt()]
    private fun differs(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect) =
        (bounds.top.roundToInt() until bounds.bottom.roundToInt()).any { y ->
            (bounds.left.roundToInt() until bounds.right.roundToInt()).any { x -> first[x, y] != second[x, y] }
        }
    private fun sources(test: (Source) -> Unit) {
        val directory = Files.createTempDirectory("native-media-loading-")
        val thumbnail = directory.resolve("thumbnail.png")
        val full = directory.resolve("full.png")
        try {
            fun image(width: Int, height: Int, left: Int, right: Int) =
                BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
                    for (y in 0 until height) for (x in 0 until width) setRGB(x, y, if (x < width / 2) left else right)
                }
            val mini = ByteArrayOutputStream().use { stream ->
                check(ImageIO.write(image(40, 20, WARM, COOL), "jpg", stream))
                stream.toByteArray()
            }
            val thumbnailLeft = 0xffda6e0c.toInt()
            check(ImageIO.write(image(120, 60, thumbnailLeft, 0xff2e7adc.toInt()), "png", thumbnail.toFile()))
            check(ImageIO.write(image(640, 320, GREEN, GREEN), "png", full.toFile()))
            test(Source(thumbnail, full, mini, thumbnailLeft))
        } finally {
            Files.deleteIfExists(thumbnail)
            Files.deleteIfExists(full)
            Files.deleteIfExists(directory)
        }
    }
    private fun export(frame: ComposePixelFrame, label: String) {
        val directory = Path.of(System.getProperty("nngGram.mediaLoadingPipelineFrames",
            Path.of(System.getProperty("java.io.tmpdir"), "native-media-loading-pipeline").toString()))
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$label.png").toFile()))
    }
    private companion object {
        const val CHAT_ID = 10L
        const val MESSAGE_ID = 101L
        const val SENDER_ID = 20L
        const val FULL_ID = 1
        const val THUMB_ID = 2
        const val SENDER = "Synthetic sender"
        val TOKEN = ChatToken(1, 1, CHAT_ID)
        val APPEARANCES = listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Light to 1.25f,
            ColorSchemeMode.Dark to 1f, ColorSchemeMode.Dark to 1.25f)
        val GREEN = 0xff24bd76.toInt()
        val WARM = 0xffcc4020.toInt()
        val COOL = 0xff2084cc.toInt()
        fun red(color: Int) = color ushr 16 and 255
        fun blue(color: Int) = color and 255
        fun distance(first: Int, second: Int) = listOf(16, 8, 0).maxOf {
            abs((first ushr it and 255) - (second ushr it and 255))
        }
        fun darkened(color: Int) = (255 shl 24) or ((color ushr 16 and 255) * 207 / 255 shl 16) or
            ((color ushr 8 and 255) * 207 / 255 shl 8) or ((color and 255) * 207 / 255)
        fun nativeMessage(content: TdApi.MessageContent) = TdApi.Message().apply {
            id = MESSAGE_ID; chatId = CHAT_ID; date = 1_700_000_000
            senderId = TdApi.MessageSenderUser(SENDER_ID)
            this.content = content
            canBeSaved = true
        }
        fun nativeFile(id: Int, downloading: Boolean = true, size: Long = 1_000, path: Path? = null) = TdApi.File().apply {
            this.id = id; this.size = size; expectedSize = size
            local = TdApi.LocalFile().apply {
                this.path = path?.toString().orEmpty()
                canBeDownloaded = true
                isDownloadingActive = downloading && path == null
                isDownloadingCompleted = path != null
                downloadedSize = if (path != null) size else 100
            }
        }
        fun <T> onUi(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
