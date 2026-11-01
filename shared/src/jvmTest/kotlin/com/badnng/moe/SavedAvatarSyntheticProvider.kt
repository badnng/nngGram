package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.badnng.moe.settings.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic only: records typed requests and enforces their live caller/source gates. */
internal class SavedAvatarSyntheticProvider : ProfileStickerAvatarArtworkProvider {
    override val generation = 7L
    val requests = CopyOnWriteArrayList<Call>()
    val framesClosed = AtomicInteger()
    override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        candidate: ProfileStickerAvatarCandidateTicket, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease =
        object : ProfileStickerAvatarArtworkLease { override fun close() {} }
    override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
        selection: ProfileStickerAvatarSelectionTicket, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarArtworkLease =
        object : ProfileStickerAvatarArtworkLease { override fun close() {} }
    override fun savedPreview(request: SettingsSavedProfileStickerAvatarRequest, physicalSizePx: Int, active: () -> Boolean,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarSavedPreviewLease =
        savedPreviewOwned(request, physicalSizePx, active, SettingsSavedProfileStickerAvatarDemand(), result)
    override fun savedPreviewOwned(request: SettingsSavedProfileStickerAvatarRequest, physicalSizePx: Int, active: () -> Boolean,
        demand: SettingsSavedProfileStickerAvatarDemand,
        result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarSavedPreviewLease =
        Call(request, physicalSizePx, active, demand, result).also { requests += it }
    override fun close() { requests.forEach { it.close() } }

    inner class Call(val request: SettingsSavedProfileStickerAvatarRequest, val size: Int, private val caller: () -> Boolean,
        val demand: SettingsSavedProfileStickerAvatarDemand,
        private val result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit) : ProfileStickerAvatarSavedPreviewLease {
        val closed = AtomicBoolean(); val source = AtomicBoolean(true)
        // Optional one-shot barrier for a callback already entering its final source check.
        val beforeNextCurrentCheck = AtomicReference<(() -> Unit)?>(null)
        override fun requestCurrent(): Boolean {
            beforeNextCurrentCheck.getAndSet(null)?.invoke()
            return !closed.get() && source.get() && caller()
        }
        override fun revokeDemand() = demand.revokeDemand()
        fun ready(animated: Boolean = false, repaint: Boolean = false, delayFrames: Boolean = false,
            ignoreClipSource: Boolean = false): Clip = Clip(this, animated, repaint).also {
            it.delayFrames = delayFrames
            it.ignoreSource = ignoreClipSource
            result(SettingsResult(value = it))
        }
        fun fail() = result(SettingsResult(error = SettingsError.REQUEST_FAILED))
        override fun close() { closed.set(true) }
    }

    inner class Clip(private val call: Call, override val animated: Boolean, repaint: Boolean) : ProfileStickerAvatarPreviewClip {
        override val durationMillis = if (animated) 400L else 0L
        override val frameDurationMillis = if (animated) 80L else 0L
        val times = CopyOnWriteArrayList<Long>(); val closed = AtomicBoolean()
        val delayed = CopyOnWriteArrayList<() -> Unit>(); var delayFrames = false; var ignoreSource = false
        private val image = ImageBitmap(32, 32).also { bitmap ->
            Canvas(bitmap).drawRect(Rect(0f, 0f, 32f, 32f), Paint().apply { color = Color(0xffe6374f) })
        }
        private val shouldRepaint = repaint
        override fun active() = !closed.get() && (ignoreSource || call.requestCurrent())
        override fun frame(timeMillis: Long, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease {
            times += timeMillis
            val frameLive = AtomicBoolean(true)
            val art = object : ProfileStickerAvatarArtwork {
                override val image: ImageBitmap? get() = this@Clip.image.takeIf { frameLive.get() && this@Clip.active() && active() }
                override val needsRepainting = shouldRepaint
            }
            val deliver = { result(SettingsResult(value = art)) }
            if (delayFrames) delayed += deliver else deliver()
            return object : ProfileStickerAvatarArtworkLease {
                override fun close() { if (frameLive.compareAndSet(true, false)) framesClosed.incrementAndGet() }
            }
        }
        override fun close() { closed.set(true) }
    }
}
