package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.*

class MediaSpoilerRenderTest {
    @Test fun photosVideosAndGifsUseRealBlurAndFirstClickOnlyRevealsAtBothScalesAndThemes() = withThumbnail { path ->
        for (format in listOf(MediaFormat.PHOTO, MediaFormat.VIDEO, MediaFormat.ANIMATION))
            for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) {
                val media = MessageMedia(MediaFile(1, size = if (format == MediaFormat.VIDEO) 1000L else 0L, canDownload = format != MediaFormat.ANIMATION),
                    MediaFile(2, localPath = path.toString(), downloaded = true), width = 240, height = 160,
                    format = format, hasSpoiler = true, durationSeconds = 12)
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10))
                val downloads = mutableListOf<Int>()
                val automatic = mutableListOf<Boolean>()
                val samples = mutableListOf<Pair<Long, Float>>()
                var opens = 0
                var plays = 0
                val features = ChatFeatureActions(downloadFile = { _, id -> downloads += id },
                    automaticMedia = { _, _, active -> automatic += active })
                ComposePixelScene((280 * density).roundToInt(), (210 * density).roundToInt()) {
                    SpoilerTheme(dark, density) {
                        CompositionLocalProvider(LocalMediaSpoilerFrameObserver provides { time, progress -> samples += time to progress }) {
                            Box(Modifier.padding(20.dp).size(240.dp, 160.dp)) {
                                Fixture(format, media, snapshot, features, visible = true, active = true,
                                    onOpen = { opens++ }, onPlay = { plays++ })
                            }
                        }
                    }
                }.use { scene ->
                    val hidden = scene.awaitBlur()
                    assertTrue(downloads.isEmpty(), "A ready native thumbnail must not request the full $format file.")
                    assertTrue(automatic.none { it }, "A concealed media has no AUTO owner.")
                    assertTrue(scene.semanticsBounds {
                        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "包含剧透 · 点击显示" }
                    }.isEmpty())
                    val label = "${format.name.lowercase()}-${if (dark) "dark" else "light"}-${(density * 100).roundToInt()}"
                    export(hidden, "$label-hidden")
                    val target = scene.action("显示剧透媒体").single()
                    scene.pressAt(target.center.x, target.center.y)
                    scene.releaseAt(target.center.x, target.center.y)
                    val middle = scene.settledFrame(3, stepMillis = 24)
                    assertEquals(0, opens, "The first click must reveal rather than open a viewer.")
                    assertEquals(0, plays, "The first click must never start concealed video playback.")
                    assertTrue(samples.any { it.second > 0f && it.second < 1f }, "Reveal needs actual intermediate pixels: $label")
                    export(middle, "$label-revealing")
                    val revealed = scene.settledFrame(20, stepMillis = 32)
                    assertTrue(scene.action("显示剧透媒体").isEmpty())
                    assertTrue(changedPixels(hidden, revealed, target) > target.width * target.height * 0.08f,
                        "A clear native thumbnail must replace the blurred artwork: $label")
                    if (format == MediaFormat.PHOTO) {
                        assertTrue(downloads.isEmpty(), "Photo reveal requests its policy-owned AUTO lease.")
                        assertTrue(automatic.any { it })
                    }
                    export(revealed, "$label-revealed")
                }
            }
    }

    @Test fun offscreenAndPausedSpoilersOwnNoFrameLoopAndCancellationDoesNotReplayReveal() = withThumbnail { path ->
        val visible = mutableStateOf(false)
        val active = mutableStateOf(true)
        val focused = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val samples = mutableListOf<Pair<Long, Float>>()
        val downloads = mutableListOf<Int>()
        val media = MessageMedia(MediaFile(1, canDownload = true), MediaFile(2, localPath = path.toString(), downloaded = true),
            width = 240, height = 160, hasSpoiler = true)
        ComposePixelScene(280, 210) {
            SpoilerTheme(false, 1f, focused.value) {
                CompositionLocalProvider(LocalMediaSpoilerFrameObserver provides { time, progress -> samples += time to progress }) {
                    if (mounted.value) Box(Modifier.padding(20.dp).size(240.dp, 160.dp)) {
                        RemoteMedia(media, ChatSnapshot(token = ChatToken(1, 1, 10)),
                            ChatFeatureActions(downloadFile = { _, id -> downloads += id }), visible.value, active.value,
                            Modifier.fillMaxSize())
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertFalse(scene.invalidated(), "An offscreen spoiler must not leave a frame request alive.")
            assertTrue(downloads.isEmpty())
            visible.value = true
            scene.awaitBlur()
            val before = samples.last().first
            scene.awaitParticleAdvance(before) { samples.last().first }
            active.value = false
            scene.settledFrame()
            val paused = samples.last().first
            scene.settledFrame(4, 64)
            assertEquals(paused, samples.last().first)
            assertFalse(scene.invalidated(), "A visible inactive spoiler must own no idle frame callback.")
            active.value = true
            focused.value = false
            scene.settledFrame()
            assertFalse(scene.invalidated(), "An unfocused window must pause image spoiler particles.")
            focused.value = true
            scene.settledFrame(3, 16)
            val target = scene.action("显示剧透媒体").single()
            scene.pressAt(target.center.x, target.center.y)
            scene.releaseAt(target.center.x, target.center.y)
            scene.settledFrame(2, 16)
            active.value = false
            scene.settledFrame()
            assertTrue(scene.action("显示剧透媒体").isEmpty())
            active.value = true
            scene.settledFrame()
            assertTrue(scene.action("显示剧透媒体").isEmpty(), "Reactivation must not replay an already accepted reveal.")
            mounted.value = false
            scene.settledFrame()
            assertFalse(scene.invalidated(), "Disposal must release both thumbnail and effect owners.")
        }
    }

    @Test fun concealedMediaRequestsOnlyItsRealThumbnailAndNeverRetriesAnImmutableFailureWhileIdle() {
        val visible = mutableStateOf(false)
        val snapshot = mutableStateOf(ChatSnapshot(token = ChatToken(1, 1, 10)))
        val downloads = mutableListOf<Int>()
        val media = MessageMedia(MediaFile(1, canDownload = true), MediaFile(2, canDownload = true), hasSpoiler = true)
        ComposePixelScene(240, 160) {
            SpoilerTheme(false, 1f) {
                RemoteMedia(media, snapshot.value, ChatFeatureActions(downloadFile = { _, id -> downloads += id }),
                    visible.value, animate = false, modifier = Modifier.fillMaxSize())
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(downloads.isEmpty())
            visible.value = true
            scene.settledFrame()
            assertEquals(listOf(2), downloads)
            snapshot.value = snapshot.value.copy(fileErrors = mapOf(2 to ChatError.REQUEST_FAILED))
            visible.value = false
            scene.settledFrame()
            visible.value = true
            scene.settledFrame()
            assertEquals(listOf(2), downloads)
            assertFalse(scene.invalidated())
        }
    }

    @Composable private fun Fixture(format: MediaFormat, media: MessageMedia, snapshot: ChatSnapshot, features: ChatFeatureActions,
        visible: Boolean, active: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
        when (format) {
            MediaFormat.PHOTO -> RemoteMedia(media, snapshot, features, visible, active, Modifier.fillMaxSize(), onClick = onOpen,
                photoBackground = true, messageId = 42)
            MediaFormat.ANIMATION -> AnimationMessageContent(media, snapshot, features, 42, visible, active, Modifier.fillMaxSize())
            else -> AttachmentMedia(media, snapshot, features, visible, false, { _, _ -> }, onPlay, 42,
                false, active, {}, null, onOpen, Modifier.fillMaxSize())
        }
    }

    @Composable private fun SpoilerTheme(dark: Boolean, density: Float, focused: Boolean = true, content: @Composable () -> Unit) {
        val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
        val window = LocalWindowInfo.current
        val currentFocused by rememberUpdatedState(focused)
        val testWindow = remember(window) { object : WindowInfo by window { override val isWindowFocused get() = currentFocused } }
        CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides testWindow) {
            MiuixTheme(controller = theme) { Box(Modifier.fillMaxSize().background(Color(0xFF547060))) { content() } }
        }
    }

    private fun ComposePixelScene.awaitBlur(): ComposePixelFrame {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2, 32)
            if (unmergedSemanticsBounds { "模糊剧透缩略图" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.isNotEmpty() &&
                unmergedSemanticsBounds { "剧透粒子遮罩" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.isNotEmpty()) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("The real synthetic thumbnail did not finish its background blur.")
    }

    private fun ComposePixelScene.action(label: String) = semanticsBounds { it.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun ComposePixelScene.awaitParticleAdvance(previous: Long, current: () -> Long) {
        val deadline = System.nanoTime() + 2_000_000_000L
        do {
            settledFrame(2, 32)
            if (current() != previous) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("An active ready spoiler did not advance its 33ms mask frame.")
    }

    private fun ComposePixelScene.invalidated(): Boolean {
        val native = ComposePixelScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(this)
        val result = AtomicBoolean()
        SwingUtilities.invokeAndWait { result.set(native.javaClass.getMethod("hasInvalidations").invoke(native) as Boolean) }
        return result.get()
    }

    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var changed = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(first.height))
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(first.width))
                if (first[x, y] != second[x, y]) changed++
        return changed
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val output = Path.of(System.getProperty("nnggram.media.spoiler.render.output",
            "build/behavior-official-validation/media-spoiler-renders-final"))
        Files.createDirectories(output)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        assertTrue(ImageIO.write(image, "png", output.resolve("$name.png").toFile()))
    }

    private fun withThumbnail(test: (Path) -> Unit) {
        val path = Files.createTempFile("synthetic-media-spoiler-", ".png")
        try {
            val image = BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val base = if (x < image.width / 2) 0xC44828 else 0x2088CC
                image.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) base else base xor 0x00303030)
            }
            check(ImageIO.write(image, "png", path.toFile()))
            test(path)
        } finally { Files.deleteIfExists(path) }
    }
}
