package com.badnng.moe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

/** The ordinary composer shares the rich editor's text, selection and shortcut handling. */
@Composable
internal fun ConversationComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    canSend: Boolean,
    sendPending: Boolean,
    canSendPhotos: Boolean,
    canSendStickers: Boolean,
    canSendVideos: Boolean,
    canSendDocuments: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onEscape: (() -> Unit)?,
    onEditLast: () -> Unit,
    onPhoto: () -> Unit,
    onSticker: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuOpenChange: (Boolean) -> Unit = {},
    replyContent: (@Composable () -> Unit)? = null,
    canSendVoiceNotes: Boolean = canSend,
    onVoice: (() -> Unit)? = null,
    onStickerAnchorChanged: (Rect) -> Unit = {},
    onStickerHoverChanged: (Boolean) -> Unit = {},
    onVoicePress: (() -> Unit)? = null,
    onVoiceRelease: ((cancelled: Boolean) -> Unit)? = null,
    onVoiceLock: (() -> Unit)? = null,
    editing: Boolean = false,
    allowEmptySubmit: Boolean = false,
    interactive: Boolean = true,
) {
    val currentInteractive by rememberUpdatedState(interactive)
    val focusOwnerMounted = remember(focusRequester) { mutableStateOf(false) }
    DisposableEffect(focusOwnerMounted) {
        focusOwnerMounted.value = true
        onDispose { focusOwnerMounted.value = false }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var attachmentAnchor by remember { mutableStateOf<Rect?>(null) }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    val attachmentInteraction = remember { MutableInteractionSource() }
    val attachmentHovered by attachmentInteraction.collectIsHoveredAsState()
    var attachmentMenuHovered by remember { mutableStateOf(false) }
    val stickerInteraction = remember { MutableInteractionSource() }
    val stickerHovered by stickerInteraction.collectIsHoveredAsState()
    val currentMenuAction by rememberUpdatedState(onMenuOpenChange)
    DisposableEffect(menuOpen) {
        currentMenuAction(menuOpen)
        onDispose { if (menuOpen) currentMenuAction(false) }
    }
    val idle = interactive && !sendPending
    val canSubmit = canSend && idle && value.composition == null && (allowEmptySubmit || value.text.isNotBlank())
    val submit = {
        if (currentInteractive && focusOwnerMounted.value && canSubmit) {
            onSend()
            // HistoryWidget::sendTextWithTags calls setInnerFocus after submitting and
            // clearing the field. Do this at the input action, never at a late native result
            // that could steal focus from another control or a different conversation.
            if (currentInteractive && focusOwnerMounted.value) focusRequester.requestFocus()
        }
    }
    val menuEnabled = !editing && idle && (canSendPhotos || canSendVideos || canSendDocuments)
    LaunchedEffect(attachmentHovered, attachmentMenuHovered, menuEnabled) {
        if (!menuEnabled) menuOpen = false
        else if (attachmentHovered || attachmentMenuHovered) menuOpen = true
        else { delay(ComposerPanelHideDelayMillis); menuOpen = false }
    }
    val currentStickerHover by rememberUpdatedState(onStickerHoverChanged)
    LaunchedEffect(stickerHovered, idle, canSendStickers) {
        if (stickerHovered && idle && canSendStickers) menuOpen = false
        currentStickerHover(stickerHovered && idle && canSendStickers)
    }
    DisposableEffect(Unit) { onDispose { currentStickerHover(false) } }
    val colors = MiuixTheme.colorScheme
    RichTextEditor(
        value = value,
        onValueChange = { if (currentInteractive) onValueChange(automaticComposerFormatting(value, it)) },
        label = if (editing) "编辑消息…" else if (canSend) "发送消息…" else "文字只读",
        modifier = modifier.onGloballyPositioned { fieldBounds = it.boundsInWindow(clipBounds = false) },
        enabled = interactive && (canSend || canSendPhotos || canSendDocuments || canSendVideos || canSendStickers || canSendVoiceNotes),
        readOnly = !canSend || (editing && sendPending),
        maxLines = 6,
        focusRequester = focusRequester,
        onSubmit = submit.takeIf { canSend },
        canSubmit = canSubmit,
        allowEmptySubmit = allowEmptySubmit,
        onEscape = if (menuOpen || onEscape != null) ({
            if (menuOpen) menuOpen = false else onEscape?.invoke()
        }) else null,
        onEditLast = onEditLast.takeIf { canSend && idle && !editing },
        onAttach = onFile.takeIf { idle && canSendDocuments && !editing },
        compactControls = true,
        inlineControls = true,
        allowAttachmentPaste = idle && !editing,
        headerContent = replyContent,
        leadingControl = {
            Box(Modifier.padding(start = 6.dp, end = 6.dp)) {
                ComposerActionIcon(MiuixIcons.Regular.File, "附件", menuEnabled,
                    onClick = {
                        if (!currentInteractive) return@ComposerActionIcon
                        menuOpen = false
                        when {
                            canSendDocuments -> onFile()
                            canSendPhotos -> onPhoto()
                            canSendVideos -> onVideo()
                        }
                    }, modifier = Modifier.hoverable(attachmentInteraction)
                        .onGloballyPositioned { attachmentAnchor = it.boundsInWindow(clipBounds = false) })
                ComposerAttachmentMenu(menuOpen && interactive, attachmentAnchor, onDismiss = { menuOpen = false },
                    onHovered = { attachmentMenuHovered = it }) {
                    if (canSendPhotos) {
                        ComposerMenuItem("图片", MiuixIcons.Regular.Image, idle && canSendPhotos) {
                            if (!currentInteractive) return@ComposerMenuItem
                            menuOpen = false
                            onPhoto()
                        }
                    }
                    if (canSendVideos) {
                        ComposerMenuItem("视频", MiuixIcons.Regular.Play, idle && canSendVideos) {
                            if (!currentInteractive) return@ComposerMenuItem
                            menuOpen = false
                            onVideo()
                        }
                    }
                    if (canSendDocuments) {
                        ComposerMenuItem("文件", MiuixIcons.Regular.File, idle && canSendDocuments) {
                            if (!currentInteractive) return@ComposerMenuItem
                            menuOpen = false
                            onFile()
                        }
                    }
                }
            }
        },
        trailingControls = {
            Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ComposerActionIcon(MiuixIcons.Regular.GridView, "贴纸", idle && canSendStickers,
                    onClick = { if (currentInteractive) { menuOpen = false; onSticker() } },
                    modifier = Modifier.hoverable(stickerInteraction)
                        .onGloballyPositioned { onStickerAnchorChanged(it.boundsInWindow(clipBounds = false)) })
                if (editing) {
                    ComposerActionIcon(MiuixIcons.Regular.Ok, if (sendPending) "保存中" else "保存修改",
                        enabled = canSubmit, onClick = submit, color = colors.primary,
                        modifier = Modifier.focusProperties { canFocus = false })
                } else if (value.text.isBlank() && !sendPending) {
                    ComposerActionIcon(MiuixIcons.Regular.Mic, "录制语音", idle && canSendVoiceNotes && (onVoice != null || onVoicePress != null),
                        onClick = { if (currentInteractive) onVoice?.invoke() },
                        modifier = composerVoiceGesture(idle && canSendVoiceNotes, { fieldBounds },
                            onVoicePress?.let { press -> ({ if (currentInteractive) press() }) },
                            onVoiceRelease?.let { release -> ({ cancelled: Boolean -> release(cancelled || !currentInteractive) }) },
                            onVoiceLock?.let { lock -> ({ if (currentInteractive) lock() }) }))
                } else {
                    ComposerActionIcon(MiuixIcons.Regular.Send, if (sendPending) "正在发送" else "发送消息",
                        enabled = canSubmit, onClick = submit, color = colors.primary,
                        modifier = Modifier.focusProperties { canFocus = false })
                }
            }
        },
    )
}

@Composable
internal fun ComposerActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit,
    color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val colors = MiuixTheme.colorScheme
    Box(modifier.size(32.dp).clip(CircleShape).semantics {
        contentDescription = label
        role = Role.Button
        if (!enabled) disabled()
    }.clickable(enabled = enabled, role = Role.Button, interactionSource = interactions,
        indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (!enabled) colors.disabledOnSurface
            else if (color == Color.Unspecified) colors.onSurfaceVariantSummary else color)
    }
}

@Composable
private fun ComposerMenuItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).chatSquircleSurface(Color.Transparent, 15.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = if (enabled) colors.onSurface else colors.disabledOnSurface
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, fontSize = 14.sp, color = tint)
    }
}
