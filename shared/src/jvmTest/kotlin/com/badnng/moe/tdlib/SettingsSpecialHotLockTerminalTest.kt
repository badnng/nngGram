package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Deterministic fake-native source tests. No account, TDLib instance or disk state is used. */
class SettingsSpecialHotLockTerminalTest {
    @Test fun realSuccessAfterLockPreservesReceiptButCannotContinueWithARead() {
        val f = Native()
        var accepted = false
        var receipt: SettingsSpecialWriteReceipt? = null
        val api = OfficialTdPrivacyCommon(f.send)
        api.setTopPeers(true, false, { true }) {
            accepted = it.value != null
            receipt = SettingsSpecialMutationContext.receipt()
            f.send(TdApi.GetMe(), { fail("Old receipt authorized a new read") }, {})
        }
        f.requests[0].result(TdApi.OptionValueBoolean(false))
        assertIs<TdApi.SetOption>(f.requests[1].command)
        f.lock()
        f.requests[1].result(TdApi.Ok())
        assertTrue(accepted)
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, receipt?.outcome)
        assertEquals(2, f.requests.size)
    }

    @Test fun actualNativeErrorAfterLockIsNeverTheSyntheticNotReadyError() {
        val f = Native()
        var result: SettingsResult<Unit>? = null
        var receipt: SettingsSpecialWriteReceipt? = null
        OfficialTdPrivacyCommon(f.send).setTopPeers(true, false, { true }) {
            result = it; receipt = SettingsSpecialMutationContext.receipt()
        }
        f.requests[0].result(TdApi.OptionValueBoolean(false))
        f.lock()
        f.requests[1].result(TdApi.Error(400, "FAKE_TEST_ONLY"))
        assertNull(result?.value)
        assertEquals(SettingsSpecialWriteOutcome.REJECTED, receipt?.outcome)
        assertNotEquals(SettingsError.NOT_READY, result?.error)
    }

    @Test fun unknownDispatchDoesNotEatTheLaterRealPhysicalTerminal() {
        val f = Native()
        val receipts = mutableListOf<SettingsSpecialWriteOutcome?>()
        OfficialTdPrivacyCommon(f.send).setTopPeers(true, false, { true }) {
            receipts += SettingsSpecialMutationContext.receipt()?.outcome
        }
        f.requests[0].result(TdApi.OptionValueBoolean(false))
        f.requests[1].failed()
        f.lock()
        f.requests[1].result(TdApi.Ok())
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.INDETERMINATE,
            SettingsSpecialWriteOutcome.ACCEPTED), receipts)
        assertEquals(2, f.requests.size)
    }

    @Test fun lockBeforeAdmissionProvesNotDispatched() {
        val f = Native()
        f.lock()
        var receipt: SettingsSpecialWriteReceipt? = null
        SettingsSpecialMutationContext.write(TdApi.SetOption("disable_top_chats", TdApi.OptionValueBoolean(true)),
            f.send, { fail("Blocked command dispatched") }, { receipt = SettingsSpecialMutationContext.receipt() })
        assertEquals(SettingsSpecialWriteOutcome.NOT_DISPATCHED, receipt?.outcome)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun contactSignupAcceptanceAfterLockDoesNotStartItsReadback() {
        val f = Native()
        val authority = f.gate.captureSpecialAuthority()
        var outcome: ContactSignupSaveResult? = null
        OfficialTdContactSignup(f.send).save(ContactSignupValue(true, ContactSignupValueSource.EXPLICIT), false,
            authority, { outcome = it })
        f.requests[0].result(TdApi.OptionValueBoolean(false))
        assertIs<TdApi.SetOption>(f.requests[1].command)
        f.lock()
        f.requests[1].result(TdApi.Ok())
        assertTrue(checkNotNull(outcome).locallyAccepted)
        assertNull(outcome?.actual)
        assertEquals(2, f.requests.size)
    }

    @Test fun reactionUnknownRetainsNativeFlightUntilLateRealResult() {
        val f = Native()
        val api = OfficialTdReactionNotifications(f.send)
        api.accept(TdApi.UpdateReactionNotificationSettings(TdApi.ReactionNotificationSettings(
            TdApi.ReactionNotificationSourceContacts(), TdApi.ReactionNotificationSourceNone(),
            TdApi.ReactionNotificationSourceAll(), 0, true)))
        val receipts = mutableListOf<SettingsSpecialWriteOutcome?>()
        val accepted = mutableListOf<Boolean>()
        api.save(api.state(), ReactionNotificationPatch(showSender = false), { true }) {
            receipts += SettingsSpecialMutationContext.receipt()?.outcome
            accepted += it.locallyAccepted
        }
        f.requests.single().failed()
        f.lock()
        f.requests.single().result(TdApi.Ok())
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.INDETERMINATE,
            SettingsSpecialWriteOutcome.ACCEPTED), receipts)
        assertEquals(listOf(false, true), accepted)
        assertEquals(1, f.requests.size)
    }

    @Test fun unlockAndRelockNeverReauthorizeAnOldAuthority() {
        val f = Native()
        val authority = f.gate.captureSpecialAuthority()
        assertTrue(authority())
        f.lock(); assertFalse(authority())
        f.witness = LocalLockWitness(2, false); assertFalse(authority())
        f.witness = LocalLockWitness(3, true); assertFalse(authority())
        f.witness = LocalLockWitness(4, false); assertFalse(authority())
    }

    @Test fun capturedReceiptDoesNotCaptureTheExactCommandMarker() {
        val command = TdApi.SetOption("disable_top_chats", TdApi.OptionValueBoolean(true))
        var captured: ((() -> Unit) -> Unit)? = null
        SettingsSpecialMutationContext.write(command, { _, result, _ -> result(TdApi.Ok()) }, {
            captured = SettingsSpecialMutationContext.capture()
        }, {})
        checkNotNull(captured).invoke {
            assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, SettingsSpecialMutationContext.receipt()?.outcome)
            assertFalse(SettingsSpecialMutationContext.marks(command))
        }
        assertNull(SettingsSpecialMutationContext.receipt())
    }

    @Test fun synchronousSuccessThenConsumerExceptionCannotBecomeUnknown() {
        val outcomes = mutableListOf<SettingsSpecialWriteOutcome?>()
        SettingsSpecialMutationContext.write(TdApi.SetOption("disable_top_chats", TdApi.OptionValueBoolean(true)),
            { _, result, _ -> result(TdApi.Ok()); error("After callback") }, {
                outcomes += SettingsSpecialMutationContext.receipt()?.outcome
                error("Consumer failure")
            }, { outcomes += SettingsSpecialMutationContext.receipt()?.outcome })
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.ACCEPTED), outcomes)
    }

    @Test fun queuedBeforeLockTerminalReleasesOnlyItsSlotWithoutPublishingOldData() {
        val f = Controller()
        f.open()
        f.controller.topPeers(f.snapshot.token, true, false)
        f.complete(SettingsSpecialWriteOutcome.ACCEPTED, SettingsResult(Unit))
        f.epoch++
        f.allowed = false
        val before = f.publications
        f.drain()
        assertEquals(before, f.publications)
        f.controller.close(f.snapshot.token)
        f.allowed = true
        f.open()
        assertFalse(f.snapshot.busy)
        assertEquals(1, f.writes)
        assertEquals(1L, f.snapshot.acceptedRevision)
    }

    @Test fun uncertainControllerFlightStaysOwnedUntilRealTerminal() {
        val f = Controller()
        f.open()
        f.controller.topPeers(f.snapshot.token, true, false)
        f.complete(SettingsSpecialWriteOutcome.INDETERMINATE, SettingsResult(error = SettingsError.NOT_READY))
        f.drain()
        f.controller.close(f.snapshot.token)
        f.open()
        assertTrue(f.snapshot.busy)
        f.complete(SettingsSpecialWriteOutcome.ACCEPTED, SettingsResult(Unit))
        f.drain()
        assertFalse(f.snapshot.busy)
        assertEquals(1, f.writes)
    }

    @Test fun retiredControllerCannotProjectIntoAnotherGeneration() {
        val f = Controller()
        f.open()
        f.controller.topPeers(f.snapshot.token, true, false)
        f.controller.deactivate()
        val before = f.publications
        f.complete(SettingsSpecialWriteOutcome.ACCEPTED, SettingsResult(Unit))
        f.drain()
        assertEquals(before, f.publications)
        assertEquals(1, f.writes)
    }

    private class Native {
        var witness = LocalLockWitness(0, false)
        val gate = TdUserInteractionGate().also { it.bindLocalWitness { witness } }
        val requests = mutableListOf<Request>()
        val send = gate.guard { command, result, failed -> requests += Request(command, result, failed) }
        fun lock() { witness = LocalLockWitness(witness.epoch + 1, true); gate.localWitnessChanged() }
    }
    private data class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private class Controller {
        var epoch = 0
        var allowed = true
        var publications = 0
        var writes = 0
        var snapshot = PrivacyCommonSnapshot()
        private val queue = ArrayDeque<() -> Unit>()
        private var done: ((SettingsResult<Unit>) -> Unit)? = null
        private val session = object : TdPrivacyCommonSession {
            override fun load(isCurrent: () -> Boolean, result: (SettingsResult<PrivacyCommonRead>) -> Unit) {
                check(isCurrent())
                result(SettingsResult(PrivacyCommonRead(PrivacyCommonData(topPeersEnabled = true, accountTtlDays = 365))))
            }
            override fun setTopPeers(expected: Boolean, desired: Boolean, isCurrent: () -> Boolean,
                result: (SettingsResult<Unit>) -> Unit,
            ) { check(isCurrent()); writes++; done = result }
            override fun setArchive(expected: PrivacyArchiveSettings, desired: PrivacyArchiveSettings,
                isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
            ) = error("Outside test scope")
            override fun setAccountTtl(expected: Int, desired: Int, isCurrent: () -> Boolean,
                result: (SettingsResult<Unit>) -> Unit,
            ) = error("Outside test scope")
        }
        val controller = PrivacyCommonController(17, session, { queue += it },
            publish = { snapshot = it; publications++ },
            terminal = SettingsSpecialMutationBoundary({ queue += it }, authority = {
                val captured = epoch
                val current: () -> Boolean = { allowed && epoch == captured }
                current
            }), isAllowed = { allowed })
        fun open() { controller.open { true }; drain() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun complete(outcome: SettingsSpecialWriteOutcome, result: SettingsResult<Unit>) {
            SettingsSpecialMutationContext.withReceipt(SettingsSpecialWriteReceipt(outcome)) { checkNotNull(done)(result) }
        }
    }
}
