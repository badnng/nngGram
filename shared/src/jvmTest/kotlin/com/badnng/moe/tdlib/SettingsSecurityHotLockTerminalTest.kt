package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Synthetic dispatch/actor boundaries only. Never opens TDLib, files, an account, or a device. */
class SettingsSecurityHotLockTerminalTest {
    @Test fun trueNativeSuccessAndErrorSettleTheirOriginalOwnerWithoutReopeningItsForm() {
        for (response in listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(403, "synthetic forbidden"))) {
            val f = Fixture()
            f.openWebsites()
            var forms = 0
            f.controller.disconnectAll(f.state.token) { forms++ }
            val pending = f.last
            f.lock()
            val closed = f.state
            pending.reply(response)
            f.flush()
            assertEquals(if (response is TdApi.Ok) SettingsSpecialWriteOutcome.ACCEPTED else SettingsSpecialWriteOutcome.REJECTED,
                f.controller.lastMutationReceipt?.outcome)
            assertEquals(if (response is TdApi.Error) SettingsError.FORBIDDEN else null, f.controller.lastMutationReceipt?.error)
            assertEquals(closed, f.state)
            assertEquals(0, forms)
            assertEquals(1, f.calls.count { it.command is TdApi.DisconnectAllWebsites })
            f.unlock()
            f.openWebsites()
            assertFalse(f.state.busy)
            assertEquals(1, f.calls.count { it.command is TdApi.DisconnectAllWebsites })
        }
    }

    @Test fun aRealCallbackQueuedBeforeLockStillSettlesButCannotStartItsReadback() {
        val f = Fixture()
        f.openWebsites()
        var forms = 0
        f.controller.disconnectAll(f.state.token) { forms++ }
        f.last.reply(TdApi.Ok()) // Terminal enqueue has not run.
        f.lock()
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertEquals(0, forms)
        assertNull(f.state.section)
        assertEquals(2, f.calls.size, "No old GetConnectedWebsites readback")
    }

    @Test fun uncertainSubmissionRetainsItsPhysicalOwnerUntilTheLaterRealReceipt() {
        val f = Fixture()
        f.openWebsites()
        val forms = mutableListOf<SettingsResult<Unit>>()
        f.controller.disconnectAll(f.state.token, forms::add)
        val pending = f.last
        pending.failed()
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.INDETERMINATE, f.controller.lastMutationReceipt?.outcome)
        assertEquals(SettingsError.RESULT_UNKNOWN, forms.single().error)
        assertNull(forms.single().value)
        f.lock()
        f.unlock()
        f.openWebsites()
        assertEquals(SettingsError.RESULT_UNKNOWN, f.state.error)
        var refusal: SettingsResult<Unit>? = null
        f.controller.disconnectAll(f.state.token) { refusal = it }
        assertEquals(SettingsError.RESULT_UNKNOWN, refusal?.error)
        assertEquals(1, f.calls.count { it.command is TdApi.DisconnectAllWebsites })
        pending.reply(TdApi.Ok())
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertEquals(1, f.calls.count { it.command is TdApi.DisconnectAllWebsites })
    }

    @Test fun anOldPreflightReadCannotDispatchTheConfirmedWebsiteWriteAfterLock() {
        val f = Fixture()
        f.openWebsites()
        f.controller.disconnect(f.state.token, website(), true) { fail("Retired form") }
        val preflight = f.last
        assertIs<TdApi.GetConnectedWebsites>(preflight.command)
        f.lock()
        preflight.reply(websites())
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.NOT_DISPATCHED, f.controller.lastMutationReceipt?.outcome)
        assertTrue(f.calls.none { it.command is TdApi.DisconnectWebsite || it.command is TdApi.SetMessageSenderBlockList })
    }

    @Test fun acceptedWebsiteDisconnectCannotContinueWithBotBlockingAfterLock() {
        val f = Fixture()
        f.openWebsites()
        f.controller.disconnect(f.state.token, website(), true) { fail("Retired form") }
        f.last.reply(websites())
        val physical = f.last
        assertIs<TdApi.DisconnectWebsite>(physical.command)
        f.lock()
        physical.reply(TdApi.Ok())
        f.flush()
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertTrue(f.calls.none { it.command is TdApi.GetUser || it.command is TdApi.SetMessageSenderBlockList })
    }

    @Test fun unknownRevokesTheNativeContinuationBeforeTheActorProcessesItsNotification() {
        val f = Fixture()
        f.openWebsites()
        val forms = mutableListOf<SettingsResult<SettingsWebsiteDisconnectOutcome>>()
        f.controller.disconnect(f.state.token, website(), true, forms::add)
        f.last.reply(websites())
        val physical = f.last
        physical.failed()
        physical.reply(TdApi.Ok()) // The unknown terminal action is still queued.
        f.flush()
        assertEquals(SettingsError.RESULT_UNKNOWN, forms.single().error)
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertTrue(f.calls.none { it.command is TdApi.GetUser || it.command is TdApi.SetMessageSenderBlockList })
    }

    @Test fun deactivationAndASecondLockCannotPublishOrAutomaticallyRepeatTheirWrite() {
        val f = Fixture()
        f.openWebsites()
        f.controller.disconnectAll(f.state.token) { fail("Retired form") }
        val pending = f.last
        f.controller.deactivate()
        val old = f.state
        f.lock()
        f.unlock()
        f.lock()
        pending.reply(TdApi.Ok())
        f.flush()
        assertEquals(old, f.state)
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, f.controller.lastMutationReceipt?.outcome)
        assertEquals(1, f.calls.count { it.command is TdApi.DisconnectAllWebsites })
    }

    @Test fun passwordRetirementClearsSecretContinuationsAndStopsVerificationAfterTheRealWrite() {
        val calls = mutableListOf<Pending>()
        var current = true
        val helper = OfficialTdSettingsPassword({ command, reply, failed -> calls += Pending(command, reply, failed) },
            { current }, specialWrites = true)
        val results = mutableListOf<Pair<SettingsSpecialWriteReceipt?, SettingsResult<SettingsPasswordOutcome>>>()
        helper.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD,
            currentPassword = "synthetic old", newPassword = "synthetic new", email = "synthetic@example.test")) {
            results += SettingsSpecialMutationContext.receipt() to it
        }
        assertEquals(1, continuationCount(helper))
        current = false
        helper.retire()
        assertEquals(0, continuationCount(helper))
        calls.single().reply(password())
        assertEquals(1, calls.size, "No GetRecoveryEmailAddress with the retired password")
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, results.single().first?.outcome)
        assertNull(results.single().second.value)
    }

    @Test fun passwordUnknownDoesNotSwallowTheLaterRealNativeReceipt() {
        val calls = mutableListOf<Pending>()
        val helper = OfficialTdSettingsPassword({ command, reply, failed -> calls += Pending(command, reply, failed) },
            { true }, specialWrites = true)
        val receipts = mutableListOf<SettingsSpecialWriteOutcome?>()
        helper.execute(SettingsPasswordCommand(SettingsPasswordOperation.SET_PASSWORD, newPassword = "synthetic")) {
            receipts += SettingsSpecialMutationContext.receipt()?.outcome
        }
        calls.single().failed()
        assertEquals(0, continuationCount(helper))
        calls.single().reply(password())
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.INDETERMINATE,
            SettingsSpecialWriteOutcome.ACCEPTED), receipts)
        assertEquals(1, calls.size)
    }

    @Test fun configuredLoginEmailUnknownKeepsTheSharedFlightAndRetirementDropsSecretSteps() {
        val calls = mutableListOf<Pending>()
        val broker = OfficialTdLoginEmail({ command, reply, failed -> calls += Pending(command, reply, failed) })
        val owner = SettingsSecurityToken(7, 1)
        val receipts = mutableListOf<SettingsSpecialWriteOutcome?>()
        broker.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "synthetic@example.test"), { true }) { receipts += SettingsSpecialMutationContext.receipt()?.outcome }
        calls.last().reply(password(login = "masked***"))
        val write = calls.last()
        assertIs<TdApi.SetLoginEmailAddress>(write.command)
        write.failed()
        broker.retireConfigured(owner)
        assertEquals(0, configuredContinuationCount(broker))
        val newer = SettingsSecurityToken(7, 2)
        var blocked: SettingsResult<SettingsPasswordOutcome>? = null
        broker.configured(newer, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "other@example.test"), { true }) { blocked = it }
        assertEquals(SettingsError.NOT_READY, blocked?.error)
        assertEquals(2, calls.size)
        write.reply(TdApi.EmailAddressAuthenticationCodeInfo("masked***", 4))
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.INDETERMINATE,
            SettingsSpecialWriteOutcome.ACCEPTED), receipts)
        broker.configured(newer, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "other@example.test"), { true }) { }
        assertIs<TdApi.GetPasswordState>(calls.last().command)
        assertEquals(3, calls.size, "Only explicit new-owner preflight starts after the real terminal")
    }

    @Test fun configuredEmailRealTerminalSurvivesTheSharedLocalTransportAndOrdinaryPolicyWrappers() {
        var witness = LocalLockWitness(1, false)
        val gate = TdUserInteractionGate().apply { bindLocalWitness { witness } }
        val calls = mutableListOf<Pending>()
        val broker = OfficialTdLoginEmail(gate.guardLocal { command, reply, failed -> calls += Pending(command, reply, failed) },
            gate = gate)
        val owner = SettingsSecurityToken(7, 1)
        val results = mutableListOf<Pair<SettingsSpecialWriteReceipt?, SettingsResult<SettingsPasswordOutcome>>>()
        broker.configured(owner, SettingsPasswordCommand(SettingsPasswordOperation.SET_LOGIN_EMAIL,
            email = "synthetic@example.test"), { !witness.blocked }) { results += SettingsSpecialMutationContext.receipt() to it }
        calls.last().reply(password(login = "masked***"))
        val write = calls.last()
        witness = LocalLockWitness(2, true)
        gate.localWitnessChanged()
        broker.retireConfigured(owner)
        write.reply(TdApi.EmailAddressAuthenticationCodeInfo("masked***", 4))
        assertEquals(SettingsSpecialWriteOutcome.ACCEPTED, results.single().first?.outcome)
        assertNull(results.single().second.value)
        assertEquals(2, calls.size)
    }

    private class Fixture {
        var witness = LocalLockWitness(1, false)
        val gate = TdUserInteractionGate().apply { bindLocalWitness { witness } }
        val calls = mutableListOf<Pending>()
        private val ordinary = ArrayDeque<() -> Unit>()
        private val terminal = ArrayDeque<() -> Unit>()
        var state = SettingsSecuritySnapshot()
        val session = OfficialTdSettingsSession(send = gate.guard { command, reply, failed -> calls += Pending(command, reply, failed) })
        private val boundary = SettingsSpecialMutationBoundary(terminal::addLast, authority = {
            val captured = witness;
            { witness == captured && !witness.blocked }
        })
        val controller = SettingsSecurityController(7, session, { work ->
            val captured = gate.callbackWitness() ?: witness
            ordinary.addLast { if (witness == captured && !witness.blocked) work() }
        }, { state = it }, boundary)
        val last get() = calls.last()
        fun openWebsites() {
            controller.open(SettingsSecuritySection.WEBSITES)
            last.reply(websites())
            flush()
        }
        fun lock() {
            witness = LocalLockWitness(witness.epoch + 1, true)
            controller.close(state.token)
            gate.localWitnessChanged()
        }
        fun unlock() { witness = LocalLockWitness(witness.epoch + 1, false); gate.localWitnessChanged() }
        fun flush() {
            while (ordinary.isNotEmpty() || terminal.isNotEmpty()) {
                while (ordinary.isNotEmpty()) ordinary.removeFirst().invoke()
                while (terminal.isNotEmpty()) terminal.removeFirst().invoke()
            }
        }
    }
    private data class Pending(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)
    private companion object {
        fun password(login: String = "") = TdApi.PasswordState(true, "synthetic hint", false, false, null, login, 0)
        fun website() = SettingsWebsite(77, "synthetic.test", 42, "", "", 1, 2, "", "")
        fun websites() = TdApi.ConnectedWebsites(arrayOf(TdApi.ConnectedWebsite(77, "synthetic.test", 42, "", "", 1, 2, "", "")))
        fun continuationCount(helper: OfficialTdSettingsPassword): Int = (helper.javaClass.getDeclaredField("continuations")
            .apply { isAccessible = true }.get(helper) as Set<*>).size
        fun configuredContinuationCount(broker: OfficialTdLoginEmail): Int {
            val record = broker.javaClass.getDeclaredField("configuredFlight").apply { isAccessible = true }.get(broker) ?: return 0
            return (record.javaClass.getDeclaredField("continuations").apply { isAccessible = true }.get(record) as Set<*>).size
        }
    }
}
