package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface TdPrivacyCommonSession {
    fun observe(observer: ((PrivacyCommonUpdate) -> Unit)?) {}
    fun load(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyCommonRead>) -> Unit)
    fun setTopPeers(expected: Boolean, desired: Boolean, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun setArchive(expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
    fun setAccountTtl(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit)
}

/** Account settings only. No contact upload, chat traversal or local message-deletion timers. */
internal class OfficialTdPrivacyCommon(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdPrivacyCommonSession {
    private val alive = AtomicBoolean(true)
    private val observer = AtomicReference<((PrivacyCommonUpdate) -> Unit)?>(null)
    private val topPeersVersion = AtomicLong()
    private val capabilityVersion = AtomicLong()
    private val latestCapability = AtomicReference(PrivacyArchiveCapability.UNKNOWN)
    fun retire() { alive.set(false); observer.set(null) }
    override fun observe(observer: ((PrivacyCommonUpdate) -> Unit)?) { this.observer.set(observer) }
    fun accept(update: TdApi.Object) {
        if (!alive.get() || update !is TdApi.UpdateOption) return
        val field = when (update.name) {
            "disable_top_chats" -> PrivacyCommonField.TOP_PEERS
            "can_archive_and_mute_new_chats_from_unknown_users" -> PrivacyCommonField.ARCHIVE_CAPABILITY
            else -> return
        }
        val value = if (field == PrivacyCommonField.TOP_PEERS) when (val native = update.value) {
            is TdApi.OptionValueBoolean -> !native.value
            is TdApi.OptionValueEmpty -> true
            else -> null
        } else (update.value as? TdApi.OptionValueBoolean)?.value
        if (field == PrivacyCommonField.TOP_PEERS) topPeersVersion.incrementAndGet()
        else {
            capabilityVersion.incrementAndGet()
            latestCapability.set(when (value) {
                true -> PrivacyArchiveCapability.ALLOWED
                false -> PrivacyArchiveCapability.DENIED
                null -> PrivacyArchiveCapability.UNKNOWN
            })
        }
        observer.get()?.invoke(PrivacyCommonUpdate(field, value))
    }

    override fun load(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyCommonRead>) -> Unit) {
        val remaining = AtomicInteger(4)
        val lock = Any()
        var data = PrivacyCommonData()
        val errors = mutableMapOf<PrivacyCommonField, SettingsError>()
        fun finish(field: PrivacyCommonField, error: SettingsError?, change: (PrivacyCommonData) -> PrivacyCommonData) {
            synchronized(lock) {
                if (error == null) data = change(data) else errors[field] = error
            }
            if (remaining.decrementAndGet() == 0) {
                val read = synchronized(lock) { PrivacyCommonRead(data, errors.toMap()) }
                result(if (current(isCurrent)) SettingsResult(read) else SettingsResult(error = SettingsError.NOT_READY))
            }
        }
        topPeers(isCurrent) { response -> finish(PrivacyCommonField.TOP_PEERS, response.error) { it.copy(topPeersEnabled = response.value) } }
        archive(isCurrent) { response -> finish(PrivacyCommonField.ARCHIVE, response.error) { it.copy(archive = response.value) } }
        capability(isCurrent) { response -> finish(PrivacyCommonField.ARCHIVE_CAPABILITY, response.error) {
            it.copy(archiveCapability = response.value ?: PrivacyArchiveCapability.UNKNOWN)
        } }
        accountTtl(isCurrent) { response -> finish(PrivacyCommonField.ACCOUNT_TTL, response.error) { it.copy(accountTtlDays = response.value) } }
    }

    override fun setTopPeers(expected: Boolean, desired: Boolean, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        topPeers(isCurrent) { fresh ->
            if (fresh.value == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else if (fresh.value != expected) result(SettingsResult(error = SettingsError.CONFLICT))
            else if (expected == desired) result(SettingsResult(Unit))
            else request(TdApi.SetOption("disable_top_chats", TdApi.OptionValueBoolean(!desired)), isCurrent, ::ok, result, finalWrite = true)
        }
    }

    override fun setArchive(expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings, isCurrent: () -> Boolean,
        result: (SettingsResult<Unit>) -> Unit,
    ) {
        archive(isCurrent) { fresh ->
            val native = fresh.value
            if (native == null) { result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED)); return@archive }
            fun changedBeforeRead(before: Boolean, after: Boolean, actual: Boolean) = before != after && actual != before
            if (changedBeforeRead(expected.archiveAndMuteNewChats, desired.archiveAndMuteNewChats, native.archiveAndMuteNewChats) ||
                changedBeforeRead(expected.keepUnmutedChatsArchived, desired.keepUnmutedChatsArchived, native.keepUnmutedChatsArchived) ||
                changedBeforeRead(expected.keepChatsFromFoldersArchived, desired.keepChatsFromFoldersArchived, native.keepChatsFromFoldersArchived)) {
                result(SettingsResult(error = SettingsError.CONFLICT)); return@archive
            }
            fun merge(before: Boolean, after: Boolean, actual: Boolean) = if (before == after) actual else after
            val merged = PrivacyArchiveSettings(
                merge(expected.archiveAndMuteNewChats, desired.archiveAndMuteNewChats, native.archiveAndMuteNewChats),
                merge(expected.keepUnmutedChatsArchived, desired.keepUnmutedChatsArchived, native.keepUnmutedChatsArchived),
                merge(expected.keepChatsFromFoldersArchived, desired.keepChatsFromFoldersArchived, native.keepChatsFromFoldersArchived))
            fun save() {
                if (merged.archiveAndMuteNewChats && !native.archiveAndMuteNewChats &&
                    latestCapability.get() != PrivacyArchiveCapability.ALLOWED) {
                    result(SettingsResult(error = SettingsError.CONFLICT)); return
                }
                if (merged == native) result(SettingsResult(Unit))
                else request(TdApi.SetArchiveChatListSettings(TdApi.ArchiveChatListSettings(merged.archiveAndMuteNewChats,
                    merged.keepUnmutedChatsArchived, merged.keepChatsFromFoldersArchived)), isCurrent, ::ok, result, finalWrite = true)
            }
            if (merged.archiveAndMuteNewChats && !native.archiveAndMuteNewChats) capability(isCurrent) { allowed ->
                when (allowed.value) {
                    PrivacyArchiveCapability.ALLOWED -> save()
                    PrivacyArchiveCapability.DENIED -> result(SettingsResult(error = SettingsError.FORBIDDEN))
                    else -> result(SettingsResult(error = allowed.error ?: SettingsError.NOT_READY))
                }
            } else save()
        }
    }

    override fun setAccountTtl(expected: Int, desired: Int, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
        if (expected !in 30..730 || desired !in 30..730) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        accountTtl(isCurrent) { fresh ->
            if (fresh.value == null) result(SettingsResult(error = fresh.error ?: SettingsError.REQUEST_FAILED))
            else if (fresh.value != expected) result(SettingsResult(error = SettingsError.CONFLICT))
            else if (expected == desired) result(SettingsResult(Unit))
            else request(TdApi.SetAccountTtl(TdApi.AccountTtl(desired)), isCurrent, ::ok, result, finalWrite = true)
        }
    }

    private fun topPeers(isCurrent: () -> Boolean, result: (SettingsResult<Boolean>) -> Unit) {
        val revision = topPeersVersion.get()
        request(TdApi.GetOption("disable_top_chats"), isCurrent, { native -> when (native) {
            is TdApi.OptionValueBoolean -> !native.value
            is TdApi.OptionValueEmpty -> true
            else -> null
        } }) { response ->
            result(if (topPeersVersion.get() == revision) response else SettingsResult(error = SettingsError.CONFLICT))
        }
    }
    private fun archive(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyArchiveSettings>) -> Unit) =
        request(TdApi.GetArchiveChatListSettings(), isCurrent, { native -> (native as? TdApi.ArchiveChatListSettings)?.let {
            PrivacyArchiveSettings(it.archiveAndMuteNewChatsFromUnknownUsers, it.keepUnmutedChatsArchived, it.keepChatsFromFoldersArchived)
        } }, result)
    private fun capability(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyArchiveCapability>) -> Unit) {
        val revision = capabilityVersion.get()
        request(TdApi.GetOption("can_archive_and_mute_new_chats_from_unknown_users"), isCurrent, { native -> when (native) {
            is TdApi.OptionValueBoolean -> if (native.value) PrivacyArchiveCapability.ALLOWED else PrivacyArchiveCapability.DENIED
            is TdApi.OptionValueEmpty -> PrivacyArchiveCapability.UNKNOWN
            else -> null
        } }) { response ->
            if (capabilityVersion.get() != revision) result(SettingsResult(error = SettingsError.CONFLICT))
            else { response.value?.let(latestCapability::set); result(response) }
        }
    }
    private fun accountTtl(isCurrent: () -> Boolean, result: (SettingsResult<Int>) -> Unit) =
        request(TdApi.GetAccountTtl(), isCurrent, { native -> (native as? TdApi.AccountTtl)?.days?.takeIf { it in 30..730 } }, result)
    private fun current(lease: () -> Boolean) = alive.get() && lease()
    private fun ok(native: TdApi.Object) = if (native is TdApi.Ok) Unit else null
    private fun <T> request(command: TdApi.Function<*>, lease: () -> Boolean, map: (TdApi.Object) -> T?,
        result: (SettingsResult<T>) -> Unit,
    ) = request(command, lease, map, result, false)

    private fun <T> request(command: TdApi.Function<*>, lease: () -> Boolean, map: (TdApi.Object) -> T?, result: (SettingsResult<T>) -> Unit, finalWrite: Boolean) {
        val once = AtomicBoolean()
        fun finish(value: SettingsResult<T>) {
            if (finalWrite && SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                if (!once.get()) result(value)
            } else if (once.compareAndSet(false, true)) result(value)
        }
        if (!current(lease)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        val dispatch: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
            if (finalWrite) { command, result, failed -> SettingsSpecialMutationContext.write(command, send, result, failed) } else send
        try { dispatch(command, { native ->
            if (!finalWrite && !current(lease)) finish(SettingsResult(error = SettingsError.NOT_READY))
            else {
                val mapped = runCatching { map(native) }.getOrNull()
                finish(if (mapped != null) SettingsResult(mapped) else SettingsResult(error = settingsError(native)))
            }
        }, { finish(SettingsResult(error = SettingsError.NOT_READY)) }) }
        catch (_: Exception) { finish(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
    }
}
