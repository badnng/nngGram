package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class HistoryWindowMotion(val outgoing: Boolean, val progress: () -> Float)
internal val LocalHistoryWindowMotion = staticCompositionLocalOf<HistoryWindowMotion?> { null }

/** Only the timeline moves. The new pane owns the stationary header and composer. */
internal fun Modifier.historyWindowMotion(motion: HistoryWindowMotion?): Modifier =
    if (motion == null) this else graphicsLayer {
        translationY = (if (motion.outgoing) -motion.progress() else 1f - motion.progress()) * size.height
    }

/**
 * Latest-history requests intentionally change the native selection token. Keep the old real
 * window visible until a real latest batch arrives, then scroll between those two isolated
 * windows. We never join their messages across the unloaded interval.
 */
@Composable
internal fun ConversationLatestTransition(
    snapshot: ChatSnapshot,
    onReturnToLatest: (ChatToken) -> Unit,
    content: @Composable (shownSnapshot: ChatSnapshot, interactive: Boolean, returnToLatest: () -> Unit) -> Unit,
) {
    var source by remember(snapshot.token.generation, snapshot.token.chatId) { mutableStateOf<ChatSnapshot?>(null) }
    val retained = source
    val incoming = retained != null && snapshot.token != retained.token &&
        (snapshot.messages.isNotEmpty() || snapshot.historyEnd || snapshot.historyError != null)
    val progress = remember(retained?.token) { Animatable(0f) }
    LaunchedEffect(retained?.token, if (incoming) snapshot.token else null) {
        if (incoming) {
            progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            if (source?.token == retained?.token) source = null
        }
    }
    val requestLatest: () -> Unit = {
        if (source == null) {
            source = snapshot
            onReturnToLatest(snapshot.token)
        }
    }
    val windows = when {
        retained == null -> listOf(snapshot)
        incoming -> listOf(retained, snapshot)
        else -> listOf(retained)
    }
    Box(Modifier.fillMaxSize()) {
        windows.forEach { window -> key(window.token) {
            val interactive = retained == null
            val motion = if (incoming) remember(window.token, retained?.token, progress) {
                HistoryWindowMotion(window.token == retained?.token) { progress.value }
            } else null
            CompositionLocalProvider(LocalHistoryWindowMotion provides motion) {
                Box(Modifier.fillMaxSize().focusProperties { canFocus = interactive }
                    .onPreviewKeyEvent { !interactive }.pointerInput(interactive) {
                        if (!interactive) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
                    content(window, interactive, requestLatest)
                }
            }
        } }
        if (retained != null && !incoming) Text("正在加载最新消息……", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
                .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 15.dp)
                .padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
