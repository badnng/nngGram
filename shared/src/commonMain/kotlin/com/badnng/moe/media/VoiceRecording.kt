package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.VoiceNoteDraft

internal enum class VoiceRecordingCommand { RECORD, REVIEW, SEND, CANCEL }

/** Gesture commands contain no audio and never open a recording device. */
@Stable
class VoiceRecordingControl(startLocked: Boolean = false) {
    var isLocked by mutableStateOf(startLocked)
        private set
    internal var command by mutableStateOf(VoiceRecordingCommand.RECORD)
        private set
    internal var revision by mutableLongStateOf(0)
        private set

    fun beginHeldRecording() {
        isLocked = false
        request(VoiceRecordingCommand.RECORD)
    }
    fun beginLockedRecording() {
        isLocked = true
        request(VoiceRecordingCommand.RECORD)
    }
    fun lock() { isLocked = true }
    fun review() { request(VoiceRecordingCommand.REVIEW) }
    fun stop() = review()
    fun send() { request(VoiceRecordingCommand.SEND) }
    fun cancel() { request(VoiceRecordingCommand.CANCEL) }

    private fun request(value: VoiceRecordingCommand) {
        command = value
        revision++
    }
}

/** Mounted in the composer only after an explicit microphone action. */
@Composable
expect fun VoiceRecordingBar(
    show: Boolean,
    pending: Boolean,
    error: ChatError?,
    onDismiss: () -> Unit,
    onSend: (VoiceNoteDraft) -> Unit,
    modifier: Modifier = Modifier,
    control: VoiceRecordingControl? = null,
)

@Composable
expect fun VoiceRecordingDialog(
    show: Boolean,
    pending: Boolean,
    error: ChatError?,
    onDismiss: () -> Unit,
    onSend: (VoiceNoteDraft) -> Unit,
)
