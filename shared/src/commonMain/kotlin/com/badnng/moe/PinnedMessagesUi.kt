package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PinnedMessageBar(snapshot: ChatSnapshot, features: ChatFeatureActions, onOpenList: () -> Unit,
    modifier: Modifier = Modifier) {
    val pins = snapshot.pinned.messages
    var index by remember(snapshot.token) { mutableStateOf(0) }
    val message = pins.getOrNull(index.coerceIn(0, (pins.size - 1).coerceAtLeast(0))) ?: return
    val colors = MiuixTheme.colorScheme
    Row(modifier.fillMaxWidth().chatGlassSurface(15.dp).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(32.dp).chatSquircleBackground(colors.primary, 2.dp))
        // Keep both lines' glyphs clear of the feedback surface's curved clip.
        Column(Modifier.weight(1f).chatSquircleSurface(Color.Transparent, 15.dp).clickable(role = Role.Button, onClickLabel = "跳转置顶消息") {
            features.openPinnedMessage(snapshot.token, message.id)
            if (pins.size > 1) index = (index + 1) % pins.size
        }.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(if (snapshot.pinned.totalCount > 1) "已置顶消息 · ${snapshot.pinned.totalCount}" else "已置顶消息",
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = colors.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(messagePreview(message), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "查看全部置顶消息", onClick = onOpenList),
            contentAlignment = Alignment.Center) {
            Icon(MiuixIcons.Regular.Pin, "查看全部置顶消息", Modifier.size(20.dp), tint = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun PinnedMessagesDialog(snapshot: ChatSnapshot, features: ChatFeatureActions, onDismiss: () -> Unit) {
    WindowDialog(true, title = "置顶消息", maxWidth = 520.dp, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(snapshot.pinned.messages, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .chatSquircleSurface(MiuixTheme.colorScheme.secondaryContainer, 15.dp)
                        .clickable(role = Role.Button) { features.openPinnedMessage(snapshot.token, message.id); onDismiss() }
                        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(snapshot.senderLabel(message), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        Text(messagePreview(message), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(chatListTime(message.date), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (snapshot.pinned.loading) Text("正在加载……", fontSize = 13.sp)
            if (!snapshot.pinned.loading && snapshot.pinned.messages.isEmpty() && snapshot.pinned.error == null)
                Text("没有置顶消息", fontSize = 14.sp)
            snapshot.pinned.error?.let { ChatErrorText(it) }
            if (snapshot.pinned.nextFromMessageId > 0 || snapshot.pinned.error != null) {
                ChatButton(if (snapshot.pinned.error != null) "重试" else "加载更多", Modifier.fillMaxWidth(),
                    enabled = !snapshot.pinned.loading, onClick = { features.loadPinned(snapshot.token, snapshot.pinned.error == null) })
            }
            ChatButton("关闭", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
