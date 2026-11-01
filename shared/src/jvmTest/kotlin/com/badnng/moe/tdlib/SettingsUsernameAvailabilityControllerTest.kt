package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

/** Real controller scheduling and ownership; the session fake does not gate or deduplicate anything. */
class SettingsUsernameAvailabilityControllerTest {
    @Test fun workerCurrentNeverReadsActorGuardAndNativeResultWaitsForActorPublication() {
        val f = Fixture()
        val results = f.check("Exact_Case")
        val native = f.session.checks.single()
        val guardCalls = f.guardCalls
        var workerCurrent = false
        thread {
            workerCurrent = native.current()
            native.result(SettingsResult(value("Exact_Case", status = SettingsUsernameAvailabilityStatus.UNKNOWN)))
        }.join()
        assertTrue(workerCurrent)
        assertEquals(guardCalls, f.guardCalls, "The native current predicate cannot touch the actor-only admission guard.")
        assertTrue(results.isEmpty())
        f.drain()
        assertEquals(value("Exact_Case", status = SettingsUsernameAvailabilityStatus.UNKNOWN), results.single().value)
        assertTrue(f.guardCalls > guardCalls)
        assertFalse(native.current())
    }

    @Test fun rapidQueriesRetainOnlyLatestPendingAndCannotStartMoreNativeWorkUntilActualCallback() {
        val f = Fixture()
        val first = f.check("First")
        val original = f.session.checks.single()
        val consumers = (1..24).map { index -> f.check("Candidate$index") }
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertFalse(original.current())
        assertTrue(consumers.dropLast(1).all { it.single().error == SettingsError.NOT_READY })
        assertTrue(consumers.last().isEmpty())
        f.drain()
        assertEquals(1, f.session.checks.size, "Closing the source is not cancellation of an outstanding native RPC.")
        original.result(SettingsResult(value("First")))
        assertEquals(1, f.session.checks.size)
        f.drain()
        assertEquals(2, f.session.checks.size)
        val latest = f.session.checks.last()
        assertEquals("Candidate24", latest.username)
        assertTrue(latest.current())
        latest.result(SettingsResult(value("Candidate24"))); f.drain()
        assertEquals(value("Candidate24"), consumers.last().single().value)
        assertEquals(1, first.size)
        assertTrue(consumers.all { it.size == 1 })
    }

    @Test fun sameUsernameHasNewIdentityAndDuplicateOldCallbackCannotClearReplacementSlot() {
        val f = Fixture()
        val old = f.check("Same_name")
        val oldNative = f.session.checks.single()
        val retyped = f.check("Same_name")
        assertEquals(SettingsError.NOT_READY, old.single().error)
        assertTrue(retyped.isEmpty())
        oldNative.result(SettingsResult(value("Same_name"))); f.drain()
        val replacement = f.session.checks.last()
        assertEquals(2, f.session.checks.size)
        val third = f.check("Third")
        assertEquals(SettingsError.NOT_READY, retyped.single().error)
        oldNative.result(SettingsResult(value("Same_name")))
        oldNative.result(SettingsResult(error = SettingsError.FORBIDDEN)); f.drain()
        assertEquals(2, f.session.checks.size, "A duplicate old callback cannot release the new physical slot.")
        assertTrue(third.isEmpty())
        replacement.result(SettingsResult(value("Same_name"))); f.drain()
        assertEquals(3, f.session.checks.size)
        assertEquals("Third", f.session.checks.last().username)
        f.session.checks.last().result(SettingsResult(value("Third"))); f.drain()
        assertEquals(value("Third"), third.single().value)
        assertEquals(1, old.size)
        assertEquals(1, retyped.size)
    }

    @Test fun pendingViewThatExpiresBeforePumpReceivesNotReadyWithoutStartingNativeWork() {
        val f = Fixture()
        val first = f.check("First")
        val visible = AtomicBoolean(true)
        val pending = f.check("Pending", visible::get)
        visible.set(false)
        f.session.checks.single().result(SettingsResult(value("First")))
        assertTrue(pending.isEmpty())
        f.drain()
        assertEquals(1, f.session.checks.size)
        assertEquals(SettingsError.NOT_READY, pending.single().error)
        assertEquals(SettingsError.NOT_READY, first.single().error)
    }

    @Test fun wrongResponseIdentityAndEmptyDataFailWhileNativeErrorsRemainTyped() {
        val f = Fixture()
        val malformed = listOf(SettingsResult(value("Candidate", userId = 44)),
            SettingsResult(value("candidate")), SettingsResult<SettingsUsernameAvailability>())
        for (response in malformed) {
            val results = f.check("Candidate")
            f.session.checks.last().result(response); f.drain()
            assertEquals(SettingsError.REQUEST_FAILED, results.single().error)
            assertNull(results.single().value)
        }
        val failure = f.check("Candidate")
        f.session.checks.last().result(SettingsResult(error = SettingsError.RATE_LIMITED)); f.drain()
        assertEquals(SettingsError.RATE_LIMITED, failure.single().error)
        val mixed = f.check("Candidate")
        f.session.checks.last().result(SettingsResult(value("Candidate"), SettingsError.FORBIDDEN)); f.drain()
        assertEquals(SettingsError.FORBIDDEN, mixed.single().error)
        assertNull(mixed.single().value, "A native error must never publish data from the same response.")
    }

    @Test fun retirementClosesSourcesAndConsumersOnceAndLateCallbacksCannotRestartPendingWork() {
        val f = Fixture()
        val running = f.check("Running")
        val native = f.session.checks.single()
        val pending = f.check("Pending")
        f.controller.retire(); f.controller.retire()
        assertFalse(native.current())
        assertEquals(SettingsError.NOT_READY, running.single().error)
        assertEquals(SettingsError.NOT_READY, pending.single().error)
        val after = f.check("After_retire")
        assertEquals(SettingsError.NOT_READY, after.single().error)
        native.result(SettingsResult(value("Running")))
        native.result(SettingsResult(value("Running"))); f.drain()
        assertEquals(1, f.session.checks.size)
        assertEquals(1, running.size)
        assertEquals(1, pending.size)
        assertEquals(1, after.size)
    }

    @Test fun rejectedInputOwnerAndThrowingViewDoNotDisplaceAnAdmittedPhysicalRequest() {
        val f = Fixture()
        val admitted = f.check("Candidate")
        val native = f.session.checks.single()
        val tooLong = f.check("a".repeat(33))
        val staleToken = f.check("Old_owner", token = f.token.copy(revision = f.token.revision + 1))
        val otherUser = f.check("Other_user", userId = 44)
        val throwing = f.check("Throwing", { throw IllegalStateException("synthetic current failure") })
        assertEquals(SettingsError.INVALID_VALUE, tooLong.single().error)
        assertEquals(SettingsError.NOT_READY, staleToken.single().error)
        assertEquals(SettingsError.NOT_READY, otherUser.single().error)
        assertEquals(SettingsError.NOT_READY, throwing.single().error)
        assertTrue(native.current())
        assertTrue(admitted.isEmpty())
        assertEquals(1, f.session.checks.size)
        native.result(SettingsResult(value("Candidate"))); f.drain()
        assertEquals(value("Candidate"), admitted.single().value)
        val boundary = f.check("a".repeat(32))
        assertEquals("a".repeat(32), f.session.checks.last().username)
        f.session.checks.last().result(SettingsResult(value("a".repeat(32)))); f.drain()
        assertNotNull(boundary.single().value)
    }

    @Test fun throwingNativeCurrentClosesItsAtomicSourcePermanentlyWithoutFreeingPhysicalSlot() {
        val f = Fixture()
        val throws = AtomicBoolean(false)
        val first = f.check("First", { if (throws.get()) error("synthetic current failure") else true })
        val native = f.session.checks.single()
        throws.set(true)
        var workerCurrent = true
        thread { workerCurrent = native.current() }.join()
        assertFalse(workerCurrent)
        throws.set(false)
        assertFalse(native.current(), "A source closed by its own failed current predicate cannot revive.")
        val pending = f.check("Pending")
        f.drain()
        assertEquals(1, f.session.checks.size)
        assertEquals(SettingsError.NOT_READY, first.single().error)
        native.result(SettingsResult(value("First"))); f.drain()
        assertEquals(2, f.session.checks.size)
        f.session.checks.last().result(SettingsResult(value("Pending"))); f.drain()
        assertEquals(value("Pending"), pending.single().value)
    }

    @Test fun consumerReentryAndExceptionsKeepLatestPendingAndLeaveActorAbleToServeFollowingQueries() {
        val f = Fixture()
        lateinit var reentrant: MutableList<SettingsResult<SettingsUsernameAvailability>>
        lateinit var following: MutableList<SettingsResult<SettingsUsernameAvailability>>
        val first = f.check("First", consumer = {
            reentrant = f.check("Reentrant", consumer = { response ->
                if (response.value != null) following = f.check("Following")
                throw IllegalStateException("synthetic completion consumer failure")
            })
            throw IllegalStateException("synthetic cancelled consumer failure")
        })
        val native = f.session.checks.single()
        val displaced = f.check("Displaced", consumer = { throw IllegalStateException("synthetic pending consumer failure") })
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(SettingsError.NOT_READY, displaced.single().error)
        assertTrue(reentrant.isEmpty())
        assertEquals(1, f.session.checks.size)
        native.result(SettingsResult(value("First"))); f.drain()
        assertEquals(listOf("First", "Reentrant"), f.session.checks.map { it.username })
        f.session.checks.last().result(SettingsResult(value("Reentrant"))); f.drain()
        assertEquals(value("Reentrant"), reentrant.single().value)
        assertEquals(listOf("First", "Reentrant", "Following"), f.session.checks.map { it.username })
        assertTrue(following.isEmpty())
        f.session.checks.last().result(SettingsResult(value("Following"))); f.drain()
        assertEquals(value("Following"), following.single().value)
        assertEquals(1, first.size)
        assertEquals(1, displaced.size)
    }

    @Test fun synchronousNativeCompletionAndInvocationFailureStillCrossActorAndReleaseOnlyTheirOwnSlot() {
        val f = Fixture()
        f.session.onCheck = { it.result(SettingsResult(value(it.username))) }
        val first = f.check("First")
        assertTrue(first.isEmpty())
        val latest = f.check("Latest")
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertTrue(latest.isEmpty())
        assertEquals(1, f.session.checks.size)
        f.drain()
        assertEquals(2, f.session.checks.size)
        assertEquals(value("Latest"), latest.single().value)
        f.session.onCheck = { throw IllegalStateException("synthetic native invocation failure") }
        val failed = f.check("Failed")
        assertTrue(failed.isEmpty())
        f.drain()
        assertEquals(SettingsError.REQUEST_FAILED, failed.single().error)
        val throwingCall = f.session.checks.last()
        f.session.onCheck = {}
        val following = f.check("Following")
        throwingCall.result(SettingsResult(value("Failed"))); f.drain()
        assertTrue(following.isEmpty())
        f.session.checks.last().result(SettingsResult(value("Following"))); f.drain()
        assertEquals(value("Following"), following.single().value)
        assertEquals(1, failed.size)
    }

    @Test fun settingsControllerDelegatesActualProfileTokenAndOrdinaryGateAndRetiresChild() {
        val session = Session()
        val queue = ArrayDeque<() -> Unit>()
        var snapshot = SettingsSnapshot()
        var ordinary = true
        val controller = SettingsController(7, session, { queue.addLast(it) }, { snapshot = it }, ordinaryAllowed = { ordinary })
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun check(username: String, userId: Long = 43, token: SettingsToken = snapshot.token): MutableList<SettingsResult<SettingsUsernameAvailability>> {
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            controller.checkUsername(token, userId, username, { true }, results::add)
            return results
        }
        controller.start()
        assertEquals(SettingsError.NOT_READY, check("Unloaded").single().error)
        controller.load(snapshot.token, SettingsSection.PROFILE); drain()
        assertEquals(SettingsError.NOT_READY, check("Wrong_user", userId = 44).single().error)
        assertEquals(SettingsError.NOT_READY, check("Old_token", token = snapshot.token.copy(generation = 6)).single().error)
        assertTrue(session.checks.isEmpty())
        val first = check("First")
        val pending = check("Pending")
        ordinary = false
        session.checks.single().result(SettingsResult(value("First"))); drain()
        assertEquals(SettingsError.NOT_READY, first.single().error)
        assertEquals(SettingsError.NOT_READY, pending.single().error)
        assertEquals(1, session.checks.size, "The ordinary gate is checked again before pending admission.")

        ordinary = true
        val oldSelf = check("Old_self")
        session.profileUserId = 44
        controller.load(snapshot.token, SettingsSection.PROFILE); drain()
        session.checks.last().result(SettingsResult(value("Old_self"))); drain()
        assertEquals(SettingsError.NOT_READY, oldSelf.single().error, "Completion revalidates the actual loaded profile identity.")
        val active = check("Active", userId = 44)
        val native = session.checks.last()
        val waiting = check("Waiting", userId = 44)
        controller.deactivate()
        assertFalse(native.current())
        assertEquals(SettingsError.NOT_READY, active.single().error)
        assertEquals(SettingsError.NOT_READY, waiting.single().error)
        val retired = snapshot
        val calls = session.checks.size
        native.result(SettingsResult(value("Active", userId = 44))); drain()
        assertEquals(retired, snapshot)
        assertEquals(calls, session.checks.size)
        assertEquals(1, session.retirements)
        assertEquals(1, active.size)
        assertEquals(1, waiting.size)
    }

    private class Fixture {
        val token = SettingsToken(7, 1)
        val session = Session()
        private val actor = Thread.currentThread()
        private val queue = ArrayDeque<() -> Unit>()
        var guardCalls = 0
        val controller = SettingsUsernameAvailabilityController(session, { queue.addLast(it) }) { candidate, userId ->
            kotlin.check(Thread.currentThread() === actor)
            guardCalls++
            candidate == token && userId == 43L
        }
        fun check(username: String, current: () -> Boolean = { true }, token: SettingsToken = this.token, userId: Long = 43,
            consumer: ((SettingsResult<SettingsUsernameAvailability>) -> Unit)? = null,
        ): MutableList<SettingsResult<SettingsUsernameAvailability>> {
            val results = mutableListOf<SettingsResult<SettingsUsernameAvailability>>()
            controller.check(token, userId, username, current) { response -> results += response; consumer?.invoke(response) }
            return results
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    /** Pure native boundary: stores every callback, including stale and duplicate invocations. */
    private class Session : TdSettingsSession {
        class Check(val userId: Long, val username: String, val current: () -> Boolean,
            val result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
        )
        val checks = mutableListOf<Check>()
        var onCheck: (Check) -> Unit = {}
        var profileUserId = 43L
        var retirements = 0
        override fun checkUsername(userId: Long, username: String, isCurrent: () -> Boolean,
            result: (SettingsResult<SettingsUsernameAvailability>) -> Unit,
        ) { Check(userId, username, isCurrent, result).also { checks += it; onCheck(it) } }
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) =
            result(SettingsResult(SettingsProfile(profileUserId, "Synthetic", "", "original", "", "")))
        override fun retire() { retirements++ }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
    }

    private companion object {
        fun value(username: String, userId: Long = 43, status: SettingsUsernameAvailabilityStatus = SettingsUsernameAvailabilityStatus.CAN_BE_SET) =
            SettingsUsernameAvailability(userId, username, status)
    }
}
