package com.badnng.moe.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.ComposePixelFrame
import com.badnng.moe.ComposePixelScene
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** The real overlay, driven by synthetic state only: no decoder, account or audio device. */
class InlineVideoProgressRenderTest {
    @Test
    fun inlineProgressTouchesTheMediaBottomAndOnlyTheImageHandlesClicks() {
        for (density in listOf(1f, 1.25f)) for (dark in listOf(false, true)) {
            val widthDp = if (dark) 320 else 520
            val heightDp = if (dark) 170 else 260
            val state = mutableStateOf(VideoPlayerState(ready = true, loading = false,
                playing = true, position = 25.0, duration = 100.0, volume = 0f))
            var opened = 0
            overlayScene(widthDp, heightDp, density, dark, state = { state.value }, onOpen = { opened++ }).use { scene ->
                val frame = scene.settledFrame()
                val progress = assertNotNull(scene.semanticsBounds {
                    "视频播放进度" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.singleOrNull())
                assertTrue(abs(progress.bottom - (16 + heightDp) * density) <= 1f,
                    "The thin line must end on the video edge, above the metadata row: $progress")
                assertTrue(abs(progress.height - 3f * density) <= 1f,
                    "Inline progress must not reserve the viewer's 22 dp seek target: $progress")
                assertTrue(scene.unmergedSemanticsBounds { it.getOrNull(SemanticsActions.SetProgress) != null }.isEmpty(),
                    "The bubble must not offer an accessible seek action.")
                assertTrue(scene.unmergedSemanticsBounds {
                    "视频播放进度" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                        it.getOrNull(SemanticsProperties.Focused) != null
                }.isEmpty(),
                    "The display-only line must not create a keyboard focus target.")
                val playedX = (progress.left + progress.width * .1f).roundToInt()
                val remainingX = (progress.left + progress.width * .75f).roundToInt()
                val lineY = (progress.bottom - 2f * density).roundToInt()
                assertTrue(distance(frame[playedX, lineY], frame[remainingX, lineY]) > 45,
                    "The line must distinguish the theme-colored played segment from the gray remainder.")
                val aboveLine = (progress.top - 2f * density).roundToInt()
                assertEquals(0xff000000.toInt(), frame[playedX, aboveLine],
                    "No invisible hit-strip padding or raised progress track may remain over the picture.")
                export(frame, "progress-${if (dark) "dark" else "light"}-$density")

                val x = progress.left + progress.width * .7f
                val y = progress.center.y
                scene.moveTo(x, y)
                scene.pressAt(x, y)
                scene.releaseAt(x, y)
                scene.settledFrame(2)
                assertEquals(1, opened, "A click on the line must retain the underlying video's open action.")
            }
        }
    }

    @Test
    fun waitingUsesTheNativeCircularArcAndUnmountsWhenInactiveReadyOrFailed() {
        for (density in listOf(1f, 1.25f)) for (dark in listOf(false, true)) {
            val state = mutableStateOf(VideoPlayerState())
            val active = mutableStateOf(true)
            val focused = mutableStateOf(true)
            overlayScene(320, 170, density, dark, state = { state.value }, active = { active.value },
                focused = { focused.value }).use { scene ->
                val first = scene.settledFrame(2)
                val loading = assertNotNull(scene.semanticsBounds {
                    "视频加载中" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.singleOrNull())
                assertTrue(abs(loading.width - 30f * density) <= 1f && abs(loading.height - 30f * density) <= 1f,
                    "The requested Miuix arc must keep its native 30 dp diameter.")
                assertTrue(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains("加载") || text.text.contains("缓冲") }
                }.isEmpty(), "Waiting must not draw a loading-text card.")
                assertTrue(scene.unmergedSemanticsBounds {
                    "视频加载中" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                        it.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
                }.isNotEmpty(), "Waiting retains indeterminate progress semantics.")
                assertTrue(scene.unmergedSemanticsBounds { it.getOrNull(SemanticsActions.SetProgress) != null }.isEmpty(),
                    "The loading arc must not introduce a seek action.")
                val second = scene.settledFrame(3)
                assertTrue(regionDiffers(first, second, loading.left.toInt(), loading.top.toInt(),
                    loading.right.toInt(), loading.bottom.toInt()), "The native arc must move over its gray track during waiting.")
                export(first, "loading-a-${if (dark) "dark" else "light"}-$density")
                export(second, "loading-b-${if (dark) "dark" else "light"}-$density")

                focused.value = false
                scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty(), "An unfocused window must unmount the loading animation.")
                focused.value = true
                active.value = false
                val inactive = scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty(), "An invisible/inactive player must unmount the infinite animation.")
                val laterInactive = scene.settledFrame(5)
                assertTrue(!regionDiffers(inactive, laterInactive, loading.left.toInt(), loading.top.toInt(),
                    loading.right.toInt(), loading.bottom.toInt()), "The inactive loading region must stay still.")
                active.value = true
                state.value = state.value.copy(ready = true, loading = false, playing = true, buffering = true)
                scene.settledFrame(2)
                assertEquals(1, scene.loadingBounds().size, "A real buffering state must show the same indicator.")
                state.value = state.value.copy(buffering = false)
                scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty(), "Ready playback must have no loading animation.")
                state.value = state.value.copy(buffering = true, failed = true)
                scene.settledFrame(2)
                assertTrue(scene.loadingBounds().isEmpty(), "An error must leave the error UI visible without an infinite spinner.")
            }
        }
    }

    private fun overlayScene(widthDp: Int, videoHeightDp: Int, density: Float, dark: Boolean,
        state: () -> VideoPlayerState, active: () -> Boolean = { true }, onOpen: () -> Unit = {},
        focused: () -> Boolean = { true }): ComposePixelScene =
        ComposePixelScene((widthDp * density).roundToInt(), ((videoHeightDp + 72) * density).roundToInt()) {
            val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }
            val window = LocalWindowInfo.current
            val currentFocus by rememberUpdatedState(focused())
            val testWindow = remember(window) { object : WindowInfo by window {
                override val isWindowFocused get() = currentFocus
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides testWindow) {
                MiuixTheme(colors = colors) {
                    Box(Modifier.fillMaxSize().background(colors.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Box(Modifier.fillMaxWidth().height(videoHeightDp.dp).squircleClip(12.dp).background(Color.Black)) {
                                Box(Modifier.matchParentSize().clickable(onClick = onOpen))
                                InlineVideoProgressOverlay(state(), active(), { .25f }, Modifier.matchParentSize())
                            }
                            Text("13:13  ✓✓", color = colors.onSurface, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.End).padding(top = 8.dp, end = 6.dp))
                        }
                    }
                }
            }
        }

    private fun ComposePixelScene.loadingBounds() = semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
            description == "视频加载中" || description == "视频缓冲中"
        }
    }

    private fun export(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "inline-video-progress")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
        println("Synthetic inline video frame: ${directory.resolve("$name.png")}")
    }

    private fun regionDiffers(first: ComposePixelFrame, second: ComposePixelFrame,
        left: Int, top: Int, right: Int, bottom: Int): Boolean =
        (top until bottom).any { y -> (left until right).any { x -> distance(first[x, y], second[x, y]) > 10 } }

    private fun distance(first: Int, second: Int): Int = listOf(0, 8, 16).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }
}
