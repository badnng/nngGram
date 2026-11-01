package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.badnng.moe.settings.SettingsProxyCheckRow
import com.badnng.moe.settings.SettingsProxyCheckStatus
import com.badnng.moe.settings.SettingsProxyChecksActions
import com.badnng.moe.settings.SettingsProxyChecksLoad
import com.badnng.moe.settings.SettingsProxyChecksToken
import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken
import com.badnng.moe.settings.SettingsProxyShareContent
import com.badnng.moe.settings.SettingsProxyShareRequest
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.presentation
import kotlinx.coroutines.flow.MutableStateFlow

/** The workspace owns this registry on the UI thread and revokes it before navigation. */
internal class SettingsProxyChecksPageOwner {
    var current by mutableStateOf<SettingsProxyChecksPageLease?>(null)
        private set

    fun install(lease: SettingsProxyChecksPageLease) {
        if (current !== lease) {
            current?.close()
            current = lease
        }
    }

    fun remove(lease: SettingsProxyChecksPageLease) {
        if (current === lease) current = null
        lease.close()
    }

    fun close() {
        val previous = current
        current = null
        previous?.close()
    }

    /** A retained header callback cannot refresh an owner installed after it was captured. */
    fun refresh(captured: SettingsProxyChecksPageLease?) {
        if (captured != null && owns(captured)) captured.refresh()
    }

    /** Called on the UI thread at the instant an action is submitted. */
    fun owns(captured: SettingsProxyChecksPageLease?): Boolean =
        captured != null && current === captured && captured.alive.value
}

/** Flow values are safe for the native callback and its thread-safe current predicate. */
internal class SettingsProxyChecksPageLease(private val parent: SettingsToken) {
    val alive = MutableStateFlow(true)
    val acknowledged = MutableStateFlow<SettingsProxyChecksToken?>(null)
    private val load = MutableStateFlow<SettingsProxyChecksLoad?>(null)
    private val shareRevoker = MutableStateFlow<(() -> Unit)?>(null)

    fun open(actions: SettingsProxyChecksActions) {
        if (!alive.value) return
        val handle = actions.open(parent, { alive.value }) { token ->
            if (alive.value) acknowledged.value = token?.takeIf { it.parent == parent }
        }
        // close() can race a delayed open return. Claim and close a returned handle once.
        check(load.compareAndSet(null, handle))
        if (!alive.value) closeHandle()
    }

    fun close() {
        if (!alive.compareAndSet(true, false)) return
        acknowledged.value = null
        takeShareRevoker()?.invoke()
        closeHandle()
    }

    fun refresh() {
        if (alive.value) {
            shareRevoker.value?.invoke()
            load.value?.refresh()
        }
    }

    /** Share work uses this exact page handle, never a newly installed visit. */
    fun share(request: SettingsProxyShareRequest, result: (SettingsResult<SettingsProxyShareContent>) -> Unit) {
        val handle = load.value
        if (!alive.value || request.token.parent != parent || acknowledged.value != request.token || handle == null) {
            result(SettingsResult(error = SettingsError.NOT_READY))
            return
        }
        handle.share(request) { response ->
            if (alive.value && acknowledged.value == request.token) result(response)
            else response.value?.close()
        }
    }

    /** The page closes sensitive transient UI content synchronously on revoke/refresh. */
    fun installShareRevoker(revoke: () -> Unit) {
        while (true) {
            val previous = shareRevoker.value
            if (shareRevoker.compareAndSet(previous, revoke)) {
                if (previous !== revoke) previous?.invoke()
                if (!alive.value && shareRevoker.compareAndSet(revoke, null)) revoke()
                return
            }
        }
    }

    fun removeShareRevoker(revoke: () -> Unit) {
        if (shareRevoker.compareAndSet(revoke, null)) revoke()
    }

    private fun takeShareRevoker(): (() -> Unit)? {
        while (true) {
            val current = shareRevoker.value ?: return null
            if (shareRevoker.compareAndSet(current, null)) return current
        }
    }

    private fun closeHandle() {
        while (true) {
            val handle = load.value ?: return
            if (load.compareAndSet(handle, null)) {
                handle.close()
                return
            }
        }
    }
}

/** A fresh lease belongs to each active visit, regardless of the retained outgoing page. */
@Composable
internal fun rememberSettingsProxyChecksToken(
    snapshot: SettingsSnapshot,
    actions: SettingsProxyChecksActions,
    owner: SettingsProxyChecksPageOwner,
    pageActive: Boolean,
    navigationRevision: Long,
): SettingsProxyChecksToken? {
    val active = pageActive && snapshot.enabled
    val lease = remember(owner, snapshot.token, active, navigationRevision) {
        if (active) SettingsProxyChecksPageLease(snapshot.token) else null
    }
    val noAcknowledgement = remember { MutableStateFlow<SettingsProxyChecksToken?>(null) }
    val inactive = remember { MutableStateFlow(false) }
    val acknowledged by (lease?.acknowledged ?: noAcknowledgement).collectAsState()
    val alive by (lease?.alive ?: inactive).collectAsState()
    DisposableEffect(lease) {
        if (lease != null) {
            owner.install(lease)
            lease.open(actions)
        }
        onDispose { if (lease != null) owner.remove(lease) }
    }
    return acknowledged.takeIf { active && alive && it?.parent == snapshot.token }
}

/** The redacted list's position and every exposed field are part of row ownership. */
internal fun settingsProxyCheckRows(
    snapshot: SettingsSnapshot,
    token: SettingsProxyChecksToken?,
): List<SettingsProxyCheckRow>? {
    val checks = snapshot.proxyChecks
    if (token == null || token.parent != snapshot.token || checks.token != token ||
        checks.rows.size != snapshot.proxies.size ||
        checks.rows.indices.any { checks.rows[it].index != it || checks.rows[it].proxy != snapshot.proxies[it] }) return null
    return checks.rows
}

internal fun settingsProxyCheckText(row: SettingsProxyCheckRow?): String = when (val status = row?.status) {
    SettingsProxyCheckStatus.Checking -> "正在检测……"
    is SettingsProxyCheckStatus.Available -> "延迟 ${status.milliseconds} ms"
    SettingsProxyCheckStatus.Unavailable -> "不可用"
    SettingsProxyCheckStatus.NotTested -> if (row.error == null) "未检测" else "未检测（检查未完成）"
    null -> "未检测"
}

internal fun settingsProxySummary(row: SettingsProxyCheckRow?, proxy: SettingsProxy, connection: ConnectionPhase): String =
    buildString {
        append(settingsProxyCheckText(row))
        if (proxy.enabled) append(" · 当前连接：${connection.presentation().label}")
    }
