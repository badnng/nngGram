package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/** One per native session. A lane owns the entire fresh/read -> Set -> readback transaction. */
internal class NativeChatNotificationMutations(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) {
    private val lock = Any()
    private var alive = true
    private val lanes = mutableMapOf<Long, Lane>()
    private var count = 0
    internal class Lane {
        var revision = 0L
        var running: Work? = null
        val queued = ArrayDeque<Work>()
    }
    internal class Work(val active: () -> Boolean, val rejected: (SettingsError) -> Unit, val body: (Lease) -> Unit) {
        val done = AtomicBoolean()
    }

    inner class Lease internal constructor(private val chatId: Long, private val lane: Lane, private val work: Work) {
        val revision: Long get() = synchronized(lock) { lane.revision }
        fun current() = synchronized(lock) { alive && !work.done.get() && runCatching(work.active).getOrDefault(false) }
        fun finish() {
            if (!work.done.compareAndSet(false, true)) return
            synchronized(lock) {
                count--
                if (lane.running === work) lane.running = null
                advance(chatId, lane)
            }
        }
        fun request(command: TdApi.Function<*>, result: (SettingsResult<TdApi.Object>) -> Unit,
            expectedRevision: Long? = null, preserveAccepted: Boolean = false, specialWrite: Boolean = false,
        ) {
            val once = AtomicBoolean()
            fun complete(value: SettingsResult<TdApi.Object>) {
                if (specialWrite && SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                    if (!once.get()) result(value)
                } else if (once.compareAndSet(false, true)) result(value)
            }
            synchronized(lock) {
                if (!current()) { complete(SettingsResult(error = SettingsError.NOT_READY)); return }
                if (expectedRevision != null && lane.revision != expectedRevision) {
                    complete(SettingsResult(error = SettingsError.CONFLICT)); return
                }
                try {
                    val dispatch: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
                        if (specialWrite) { command, result, failed ->
                            SettingsSpecialMutationContext.write(command, send, result, failed)
                        } else send
                    dispatch(command, received@ { response ->
                        if ((!specialWrite || SettingsSpecialMutationContext.receipt()?.outcome != SettingsSpecialWriteOutcome.INDETERMINATE) &&
                            !once.compareAndSet(false, true)) return@received
                        val value = synchronized(lock) {
                            when {
                                specialWrite && response is TdApi.Error -> SettingsResult(error = settingsError(response))
                                preserveAccepted && response is TdApi.Ok -> SettingsResult<TdApi.Object>(response)
                                !current() -> SettingsResult(error = SettingsError.NOT_READY)
                                response is TdApi.Error -> SettingsResult(error = settingsError(response))
                                else -> SettingsResult(response)
                            }
                        }
                        result(value)
                    }, {
                        val rejected = if (specialWrite) SettingsSpecialMutationContext.receipt()?.error else null
                        complete(SettingsResult(error = rejected ?: if (current()) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
                    })
                } catch (_: Exception) {
                    complete(SettingsResult(error = if (current()) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
                }
            }
        }
    }

    fun submit(chatId: Long, active: () -> Boolean, rejected: (SettingsError) -> Unit, body: (Lease) -> Unit) {
        synchronized(lock) {
            if (!alive || chatId == 0L || !runCatching(active).getOrDefault(false)) { runCatching { rejected(SettingsError.NOT_READY) }; return }
            if (count >= 128) { runCatching { rejected(SettingsError.RATE_LIMITED) }; return }
            val lane = lanes.getOrPut(chatId) { Lane() }
            if (lane.queued.size >= 32) { runCatching { rejected(SettingsError.RATE_LIMITED) }; return }
            lane.queued.addLast(Work(active, rejected, body)); count++
            advance(chatId, lane)
        }
    }

    private fun advance(chatId: Long, lane: Lane) {
        if (lane.running != null) return
        while (lane.queued.isNotEmpty()) {
            val work = lane.queued.removeFirst()
            if (!alive || !runCatching(work.active).getOrDefault(false)) {
                if (work.done.compareAndSet(false, true)) { count--; runCatching { work.rejected(SettingsError.NOT_READY) } }
                continue
            }
            lane.running = work
            val lease = Lease(chatId, lane, work)
            try { work.body(lease) }
            catch (_: Exception) {
                if (!work.done.get()) try { runCatching { work.rejected(SettingsError.REQUEST_FAILED) } } finally { lease.finish() }
            }
            return
        }
        if (lanes[chatId] === lane) lanes.remove(chatId)
    }

    fun accept(update: TdApi.Object) = synchronized(lock) {
        when (update) {
            is TdApi.UpdateChatNotificationSettings -> lanes[update.chatId]?.let { it.revision++ }
            is TdApi.UpdateScopeNotificationSettings -> lanes.values.forEach { it.revision++ }
            is TdApi.UpdateUser, is TdApi.UpdateBasicGroup, is TdApi.UpdateSupergroup -> lanes.values.forEach { it.revision++ }
            is TdApi.UpdateOption -> if (update.name == "replies_bot_chat_id" || update.name == "verification_codes_bot_chat_id")
                lanes.values.forEach { it.revision++ }
            else -> Unit
        }
    }

    fun retire() = synchronized(lock) {
        if (!alive) return@synchronized
        alive = false
        lanes.toList().forEach { (id, lane) ->
            while (lane.queued.isNotEmpty()) {
                val work = lane.queued.removeFirst()
                if (work.done.compareAndSet(false, true)) { count--; runCatching { work.rejected(SettingsError.NOT_READY) } }
            }
            if (lane.running == null) lanes.remove(id)
        }
    }

    /** The existing chat-list action has no page lease, but shares the same bounded native lane. */
    fun mute(chatId: Long, muted: Boolean, result: (ChatResult<Unit>) -> Unit) {
        val once = AtomicBoolean()
        fun finish(accepted: Boolean, error: SettingsError? = null) {
            if (once.compareAndSet(false, true)) result(if (accepted) ChatResult(Unit) else ChatResult(error = when (error) {
                SettingsError.NOT_READY -> ChatError.NOT_READY
                SettingsError.FORBIDDEN -> ChatError.FORBIDDEN
                SettingsError.RATE_LIMITED -> ChatError.RATE_LIMITED
                else -> ChatError.REQUEST_FAILED
            }))
        }
        submit(chatId, { true }, { finish(false, it) }) { lease ->
            val version = lease.revision
            fun done(accepted: Boolean, error: SettingsError? = null) { try { finish(accepted, error) } finally { lease.finish() } }
            lease.request(TdApi.GetChat(chatId), fresh@ { response ->
                val chat = response.value as? TdApi.Chat
                val original = chat?.notificationSettings
                if (chat?.id != chatId || original == null) { done(false, response.error); return@fresh }
                val settings = nativeChatNotificationValues(original).copy(useDefaultMuteFor = false,
                    muteFor = if (muted) Int.MAX_VALUE else 0)
                lease.request(TdApi.SetChatNotificationSettings(chatId, settings.native()), accepted@ { accepted ->
                    if (accepted.value !is TdApi.Ok) { done(false, accepted.error); return@accepted }
                    // Keep actual local acceptance even if its owner retired before readback.
                    lease.request(TdApi.GetChat(chatId), { done(true) })
                }, expectedRevision = version, preserveAccepted = true)
            })
        }
    }
}

internal fun nativeChatNotificationValues(value: TdApi.ChatNotificationSettings) = ChatNotificationValues(
    value.useDefaultMuteFor, value.muteFor, value.useDefaultSound || value.soundId == -1L, value.soundId,
    value.useDefaultShowPreview, value.showPreview, value.useDefaultMuteStories, value.muteStories,
    value.useDefaultStorySound, value.storySoundId, value.useDefaultShowStoryPoster, value.showStoryPoster,
    value.useDefaultDisablePinnedMessageNotifications, value.disablePinnedMessageNotifications,
    value.useDefaultDisableMentionNotifications, value.disableMentionNotifications)

internal fun ChatNotificationValues.native() = TdApi.ChatNotificationSettings(
    useDefaultMuteFor, muteFor, useDefaultSound, soundId, useDefaultShowPreview, showPreview,
    useDefaultMuteStories, muteStories, useDefaultStorySound, storySoundId, useDefaultShowStoryPoster, showStoryPoster,
    useDefaultDisablePinnedMessageNotifications, disablePinnedMessageNotifications,
    useDefaultDisableMentionNotifications, disableMentionNotifications)
