package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.status.ConnectionPhase
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AccountAvatarEdgeRenderTest {
    @Test
    fun anAccountPhotoKeepsAnActualCircleInsteadOfASaturatedRoundedRectangle() {
        for (density in listOf(1f, 1.25f)) {
            val path = Files.createTempFile("nnggram-avatar-circle-", ".png")
            try {
                val photo = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 48) for (x in 0 until 48) photo.setRGB(x, y, 0xff28bce2.toInt())
                check(ImageIO.write(photo, "png", path.toFile()))
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
                    account = AccountProfile(900001, "合成圆形账号", avatar = MediaFile(700, downloaded = true, localPath = path.toString())))
                val viewport = IntSize((580 * density).roundToInt(), (800 * density).roundToInt())
                ComposePixelScene(viewport.width, viewport.height) {
                    val colors = remember { lightColorScheme(surfaceContainer = Color.White) }
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        MiuixTheme(colors = colors) {
                            AccountAvatarPhotos(snapshot) {
                                Box(Modifier.fillMaxSize().background(Color.Black)) {
                                    AccountMenuOverlay(snapshot, ChatFeatureActions(), ConnectionPhase.READY,
                                        Rect(8f * density, 8f * density, 60f * density, 52f * density), viewport,
                                        false, {}, false, {}, {}, null, {}, {}, {}, {}, showAvatar = false)
                                }
                            }
                        }
                    }
                }.use { scene ->
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (scene.photoBounds().isEmpty() && System.nanoTime() < deadline) {
                        scene.settledFrame(2)
                        Thread.sleep(5)
                    }
                    val frame = scene.settledFrame()
                    val bounds = assertNotNull(scene.photoBounds().singleOrNull())
                    for ((x, y) in listOf(0.13f to 0.13f, 0.87f to 0.13f, 0.13f to 0.87f, 0.87f to 0.87f)) {
                        val pixel = frame[(bounds.left + bounds.width * x).toInt(), (bounds.top + bounds.height * y).toInt()]
                        assertTrue((pixel ushr 16 and 255) >= 245,
                            "Circle diagonals must expose the white body; a squircle would fill this point: $density/$x,$y")
                    }
                    val axis = frame[bounds.center.x.toInt(), (bounds.top + 2 * density).toInt()]
                    assertTrue((axis ushr 16 and 255) < 80 && (axis ushr 8 and 255) > 150,
                        "The circle must still draw the photograph along its vertical axis.")
                }
            } finally { Files.deleteIfExists(path) }
        }
    }

    @Test
    fun photoTransparencyAndFractionalMenuScaleNeverIntroduceADarkOutline() {
        for (density in listOf(1f, 1.25f)) for (transparentPhoto in listOf(false, true)) {
            for (backdrop in listOf(Color.Black, Color.Transparent)) verifyPhotoEdge(density, transparentPhoto, backdrop)
        }
    }

    private fun verifyPhotoEdge(density: Float, transparentPhoto: Boolean, backdrop: Color) {
        val path = Files.createTempFile("nnggram-avatar-edge-", ".png")
        try {
            val photo = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 48) for (x in 0 until 48) {
                // Transparent white pixels exercise premultiplied image edges without
                // treating a dark region genuinely present in a user's photo as a bug.
                val alpha = if (transparentPhoto) ((x * 17 + y * 13) % 256) else 255
                photo.setRGB(x, y, (alpha shl 24) or 0x00ffffff)
            }
            check(ImageIO.write(photo, "png", path.toFile()))
            val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
                account = AccountProfile(900001, "合成边缘账号",
                    avatar = MediaFile(700, downloaded = true, localPath = path.toString())))
            val progress = mutableFloatStateOf(1f)
            val viewport = IntSize((580 * density).roundToInt(), (800 * density).roundToInt())
            val anchor = Rect(8f * density, 8f * density, 60f * density, 52f * density)
            ComposePixelScene(viewport.width, viewport.height) {
                val colors = remember { lightColorScheme(surfaceContainer = Color.White) }
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    MiuixTheme(colors = colors) {
                        AccountAvatarPhotos(snapshot) {
                            Box(Modifier.fillMaxSize().background(backdrop)) {
                                AccountMenuOverlay(snapshot, ChatFeatureActions(), ConnectionPhase.READY, anchor, viewport,
                                    false, {}, false, {}, {}, null, {}, {}, {}, {}, progress = progress.floatValue,
                                    interactive = true, showAvatar = false)
                            }
                        }
                    }
                }
            }.use { scene ->
                val deadline = System.nanoTime() + 5_000_000_000L
                while (scene.photoBounds().isEmpty() && System.nanoTime() < deadline) {
                    scene.settledFrame(2)
                    Thread.sleep(5)
                }
                assertNotNull(scene.photoBounds().singleOrNull(), "The isolated PNG must really be decoded.")
                for (reveal in listOf(1f, 0.99f, 0.65f, 0.35f)) {
                    progress.floatValue = reveal
                    val frame = scene.settledFrame()
                    val bounds = assertNotNull(scene.photoBounds().singleOrNull())
                    val referenceX = (bounds.left - 4 * density).roundToInt()
                    val band = ceil(3f * density).toInt()
                    val left = floor(bounds.left).toInt()
                    val right = ceil(bounds.right).toInt()
                    val top = floor(bounds.top).toInt()
                    val bottom = ceil(bounds.bottom).toInt()
                    for (y in top until bottom) for (x in left until right) {
                        if (x >= left + band && x < right - band && y >= top + band && y < bottom - band) continue
                        val actual = frame[x, y]
                        val surroundingBody = frame[referenceX, y]
                        assertTrue(distance(actual, surroundingBody) <= 4,
                            "Photo mask introduced a dark/opaque edge: $density/$transparentPhoto/$backdrop/$reveal @ $x,$y")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun ComposePixelScene.photoBounds() = unmergedSemanticsBounds {
        "我的头像" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun distance(first: Int, second: Int): Int = listOf(24, 16, 8, 0).maxOf {
        abs((first ushr it and 255) - (second ushr it and 255))
    }
}
