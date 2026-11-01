package com.badnng.moe

import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** Logical display dimensions. Source dimensions are never enlarged to fill a message card. */
internal data class MessageMediaSize(val width: Float, val height: Float)

/** Telegram Desktop Sticker::Size: downscale to 224 logical pixels, never enlarge. */
internal fun stickerMessageSize(sourceWidth: Int, sourceHeight: Int, availableWidth: Float = 224f): MessageMediaSize =
    messageMediaSize(sourceWidth, sourceHeight, availableWidth = availableWidth, maxEdge = 224f)

/**
 * Matches the official client's desired-size then available-width fit: cap both dimensions,
 * retain the native ratio, and shrink again when the conversation has less horizontal room.
 * Unknown dimensions use a modest placeholder until TDLib supplies the actual media metadata.
 */
internal fun messageMediaSize(
    sourceWidth: Int,
    sourceHeight: Int,
    availableWidth: Float = 520f,
    maxEdge: Float = 520f,
): MessageMediaSize {
    val bound = maxEdge.coerceAtLeast(1f)
    val width = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() else min(280f, bound)
    val height = if (sourceWidth > 0 && sourceHeight > 0) sourceHeight.toFloat() else min(180f, bound)
    val scale = min(1f, min(bound / width, min(bound / height, availableWidth.coerceAtLeast(1f) / width)))
    return MessageMediaSize((width * scale).coerceAtLeast(1f), (height * scale).coerceAtLeast(1f))
}

/** Photo::countOptimalSize/countCurrentSize, separate from videos, GIFs and sticker artwork. */
internal fun photoMessageSize(
    sourceWidth: Int,
    sourceHeight: Int,
    availableWidth: Float = 430f,
    hasBubble: Boolean = false,
    captionWidth: Float = 0f,
    minimumInfoWidth: Float = 0f,
): MessageMediaSize {
    val available = availableWidth.coerceIn(1f, 430f)
    val desired = messageMediaSize(sourceWidth, sourceHeight, availableWidth = 430f, maxEdge = 430f)
    val nativeMinimum = if (hasBubble) 200f else 100f
    val optimalMinimum = max(nativeMinimum, minimumInfoWidth).coerceAtMost(430f)
    val caption = captionWidth.takeIf { it.isFinite() }?.coerceIn(0f, 430f) ?: 0f
    val optimalWidth = max(max(desired.width, optimalMinimum), max(desired.height, caption)).coerceAtMost(430f)
    var optimalHeight = max(desired.height, 100f)
    if (hasBubble) optimalHeight = lessCroppedPhotoHeight(sourceWidth, sourceHeight, optimalWidth, optimalHeight)
    // CountPhotoMediaSize fits both edges into the available conversation width.
    val scale = min(1f, min(min(available, optimalWidth) / desired.width, available / desired.height))
    val width = max(max(desired.width * scale, min(optimalMinimum, available)), min(caption, available))
        .coerceIn(1f, available)
    var height = max(desired.height * scale, 100f)
    if (hasBubble) height = lessCroppedPhotoHeight(sourceWidth, sourceHeight, width, height)
    if (width >= optimalWidth) height = min(height, optimalHeight)
    return MessageMediaSize(width, height)
}

/** Streaming::FrameResizeMayExpand uses a 3/4 visible-height threshold with one-pixel tolerance. */
private fun lessCroppedPhotoHeight(sourceWidth: Int, sourceHeight: Int, width: Float, height: Float): Float {
    if (sourceWidth <= 0 || sourceHeight <= 0) return height
    val smallest = min(min(width, height), min(sourceWidth.toFloat(), sourceHeight.toFloat()))
    if (smallest < 4f) return height
    val fullHeight = width * sourceHeight.toFloat() / sourceWidth
    val tolerance = (3f * smallest - 4f) / (4f * smallest)
    return if (fullHeight >= height && fullHeight * tolerance <= height) max(height, fullHeight) else height
}

/** Streaming::DecideFrameResize: only a crop that retains at least 3/4 of the height expands. */
internal fun photoResizeExpands(sourceWidth: Int, sourceHeight: Int, canvasWidth: Float, canvasHeight: Float): Boolean {
    if (sourceWidth <= 0 || sourceHeight <= 0 || !canvasWidth.isFinite() || !canvasHeight.isFinite()) return false
    val width = canvasWidth.roundToInt().coerceAtLeast(1)
    val height = canvasHeight.roundToInt().coerceAtLeast(1)
    val coverScale = max(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
    val expandedWidth = (sourceWidth * coverScale).roundToInt()
    val expandedHeight = (sourceHeight * coverScale).roundToInt()
    return expandedWidth <= width && expandedHeight.toLong() * 3 <= height.toLong() * 4
}
