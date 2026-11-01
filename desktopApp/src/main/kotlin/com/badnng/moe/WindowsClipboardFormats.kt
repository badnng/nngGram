package com.badnng.moe

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.abs

/** Bounded, toolkit-free parsers for Win32 clipboard memory objects. */
internal object WindowsClipboardFormats {
    const val UNICODE_TEXT = 13
    const val FILE_DROP = 15
    const val DIB = 8
    const val DIB_V5 = 17
    const val MAX_TEXT_BYTES = 64 * 1024 * 1024
    const val MAX_FILE_LIST_BYTES = 2 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 128 * 1024 * 1024
    const val MAX_PNG_BYTES = 64 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 24_000_000L
    const val MAX_FILES = 64

    fun decodeText(bytes: ByteArray): String? {
        if (bytes.size !in 2..MAX_TEXT_BYTES || bytes.size % 2 != 0) return null
        var end = 0
        while (end + 1 < bytes.size) {
            if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) return String(bytes, 0, end, Charsets.UTF_16LE)
            end += 2
        }
        return null
    }

    fun encodeText(text: String): ByteArray {
        require(text.length <= MAX_TEXT_BYTES / 2 - 1) { "Clipboard text is too large" }
        return (text.substringBefore('\u0000') + '\u0000').toByteArray(Charsets.UTF_16LE)
    }

    fun decodeFiles(bytes: ByteArray, ansiCharset: Charset): List<String>? {
        if (bytes.size !in 22..MAX_FILE_LIST_BYTES) return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = header.getInt(0).toLong() and 0xffffffffL
        val wide = header.getInt(16) != 0
        val unit = if (wide) 2 else 1
        if (offset !in 20L..(bytes.size - unit * 2).toLong() || wide && offset % 2 != 0L) return null
        val names = mutableListOf<String>()
        var start = offset.toInt()
        var position = start
        while (position + unit <= bytes.size) {
            val zero = bytes[position] == 0.toByte() && (!wide || bytes[position + 1] == 0.toByte())
            if (zero) {
                if (position == start) return names.takeIf { it.isNotEmpty() }
                val name = String(bytes, start, position - start, if (wide) Charsets.UTF_16LE else ansiCharset)
                if (!validFilePath(name) || names.size >= MAX_FILES) return null
                names += name
                start = position + unit
            }
            position += unit
        }
        return null // The file list must have its second terminating NUL.
    }

    fun encodeFiles(paths: List<String>): ByteArray {
        require(paths.size in 1..MAX_FILES && paths.all(::validFilePath)) { "Invalid clipboard file list" }
        val names = (paths.joinToString("\u0000") + "\u0000\u0000").toByteArray(Charsets.UTF_16LE)
        require(names.size + 20 <= MAX_FILE_LIST_BYTES) { "Clipboard file list is too large" }
        return ByteBuffer.allocate(20 + names.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(20).putInt(0).putInt(0).putInt(0).putInt(1).put(names).array()
    }

    private fun validFilePath(path: String): Boolean {
        if (path.length !in 3..32767 || path.any { it.code < 32 }) return false
        val drive = path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] in "/\\"
        val network = path.startsWith("\\\\") && path.drop(2).contains('\\')
        return drive || network
    }

    fun validatePng(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size in 8..MAX_PNG_BYTES && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) { "Unsupported clipboard image" }
        return Data.makeFromBytes(bytes).use { data ->
            Codec.makeFromData(data).use { codec ->
                require(codec.encodedImageFormat == EncodedImageFormat.PNG && codec.frameCount <= 1) { "Unsupported clipboard image" }
                validateDimensions(codec.width, codec.height)
                codec.width to codec.height
            }
        }
    }

    fun pngToRaster(bytes: ByteArray): ClipboardRaster {
        val (width, height) = validatePng(bytes)
        return Image.makeFromEncoded(bytes).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                require(bitmap.width == width && bitmap.height == height) { "Invalid clipboard image" }
                ClipboardRaster(width, height, IntArray(width * height) { bitmap.getColor(it % width, it / width) })
            }
        }
    }

    fun rasterToPng(raster: ClipboardRaster): ByteArray {
        validateRaster(raster)
        val rgba = ByteArray(raster.argb.size * 4)
        raster.argb.forEachIndexed { index, pixel ->
            rgba[index * 4] = (pixel ushr 16).toByte()
            rgba[index * 4 + 1] = (pixel ushr 8).toByte()
            rgba[index * 4 + 2] = pixel.toByte()
            rgba[index * 4 + 3] = (pixel ushr 24).toByte()
        }
        return Image.makeRaster(ImageInfo(raster.width, raster.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
            rgba, raster.width * 4).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                require(data.size <= MAX_PNG_BYTES) { "Clipboard image is too large" }
                data.bytes
            }
        }
    }

    fun decodeDib(bytes: ByteArray): ClipboardRaster {
        require(bytes.size in 40..MAX_IMAGE_BYTES) { "Unsupported clipboard bitmap" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = data.getInt(0)
        require(headerSize in setOf(40, 52, 56, 108, 124) && bytes.size >= headerSize) { "Unsupported clipboard bitmap" }
        val width = data.getInt(4)
        val signedHeight = data.getInt(8)
        require(signedHeight != Int.MIN_VALUE) { "Unsupported clipboard bitmap" }
        val height = abs(signedHeight)
        validateDimensions(width, height)
        val bitCount = data.getShort(14).toInt() and 0xffff
        val compression = data.getInt(16)
        require(data.getShort(12).toInt() == 1 && bitCount in setOf(24, 32) && compression in setOf(0, 3)) {
            "Unsupported clipboard bitmap"
        }
        require(compression != 3 || bitCount == 32) { "Unsupported clipboard bitmap" }
        val paletteCount = data.getInt(32).toLong() and 0xffffffffL
        require(paletteCount <= 256) { "Unsupported clipboard bitmap" }
        val masksBytes = if (headerSize == 40 && compression == 3) 12 else 0
        val pixelOffset = headerSize.toLong() + masksBytes + paletteCount * 4
        val stride = ((width.toLong() * bitCount + 31) / 32) * 4
        val pixelEnd = pixelOffset + stride * height
        require(pixelEnd <= bytes.size && pixelEnd <= MAX_IMAGE_BYTES) { "Incomplete clipboard bitmap" }
        if (headerSize == 124) {
            val profileOffset = data.getInt(112).toLong() and 0xffffffffL
            val profileSize = data.getInt(116).toLong() and 0xffffffffL
            // A packed DIB's profile follows the bits. Never follow a linked profile
            // path or interpret profile bytes as image data.
            require(profileSize == 0L || profileOffset >= pixelEnd && profileOffset + profileSize <= bytes.size) {
                "Invalid clipboard bitmap profile"
            }
        }
        val redMask = if (compression == 3) data.getInt(40) else 0x00ff0000
        val greenMask = if (compression == 3) data.getInt(44) else 0x0000ff00
        val blueMask = if (compression == 3) data.getInt(48) else 0x000000ff
        val alphaMask = if (compression == 3 && headerSize >= 56) data.getInt(52) else 0
        if (compression == 3) validateMasks(redMask, greenMask, blueMask, alphaMask)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = if (signedHeight > 0) height - y - 1 else y
            val row = (pixelOffset + sourceY * stride).toInt()
            for (x in 0 until width) {
                val offset = row + x * (bitCount / 8)
                pixels[y * width + x] = if (bitCount == 24) {
                    0xff000000.toInt() or ((bytes[offset + 2].toInt() and 255) shl 16) or
                        ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset].toInt() and 255)
                } else {
                    val pixel = data.getInt(offset)
                    val alpha = if (alphaMask == 0) 255 else component(pixel, alphaMask)
                    (alpha shl 24) or (component(pixel, redMask) shl 16) or
                        (component(pixel, greenMask) shl 8) or component(pixel, blueMask)
                }
            }
        }
        return ClipboardRaster(width, height, pixels)
    }

    fun encodeDibV5(raster: ClipboardRaster): ByteArray {
        validateRaster(raster)
        val pixelsSize = raster.argb.size * 4
        val data = ByteBuffer.allocate(124 + pixelsSize).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0, 124)
        data.putInt(4, raster.width)
        data.putInt(8, -raster.height)
        data.putShort(12, 1)
        data.putShort(14, 32)
        data.putInt(16, 3) // BI_BITFIELDS, with explicit straight alpha.
        data.putInt(20, pixelsSize)
        data.putInt(40, 0x00ff0000)
        data.putInt(44, 0x0000ff00)
        data.putInt(48, 0x000000ff)
        data.putInt(52, 0xff000000.toInt())
        data.putInt(56, 0x73524742) // LCS_sRGB.
        data.putInt(108, 4) // LCS_GM_IMAGES.
        raster.argb.forEachIndexed { index, pixel -> data.putInt(124 + index * 4, pixel) }
        return data.array()
    }

    private fun validateDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_IMAGE_PIXELS) { "Clipboard image is too large" }
    }

    private fun validateRaster(raster: ClipboardRaster) {
        validateDimensions(raster.width, raster.height)
        require(raster.argb.size.toLong() == raster.width.toLong() * raster.height) { "Incomplete clipboard image" }
    }

    private fun validateMasks(red: Int, green: Int, blue: Int, alpha: Int) {
        val masks = listOf(red, green, blue, alpha)
        require(masks.take(3).all { it != 0 } && masks.filter { it != 0 }.all { mask ->
            val shifted = (mask.toLong() and 0xffffffffL) ushr Integer.numberOfTrailingZeros(mask)
            shifted and (shifted + 1L) == 0L
        } && masks.indices.all { first -> (first + 1 until masks.size).all { masks[first] and masks[it] == 0 } }) {
            "Unsupported clipboard bitmap masks"
        }
    }

    private fun component(pixel: Int, mask: Int): Int {
        val shift = Integer.numberOfTrailingZeros(mask)
        val maximum = (mask.toLong() and 0xffffffffL) ushr shift
        val value = ((pixel and mask).toLong() and 0xffffffffL) ushr shift
        return ((value * 255 + maximum / 2) / maximum).toInt()
    }

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
}

internal data class ClipboardRaster(val width: Int, val height: Int, val argb: IntArray) {
    override fun toString() = "ClipboardRaster(<redacted>)"
}
