package com.badnng.moe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The nested popup is anchored to the real menu row, including when its parent popup moves. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun MessageReadersMenuRow(
    messageId: Long,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions?,
    expanded: Boolean,
    onClick: () -> Unit,
    onHover: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val readers = snapshot.readDetails?.takeIf {
        snapshot.readDetailsMessageId == messageId && it.availability == MessageReadAvailability.AVAILABLE
    }?.readers
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).chatSquircleSurface(colors.surfaceContainer, 15.dp)
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .clickable(role = Role.Button, onClickLabel = "查看已读成员", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ReaderDoubleCheck(Modifier.size(19.dp, 16.dp))
            Text(readers?.let { "${it.size} 人已读" } ?: "谁已读", fontSize = 13.sp,
                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (features != null && readers != null) NativeAvatarStack(readers.mapNotNull { it.avatar }, snapshot, features)
        }
        if (expanded) MessageReadersFlyout(messageId, snapshot, features, onRetry, onDismiss)
    }
}

@Composable
private fun MessageReadersFlyout(
    messageId: Long,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = snapshot.readDetailsMessageId == messageId
    val loading = !current || snapshot.readDetailsLoading
    val details = snapshot.readDetails.takeIf { current }
    val error = snapshot.readDetailsError.takeIf { current }
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val margin = with(density) { 8.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val menuInset = with(density) { 6.dp.roundToPx() }
    val shadowPadding = with(density) { ChatShortcutShadowPadding.roundToPx() }
    val width = if (viewport.width > 0) with(density) { (viewport.width - shadowPadding * 2).coerceAtLeast(1).toDp() }.coerceAtMost(252.dp) else 252.dp
    val maxHeight = if (viewport.height > 0) with(density) { (viewport.height - shadowPadding * 2).coerceAtLeast(1).toDp() }.coerceAtMost(360.dp) else 360.dp
    val position = remember(margin, gap, menuInset, shadowPadding) {
        ChatShortcutPopupPosition(MessageReadersPosition(margin, gap, menuInset), shadowPadding)
    }
    val colors = MiuixTheme.colorScheme
    val appearing = remember(snapshot.token, messageId) { MutableTransitionState(false) }
    LaunchedEffect(appearing) { appearing.targetState = true }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true)) {
        AnimatedVisibility(visibleState = appearing,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f))) {
            ChatShortcutShadowSpace {
            Column(Modifier.width(width).heightIn(max = maxHeight).chatShortcutShadow()
                .chatSquircleSurface(colors.surfaceContainer, 15.dp).padding(4.dp)) {
                when {
                    loading -> ReaderStatus("正在查询读者……")
                    error != null -> {
                        ReaderStatus(when (error) {
                            ChatError.FORBIDDEN -> "Telegram 未允许查看此消息的读者。"
                            ChatError.UNSUPPORTED -> "此消息不支持查询读者。"
                            else -> "读者信息获取失败。"
                        })
                        Text("重试", fontSize = 12.sp, color = colors.primary,
                            modifier = Modifier.fillMaxWidth().chatSquircleSurface(colors.surfaceContainer, 15.dp)
                                .clickable(role = Role.Button, onClick = onRetry).padding(10.dp))
                    }
                    details?.availability == MessageReadAvailability.AVAILABLE -> {
                        if (details.readers.isEmpty()) ReaderStatus("Telegram 暂未返回已读成员。")
                        else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(details.readers, key = { it.userId }) { reader ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 45.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (features != null && reader.avatar != null) NativeAvatarStack(listOf(reader.avatar), snapshot, features, size = 32.dp)
                                    else Spacer(Modifier.size(32.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(reader.name.ifBlank { "用户" }, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            ReaderDoubleCheck(Modifier.size(16.dp, 13.dp))
                                            Text(readerReadTime(reader.readDate), fontSize = 11.sp,
                                                color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> ReaderStatus(when (details?.availability) {
                        MessageReadAvailability.UNREAD -> "对方尚未阅读这条消息。"
                        MessageReadAvailability.TOO_OLD -> "这条消息已超过 Telegram 可查询已读时间的期限。"
                        MessageReadAvailability.USER_PRIVACY_RESTRICTED -> "对方的隐私设置不允许显示已读时间。"
                        MessageReadAvailability.MY_PRIVACY_RESTRICTED -> "你的已读时间隐私设置限制了查看对方的已读时间。"
                        else -> "Telegram 未提供这条消息的读者信息。"
                    })
                }
            }
            }
        }
    }
}

@Composable
private fun ReaderStatus(text: String) = Text(text, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp))

/** Fixed vector strokes cannot wrap like a pair of font-dependent check glyphs. */
@Composable
internal fun ReaderDoubleCheck(modifier: Modifier = Modifier) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier) {
        val stroke = 1.2.dp.toPx()
        fun check(start: Float) {
            drawLine(color, Offset(size.width * start, size.height * 0.53f),
                Offset(size.width * (start + 0.17f), size.height * 0.72f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * (start + 0.17f), size.height * 0.72f),
                Offset(size.width * (start + 0.54f), size.height * 0.27f), stroke, StrokeCap.Round)
        }
        check(0.04f)
        check(0.38f)
    }
}

internal expect fun readerReadTime(epochSeconds: Int): String

/** Popup coordinates are supplied by Compose for the menu row, not synthesized from message IDs. */
internal class MessageReadersPosition(private val margin: Int, private val gap: Int,
    private val anchorHorizontalInset: Int = 0) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // The anchor is the inner row, while the visible parent surface extends past it on both sides.
        val surfaceLeft = anchorBounds.left - anchorHorizontalInset
        val surfaceRight = anchorBounds.right + anchorHorizontalInset
        val right = surfaceRight + gap
        val left = surfaceLeft - gap - popupContentSize.width
        val rightFits = right + popupContentSize.width <= windowSize.width - margin
        val leftFits = left >= margin
        val preferredRight = layoutDirection == LayoutDirection.Ltr
        val x = when {
            preferredRight && rightFits -> right
            !preferredRight && leftFits -> left
            leftFits -> left
            rightFits -> right
            windowSize.width - surfaceRight >= surfaceLeft -> right
            else -> left
        }
        return IntOffset(x.coerceIn(margin, (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)),
            anchorBounds.top.coerceIn(margin, (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)))
    }
}
