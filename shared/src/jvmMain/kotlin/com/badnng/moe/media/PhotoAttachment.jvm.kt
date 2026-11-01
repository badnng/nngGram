package com.badnng.moe.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFormat
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.chat.withMarkdownFormatting
import com.badnng.moe.RichTextEditor
import com.badnng.moe.richText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.nio.file.Files
import java.nio.file.Path

internal object PhotoAttachmentValidation {
    fun validate(path: String): Boolean = try {
        val file = Path.of(path)
        if (!Files.isRegularFile(file) || Files.size(file) !in 1..10_000_000L) false
        else Data.makeFromBytes(Files.readAllBytes(file)).use { data ->
            Codec.makeFromData(data).use { codec ->
                val width = codec.width.toLong()
                val height = codec.height.toLong()
                codec.encodedImageFormat in setOf(EncodedImageFormat.JPEG, EncodedImageFormat.PNG, EncodedImageFormat.WEBP) &&
                    codec.frameCount <= 1 && width > 0 && height > 0 && width + height <= 10_000 &&
                    maxOf(width, height) <= minOf(width, height) * 20 && width * height <= 24_000_000
            }
        }
    } catch (_: Exception) { false }
}

@Composable
actual fun PhotoAttachmentDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSend: (path: String, caption: RichMessageText) -> Unit,
    pending: Boolean,
    error: ChatError?,
    initialCaption: TextFieldValue,
) {
    if (!show) return
    var path by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf(initialCaption.copy(composition = null)) }
    val captionText = remember(caption) { caption.richText().withMarkdownFormatting().text }
    var selecting by remember { mutableStateOf(true) }
    var invalid by remember { mutableStateOf(false) }
    var pickerFailed by remember { mutableStateOf(false) }
    var selectionAttempt by remember { mutableIntStateOf(0) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val dialogOwner = LocalNativeDialogOwner.current
    val picker = LocalNativeFilePicker.current
    LaunchedEffect(selectionAttempt) {
        selecting = true
        pickerFailed = false
        val selected = try {
            picker("选择要发送的图片", setOf("jpg", "jpeg", "png", "webp"), dialogOwner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pickerFailed = true
            selecting = false
            return@LaunchedEffect
        }
        if (selected == null) {
            if (path == null) currentDismiss()
        } else {
            val valid = withContext(Dispatchers.IO) { PhotoAttachmentValidation.validate(selected) }
            invalid = !valid
            path = selected.takeIf { valid }
        }
        selecting = false
    }
    WindowDialog(show = !selecting, title = "发送图片", onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 500.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                path?.let { MediaPreview(it, MediaFormat.PHOTO, Modifier.fillMaxWidth().height(220.dp), "待发送图片", false) }
                if (selecting) Text("正在选择图片……")
                if (pickerFailed) Text("无法打开系统文件选择器，请重试。", color = MiuixTheme.colorScheme.error)
                if (invalid) Text("请选择 10 MB 内的 JPG、PNG 或静态 WebP，宽高总和不超过 10000，长宽比不超过 20。",
                    color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { selectionAttempt++ }, enabled = !selecting && !pending) { Text("选择其他图片") }
                Spacer(Modifier.height(12.dp))
                RichTextEditor(value = caption, onValueChange = { caption = it }, label = "图片说明（可选）",
                    modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !pending)
                if (captionText.codePointCount(0, captionText.length) > 1024) Text("图片说明不能超过 1024 个字符。",
                    color = MiuixTheme.colorScheme.error)
                if (error != null) Text(when (error) {
                    ChatError.NOT_READY -> "聊天已切换，请取消后重新选择发送目标。"
                    ChatError.FORBIDDEN -> "此聊天不允许发送图片。"
                    ChatError.RATE_LIMITED -> "发送过于频繁，请稍后再试。"
                    else -> "图片未提交成功，内容已保留，可检查连接后重试。"
                }, color = MiuixTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { path?.let { onSend(it, caption.richText()) } }, modifier = Modifier.weight(1f),
                    enabled = path != null && !selecting && !pending && error != ChatError.NOT_READY && captionText.codePointCount(0, captionText.length) <= 1024,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (pending) "正在提交……" else "发送图片") }
            }
        }
    }
}

