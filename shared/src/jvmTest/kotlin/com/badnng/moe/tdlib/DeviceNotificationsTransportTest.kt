package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.notifications.*
import com.badnng.moe.settings.NotificationScope
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class DeviceNotificationsTransportTest {
    @Test fun notificationScopeUsesOnlyTheActualChatIdentityAndTypeWithoutPublishingAnActivation() {
        val types = listOf(
            TdApi.ChatTypePrivate(77) to NotificationScope.PRIVATE_CHATS,
            TdApi.ChatTypeBasicGroup(78) to NotificationScope.GROUP_CHATS,
            TdApi.ChatTypeSupergroup(79, false) to NotificationScope.GROUP_CHATS,
            TdApi.ChatTypeSupergroup(79, true) to NotificationScope.CHANNEL_CHATS)
        for ((type, scope) in types) {
            val f = Fixture(); val results = mutableListOf<NotificationScope?>(); var activations = 0
            f.session.onResolvedChat = { activations++ }
            f.session.notificationScope(10, { true }, results::add)
            assertEquals(10L, assertIs<TdApi.GetChat>(f.requests.single().request).chatId)
            f.reply(TdApi.Chat().apply { id = 10; this.type = type })
            assertEquals(listOf<NotificationScope?>(scope), results)
            assertEquals(0, activations); assertTrue(f.events.isEmpty()); assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun notificationScopeRejectsUnknownOrMismatchedChatTypesAndIllegalNativePeerIds() {
        val invalid = listOf<TdApi.Object>(TdApi.Ok(), TdApi.Chat().apply { id = 11; type = TdApi.ChatTypePrivate(77) },
            TdApi.Chat().apply { id = 10 }, TdApi.Chat().apply { id = 10; type = TdApi.ChatTypeSecret(1, 77) },
            TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(0) },
            TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(-1) },
            TdApi.Chat().apply { id = 10; type = TdApi.ChatTypeBasicGroup(0) },
            TdApi.Chat().apply { id = 10; type = TdApi.ChatTypeSupergroup(-1, true) })
        for (native in invalid) {
            val f = Fixture(); val results = mutableListOf<NotificationScope?>()
            f.session.notificationScope(10, { true }, results::add); f.reply(native)
            assertNull(results.single()); assertTrue(f.requests.isEmpty()); assertTrue(f.events.isEmpty())
        }
        for (kind in 0..2) {
            val f = Fixture(); val results = mutableListOf<NotificationScope?>()
            if (kind == 2) f.session.listen(null)
            f.session.notificationScope(if (kind == 0) 0 else 10, { kind != 1 }, results::add)
            assertNull(results.single()); assertTrue(f.requests.isEmpty())
        }
        val legacy = object : TdDeviceNotificationSession {
            override fun listen(listener: ((DeviceNotificationEvent) -> Unit)?) {}
            override fun enable(enabled: Boolean, result: (Boolean) -> Unit) { result(false) }
            override fun dismiss(groupId: Int, notificationId: Int, result: (Boolean) -> Unit) { result(false) }
        }
        val unknown = mutableListOf<NotificationScope?>()
        legacy.notificationScope(10, { true }, unknown::add)
        assertNull(unknown.single())
    }

    @Test fun notificationScopeNativeFailuresDiscardsExceptionsAndDuplicateCallbacksCompleteOnce() {
        for (failure in 0..2) {
            var calls = 0
            val session = OfficialTdDeviceNotificationSession { command, reply, failed ->
                calls++; assertIs<TdApi.GetChat>(command)
                when (failure) {
                    0 -> { reply(TdApi.Error(500, "private native error")); reply(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77) }) }
                    1 -> { failed(); reply(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77) }) }
                    else -> error("synthetic native dispatch failure")
                }
            }
            session.listen {}
            val results = mutableListOf<NotificationScope?>()
            session.notificationScope(10, { true }, results::add)
            assertNull(results.single()); assertEquals(1, calls)
        }
        val f = Fixture(); val results = mutableListOf<NotificationScope?>()
        f.session.notificationScope(10, { true }, results::add)
        val held = f.requests.removeFirst()
        repeat(2) { held.result(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77) }) }
        held.failed()
        assertEquals(listOf<NotificationScope?>(NotificationScope.PRIVATE_CHATS), results); assertTrue(f.requests.isEmpty())
    }

    @Test fun lateScopeResultsRespectPageLeaseAndRetirementAndDoNotSupersedeExplicitActivation() {
        for (retire in listOf(false, true)) {
            val f = Fixture(); var active = true; val results = mutableListOf<NotificationScope?>()
            f.session.notificationScope(10, { active }, results::add)
            val held = f.requests.removeFirst()
            if (retire) f.session.listen(null) else active = false
            held.result(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77) })
            assertNull(results.single()); assertTrue(f.requests.isEmpty())
        }
        val f = Fixture(); val opened = mutableListOf<Boolean>(); val chats = mutableListOf<Long>(); val scopes = mutableListOf<NotificationScope?>()
        f.session.onResolvedChat = { chats += it.id }
        f.session.resolveTarget(DeviceNotificationIdentity(1, 2, 3), 10, 108, false, opened::add)
        val target = f.requests.removeFirst()
        f.session.notificationScope(10, { true }, scopes::add)
        f.reply(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypeSupergroup(79, true) })
        assertTrue(chats.isEmpty()); assertTrue(opened.isEmpty())
        target.result(TdApi.Chat().apply { id = 10 })
        assertEquals(listOf<NotificationScope?>(NotificationScope.CHANNEL_CHATS), scopes)
        assertEquals(listOf(true), opened); assertEquals(listOf(10L), chats)
    }

    @Test fun synchronousMetadataCallbacksInvokeForeignLeasesAndConsumersOutsideTheNativeMonitor() {
        lateinit var session: OfficialTdDeviceNotificationSession
        var reads = 0
        session = OfficialTdDeviceNotificationSession { command, reply, _ ->
            reads++; assertIs<TdApi.GetChat>(command)
            reply(TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(77); notificationSettings = TdApi.ChatNotificationSettings() })
        }
        session.listen {}
        val active = { assertFalse(Thread.holdsLock(session)); true }
        val scopes = mutableListOf<NotificationScope?>()
        session.notificationScope(10, active) { assertFalse(Thread.holdsLock(session)); scopes += it }
        val policies = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
        session.ordinaryPolicy(10, active) { assertFalse(Thread.holdsLock(session)); policies += it }
        assertEquals(listOf<NotificationScope?>(NotificationScope.PRIVATE_CHATS), scopes)
        assertEquals(DeviceOrdinaryNotificationPolicy(false, 0, false), policies.single())
        assertEquals(2, reads)
    }

    @Test fun mappedSpoilerCaptionsAreConcealedAndPushOnlyTextCannotRevealUnknownEntities() {
        val f = Fixture()
        val secret = "public private"
        val caption = TdApi.FormattedText(secret, arrayOf(TdApi.TextEntity(7, 7, TdApi.TextEntityTypeSpoiler())))
        val message = notification(8).apply {
            type = TdApi.NotificationTypeNewMessage(TdApi.Message().apply {
                id = 108; chatId = 10; content = TdApi.MessagePhoto().apply { this.caption = caption }
            }, true)
        }
        f.session.accept(TdApi.UpdateNotification(6, message))
        assertEquals("public ▚▚▚▚▚▚▚", assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item.text)
        f.session.accept(TdApi.UpdateNotification(6, TdApi.Notification(9, 100, false,
            TdApi.NotificationTypeNewPushMessage().apply {
                messageId = 109; content = TdApi.PushMessageContentText(secret, false)
            })))
        assertEquals("文字消息", assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item.text)
        assertFalse(f.events.toString().contains("private"))
    }

    @Test fun retiringBeforeTheSizeReplyCannotSendTheCountOption() {
        val f = Fixture()
        f.session.enable(true) {}
        val pending = f.requests.removeFirst()
        f.session.listen(null)
        pending.result(TdApi.Ok())
        assertTrue(f.requests.isEmpty(), "A retired native owner must not continue the enable chain.")
    }

    @Test fun newerConfigurationSupersedesOlderSizeReplyBeforeAnyFollowOnWrite() {
        val f = Fixture()
        f.session.enable(true) {}
        val older = f.requests.removeFirst()
        f.session.enable(false) {}
        older.result(TdApi.Ok())
        assertEquals(1, f.requests.size)
        f.reply(TdApi.Ok())
        val option = assertIs<TdApi.SetOption>(f.requests.single().request)
        assertEquals(0L, assertIs<TdApi.OptionValueInteger>(option.value).value)
    }

    @Test fun retiredOrReplacedTargetsCannotContinueGetMessageOrPublishChatMetadata() {
        val f = Fixture()
        val chats = mutableListOf<Long>()
        f.session.onResolvedChat = { chats += it.id }
        val id = DeviceNotificationIdentity(7, 6, 8)
        f.session.resolveTarget(id, 10, 108, true) {}
        val first = f.requests.removeFirst()
        f.session.accept(TdApi.UpdateNotification(6, notification(8).apply {
            type = TdApi.NotificationTypeNewMessage(TdApi.Message().apply { this.id = 999; chatId = 10 }, true)
        }))
        first.result(TdApi.Chat().apply { this.id = 10 })
        assertTrue(f.requests.isEmpty(), "A replaced native target cannot start a GetMessage continuation.")
        assertTrue(chats.isEmpty())
        f.session.resolveTarget(id, 10, 108, true) {}
        val late = f.requests.removeFirst()
        f.session.listen(null)
        late.result(TdApi.Chat().apply { this.id = 10 })
        assertTrue(f.requests.isEmpty())
        assertTrue(chats.isEmpty())
    }

    @Test fun targetMustMatchBothNativeChatAndMessageBeforeOneMetadataPublication() {
        val f = Fixture()
        val chats = mutableListOf<Long>()
        f.session.onResolvedChat = { chats += it.id }
        var accepted: Boolean? = null
        f.session.resolveTarget(DeviceNotificationIdentity(7, 6, 8), 10, 108, true) { accepted = it }
        f.reply(TdApi.Chat().apply { id = 10 })
        assertIs<TdApi.GetMessage>(f.requests.single().request)
        f.reply(TdApi.Message().apply { id = 108; chatId = 11 })
        assertEquals(false, accepted)
        assertTrue(chats.isEmpty())
        f.session.resolveTarget(DeviceNotificationIdentity(7, 6, 8), 10, 108, true) { accepted = it }
        val chat = f.requests.removeFirst()
        chat.result(TdApi.Chat().apply { id = 10 })
        chat.result(TdApi.Chat().apply { id = 10 })
        assertEquals(1, f.requests.size)
        val message = f.requests.removeFirst()
        message.result(TdApi.Message().apply { id = 108; chatId = 10 })
        message.result(TdApi.Message().apply { id = 108; chatId = 10 })
        assertEquals(true, accepted)
        assertEquals(listOf(10L), chats)
    }

    @Test fun consumerEnablesLegalNativeGroupsAndDoesNotTreatFailureAsEnabled() {
        val f = Fixture()
        var accepted: Boolean? = null
        f.session.enable(true) { accepted = it }
        val size = assertIs<TdApi.SetOption>(f.requests.single().request)
        assertEquals("notification_group_size_max", size.name)
        assertEquals(10, assertIs<TdApi.OptionValueInteger>(size.value).value.toInt())
        f.reply(TdApi.Ok())
        val count = assertIs<TdApi.SetOption>(f.requests.single().request)
        assertEquals("notification_group_count_max", count.name)
        assertEquals(25, assertIs<TdApi.OptionValueInteger>(count.value).value.toInt())
        f.reply(TdApi.Error(500, "private server text"))
        assertEquals(false, accepted)
        f.session.enable(false) { accepted = it }
        f.reply(TdApi.Ok())
        assertEquals(0L, assertIs<TdApi.OptionValueInteger>(assertIs<TdApi.SetOption>(f.requests.single().request).value).value)
        f.reply(TdApi.Ok())
        assertEquals(true, accepted)
    }

    @Test fun groupUsesNativeSettingsIdentitySoundSilenceTotalAndRemovalWithoutTouchingTimeline() {
        val f = Fixture()
        f.session.accept(TdApi.UpdateChatTitle(10, "Actual native chat"))
        val group = TdApi.UpdateNotificationGroup().apply {
            notificationGroupId = 4; chatId = 10; totalCount = 90
            type = TdApi.NotificationGroupTypeMentions()
            notificationSettingsChatId = 99; notificationSoundId = 887
            addedNotifications = arrayOf(notification(5, silent = true, preview = false))
            removedNotificationIds = intArrayOf(1, 3)
        }
        f.session.accept(group)
        val event = assertIs<DeviceNotificationEvent.Group>(f.events.single())
        assertEquals(90, event.group.totalCount)
        assertEquals(99L, event.group.settingsChatId)
        assertEquals(887L, event.group.soundId)
        assertEquals(listOf(1, 3), event.removedIds)
        assertTrue(event.group.items.single().silent)
        assertFalse(event.group.items.single().nativeShowPreview)
        assertEquals("", event.group.items.single().text)
        assertTrue(f.requests.isEmpty())
        assertFalse(event.toString().contains("secret message body"))
    }

    @Test fun activeAndReplacementPreserveIdentityWhilePushDoesNotGrantHistoryInsertion() {
        val f = Fixture()
        f.session.accept(TdApi.UpdateActiveNotifications(arrayOf(TdApi.NotificationGroup(6,
            TdApi.NotificationGroupTypeMessages(), 10, 50, arrayOf(notification(8))))))
        val active = assertIs<DeviceNotificationEvent.Active>(f.events.single())
        assertEquals(50, active.groups.single().totalCount)
        f.session.accept(TdApi.UpdateNotification(6, notification(8, preview = false)))
        val replacement = assertIs<DeviceNotificationEvent.Replace>(f.events.last())
        assertEquals(6, replacement.groupId)
        assertEquals(8, replacement.item.id)
        val push = TdApi.Notification(9, 100, false, TdApi.NotificationTypeNewPushMessage().apply {
            messageId = 109; senderName = "Native push sender"; content = TdApi.PushMessageContentText("Push content", false)
        })
        f.session.accept(TdApi.UpdateNotification(6, push))
        val mapped = assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item
        assertFalse(mapped.canOpenMessage)
        assertEquals(109L, mapped.messageId)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun countersUseTheNativeListAndAllDistinctMarkedFields() {
        val f = Fixture()
        f.session.accept(TdApi.UpdateUnreadMessageCount(TdApi.ChatListFolder(7), 50, 20))
        f.session.accept(TdApi.UpdateUnreadChatCount(TdApi.ChatListFolder(7), 40, 12, 8, 3, 1))
        assertEquals(DeviceNotificationEvent.Messages(ChatListRef(7), 50, 20), f.events[0])
        assertEquals(DeviceNotificationEvent.Chats(ChatListRef(7), 12, 8, 3, 1), f.events[1])
        // Native removal is only mapped. It must never submit RemoveNotification or ViewMessages.
        f.session.accept(TdApi.UpdateNotificationGroup().apply {
            notificationGroupId = 2; chatId = 10; type = TdApi.NotificationGroupTypeMessages()
            totalCount = 0; removedNotificationIds = intArrayOf(3)
        })
        assertTrue(f.requests.isEmpty())
        var result: Boolean? = null
        f.session.dismiss(2, 3) { result = it }
        val request = assertIs<TdApi.RemoveNotification>(f.requests.single().request)
        assertEquals(2, request.notificationGroupId)
        assertEquals(3, request.notificationId)
        f.reply(TdApi.Ok())
        assertEquals(true, result)
    }

    private class Fixture {
        val requests = ArrayDeque<Request>()
        val events = mutableListOf<DeviceNotificationEvent>()
        val session = OfficialTdDeviceNotificationSession { request, result, failed -> requests.addLast(Request(request, result, failed)) }
        init { session.listen { events += it } }
        fun reply(value: TdApi.Object) { requests.removeFirst().result(value) }
    }
    private data class Request(val request: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private companion object {
        fun notification(id: Int, silent: Boolean = false, preview: Boolean = true): TdApi.Notification =
            TdApi.Notification(id, 100, silent, TdApi.NotificationTypeNewMessage(TdApi.Message().apply {
                this.id = 100L + id; chatId = 10
                content = TdApi.MessageText().apply { text = TdApi.FormattedText("secret message body", emptyArray()) }
            }, preview))
    }
}
