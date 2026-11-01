package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.autoPlayAllowed
import com.badnng.moe.media.ownedMediaRangeReader
import com.badnng.moe.media.AnimationPlayback
import com.badnng.moe.media.MediaPreview
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Telegram Gif::countThumbSize/countCurrentSize: 320 dp cap and 100 dp minimum media canvas. */
internal fun animationMessageSize(sourceWidth: Int, sourceHeight: Int, availableWidth: Float = 320f): MessageMediaSize {
    val available = availableWidth.coerceAtLeast(1f)
    val size = messageMediaSize(sourceWidth, sourceHeight, availableWidth = available, maxEdge = 320f)
    val minimum = minOf(100f, available)
    return MessageMediaSize(size.width.coerceAtLeast(minimum), size.height.coerceAtLeast(minimum))
}

/** Each real animation owns its own silent loop; it does not compete for the audible video session. */
@Composable
internal fun AnimationMessageContent(
    media: MessageMedia,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    messageId: Long,
    visible: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val file = snapshot.mediaFiles[media.file.id] ?: media.file
    val thumbnail = media.thumbnail?.let { snapshot.mediaFiles[it.id] ?: it }
    val path = file.localPath?.takeIf { file.downloaded }
    val size = file.size
    val busy = file.downloading || file.id in snapshot.downloadingFiles
    val error = snapshot.fileErrors[file.id]
    val spoiler = rememberMediaSpoilerState(Triple(snapshot.token, messageId, media.file.id), media.hasSpoiler, visible && active)
    val revealed = spoiler.revealed
    var requested by remember(snapshot.token, messageId, media.file.id) { mutableStateOf(false) }
    val message = snapshot.messages.firstOrNull { it.id == messageId && it.chatId == snapshot.token.chatId }
    val mayAutoPlay = message?.let(snapshot::autoPlayAllowed) == true
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val playbackActive = visible && active && windowFocused && revealed && (mayAutoPlay || requested)
    AutomaticMediaVisibility(features, snapshot.token, messageId, visible && active && revealed)
    MediaArtworkVisibility(snapshot, features, thumbnail, playbackActive)
    val canStream = file.canDownload || busy
    val click = if (!revealed) Modifier.clickable(enabled = active, onClickLabel = "显示 GIF") { spoiler.reveal() }
        else Modifier
    Box(modifier.chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 10.dp)
        .chatSquircleClip(10.dp).then(click), contentAlignment = Alignment.Center) {
        if (revealed) {
            val placeholder: @Composable () -> Unit = {
                thumbnail?.localPath?.takeIf { thumbnail.downloaded }?.let { thumbnailPath ->
                    MediaPreview(thumbnailPath, media.thumbnailFormat ?: MediaFormat.PHOTO,
                        modifier = Modifier.fillMaxSize(), contentDescription = "GIF 缩略图", animate = false)
                }
            }
            if (path != null || canStream) key(snapshot.token, messageId, media.file.id) {
                AnimationPlayback(path.orEmpty(), active = playbackActive, modifier = Modifier.fillMaxSize(),
                    streamSize = size, placeholder = placeholder,
                    readerFactory = { ownedMediaRangeReader(features, snapshot.token, messageId, file.id) })
            } else {
                placeholder()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (error == null) "GIF 暂时不可用" else "GIF 加载失败", fontSize = 12.sp)
                    if (file.canDownload && !busy) ChatButton("重试", enabled = active, onClick = {
                        requested = true
                    })
                }
            }
            if (!mayAutoPlay && !requested) ChatButton("播放 GIF", enabled = visible && active, onClick = { requested = true })
            Text("GIF", color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .chatSquircleBackground(Color.Black.copy(alpha = 0.4f), 5.dp).padding(horizontal = 5.dp, vertical = 2.dp))
        }
        if (media.hasSpoiler) MediaSpoilerOverlay(media, snapshot, features, spoiler, visible, active,
            10.dp, Modifier.fillMaxSize())
    }
}
