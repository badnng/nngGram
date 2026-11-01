package com.badnng.moe

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Windows can temporarily lock the clipboard while another application owns it. */
internal class RetryingClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    override suspend fun getClipEntry(): ClipEntry? = retryWhenBusy(null) {
        delegate.getClipEntry()
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        retryWhenBusy(Unit) { delegate.setClipEntry(clipEntry) }
    }

    private suspend fun <T> retryWhenBusy(fallback: T, operation: suspend () -> T): T {
        repeat(6) { attempt ->
            try {
                return operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: IllegalStateException) {
                // Do not hide unrelated failures in a platform clipboard implementation.
                if (busy.message != "cannot open system clipboard") throw busy
                if (attempt < 5) delay(20L shl attempt)
            }
        }
        // Leave the text intact if Windows still has the clipboard locked after 620 ms.
        return fallback
    }
}
