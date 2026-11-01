package com.badnng.moe.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chatSquircleSurface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class VoiceRecordingVisualStage { RECORDING, ENCODING, READY, FAILED }

/** Inline status/actions follow the native record bar; capture and send ownership stay outside UI. */
@Composable
internal fun VoiceRecordingBarContent(
    stage: VoiceRecordingVisualStage,
    elapsedMillis: Long,
    locked: Boolean,
    pending: Boolean,
    error: ChatError?,
    onCancel: () -> Unit,
    onReview: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val recording = stage == VoiceRecordingVisualStage.RECORDING
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    val status = when {
        pending -> "正在发送语音……"
        error == ChatError.FORBIDDEN -> "当前聊天不允许发送语音。"
        error != null -> "语音未提交成功，可重试发送。"
        stage == VoiceRecordingVisualStage.FAILED -> "录音未完成，请检查麦克风和媒体组件后重试。"
        stage == VoiceRecordingVisualStage.ENCODING -> "正在处理语音……"
        stage == VoiceRecordingVisualStage.READY -> "录音已就绪，确认后发送。"
        locked -> "已锁定录音 · 停止后确认或直接发送"
        else -> "松开发送 · 移出取消 · 上滑锁定"
    }
    Column(modifier.fillMaxWidth().chatSquircleSurface(colors.surfaceContainer, 15.dp)
        .pointerInput(Unit) { detectTapGestures {} }
        .semantics { contentDescription = "语音录制栏" }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (recording) colors.error else colors.onSurfaceVariantSummary, CircleShape))
            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 16.sp,
                fontWeight = FontWeight.Medium, color = colors.onSurface)
            if (recording && locked) Icon(MiuixIcons.Regular.Lock, "录音已锁定", Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
            Spacer(Modifier.weight(1f))
            RecordingAction("取消录音", MiuixIcons.Regular.Delete, !pending, onCancel)
            if (recording) RecordingAction("停止录音", null, !pending, onReview)
            RecordingAction(if (pending) "正在发送语音" else "发送语音", MiuixIcons.Regular.Send,
                !pending && (recording || stage == VoiceRecordingVisualStage.READY), onSend, primary = true)
        }
        Text(status, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (error != null || stage == VoiceRecordingVisualStage.FAILED) colors.error else colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun RecordingAction(label: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val foreground = when {
        !enabled -> colors.disabledOnSurface
        primary -> colors.primary
        else -> colors.onSurfaceVariantSummary
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp).clip(CircleShape)
        .semantics { contentDescription = label; role = Role.Button }, minWidth = 32.dp, minHeight = 32.dp,
        backgroundColor = Color.Transparent, cornerRadius = 16.dp) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = foreground)
        else Box(Modifier.size(12.dp).background(foreground))
    }
}
