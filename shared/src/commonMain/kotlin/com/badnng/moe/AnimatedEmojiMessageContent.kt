package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.PathParser
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.media.MediaPreview
import top.yukonga.miuix.kmp.basic.Text

/** Sticker::EmojiSize is a separate 112dp transparent canvas, with one cycle rather than a sticker loop. */
@Composable
internal fun AnimatedEmojiMessageContent(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val media = message.media
    val file = media?.file?.let { snapshot.mediaFiles[it.id] ?: it }
    val thumbnail = media?.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    val path = file?.localPath?.takeIf { file.downloaded }
    val thumbnailPath = thumbnail?.localPath?.takeIf { thumbnail.downloaded }
    val error = file?.let { snapshot.fileErrors[it.id] }
    val busy = file?.let { it.downloading || it.id in snapshot.downloadingFiles } == true
    val outline = file?.let { snapshot.stickerOutlines[it.id] }
    val drawableOutline = remember(outline) {
        outline?.takeIf { it.width > 0 && it.height > 0 && it.svgPath.isNotBlank() && it.svgPath.length <= 65_536 }
            ?.takeIf { runCatching { !PathParser().parsePathString(it.svgPath).toPath().isEmpty }.getOrDefault(false) }
    }
    val regularEmoji = media?.animatedEmoji?.isRegularEmoji != false
    var failed by remember(snapshot.token, file?.id, path, media?.format, media?.animatedEmoji?.fitzpatrickType) {
        mutableStateOf(false)
    }
    var replay by remember(snapshot.token, message.id, file?.id) { mutableLongStateOf(0L) }
    val interaction = remember(snapshot.token, message.id) { MutableInteractionSource() }
    MediaArtworkVisibility(snapshot, features, file, visible && active)
    LaunchedEffect(snapshot.token, file?.id, file?.downloaded, file?.canDownload, busy, error, outline, visible, active) {
        if (visible && active && file != null && file.id > 0) {
            if (outline == null) features.loadStickerOutline(snapshot.token, file.id)
        }
    }
    val fallback: @Composable () -> Unit = {
        // LargeEmoji's regular glyph is 36dp inside a 38dp static canvas. Keep the
        // actual native string, including variation selectors and skin modifiers.
        FittedAnimatedEmojiFallback(message.text)
    }
    Box(modifier.clickable(enabled = visible && active && (regularEmoji || error != null), interactionSource = interaction,
        indication = null, onClickLabel = "重播动画表情", onClick = {
            if (error != null && !busy && file?.canDownload == true) features.downloadFile(snapshot.token, file.id)
            else if (regularEmoji) replay++
        }).semantics { contentDescription = "独立表情画布" }, contentAlignment = Alignment.Center) {
        key(snapshot.token, message.id, file?.id) { when {
            media == null || failed || error != null -> fallback()
            path != null -> MediaPreview(path, media.format, Modifier.fillMaxSize(),
                contentDescription = "动画表情 ${message.text}", animate = visible && active,
                placeholder = fallback, contentScale = ContentScale.Fit,
                onLoadResult = { failed = !it }, playOnce = regularEmoji, replayKey = replay,
                fitzpatrickType = media.animatedEmoji?.fitzpatrickType ?: 0, alignTopStart = !regularEmoji)
            thumbnailPath != null -> MediaPreview(thumbnailPath, media.thumbnailFormat ?: MediaFormat.PHOTO,
                Modifier.fillMaxSize(), contentDescription = "动画表情缩略图", animate = false,
                placeholder = fallback, contentScale = ContentScale.Fit, onLoadResult = { failed = !it },
                alignTopStart = !regularEmoji)
            drawableOutline != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val available = constraints.maxDpSize(LocalDensity.current)
                    val factor = minOf(available.width.value / drawableOutline.width,
                        available.height.value / drawableOutline.height)
                    StickerLoadingOutline(drawableOutline, animate = visible && active,
                        modifier = Modifier.align(if (regularEmoji) Alignment.Center else Alignment.TopStart)
                            .size((drawableOutline.width * factor).dp, (drawableOutline.height * factor).dp))
                }
            else -> fallback()
        } }
    }
}

/** Measure the actual fallback font without clipping, then fit its complete natural line. */
@Composable
internal fun FittedAnimatedEmojiFallback(text: String, modifier: Modifier = Modifier) {
    Layout(modifier = modifier.fillMaxSize(), content = {
        Text(text, fontSize = 36.sp, lineHeight = 38.sp, maxLines = 1,
            softWrap = false, overflow = TextOverflow.Visible)
    }) { measurables, constraints ->
        val glyph = measurables.single().measure(Constraints())
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else glyph.width
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else glyph.height
        // The official static image reserves one logical pixel for its outline. This
        // also keeps antialiased fallback ink inside the canvas at fractional density.
        val margin = 1.dp.toPx()
        val scale = minOf(1f, (width - 2 * margin).coerceAtLeast(1f) / glyph.width.coerceAtLeast(1),
            (height - 2 * margin).coerceAtLeast(1f) / glyph.height.coerceAtLeast(1))
        layout(width, height) {
            glyph.placeWithLayer(((width - glyph.width * scale) / 2).toInt(),
                ((height - glyph.height * scale) / 2).toInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
