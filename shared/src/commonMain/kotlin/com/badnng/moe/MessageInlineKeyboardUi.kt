package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MessageInlineButtonClick
import com.badnng.moe.chat.MessageInlineButtonKind
import com.badnng.moe.chat.externalUrl
import com.badnng.moe.chat.inlineMessageButtonUrl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private data class InlineLinkConfirmation(val token: ChatToken, val click: MessageInlineButtonClick, val url: String) {
    override fun toString() = "InlineLinkConfirmation(redacted)"
}

/** Native rows below their message, following ReplyKeyboard's 36 dp buttons and 2 dp gaps. */
@Composable
internal fun MessageInlineKeyboard(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    currentSnapshot: () -> ChatSnapshot = { snapshot },
    openLink: (String) -> Boolean = ::openExternalChatLink,
) {
    val keyboard = message.inlineKeyboard
    val token = snapshot.token
    val colors = MiuixTheme.colorScheme
    val latestSnapshot by rememberUpdatedState(currentSnapshot)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOpenLink by rememberUpdatedState(openLink)
    var pending by remember(message.chatId, message.presentationId) { mutableStateOf<InlineLinkConfirmation?>(null) }
    var requested by remember(message.chatId, message.presentationId) { mutableStateOf(false) }
    var failed by remember(message.chatId, message.presentationId) { mutableStateOf(false) }
    DisposableEffect(message.chatId, message.presentationId) {
        onDispose { requested = false }
    }
    val currentUrl = pending?.let { inlineMessageButtonUrl(snapshot, it.token, it.click, enabled) }
    val dialogShown = requested && currentUrl != null
    LaunchedEffect(requested, currentUrl) {
        if (requested && currentUrl == null) requested = false
    }
    if (keyboard != null && keyboard.rows.any { it.isNotEmpty() }) Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keyboard.rows.forEachIndexed { rowIndex, row ->
            if (row.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { columnIndex, button ->
                    val available = enabled && button.externalUrl() != null
                    val foreground = if (available) colors.onSurface else colors.disabledOnSurface
                    Box(Modifier.weight(1f).heightIn(min = 36.dp)
                        .chatSquircleSurface(colors.surfaceContainer, 15.dp)
                        .clickable(enabled = available, role = Role.Button) {
                            val click = MessageInlineButtonClick(message.chatId, message.id, rowIndex, columnIndex, keyboard)
                            val url = inlineMessageButtonUrl(latestSnapshot(), token, click, latestEnabled)
                            if (url != null) {
                                failed = false
                                pending = InlineLinkConfirmation(token, click, url)
                                requested = true
                            }
                        }.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(button.text, modifier = Modifier.fillMaxWidth().padding(end = if (button.kind == MessageInlineButtonKind.URL) 10.dp else 0.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = foreground,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (button.kind == MessageInlineButtonKind.URL) Text("↗", Modifier.align(Alignment.TopEnd), fontSize = 10.sp, color = foreground)
                    }
                }
            }
        }
    }
    // The Miuix overlay must observe show=false and finish its own exit. Removing the
    // dialog composition while it is shown leaves retained root-scaffold content alive.
    if (pending != null) ContextMenuBackdropRegistration(dialogShown)
    OverlayDialog(show = dialogShown, title = "打开链接", maxWidth = 480.dp,
        onDismissRequest = { requested = false },
        onDismissFinished = { if (!requested) pending = null }) {
        pending?.let { confirmation ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将使用默认浏览器打开以下地址：", fontSize = 13.sp, color = colors.onSurface)
                Text(confirmation.url, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.fillMaxWidth())
                if (failed) Text("无法打开链接，请检查默认浏览器设置。", fontSize = 12.sp, color = colors.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("取消", enabled = dialogShown, onClick = { requested = false })
                    ChatButton("打开链接", primary = true, enabled = dialogShown, onClick = {
                        val current = inlineMessageButtonUrl(latestSnapshot(), confirmation.token, confirmation.click,
                            latestEnabled && requested)
                        if (current == null) requested = false
                        else if (latestOpenLink(current)) requested = false
                        else failed = true
                    })
                }
            }
        }
    }
}
