package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual session/settings/native-helper composition with only the final native send replaced. */
class OfficialTdUsernameSessionGateTest {
    @Test fun readySessionUsesFreshSelfForExactNativeQueryAndDeliversItsReadOnlyResult() {
        val f = Fixture()
        val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        f.settings.checkUsername(43, "Mixed_Name", { true }, results::add)
        assertIs<TdApi.GetMe>(f.last.command)
        f.last.reply(self())
        val query = assertIs<TdApi.CheckChatUsername>(f.last.command)
        assertEquals(43L, query.chatId)
        assertEquals("Mixed_Name", query.username)
        assertTrue(results.isEmpty())
        f.last.reply(TdApi.CheckChatUsernameResultOk())
        assertEquals(SettingsUsernameAvailability(43, "Mixed_Name", SettingsUsernameAvailabilityStatus.CAN_BE_SET),
            results.single().value)
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.all { it.command is TdApi.GetMe || it.command is TdApi.CheckChatUsername })
    }

    @Test fun independentNativeReadyMandatoryAndRetiredSettingsGatesRejectBeforeGetMe() {
        for (gate in 0..2) {
            val f = Fixture()
            when (gate) {
                0 -> f.ready = false
                1 -> f.required()
                else -> f.settings.retire()
            }
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            f.settings.checkUsername(43, "candidate", { true }, results::add)
            assertTrue(f.calls.isEmpty(), "Admission gate $gate must reject before the synthetic native ledger.")
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
        }
    }

    @Test fun readyMandatoryAndSettingsActiveAreRecheckedBetweenGetMeAndCheckChatUsername() {
        for (gate in 0..2) {
            val f = Fixture()
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            f.settings.checkUsername(43, "candidate", { true }, results::add)
            val getMe = f.last
            assertIs<TdApi.GetMe>(getMe.command)
            when (gate) {
                0 -> f.ready = false
                1 -> f.required()
                else -> f.settings.retire()
            }
            getMe.reply(self()); getMe.reply(self()); getMe.failed()
            assertEquals(1, f.calls.size, "Continuation gate $gate must prevent CheckChatUsername.")
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
        }
    }

    @Test fun sessionCloseStopsPendingContinuationAndRejectsFurtherReadsWithoutDuplicatingClose() {
        val f = Fixture()
        val pending = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        f.settings.checkUsername(43, "candidate", { true }, pending::add)
        val getMe = f.last
        assertIs<TdApi.GetMe>(getMe.command)
        f.session.close(); f.session.close()
        assertFalse(f.gate.allowsOrdinary())
        assertEquals(1, f.calls.count { it.command is TdApi.Close })
        getMe.reply(self()); getMe.reply(self()); getMe.failed()
        assertEquals(listOf(SettingsError.NOT_READY), pending.map { it.error })
        val afterClose = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        f.settings.checkUsername(43, "another_name", { true }, afterClose::add)
        assertEquals(listOf(SettingsError.NOT_READY), afterClose.map { it.error })
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.none { it.command is TdApi.CheckChatUsername })
    }

    @Test fun callerRevocationAtEitherNativeStepAndSettingsRetirementRejectLateResultsOnce() {
        for (stage in 0..2) {
            val f = Fixture()
            val current = AtomicBoolean(true)
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            f.settings.checkUsername(43, "candidate", current::get, results::add)
            if (stage == 0) {
                val getMe = f.last
                current.set(false)
                getMe.reply(self()); getMe.failed()
                assertEquals(1, f.calls.size)
            } else {
                f.last.reply(self())
                val query = f.last
                assertIs<TdApi.CheckChatUsername>(query.command)
                if (stage == 1) current.set(false) else f.settings.retire()
                query.reply(TdApi.CheckChatUsernameResultOk())
                query.reply(TdApi.CheckChatUsernameResultUsernameOccupied()); query.failed()
                assertEquals(2, f.calls.size)
            }
            assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
            assertNull(results.single().value)
        }
    }

    @Test fun wrongSelfAndRepeatedCallbacksCannotAuthorizeOrCompleteAnotherSessionQuery() {
        val f = Fixture()
        val wrong = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        val right = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
        f.settings.checkUsername(43, "wrong_owner", { true }, wrong::add)
        val first = f.last
        f.settings.checkUsername(43, "right_owner", { true }, right::add)
        val second = f.last
        first.reply(self(99)); first.reply(self(43)); first.failed()
        assertEquals(listOf(SettingsError.NOT_READY), wrong.map { it.error })
        assertEquals(2, f.calls.size)
        assertTrue(right.isEmpty())
        second.reply(self()); second.reply(self()); second.failed()
        val query = f.last
        assertEquals("right_owner", assertIs<TdApi.CheckChatUsername>(query.command).username)
        assertEquals(3, f.calls.size)
        query.reply(TdApi.CheckChatUsernameResultOk()); query.failed()
        query.reply(TdApi.CheckChatUsernameResultUsernameOccupied())
        assertEquals(SettingsUsernameAvailabilityStatus.CAN_BE_SET, right.single().value?.status)
        assertEquals(1, wrong.size)
        assertEquals(1, right.size)
        assertEquals(1, f.calls.count { it.command is TdApi.CheckChatUsername })
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private class Fixture {
        var ready = true
        val gate = TdUserInteractionGate()
        private val suggestions = TdLoginEmailSuggestions(gate)
        val calls = mutableListOf<Call>()
        val session = OfficialTdSession(offline = true, interactionGate = gate,
            loginEmailSuggestions = suggestions, isAuthorizationReady = { ready },
            send = { command, reply, failed -> calls += Call(command, reply, failed) })
        val settings get() = session.settings
        val last get() = calls.last()
        fun required() = suggestions.accept(TdApi.UpdateSuggestedActions(
            arrayOf(TdApi.SuggestedActionSetLoginEmailAddress(false)), emptyArray()))
    }

    private companion object {
        fun self(id: Long = 43) = TdApi.User().apply {
            this.id = id
            usernames = TdApi.Usernames().apply { editableUsername = "old_name" }
        }
    }
}
