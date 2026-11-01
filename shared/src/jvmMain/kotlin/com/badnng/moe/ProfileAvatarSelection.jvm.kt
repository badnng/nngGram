package com.badnng.moe

import com.badnng.moe.media.ProfilePhotoSource
import com.badnng.moe.media.readProfilePhotoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.nio.file.Path

// Pinned PhotoVideoFilesFilter advertises mp4/mov/m4v; its image half is supplied
// dynamically by Qt. Advertise the bounded Skia image decoders available in this host.
internal val profileAvatarImageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "ico", "wbmp")
internal val profileAvatarVideoExtensions = setOf("mp4", "mov", "m4v")
internal val profileAvatarMediaExtensions = profileAvatarImageExtensions + profileAvatarVideoExtensions

/** The extension filter is presentation only. Actual decoder content chooses the editor. */
internal sealed interface ProfileAvatarSelection {
    class Photo(val source: ProfilePhotoSource) : ProfileAvatarSelection
    class Video(val path: String) : ProfileAvatarSelection
}

internal suspend fun readProfileAvatarSelection(
    path: String,
    readPhoto: suspend (String) -> ProfilePhotoSource = ::readProfilePhotoSource,
): ProfileAvatarSelection {
    val photo = try {
        readPhoto(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val extension = Path.of(path).fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
        // Pinned PrepareProfileMediaFromFile leaves failed image extensions in its
        // bad-photo branch instead of opening an unrelated video editor.
        if (extension in profileAvatarImageExtensions) throw failure
        null
    }
    currentCoroutineContext().ensureActive()
    // The video editor stages and probes this file before enabling preview or upload. This
    // rejects corrupt/unsupported files without uploading them.
    return photo?.let { ProfileAvatarSelection.Photo(it) } ?: ProfileAvatarSelection.Video(path)
}

/** A native chooser may complete after its coroutine has been cancelled or its page removed. */
internal class ProfileAvatarSelectionOwner : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private var revision = 0L
    @Synchronized fun begin(): Long = ++revision
    @Synchronized fun current(expected: Long) = alive.get() && revision == expected
    @Synchronized override fun close() { alive.set(false); ++revision }
}
