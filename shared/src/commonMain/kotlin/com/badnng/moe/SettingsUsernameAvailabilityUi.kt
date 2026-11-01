package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.settings.SettingsActions
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.settings.SettingsUsernameAvailability
import com.badnng.moe.settings.SettingsUsernameAvailabilityStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Normalize the candidate without rewriting the displayed text or selection. Internal @ remains invalid. */
internal fun settingsUsernameCandidate(raw: String) = raw.trim().removePrefix("@").trim()

internal fun validSettingsUsernameCandidate(candidate: String) = candidate.isEmpty() || candidate.length in 5..32 &&
    (candidate.first() in 'a'..'z' || candidate.first() in 'A'..'Z') && candidate.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_'
    }

private class UsernameHintLease {
    private val alive = MutableStateFlow(true)
    fun active() = alive.value
    fun close() { alive.value = false }
}

internal enum class UsernameHintPhase { IDLE, DEBOUNCING, CHECKING, RESULT, ERROR }

internal class UsernameHintQuery(
    val rawText: String, val candidate: String, val textRevision: Long,
    private val editor: () -> Boolean, private val page: () -> Boolean,
) {
    private val alive = MutableStateFlow(true)
    fun current() = alive.value && editor() && page()
    fun close() { alive.value = false }
    override fun toString() = "UsernameHintQuery(<redacted>)"
}

/** One owner per editor/token/self/base identity. Only captured Flow leases cross the native boundary. */
internal class SettingsUsernameAvailabilityUiState(
    val token: SettingsToken, val userId: Long, val baseUsername: String, initialText: String,
    initialTextRevision: Long = 0L,
) {
    private val editorLease = UsernameHintLease()
    private var pageLease by mutableStateOf(UsernameHintLease())
    var rawText by mutableStateOf(initialText); private set
    var textRevision by mutableStateOf(initialTextRevision); private set
    var attemptRevision by mutableStateOf(0L); private set
    var phase by mutableStateOf(UsernameHintPhase.IDLE); private set
    var result by mutableStateOf<SettingsUsernameAvailability?>(null); private set
    var error by mutableStateOf<SettingsError?>(null); private set
    var timedOut by mutableStateOf(false); private set
    private var query: UsernameHintQuery? = null
    fun open() = editorLease.active()
    fun interactive() = open() && pageLease.active()
    fun captureInputLease(): () -> Boolean {
        val editor = editorLease
        val page = pageLease
        return { editor.active() && page.active() }
    }
    private fun clear() {
        query?.close(); query = null; result = null; error = null; timedOut = false; phase = UsernameHintPhase.IDLE
    }
    /** Called synchronously by TextField.onValueChange, including delete/retype of identical content. */
    fun changeText(next: String) {
        if (!open() || next == rawText) return
        clear(); rawText = next; ++textRevision
    }
    fun activate() {
        if (open() && !pageLease.active()) { pageLease = UsernameHintLease(); ++attemptRevision }
    }
    fun pause() { pageLease.close(); clear() }
    fun close() { editorLease.close(); pause() }
    fun retry() {
        if (!open() || !pageLease.active()) return
        clear(); ++attemptRevision
    }
    fun begin(): UsernameHintQuery? {
        if (!open() || !pageLease.active()) return null
        clear()
        val editor = editorLease
        val page = pageLease
        return UsernameHintQuery(rawText, settingsUsernameCandidate(rawText), textRevision, editor::active, page::active).also {
            query = it; phase = UsernameHintPhase.DEBOUNCING
        }
    }
    fun owns(expected: UsernameHintQuery) = open() && query === expected && expected.current() &&
        rawText == expected.rawText && textRevision == expected.textRevision
    fun checking(expected: UsernameHintQuery) { if (owns(expected)) phase = UsernameHintPhase.CHECKING }
    fun complete(expected: UsernameHintQuery, response: SettingsResult<SettingsUsernameAvailability>) {
        if (!owns(expected)) return
        val value = response.value?.takeIf { response.error == null && it.userId == userId && it.username == expected.candidate }
        result = value
        error = if (value == null) response.error ?: SettingsError.REQUEST_FAILED else null
        phase = if (value == null) UsernameHintPhase.ERROR else UsernameHintPhase.RESULT
        expected.close()
    }
    fun timeout(expected: UsernameHintQuery) {
        if (!owns(expected)) return
        timedOut = true; phase = UsernameHintPhase.ERROR; expected.close()
    }
    override fun toString() = "SettingsUsernameAvailabilityUiState(<redacted>)"
}

/** Deterministic offline timing seam; production always uses real cancellable coroutine delays. */
internal val LocalUsernameHintDelay = staticCompositionLocalOf<suspend (Long) -> Unit> { { delay(it) } }

/** Exact application callbacks for offline stale-input ownership tests; no production observer. */
internal class SettingsUsernameEditorUiProbe(
    val state: SettingsUsernameAvailabilityUiState, val value: TextFieldValue, val contentRevision: Long,
    val onValueChange: (TextFieldValue) -> Unit, val onSave: () -> Unit, val onCancel: () -> Unit,
) { override fun toString() = "SettingsUsernameEditorUiProbe(<redacted>)" }
internal val LocalSettingsUsernameEditorUiProbe = staticCompositionLocalOf<((SettingsUsernameEditorUiProbe) -> Unit)?> { null }

@Composable
internal fun SettingsUsernameAvailabilityUi(
    state: SettingsUsernameAvailabilityUiState, text: String, active: Boolean, actions: SettingsActions,
) {
    val scope = rememberCoroutineScope()
    val wait = LocalUsernameHintDelay.current
    val latestText by rememberUpdatedState(text)
    val latestActive by rememberUpdatedState(active)
    val latestActions by rememberUpdatedState(actions)
    val candidate = settingsUsernameCandidate(text)
    // Match the pinned ordinary UsernamesBox boundary; no check for empty or four-character input.
    val checkable = candidate.isNotEmpty() && validSettingsUsernameCandidate(candidate) &&
        (candidate != state.baseUsername || state.textRevision > 0L)
    DisposableEffect(state) { onDispose { state.close() } }
    DisposableEffect(state, active) {
        if (active) state.activate() else state.pause()
        onDispose { state.pause() }
    }
    fun current(query: UsernameHintQuery) = latestActive && latestText == query.rawText && state.owns(query)
    LaunchedEffect(state, text, state.textRevision, state.attemptRevision, active) {
        if (state.rawText != text) { state.changeText(text); return@LaunchedEffect }
        if (!active || !checkable) return@LaunchedEffect
        val query = state.begin() ?: return@LaunchedEffect
        try {
            wait(200)
            if (!current(query)) return@LaunchedEffect
            state.checking(query)
            val finished = CompletableDeferred<Unit>()
            val timeout = launch {
                wait(10_000)
                if (current(query)) state.timeout(query)
                finished.complete(Unit)
            }
            try {
                // This predicate closes over captured thread-safe leases only, never Compose state.
                latestActions.checkUsername(state.token, state.userId, query.candidate, query::current) { response ->
                    scope.launch {
                        if (current(query)) state.complete(query, response)
                        finished.complete(Unit)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(query)) state.complete(query, SettingsResult(error = SettingsError.REQUEST_FAILED))
                finished.complete(Unit)
            }
            try { finished.await() } finally { timeout.cancel() }
        } finally { query.close() }
    }
    when {
        !checkable || !active -> Unit
        state.phase == UsernameHintPhase.DEBOUNCING || state.phase == UsernameHintPhase.CHECKING -> Unit
        state.phase == UsernameHintPhase.ERROR -> {
            SettingsNote(if (state.timedOut) "检查超时，请重试；也可以尝试保存。" else "暂时无法检查用户名，仍可尝试保存。", error = true)
            ChatButton("重新检查用户名", enabled = state.open() && active, onClick = { if (latestActive && state.open()) state.retry() })
        }
        state.phase == UsernameHintPhase.RESULT -> state.result?.let { value ->
            SettingsNote(when (value.status) {
                SettingsUsernameAvailabilityStatus.CURRENT_USERNAME -> "这是你当前使用的用户名。"
                SettingsUsernameAvailabilityStatus.CAN_BE_SET -> "这个用户名可以设置，保存时会再次确认。"
                SettingsUsernameAvailabilityStatus.INVALID -> "这个用户名格式无效，请换一个。"
                SettingsUsernameAvailabilityStatus.OCCUPIED -> "这个用户名已被占用。"
                SettingsUsernameAvailabilityStatus.PURCHASABLE -> "这个用户名需要另行购买。"
                SettingsUsernameAvailabilityStatus.PUBLIC_CHATS_TOO_MANY -> "公开用户名数量已达上限。"
                SettingsUsernameAvailabilityStatus.PUBLIC_GROUPS_UNAVAILABLE -> "此账号暂时无法使用公开用户名。"
                SettingsUsernameAvailabilityStatus.UNKNOWN -> "暂时无法确认这个用户名，仍可尝试保存。"
            }, error = value.status !in setOf(SettingsUsernameAvailabilityStatus.CURRENT_USERNAME, SettingsUsernameAvailabilityStatus.CAN_BE_SET))
        }
    }
}
