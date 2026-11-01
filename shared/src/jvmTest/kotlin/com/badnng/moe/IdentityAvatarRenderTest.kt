package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real avatar components; every image is generated locally, without native account access. */
class IdentityAvatarRenderTest {
    @Test
    fun chatSenderToolsAccountAndRecentPhotosFillTheirCirclesWithoutChangingTheSourceProportions() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (portrait in listOf(false, true)) withPhoto(portrait) { path ->
                val snapshot = photoSnapshot(path)
                avatarScene(density, mode) { AvatarExamples(snapshot) }.use { scene ->
                    val frame = scene.awaitPhotos(5)
                    val bounds = scene.photoBounds().sortedBy { it.left }
                    assertEquals(5, bounds.size)
                    for ((index, edge) in listOf(48f, 33f, 36f, 44f, 21f).withIndex()) {
                        val circle = bounds[index]
                        assertTrue(abs(circle.width - edge * density) <= 1f, "Avatar $index must keep its actual logical size.")
                        assertEquals(circle.width, circle.height, "Avatar slots must be square at fractional DPI.")
                        for ((x, y) in listOf(.5f to .12f, .5f to .88f, .12f to .5f, .88f to .5f)) {
                            assertColor(GREEN, frame.pixel(circle, x, y), "The photograph must fill the circle's cardinal edges.")
                        }
                        for ((x, y) in listOf(.04f to .04f, .96f to .04f, .04f to .96f, .96f to .96f)) {
                            assertColor(MAGENTA, frame.pixel(circle, x, y), "The exterior must remain an actual circle.")
                        }
                        val centerX = circle.center.x.roundToInt()
                        val centerY = circle.center.y.roundToInt()
                        val horizontal = (circle.left.roundToInt() until circle.right.roundToInt()).count {
                            isBlue(frame[it, centerY])
                        }
                        val vertical = (circle.top.roundToInt() until circle.bottom.roundToInt()).count {
                            isBlue(frame[centerX, it])
                        }
                        assertTrue(abs(horizontal - vertical) <= 2,
                            "The synthetic circular feature must retain its proportion after center cropping.")
                    }
                    frame.writePreview("${if (mode == ColorSchemeMode.Light) "light" else "dark"}-${if (density == 1f) "100" else "125"}-${if (portrait) "portrait" else "landscape"}")
                }
            }
        }
    }

    @Test
    fun finePhotoDetailsUseSmoothCoverageAtNormalAndFractionalDpi() {
        for (density in listOf(1f, 1.25f)) withPhoto(fineDetail = true) { path ->
            val snapshot = photoSnapshot(path)
            avatarScene(density, ColorSchemeMode.Light) {
                Box(Modifier.offset(20.dp, 20.dp)) {
                    SenderIdentityAvatar(MESSAGE, snapshot, ChatFeatureActions(), visible = true)
                }
            }.use { scene ->
                val frame = scene.awaitPhotos(1)
                val bounds = scene.photoBounds().single()
                // A nearest-neighbour downscale can only choose black or white here.
                // Smooth sampling must integrate the synthetic high-frequency detail.
                var samples = 0
                var mixed = 0
                for (y in (bounds.top + bounds.height * .25f).roundToInt() until (bounds.bottom - bounds.height * .25f).roundToInt()) {
                    for (x in (bounds.left + bounds.width * .25f).roundToInt() until (bounds.right - bounds.width * .25f).roundToInt()) {
                        val pixel = frame[x, y]
                        val red = pixel ushr 16 and 255
                        val green = pixel ushr 8 and 255
                        val blue = pixel and 255
                        samples++
                        if (red in 8..247 && abs(red - green) <= 1 && abs(red - blue) <= 1) mixed++
                    }
                }
                assertTrue(mixed >= samples / 3, "Smooth downscaling must retain partial coverage rather than aliasing the fine detail.")
                frame.writePreview("detail-${if (density == 1f) "100" else "125"}")
            }
        }
    }

    @Test
    fun placeholderDownloadAndInactivePhotoKeepTheSameFullSizeWithoutAScaleTransition() = withPhoto { path ->
        val ready = photoSnapshot(path)
        val loading = ready.copy(senderAvatars = mapOf(USER_ID to MediaFile(FILE_ID, canDownload = true)))
        val state = mutableStateOf(loading)
        val visible = mutableStateOf(false)
        val downloads = mutableListOf<Int>()
        val features = ChatFeatureActions(downloadFile = { _, id -> downloads += id })
        avatarScene(1.25f, ColorSchemeMode.Dark) {
            Box(Modifier.offset(20.dp, 20.dp)) {
                SenderIdentityAvatar(MESSAGE, state.value, features, visible = visible.value)
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(downloads.isEmpty(), "An inactive avatar must not request native bytes.")
            visible.value = true
            scene.settledFrame()
            assertEquals(listOf(FILE_ID), downloads)
            state.value = ready
            scene.awaitPhotos(1)
            val originalBounds = scene.photoBounds().single()
            repeat(5) {
                val frame = scene.settledFrame(1)
                assertEquals(originalBounds, scene.photoBounds().single(), "A loaded avatar must immediately have its final dimensions.")
                assertColor(GREEN, frame.pixel(originalBounds, .5f, .08f), "The photo must not enter through a smaller raster.")
            }
            visible.value = false
            val inactiveFrame = scene.settledFrame()
            assertEquals(originalBounds, scene.photoBounds().single())
            assertColor(GREEN, inactiveFrame.pixel(originalBounds, .5f, .08f), "Outgoing pages must retain their ready avatar drawing.")
            assertEquals(listOf(FILE_ID), downloads)
        }
    }

    @Composable
    private fun AvatarExamples(snapshot: ChatSnapshot) {
        ChatIdentityAvatar(ChatItem(20, "合成聊天", avatar = snapshot.senderAvatars[USER_ID]), snapshot,
            ChatFeatureActions(), modifier = Modifier.offset(12.dp, 20.dp))
        SenderIdentityAvatar(MESSAGE, snapshot, ChatFeatureActions(), visible = true,
            modifier = Modifier.offset(80.dp, 20.dp))
        Box(Modifier.offset(135.dp, 20.dp)) { ToolsUserAvatar(USER_ID, "合成用户", snapshot, ChatFeatureActions()) }
        AccountAvatarPhotos(snapshot) {
            AccountAvatarButton(snapshot, ChatFeatureActions(), expanded = false, onBounds = {}, onClick = {},
                modifier = Modifier.offset(190.dp, 16.dp))
        }
        Box(Modifier.offset(246.dp, 20.dp)) {
            NativeAvatarStack(listOf(snapshot.senderAvatars.getValue(USER_ID)), snapshot, ChatFeatureActions())
        }
    }

    private fun avatarScene(density: Float, mode: ColorSchemeMode, content: @Composable () -> Unit) =
        ComposePixelScene((280 * density).roundToInt(), (100 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MiuixTheme(controller = theme) { Box(Modifier.fillMaxSize().background(Color.Magenta)) { content() } }
            }
        }

    private fun ComposePixelScene.photoBounds() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
            description == "聊天图片" || description == "我的头像"
        }
    }

    private fun ComposePixelScene.awaitPhotos(expected: Int): ComposePixelFrame {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val frame = settledFrame(2)
            if (photoBounds().size == expected) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated synthetic avatars did not finish decoding.")
    }

    private fun photoSnapshot(path: Path): ChatSnapshot {
        val file = MediaFile(FILE_ID, downloaded = true, localPath = path.toString())
        return ChatSnapshot(token = ChatToken(1, 1, 20), senderAvatars = mapOf(USER_ID to file),
            account = AccountProfile(19, "合成账号", avatar = file))
    }

    private fun withPhoto(portrait: Boolean = false, fineDetail: Boolean = false, test: (Path) -> Unit) {
        val path = Files.createTempFile("nnggram-identity-avatar-", ".png")
        try {
            val image = BufferedImage(if (portrait) 160 else 320, if (portrait) 320 else 160, BufferedImage.TYPE_INT_RGB)
            val centerX = image.width / 2
            val centerY = image.height / 2
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val position = if (portrait) y else x
                image.setRGB(x, y, when {
                    fineDetail -> if ((x + y) % 4 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    position < 80 || position >= 240 -> 0xFFFF0000.toInt()
                    (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= 35 * 35 -> BLUE
                    else -> GREEN
                })
            }
            check(ImageIO.write(image, "png", path.toFile()))
            test(path)
        } finally { Files.deleteIfExists(path) }
    }

    private fun ComposePixelFrame.pixel(bounds: Rect, x: Float, y: Float) =
        this[(bounds.left + bounds.width * x).toInt(), (bounds.top + bounds.height * y).toInt()]

    private fun ComposePixelFrame.writePreview(name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "identity-avatar-renders")
        Files.createDirectories(directory)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun assertColor(expected: Int, actual: Int, message: String) = assertTrue(
        listOf(24, 16, 8, 0).all { abs((expected ushr it and 255) - (actual ushr it and 255)) <= 3 }, message)

    private fun isBlue(pixel: Int) = (pixel and 255) > 200 && (pixel ushr 8 and 255) < 30 && (pixel ushr 16 and 255) < 30

    private companion object {
        const val USER_ID = 17L
        const val FILE_ID = 700
        val MESSAGE = ChatMessage(1, 20, senderId = USER_ID)
        val GREEN = 0xFF00FF00.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val MAGENTA = 0xFFFF00FF.toInt()
    }
}
