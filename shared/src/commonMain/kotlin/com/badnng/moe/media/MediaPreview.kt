package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.AttachmentKind
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.MediaMiniThumbnail

/** Only local files supplied by TDLib or an explicit attachment choice are rendered. */
@Composable
expect fun MediaPreview(
    path: String,
    format: MediaFormat,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    animate: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Fit,
    onLoadResult: ((Boolean) -> Unit)? = null,
    highQuality: Boolean = false,
    playOnce: Boolean = false,
    replayKey: Long = 0L,
    fitzpatrickType: Int = 0,
    alignTopStart: Boolean = false,
    onPrepared: ((Boolean) -> Unit)? = null,
    outputGate: MediaOutputGate? = null,
)

@Composable
expect fun PhotoAttachmentDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSend: (path: String, caption: RichMessageText) -> Unit,
    pending: Boolean = false,
    error: ChatError? = null,
    initialCaption: TextFieldValue = TextFieldValue(),
)

@Composable
expect fun AttachmentDialog(
    show: Boolean,
    kind: AttachmentKind,
    onDismiss: () -> Unit,
    onSend: (LocalAttachment, RichMessageText) -> Unit,
    pending: Boolean = false,
    error: ChatError? = null,
    initialCaption: TextFieldValue = TextFieldValue(),
)

/** Explicit save follows download preferences; explicit Save As always chooses a target. Never executes a file. */
@Composable
expect fun SaveMediaFile(show: Boolean, path: String, suggestedName: String, forceChooseLocation: Boolean = false, onDismiss: () -> Unit)

/** A user-requested player is owned by the current conversation and closes when it leaves composition. */
interface VideoPlaybackSession {
    fun enterViewer()
    fun leaveViewer()
}

/** Hoist ownership above the message list so expanding the video does not restart its decoder. */
@Composable
expect fun rememberVideoPlaybackSession(
    path: String,
    active: Boolean,
    streamSize: Long = 0,
    readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)? = null,
    initiallyMuted: Boolean = false,
    readerFactory: (() -> MediaRangeReader)? = null,
): VideoPlaybackSession

@Composable
expect fun VideoPlayback(
    show: Boolean,
    path: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    streamSize: Long = 0,
    readPart: ((Long, Int, (ChatResult<ByteArray>) -> Unit) -> Unit)? = null,
    aspectRatio: Float = 16f / 9f,
    onSave: (() -> Unit)? = null,
    session: VideoPlaybackSession? = null,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    caption: RichMessageText = RichMessageText(""),
    title: String = "",
    downloadedFraction: Float = 0f,
    allowCaptionCopy: Boolean = false,
    canCopyCaptionNow: () -> Boolean = { allowCaptionCopy },
    loadingThumbnailPath: String? = null,
    loadingMinithumbnail: MediaMiniThumbnail? = null,
    readerFactory: (() -> MediaRangeReader)? = null,
)
