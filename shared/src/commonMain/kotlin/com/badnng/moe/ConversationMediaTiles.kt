package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Tiles download only native thumbnails, reuse completed photos, and never start video playback. */
@Composable
internal fun ConversationMediaTileRow(
    messages: List<ChatMessage>,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    columns: Int = 3,
    onSelect: (ChatMessage) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        messages.forEach { message ->
            ConversationMediaTile(message, snapshot, features, Modifier.weight(1f)) { onSelect(message) }
        }
        repeat((columns - messages.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ConversationMediaTile(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val media = message.media
    val colors = MiuixTheme.colorScheme
    val video = message.kind == MessageKind.VIDEO
    val preview = conversationMediaPreview(media, snapshot.mediaFiles)
    val hover = remember(message.id) { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.aspectRatio(1f).hoverable(hover)
        .semantics { contentDescription = (if (video) "视频" else "图片") + "，" + chatListTime(message.date) + "，查看原消息" },
        contentAlignment = Alignment.Center) {
        if (preview != null) RemoteMedia(preview, snapshot, features, visible = true, animate = false,
            modifier = Modifier.fillMaxSize(), compact = true, cornerRadius = 15.dp, onClick = onClick,
            placeholder = { Text(if (video) "视频" else "图片", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
            contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().chatSquircleSurface(colors.secondaryContainer, 15.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(if (video) "视频" else "图片", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (video && media?.hasSpoiler != true) Row(Modifier.align(Alignment.BottomStart).padding(4.dp)
            .chatSquircleBackground(colors.surface.copy(alpha = 0.90f), 8.dp).padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Regular.Play, null, Modifier.size(12.dp), tint = colors.onSurface)
            val seconds = media?.durationSeconds ?: 0
            Text(if (seconds > 0) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "视频",
                fontSize = 10.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hovered) Text("查看原消息", fontSize = 10.sp, maxLines = 1, color = colors.onSurface,
            modifier = Modifier.align(Alignment.TopCenter).padding(4.dp)
                .chatSquircleBackground(colors.surface.copy(alpha = 0.90f), 8.dp).padding(horizontal = 5.dp, vertical = 3.dp))
    }
}

/** A missing native thumbnail must not cause an entire attachment to download in the index. */
internal fun conversationMediaPreview(media: MessageMedia?, files: Map<Int, MediaFile>): MessageMedia? = when {
    media == null -> null
    media.format == MediaFormat.PHOTO && (files[media.file.id] ?: media.file).let { it.downloaded && it.localPath != null } -> media
    media.thumbnail != null -> MessageMedia(media.thumbnail, format = media.thumbnailFormat ?: MediaFormat.PHOTO,
        hasSpoiler = media.hasSpoiler)
    else -> null
}
