package com.badnng.moe.tdlib

import com.badnng.moe.chat.MessageKind
import com.badnng.moe.notifications.*
import com.badnng.moe.settings.NotificationScope
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

interface TdDeviceNotificationSession {
    fun listen(listener: ((DeviceNotificationEvent) -> Unit)?)
    fun enable(enabled: Boolean, result: (Boolean) -> Unit)
    fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit)
    fun resolveTarget(identity: DeviceNotificationIdentity, chatId: Long, messageId: Long, openMessage: Boolean,
        result: (Boolean) -> Unit) { result(false) }
    fun ordinaryPolicy(chatId: Long, active: () -> Boolean, result: (DeviceOrdinaryNotificationPolicy?) -> Unit) { result(null) }
    fun notificationScope(chatId: Long, active: () -> Boolean, result: (NotificationScope?) -> Unit) { result(null) }
}

/** Raw notifications and aggregate counters have no dependency on timeline windows or read actions. */
internal class OfficialTdDeviceNotificationSession(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdDeviceNotificationSession {
    @Volatile private var listener: ((DeviceNotificationEvent) -> Unit)? = null
    private val pending = ArrayDeque<DeviceNotificationEvent>()
    // TDLib owns the real chat cache; this presentation cache is bounded and never fabricates identities.
    private val titles = linkedMapOf<Long, String>()
    var onResolvedChat: (TdApi.Chat) -> Unit = {}
    private var retired = false
    private var lifecycle = 0L
    private var configurationOperation = 0L
    private var targetOperation = 0L
    private var target: Target? = null

    private data class Target(val groupId: Int, val notificationId: Int, val chatId: Long, val messageId: Long)

    @Synchronized override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) {
        if (retired) return
        this.listener = listener
        if (listener == null) {
            retired = true
            lifecycle++
            target = null
            targetOperation++
            configurationOperation++
            pending.clear()
            titles.clear()
            onResolvedChat = {}
        }
        else while (pending.isNotEmpty()) listener(pending.removeFirst())
    }

    @Synchronized override fun enable(enabled: Boolean, result: (Boolean) -> Unit) {
        val owner = lifecycle
        val operation = ++configurationOperation
        val allowed = { !retired && listener != null && lifecycle == owner && configurationOperation == operation }
        // The native default is zero: an actual consumer must explicitly enable bounded group output.
        request(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(10)), allowed) { sizeOk ->
            if (!sizeOk) result(false)
            else request(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(if (enabled) 25L else 0L)), allowed) { result(it) }
        }
    }

    override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) {
        if (groupId <= 0 || notificationId <= 0) { result(false); return }
        val owner = synchronized(this) { lifecycle }
        request(TdApi.RemoveNotification(groupId, notificationId), { !retired && listener != null && owner == lifecycle }, result)
    }

    override fun ordinaryPolicy(chatId: Long, active: () -> Boolean,
        result: (DeviceOrdinaryNotificationPolicy?) -> Unit,
    ) {
        val owner = synchronized(this) { lifecycle }
        val done = AtomicBoolean()
        fun finish(value: DeviceOrdinaryNotificationPolicy?) { if (done.compareAndSet(false, true)) result(value) }
        fun allowed() = !retired && listener != null && lifecycle == owner
        if (chatId == 0L) { finish(null); return }
        policyRequest(TdApi.GetChat(chatId), ::allowed, active) chat@ { response ->
            val chat = response as? TdApi.Chat
            if (chat == null || chat.id != chatId) { finish(null); return@chat }
            val scope = when (val type = chat.type) {
                is TdApi.ChatTypePrivate -> if (type.userId > 0) TdApi.NotificationSettingsScopePrivateChats() else null
                is TdApi.ChatTypeBasicGroup -> if (type.basicGroupId > 0) TdApi.NotificationSettingsScopeGroupChats() else null
                is TdApi.ChatTypeSupergroup -> if (type.supergroupId <= 0) null else if (type.isChannel)
                    TdApi.NotificationSettingsScopeChannelChats() else TdApi.NotificationSettingsScopeGroupChats()
                else -> null
            }
            val settings = chat.notificationSettings
            if (scope == null || settings == null) { finish(null); return@chat }
            fun effective(default: TdApi.ScopeNotificationSettings?) {
                val current = runCatching(active).getOrDefault(false) && synchronized(this) { allowed() }
                if (!current) { finish(null); return }
                val mute = if (settings.useDefaultMuteFor) default?.muteFor else settings.muteFor
                val sound = if (settings.useDefaultSound) default?.soundId else settings.soundId
                val preview = if (settings.useDefaultShowPreview) default?.showPreview else settings.showPreview
                finish(if (mute == null || mute < 0 || sound == null || preview == null) null
                    else DeviceOrdinaryNotificationPolicy(mute > 0, sound, preview))
            }
            if (settings.useDefaultMuteFor || settings.useDefaultSound || settings.useDefaultShowPreview)
                policyRequest(TdApi.GetScopeNotificationSettings(scope), ::allowed, active) { value ->
                    val defaults = value as? TdApi.ScopeNotificationSettings
                    if (defaults == null) finish(null) else effective(defaults)
                }
            else effective(null)
        }
    }

    override fun notificationScope(chatId: Long, active: () -> Boolean, result: (NotificationScope?) -> Unit) {
        if (chatId == 0L) { result(null); return }
        val owner = synchronized(this) { lifecycle }
        policyRequest(TdApi.GetChat(chatId), { !retired && listener != null && lifecycle == owner }, active) { response ->
            val chat = (response as? TdApi.Chat)?.takeIf { it.id == chatId }
            result(when (val type = chat?.type) {
                is TdApi.ChatTypePrivate -> NotificationScope.PRIVATE_CHATS.takeIf { type.userId > 0 }
                is TdApi.ChatTypeBasicGroup -> NotificationScope.GROUP_CHATS.takeIf { type.basicGroupId > 0 }
                is TdApi.ChatTypeSupergroup -> if (type.supergroupId <= 0) null else if (type.isChannel)
                    NotificationScope.CHANNEL_CHATS else NotificationScope.GROUP_CHATS
                else -> null
            })
        }
    }

    private fun policyRequest(function: TdApi.Function<*>, allowed: () -> Boolean,
        active: () -> Boolean = { true }, result: (TdApi.Object?) -> Unit,
    ) {
        val once = AtomicBoolean()
        var deferred: (() -> Unit)? = null
        fun finish(value: TdApi.Object?) {
            if (!once.compareAndSet(false, true)) return
            val deliver = {
                // These functions belong to another owner. Never invoke them under our monitor.
                val current = runCatching(active).getOrDefault(false)
                val actual = synchronized(this) { if (current && allowed()) value else null }
                result(actual)
            }
            if (Thread.holdsLock(this)) deferred = deliver else deliver()
        }
        val current = runCatching(active).getOrDefault(false)
        synchronized(this) {
            if (!current || !allowed()) finish(null)
            else try { send(function, ::finish, { finish(null) }) }
                catch (_: Exception) { finish(null) }
        }
        deferred?.invoke()
    }

    @Synchronized override fun resolveTarget(identity: DeviceNotificationIdentity, chatId: Long, messageId: Long, openMessage: Boolean,
        result: (Boolean) -> Unit) {
        val done = AtomicBoolean()
        fun finish(value: Boolean) { if (done.compareAndSet(false, true)) result(value) }
        if (retired || listener == null || chatId == 0L || messageId <= 0) { finish(false); return }
        val owner = lifecycle
        val operation = ++targetOperation
        target = Target(identity.groupId, identity.notificationId, chatId, messageId)
        val chatCompleted = AtomicBoolean()
        fun allowed() = !retired && lifecycle == owner && targetOperation == operation
        try { send(TdApi.GetChat(chatId), { chat ->
            synchronized(this) {
            if (!chatCompleted.compareAndSet(false, true) || done.get()) return@synchronized
            if (!allowed()) { finish(false); return@synchronized }
            if (chat !is TdApi.Chat || chat.id != chatId) { finish(false); return@send }
            if (!openMessage) { onResolvedChat(chat); finish(true) }
            else try { send(TdApi.GetMessage(chatId, messageId), { message ->
                synchronized(this) {
                    if (done.get()) return@synchronized
                    if (!allowed()) finish(false)
                    else if (message is TdApi.Message && message.chatId == chatId && message.id == messageId) {
                        onResolvedChat(chat); finish(true)
                    } else finish(false)
                }
            }, { finish(false) }) } catch (_: Exception) { finish(false) }
            }
        }, { finish(false) }) } catch (_: Exception) { finish(false) }
    }

    @Synchronized fun accept(update: TdApi.Object) {
        if (retired) return
        val pendingTarget = target
        if (pendingTarget != null) {
            val invalidated = when (update) {
                is TdApi.UpdateNotificationGroup -> update.notificationGroupId == pendingTarget.groupId &&
                    (update.totalCount <= 0 || update.chatId != pendingTarget.chatId ||
                        (update.removedNotificationIds ?: intArrayOf()).contains(pendingTarget.notificationId) ||
                        update.addedNotifications.orEmpty().any { it?.id == pendingTarget.notificationId && nativeMessageId(it) != pendingTarget.messageId })
                is TdApi.UpdateNotification -> update.notificationGroupId == pendingTarget.groupId &&
                    update.notification?.id == pendingTarget.notificationId && nativeMessageId(update.notification) != pendingTarget.messageId
                is TdApi.UpdateActiveNotifications -> update.groups.orEmpty().none { group ->
                    group?.id == pendingTarget.groupId && group.chatId == pendingTarget.chatId &&
                        group.notifications.orEmpty().any { it?.id == pendingTarget.notificationId && nativeMessageId(it) == pendingTarget.messageId }
                }
                else -> false
            }
            if (invalidated) { target = null; targetOperation++ }
        }
        when (update) {
            is TdApi.UpdateNewChat -> update.chat?.let { rememberTitle(it.id, it.title.orEmpty()) }
            is TdApi.UpdateChatTitle -> rememberTitle(update.chatId, update.title.orEmpty())
            is TdApi.UpdateChatNotificationSettings -> if (update.chatId != 0L)
                emit(DeviceNotificationEvent.PolicyChanged(update.chatId))
            is TdApi.UpdateScopeNotificationSettings -> emit(DeviceNotificationEvent.PolicyChanged())
            is TdApi.UpdateActiveNotifications -> emit(DeviceNotificationEvent.Active(update.groups.orEmpty().mapNotNull(::mapGroup)))
            is TdApi.UpdateNotificationGroup -> {
                if (!supported(update.type)) return
                val group = DeviceNotificationGroup(update.notificationGroupId, update.chatId, update.totalCount.coerceAtLeast(0),
                    update.notificationSettingsChatId, update.notificationSoundId,
                    update.addedNotifications.orEmpty().mapNotNull { mapItem(update.chatId, it) }, groupKind(update.type))
                emit(DeviceNotificationEvent.Group(group, (update.removedNotificationIds ?: intArrayOf()).toList()))
            }
            is TdApi.UpdateNotification -> update.notification?.let { notification ->
                // The group owns the actual chat identity. Replacement content may carry a native message.
                val chatId = ((notification.type as? TdApi.NotificationTypeNewMessage)?.message)?.chatId ?: 0L
                mapItem(chatId, notification)?.let { emit(DeviceNotificationEvent.Replace(update.notificationGroupId, it)) }
            }
            is TdApi.UpdateUnreadMessageCount -> mapChatList(update.chatList)?.let {
                emit(DeviceNotificationEvent.Messages(it, update.unreadCount, update.unreadUnmutedCount))
            }
            is TdApi.UpdateUnreadChatCount -> mapChatList(update.chatList)?.let {
                emit(DeviceNotificationEvent.Chats(it, update.unreadCount, update.unreadUnmutedCount,
                    update.markedAsUnreadCount, update.markedAsUnreadUnmutedCount))
            }
        }
    }

    private fun supported(type: TdApi.NotificationGroupType?): Boolean =
        type is TdApi.NotificationGroupTypeMessages || type is TdApi.NotificationGroupTypeMentions

    private fun groupKind(type: TdApi.NotificationGroupType?) = if (type is TdApi.NotificationGroupTypeMentions)
        DeviceNotificationGroupKind.MENTIONS else DeviceNotificationGroupKind.MESSAGES

    private fun nativeMessageId(notification: TdApi.Notification?): Long? = when (val value = notification?.type) {
        is TdApi.NotificationTypeNewMessage -> value.message?.id
        is TdApi.NotificationTypeNewPushMessage -> value.messageId
        else -> null
    }

    private fun mapGroup(value: TdApi.NotificationGroup?): DeviceNotificationGroup? {
        if (value == null || value.id <= 0 || value.chatId == 0L || !supported(value.type)) return null
        return DeviceNotificationGroup(value.id, value.chatId, value.totalCount.coerceAtLeast(0),
            items = value.notifications.orEmpty().mapNotNull { mapItem(value.chatId, it) }, kind = groupKind(value.type))
    }

    private fun mapItem(chatId: Long, value: TdApi.Notification?): DeviceNotificationItem? {
        if (value == null || value.id <= 0) return null
        return when (val type = value.type) {
            is TdApi.NotificationTypeNewMessage -> {
                val message = type.message ?: return null
                if (message.id <= 0 || message.chatId == 0L || chatId != 0L && message.chatId != chatId) return null
                val (text, kind) = mapMessageContent(message.content)
                val safeText = deviceNotificationFormattedText(message.content)?.let(::deviceNotificationPreview) ?: text
                DeviceNotificationItem(value.id, value.date, message.id, titles[message.chatId].orEmpty(),
                    if (type.showPreview) safeText.ifBlank { mediaLabel(kind) } else "", value.isSilent, type.showPreview, true,
                    if (message.content is TdApi.MessagePinMessage) DeviceNotificationCause.PINNED else DeviceNotificationCause.ORDINARY)
            }
            is TdApi.NotificationTypeNewPushMessage -> {
                if (type.messageId <= 0) return null
                // Push-only identities are not inserted into history or assumed history-navigable.
                val text = when (val content = type.content) {
                    // Push text/captions carry no native entity ranges. Until a full native
                    // notification arrives, showing a type label cannot leak concealed spoilers.
                    is TdApi.PushMessageContentText -> "文字消息"
                    is TdApi.PushMessageContentPhoto -> if (content.isSecret) "新消息" else "照片"
                    else -> "新消息"
                }
                DeviceNotificationItem(value.id, value.date, type.messageId,
                    titles[chatId].orEmpty().ifBlank { type.senderName.orEmpty() }, text, value.isSilent, true, false,
                    if (pinnedPush(type.content)) DeviceNotificationCause.PINNED else DeviceNotificationCause.ORDINARY)
            }
            else -> null
        }
    }

    /** Only the fixed schema's explicit is_pinned fields establish a push pin cause. */
    private fun pinnedPush(content: TdApi.PushMessageContent?): Boolean = when (content) {
        is TdApi.PushMessageContentHidden -> content.isPinned
        is TdApi.PushMessageContentAnimation -> content.isPinned
        is TdApi.PushMessageContentAudio -> content.isPinned
        is TdApi.PushMessageContentContact -> content.isPinned
        is TdApi.PushMessageContentDocument -> content.isPinned
        is TdApi.PushMessageContentGame -> content.isPinned
        is TdApi.PushMessageContentGameScore -> content.isPinned
        is TdApi.PushMessageContentInvoice -> content.isPinned
        is TdApi.PushMessageContentLocation -> content.isPinned
        is TdApi.PushMessageContentPaidMedia -> content.isPinned
        is TdApi.PushMessageContentPhoto -> content.isPinned
        is TdApi.PushMessageContentPoll -> content.isPinned
        is TdApi.PushMessageContentGiveaway -> content.isPinned
        is TdApi.PushMessageContentSticker -> content.isPinned
        is TdApi.PushMessageContentStory -> content.isPinned
        is TdApi.PushMessageContentText -> content.isPinned
        is TdApi.PushMessageContentChecklist -> content.isPinned
        is TdApi.PushMessageContentVideo -> content.isPinned
        is TdApi.PushMessageContentVideoNote -> content.isPinned
        is TdApi.PushMessageContentVoiceNote -> content.isPinned
        else -> false
    }

    private fun mediaLabel(kind: MessageKind): String = when (kind) {
        MessageKind.PHOTO -> "照片"
        MessageKind.VIDEO -> "视频"
        MessageKind.ANIMATION -> "动画"
        MessageKind.FILE -> "文件"
        MessageKind.VOICE -> "语音消息"
        MessageKind.STICKER -> "贴纸"
        else -> "新消息"
    }

    private fun rememberTitle(chatId: Long, title: String) {
        if (chatId == 0L) return
        titles.remove(chatId)
        titles[chatId] = title
        if (titles.size > 1024) titles.remove(titles.keys.first())
    }

    private fun emit(value: DeviceNotificationEvent) {
        val target = listener
        if (target != null) target(value)
        else {
            // The engine binds before parameters. This buffer only covers the creation hand-off.
            if (pending.size == 1024) pending.removeFirst()
            pending.addLast(value)
        }
    }

    private fun request(function: TdApi.Function<*>, allowed: () -> Boolean, result: (Boolean) -> Unit) {
        val done = AtomicBoolean()
        fun finish(success: Boolean) { if (done.compareAndSet(false, true)) result(success) }
        synchronized(this) {
            if (!allowed()) { finish(false); return }
            try { send(function, { response -> synchronized(this) { finish(allowed() && response is TdApi.Ok) } }, { finish(false) }) }
            catch (_: Exception) { finish(false) }
        }
    }
}
