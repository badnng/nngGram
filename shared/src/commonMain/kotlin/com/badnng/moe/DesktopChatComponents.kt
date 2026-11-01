package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.senderAvatar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkspaceDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.65f)))
}

@Composable
internal fun WorkspaceActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val compact = LocalDesktopChatLayout.current.compact
    val size = if (compact) 36.dp else 40.dp
    IconButton(onClick, modifier.size(size).clip(CircleShape), enabled = enabled, minWidth = size, minHeight = size,
        backgroundColor = Color.Transparent, cornerRadius = size / 2) {
        Icon(icon, label, Modifier.size(if (compact) 20.dp else 23.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary
            else MiuixTheme.colorScheme.disabledOnSurface)
    }
}

@Composable
internal fun ChatIdentityAvatar(
    chat: ChatItem,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    visible: Boolean = true,
) {
    val avatar = chat.avatar
    if (avatar != null) {
        RemoteMedia(MessageMedia(avatar), snapshot, features, visible = visible,
            animate = false, compact = true, modifier = modifier.size(size).clip(CircleShape), cornerRadius = 0.dp,
            retainWhenInactive = true, contentScale = ContentScale.Crop, highQuality = true,
            placeholder = { IdentityAvatarPlaceholder(chat.id, chat.title, size) })
    } else {
        IdentityAvatarPlaceholder(chat.id, chat.title, size, modifier)
    }
}

@Composable
internal fun SenderIdentityAvatar(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 33.dp,
) {
    val label = snapshot.senderLabel(message)
    val avatar = snapshot.senderAvatar(message)
    if (avatar != null) {
        RemoteMedia(MessageMedia(avatar), snapshot, features, visible = visible,
            animate = false, compact = true, modifier = modifier.size(size).clip(CircleShape), cornerRadius = 0.dp,
            retainWhenInactive = true, contentScale = ContentScale.Crop, highQuality = true,
            placeholder = { IdentityAvatarPlaceholder(message.senderId, label, size) })
    } else {
        IdentityAvatarPlaceholder(message.senderId, label, size, modifier)
    }
}

@Composable
internal fun IdentityAvatarPlaceholder(
    id: Long,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = listOf(Color(0xFF3482FF), Color(0xFF8D79BF), Color(0xFF61A7B4),
        Color(0xFFD6A36D), Color(0xFF7BA78C), Color(0xFFBA8E97), Color(0xFF87A4C6))
    val color = palette[((id xor (id ushr 32)).toInt() and Int.MAX_VALUE) % palette.size]
    Box(modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(label.trim().firstOrNull()?.uppercase() ?: "聊", fontSize = (size.value * 0.37f).sp,
            fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

internal expect fun messageClock(epochSeconds: Int): String
internal expect fun messageDayLabel(epochSeconds: Int): String
internal expect fun chatListTime(epochSeconds: Int): String
