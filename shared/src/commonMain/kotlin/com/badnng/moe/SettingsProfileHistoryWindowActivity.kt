package com.badnng.moe

import androidx.compose.runtime.Composable

/** Platform activity may be read and observed off the scene dispatcher. */
internal interface ProfileHistoryWindowActivity {
    fun active(): Boolean
    fun observe(onInactive: () -> Unit, onChange: () -> Unit): ProfileHistoryWindowActivityObserver
}

internal fun interface ProfileHistoryWindowActivityObserver { fun close() }

@Composable
internal expect fun rememberProfileHistoryWindowActivity(): ProfileHistoryWindowActivity
