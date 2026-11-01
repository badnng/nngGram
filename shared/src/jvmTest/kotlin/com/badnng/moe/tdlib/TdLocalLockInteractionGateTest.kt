package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalLockWitness
import com.badnng.moe.settings.LoginEmailSuggestions
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses only synthetic requests and captured callbacks; no TDLib session or account is opened. */
class TdLocalLockInteractionGateTest {
    @Test
    fun localLockAndMandatoryEmailAreIndependentAndOnlyCleanupCrossesBoth() {
        val gate = TdUserInteractionGate()
        var witness = LocalLockWitness(7, false)
        gate.bindLocalWitness { witness }
        gate.update(LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 12))
        assertTrue(gate.allowsLocal())
        assertTrue(gate.emailRequiredBlocked())
        assertFalse(gate.allowsOrdinary())

        val sent = mutableListOf<TdApi.Function<*>>()
        var rejected = 0
        val special = gate.guardLocal { command, _, _ -> sent += command }
        special(TdApi.SetLoginEmailAddress("synthetic@example.invalid"), {}, { rejected++ })
        assertEquals(1, sent.size, "Mandatory email has its own path while the local gate is open.")

        witness = LocalLockWitness(8, true)
        gate.localWitnessChanged()
        assertFalse(gate.allowsLocal())
        assertTrue(gate.emailRequiredBlocked(), "Local protection must not rewrite the native email cause.")
        assertFalse(gate.allowsNative(TdApi.GetMe()))
        assertFalse(gate.allowsNative(TdApi.DownloadFile()))
        assertTrue(gate.allowsNative(TdApi.CloseChat(100)))
        assertTrue(gate.allowsNative(TdApi.CancelDownloadFile(42, false)))
        special(TdApi.SetLoginEmailAddress("synthetic@example.invalid"), {}, { rejected++ })
        special(TdApi.CloseChat(100), {}, { rejected++ })
        assertEquals(2, sent.size)
        assertEquals(1, rejected)
        var enteredStorage = false
        assertFalse(gate.dispatchStorage { enteredStorage = true; true })
        assertFalse(enteredStorage)

        witness = LocalLockWitness(9, false)
        gate.localWitnessChanged()
        assertTrue(gate.allowsLocal())
        assertFalse(gate.allowsOrdinary(), "Unlocking the device cannot satisfy a required login email.")
        gate.satisfyRequired(12)
        assertTrue(gate.allowsOrdinary())
    }

    @Test
    fun loginAndOrdinaryNativeResultsFromBeforeLockCannotPublishAfterLockOrUnlock() {
        val gate = TdUserInteractionGate()
        var witness = LocalLockWitness(30, false)
        gate.bindLocalWitness { witness }
        val nativeResults = mutableListOf<(TdApi.Object) -> Unit>()
        var accepted = 0
        var rejected = 0
        val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit = { _, result, _ ->
            nativeResults += result
        }
        val special = gate.guardLocal(send)
        val ordinary = gate.guard(send)
        special(TdApi.SetLoginEmailAddress("synthetic@example.invalid"), { accepted++ }, { rejected++ })
        ordinary(TdApi.GetMe(), { accepted++ }, { rejected++ })
        assertEquals(2, nativeResults.size)

        witness = LocalLockWitness(31, true)
        gate.localWitnessChanged()
        nativeResults[0](TdApi.Ok())
        assertEquals(0, accepted)
        assertEquals(1, rejected)
        witness = LocalLockWitness(32, false)
        gate.localWitnessChanged()
        nativeResults[1](TdApi.Ok())
        assertEquals(0, accepted, "An unlock does not revive a callback from an earlier lock epoch.")
        assertEquals(2, rejected)

        ordinary(TdApi.GetMe(), { accepted++ }, { rejected++ })
        nativeResults.last()(TdApi.Ok())
        assertEquals(1, accepted)
    }

    @Test
    fun mandatoryEmailAndRetirementRejectOldOrdinaryResultsButStillReleaseTheirOwner() {
        val gate = TdUserInteractionGate()
        gate.bindLocalWitness { LocalLockWitness(3, false) }
        val pending = mutableListOf<(TdApi.Object) -> Unit>()
        var published = 0
        var released = 0
        val ordinary = gate.guard { _, result, _ -> pending += result }
        ordinary(TdApi.GetMe(), { published++ }, { released++ })
        gate.update(LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 1))
        pending[0](TdApi.Ok())
        assertEquals(0, published)
        assertEquals(1, released)

        gate.satisfyRequired(1)
        ordinary(TdApi.GetMe(), { published++ }, { released++ })
        gate.retire()
        pending[1](TdApi.Ok())
        assertEquals(0, published)
        assertEquals(2, released)
        ordinary(TdApi.CloseChat(100), { published++ }, { released++ })
        assertEquals(2, pending.size, "Retired gates do not send new cleanup or ordinary requests.")
        assertEquals(3, released)
    }

    @Test
    fun realSendReceiptRetainsItsOriginalWitnessAcrossLocalLockButCannotSendAnotherPart() {
        val gate = TdUserInteractionGate()
        var witness = LocalLockWitness(20, false)
        gate.bindLocalWitness { witness }
        val native = mutableListOf<TdApi.Function<*>>()
        val callbacks = mutableListOf<(TdApi.Object) -> Unit>()
        var accepted = 0
        var rejected = 0
        val guarded = gate.guard { request, result, _ -> native += request; callbacks += result }
        guarded(TdApi.SendMessage(), {
            accepted++
            assertTrue(gate.callbackIsTerminal())
            assertEquals(LocalLockWitness(20, false), gate.callbackWitness())
            assertFalse(gate.callbackContextIsCurrent())
            guarded(TdApi.SendMessage(), {}, { rejected++ })
        }, { rejected++ })
        witness = LocalLockWitness(21, true)
        gate.localWitnessChanged()
        callbacks.single()(TdApi.Ok())
        assertEquals(1, accepted, "A true native mutation receipt remains attributable to the admitted request.")
        assertEquals(1, rejected)
        assertEquals(1, native.size, "The old batch callback cannot dispatch its next part.")
        assertEquals(null, gate.callbackWitness())
        assertFalse(gate.callbackIsTerminal())
        witness = LocalLockWitness(22, false)
        gate.localWitnessChanged()
        assertEquals(1, native.size, "Unlock itself must not replay the next part.")
    }

    @Test
    fun mandatoryEmailChangeKeepsAlreadySubmittedSendReceiptButRejectsOldReadAndFollowup() {
        val gate = TdUserInteractionGate()
        gate.bindLocalWitness { LocalLockWitness(4, false) }
        val native = mutableListOf<TdApi.Function<*>>()
        val callbacks = mutableListOf<(TdApi.Object) -> Unit>()
        var accepted = 0
        var rejected = 0
        val guarded = gate.guard { request, result, _ -> native += request; callbacks += result }
        guarded(TdApi.SendMessage(), {
            assertTrue(gate.callbackIsTerminal())
            assertFalse(gate.callbackContextIsCurrent())
            accepted++
            guarded(TdApi.SendMessage(), {}, { rejected++ })
        }, { rejected++ })
        guarded(TdApi.GetMe(), { accepted++ }, { rejected++ })
        gate.update(LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 5))
        callbacks[0](TdApi.Ok())
        callbacks[1](TdApi.Ok())
        assertEquals(1, accepted)
        assertEquals(2, rejected)
        assertEquals(2, native.size)
    }

    @Test
    fun profileMutationReceiptsRetainRealResultsAcrossBothGatesButTransportFailureIsNotAReceipt() {
        for (mandatoryGate in listOf(false, true)) {
            for (response in listOf<TdApi.Object>(TdApi.Ok(), TdApi.Error(400, "synthetic failure"))) {
                val gate = TdUserInteractionGate()
                var witness = LocalLockWitness(40, false)
                gate.bindLocalWitness { witness }
                val callbacks = mutableListOf<(TdApi.Object) -> Unit>()
                val requests = mutableListOf<TdApi.Function<*>>()
                var observed: TdApi.Object? = null
                var failed = 0
                val guarded = gate.guard { request, result, _ ->
                    requests += request
                    callbacks += result
                }
                gate.withSettingsMutation {
                    guarded(TdApi.SetName("synthetic", "profile"), {
                        assertTrue(gate.callbackIsTerminal())
                        assertEquals(LocalLockWitness(40, false), gate.callbackWitness())
                        assertFalse(gate.callbackContextIsCurrent())
                        observed = it
                    }, { failed++ })
                }
                if (mandatoryGate) {
                    gate.update(LoginEmailSuggestions(revision = 1, requiredPresent = true, requiredEpoch = 8))
                } else {
                    witness = LocalLockWitness(41, true)
                    gate.localWitnessChanged()
                }
                callbacks.single()(response)
                assertTrue(observed === response, "Only the real native response settles an accepted mutation.")
                assertEquals(0, failed)
                guarded(TdApi.GetMe(), {}, { failed++ })
                assertEquals(1, failed, "The obsolete owner cannot start a readback.")
                assertEquals(1, requests.size)

                // A transport failure has no native Ok/Error and must use the unknown-outcome path.
                val freshGate = TdUserInteractionGate()
                freshGate.bindLocalWitness { LocalLockWitness(50, false) }
                var rawFailure: (() -> Unit)? = null
                var fakeReceipt = false
                val failing = freshGate.guard { _, _, onFailure -> rawFailure = onFailure }
                freshGate.withSettingsMutation {
                    failing(TdApi.SetName("synthetic", "profile"), { fakeReceipt = true }, {
                        assertTrue(freshGate.callbackWriteIndeterminate())
                        failed++
                    })
                }
                rawFailure!!()
                assertFalse(fakeReceipt)
                assertEquals(2, failed)
            }
        }
    }

    @Test
    fun profileCommandOutsideTheSettingsMutationOwnerDoesNotGainTheTerminalException() {
        val gate = TdUserInteractionGate()
        var witness = LocalLockWitness(70, false)
        gate.bindLocalWitness { witness }
        var result: ((TdApi.Object) -> Unit)? = null
        var accepted = 0
        var rejected = 0
        val guarded = gate.guard { _, callback, _ -> result = callback }
        guarded(TdApi.SetName("synthetic", "other owner"), { accepted++ }, { rejected++ })
        witness = LocalLockWitness(71, true)
        gate.localWitnessChanged()
        result!!(TdApi.Ok())
        assertEquals(0, accepted)
        assertEquals(1, rejected)
    }
}
