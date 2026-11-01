package com.badnng.moe.media

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Native window events remain observable when Tao parks the minimized scene's dispatcher. */
class WindowActivityGate(
    focused: StateFlow<Boolean>,
    minimized: StateFlow<Boolean>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableActive = MutableStateFlow(focused.value && !minimized.value)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    init {
        scope.launch {
            combine(focused, minimized) { focus, minimizedNow -> focus && !minimizedNow }
                .collect { mutableActive.value = it }
        }
    }

    override fun close() { scope.cancel() }
}

/** Optional for previews; production always supplies the native window's activity flow. */
val LocalWindowActivity = staticCompositionLocalOf<StateFlow<Boolean>?> { null }

/** This observer only suspends work. Regaining focus must never undo a user's pause. */
fun observeWindowInactivity(
    activity: StateFlow<Boolean>,
    onInactive: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        activity.collect { if (!it) onInactive() }
    }
    return AutoCloseable { scope.cancel() }
}
