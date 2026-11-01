package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Bounded metadata operations; no history, read receipts, downloads, creation or opening chats. */
internal class OfficialTdNotificationExceptions(
    private val coordinator: NativeChatNotificationMutations,
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdNotificationExceptionsSession {
    private val alive = AtomicBoolean(true)
    @Volatile private var observer: ((Long?) -> Unit)? = null
    override fun observe(observer: ((Long?) -> Unit)?) { if (alive.get()) this.observer = observer }
    override fun retire() { alive.set(false); observer = null; coordinator.retire() }
    fun accept(update: TdApi.Object) {
        coordinator.accept(update)
        if (!alive.get()) return
        when (update) {
            is TdApi.UpdateChatNotificationSettings -> if (update.chatId != 0L) observer?.invoke(update.chatId)
            is TdApi.UpdateScopeNotificationSettings, is TdApi.UpdateUser, is TdApi.UpdateBasicGroup, is TdApi.UpdateSupergroup -> observer?.invoke(null)
            is TdApi.UpdateOption -> if (update.name == "replies_bot_chat_id" || update.name == "verification_codes_bot_chat_id") observer?.invoke(null)
        }
    }

    override fun list(scope: NotificationScope, active: () -> Boolean, result: (SettingsResult<List<Long>>) -> Unit) {
        request(TdApi.GetChatNotificationSettingsExceptions(scope.native(), true), active) { response ->
            val ids = (response.value as? TdApi.Chats)?.chatIds
            result(when {
                ids == null -> SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED)
                ids.size > 32768 -> SettingsResult(error = SettingsError.INVALID_VALUE)
                ids.any { it == 0L } -> SettingsResult(error = SettingsError.INVALID_VALUE)
                else -> SettingsResult(ids.distinct())
            })
        }
    }

    override fun resolve(scope: NotificationScope, ids: List<Long>, active: () -> Boolean,
        result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit,
    ) {
        if (ids.size > 32 || ids.any { it == 0L }) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        val selected = ids.distinct()
        context(active) ready@ { response ->
            val context = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            resolveAll(scope, selected, context, active, result)
        }
    }

    override fun search(scope: NotificationScope, query: String, remote: Boolean, active: () -> Boolean,
        result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit,
    ) {
        if (query.length > 128 || remote && query.isBlank()) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        context(active) ready@ { response ->
            val context = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            fun finish(ids: List<Long>) = resolveAll(scope, ids.distinct(), context, active) { resolved ->
                result(if (resolved.value == null) SettingsResult(error = resolved.error) else SettingsResult(
                    resolved.value.filter { it.error != SettingsError.FORBIDDEN && it.error != SettingsError.INVALID_VALUE }))
            }
            fun ids(command: TdApi.Function<*>, completed: (SettingsResult<List<Long>>) -> Unit) {
                request(command, active) { response ->
                    val values = (response.value as? TdApi.Chats)?.chatIds
                    completed(if (values == null || values.size > 64 || values.any { it == 0L })
                        SettingsResult(error = response.error ?: SettingsError.REQUEST_FAILED) else SettingsResult(values.toList()))
                }
            }
            if (query.isBlank()) {
                ids(TdApi.GetChats(TdApi.ChatListMain(), 64)) main@ { main ->
                    val first = main.value ?: run { result(SettingsResult(error = main.error)); return@main }
                    ids(TdApi.GetChats(TdApi.ChatListArchive(), 64)) archive@ { archive ->
                        val second = archive.value ?: run { result(SettingsResult(error = archive.error)); return@archive }
                        finish(first + second)
                    }
                }
            } else ids(if (remote) TdApi.SearchChatsOnServer(query, null, 64) else TdApi.SearchChats(query, null, 64)) {
                if (it.value == null) result(SettingsResult(error = it.error)) else finish(it.value)
            }
        }
    }

    override fun read(scope: NotificationScope, chatId: Long, active: () -> Boolean,
        result: (SettingsResult<NotificationExceptionPeer>) -> Unit,
    ) = readInternal(scope, chatId, active, null, result)

    override fun save(edit: NotificationExceptionEdit, active: () -> Boolean,
        result: (NotificationExceptionWriteResult) -> Unit,
    ) {
        val expected = edit.expected
        val dirty = edit.dirty.toSet()
        val desired = edit.desired
        if (!expected.editable || expected.chatId == 0L || dirty.isEmpty() || expected.sampledAtMillis <= 0 ||
            expected.sampledAtMillis > System.currentTimeMillis() + 5000 ||
            NotificationExceptionField.MUTE in dirty && !desired.useDefaultMuteFor && desired.muteFor < 0) {
            result(NotificationExceptionWriteResult(error = SettingsError.INVALID_VALUE)); return
        }
        val once = AtomicBoolean()
        fun complete(value: NotificationExceptionWriteResult) {
            if (value.receipt == NotificationSettingsWriteReceipt.INDETERMINATE) {
                if (!once.get()) result(value)
            } else if (once.compareAndSet(false, true)) result(value)
        }
        coordinator.submit(expected.chatId, { current(active) }, { complete(NotificationExceptionWriteResult(error = it)) }) { lease ->
            val version = lease.revision
            fun finish(value: NotificationExceptionWriteResult) {
                try { complete(value) } finally {
                    if (value.receipt != NotificationSettingsWriteReceipt.INDETERMINATE) lease.finish()
                }
            }
            readInternal(expected.scope, expected.chatId, active, lease) fresh@ { response ->
                val fresh = response.value ?: run { finish(NotificationExceptionWriteResult(error = response.error)); return@fresh }
                if (response.error != null) {
                    finish(NotificationExceptionWriteResult(actual = fresh, error = response.error)); return@fresh
                }
                if (!matches(expected, fresh, dirty)) {
                    finish(NotificationExceptionWriteResult(actual = fresh, error = SettingsError.CONFLICT)); return@fresh
                }
                val merged = merge(fresh.values, desired, dirty)
                val submitted = fresh.copy(values = merged, sampledAtMillis = System.currentTimeMillis())
                var receipt = NotificationSettingsWriteReceipt.NOT_DISPATCHED
                lease.request(TdApi.SetChatNotificationSettings(expected.chatId, merged.native()), accepted@ { accepted ->
                    receipt = SettingsSpecialMutationContext.receipt()?.let {
                        NotificationSettingsWriteReceipt.valueOf(it.outcome.name)
                    } ?: if (accepted.value is TdApi.Ok) NotificationSettingsWriteReceipt.ACCEPTED
                        else NotificationSettingsWriteReceipt.NOT_DISPATCHED
                    if (accepted.value !is TdApi.Ok) {
                        finish(NotificationExceptionWriteResult(error = accepted.error ?: SettingsError.REQUEST_FAILED, receipt = receipt)); return@accepted
                    }
                    readInternal(expected.scope, expected.chatId, active, lease) { actual ->
                        val error = actual.error ?: if (actual.value != null && !matches(submitted, actual.value, dirty)) SettingsError.CONFLICT else null
                        finish(NotificationExceptionWriteResult(true, actual.value, error, receipt))
                    }
                }, expectedRevision = version, preserveAccepted = true, specialWrite = true)
            }
        }
    }

    private data class Context(val userId: Long, val excludedChats: Set<Long>) {
        override fun toString() = "NotificationExceptionContext(<redacted>)"
    }
    private fun context(active: () -> Boolean, lease: NativeChatNotificationMutations.Lease? = null,
        result: (SettingsResult<Context>) -> Unit,
    ) {
        request(TdApi.GetMe(), active, lease) me@ { response ->
            val me = response.value as? TdApi.User
            if (me == null || me.id <= 0) { result(SettingsResult(error = response.error ?: SettingsError.NOT_READY)); return@me }
            request(TdApi.GetOption("replies_bot_chat_id"), active, lease) replies@ { response ->
                val replies = (response.value as? TdApi.OptionValueInteger)?.value?.takeIf { it != 0L }
                if (replies == null) { result(SettingsResult(error = response.error ?: SettingsError.NOT_READY)); return@replies }
                request(TdApi.GetOption("verification_codes_bot_chat_id"), active, lease) verify@ { response ->
                    val verify = (response.value as? TdApi.OptionValueInteger)?.value?.takeIf { it != 0L }
                    result(if (verify == null) SettingsResult(error = response.error ?: SettingsError.NOT_READY)
                        else SettingsResult(Context(me.id, setOf(replies, verify))))
                }
            }
        }
    }

    private fun resolveAll(scope: NotificationScope, ids: List<Long>, context: Context, active: () -> Boolean,
        result: (SettingsResult<List<NotificationExceptionRow>>) -> Unit,
    ) {
        val rows = mutableListOf<NotificationExceptionRow>()
        fun next(index: Int) {
            if (!current(active)) { result(SettingsResult(error = SettingsError.NOT_READY)); return }
            if (index == ids.size) { result(SettingsResult(rows.toList())); return }
            resolvePeer(scope, ids[index], context, active, null) { response ->
                rows += NotificationExceptionRow(ids[index], response.value, response.error)
                next(index + 1)
            }
        }
        next(0)
    }

    private fun readInternal(scope: NotificationScope, chatId: Long, active: () -> Boolean,
        lease: NativeChatNotificationMutations.Lease?, result: (SettingsResult<NotificationExceptionPeer>) -> Unit,
    ) {
        if (chatId == 0L) { result(SettingsResult(error = SettingsError.INVALID_VALUE)); return }
        context(active, lease) ready@ { response ->
            val context = response.value ?: run { result(SettingsResult(error = response.error)); return@ready }
            resolvePeer(scope, chatId, context, active, lease, result)
        }
    }

    private fun resolvePeer(scope: NotificationScope, chatId: Long, context: Context, active: () -> Boolean,
        lease: NativeChatNotificationMutations.Lease?, result: (SettingsResult<NotificationExceptionPeer>) -> Unit,
    ) {
        if (chatId in context.excludedChats) { result(SettingsResult(error = SettingsError.FORBIDDEN)); return }
        if (lease == null) {
            coordinator.submit(chatId, { current(active) }, { result(SettingsResult(error = it)) }) { readLease ->
                resolvePeer(scope, chatId, context, active, readLease) { response ->
                    try { result(response) } finally { readLease.finish() }
                }
            }
            return
        }
        val version = lease.revision
        request(TdApi.GetChat(chatId), active, lease) chat@ { response ->
            val chat = response.value as? TdApi.Chat
            val settings = chat?.notificationSettings
            if (chat?.id != chatId || settings == null || settings.muteFor < 0) {
                result(SettingsResult(error = response.error ?: SettingsError.INVALID_VALUE)); return@chat
            }
            val sampled = System.currentTimeMillis()
            fun finish(valid: Boolean, error: SettingsError? = null) {
                val peer = if (valid) NotificationExceptionPeer(chatId, scope, chat.title.orEmpty(),
                    nativeChatNotificationValues(settings), sampled) else null
                result(if (!current(active)) SettingsResult(error = SettingsError.NOT_READY)
                    else if (!valid) SettingsResult(error = error ?: SettingsError.FORBIDDEN)
                    else if (lease.revision != version) SettingsResult(peer, SettingsError.CONFLICT)
                    else SettingsResult(peer))
            }
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> {
                    if (scope != NotificationScope.PRIVATE_CHATS || type.userId <= 0 || type.userId == context.userId) {
                        finish(false); return@chat
                    }
                    request(TdApi.GetUser(type.userId), active, lease) { user ->
                        val value = user.value as? TdApi.User
                        finish(value != null && value.id == type.userId && (value.type is TdApi.UserTypeRegular || value.type is TdApi.UserTypeBot), user.error)
                    }
                }
                is TdApi.ChatTypeBasicGroup -> {
                    if (scope != NotificationScope.GROUP_CHATS || type.basicGroupId <= 0) { finish(false); return@chat }
                    request(TdApi.GetBasicGroup(type.basicGroupId), active, lease) { group ->
                        val value = group.value as? TdApi.BasicGroup
                        finish(value != null && value.id == type.basicGroupId && value.isActive && member(value.status), group.error)
                    }
                }
                is TdApi.ChatTypeSupergroup -> {
                    val actualScope = if (type.isChannel) NotificationScope.CHANNEL_CHATS else NotificationScope.GROUP_CHATS
                    if (scope != actualScope || type.supergroupId <= 0) { finish(false); return@chat }
                    request(TdApi.GetSupergroup(type.supergroupId), active, lease) { group ->
                        val value = group.value as? TdApi.Supergroup
                        finish(value != null && value.id == type.supergroupId && value.isChannel == type.isChannel &&
                            !value.isDirectMessagesGroup && member(value.status), group.error)
                    }
                }
                else -> finish(false)
            }
        }
    }

    private fun member(status: TdApi.ChatMemberStatus?) = when (status) {
        is TdApi.ChatMemberStatusCreator -> status.isMember
        is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusMember -> true
        is TdApi.ChatMemberStatusRestricted -> status.isMember
        else -> false
    }

    private fun matches(expected: NotificationExceptionPeer, fresh: NotificationExceptionPeer,
        dirty: Set<NotificationExceptionField>,
    ) = expected.chatId == fresh.chatId && expected.scope == fresh.scope && dirty.all { field ->
        val a = expected.values; val b = fresh.values
        when (field) {
            NotificationExceptionField.MUTE -> a.useDefaultMuteFor == b.useDefaultMuteFor &&
                (a.useDefaultMuteFor || sameMute(a.muteFor, expected.sampledAtMillis, b.muteFor, fresh.sampledAtMillis))
            NotificationExceptionField.SOUND -> {
                val inheritedA = a.useDefaultSound || a.soundId == -1L
                val inheritedB = b.useDefaultSound || b.soundId == -1L
                inheritedA == inheritedB && (inheritedA || a.soundId == b.soundId)
            }
            NotificationExceptionField.PREVIEW -> a.useDefaultShowPreview == b.useDefaultShowPreview &&
                (a.useDefaultShowPreview || a.showPreview == b.showPreview)
            NotificationExceptionField.PINNED -> a.useDefaultDisablePinnedMessageNotifications == b.useDefaultDisablePinnedMessageNotifications &&
                (a.useDefaultDisablePinnedMessageNotifications || a.disablePinnedMessageNotifications == b.disablePinnedMessageNotifications)
            NotificationExceptionField.MENTIONS -> a.useDefaultDisableMentionNotifications == b.useDefaultDisableMentionNotifications &&
                (a.useDefaultDisableMentionNotifications || a.disableMentionNotifications == b.disableMentionNotifications)
        }
    }

    private fun sameMute(a: Int, sampledA: Long, b: Int, sampledB: Long): Boolean {
        if (a < 0 || b < 0) return false
        if (a == 0) return b == 0
        val deadlineA = muteDeadline(a, sampledA)
        if (b == 0) return deadlineA <= sampledB + 2500
        return abs(deadlineA - muteDeadline(b, sampledB)) <= 2500
    }
    private fun muteDeadline(seconds: Int, sampled: Long) = if (seconds > 366 * 86400) Int.MAX_VALUE.toLong() * 1000
        else (sampled + seconds.toLong() * 1000).coerceAtMost(Int.MAX_VALUE.toLong() * 1000)

    private fun merge(fresh: ChatNotificationValues, desired: ChatNotificationValues,
        dirty: Set<NotificationExceptionField>,
    ): ChatNotificationValues {
        var value = fresh
        for (field in dirty) value = when (field) {
            NotificationExceptionField.MUTE -> value.copy(useDefaultMuteFor = desired.useDefaultMuteFor,
                muteFor = if (desired.useDefaultMuteFor) fresh.muteFor else desired.muteFor)
            NotificationExceptionField.SOUND -> value.copy(useDefaultSound = desired.useDefaultSound || desired.soundId == -1L,
                soundId = if (desired.useDefaultSound || desired.soundId == -1L) fresh.soundId else desired.soundId)
            NotificationExceptionField.PREVIEW -> value.copy(useDefaultShowPreview = desired.useDefaultShowPreview,
                showPreview = if (desired.useDefaultShowPreview) fresh.showPreview else desired.showPreview)
            NotificationExceptionField.PINNED -> value.copy(useDefaultDisablePinnedMessageNotifications = desired.useDefaultDisablePinnedMessageNotifications,
                disablePinnedMessageNotifications = if (desired.useDefaultDisablePinnedMessageNotifications) fresh.disablePinnedMessageNotifications else desired.disablePinnedMessageNotifications)
            NotificationExceptionField.MENTIONS -> value.copy(useDefaultDisableMentionNotifications = desired.useDefaultDisableMentionNotifications,
                disableMentionNotifications = if (desired.useDefaultDisableMentionNotifications) fresh.disableMentionNotifications else desired.disableMentionNotifications)
        }
        return value
    }

    private fun current(active: () -> Boolean) = alive.get() && runCatching(active).getOrDefault(false)
    private fun request(command: TdApi.Function<*>, active: () -> Boolean,
        lease: NativeChatNotificationMutations.Lease? = null, result: (SettingsResult<TdApi.Object>) -> Unit,
    ) {
        if (lease != null) { lease.request(command, result); return }
        val once = AtomicBoolean()
        fun finish(response: SettingsResult<TdApi.Object>) { if (once.compareAndSet(false, true)) result(response) }
        if (!current(active)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try { send(command, received@ { response ->
            if (!once.compareAndSet(false, true)) return@received
            result(when {
                !current(active) -> SettingsResult(error = SettingsError.NOT_READY)
                response is TdApi.Error -> SettingsResult(error = settingsError(response))
                else -> SettingsResult(response)
            })
        }, { finish(SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)) }) }
        catch (_: Exception) { finish(SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)) }
    }
}
