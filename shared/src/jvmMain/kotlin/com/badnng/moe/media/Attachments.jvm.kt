package com.badnng.moe.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AttachmentKind
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.LocalAttachment
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.withMarkdownFormatting
import com.badnng.moe.RichTextEditor
import com.badnng.moe.richText
import com.badnng.moe.DownloadPreferencesRead
import com.badnng.moe.LocalDownloadAccountDataPath
import com.badnng.moe.LocalDownloadAccountUserId
import com.badnng.moe.SettingsDownloadDirectory
import com.badnng.moe.SettingsDownloadPreferences
import com.badnng.moe.LocalDownloadPreferencesAccess
import com.badnng.moe.LocalSettingsSecurityConfirmationHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun AttachmentDialog(
    show: Boolean,
    kind: AttachmentKind,
    onDismiss: () -> Unit,
    onSend: (LocalAttachment, RichMessageText) -> Unit,
    pending: Boolean,
    error: ChatError?,
    initialCaption: TextFieldValue,
) {
    if (!show) return
    var attachment by remember(kind) { mutableStateOf<LocalAttachment?>(null) }
    val selectionOwner = remember(kind) { AttachmentSelectionOwner() }
    DisposableEffect(selectionOwner) { onDispose { selectionOwner.close() } }
    var caption by remember(kind) { mutableStateOf(initialCaption.copy(composition = null)) }
    val captionText = remember(caption) { caption.richText().withMarkdownFormatting().text }
    var choosing by remember(kind) { mutableStateOf(true) }
    var inspecting by remember(kind) { mutableStateOf(false) }
    var failure by remember(kind) { mutableStateOf<AttachmentFailure.Reason?>(null) }
    var pickerFailed by remember(kind) { mutableStateOf(false) }
    var attempt by remember(kind) { mutableIntStateOf(0) }
    val dismiss by rememberUpdatedState(onDismiss)
    val dialogOwner = LocalNativeDialogOwner.current
    val picker = LocalNativeFilePicker.current
    val label = if (kind == AttachmentKind.VIDEO) "视频" else "文件"
    LaunchedEffect(kind, attempt) {
        choosing = true
        pickerFailed = false
        val choice = try {
            picker("选择要发送的$label", if (kind == AttachmentKind.VIDEO) setOf("mp4", "m4v", "mov") else null, dialogOwner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pickerFailed = true
            choosing = false
            return@LaunchedEffect
        }
        choosing = false
        if (choice == null) {
            if (attachment == null) dismiss()
        } else {
            inspecting = true
            val result = try {
                Result.success(prepareAttachmentSelection(choice, kind))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val prepared = result.getOrNull()
            if (!selectionOwner.replace(prepared)) return@LaunchedEffect
            attachment = prepared
            failure = result.exceptionOrNull()?.let { (it as? AttachmentFailure)?.reason ?: AttachmentFailure.Reason.INVALID_FILE }
            inspecting = false
        }
    }
    WindowDialog(show = !choosing, title = "发送$label", onDismissRequest = { if (!pending) onDismiss() }) {
        Column(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inspecting) Text("正在读取附件信息……")
                if (pickerFailed) Text("无法打开系统文件选择器，请重试。", color = MiuixTheme.colorScheme.error)
                attachment?.let {
                    it.thumbnail?.let { thumbnail ->
                        MediaPreview(thumbnail.path, MediaFormat.PHOTO, Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            contentDescription = "所选视频缩略图", animate = false)
                    }
                    Text(it.fileName)
                    Text("${(it.size / 1024.0 / 1024 * 10).toLong() / 10.0} MB")
                    if (kind == AttachmentKind.VIDEO) Text("${it.width} × ${it.height} · ${it.durationSeconds} 秒")
                }
                failure?.let { Text(attachmentFailureText(it), color = MiuixTheme.colorScheme.error) }
                Button(onClick = { attempt++ }, enabled = !pending && !inspecting) { Text("选择其他$label") }
                RichTextEditor(caption, { caption = it }, label = "$label 说明（可选）", maxLines = 4,
                    modifier = Modifier.fillMaxWidth(), enabled = !pending)
                if (captionText.codePointCount(0, captionText.length) > 1024) Text("说明不能超过 1024 个字符。", color = MiuixTheme.colorScheme.error)
                error?.let {
                    Text(when (it) {
                        ChatError.NOT_READY -> "聊天已切换，请取消后重新选择发送目标。"
                        ChatError.FORBIDDEN -> "此聊天不允许发送这类附件。"
                        ChatError.RATE_LIMITED -> "发送过于频繁，请稍后再试。"
                        else -> "附件未提交成功，内容已保留，可检查连接后重试。"
                    }, color = MiuixTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, enabled = !pending, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { attachment?.let { onSend(it, caption.richText()) } }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = attachment != null && !pending && !inspecting && error != ChatError.NOT_READY &&
                        captionText.codePointCount(0, captionText.length) <= 1024) { Text(if (pending) "正在提交……" else "发送$label") }
            }
        }
    }
}

@Composable
actual fun SaveMediaFile(show: Boolean, path: String, suggestedName: String, forceChooseLocation: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    var choosing by remember(path) { mutableStateOf(true) }
    var destination by remember(path) { mutableStateOf<Path?>(null) }
    var automaticDirectory by remember(path) { mutableStateOf<Path?>(null) }
    var automaticUsesDefault by remember(path) { mutableStateOf(false) }
    var overwrite by remember(path) { mutableStateOf(false) }
    var copying by remember(path) { mutableStateOf(false) }
    var failed by remember(path) { mutableStateOf(false) }
    var attempt by remember(path) { mutableIntStateOf(0) }
    var copyRequest by remember(path) { mutableIntStateOf(0) }
    var retryWithChooser by remember(path) { mutableStateOf(false) }
    var targetIssue by remember(path) { mutableStateOf<SaveTargetIssue?>(null) }
    var targetMayExist by remember(path) { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val dialogOwner = LocalNativeDialogOwner.current
    val picker = LocalNativeSavePicker.current
    val preferenceAccess = LocalDownloadPreferencesAccess.current
    val accountDataPath = LocalDownloadAccountDataPath.current
    val accountUserId = LocalDownloadAccountUserId.current
    val resolveRoots = LocalDownloadDestinationRootsResolver.current
    val saveIo = LocalSavedMediaFileIo.current
    // An inventory change retires this captured boundary but must not silently start a
    // second copy. A new capture belongs to an explicit retry or a new save owner.
    val owner = remember(path, accountDataPath, accountUserId, forceChooseLocation, attempt) { AtomicBoolean(true) }
    val targetBoundary = remember(owner) { NativeSaveTargets.capture() }
    DisposableEffect(owner) { onDispose { owner.set(false) } }
    LaunchedEffect(owner, attempt) {
        choosing = true
        destination = null
        automaticDirectory = null
        automaticUsesDefault = false
        targetIssue = null
        targetMayExist = false
        val preferences = (preferenceAccess.read() as? DownloadPreferencesRead.Value)?.preferences
            ?: SettingsDownloadPreferences(askEveryTime = true)
        if (!preferences.askEveryTime && !forceChooseLocation && !retryWithChooser) {
            val roots = withContext(Dispatchers.IO) { resolveRoots(accountDataPath, accountUserId) }
            val directory = try { withContext(Dispatchers.IO) { resolveCheckedDownloadDirectory(preferences, roots, targetBoundary) } }
            catch (rejected: SaveTargetRejected) { targetIssue = rejected.issue; null }
            choosing = false
            if (directory == null) failed = true
            else {
                automaticDirectory = directory
                automaticUsesDefault = preferences.directory == SettingsDownloadDirectory.DOWNLOADS
                failed = false
                copyRequest++
            }
            return@LaunchedEffect
        }
        val selected = try {
            picker(suggestedName, dialogOwner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            choosing = false
            return@LaunchedEffect
        }
        choosing = false
        failed = false
        if (!owner.get()) return@LaunchedEffect
        if (selected == null) dismiss()
        else {
            val target = runCatching { Path.of(selected).toAbsolutePath().normalize().let {
                require(it.fileName != null && it.parent != null)
                it.resolveSibling(SavedMediaFiles.safeDownloadName(it.fileName.toString()))
            } }.getOrNull()
            if (target == null) { failed = true; return@LaunchedEffect }
            val issue = withContext(Dispatchers.IO) { targetBoundary.check(target) }
            if (issue != null) { targetIssue = issue; failed = true; return@LaunchedEffect }
            destination = target
            overwrite = withContext(Dispatchers.IO) { Files.exists(target) }
            if (!overwrite) copyRequest++
        }
    }
    LaunchedEffect(owner, copyRequest) {
        val target = destination
        val automatic = automaticDirectory
        if (copyRequest > 0 && (target != null || automatic != null)) {
            copying = true
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    if (automatic != null) {
                        val saved = runCatching { SavedMediaFiles.saveUniqueWithIo(Path.of(path), automatic, suggestedName, targetBoundary, saveIo, owner::get) }
                        if (saved.isFailure) {
                            // A forbidden/unknown target never silently becomes a different directory.
                            if (saved.exceptionOrNull() is SaveTargetRejected || saved.exceptionOrNull() is SaveTargetPublicationUncertain ||
                                !targetBoundary.current()) throw saved.exceptionOrNull()!!
                            val fallback = resolveRoots(accountDataPath, accountUserId).accountTemporary
                            if (!automaticUsesDefault || fallback == null || fallback == automatic || !owner.get()) {
                                throw saved.exceptionOrNull()!!
                            }
                            SavedMediaFiles.saveUniqueWithIo(Path.of(path), fallback, suggestedName, targetBoundary, saveIo, owner::get)
                        }
                    } else SavedMediaFiles.saveSelectedWithIo(Path.of(path), target!!, overwrite, targetBoundary, saveIo, owner::get)
                }
            }
            failed = copied.isFailure
            val rejection = copied.exceptionOrNull() as? SaveTargetRejected
            targetIssue = rejection?.issue
            targetMayExist = rejection?.publicFileMayExist == true || copied.exceptionOrNull() is SaveTargetPublicationUncertain
            copying = false
            overwrite = false
            if (!failed && owner.get()) dismiss()
        }
    }
    LocalSettingsSecurityConfirmationHost.current.Content(show = !choosing, title = "保存附件", summary = null, maxWidth = 520.dp,
        onDismissRequest = { if (!copying) onDismiss() }) {
        Column(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                copying -> "正在保存……"
                failed && targetMayExist -> "保存结果需要核实：目标位置可能已写入，原文件保留。请先检查目标位置，再决定是否重试。"
                failed && targetIssue != null -> "此保存目标无法确认为独立副本位置。原文件保留，请重新选择缓存扫描目录之外的位置。"
                failed -> "保存失败。请确认目标目录可写、空间足够，且附件未被清理。"
                overwrite -> "所选位置已有同名文件，是否替换？"
                else -> "正在准备保存……"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, enabled = !copying, modifier = Modifier.weight(1f)) { Text("取消") }
                if (overwrite && !copying) Button(onClick = { copyRequest++ }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("替换") }
                else if (failed) Button(onClick = { retryWithChooser = true; attempt++ }, modifier = Modifier.weight(1f)) { Text("重新选择位置") }
            }
        }
    }
}

private fun attachmentFailureText(reason: AttachmentFailure.Reason): String = when (reason) {
    AttachmentFailure.Reason.INVALID_FILE -> "无法读取这个文件，请选择非空的本地文件。"
    AttachmentFailure.Reason.TOO_LARGE -> "文件超过 4 GB，请选择较小的文件。实际发送上限还取决于账号与服务端限制。"
    AttachmentFailure.Reason.VIDEO_TOOLS_MISSING -> "视频检查工具不可用，请准备媒体组件后重试；也可通过“文件”发送。"
    AttachmentFailure.Reason.INVALID_VIDEO -> "无法读取视频信息，请选择完整的视频文件，或通过“文件”发送。"
    AttachmentFailure.Reason.VIDEO_FORMAT -> "视频消息支持 H.264 编码的 MP4；其他格式可通过“文件”发送。"
    AttachmentFailure.Reason.SAVE_FAILED -> "保存失败，请重新选择位置。"
}
