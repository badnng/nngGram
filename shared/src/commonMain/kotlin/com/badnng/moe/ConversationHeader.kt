package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Only the capsule paints a surface; the timeline remains visible around it. */
@Composable
internal fun ConversationHeader(
    chat: ChatItem,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    active: Boolean,
    onBack: (() -> Unit)?,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onMarkRead: () -> Unit,
    onBounds: (Rect) -> Unit,
    onSearch: () -> Unit,
    onDetails: () -> Unit,
    onMedia: () -> Unit,
    showInfoButton: Boolean,
    onPinned: () -> Unit,
    onTogglePinned: () -> Unit,
    pinnedHidden: Boolean,
    onMenuDismissFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    adminsPopup: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val available = constraints.maxDpSize(LocalDensity.current)
        val height = 64.dp
        val width = conversationHeaderWidth(available.width)
        val narrow = width < 440.dp
        FloatingToolbar(
            modifier = Modifier.width(width).onGloballyPositioned { onBounds(it.boundsInWindow(clipBounds = false)) }
                .chatGlassSurface(height / 2),
            color = Color.Transparent,
            cornerRadius = height / 2,
            outSidePadding = PaddingValues(0.dp),
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = height)
                    .padding(horizontal = if (narrow) 8.dp else 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onBack?.let { HeaderAction(MiuixIcons.Regular.Back, "返回聊天列表", it) }
                ChatIdentityAvatar(chat, snapshot, features, size = if (narrow) 32.dp else 36.dp, visible = active)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chat.title.ifBlank { "未命名聊天" }, fontSize = 18.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ConversationHeaderStatus(chat, snapshot)
                }
                HeaderAction(MiuixIcons.Regular.Search, "在此群搜索内容", onSearch)
                if (chat.privateUserId == null) Box {
                    HeaderAction(MiuixIcons.Regular.Contacts, "管理员", onDetails)
                    adminsPopup()
                }
                if (showInfoButton) HeaderAction(MiuixIcons.Regular.Sidebar, "展开群信息", onMedia)
                Box {
                    HeaderAction(MiuixIcons.Regular.More, "群操作", { onMenuOpen(!menuOpen) })
                    ChatShortcutPopup(show = menuOpen, onDismissRequest = { onMenuOpen(false) },
                        onDismissFinished = onMenuDismissFinished) {
                        ConversationOperationRow("查看群组信息", MiuixIcons.ContactsCircle) { onMenuOpen(false); onMedia() }
                        if (snapshot.pinned.totalCount > 0 || snapshot.pinned.error != null) {
                            ConversationOperationRow("查看置顶消息", MiuixIcons.Regular.Pin) { onMenuOpen(false); onPinned() }
                            ConversationOperationRow(if (pinnedHidden) "显示置顶消息" else "隐藏置顶消息",
                                if (pinnedHidden) MiuixIcons.Show else MiuixIcons.Hide) { onMenuOpen(false); onTogglePinned() }
                        }
                        ConversationOperationRow(if (snapshot.readPending) "标记中" else "标为已读", MiuixIcons.Regular.Messages,
                            enabled = !snapshot.readPending) { onMenuOpen(false); onMarkRead() }
                        ConversationOperationRow("标为未读", MiuixIcons.Regular.Messages, enabled = !snapshot.readPending) {
                            onMenuOpen(false); features.markUnread(snapshot.token)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationOperationRow(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.disabledOnSecondaryVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).chatSquircleSurface(Color.Transparent, 15.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, fontSize = 13.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ConversationHeaderStatus(chat: ChatItem, snapshot: ChatSnapshot) {
    val activity = snapshot.headerStatus.activity
    val colors = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (activity != null) {
            val icon = when (activity.kind) {
                ChatActivityKind.CHOOSING_STICKER -> MiuixIcons.Regular.GridView
                ChatActivityKind.UPLOADING_PHOTO, ChatActivityKind.UPLOADING_VIDEO,
                ChatActivityKind.UPLOADING_DOCUMENT -> MiuixIcons.Regular.UploadCloud
                else -> MiuixIcons.Regular.Edit
            }
            Icon(icon, null, Modifier.size(13.dp), tint = colors.primary)
        }
        Text(conversationHeaderSummary(chat, snapshot), fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (activity == null) colors.onSurfaceVariantSummary else colors.primary)
    }
}

internal fun conversationHeaderSummary(chat: ChatItem, snapshot: ChatSnapshot): String {
    val status = snapshot.headerStatus
    status.activity?.let { activity ->
        val sender = if (activity.senderIsChat) snapshot.knownChats.firstOrNull { it.id == activity.senderId }?.title
            else snapshot.senderNames[activity.senderId]
        val action = when (activity.kind) {
            ChatActivityKind.TYPING -> "正在输入"
            ChatActivityKind.UPLOADING_PHOTO -> "正在发送照片"
            ChatActivityKind.UPLOADING_VIDEO -> "正在发送视频"
            ChatActivityKind.UPLOADING_DOCUMENT -> "正在发送文件"
            ChatActivityKind.CHOOSING_STICKER -> "正在寻找贴纸"
        }
        return listOfNotNull(sender?.takeIf { it.isNotBlank() }, action).joinToString(" ")
    }
    return when {
        status.memberCount != null -> "${status.memberCount} 位成员" + (status.onlineCount?.let { "，$it 位在线" } ?: "")
        status.onlineCount != null -> "${status.onlineCount} 位在线"
        !chat.canSend -> "文字只读"
        chat.privateUserId != null -> "私聊"
        else -> "聊天"
    }
}
