package com.badnng.moe.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.badnng.moe.ComposePixelScene
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountAvatarPhotoLifecycleTest {
    @Test
    fun duplicateInstallKeepsTheFirstImageAndClosesTheRejectedImage() {
        val photo = SharedAccountAvatarPhoto()
        val firstImage = syntheticImage()
        val rejectedImage = syntheticImage()
        try {
            val first = DecodedMedia.Still(firstImage)
            assertSame(first, photo.install(first))
            assertSame(first, photo.install(first))
            assertFalse(firstImage.isClosed, "Reinstalling the owned object must not release it.")
            assertSame(first, photo.install(DecodedMedia.Still(rejectedImage)))
            assertFalse(firstImage.isClosed)
            assertTrue(rejectedImage.isClosed, "A second decode must never leak beside the first one.")
            photo.close()
            photo.close()
            assertTrue(firstImage.isClosed, "The one accepted native image is released on disposal.")
        } finally {
            photo.close()
            if (!firstImage.isClosed) firstImage.close()
            if (!rejectedImage.isClosed) rejectedImage.close()
        }
    }

    @Test
    fun decoderCompletionAfterDisposalImmediatelyClosesItsImage() {
        val photo = SharedAccountAvatarPhoto()
        val image = syntheticImage()
        try {
            photo.close()
            assertNull(photo.install(DecodedMedia.Still(image)))
            assertTrue(image.isClosed, "A cancelled background decode must release its late result.")
            photo.close()
        } finally {
            photo.close()
            if (!image.isClosed) image.close()
        }
    }

    @Test
    fun disposalAndLatePublicationCannotLeaveAClosedImageDrawable() {
        val photo = SharedAccountAvatarPhoto()
        val image = syntheticImage()
        try {
            val media = DecodedMedia.Still(image)
            photo.publish(photo.install(media))
            ComposePixelScene(48, 48) {
                val controller = remember { ThemeController(ColorSchemeMode.Light) }
                MiuixTheme(controller = controller) { photo.Content(Modifier.fillMaxSize(), "合成账号头像") }
            }.use { scene ->
                scene.settledFrame()
                assertEquals(1, scene.avatarNodes().size)
                photo.close()
                photo.publish(media)
                scene.settledFrame()
                assertTrue(scene.avatarNodes().isEmpty(), "A late publication must not revive a released image.")
                assertTrue(image.isClosed)
            }
        } finally {
            photo.close()
            if (!image.isClosed) image.close()
        }
    }

    private fun ComposePixelScene.avatarNodes() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription)?.contains("合成账号头像") == true
    }

    private fun syntheticImage(): Image = Surface.makeRasterN32Premul(2, 2).use { surface ->
        surface.canvas.clear(0xFF39CBB2.toInt())
        surface.makeImageSnapshot()
    }
}
