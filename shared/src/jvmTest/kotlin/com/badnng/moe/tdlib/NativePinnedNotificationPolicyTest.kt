package com.badnng.moe.tdlib

import com.badnng.moe.notifications.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic native boundary only: no account, notifications, downloads, history or OS actions. */
class NativePinnedNotificationPolicyTest {
    @Test fun explicitOrdinaryFieldsNeedOnlyGetChatAndNeverPublishAnActivation() {
        val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>(); var activated = 0
        f.session.onResolvedChat = { activated++ }
        f.session.ordinaryPolicy(10, f::active, results::add)
        assertEquals(10L, assertIs<TdApi.GetChat>(f.last.command).chatId)
        f.last.reply(chat().apply { notificationSettings.useDefaultMuteStories = true })
        assertEquals(DeviceOrdinaryNotificationPolicy(true, 99, false), results.single())
        assertEquals(1, f.requests.size); assertEquals(0, activated); f.assertReadsOnly()
    }

    @Test fun actualChatTypeSelectsTheCorrectNativeScopeForAllSupportedTypes() {
        val types = listOf(
            TdApi.ChatTypePrivate(71) to TdApi.NotificationSettingsScopePrivateChats::class.java,
            TdApi.ChatTypeBasicGroup(72) to TdApi.NotificationSettingsScopeGroupChats::class.java,
            TdApi.ChatTypeSupergroup(73, false) to TdApi.NotificationSettingsScopeGroupChats::class.java,
            TdApi.ChatTypeSupergroup(74, true) to TdApi.NotificationSettingsScopeChannelChats::class.java)
        for ((type, expected) in types) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add)
            f.last.reply(chat().apply { this.type = type; notificationSettings = settings(7) })
            assertEquals(expected, assertIs<TdApi.GetScopeNotificationSettings>(f.last.command).scope.javaClass)
            f.last.reply(defaults())
            assertEquals(DeviceOrdinaryNotificationPolicy(false, -1, true), results.single())
            assertEquals(2, f.requests.size); f.assertReadsOnly()
        }
    }

    @Test fun eachDefaultFlagIndependentlyUsesItsScopeValueAndPreservesExplicitFields() {
        for (bits in 0..7) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add)
            f.last.reply(chat().apply { notificationSettings = settings(bits) })
            if (bits != 0) f.last.reply(defaults())
            assertEquals(DeviceOrdinaryNotificationPolicy(bits and 1 == 0,
                if (bits and 2 != 0) -1 else 99, bits and 4 != 0), results.single())
            assertEquals(if (bits == 0) 1 else 2, f.requests.size); f.assertReadsOnly()
        }
    }

    @Test fun wrongChatIdentityMissingSettingsUnsupportedTypesAndInvalidEffectiveValuesStayUnknown() {
        val invalid = listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(500, "private"), chat().apply { id = 11 },
            chat().apply { notificationSettings = null }, chat().apply { type = null },
            chat().apply { type = TdApi.ChatTypeSecret(5, 71) }, chat().apply { type = TdApi.ChatTypePrivate(0) },
            chat().apply { type = TdApi.ChatTypeBasicGroup(0) }, chat().apply { type = TdApi.ChatTypeSupergroup(0, false) },
            chat().apply { notificationSettings.muteFor = -1 })
        for (native in invalid) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add); f.last.reply(native)
            assertNull(results.single()); assertEquals(1, f.requests.size); f.assertReadsOnly()
        }
        for (native in listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(500, "private"),
            defaults().apply { muteFor = -1 })) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add)
            f.last.reply(chat().apply { notificationSettings = settings(7) }); f.last.reply(native)
            assertNull(results.single()); assertEquals(2, f.requests.size); f.assertReadsOnly()
        }
    }

    @Test fun explicitAndDefaultSoundIdsPreserveTheEntireNativeSignedLongDomain() {
        for (fromScope in listOf(false, true)) for (sound in listOf(Long.MIN_VALUE, -2L, -1L, 0L, 1L, Long.MAX_VALUE)) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add)
            f.last.reply(chat().apply {
                notificationSettings = settings(if (fromScope) 2 else 0).apply { soundId = sound }
            })
            if (fromScope) f.last.reply(defaults().apply { soundId = sound })
            assertEquals(DeviceOrdinaryNotificationPolicy(true, sound, false), results.single())
            assertEquals(if (fromScope) 2 else 1, f.requests.size); f.assertReadsOnly()
        }
    }

    @Test fun everyNativeStepHandlesDiscardExceptionAndDuplicateCallbacksExactlyOnce() {
        for (atScope in listOf(false, true)) for (throwing in listOf(false, true)) {
            val requests = mutableListOf<Pending>()
            val session = OfficialTdDeviceNotificationSession { command, reply, failed ->
                val index = requests.size; requests += Pending(command, reply, failed)
                if (index == if (atScope) 1 else 0) {
                    if (throwing) error("synthetic") else failed()
                }
            }
            session.listen {}
            val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            session.ordinaryPolicy(10, { true }, results::add)
            if (atScope) requests.last().reply(chat().apply { notificationSettings = settings(7) })
            requests.last().failed(); requests.last().reply(defaults())
            assertNull(results.single()); assertEquals(if (atScope) 2 else 1, requests.size)
        }
        val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
        f.session.ordinaryPolicy(10, f::active, results::add)
        val first = f.last; first.reply(chat().apply { notificationSettings = settings(7) })
        first.reply(chat().apply { notificationSettings = settings(7) }); first.failed()
        val second = f.last; second.reply(defaults()); second.reply(defaults()); second.failed()
        assertEquals(2, f.requests.size); assertNotNull(results.single())
    }

    @Test fun inactiveAndRetiredLeasesStopEveryStepWithoutGuessingUnmuted() {
        for (retire in listOf(false, true)) for (atScope in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            f.session.ordinaryPolicy(10, f::active, results::add)
            if (atScope) f.last.reply(chat().apply { notificationSettings = settings(7) })
            if (retire) f.session.listen(null) else f.enabled = false
            f.last.reply(if (atScope) defaults() else chat().apply { notificationSettings = settings(7) })
            assertNull(results.single()); assertEquals(if (atScope) 2 else 1, f.requests.size)
        }
        for (invalid in 0..2) {
            val f = Fixture(); val results = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
            if (invalid == 1) f.enabled = false
            if (invalid == 2) f.session.listen(null)
            f.session.ordinaryPolicy(if (invalid == 0) 0 else 10, f::active, results::add)
            assertNull(results.single()); assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun metadataLookupDoesNotSupersedeTheIndependentExplicitActivationTarget() {
        val f = Fixture(); val activated = mutableListOf<Long>(); val opened = mutableListOf<Boolean>()
        f.session.onResolvedChat = { activated += it.id }
        f.session.resolveTarget(DeviceNotificationIdentity(4, 2, 3), 10, 108, false, opened::add)
        val target = f.last
        val policy = mutableListOf<DeviceOrdinaryNotificationPolicy?>()
        f.session.ordinaryPolicy(10, f::active, policy::add); f.last.reply(chat())
        assertTrue(activated.isEmpty()); assertTrue(opened.isEmpty())
        target.reply(chat())
        assertEquals(listOf(10L), activated); assertEquals(listOf(true), opened)
        assertNotNull(policy.single()); assertEquals(2, f.requests.size); f.assertReadsOnly()
    }

    @Test fun fullPinServiceMessagesAndGroupKindsAreMappedWithoutGuessingFromMessageIsPinned() {
        val f = Fixture()
        for (mentions in listOf(false, true)) {
            val native = group(mentions, full(TdApi.MessagePinMessage(55)))
            f.session.accept(TdApi.UpdateActiveNotifications(arrayOf(native)))
            val active = assertIs<DeviceNotificationEvent.Active>(f.events.last()).groups.single()
            assertEquals(if (mentions) DeviceNotificationGroupKind.MENTIONS else DeviceNotificationGroupKind.MESSAGES, active.kind)
            assertEquals(DeviceNotificationCause.PINNED, active.items.single().cause)
            f.session.accept(TdApi.UpdateNotificationGroup().apply {
                notificationGroupId = 2; chatId = 10; type = native.type; totalCount = 1
                addedNotifications = native.notifications; notificationSettingsChatId = 22; notificationSoundId = 91
            })
            val changed = assertIs<DeviceNotificationEvent.Group>(f.events.last()).group
            assertEquals(active.kind, changed.kind); assertEquals(22L, changed.settingsChatId); assertEquals(91L, changed.soundId)
        }
        val ordinaryPinnedPost = full(TdApi.MessageText().apply { text = TdApi.FormattedText("pinned text", emptyArray()) })
        assertIs<TdApi.NotificationTypeNewMessage>(ordinaryPinnedPost.type).message.isPinned = true
        f.session.accept(TdApi.UpdateNotification(2, ordinaryPinnedPost))
        assertEquals(DeviceNotificationCause.ORDINARY, assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item.cause)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun allTwentyActualPushPinnedFieldsAreRespectedAndUnknownPushNeverGuesses() {
        val f = Fixture()
        val factories = listOf<(Boolean) -> TdApi.PushMessageContent>(
            { TdApi.PushMessageContentHidden().apply { isPinned = it } },
            { TdApi.PushMessageContentAnimation().apply { isPinned = it } },
            { TdApi.PushMessageContentAudio().apply { isPinned = it } },
            { TdApi.PushMessageContentContact().apply { isPinned = it } },
            { TdApi.PushMessageContentDocument().apply { isPinned = it } },
            { TdApi.PushMessageContentGame().apply { isPinned = it } },
            { TdApi.PushMessageContentGameScore().apply { isPinned = it } },
            { TdApi.PushMessageContentInvoice().apply { isPinned = it } },
            { TdApi.PushMessageContentLocation().apply { isPinned = it } },
            { TdApi.PushMessageContentPaidMedia().apply { isPinned = it } },
            { TdApi.PushMessageContentPhoto().apply { isPinned = it } },
            { TdApi.PushMessageContentPoll().apply { isPinned = it } },
            { TdApi.PushMessageContentGiveaway().apply { isPinned = it } },
            { TdApi.PushMessageContentSticker().apply { isPinned = it } },
            { TdApi.PushMessageContentStory().apply { isPinned = it } },
            { TdApi.PushMessageContentText().apply { isPinned = it } },
            { TdApi.PushMessageContentChecklist().apply { isPinned = it } },
            { TdApi.PushMessageContentVideo().apply { isPinned = it } },
            { TdApi.PushMessageContentVideoNote().apply { isPinned = it } },
            { TdApi.PushMessageContentVoiceNote().apply { isPinned = it } })
        assertEquals(20, factories.size)
        for (factory in factories) for (pinned in listOf(false, true)) {
            f.session.accept(TdApi.UpdateNotification(2, push(factory(pinned))))
            val item = assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item
            assertEquals(if (pinned) DeviceNotificationCause.PINNED else DeviceNotificationCause.ORDINARY, item.cause)
            assertFalse(item.canOpenMessage)
        }
        for (content in listOf<TdApi.PushMessageContent?>(null, TdApi.PushMessageContentChatChangeTitle().apply { title = "pinned" },
            TdApi.PushMessageContentContactRegistered())) {
            f.session.accept(TdApi.UpdateNotification(2, push(content)))
            assertEquals(DeviceNotificationCause.ORDINARY, assertIs<DeviceNotificationEvent.Replace>(f.events.last()).item.cause)
        }
        assertTrue(f.requests.isEmpty())
    }

    @Test fun policyUpdatesOnlyInvalidateTypedTargetsAndNeverRemoveNativeNotifications() {
        val f = Fixture()
        f.session.accept(TdApi.UpdateChatNotificationSettings(10, settings(0)))
        f.session.accept(TdApi.UpdateScopeNotificationSettings(TdApi.NotificationSettingsScopeGroupChats(), defaults()))
        assertEquals(listOf<DeviceNotificationEvent>(DeviceNotificationEvent.PolicyChanged(10), DeviceNotificationEvent.PolicyChanged()), f.events)
        f.session.accept(TdApi.UpdateNotificationGroup().apply {
            notificationGroupId = 2; chatId = 10; type = TdApi.NotificationGroupTypeMentions()
            totalCount = 0; removedNotificationIds = intArrayOf(3)
        })
        assertEquals(DeviceNotificationGroupKind.MENTIONS, assertIs<DeviceNotificationEvent.Group>(f.events.last()).group.kind)
        assertTrue(f.requests.isEmpty())
        val count = f.events.size; f.session.listen(null)
        f.session.accept(TdApi.UpdateChatNotificationSettings(10, settings(0)))
        assertEquals(count, f.events.size)
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private class Fixture {
        var enabled = true
        val requests = mutableListOf<Pending>(); val events = mutableListOf<DeviceNotificationEvent>()
        val session = OfficialTdDeviceNotificationSession { command, reply, failed -> requests += Pending(command, reply, failed) }
        init { session.listen(events::add) }
        val last get() = requests.last()
        fun active() = enabled
        fun assertReadsOnly() { assertTrue(requests.all { it.command is TdApi.GetChat || it.command is TdApi.GetScopeNotificationSettings }) }
    }
    private fun chat() = TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(71); notificationSettings = settings(0) }
    private fun settings(bits: Int) = TdApi.ChatNotificationSettings().apply {
        useDefaultMuteFor = bits and 1 != 0; muteFor = 60
        useDefaultSound = bits and 2 != 0; soundId = 99
        useDefaultShowPreview = bits and 4 != 0; showPreview = false
    }
    private fun defaults() = TdApi.ScopeNotificationSettings().apply { muteFor = 0; soundId = -1; showPreview = true }
    private fun full(content: TdApi.MessageContent) = TdApi.Notification(3, 100, false,
        TdApi.NotificationTypeNewMessage(TdApi.Message().apply { id = 108; chatId = 10; this.content = content }, true))
    private fun push(content: TdApi.PushMessageContent?) = TdApi.Notification(3, 100, false,
        TdApi.NotificationTypeNewPushMessage().apply { messageId = 108; this.content = content })
    private fun group(mentions: Boolean, notification: TdApi.Notification) = TdApi.NotificationGroup().apply {
        id = 2; chatId = 10; totalCount = 1; notifications = arrayOf(notification)
        type = if (mentions) TdApi.NotificationGroupTypeMentions() else TdApi.NotificationGroupTypeMessages()
    }
}
