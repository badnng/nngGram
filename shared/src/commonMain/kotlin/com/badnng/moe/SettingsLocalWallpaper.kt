package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Immutable worker-prepared pixels. Each handle independently owns its display lifetime. */
internal interface LocalWallpaperFrame {
    val width: Int
    val height: Int
    val blurAvailable: Boolean
    fun retain(): LocalWallpaperFrame
    suspend fun prepare(widthPx: Int, heightPx: Int, blurred: Boolean)
    fun draw(scope: DrawScope, destination: Rect, blurred: Boolean)
    fun close()
}

internal enum class LocalWallpaperReadFailureKind { INVALID, UNAVAILABLE }
internal class LocalWallpaperReadFailure(val kind: LocalWallpaperReadFailureKind) : Exception("Local wallpaper unavailable")

internal data class LocalWallpaperSelection(val frame: LocalWallpaperFrame, val blurred: Boolean)

/** No paths, account identities or encoded bytes cross this presentation boundary. */
internal interface LocalWallpaperBackend {
    suspend fun pick(): LocalWallpaperFrame?
    suspend fun load(): LocalWallpaperSelection?
    suspend fun commit(frame: LocalWallpaperFrame, blurred: Boolean, current: () -> Boolean): Boolean
    suspend fun restore(current: () -> Boolean): Boolean
}

@Composable
internal expect fun rememberLocalWallpaperBackend(): LocalWallpaperBackend

internal val LocalLocalWallpaperController = staticCompositionLocalOf<LocalWallpaperController?> { null }
internal val LocalLocalWallpaperBackend = staticCompositionLocalOf<LocalWallpaperBackend?> { null }

/** UI-thread transaction ownership; admitted disk operations retain their own frozen frame. */
internal class LocalWallpaperController(private val backend: LocalWallpaperBackend, private val scope: CoroutineScope) {
    var committed by mutableStateOf<LocalWallpaperSelection?>(null)
        private set
    var preview by mutableStateOf<LocalWallpaperSelection?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var previewDarkValue by mutableStateOf(false)
    val previewDark: Boolean get() = previewDarkValue
    private var alive = true
    private var revision = 0L
    private var operation = 0L

    fun load() {
        if (!alive || busy) return
        val request = ++operation
        val initialRevision = revision
        busy = true
        scope.launch {
            var loaded: LocalWallpaperSelection? = null
            try {
                loaded = backend.load()
                if (alive && revision == initialRevision && operation == request) {
                    committed?.frame?.close()
                    committed = loaded
                    loaded = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (alive && operation == request) error = "无法读取本地壁纸。" }
            finally { loaded?.frame?.close(); if (alive && operation == request) busy = false }
        }
    }

    fun pick(owner: () -> Boolean) {
        if (!alive || busy || !owner()) return
        cancel()
        val requestedRevision = revision
        val request = ++operation
        busy = true
        error = null
        scope.launch {
            var picked: LocalWallpaperFrame? = null
            try {
                picked = backend.pick()
                if (alive && owner() && revision == requestedRevision && operation == request) {
                    preview = picked?.let { LocalWallpaperSelection(it, false) }
                    picked = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (alive && owner() && revision == requestedRevision) error = "无法使用此图片，请选择有效的 JPEG、PNG 或 WebP 图片。"
            } finally { picked?.close(); if (alive && operation == request) busy = false }
        }
    }

    fun cancel() {
        revision++
        preview?.frame?.close()
        preview = null
        error = null
    }

    fun setBlurred(value: Boolean) {
        if (!alive || busy) return
        preview?.takeIf { !value || it.frame.blurAvailable }?.let {
            revision++
            preview = it.copy(blurred = value)
        }
    }

    fun setPreviewDark(value: Boolean) { if (alive) previewDarkValue = value }

    fun apply(owner: () -> Boolean) {
        val selected = preview ?: return
        if (!alive || busy || !owner()) return
        val frozen = selected.copy(frame = selected.frame.retain())
        val acceptedRevision = revision
        val request = ++operation
        busy = true
        error = null
        scope.launch {
            var retained = true
            try {
                val saved = backend.commit(frozen.frame, frozen.blurred) {
                    alive && owner() && revision == acceptedRevision
                }
                if (saved && alive) {
                    committed?.frame?.close()
                    committed = frozen
                    retained = false
                    if (revision == acceptedRevision) cancel()
                } else if (alive && owner() && revision == acceptedRevision) error = "壁纸未保存，原壁纸已保留。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (alive && owner() && revision == acceptedRevision) error = "壁纸未保存，原壁纸已保留。"
            } finally {
                if (retained) frozen.frame.close()
                if (alive && operation == request) busy = false
            }
        }
    }

    fun restore(owner: () -> Boolean) {
        if (!alive || busy || !owner()) return
        val acceptedRevision = revision
        val request = ++operation
        busy = true
        error = null
        scope.launch {
            try {
                val saved = backend.restore { alive && owner() && revision == acceptedRevision }
                if (saved && alive) {
                    committed?.frame?.close()
                    committed = null
                    if (revision == acceptedRevision) cancel()
                } else if (alive && owner() && revision == acceptedRevision) error = "内置壁纸未恢复，原壁纸已保留。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (alive && owner() && revision == acceptedRevision) error = "内置壁纸未恢复，原壁纸已保留。"
            } finally { if (alive && operation == request) busy = false }
        }
    }

    fun close() {
        if (!alive) return
        alive = false
        cancel()
        committed?.frame?.close()
        committed = null
    }
}

