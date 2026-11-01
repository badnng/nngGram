package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi

/**
 * Keeps the latest early reaction-notification settings update until its native receiver is ready.
 * Construct before Client.create and bind once the settings receiver exists.
 */
internal class TdReactionNotificationsEarlyUpdates {
    private val monitor = Any()

    private var retired = false
    private var bound = false
    private var target: ((TdApi.Object) -> Unit)? = null
    private var draining = false
    private var pending: Snapshot? = null

    /** Accepts only reaction-notification settings updates; all other TDLib objects are ignored. */
    fun accept(update: TdApi.Object) {
        val reactionUpdate = update as? TdApi.UpdateReactionNotificationSettings ?: return
        val shouldDrain = synchronized(monitor) {
            if (retired) return
            // This buffer is intentionally one slot: settings are state, so the latest full value wins.
            pending = snapshot(reactionUpdate)
            if (target != null && !draining) {
                draining = true
                true
            } else false
        }
        if (shouldDrain) drain()
    }

    /** Binds at most once and synchronously starts an ordered serial drain. */
    fun bind(target: (TdApi.Object) -> Unit) {
        val shouldDrain = synchronized(monitor) {
            if (retired || bound) return
            bound = true
            this.target = target
            if (!draining && pending != null) {
                draining = true
                true
            } else false
        }
        if (shouldDrain) drain()
    }

    /** Drops the cached update and prevents any later forwarding or binding. */
    fun retire() {
        synchronized(monitor) {
            retired = true
            target = null
            pending = null
        }
    }

    private fun drain() {
        while (true) {
            val delivery = synchronized(monitor) {
                if (retired || target == null) {
                    pending = null
                    draining = false
                    return
                }
                val next = pending
                if (next == null) {
                    draining = false
                    return
                }
                pending = null
                Delivery(target!!, next)
            }
            // The settings receiver is foreign code and may reenter this collector.
            try {
                delivery.target(delivery.snapshot.toUpdate())
            } catch (_: Exception) {
                // A failed callback must not wedge the serial drain or retain an older update.
            }
        }
    }

    private fun snapshot(update: TdApi.UpdateReactionNotificationSettings): Snapshot {
        val settings = update.notificationSettings ?: return InvalidSnapshot
        val message = sourceSnapshot(settings.messageReactionSource) ?: return InvalidSnapshot
        val story = sourceSnapshot(settings.storyReactionSource) ?: return InvalidSnapshot
        val poll = sourceSnapshot(settings.pollVoteSource) ?: return InvalidSnapshot
        return SettingsSnapshot(message, story, poll, settings.soundId, settings.showPreview)
    }

    private fun sourceSnapshot(source: TdApi.ReactionNotificationSource?): SourceSnapshot? = when (source) {
        is TdApi.ReactionNotificationSourceNone -> SourceSnapshot.NONE
        is TdApi.ReactionNotificationSourceContacts -> SourceSnapshot.CONTACTS
        is TdApi.ReactionNotificationSourceAll -> SourceSnapshot.ALL
        else -> null
    }

    private data class Delivery(val target: (TdApi.Object) -> Unit, val snapshot: Snapshot)

    private sealed interface Snapshot {
        fun toUpdate(): TdApi.UpdateReactionNotificationSettings
    }

    private data object InvalidSnapshot : Snapshot {
        override fun toUpdate() = TdApi.UpdateReactionNotificationSettings()
    }

    private data class SettingsSnapshot(
        val message: SourceSnapshot,
        val story: SourceSnapshot,
        val poll: SourceSnapshot,
        val soundId: Long,
        val showPreview: Boolean,
    ) : Snapshot {
        override fun toUpdate() = TdApi.UpdateReactionNotificationSettings(
            TdApi.ReactionNotificationSettings(message.toTdApi(), story.toTdApi(), poll.toTdApi(), soundId, showPreview))
    }

    private enum class SourceSnapshot {
        NONE,
        CONTACTS,
        ALL;

        fun toTdApi(): TdApi.ReactionNotificationSource = when (this) {
            NONE -> TdApi.ReactionNotificationSourceNone()
            CONTACTS -> TdApi.ReactionNotificationSourceContacts()
            ALL -> TdApi.ReactionNotificationSourceAll()
        }
    }
}
