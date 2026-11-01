package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Photo::needsBubble/draw: the image owns its edges; only surrounding text needs a surface. */
@Composable
internal fun PhotoMessageContent(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    size: MessageMediaSize,
    sender: String,
    displaySenderName: Boolean,
    visible: Boolean,
    active: Boolean,
    pageInteractive: Boolean,
    showImageInfo: Boolean,
    read: Boolean,
    retrying: Boolean,
    compactLayout: Boolean,
    selectionState: SelectionState,
    copyAllowed: Boolean,
    currentSnapshot: () -> ChatSnapshot,
    onOpenLink: (String) -> Boolean,
    onEnlarge: () -> Unit,
    onJumpToReply: (MessageReply) -> Unit,
    onRetry: () -> Unit,
) {
    val media = checkNotNull(message.media)
    val content = message.richText()
    val hasCaption = content.text.isNotEmpty()
    val captionAbove = hasCaption && message.showCaptionAboveMedia
    val hasHeader = displaySenderName || message.forwardInfo != null || message.replyTo != null
    val hasBubble = hasCaption || hasHeader
    val infoOnImage = !hasCaption || captionAbove
    // Media::adjustedBubbleRounding: only the exposed ends of the whole bubble
    // are round. A header/caption joins the image with a straight, flush edge.
    val exposedImageRadius = if (hasBubble) 15.dp else 12.dp
    val imageTopRadius = if (hasHeader || captionAbove) 0.dp else exposedImageRadius
    val imageBottomRadius = if (hasCaption && !captionAbove) 0.dp else exposedImageRadius
    val colors = MiuixTheme.colorScheme
    val surface = if (message.outgoing) lerp(colors.surfaceContainer, colors.primary, .1f)
        else colors.surfaceContainer
    val body: @Composable ((TextLayoutResult) -> Unit) -> Unit = { reportLayout ->
        MessageBodyInteractionProvider(message, snapshot, pageInteractive, currentSnapshot, onOpenLink) {
            InlineMessageEmojiProvider(snapshot, features, visible, active = active) {
                RichMessageBody(content, selectionState = selectionState, allowCopy = copyAllowed,
                    canCopyNow = {
                        val latest = currentSnapshot()
                        val source = latest.messages.firstOrNull { it.id == message.id && it.chatId == message.chatId }
                        latest.token == snapshot.token && latest.selected?.id == message.chatId &&
                            latest.selected?.hasProtectedContent == false && source?.canBeSaved == true && source.richText() == content
                    }, onTextLayout = reportLayout)
            }
        }
    }
    val metadata: @Composable (Boolean) -> Unit = { onImage ->
        FlowRow(
            modifier = if (onImage) Modifier.chatSquircleBackground(Color.Black.copy(alpha = .55f), 6.dp)
                .padding(horizontal = 4.dp, vertical = 1.dp) else Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(messageClock(message.date), style = chatMessageTextStyle(),
                color = if (onImage) Color.White else colors.onSurfaceVariantActions)
            if (message.editDate > 0) Text("已编辑", style = chatMessageTextStyle(),
                color = if (onImage) Color.White else colors.onSurfaceVariantSummary)
            if (message.outgoing) OutgoingDeliveryIndicator(message, read, retrying, compactLayout,
                foreground = if (onImage) Color.White else null)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().then(if (hasBubble) Modifier.chatSquircleBackground(surface, 15.dp) else Modifier)) {
            if (hasHeader) Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (displaySenderName) Text(sender,
                    style = chatMessageTextStyle().copy(fontWeight = FontWeight.SemiBold),
                    color = peerSenderNameColor(message, snapshot), maxLines = 1, overflow = TextOverflow.Ellipsis)
                message.forwardInfo?.let { ForwardedMessageHeader(it, snapshot) }
                message.replyTo?.let { ReplyReference(it, snapshot, onJump = onJumpToReply, active = visible && active && pageInteractive) }
            }
            if (captionAbove) Box(Modifier.padding(start = 11.dp, end = 11.dp,
                top = if (hasHeader) 0.dp else 8.dp, bottom = 5.dp)) { body { } }
            Box(Modifier.size(size.width.dp, size.height.dp).squircleClip(
                topStart = imageTopRadius, topEnd = imageTopRadius,
                bottomEnd = imageBottomRadius, bottomStart = imageBottomRadius)) {
                RemoteMedia(media, snapshot, features, visible, active,
                    modifier = Modifier.fillMaxSize(), onClick = {
                        if (!media.file.downloaded) features.downloadFile(snapshot.token, media.file.id)
                        onEnlarge()
                    }, photoBackground = true, messageId = message.id,
                    cornerRadius = 0.dp,
                    contentScale = if (photoResizeExpands(media.width, media.height, size.width, size.height))
                        ContentScale.Crop else ContentScale.Fit)
                if (infoOnImage && showImageInfo) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { metadata(true) }
            }
            if (hasCaption && !captionAbove) Box(Modifier.padding(start = 11.dp, end = 11.dp, top = 5.dp, bottom = 8.dp)) {
                MessageTextFooterLayout(
                    canInline = message.reactions.isEmpty() && content.entities.none {
                        it.kind == MessageEntityKind.PRE || it.kind == MessageEntityKind.BLOCK_QUOTE ||
                            it.kind == MessageEntityKind.EXPANDABLE_BLOCK_QUOTE
                    },
                    layoutKey = content, body = body,
                    footer = { MessageReactionRow(message, snapshot, features, active = visible && active,
                        metadata = { metadata(false) }) },
                )
            }
        }
        // Photo customInfoLayout keeps these outside even when a header/above-caption needs a bubble.
        if (infoOnImage && (message.reactions.isNotEmpty() || snapshot.reactionErrorMessageId == message.id)) {
            Box(Modifier.fillMaxWidth().padding(top = 5.dp),
                contentAlignment = if (message.outgoing) Alignment.TopEnd else Alignment.TopStart) {
                MessageReactionRow(message, snapshot, features, active = visible && active,
                    fillWidth = false, alignRight = message.outgoing)
            }
        }
        if (message.delivery == MessageDelivery.FAILED) Column(Modifier.padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (message.canRetry) {
                if (message.retryAfterSeconds > 0) Text("Telegram 要求等待至少 ${message.retryAfterSeconds} 秒后重试。",
                    fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
                ChatButton(if (retrying) "重试中……" else "重试发送", enabled = !retrying, onClick = onRetry)
            } else Text("此消息无法直接重试，请使用官方客户端查看详情。", fontSize = 11.sp,
                color = colors.onSurfaceVariantSummary)
        }
    }
}
