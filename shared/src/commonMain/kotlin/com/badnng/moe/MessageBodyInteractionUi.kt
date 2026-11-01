package com.badnng.moe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class MessageBodyLinkHandler(val open: (MessageEntity) -> Boolean)
internal val LocalMessageBodyLinks = staticCompositionLocalOf<MessageBodyLinkHandler?> { null }

private data class MessageBodyLinkConfirmation(val request: MessageTextLinkRequest, val url: String) {
    override fun toString() = "MessageBodyLinkConfirmation(redacted)"
}

/** Page ownership remains live while this dialog's backdrop suspends timeline activity. */
@Composable
internal fun MessageBodyInteractionProvider(
    message: ChatMessage,
    snapshot: ChatSnapshot,
    pageInteractive: Boolean,
    currentSnapshot: () -> ChatSnapshot = { snapshot },
    openLink: (String) -> Boolean = ::openExternalChatLink,
    content: @Composable () -> Unit,
) {
    val source = message.richText()
    val token = snapshot.token
    val latestSnapshot by rememberUpdatedState(currentSnapshot)
    val latestInteractive by rememberUpdatedState(pageInteractive)
    val latestOpenLink by rememberUpdatedState(openLink)
    val window = LocalWindowInfo.current
    var pending by remember(message.chatId, message.presentationId) { mutableStateOf<MessageBodyLinkConfirmation?>(null) }
    var requested by remember(message.chatId, message.presentationId) { mutableStateOf(false) }
    var failed by remember(message.chatId, message.presentationId) { mutableStateOf(false) }
    DisposableEffect(message.chatId, message.presentationId) { onDispose { requested = false } }
    val handler = remember(token, message.chatId, message.id, source) {
        MessageBodyLinkHandler { entity ->
            val originalEntity = source.originalLinkEntity(entity) ?: entity
            val request = MessageTextLinkRequest(token, message.chatId, message.id, source, originalEntity)
            val current = latestSnapshot()
            val url = validatedMessageTextLink(current, request, latestInteractive)
            when {
                url == null -> true // An outgoing page or a stale native source has no action.
                messageLinkRequiresConfirmation(source, request.entity, url, window.keyboardModifiers.isCtrlPressed,
                    current.linkConfirmationDomains ?: OFFICIAL_DEFAULT_LINK_DOMAINS) -> {
                    pending = MessageBodyLinkConfirmation(request, url)
                    failed = false
                    requested = true
                    true
                }
                else -> latestOpenLink(url)
            }
        }
    }
    CompositionLocalProvider(LocalMessageBodyLinks provides handler, content = content)
    val currentUrl = pending?.let { validatedMessageTextLink(snapshot, it.request, pageInteractive) }
    val dialogShown = requested && currentUrl != null
    LaunchedEffect(requested, currentUrl) { if (requested && currentUrl == null) requested = false }
    if (pending != null) ContextMenuBackdropRegistration(dialogShown)
    val colors = MiuixTheme.colorScheme
    // Keep the Miuix overlay mounted through its own exit, as for native URL keyboard buttons.
    OverlayDialog(show = dialogShown, title = "打开链接", maxWidth = 480.dp,
        onDismissRequest = { requested = false },
        onDismissFinished = { if (!requested) pending = null }) {
        pending?.let { confirmation ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请核对以下实际地址：", fontSize = 13.sp, color = colors.onSurface)
                SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text(confirmation.url, fontSize = 13.sp, color = colors.onSurface, modifier = Modifier.fillMaxWidth())
                }
                if (messageUrlIsSuspicious(confirmation.url)) Text("此地址的域名包含易混淆字符，请核对实际地址。",
                    fontSize = 12.sp, color = colors.error)
                if (failed) Text("无法打开链接，请检查默认浏览器设置。", fontSize = 12.sp, color = colors.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton("取消", enabled = dialogShown, onClick = { requested = false })
                    ChatButton("打开链接", primary = true, enabled = dialogShown, onClick = {
                        val url = validatedMessageTextLink(latestSnapshot(), confirmation.request, latestInteractive && requested)
                        if (url == null || url != confirmation.url) requested = false
                        else if (latestOpenLink(url)) requested = false
                        else failed = true
                    })
                }
            }
        }
    }
}
