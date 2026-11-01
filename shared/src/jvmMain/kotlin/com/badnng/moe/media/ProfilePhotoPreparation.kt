package com.badnng.moe.media

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.SettingsPreparedProfilePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val profilePhotoWorker = Semaphore(1)
internal data class ProfilePhotoCrop(val x: Float = .5f, val y: Float = .5f, val zoom: Float = 1f)
/** Only an explicitly picked immutable encoding is retained; rendering never opens the original path. */
internal class ProfilePhotoSource internal constructor(
    internal val bytes: ByteArray, val width: Int, val height: Int, val animated: Boolean = false,
) {
    override fun toString() = "ProfilePhotoSource(<redacted>)"
}

internal suspend fun readProfilePhotoSource(path: String): ProfilePhotoSource = profilePhotoWorker.withPermit {
    runInterruptible(Dispatchers.IO) { ProfilePhotoPreparation.read(path) }
}
internal suspend fun prepareProfilePhoto(source: ProfilePhotoSource, crop: ProfilePhotoCrop): SettingsPreparedProfilePhoto {
    var prepared: SettingsPreparedProfilePhoto? = null
    try {
        return profilePhotoWorker.withPermit {
            runInterruptible(Dispatchers.IO) { ProfilePhotoPreparation.prepare(source, crop).also { prepared = it } }
        }
    } catch (error: Throwable) { prepared?.lease?.release(); throw error }
}

internal object ProfilePhotoPreparation {
    const val MAX_INPUT_BYTES = 10_000_000L
    const val MAX_OUTPUT_BYTES = 2_000_000L
    const val MAX_DIMENSION = 1280
    fun read(path: String): ProfilePhotoSource {
        val file = Path.of(path)
        require(Files.isRegularFile(file) && Files.size(file) in 1..MAX_INPUT_BYTES)
        // readNBytes limits allocation even when the selected file grows during the read.
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_INPUT_BYTES.toInt() + 1) }
        require(bytes.size.toLong() in 1..MAX_INPUT_BYTES)
        return Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.encodedImageFormat in setOf(EncodedImageFormat.JPEG, EncodedImageFormat.PNG,
                    EncodedImageFormat.WEBP, EncodedImageFormat.BMP, EncodedImageFormat.GIF, EncodedImageFormat.ICO,
                    EncodedImageFormat.WBMP) &&
                    codec.frameCount >= 1 && codec.width in 16..10000 && codec.height in 16..10000 &&
                    codec.width.toLong() * codec.height <= 24_000_000L)
                val swap = codec.encodedOrigin.swapsWidthHeight()
                ProfilePhotoSource(bytes, if (swap) codec.height else codec.width, if (swap) codec.width else codec.height,
                    animated = codec.frameCount > 1)
            }
        }
    }

    fun prepare(source: ProfilePhotoSource, crop: ProfilePhotoCrop): SettingsPreparedProfilePhoto {
        require(crop.x.isFinite() && crop.y.isFinite() && crop.zoom.isFinite() &&
            crop.x in 0f..1f && crop.y in 0f..1f && crop.zoom in 1f..4f)
        var output: ByteArray? = null
        Data.makeFromBytes(source.bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.frameCount >= 1 && codec.width.toLong() * codec.height <= 24_000_000L)
                val info = ImageInfo(codec.width, codec.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, codec.colorSpace)
                Bitmap().use { bitmap ->
                    require(bitmap.allocPixels(info))
                    // The official image-first branch uses one still image. Decode frame zero
                    // only, including GIF/WebP inputs; do not create a playback resource.
                    codec.readPixels(bitmap)
                    val pixels = checkNotNull(bitmap.readPixels(info))
                    val size = minOf(source.width, source.height).toDouble() / crop.zoom
                    val dimension = minOf(MAX_DIMENSION, floor(size).toInt()).coerceAtLeast(1)
                    val sampled = cropAreaPixels(pixels, codec.width, codec.height, codec.encodedOrigin,
                        source.width, source.height, crop, dimension)
                    Image.makeRaster(ImageInfo(dimension, dimension, ColorType.RGBA_8888, ColorAlphaType.OPAQUE,
                        codec.colorSpace), sampled, dimension * 4).use { image ->
                        checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, 87)).use { encoded -> output = encoded.bytes }
                    }
                    checkNotNull(output).also { require(it.size.toLong() in 1..MAX_OUTPUT_BYTES) }
                    val target = Files.createTempFile("nnggram-profile-", ".jpg")
                    val lease = ownedAttachmentLease { runCatching { Files.deleteIfExists(target) } }
                    try {
                        Files.write(target, checkNotNull(output))
                        return SettingsPreparedProfilePhoto(target.toString(), checkNotNull(output).size.toLong(), dimension,
                            profilePhotoSha256(checkNotNull(output)), lease)
                    } catch (error: Throwable) { lease.release(); throw error }
                }
            }
        }
    }
}

/** Positive area integration over the oriented crop, in premultiplied RGBA, then flatten onto white for JPEG. */
internal fun cropAreaPixels(pixels: ByteArray, rawWidth: Int, rawHeight: Int, origin: EncodedOrigin,
    width: Int, height: Int, crop: ProfilePhotoCrop, target: Int,
): ByteArray {
    val size = minOf(width, height).toDouble() / crop.zoom
    val left = (width - size) * crop.x
    val top = (height - size) * crop.y
    val scale = size / target
    val result = ByteArray(target * target * 4)
    fun offset(x: Int, y: Int): Int {
        return when (origin) {
            EncodedOrigin.TOP_RIGHT -> (y * rawWidth + rawWidth - 1 - x) * 4
            EncodedOrigin.BOTTOM_RIGHT -> ((rawHeight - 1 - y) * rawWidth + rawWidth - 1 - x) * 4
            EncodedOrigin.BOTTOM_LEFT -> ((rawHeight - 1 - y) * rawWidth + x) * 4
            EncodedOrigin.LEFT_TOP -> (x * rawWidth + y) * 4
            EncodedOrigin.RIGHT_TOP -> ((rawHeight - 1 - x) * rawWidth + y) * 4
            EncodedOrigin.RIGHT_BOTTOM -> ((rawHeight - 1 - x) * rawWidth + rawWidth - 1 - y) * 4
            EncodedOrigin.LEFT_BOTTOM -> (x * rawWidth + rawWidth - 1 - y) * 4
            else -> (y * rawWidth + x) * 4
        }
    }
    for (y in 0 until target) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val a = top + y * scale
        val b = top + (y + 1) * scale
        for (x in 0 until target) {
            val c = left + x * scale
            val d = left + (x + 1) * scale
            var red = 0.0; var green = 0.0; var blue = 0.0
            for (sy in floor(a).toInt() until ceil(b).toInt().coerceAtMost(height)) {
                val wy = minOf(b, sy + 1.0) - maxOf(a, sy.toDouble())
                for (sx in floor(c).toInt() until ceil(d).toInt().coerceAtMost(width)) {
                    val weight = (minOf(d, sx + 1.0) - maxOf(c, sx.toDouble())) * wy
                    val index = offset(sx, sy)
                    val white = 255 - (pixels[index + 3].toInt() and 255)
                    red += ((pixels[index].toInt() and 255) + white) * weight
                    green += ((pixels[index + 1].toInt() and 255) + white) * weight
                    blue += ((pixels[index + 2].toInt() and 255) + white) * weight
                }
            }
            val index = (y * target + x) * 4
            val inverseArea = 1.0 / (scale * scale)
            result[index] = (red * inverseArea).roundToInt().coerceIn(0, 255).toByte()
            result[index + 1] = (green * inverseArea).roundToInt().coerceIn(0, 255).toByte()
            result[index + 2] = (blue * inverseArea).roundToInt().coerceIn(0, 255).toByte()
            result[index + 3] = 255.toByte()
        }
    }
    return result
}

internal fun profilePhotoSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** A changed/replaced temp file must never be submitted under its earlier preview identity. */
internal fun validatePreparedProfilePhoto(photo: SettingsPreparedProfilePhoto): Boolean = runCatching {
    if (photo.size !in 1..ProfilePhotoPreparation.MAX_OUTPUT_BYTES || photo.dimension !in 1..ProfilePhotoPreparation.MAX_DIMENSION ||
        !photo.sha256.matches(Regex("[a-f0-9]{64}"))) return false
    val file = Path.of(photo.path)
    if (!Files.isRegularFile(file) || Files.size(file) != photo.size) return false
    val bytes = Files.newInputStream(file).use { it.readNBytes(ProfilePhotoPreparation.MAX_OUTPUT_BYTES.toInt() + 1) }
    if (bytes.size.toLong() != photo.size || profilePhotoSha256(bytes) != photo.sha256) return false
    Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
        codec.encodedImageFormat == EncodedImageFormat.JPEG && codec.frameCount == 1 &&
            codec.width == photo.dimension && codec.height == photo.dimension && codec.encodedOrigin == EncodedOrigin.TOP_LEFT
    } }
}.getOrDefault(false)
