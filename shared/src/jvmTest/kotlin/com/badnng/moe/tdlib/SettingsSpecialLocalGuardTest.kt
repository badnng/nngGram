package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.LoginEmailSuggestions
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Exact configured-account writes may traverse both transport gates. No native client is used. */
class SettingsSpecialLocalGuardTest {
    @Test fun nestedOrdinaryAndLocalPreserveRealSuccessAfterHotLock() {
        val f = Fixture()
        val results = mutableListOf<SettingsSpecialWriteOutcome?>()
        SettingsSpecialMutationContext.write(command(), f.nested, {
            results += SettingsSpecialMutationContext.receipt()?.outcome
            f.witness = LocalLockWitness(2, false)
            f.nested(TdApi.GetMe(), { fail("Old callback authorized a read") }, {})
        }, { results += SettingsSpecialMutationContext.receipt()?.outcome })
        f.lock()
        f.requests.single().result(TdApi.Ok())
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.ACCEPTED), results)
        assertEquals(1, f.requests.size)
    }

    @Test fun nestedGatesPreserveActualNativeErrorAfterHotLock() {
        val f = Fixture()
        var raw: TdApi.Object? = null
        var receipt: SettingsSpecialWriteReceipt? = null
        SettingsSpecialMutationContext.write(command(), f.nested, {
            raw = it; receipt = SettingsSpecialMutationContext.receipt()
        }, { fail("A real native error was suppressed") })
        f.lock()
        val error = TdApi.Error(400, "FAKE_CONFIGURED_WRITE_ERROR")
        f.requests.single().result(error)
        assertSame(error, raw)
        assertEquals(SettingsSpecialWriteOutcome.REJECTED, receipt?.outcome)
    }

    @Test fun markedLocalWriteAloneUsesOrdinaryMandatoryEmailAdmission() {
        val f = Fixture()
        f.requireEmail()
        var outcome: SettingsSpecialWriteOutcome? = null
        SettingsSpecialMutationContext.write(command(), f.local, { fail("Blocked marked write sent") }, {
            outcome = SettingsSpecialMutationContext.receipt()?.outcome
        })
        assertEquals(SettingsSpecialWriteOutcome.NOT_DISPATCHED, outcome)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun unmarkedLocalMandatoryTrafficRetainsItsOwnPolicyAndNoTerminalBypass() {
        val f = Fixture()
        f.requireEmail()
        var completed = 0
        var failed = 0
        f.local(TdApi.GetOption("FAKE_TEST_OPTION"), { completed++ }, { failed++ })
        assertEquals(1, f.requests.size)
        f.requests[0].result(TdApi.OptionValueEmpty())
        assertEquals(1, completed)
        f.local(TdApi.GetOption("FAKE_TEST_OPTION"), { completed++ }, { failed++ })
        f.lock()
        f.requests[1].result(TdApi.OptionValueEmpty())
        assertEquals(1, completed)
        assertEquals(1, failed)
        assertNull(SettingsSpecialMutationContext.receipt())
    }

    @Test fun nestedDeferredFailurePreservesInnerNotDispatchedProof() {
        val f = Fixture()
        val outer = f.gate.guard { request, result, failed ->
            f.lock()
            f.local(request, result, failed)
        }
        var outcome: SettingsSpecialWriteOutcome? = null
        SettingsSpecialMutationContext.write(command(), outer, { fail("No raw dispatch expected") }, {
            outcome = SettingsSpecialMutationContext.receipt()?.outcome
        })
        assertTrue(f.requests.isEmpty())
        assertEquals(SettingsSpecialWriteOutcome.NOT_DISPATCHED, outcome)
    }

    @Test fun enteringAnUncertainWrapperDoesNotCreateANotDispatchedProof() {
        val f = Fixture()
        val uncertain = f.gate.guard { _, _, failed -> failed() }
        var outcome: SettingsSpecialWriteOutcome? = null
        SettingsSpecialMutationContext.write(command(), uncertain, {}, {
            outcome = SettingsSpecialMutationContext.receipt()?.outcome
        })
        assertEquals(SettingsSpecialWriteOutcome.INDETERMINATE, outcome)
    }

    @Test fun uncertainNestedFailureStillAllowsLaterRealTerminal() {
        val f = Fixture()
        val outcomes = mutableListOf<SettingsSpecialWriteOutcome?>()
        SettingsSpecialMutationContext.write(command(), f.nested,
            { outcomes += SettingsSpecialMutationContext.receipt()?.outcome },
            { outcomes += SettingsSpecialMutationContext.receipt()?.outcome })
        f.requests.single().failed()
        f.lock()
        f.requests.single().result(TdApi.Ok())
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.INDETERMINATE,
            SettingsSpecialWriteOutcome.ACCEPTED), outcomes)
    }

    @Test fun receiptCannotImpersonateAnotherDispatchEvenWhenTheCommandObjectIsReused() {
        val f = Fixture()
        val sameCommand = command()
        f.lock()
        val outcomes = mutableListOf<SettingsSpecialWriteOutcome?>()
        SettingsSpecialMutationContext.write(sameCommand, f.local, {}, {
            outcomes += SettingsSpecialMutationContext.receipt()?.outcome
            SettingsSpecialMutationContext.write(sameCommand, { _, _, failed -> failed() }, {}, {
                outcomes += SettingsSpecialMutationContext.receipt()?.outcome
            })
        })
        assertEquals(listOf<SettingsSpecialWriteOutcome?>(SettingsSpecialWriteOutcome.NOT_DISPATCHED,
            SettingsSpecialWriteOutcome.INDETERMINATE), outcomes)
    }

    @Test fun exactMarkerCannotBeReusedForDifferentUnmarkedLocalTraffic() {
        val f = Fixture()
        val old = command()
        var replay: ((() -> Unit) -> Unit)? = null
        SettingsSpecialMutationContext.write(old, f.nested, {
            replay = SettingsSpecialMutationContext.capture()
        }, {})
        f.requests.single().result(TdApi.Ok())
        var failures = 0
        checkNotNull(replay).invoke {
            assertFalse(SettingsSpecialMutationContext.marks(old))
            f.local(TdApi.GetMe(), { fail("Unmarked local read should be suppressed after lock") }, { failures++ })
        }
        f.lock()
        f.requests[1].result(TdApi.Ok())
        assertEquals(1, failures)
    }

    private fun command() = TdApi.SetOption("FAKE_CONFIGURED_ACCOUNT_SETTING", TdApi.OptionValueBoolean(true))
    private class Fixture {
        var witness = LocalLockWitness(0, false)
        val gate = TdUserInteractionGate().also { it.bindLocalWitness { witness } }
        val requests = mutableListOf<Request>()
        private val raw: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
            { request, result, failed -> requests += Request(request, result, failed) }
        val local = gate.guardLocal(raw)
        val nested = gate.guard(local)
        fun lock() { witness = LocalLockWitness(witness.epoch + 1, true); gate.localWitnessChanged() }
        fun requireEmail() = gate.update(LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 1))
    }
    private data class Request(val command: TdApi.Function<*>, val result: (TdApi.Object) -> Unit, val failed: () -> Unit)
}
