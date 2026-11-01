package com.badnng.moe.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.badnng.moe.settings.ProfileStickerAvatarArtworkProvider
import com.badnng.moe.tdlib.TdEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun profileStickerAvatarUiDispatcher(uiScope: CoroutineScope) = ProfileStickerAvatarUiDispatch { deliver, discard ->
    val claimed = AtomicBoolean()
    try {
        val job = uiScope.launch {
            if (claimed.compareAndSet(false, true)) {
                try { deliver() } catch (_: Throwable) { discard() }
            }
        }
        job.invokeOnCompletion { if (claimed.compareAndSet(false, true)) discard() }
    } catch (_: Throwable) { if (claimed.compareAndSet(false, true)) discard() }
}

/** Called from the window composition; the UI owns the provider and every rendered pixel. */
@Composable
fun rememberProfileStickerAvatarArtwork(engine: TdEngine): ProfileStickerAvatarArtworkProvider? {
    val environment by engine.profileStickerAvatarMedia.collectAsState()
    val uiScope = rememberCoroutineScope()
    val ui = remember(uiScope) { profileStickerAvatarUiDispatcher(uiScope) }
    val provider = remember(environment, ui) {
        environment?.let { JvmProfileStickerAvatarArtworkProvider(it.generation, it.reader, ui) }
    }
    DisposableEffect(provider) { onDispose { provider?.close() } }
    return provider
}
