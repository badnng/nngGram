package com.badnng.moe.media

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface

/** Media-smoke only: real synthetic silent MP4 and the production player, no account. */
class InlineVideoPlayerSmokeTestCurrentProfilePhotoOutputTest {
    @Test fun sourceGateStopsDrawingAndPlayWithoutAComposeFrameAndFreshPlayerWorks() {
        val file = Files.createTempFile("synthetic-current-output-", ".mp4")
        try {
            MediaTools.capture(assertNotNull(MediaDecoder.findFfmpeg()), listOf(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "color=c=red:s=64x48:r=30:d=2", "-an", "-c:v", "libx264", "-threads", "1",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", file.toString()), timeoutSeconds = 15)
            val allowed = AtomicBoolean(true)
            InlineVideoPlayer(file.toString(), autoPlay = false, videoOnly = true, loop = true,
                sourceIsActive = allowed::get).use { player ->
                awaitFrame(player)
                assertRed(drawCenter(player)); assertTrue(player.hasFrame())
                allowed.set(false) // No scene, recomposition, or invalidation callback drives this check.
                assertEquals(BACKGROUND, drawCenter(player))
                player.play(); assertFalse(player.state.value.playing)
                assertTrue(player.hasFrame(), "A revoked borrowed frame is retained until ordinary close")
            }
            val fresh = AtomicBoolean(true)
            InlineVideoPlayer(file.toString(), autoPlay = false, videoOnly = true, loop = true,
                sourceIsActive = fresh::get).use { player ->
                awaitFrame(player); assertRed(drawCenter(player))
            }
        } finally {
            var deleted = false
            repeat(50) {
                if (!deleted) { deleted = runCatching { Files.deleteIfExists(file) }.isSuccess; if (!deleted) Thread.sleep(20) }
            }
            if (!deleted) Files.deleteIfExists(file)
        }
    }

    private fun awaitFrame(player: InlineVideoPlayer) {
        repeat(750) {
            assertFalse(player.state.value.failed, "The synthetic video failed before its first frame")
            if (player.hasFrame()) return
            Thread.sleep(20)
        }
        assertTrue(player.hasFrame(), "The synthetic video did not produce a frame")
    }

    private fun drawCenter(player: InlineVideoPlayer): Int = Surface.makeRasterN32Premul(64, 48).use { surface ->
        surface.canvas.clear(BACKGROUND); player.draw(surface.canvas, 64f, 48f)
        surface.makeImageSnapshot().use { image ->
            assertNotNull(image.encodeToData()).use { data ->
                assertNotNull(ImageIO.read(ByteArrayInputStream(data.bytes))).getRGB(32, 24)
            }
        }
    }

    private fun assertRed(color: Int) {
        assertTrue(((color ushr 16) and 255) > 180 && (color and 255) < 60, "Expected the real synthetic video frame")
    }

    companion object { private const val BACKGROUND = -15724528 } // 0xff101010
}
