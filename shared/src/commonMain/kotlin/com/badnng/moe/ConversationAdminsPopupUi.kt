package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Composed beside the actual administrator button so the shared popup can use its anchor. */
@Composable
internal fun ConversationAdminsPopup(
    show: Boolean,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    var mounted by remember(snapshot.token) { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentDismissFinished by rememberUpdatedState(onDismissFinished)
    SideEffect { if (show) mounted = true }
    DisposableEffect(snapshot.token) {
        onDispose {
            // A responsive info pane or a changed conversation can remove its anchor
            // before the popup animation ends; never leave reading permanently suspended.
            if (mounted) {
                currentDismiss()
                currentDismissFinished()
            }
        }
    }
    ChatShortcutPopup(show = show, width = 300.dp, paneTitle = "管理员", surfaceOpacity = 0.94f,
        onDismissRequest = onDismissRequest, onDismissFinished = {
            mounted = false
            onDismissFinished()
        }) {
        val state = snapshot.details
        val details = state.details?.takeIf {
            it.chatId == snapshot.token.chatId && snapshot.selected?.id == it.chatId
        }
        val colors = MiuixTheme.colorScheme
        Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("管理员", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            when {
                state.loading -> AdminsHint("正在加载管理员……")
                state.error != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatErrorText(state.error)
                    AdminActionButton("重试", onClick = { features.loadChatDetails(snapshot.token) })
                }
                details == null -> AdminsHint("暂未取得管理员信息")
                details.administrators.isEmpty() -> AdminsHint("Telegram 未提供可显示的管理员")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(details.administrators, key = { it.userId }) { administrator ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ToolsUserAvatar(administrator.userId, administrator.name, snapshot, features)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(administrator.name.ifBlank { "用户" }, fontSize = 15.sp, color = colors.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((if (administrator.owner) "创建者" else "管理员") +
                                    administrator.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = colors.onSurfaceSecondary)
                            }
                        }
                    }
                }
            }
            AdminActionButton("关闭", Modifier.fillMaxWidth(), onClick = onDismissRequest)
        }
    }
}

@Composable
private fun AdminsHint(text: String) {
    Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun AdminActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Button(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(color = colors.secondaryContainer.copy(alpha = 1f), contentColor = colors.onSurface),
        minHeight = 40.dp, minWidth = 0.dp, cornerRadius = 15.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
    }
}
