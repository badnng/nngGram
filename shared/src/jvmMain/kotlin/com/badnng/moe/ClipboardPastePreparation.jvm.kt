package com.badnng.moe

import com.badnng.moe.chat.AttachmentKind
import com.badnng.moe.media.AttachmentFiles
import com.badnng.moe.media.PhotoAttachmentValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.nio.file.Files
import java.nio.file.Path

internal actual suspend fun inspectClipboardFiles(paths: List<String>): List<PreparedClipboardItem> = withContext(Dispatchers.IO) {
    require(paths.size in 1..20)
    paths.map { path ->
        require(path.isNotBlank() && path.length <= 32_768 && '\u0000' !in path)
        val native = Path.of(path).toAbsolutePath().normalize()
        val file = AttachmentFiles.inspect(native.toString(), AttachmentKind.FILE)
        val photo = PhotoAttachmentValidation.validate(native.toString())
        val dimensions = if (photo) Data.makeFromBytes(Files.readAllBytes(native)).use { data ->
            Codec.makeFromData(data).use { it.width to it.height }
        } else 0 to 0
        PreparedClipboardItem(file.copy(width = dimensions.first, height = dimensions.second), photo)
    }
}
