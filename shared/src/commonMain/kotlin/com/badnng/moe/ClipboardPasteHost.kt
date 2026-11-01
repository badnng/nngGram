package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import com.badnng.moe.media.LocalAttachmentClipboard
import com.badnng.moe.media.ClipboardAttachmentContent
import com.badnng.moe.media.MediaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal val LocalAttachmentPasteRequest = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalClipboardPasteActive = staticCompositionLocalOf { false }

/** Clipboard input is staged only after an editor shortcut and sent only after explicit confirmation. */
@Composable
internal fun ClipboardPasteHost(snapshot: ChatSnapshot, features: ChatFeatureActions,
    prepare: suspend (ClipboardAttachmentContent) -> PreparedClipboardBatch = { prepareClipboardBatch(it) },
    composerDraft: (ChatToken) -> TextFieldValue = { TextFieldValue() },
    composerDraftRevision: (ChatToken) -> Long = { 0 },
    onComposerDraftAccepted: (ComposerDraftTransfer) -> Unit = {},
    content: @Composable () -> Unit) {
    val inheritedFocusReturn = LocalComposerModalFocusReturn.current
    val modalFocusReturn = inheritedFocusReturn ?: remember { ComposerModalFocusReturn() }
    if (inheritedFocusReturn == null) ComposerModalFocusReturnEffect(modalFocusReturn)
    val clipboard = LocalAttachmentClipboard.current
    val scope = rememberCoroutineScope()
    val latestSnapshot by rememberUpdatedState(snapshot)
    val currentComposerDraft by rememberUpdatedState(composerDraft)
    val currentComposerDraftRevision by rememberUpdatedState(composerDraftRevision)
    val currentDraftAccepted by rememberUpdatedState(onComposerDraftAccepted)
    var revision by remember { mutableLongStateOf(0) }
    var sourceToken by remember { mutableStateOf<ChatToken?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<PreparedClipboardBatch?>(null) }
    var retainedBatch by remember { mutableStateOf<PreparedClipboardBatch?>(null) }
    var caption by remember { mutableStateOf(TextFieldValue()) }
    var draftTransfer by remember { mutableStateOf<ComposerDraftTransfer?>(null) }
    var preparationFailed by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<ClipboardSendQueue?>(null) }
    var queueTick by remember { mutableIntStateOf(0) }
    var dialogMounted by remember { mutableStateOf(false) }
    var focusIntent by remember { mutableStateOf<ComposerModalFocusReturn.Intent?>(null) }
    fun close(returnFocus: Boolean) {
        val confirmedFocusIntent = focusIntent
        focusIntent = null
        revision++
        job?.cancel()
        job = null
        preparing = false
        batch?.close()
        batch = null
        queue = null
        preparationFailed = false
        sourceToken = null
        draftTransfer = null
        if (returnFocus) modalFocusReturn.accepted(confirmedFocusIntent) { !dialogMounted }
        else modalFocusReturn.cancel(confirmedFocusIntent)
    }
    fun cancel() = close(returnFocus = false)
    LaunchedEffect(snapshot.token, snapshot.enabled, snapshot.editTarget?.id) {
        if (sourceToken != null && (!snapshot.enabled || sourceToken != snapshot.token || snapshot.editTarget != null)) cancel()
    }
    DisposableEffect(Unit) { onDispose { job?.cancel(); batch?.close(); retainedBatch?.close() } }
    val request: (() -> Unit)? = clipboard?.takeIf { snapshot.editTarget == null }?.let { native -> ({
        val source = latestSnapshot
        if (source.enabled && source.editTarget == null && !preparing && !dialogMounted && batch == null && !preparationFailed &&
            source.selected?.id == source.token.chatId && source.token.chatId != null) {
            sourceToken = source.token
            val capturedRevision = ++revision
            preparing = true
            draftTransfer = ComposerDraftTransfer(source.token, currentComposerDraft(source.token), currentComposerDraftRevision(source.token))
            caption = checkNotNull(draftTransfer).caption
            job = scope.launch {
                var prepared: PreparedClipboardBatch? = null
                try {
                    val input = native.readAttachments()
                    if (input == null) {
                        if (revision == capturedRevision) cancel()
                        return@launch
                    }
                    prepared = prepare(input)
                    if (isActive && revision == capturedRevision && latestSnapshot.token == source.token && latestSnapshot.editTarget == null) {
                        batch = prepared
                        retainedBatch?.close()
                        retainedBatch = prepared.retainForDisplay()
                        prepared = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (revision == capturedRevision && latestSnapshot.token == source.token && latestSnapshot.editTarget == null) preparationFailed = true
                } finally {
                    prepared?.close()
                    if (revision == capturedRevision) { preparing = false; job = null }
                }
            }
        }
    }) }
    LaunchedEffect(snapshot, queue, queueTick) {
        val sending = queue ?: return@LaunchedEffect
        val previousPhase = sending.phase
        sending.observe(snapshot)
        val next = sending.next(snapshot)
        if (previousPhase != sending.phase) queueTick++
        if (sending.phase == ClipboardQueuePhase.COMPLETE || sending.phase == ClipboardQueuePhase.STALE)
            close(returnFocus = sending.phase == ClipboardQueuePhase.COMPLETE)
        else if (next != null) {
            val item = next.item
            val dispatchedDraft = draftTransfer
            var completionHandled = false
            val complete: (ChatResult<Unit>) -> Unit = { result ->
                scope.launch {
                    if (completionHandled) return@launch
                    completionHandled = true
                    if (result.error == null && result.value != null && latestSnapshot.enabled &&
                        latestSnapshot.token == sending.token && latestSnapshot.editTarget == null) {
                        // Stopping the remaining queue cannot recall its already dispatched item.
                        // A matching accepted item still consumes only its borrowed source draft.
                        dispatchedDraft?.let(currentDraftAccepted)
                        if (draftTransfer === dispatchedDraft) draftTransfer = null
                    }
                    if (queue === sending) {
                        val acceptedCaption = sending.index == 0 && result.error == null && result.value != null
                        sending.complete(next.requestId, result, latestSnapshot)
                        if (sending.phase == ClipboardQueuePhase.FAILED) modalFocusReturn.cancel(focusIntent)
                        // Once the file carrying the shared caption is accepted, a later
                        // file's retry must not reuse that caption. The user may explicitly
                        // write a new caption for the remaining files after a failure.
                        if (acceptedCaption) caption = TextFieldValue()
                        queueTick++
                        if (sending.phase == ClipboardQueuePhase.COMPLETE || sending.phase == ClipboardQueuePhase.STALE)
                            close(returnFocus = sending.phase == ClipboardQueuePhase.COMPLETE)
                    }
                }
            }
            try {
                if (item.asPhoto) features.submitClipboardPhoto(sending.token, item.attachment.path, next.caption, item.lease, complete)
                else features.submitClipboardFile(sending.token, item.attachment, next.caption, complete)
            } catch (_: Exception) { complete(ChatResult(error = ChatError.REQUEST_FAILED)) }
        }
    }
    val open = preparing || batch != null || preparationFailed
    CompositionLocalProvider(LocalAttachmentPasteRequest provides request, LocalClipboardPasteActive provides (open || dialogMounted),
        LocalComposerModalFocusReturn provides modalFocusReturn) {
        Box(Modifier.fillMaxSize().then(if (inheritedFocusReturn == null)
            Modifier.observeComposerModalFocusInput(modalFocusReturn) else Modifier)) { content() }
        WindowDialog(show = open, title = "粘贴附件", maxWidth = 520.dp, onDismissRequest = ::cancel) {
            DisposableEffect(Unit) {
                dialogMounted = true
                onDispose {
                    dialogMounted = false
                    retainedBatch?.close()
                    retainedBatch = null
                }
            }
            val shown = batch ?: retainedBatch
            // This discrete tick publishes the pure queue's phase/index to the dialog.
            val phase = remember(queueTick, queue) { queue?.phase }
            val sending = phase == ClipboardQueuePhase.WAITING || phase == ClipboardQueuePhase.READY
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    preparing -> Text("正在读取剪贴板附件……", fontSize = 13.sp)
                    preparationFailed -> Text("无法读取附件。请检查文件是否仍然可用，图片是否过大。", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.error)
                    shown != null -> {
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.items.firstOrNull { it.asPhoto }?.let { photo ->
                                MediaPreview(photo.attachment.path, MediaFormat.PHOTO,
                                    Modifier.fillMaxWidth().height(190.dp).chatSquircleClip(15.dp), animate = false,
                                    contentDescription = "待发送图片")
                            }
                            shown.items.forEachIndexed { index, item ->
                                Text("${index + 1}. ${item.attachment.fileName}" + if (item.asPhoto) " · 图片" else " · 文件",
                                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        RichTextEditor(caption, { caption = it }, "说明（可选）", enabled = !sending,
                            maxLines = 3, compactControls = true)
                        queue?.failure?.let { ChatErrorText(it) }
                        if (sending) Text("正在发送 ${((queue?.index ?: 0) + 1).coerceAtMost(shown.items.size)} / ${shown.items.size}……", fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatButton(if (sending) "停止后续发送" else "取消", onClick = ::cancel)
                    if (batch != null) ChatButton(if (queue?.phase == ClipboardQueuePhase.FAILED) "重试未发送部分" else "发送",
                        enabled = !sending && !snapshot.sendPending && snapshot.editTarget == null && snapshot.token == sourceToken,
                        primary = true, onClick = {
                            val items = queue?.remainingItems ?: batch?.items.orEmpty()
                            if (items.isNotEmpty() && latestSnapshot.editTarget == null && sourceToken == latestSnapshot.token) {
                                focusIntent = modalFocusReturn.begin(latestSnapshot.token)
                                queue = ClipboardSendQueue(latestSnapshot.token, items, caption.richText().withMarkdownFormatting())
                            }
                        })
                }
            }
        }
    }
}
