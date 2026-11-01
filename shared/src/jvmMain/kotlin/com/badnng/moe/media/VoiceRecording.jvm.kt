package com.badnng.moe.media

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.VoiceNoteDraft

/** Injectable only for offline UI tests; opening a real device always follows a user's mic action. */
internal val LocalVoiceRecordingFactory = staticCompositionLocalOf<() -> VoiceRecordingSession> { { VoiceRecordingSession() } }

@Composable
actual fun VoiceRecordingBar(show: Boolean, pending: Boolean, error: ChatError?, onDismiss: () -> Unit,
    onSend: (VoiceNoteDraft) -> Unit, modifier: Modifier, control: VoiceRecordingControl?) {
    if (!show) return
    val commands = control ?: remember { VoiceRecordingControl(startLocked = true) }
    val factory = LocalVoiceRecordingFactory.current
    val recorder = remember { factory() }
    DisposableEffect(recorder) { onDispose { recorder.close() } }
    val state by recorder.state.collectAsState()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentSend by rememberUpdatedState(onSend)
    var submittedRevision by remember(recorder) { mutableLongStateOf(-1) }
    LaunchedEffect(commands.revision, state.stage, pending) {
        // A shortcut during an in-flight native send is not a queued retry.
        if (pending && commands.command == VoiceRecordingCommand.SEND) {
            submittedRevision = commands.revision
            return@LaunchedEffect
        }
        when (commands.command) {
            VoiceRecordingCommand.RECORD -> Unit
            VoiceRecordingCommand.REVIEW, VoiceRecordingCommand.SEND -> {
                recorder.stop()
                if (commands.command == VoiceRecordingCommand.SEND && !pending && state.stage == VoiceRecordingStage.READY &&
                    submittedRevision != commands.revision) {
                    state.draft?.let { draft ->
                        submittedRevision = commands.revision
                        currentSend(draft)
                    }
                }
            }
            VoiceRecordingCommand.CANCEL -> currentDismiss()
        }
    }
    VoiceRecordingBarContent(
        stage = when (state.stage) {
            VoiceRecordingStage.RECORDING -> VoiceRecordingVisualStage.RECORDING
            VoiceRecordingStage.ENCODING -> VoiceRecordingVisualStage.ENCODING
            VoiceRecordingStage.READY -> VoiceRecordingVisualStage.READY
            VoiceRecordingStage.FAILED -> VoiceRecordingVisualStage.FAILED
        },
        elapsedMillis = state.elapsedMillis, locked = commands.isLocked, pending = pending, error = error,
        onCancel = commands::cancel, onReview = commands::review, onSend = commands::send, modifier = modifier,
    )
}

/** Compatibility entry point; production places VoiceRecordingBar in its measured composer slot. */
@Composable
actual fun VoiceRecordingDialog(show: Boolean, pending: Boolean, error: ChatError?, onDismiss: () -> Unit,
    onSend: (VoiceNoteDraft) -> Unit) {
    if (!show) return
    val offset = with(LocalDensity.current) { IntOffset(0, -12.dp.roundToPx()) }
    Popup(alignment = Alignment.BottomCenter, offset = offset,
        onDismissRequest = { if (!pending) onDismiss() }, properties = PopupProperties(focusable = true)) {
        VoiceRecordingBar(true, pending, error, onDismiss, onSend,
            modifier = Modifier.widthIn(min = 240.dp, max = 460.dp).padding(8.dp))
    }
}
