package com.badnng.moe.settings

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos

/** Ordered, immutable 24-bit RGB values. This is presentation state, never native authorization. */
internal class AvatarPreviewColors private constructor(private val rgb: IntArray) {
    val size: Int get() = rgb.size
    fun values(): List<Int> = rgb.toList()
    fun color(index: Int): Int = rgb[index]

    fun replace(index: Int, value: Int): AvatarPreviewColors? {
        if (index !in rgb.indices || value !in 0..0xFFFFFF) return null
        return AvatarPreviewColors(rgb.copyOf().also { it[index] = value })
    }

    fun append(value: Int): AvatarPreviewColors? =
        if (size >= 4 || value !in 0..0xFFFFFF) null else AvatarPreviewColors(rgb + value)

    fun removeLast(): AvatarPreviewColors? =
        if (size <= 1) null else AvatarPreviewColors(rgb.copyOf(size - 1))

    override fun equals(other: Any?): Boolean = other is AvatarPreviewColors && rgb.contentEquals(other.rgb)
    override fun hashCode(): Int = rgb.contentHashCode()

    companion object {
        fun from(values: List<Int>): AvatarPreviewColors? {
            val copy = values.toIntArray()
            if (copy.size !in 1..4 || copy.any { it !in 0..0xFFFFFF }) return null
            return AvatarPreviewColors(copy)
        }
    }
}

/** Desktop a7f4536 PaletteGradients after the source swaps 0↔2 and 1↔3. */
internal object AvatarPreviewPalettes {
    private val palettes = listOf(
        listOf(0x4D8DFF, 0x2BBFFF, 0x20E2CD, 0x0EE1F1),
        listOf(0x5EB6FB, 0x1FCEEB, 0x45F7B7, 0x1FF1D9),
        listOf(0x09D260, 0x5EDC40, 0xC1E526, 0x80DF2B),
        listOf(0xF5694E, 0xF5772C, 0xFFD412, 0xFFA743),
        listOf(0xF64884, 0xEF5B41, 0xF6A730, 0xFF7742),
        listOf(0xF94BA0, 0xFB5C80, 0xFFB23A, 0xFE7E62),
        listOf(0x837CFF, 0xB063FF, 0xFF72A9, 0xE269FF),
    ).map { checkNotNull(AvatarPreviewColors.from(it)) }

    val size: Int get() = palettes.size
    fun get(index: Int): AvatarPreviewColors? = palettes.getOrNull(index)
}

internal data class AvatarPreviewRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** The schema's 67% bound is an app adaptation, not Desktop's size/√2 painter geometry. */
internal class AvatarPreviewGeometry private constructor(
    val circle: AvatarPreviewRect,
    val foregroundCanvas: AvatarPreviewRect,
) {
    fun fitArtwork(sourceWidth: Int, sourceHeight: Int): AvatarPreviewRect? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val side = foregroundCanvas.width
        val ratio = min(side / sourceWidth.toFloat(), side / sourceHeight.toFloat())
        val width = sourceWidth * ratio
        val height = sourceHeight * ratio
        return AvatarPreviewRect(
            foregroundCanvas.left + (side - width) / 2f,
            foregroundCanvas.top + (side - height) / 2f,
            width,
            height,
        )
    }

    companion object {
        fun within(width: Float, height: Float): AvatarPreviewGeometry? {
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
            val side = min(width, height)
            val left = (width - side) / 2f
            val top = (height - side) / 2f
            val artSide = side * .67f
            val inset = (side - artSide) / 2f
            return AvatarPreviewGeometry(AvatarPreviewRect(left, top, side, side),
                AvatarPreviewRect(left + inset, top + inset, artSide, artSide))
        }
    }
}

/**
 * Desktop a7f4536 / lib_ui 79c846f image_prepare.cpp:171–307 and 890–956.
 * Desktop's 45° preview references. Samples are 24-bit RGB (no alpha), never artwork; the
 * eventual renderer must explicitly make the background opaque before applying the circle clip.
 * Prepare the 64×64 complex field off the draw path and scale smoothly; clipping, interpolation,
 * color management, Qt pixel parity and the server's output are separate concerns.
 */
internal object AvatarPreviewGradient {
    fun linear45(colors: AvatarPreviewColors, x: Float, y: Float): Int? {
        if (colors.size !in 1..2 || !x.isFinite() || !y.isFinite()) return null
        if (colors.size == 1) return colors.color(0)
        // Desktop 45°: start at the upper right, end at the lower left.
        val position = ((1f - x + y) / 2f).coerceIn(0f, 1f)
        val a = colors.color(0)
        val b = colors.color(1)
        fun channel(shift: Int): Int {
            val first = a shr shift and 255
            val second = b shr shift and 255
            return (first + (second - first) * position).roundToInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun complex45(colors: AvatarPreviewColors): IntArray? {
        if (colors.size !in 3..4) return null
        val positions = arrayOf(.60f to .80f, .25f to .40f, .40f to .20f, .75f to .60f)
        val result = IntArray(64 * 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            val dx = x / 64f - .5f
            val dy = y / 64f - .5f
            val radius = sqrt(dx * dx + dy * dy)
            val swirl = .35f * radius
            val angle = swirl * swirl * .8f * 8f
            val px = (.5f + dx * cos(angle) - dy * sin(angle)).coerceIn(0f, 1f)
            val py = (.5f + dx * sin(angle) + dy * cos(angle)).coerceIn(0f, 1f)
            var weightSum = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (i in 0 until colors.size) {
                val (cx, cy) = positions[i]
                val distance = (.9f - sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))).coerceAtLeast(0f)
                val square = distance * distance
                val weight = square * square
                val color = colors.color(i)
                weightSum += weight
                red += weight * (color shr 16 and 255)
                green += weight * (color shr 8 and 255)
                blue += weight * (color and 255)
            }
            // Positions ensure a positive sum over the complete 64×64 field.
            val r = (red / weightSum).toInt().coerceIn(0, 255)
            val g = (green / weightSum).toInt().coerceIn(0, 255)
            val b = (blue / weightSum).toInt().coerceIn(0, 255)
            result[y * 64 + x] = (r shl 16) or (g shl 8) or b
        }
        return result
    }
}
