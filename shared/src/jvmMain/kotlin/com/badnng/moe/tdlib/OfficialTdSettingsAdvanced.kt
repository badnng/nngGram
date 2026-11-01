package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsBlockedSender
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsProxy
import com.badnng.moe.settings.SettingsProxyDraft
import com.badnng.moe.settings.SettingsProxyType
import com.badnng.moe.settings.SettingsResult
import com.badnng.moe.settings.SettingsSession
import com.badnng.moe.settings.SettingsStorage
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Native account settings. Credentials exist only in the explicit proxy request, never in its snapshot. */
internal class OfficialTdSettingsAdvanced(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
    private val specialSecurityWrites: Boolean,
) {
    constructor(send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit) : this(send, false)
    /** TDLib's fast statistics include the database separately from its approximate cached-file total. */
    fun storage(result: (SettingsResult<SettingsStorage>) -> Unit) {
        request(TdApi.GetStorageStatisticsFast(), { response ->
            if (response is TdApi.StorageStatisticsFast && response.filesSize >= 0 &&
                response.fileCount >= 0 && response.databaseSize >= 0) {
                SettingsResult(SettingsStorage(response.filesSize, response.fileCount, response.databaseSize))
            } else failure(response)
        }, result)
    }

    /** Legacy signature has no explicit type selection or confirmation. */
    fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) {
        result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }

    fun proxies(result: (SettingsResult<List<SettingsProxy>>) -> Unit) {
        request(TdApi.GetProxies(), { response ->
            if (response is TdApi.AddedProxies) {
                SettingsResult(settingsProxyRows(response.proxies.orEmpty()))
            } else failure(response)
        }, result)
    }

    fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) {
        if (draft.type == SettingsProxyType.WEB) { result(SettingsResult(error = SettingsError.UNSUPPORTED)); return }
        val server = draft.server.trim()
        val secret = draft.secret.trim()
        if (server.isEmpty() || server.length > MAX_PROXY_FIELD_LENGTH ||
            server.any { it.isWhitespace() || it.isISOControl() } || draft.port !in 1..65535 ||
            draft.username.length > MAX_PROXY_FIELD_LENGTH || draft.password.length > MAX_PROXY_FIELD_LENGTH ||
            (draft.type == SettingsProxyType.MTPROTO &&
                (secret.isEmpty() || secret.length > MAX_PROXY_FIELD_LENGTH || secret.length % 2 != 0 ||
                    secret.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }))) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        val type = when (draft.type) {
            SettingsProxyType.SOCKS5 -> TdApi.ProxyTypeSocks5(draft.username, draft.password)
            SettingsProxyType.HTTP -> TdApi.ProxyTypeHttp(draft.username, draft.password, false)
            SettingsProxyType.MTPROTO -> TdApi.ProxyTypeMtproto(secret)
            SettingsProxyType.WEB -> error("Unsupported proxy draft")
        }
        // TDLib owns persistence and switching; this application never copies proxy credentials to preferences.
        request(TdApi.AddProxy(TdApi.Proxy(server, draft.port, type), true, ""), { response ->
            if (response is TdApi.AddedProxy && response.id > 0) SettingsResult(Unit) else failure(response)
        }, result)
    }

    fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) {
        if (id != null && id <= 0) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        request(if (id == null) TdApi.DisableProxy() else TdApi.EnableProxy(id), ::ok, result)
    }

    fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) {
        if (id <= 0) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        request(TdApi.RemoveProxy(id), ::ok, result)
    }

    fun sessions(result: (SettingsResult<List<SettingsSession>>) -> Unit) {
        request(TdApi.GetActiveSessions(), { response ->
            if (response is TdApi.Sessions) {
                SettingsResult(response.sessions.orEmpty().take(MAX_LIST_SIZE).filter { it.id != 0L }.map { session ->
                    SettingsSession(session.id, session.isCurrent, session.deviceModel.orEmpty(),
                        session.applicationName.orEmpty(), session.applicationVersion.orEmpty(),
                        session.platform.orEmpty(), session.systemVersion.orEmpty(), session.lastActiveDate,
                        session.ipAddress.orEmpty(), session.location.orEmpty())
                })
            } else failure(response)
        }, result)
    }

    fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) {
        // Native authorization hashes are signed int64 values, so negative IDs remain valid identities.
        if (id == 0L) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        request(TdApi.TerminateSession(id), ::ok, result)
    }

    fun accountTtl(result: (SettingsResult<Int>) -> Unit) {
        request(TdApi.GetAccountTtl(), { response ->
            if (response is TdApi.AccountTtl && response.days in ACCOUNT_TTL_DAYS) SettingsResult(response.days)
            else failure(response)
        }, result)
    }

    fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) {
        if (days !in ACCOUNT_TTL_DAYS) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        request(TdApi.SetAccountTtl(TdApi.AccountTtl(days)), ::ok, result)
    }

    /** Only the first native page is requested. Its size must never be described as the total blocked count. */
    fun blocked(result: (SettingsResult<List<SettingsBlockedSender>>) -> Unit) {
        request<TdApi.MessageSenders>(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), 0, BLOCKED_PAGE_SIZE),
            { response -> if (response is TdApi.MessageSenders) SettingsResult(response) else failure(response) }) { response ->
            val senders = response.value?.senders
            if (response.value == null) {
                result(copyFailure(response))
                return@request
            }
            val identities = senders.orEmpty().take(BLOCKED_PAGE_SIZE).mapNotNull { sender ->
                when (sender) {
                    is TdApi.MessageSenderUser -> if (sender.userId > 0) SettingsBlockedSender(sender.userId, false) else null
                    is TdApi.MessageSenderChat -> if (sender.chatId != 0L) SettingsBlockedSender(sender.chatId, true) else null
                    else -> null
                }
            }.distinctBy { it.isChat to it.id }
            resolveBlockedTitles(identities, result)
        }
    }

    fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) {
        if ((sender.isChat && sender.id == 0L) || (!sender.isChat && sender.id <= 0L)) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE))
            return
        }
        val identity = if (sender.isChat) TdApi.MessageSenderChat(sender.id) else TdApi.MessageSenderUser(sender.id)
        request(TdApi.SetMessageSenderBlockList(identity, null), ::ok, result)
    }

    private fun resolveBlockedTitles(
        identities: List<SettingsBlockedSender>,
        result: (SettingsResult<List<SettingsBlockedSender>>) -> Unit,
    ) {
        val resolved = ArrayList<SettingsBlockedSender>(identities.size)
        fun next() {
            if (resolved.size == identities.size) {
                result(SettingsResult(resolved.toList()))
                return
            }
            val identity = identities[resolved.size]
            val command = if (identity.isChat) TdApi.GetChat(identity.id) else TdApi.GetUser(identity.id)
            request(command, { response ->
                when {
                    response is TdApi.Chat && identity.isChat && response.id == identity.id ->
                        SettingsResult(response.title.orEmpty())
                    response is TdApi.User && !identity.isChat && response.id == identity.id ->
                        SettingsResult(listOf(response.firstName.orEmpty(), response.lastName.orEmpty())
                            .filter(String::isNotBlank).joinToString(" "))
                    else -> failure(response)
                }
            }) { response ->
                if (response.error == SettingsError.NOT_READY) {
                    result(copyFailure(response))
                } else {
                    // Failure retains only the actual native identity, with no invented user or chat name.
                    resolved += identity.copy(title = response.value.orEmpty())
                    next()
                }
            }
        }
        next()
    }

    private fun ok(response: TdApi.Object): SettingsResult<Unit> =
        if (response is TdApi.Ok) SettingsResult(Unit) else failure(response)

    private fun <T> copyFailure(response: SettingsResult<*>) =
        SettingsResult<T>(error = response.error ?: SettingsError.REQUEST_FAILED)

    private fun <T> failure(response: TdApi.Object? = null) =
        SettingsResult<T>(error = response?.let(::settingsError) ?: SettingsError.REQUEST_FAILED)

    private fun <T> request(
        command: TdApi.Function<*>,
        map: (TdApi.Object) -> SettingsResult<T>,
        result: (SettingsResult<T>) -> Unit,
    ) {
        val completed = AtomicBoolean()
        val specialWrite = specialSecurityWrites && (command is TdApi.TerminateSession || command is TdApi.SetMessageSenderBlockList)
        fun complete(value: SettingsResult<T>) {
            if (specialWrite && SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                if (!completed.get()) result(value)
            } else if (completed.compareAndSet(false, true)) result(value)
        }
        try {
            val receive: (TdApi.Object) -> Unit = { response ->
                val mapped = try { map(response) } catch (_: Exception) { failure<T>() }
                complete(mapped)
            }
            val failed = { complete(SettingsResult<T>(error = SettingsError.NOT_READY)) }
            if (specialWrite) SettingsSpecialMutationContext.write(command, send, receive, failed)
            else send(command, receive, failed)
        } catch (_: Exception) {
            complete(failure())
        }
    }

    private companion object {
        const val MAX_LIST_SIZE = 256
        const val BLOCKED_PAGE_SIZE = 100
        const val MAX_PROXY_FIELD_LENGTH = 1024
        val ACCOUNT_TTL_DAYS = 30..730
    }
}

/** Matches the bounded public projection used for an ADVANCED page read. */
internal fun settingsProxyRows(native: Array<out TdApi.AddedProxy>): List<SettingsProxy> {
    // Preserve native order, including when an enabled item must be kept past the budget.
    // This projection is not a persisted Desktop ordering guarantee.
    val enabledIndex = native.indexOfFirst { it.isEnabled }
    val bounded = if (native.size <= SETTINGS_PROXY_ROW_BUDGET || enabledIndex < SETTINGS_PROXY_ROW_BUDGET)
        native.take(SETTINGS_PROXY_ROW_BUDGET)
    else native.take(SETTINGS_PROXY_ROW_BUDGET - 1) + native[enabledIndex]
    return bounded.mapNotNull { added ->
        val proxy = added.proxy ?: return@mapNotNull null
        val type = when (proxy.type) {
            is TdApi.ProxyTypeSocks5 -> SettingsProxyType.SOCKS5
            is TdApi.ProxyTypeHttp -> SettingsProxyType.HTTP
            is TdApi.ProxyTypeMtproto -> SettingsProxyType.MTPROTO
            is TdApi.ProxyTypeWeb -> SettingsProxyType.WEB
            else -> return@mapNotNull null
        }
        if (added.id <= 0 || proxy.server.isNullOrBlank() || proxy.port !in 1..65535) null
        else SettingsProxy(added.id, proxy.server, proxy.port, type, added.isEnabled)
    }
}

private const val SETTINGS_PROXY_ROW_BUDGET = 256

/** Native error text never enters account snapshots, diagnostics, logs or presentation strings. */
internal fun settingsError(response: TdApi.Object): SettingsError {
    if (response !is TdApi.Error) return SettingsError.REQUEST_FAILED
    return when {
        response.code == 429 || response.message?.matches(Regex("FLOOD_(?:PREMIUM_)?WAIT_[0-9]+")) == true ->
            SettingsError.RATE_LIMITED
        response.code == 401 || response.message in setOf("AUTHORIZATION_REQUIRED", "AUTH_KEY_UNREGISTERED", "SESSION_REVOKED") ->
            SettingsError.NOT_READY
        response.code == 403 || response.message in setOf("FRESH_RESET_AUTHORISATION_FORBIDDEN", "PASSWORD_REQUIRED", "USER_RESTRICTED") ->
            SettingsError.FORBIDDEN
        response.message in setOf("PROXY_TYPE_UNSUPPORTED", "METHOD_NOT_AVAILABLE") -> SettingsError.UNSUPPORTED
        response.code == 400 -> SettingsError.INVALID_VALUE
        else -> SettingsError.REQUEST_FAILED
    }
}
