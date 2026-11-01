package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.badnng.moe.media.LocalWindowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal actual fun rememberProfileHistoryWindowActivity(): ProfileHistoryWindowActivity {
    val activity = LocalWindowActivity.current
    return remember(activity) {
        object : ProfileHistoryWindowActivity {
            // Match existing playback semantics: a null preview host is not an inactive window.
            override fun active(): Boolean = activity?.value != false

            override fun observe(onInactive: () -> Unit, onChange: () -> Unit): ProfileHistoryWindowActivityObserver {
                if (activity == null) return ProfileHistoryWindowActivityObserver { }
                // As in observeWindowInactivity, collection does not depend on the scene dispatcher.
                // StateFlow can still conflate very short changes before a collector sees them.
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var initial = true
                    activity.collect { value ->
                        if (!value) onInactive()
                        if (!initial) onChange()
                        initial = false
                    }
                }
                return ProfileHistoryWindowActivityObserver { scope.cancel() }
            }
        }
    }
}
