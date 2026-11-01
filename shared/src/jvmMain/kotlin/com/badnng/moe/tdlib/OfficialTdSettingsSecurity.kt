package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** Lazy account-management reads; this boundary never opens chats or downloads profile photographs. */
internal class OfficialTdSettingsSecurity(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    @Volatile private var physicalReceipt: SettingsSpecialWriteReceipt? = null
    fun sessions(result: (SettingsResult<SettingsSessionsData>) -> Unit) {
        request(TdApi.GetActiveSessions(), { response ->
            (response as? TdApi.Sessions)?.takeIf { native ->
                native.sessions != null && native.sessions.size <= MAX_SESSIONS && native.inactiveSessionTtlDays in 1..366 &&
                    native.sessions.all { it.id != 0L || it.isCurrent } && native.sessions.map { it.id }.distinct().size == native.sessions.size &&
                    native.sessions.count { it.isCurrent } == 1
            }?.let { native -> SettingsSessionsData(native.sessions.map { session ->
                SettingsSession(session.id, session.isCurrent, session.deviceModel.orEmpty(), session.applicationName.orEmpty(),
                    session.applicationVersion.orEmpty(), session.platform.orEmpty(), session.systemVersion.orEmpty(),
                    session.lastActiveDate, session.ipAddress.orEmpty(), session.location.orEmpty(),
                    session.logInDate, session.isPasswordPending, session.isUnconfirmed)
            }, native.inactiveSessionTtlDays) }
        }, result)
    }

    fun terminateOthers(expected: List<SettingsSession>, result: (SettingsResult<Unit>) -> Unit) {
        if (expected.size !in 2..MAX_SESSIONS || expected.count { it.isCurrent } != 1 || expected.any { it.id == 0L && !it.isCurrent } ||
            expected.map { it.id }.distinct().size != expected.size) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        sessions { fresh ->
            if (fresh.value == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else if (!sameSettingsDevices(expected, fresh.value.entries)) result(SettingsResult(error = SettingsError.CONFLICT))
            else request(TdApi.TerminateAllOtherSessions(), ::ok, result, mutation = true)
        }
    }

    fun setInactiveTtl(expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) {
        if (expected !in 1..366 || desired !in 1..366) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        sessions { fresh ->
            if (fresh.value == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else if (fresh.value.inactiveTtlDays != expected) result(SettingsResult(error = SettingsError.CONFLICT))
            else if (expected == desired) result(SettingsResult(Unit))
            else request(TdApi.SetInactiveSessionTtl(desired), ::ok, result, mutation = true)
        }
    }

    fun defaultAutoDelete(result: (SettingsResult<Int>) -> Unit) = request(TdApi.GetDefaultMessageAutoDeleteTime(), { response ->
        (response as? TdApi.MessageAutoDeleteTime)?.time?.takeIf(::validAutoDelete)
    }, result)

    fun setDefaultAutoDelete(expected: Int, desired: Int, result: (SettingsResult<Unit>) -> Unit) {
        if (!validAutoDelete(expected) || !validAutoDelete(desired)) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        defaultAutoDelete { fresh ->
            if (fresh.value == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else if (fresh.value != expected) result(SettingsResult(error = SettingsError.CONFLICT))
            else if (expected == desired) result(SettingsResult(Unit))
            else request(TdApi.SetDefaultMessageAutoDeleteTime(TdApi.MessageAutoDeleteTime(desired)), ::ok, result, mutation = true)
        }
    }

    private fun validAutoDelete(seconds: Int) = seconds in 0..365 * 86400 && seconds % 86400 == 0

    fun websites(result: (SettingsResult<SettingsWebsitesData>) -> Unit) {
        request(TdApi.GetConnectedWebsites(), { response ->
            (response as? TdApi.ConnectedWebsites)?.websites?.let { native ->
                SettingsWebsitesData(native.asSequence().filter { it.id != 0L && !it.domainName.isNullOrBlank() }
                    .sortedByDescending { if (it.lastActiveDate > 0) it.lastActiveDate else it.logInDate }
                    .take(MAX_WEBSITES).map { website ->
                        SettingsWebsite(website.id, website.domainName.orEmpty(), website.botUserId,
                            website.browser.orEmpty(), website.platform.orEmpty(), website.logInDate,
                            website.lastActiveDate, website.ipAddress.orEmpty(), website.location.orEmpty())
                    }.toList(), native.size)
            }
        }, result)
    }

    fun disconnect(target: SettingsWebsite, blockBot: Boolean, result: (SettingsResult<SettingsWebsiteDisconnectOutcome>) -> Unit) {
        if (target.id == 0L || target.domain.isBlank() || blockBot && target.botUserId <= 0) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        websites { fresh ->
            if (fresh.value == null) { result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED)); return@websites }
            if (fresh.value.entries.none { it.id == target.id && it.domain == target.domain && it.botUserId == target.botUserId }) {
                result(SettingsResult(error = SettingsError.NOT_READY)); return@websites
            }
            request(TdApi.DisconnectWebsite(target.id), ::ok, { disconnected ->
                if (disconnected.value == null) {
                    result(SettingsResult(SettingsWebsiteDisconnectOutcome(false), disconnected.error ?: SettingsError.REQUEST_FAILED))
                } else if (!blockBot) result(SettingsResult(SettingsWebsiteDisconnectOutcome(true)))
                else blockWebsiteBot(target.botUserId) { blocked ->
                    result(SettingsResult(SettingsWebsiteDisconnectOutcome(true, blocked.value != null), blocked.error))
                }
            }, mutation = true)
        }
    }

    fun disconnectAll(result: (SettingsResult<Unit>) -> Unit) = request(TdApi.DisconnectAllWebsites(), ::ok, result, mutation = true)

    private fun blockWebsiteBot(id: Long, result: (SettingsResult<Unit>) -> Unit) {
        request(TdApi.GetUser(id), { response ->
            (response as? TdApi.User)?.takeIf { it.id == id && it.type is TdApi.UserTypeBot }
        }) { user ->
            if (user.value == null) result(SettingsResult(error = user.error ?: SettingsError.UNSUPPORTED))
            else request(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(id), TdApi.BlockListMain()), ::ok, result, mutation = true)
        }
    }

    fun blockedPage(offset: Int, result: (SettingsResult<SettingsBlockedPage>) -> Unit) {
        if (offset < 0 || offset > Int.MAX_VALUE - BLOCKED_PAGE_SIZE) {
            result(SettingsResult(error = SettingsError.INVALID_VALUE)); return
        }
        val limit = if (offset == 0) FIRST_BLOCKED_PAGE_SIZE else BLOCKED_PAGE_SIZE
        request(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), offset, limit), { response ->
            (response as? TdApi.MessageSenders)?.takeIf { it.senders != null && it.senders.size <= limit }
        }) { page ->
            val native = page.value
            if (native == null) { result(SettingsResult(error = page.error ?: SettingsError.REQUEST_FAILED)); return@request }
            val raw = native.senders
            val next = offset + raw.size
            val total = native.totalCount.takeIf { it >= 0 }
            val end = raw.isEmpty() || total != null && next >= total
            val identities = raw.mapNotNull { sender -> when (sender) {
                is TdApi.MessageSenderUser -> sender.userId.takeIf { it > 0 }?.let { SettingsBlockedSender(it, false) }
                is TdApi.MessageSenderChat -> sender.chatId.takeIf { it != 0L }?.let { SettingsBlockedSender(it, true) }
                else -> null
            } }.distinctBy { it.isChat to it.id }
            resolveBlocked(identities) { resolved ->
                if (resolved.value == null) result(SettingsResult(error = resolved.error ?: SettingsError.REQUEST_FAILED))
                else result(SettingsResult(SettingsBlockedPage(resolved.value, next, total, end)))
            }
        }
    }

    private fun resolveBlocked(identities: List<SettingsBlockedSender>, result: (SettingsResult<List<SettingsBlockedSender>>) -> Unit) {
        val resolved = ArrayList<SettingsBlockedSender>(identities.size)
        fun next() {
            if (resolved.size == identities.size) { result(SettingsResult(resolved.toList())); return }
            val identity = identities[resolved.size]
            val command = if (identity.isChat) TdApi.GetChat(identity.id) else TdApi.GetUser(identity.id)
            request(command, { response -> when {
                response is TdApi.Chat && identity.isChat && response.id == identity.id -> response.title.orEmpty()
                response is TdApi.User && !identity.isChat && response.id == identity.id ->
                    listOf(response.firstName.orEmpty(), response.lastName.orEmpty()).filter(String::isNotBlank).joinToString(" ")
                else -> null
            } }) { response ->
                if (response.error == SettingsError.NOT_READY) result(SettingsResult(error = SettingsError.NOT_READY))
                else { resolved += identity.copy(title = response.value.orEmpty()); next() }
            }
        }
        next()
    }

    private fun ok(response: TdApi.Object) = if (response is TdApi.Ok) Unit else null
    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit) =
        request(command, map, result, mutation = false)
    private fun <T> request(command: TdApi.Function<*>, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit,
        mutation: Boolean,
    ) {
        val completed = AtomicBoolean()
        fun complete(value: SettingsResult<T>) {
            val receipt = SettingsSpecialMutationContext.receipt()
            if (mutation) physicalReceipt = receipt
            val unknown = mutation && receipt?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE
            if (unknown) {
                if (!completed.get()) SettingsSpecialMutationContext.withReceipt(physicalReceipt) { result(value) }
            } else if (completed.compareAndSet(false, true)) {
                SettingsSpecialMutationContext.withReceipt(physicalReceipt ?: receipt) { result(value) }
            }
        }
        val receive: (TdApi.Object) -> Unit = { response ->
            val mapped = runCatching { map(response) }.getOrNull()
            complete(if (mapped != null) SettingsResult(mapped) else SettingsResult(error = settingsError(response)))
        }
        val failed = { complete(SettingsResult<T>(error = SettingsError.NOT_READY)) }
        try {
            if (mutation) SettingsSpecialMutationContext.write(command, send, receive, failed)
            else send(command, receive, failed)
        }
        catch (_: Exception) { complete(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }

    private companion object {
        const val MAX_SESSIONS = 512
        const val MAX_WEBSITES = 512
        const val FIRST_BLOCKED_PAGE_SIZE = 16
        const val BLOCKED_PAGE_SIZE = 40
    }
}
