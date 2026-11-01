package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real facade and actor with synthetic native results; no actual account or database is touched. */
class SettingsPrivacyCommonOptionsTest {
    @Test fun sessionsReadPreservesNative366DaysSignedIdsAndCurrentIdentity() {
        val f = Fixture()
        f.openSessions(366)
        assertEquals(366, f.state.inactiveSessionTtlDays)
        assertEquals(listOf(1L, -77L), f.state.sessions.map { it.id })
        assertTrue(f.state.sessions.first().isCurrent)
        assertFalse(f.state.loading)
        val zero = Fixture()
        zero.controller.open(SettingsSecuritySection.SESSIONS)
        zero.last.respond(sessions(currentId = 0))
        zero.flush()
        assertEquals(0L, zero.state.sessions.single { it.isCurrent }.id)
        var rejected: SettingsResult<Unit>? = null
        zero.controller.terminate(zero.state.token, 0) { rejected = it }
        assertEquals(SettingsError.NOT_READY, rejected?.error)
        assertEquals(1, zero.calls.size)
    }

    @Test fun allOtherSessionsUsesDedicatedNativeMutationAndOnlyReturnsAfterReadback() {
        val f = Fixture()
        f.openSessions()
        val expected = f.state.sessions.toList()
        var outcome: SettingsResult<Unit>? = null
        f.controller.terminateOthers(f.state.token, expected) { outcome = it }
        assertTrue(f.state.busy)
        assertIs<TdApi.GetActiveSessions>(f.last.command)
        f.last.respond(sessions(activeDate = 100))
        assertIs<TdApi.TerminateAllOtherSessions>(f.last.command)
        assertTrue(f.calls.none { it.command is TdApi.TerminateSession })
        f.last.respond(TdApi.Ok())
        assertNull(outcome)
        f.flush()
        assertIs<TdApi.GetActiveSessions>(f.last.command)
        assertTrue(f.state.busy)
        f.last.respond(sessions(others = emptyList()))
        f.flush()
        assertNotNull(outcome?.value)
        assertNull(outcome?.error)
        assertEquals(listOf(1L), f.state.sessions.map { it.id })
        assertTrue(f.state.sessions.single().isCurrent)
    }

    @Test fun staleBatchOrFreshlyAddedDeviceCannotTerminateUnconfirmedSessions() {
        listOf(sessions(others = listOf(-77L, 55L)), sessions(model = "Changed device"),
            sessions(currentId = 99)).forEach { changed ->
            val f = Fixture()
            f.openSessions()
            var outcome: SettingsResult<Unit>? = null
            f.controller.terminateOthers(f.state.token, f.state.sessions) { outcome = it }
            f.last.respond(changed)
            f.flush()
            assertEquals(SettingsError.CONFLICT, outcome?.error)
            assertTrue(f.state.sessionsRefreshRequired)
            assertEquals(2, f.calls.size)
            assertTrue(f.calls.none { it.command is TdApi.TerminateAllOtherSessions })
        }
    }

    @Test fun batchPreflightRejectsCurrentOnlyDuplicateAndStaleUiIdentities() {
        val f = Fixture()
        f.openSessions()
        listOf(f.state.sessions.take(1), f.state.sessions + f.state.sessions.last(),
            f.state.sessions.map { it.copy(deviceModel = "stale") }).forEach { expected ->
            var outcome: SettingsResult<Unit>? = null
            f.controller.terminateOthers(f.state.token, expected) { outcome = it }
            assertEquals(SettingsError.CONFLICT, outcome?.error)
        }
        assertEquals(1, f.calls.size)
    }

    @Test fun sessionTtlUsesExpectedFreshValueAndKeepsActualReadback() {
        val f = Fixture()
        f.openSessions(366)
        var outcome: SettingsResult<Unit>? = null
        f.controller.inactiveTtl(f.state.token, 366, 7) { outcome = it }
        f.last.respond(sessions(days = 366))
        assertEquals(7, assertIs<TdApi.SetInactiveSessionTtl>(f.last.command).inactiveSessionTtlDays)
        assertEquals(366, f.state.inactiveSessionTtlDays)
        f.last.respond(TdApi.Ok())
        f.flush()
        assertNull(outcome)
        f.last.respond(sessions(days = 7))
        f.flush()
        assertEquals(7, f.state.inactiveSessionTtlDays)
        assertNotNull(outcome?.value)
    }

    @Test fun sessionTtlConflictAndInvalidBoundsDoNotWrite() {
        val f = Fixture()
        f.openSessions(30)
        var outcome: SettingsResult<Unit>? = null
        f.controller.inactiveTtl(f.state.token, 30, 0) { outcome = it }
        assertEquals(SettingsError.INVALID_VALUE, outcome?.error)
        f.controller.inactiveTtl(f.state.token, 30, 367) { outcome = it }
        assertEquals(SettingsError.INVALID_VALUE, outcome?.error)
        assertEquals(1, f.calls.size)
        f.controller.inactiveTtl(f.state.token, 30, 7) { outcome = it }
        f.last.respond(sessions(days = 90))
        f.flush()
        assertEquals(SettingsError.CONFLICT, outcome?.error)
        assertTrue(f.state.sessionsRefreshRequired)
        assertEquals(30, f.state.inactiveSessionTtlDays)
        assertEquals(2, f.calls.size)
    }

    @Test fun defaultMessageTtlIsLazyNativeSecondsAndZeroIsDistinctFromUnknown() {
        val f = Fixture()
        assertTrue(f.calls.isEmpty())
        assertNull(f.state.defaultMessageAutoDeleteSeconds)
        f.controller.open(SettingsSecuritySection.AUTO_DELETE)
        assertIs<TdApi.GetDefaultMessageAutoDeleteTime>(f.last.command)
        f.last.respond(TdApi.MessageAutoDeleteTime(0))
        f.flush()
        assertEquals(0, f.state.defaultMessageAutoDeleteSeconds)
        assertTrue(f.calls.none { it.command is TdApi.GetAccountTtl })
    }

    @Test fun customDefaultMessageTtlFreshChecksThenWritesDaysAndWaitsForReadback() {
        val f = Fixture()
        f.openAutoDelete(17 * 86400)
        var outcome: SettingsResult<Unit>? = null
        f.controller.defaultAutoDelete(f.state.token, 17 * 86400, 365 * 86400) { outcome = it }
        assertIs<TdApi.GetDefaultMessageAutoDeleteTime>(f.last.command)
        f.last.respond(TdApi.MessageAutoDeleteTime(17 * 86400))
        assertEquals(365 * 86400, assertIs<TdApi.SetDefaultMessageAutoDeleteTime>(f.last.command).messageAutoDeleteTime.time)
        f.last.respond(TdApi.Ok())
        f.flush()
        assertNull(outcome)
        f.last.respond(TdApi.MessageAutoDeleteTime(365 * 86400))
        f.flush()
        assertNotNull(outcome?.value)
        assertEquals(365 * 86400, f.state.defaultMessageAutoDeleteSeconds)
        assertTrue(f.calls.none { it.command is TdApi.SetChatMessageAutoDeleteTime || it.command is TdApi.SetAccountTtl })
    }

    @Test fun defaultTtlConflictAndNonDayValuesDoNotWrite() {
        val f = Fixture()
        f.openAutoDelete(0)
        var outcome: SettingsResult<Unit>? = null
        listOf(-1, 1, 86399, 366 * 86400).forEach { invalid ->
            f.controller.defaultAutoDelete(f.state.token, 0, invalid) { outcome = it }
            assertEquals(SettingsError.INVALID_VALUE, outcome?.error)
        }
        assertEquals(1, f.calls.size)
        f.controller.defaultAutoDelete(f.state.token, 0, 86400) { outcome = it }
        f.last.respond(TdApi.MessageAutoDeleteTime(7 * 86400))
        f.flush()
        assertEquals(SettingsError.CONFLICT, outcome?.error)
        assertTrue(f.state.defaultAutoDeleteRefreshRequired)
        assertEquals(0, f.state.defaultMessageAutoDeleteSeconds)
        assertEquals(2, f.calls.size)
    }

    @Test fun mutationErrorsAndSuccessfulMutationWithFailedRefreshStayDistinct() {
        val failed = Fixture()
        failed.openSessions()
        var failedOutcome: SettingsResult<Unit>? = null
        failed.controller.terminateOthers(failed.state.token, failed.state.sessions) { failedOutcome = it }
        failed.last.respond(sessions())
        failed.last.respond(TdApi.Error(400, "FRESH_RESET_AUTHORISATION_FORBIDDEN"))
        failed.flush()
        assertNull(failedOutcome?.value)
        assertNotNull(failedOutcome?.error)
        assertFalse(failed.state.busy)
        assertEquals(2, failed.state.sessions.size)

        val refreshed = Fixture()
        refreshed.openAutoDelete(0)
        var refreshedOutcome: SettingsResult<Unit>? = null
        refreshed.controller.defaultAutoDelete(refreshed.state.token, 0, 86400) { refreshedOutcome = it }
        refreshed.last.respond(TdApi.MessageAutoDeleteTime(0))
        refreshed.last.respond(TdApi.Ok())
        refreshed.flush()
        refreshed.last.respond(TdApi.Error(500, "private native error"))
        refreshed.flush()
        assertNotNull(refreshedOutcome?.value)
        assertNotNull(refreshedOutcome?.error)
        assertFalse(refreshed.state.busy)
        assertEquals(0, refreshed.state.defaultMessageAutoDeleteSeconds)
        assertTrue(refreshed.state.defaultAutoDeleteRefreshRequired)
        val callsBeforeRetry = refreshed.calls.size
        refreshed.controller.defaultAutoDelete(refreshed.state.token, 0, 86400) { refreshedOutcome = it }
        assertEquals(SettingsError.NOT_READY, refreshedOutcome?.error)
        assertEquals(callsBeforeRetry, refreshed.calls.size, "An unverified stale value cannot authorize another write.")
        refreshed.controller.refresh(refreshed.state.token)
        assertIs<TdApi.GetDefaultMessageAutoDeleteTime>(refreshed.last.command)
        refreshed.last.respond(TdApi.MessageAutoDeleteTime(86400))
        refreshed.flush()
        assertFalse(refreshed.state.defaultAutoDeleteRefreshRequired)
        assertEquals(86400, refreshed.state.defaultMessageAutoDeleteSeconds)
    }

    @Test fun closingOrRetiringDuringFreshChecksPreventsAllLaterNativeSendsAndCallbacks() {
        for (retire in listOf(false, true)) {
            val f = Fixture()
            f.openSessions()
            var callbacks = 0
            f.controller.terminateOthers(f.state.token, f.state.sessions) { callbacks++ }
            val pending = f.last
            if (retire) f.controller.deactivate() else f.controller.close(f.state.token)
            pending.respond(sessions())
            f.flush()
            assertEquals(2, f.calls.size)
            assertEquals(0, callbacks)
            assertNull(f.state.section)
        }
        val f = Fixture()
        f.openAutoDelete(0)
        var callbacks = 0
        f.controller.defaultAutoDelete(f.state.token, 0, 86400) { callbacks++ }
        val pending = f.last
        f.controller.open(SettingsSecuritySection.SESSIONS)
        pending.respond(TdApi.MessageAutoDeleteTime(0))
        f.flush()
        assertEquals(3, f.calls.size)
        assertEquals(0, callbacks)
        assertEquals(SettingsSecuritySection.SESSIONS, f.state.section)
    }

    @Test fun malformedOrTruncatedSessionReadsCannotAuthorizeBatchMutations() {
        listOf(TdApi.Sessions(emptyArray(), 30), sessions(days = 0), sessions(others = listOf(1L)),
            sessions(others = List(513) { -(it + 1).toLong() })).forEach { malformed ->
            val f = Fixture()
            f.controller.open(SettingsSecuritySection.SESSIONS)
            f.last.respond(malformed)
            f.flush()
            assertTrue(f.state.sessions.isEmpty())
            assertNotNull(f.state.error)
            assertNull(f.state.inactiveSessionTtlDays)
        }
    }

    private class Fixture {
        val calls = mutableListOf<Call>()
        val last get() = calls.last()
        private val queued = ArrayDeque<() -> Unit>()
        var state = SettingsSecuritySnapshot()
        private val session = OfficialTdSettingsSession { command, response, _ -> calls += Call(command, response) }
        val controller = SettingsSecurityController(9, session, { queued += it }, { state = it })
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun openSessions(days: Int = 30) {
            controller.open(SettingsSecuritySection.SESSIONS)
            last.respond(sessions(days = days))
            flush()
        }
        fun openAutoDelete(seconds: Int) {
            controller.open(SettingsSecuritySection.AUTO_DELETE)
            last.respond(TdApi.MessageAutoDeleteTime(seconds))
            flush()
        }
    }
    private data class Call(val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit)
    companion object {
        private fun sessions(days: Int = 30, others: List<Long> = listOf(-77), currentId: Long = 1,
            model: String = "Other device", activeDate: Int = 2) = TdApi.Sessions((listOf(
                TdApi.Session().apply { id = currentId; isCurrent = true; deviceModel = "Current device" }) + others.map { identity ->
                TdApi.Session().apply { id = identity; deviceModel = model; applicationName = "Native client"; lastActiveDate = activeDate }
            }).toTypedArray(), days)
    }
}
