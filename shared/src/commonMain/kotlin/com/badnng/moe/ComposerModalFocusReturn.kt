package com.badnng.moe

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.badnng.moe.chat.ChatToken
import kotlinx.coroutines.flow.first

/** One explicit confirmation may return to its still-mounted field after its own modal closes. */
@Stable
internal class ComposerModalFocusReturn {
    class Target internal constructor(val token: ChatToken, internal val allowed: () -> Boolean,
        internal val focus: () -> Unit)
    class Intent internal constructor(internal val target: Target)
    class Pending internal constructor(internal val intent: Intent, internal val inputRevision: Long,
        internal val ready: () -> Boolean)

    private var target: Target? = null
    private var intent: Intent? = null
    private var inputRevision = 0L
    var pending by mutableStateOf<Pending?>(null)
        private set

    fun register(token: ChatToken, allowed: () -> Boolean, focus: () -> Unit): Target =
        Target(token, allowed, focus).also { target = it }

    fun unregister(owner: Target) {
        if (target === owner) target = null
        if (intent?.target === owner) { intent = null; pending = null }
    }

    fun begin(token: ChatToken): Intent? {
        intent = null
        pending = null
        return target?.takeIf { it.token == token }?.let(::Intent)?.also { intent = it }
    }

    fun cancel(owner: Intent?) {
        if (owner != null && intent === owner) { intent = null; pending = null }
    }

    fun accepted(owner: Intent?, ready: () -> Boolean) {
        if (owner != null && intent === owner && target === owner.target) {
            pending = Pending(owner, inputRevision, ready)
        }
    }

    /** Observe actual later input without consuming or changing its selection/shortcut behavior. */
    fun userInput() {
        inputRevision++
        intent = null
        pending = null
    }

    fun complete(request: Pending) {
        if (pending !== request || intent !== request.intent) return
        val owner = request.intent.target
        val restore = target === owner && inputRevision == request.inputRevision && request.ready() && owner.allowed()
        intent = null
        pending = null
        if (restore) owner.focus()
    }
}

internal val LocalComposerModalFocusReturn = staticCompositionLocalOf<ComposerModalFocusReturn?> { null }

@Composable
internal fun ComposerModalFocusReturnEffect(state: ComposerModalFocusReturn) {
    val request = state.pending
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        snapshotFlow { request.ready() }.first { it }
        // The confirmed modal's last retained content must leave the native Popup first.
        withFrameNanos { }
        state.complete(request)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.observeComposerModalFocusInput(state: ComposerModalFocusReturn): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { state.userInput() }
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) state.userInput()
            false
        }
