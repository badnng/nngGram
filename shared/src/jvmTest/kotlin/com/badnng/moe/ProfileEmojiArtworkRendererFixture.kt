package com.badnng.moe

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.badnng.moe.media.ProfileEmojiArtwork
import com.badnng.moe.media.ProfileEmojiArtworkLease
import com.badnng.moe.media.ProfileEmojiArtworkProvider
import com.badnng.moe.media.ProfileEmojiArtworkRequest
import com.badnng.moe.media.ProfileEmojiArtworkToken
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsError
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Prepared synthetic pixels, never a Unicode or platform-font substitute for native artwork. */
internal class ProfileRendererArtworkFixture(
    override val generation: Long = 7,
    private val completeImmediately: Boolean = false,
    private val color: Int = 0xffffffff.toInt(),
) : ProfileEmojiArtworkProvider, AutoCloseable {
    val calls = CopyOnWriteArrayList<Call>()
    val providerCloses = AtomicInteger()

    inner class Call(
        val token: ProfileEmojiArtworkToken,
        val expected: SettingsBackgroundEmoji?,
        val request: ProfileEmojiArtworkRequest,
        val active: () -> Boolean,
        private val result: (SettingsResult<ProfileEmojiArtwork>) -> Unit,
    ) : ProfileEmojiArtworkLease {
        val closes = AtomicInteger()
        private var pixels: Bitmap? = null
        private var bitmap: ImageBitmap? = null

        /** Deliberately allows delivery after close to exercise the renderer's stale callback guard. */
        fun complete(argb: Int = color, mask: Boolean = true, ignoreClosed: Boolean = false,
            animated: Boolean = false) {
            val edge = request.physicalSizePx
            val prepared = Surface.makeRasterN32Premul(edge, edge).use { surface ->
                surface.canvas.clear(0)
                Paint().use { paint ->
                    paint.color = argb
                    // An asymmetric L and a detached square expose transparency and clipping.
                    surface.canvas.drawRect(Rect.makeXYWH(edge * .125f, edge * .125f,
                        edge * .25f, edge * .75f), paint)
                    surface.canvas.drawRect(Rect.makeXYWH(edge * .375f, edge * .625f,
                        edge * .5f, edge * .25f), paint)
                    paint.color = (argb and 0x00ffffff) or 0x80000000.toInt()
                    surface.canvas.drawRect(Rect.makeXYWH(edge * .625f, edge * .125f,
                        edge * .25f, edge * .25f), paint)
                }
                surface.makeImageSnapshot().use { Bitmap.makeFromImage(it) }
            }
            synchronized(this) {
                // Tests complete each call once. A duplicate callback must not leak a second bitmap.
                check(pixels == null)
                pixels = prepared
                bitmap = prepared.asComposeImageBitmap()
            }
            result(SettingsResult(value = object : ProfileEmojiArtwork {
                override val image: ImageBitmap? get() = if (ignoreClosed || closes.get() == 0) bitmap else null
                override val isAlphaMask = mask
                override val animated = animated
                override val durationMillis = if (animated) 1000L else 0L
                override val frameDurationMillis = if (animated) 64L else 0L
            }))
        }

        fun fail() = result(SettingsResult(error = SettingsError.REQUEST_FAILED))

        override fun close() { closes.incrementAndGet() }

        fun releasePixels() = synchronized(this) {
            bitmap = null
            pixels?.close()
            pixels = null
        }
    }

    override fun acquire(token: ProfileEmojiArtworkToken, expected: SettingsBackgroundEmoji?,
        request: ProfileEmojiArtworkRequest, active: () -> Boolean,
        result: (SettingsResult<ProfileEmojiArtwork>) -> Unit): ProfileEmojiArtworkLease {
        val call = Call(token, expected, request, active, result)
        calls += call
        if (completeImmediately) call.complete()
        return call
    }

    override fun close() {
        providerCloses.incrementAndGet()
        calls.forEach(Call::releasePixels)
    }
}
