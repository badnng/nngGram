package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class NativeChatNotificationMutationsTest {
    @Test fun officialSessionSharesTheWholeMuteAndSettingsMutationCycleIncludingReadback() {
        val requests = mutableListOf<Pending>()
        val session = OfficialTdSession(false) { command, reply, failed -> requests += Pending(command, reply, failed) }
        val chats = session.chats
        val settings = assertNotNull(session.settings.notificationExceptions)
        val original = values()
        val muted = original.copy(useDefaultMuteFor = false, muteFor = Int.MAX_VALUE)
        val listResults = mutableListOf<ChatResult<Unit>>()
        val editResults = mutableListOf<NotificationExceptionWriteResult>()
        chats.performChatListAction(10, ChatListRef(), ChatListAction.MUTE, listResults::add)
        settings.save(NotificationExceptionEdit(peer(original), original.copy(showPreview = true),
            setOf(NotificationExceptionField.PREVIEW)), { true }, editResults::add)
        assertEquals(1, requests.size, "The settings fresh read must wait for the chat-list lane.")
        requests.last().reply(chat(original))
        assertIs<TdApi.SetChatNotificationSettings>(requests.last().command)
        requests.last().reply(TdApi.Ok())
        assertIs<TdApi.GetChat>(requests.last().command)
        assertEquals(3, requests.size); assertTrue(listResults.isEmpty())
        requests.last().reply(TdApi.Error(500, "synthetic readback failure"))
        assertEquals(Unit, listResults.single().value, "A real SetOk is local acceptance even if its readback failed.")
        assertIs<TdApi.GetMe>(requests.last().command)
        requests.last().reply(user(1)); requests.last().reply(TdApi.OptionValueInteger(900)); requests.last().reply(TdApi.OptionValueInteger(901))
        requests.last().reply(chat(muted)); requests.last().reply(user(71))
        val next = assertIs<TdApi.SetChatNotificationSettings>(requests.last().command).notificationSettings
        assertEquals(Int.MAX_VALUE, next.muteFor); assertTrue(next.showPreview); assertEquals(original.storySoundId, next.storySoundId)
        requests.last().reply(TdApi.Ok())
        val before = requests.size
        chats.performChatListAction(10, ChatListRef(), ChatListAction.UNMUTE) {}
        assertEquals(before, requests.size, "The setting writer keeps the lane through its readback.")
        requests.last().reply(TdApi.Error(500, "synthetic"))
        assertTrue(editResults.single().locallyAccepted); assertEquals(SettingsError.REQUEST_FAILED, editResults.single().error)
        assertIs<TdApi.GetChat>(requests.last().command)
    }

    @Test fun lanesArePerChatAndOnlyARealTerminalCallbackStartsTheNextWriter() {
        val requests = mutableListOf<Pending>(); val coordinator = coordinator(requests)
        val results = mutableListOf<ChatResult<Unit>>()
        coordinator.mute(10, true, results::add); coordinator.mute(10, false, results::add); coordinator.mute(11, true, results::add)
        assertEquals(2, requests.size)
        val first = requests.first(); first.reply(chat(values()))
        val set = requests.last(); set.reply(TdApi.Ok()); set.reply(TdApi.Ok()); set.failed()
        assertEquals(4, requests.size); assertTrue(results.isEmpty())
        val readback = requests.last(); readback.reply(TdApi.Error(500, "private")); readback.reply(TdApi.Ok()); readback.failed()
        assertEquals(Unit, results.single().value)
        assertEquals(5, requests.size); assertEquals(10L, assertIs<TdApi.GetChat>(requests.last().command).chatId)
    }

    @Test fun externalUpdateRejectsAnOldFreshCandidateAndReleasesTheLaneForAnExplicitRetry() {
        val requests = mutableListOf<Pending>(); val coordinator = coordinator(requests)
        val failed = mutableListOf<ChatResult<Unit>>()
        coordinator.mute(10, true, failed::add)
        coordinator.accept(TdApi.UpdateChatNotificationSettings(10, values().native()))
        requests.last().reply(chat(values()))
        assertNotNull(failed.single().error); assertEquals(1, requests.size)
        coordinator.mute(10, true) {}
        assertEquals(2, requests.size); requests.last().reply(chat(values()))
        assertIs<TdApi.SetChatNotificationSettings>(requests.last().command)
    }

    @Test fun queuedCancellationNeverSendsAndRetirementStillReportsALateSetOkAsLocalAcceptance() {
        val requests = mutableListOf<Pending>(); val coordinator = coordinator(requests)
        var active = true; var bodyStarted = false; val rejected = mutableListOf<SettingsError>()
        val accepted = mutableListOf<ChatResult<Unit>>()
        coordinator.mute(10, true, accepted::add)
        coordinator.submit(10, { active }, rejected::add) { bodyStarted = true; it.finish() }
        active = false
        requests.last().reply(chat(values()))
        val set = requests.last()
        coordinator.retire(); coordinator.retire()
        assertEquals(listOf(SettingsError.NOT_READY), rejected); assertFalse(bodyStarted)
        set.reply(TdApi.Ok()); set.reply(TdApi.Ok())
        assertEquals(Unit, accepted.single().value); assertEquals(2, requests.size)
    }

    @Test fun queueLimitsBoundBothPerChatAndWholeSessionAndReleasedCapacityIsReusable() {
        val coordinator = NativeChatNotificationMutations { _, _, _ -> error("No native request expected") }
        var first: NativeChatNotificationMutations.Lease? = null
        val queued = mutableListOf<SettingsError>()
        coordinator.submit(10, { true }, queued::add) { first = it }
        repeat(32) { coordinator.submit(10, { true }, queued::add) { it.finish() } }
        coordinator.submit(10, { true }, queued::add) { fail("Per-chat overflow must not execute") }
        assertEquals(listOf(SettingsError.RATE_LIMITED), queued)
        first!!.finish(); first!!.finish()
        var started = false; coordinator.submit(10, { true }, queued::add) { started = true; it.finish() }
        assertTrue(started)
        val leases = mutableListOf<NativeChatNotificationMutations.Lease>(); val overflow = mutableListOf<SettingsError>()
        repeat(128) { id -> coordinator.submit(100L + id, { true }, overflow::add, leases::add) }
        coordinator.submit(999, { true }, overflow::add) { fail("Global overflow must not execute") }
        assertEquals(listOf(SettingsError.RATE_LIMITED), overflow)
        leases.first().finish(); var reused = false
        coordinator.submit(999, { true }, overflow::add) { reused = true; it.finish() }
        assertTrue(reused); leases.drop(1).forEach { it.finish() }
    }

    @Test fun exceptionalBodiesAndRejectObserversCannotLeakALaneOrStopRetirementCleanup() {
        val coordinator = NativeChatNotificationMutations { _, _, _ -> error("No native request expected") }
        val failures = mutableListOf<SettingsError>()
        coordinator.submit(10, { true }, { failures += it; error("callback failure") }) { error("body failure") }
        assertEquals(listOf(SettingsError.REQUEST_FAILED), failures)
        var held: NativeChatNotificationMutations.Lease? = null
        coordinator.submit(10, { true }, failures::add) { held = it }
        coordinator.submit(10, { true }, { error("rejection observer failure") }) { fail("Retired queue must not start") }
        coordinator.submit(10, { true }, failures::add) { fail("Retired queue must not start") }
        coordinator.retire(); held!!.finish()
        assertEquals(listOf(SettingsError.REQUEST_FAILED, SettingsError.NOT_READY), failures)
        val responses = mutableListOf<SettingsResult<TdApi.Object>>()
        held!!.request(TdApi.GetChat(10), responses::add)
        assertEquals(SettingsError.NOT_READY, responses.single().error)
        val next = NativeChatNotificationMutations { _, _, _ -> error("No native request expected") }
        var blocker: NativeChatNotificationMutations.Lease? = null
        var active = true; var thirdStarted = false
        next.submit(10, { true }, {}) { blocker = it }
        next.submit(10, { active }, { error("cancelled observer failure") }) { fail("Cancelled work must not start") }
        next.submit(10, { true }, {}) { thirdStarted = true; it.finish() }
        active = false; blocker!!.finish()
        assertTrue(thirdStarted, "A throwing cancelled observer must not strand the following writer.")
    }

    @Test fun failedDiscardedThrowingAndDuplicateNativeBoundariesCompleteOnceAndReleaseTheirLanes() {
        for (throwing in listOf(false, true)) {
            val requests = mutableListOf<Pending>()
            val coordinator = NativeChatNotificationMutations { command, reply, failed ->
                requests += Pending(command, reply, failed)
                if (throwing) error("synthetic") else failed()
            }
            val results = mutableListOf<ChatResult<Unit>>()
            repeat(2) { coordinator.mute(10, true, results::add) }
            requests.forEach { it.failed(); it.reply(TdApi.Ok()) }
            assertEquals(2, requests.size); assertEquals(2, results.size); assertTrue(results.all { it.error != null })
        }
    }

    private class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private fun coordinator(requests: MutableList<Pending>) = NativeChatNotificationMutations { command, reply, failed -> requests += Pending(command, reply, failed) }
    private companion object {
        fun values() = ChatNotificationValues(false, 60, false, Long.MIN_VALUE, false, false,
            false, true, false, -2, false, false, false, true, false, true)
        fun peer(values: ChatNotificationValues) = NotificationExceptionPeer(10, NotificationScope.PRIVATE_CHATS,
            "synthetic", values, System.currentTimeMillis())
        fun chat(values: ChatNotificationValues) = TdApi.Chat().apply { id = 10; type = TdApi.ChatTypePrivate(71); notificationSettings = values.native() }
        fun user(id: Long) = TdApi.User().apply { this.id = id; type = TdApi.UserTypeRegular() }
    }
}
