package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real OfficialTdSession/settings adapters and ordinarySend; the native boundary only records calls. */
class OfficialTdReactionNotificationGateTest {
    @Test fun settingsCacheReadsAndNativeUpdatesNeverInventAGetOrRequestServerConfirmation() {
        val f = Fixture()
        val seen = mutableListOf<ReactionNotificationNativeState>()
        f.api.observe(seen::add)
        assertEquals(ReactionNotificationNativeState(), f.api.state())
        assertTrue(f.calls.isEmpty())
        f.settings.accept(TdApi.UpdateReactionNotificationSettings(native()))
        assertEquals(ReactionNotificationNativeState(value, 1), f.api.state())
        assertEquals(listOf(ReactionNotificationNativeState(value, 1)), seen)
        repeat(3) { assertEquals(value, f.api.state().value) }
        assertTrue(f.calls.isEmpty(), "The source is the unique session cache; there is no public reaction-settings Get.")
        val expected = f.api.state()
        f.api.save(expected, patch, { true }, f.results::add)
        val call = f.calls.single()
        val set = assertIs<TdApi.SetReactionNotificationSettings>(call.command)
        assertEquals(value.soundId, set.notificationSettings.soundId)
        assertIs<TdApi.ReactionNotificationSourceContacts>(set.notificationSettings.storyReactionSource)
        call.reply(TdApi.Ok())
        assertTrue(f.results.single().locallyAccepted)
        assertEquals(expected, f.api.state(), "Set Ok is local acceptance and does not revise the cache.")
        assertEquals(listOf("SetReactionNotificationSettings"), f.calls.map { it.command.javaClass.simpleName })
    }

    @Test fun ordinarySendRechecksNativeReadyAndMandatoryGateIndependentlyBeforeTheActualReactionSet() {
        for (mandatory in listOf(false, true)) {
            val f = Fixture()
            f.settings.accept(TdApi.UpdateReactionNotificationSettings(native()))
            val expected = f.api.state()
            if (mandatory) f.gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            else f.ready = false
            assertEquals(mandatory, f.session.authorizationReady())
            assertEquals(!mandatory, f.gate.allowsOrdinary())
            // The facade's active predicate stays true: the real session ordinarySend must reject this.
            f.api.save(expected, patch, { true }, f.results::add)
            assertTrue(f.calls.isEmpty())
            assertFalse(f.results.single().locallyAccepted)
            assertNotNull(f.results.single().error)
            assertEquals(expected, f.api.state())
            f.ready = true
            f.gate.update(LoginEmailSuggestions(2))
            f.api.save(expected, patch, { true }, f.results::add)
            assertIs<TdApi.SetReactionNotificationSettings>(f.calls.single().command)
            f.calls.single().reply(TdApi.Ok())
            assertEquals(2, f.results.size)
            assertTrue(f.results.last().locallyAccepted)
            assertNull(f.results.last().error)
            assertEquals(expected, f.results.last().actual)
            assertEquals(1, f.calls.size, "Recovery admits a fresh Set and does not replay the denied write.")
        }
    }

    @Test fun closeAndSettingsRetirementFreezeCacheAndPreserveOnlyTheOriginalInflightLocalAcceptance() {
        for (closeSession in listOf(false, true)) {
            val f = Fixture()
            f.settings.accept(TdApi.UpdateReactionNotificationSettings(native()))
            val expected = f.api.state()
            val seen = mutableListOf<ReactionNotificationNativeState>()
            f.api.observe(seen::add)
            f.api.save(expected, patch, { true }, f.results::add)
            val write = f.calls.single()
            assertIs<TdApi.SetReactionNotificationSettings>(write.command)
            if (closeSession) {
                f.session.close(); f.session.close()
                assertIs<TdApi.Close>(f.calls.last().command)
                assertEquals(2, f.calls.size)
            } else {
                f.settings.retire(); f.settings.retire()
                assertEquals(1, f.calls.size)
            }
            f.settings.accept(TdApi.UpdateReactionNotificationSettings(native().apply { soundId = 999 }))
            assertEquals(expected, f.api.state())
            assertTrue(seen.isEmpty())
            write.reply(TdApi.Ok()); write.reply(TdApi.Ok()); write.fail()
            assertTrue(f.results.single().locallyAccepted, "Retirement cannot erase the original request's real local acceptance.")
            assertEquals(SettingsError.NOT_READY, f.results.single().error)
            assertEquals(expected, f.results.single().actual)
            val count = f.calls.size
            f.api.save(expected, patch, { true }, f.results::add)
            assertEquals(count, f.calls.size)
            assertEquals(2, f.results.size)
            assertFalse(f.results.last().locallyAccepted)
            assertEquals(SettingsError.NOT_READY, f.results.last().error)
        }
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)

    private class Fixture {
        var ready = true
        val gate = TdUserInteractionGate()
        val calls = mutableListOf<Call>()
        val results = mutableListOf<ReactionNotificationSaveResult>()
        val session = OfficialTdSession(offline = true, interactionGate = gate, isAuthorizationReady = { ready }) { request, reply, fail ->
            calls += Call(request, reply, fail)
        }
        val settings = session.settings as OfficialTdSettingsSession
        val api = assertNotNull(settings.reactionNotifications)
    }

    private companion object {
        val value = ReactionNotificationValue(ReactionNotificationSource.ALL, ReactionNotificationSource.CONTACTS,
            ReactionNotificationSource.NONE, 901, true)
        val patch = ReactionNotificationPatch(message = ReactionNotificationSource.CONTACTS)
        fun native() = TdApi.ReactionNotificationSettings(TdApi.ReactionNotificationSourceAll(),
            TdApi.ReactionNotificationSourceContacts(), TdApi.ReactionNotificationSourceNone(), 901, true)
    }
}
