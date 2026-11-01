package com.badnng.moe.tdlib

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.LoginEmailSuggestions
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TdUserInteractionGateTest {
    @Test fun observerReplaysImmediatelyAndNativeRequiredFlipIsSynchronous() {
        val gate = TdUserInteractionGate()
        val values = mutableListOf<Boolean>()
        gate.observe { allowed -> values += allowed; assertEquals(allowed, gate.allowsOrdinary()) }
        assertEquals(listOf(true), values)
        gate.update(required(1, 1))
        assertEquals(listOf(true, false), values, "The gate must lock before update returns, independently of actor/Compose scheduling.")
        assertFalse(gate.allowsOrdinary())
        val replacement = mutableListOf<Boolean>()
        gate.observe(replacement::add)
        assertEquals(listOf(false), replacement)
    }

    @Test fun onlyTheActualRequiredEpochCanBeSatisfiedAndANewEpochRelocks() {
        val gate = TdUserInteractionGate()
        gate.update(required(1, 7))
        gate.satisfyRequired(6)
        assertFalse(gate.allowsOrdinary())
        gate.satisfyRequired(7)
        assertTrue(gate.allowsOrdinary())
        gate.update(required(2, 8))
        assertFalse(gate.allowsOrdinary())
        gate.satisfyRequired(7)
        assertFalse(gate.allowsOrdinary())
        gate.satisfyRequired(8)
        assertTrue(gate.allowsOrdinary())
    }

    @Test fun olderNativeRevisionCannotUnlockOrReplaceTheCurrentRequiredEpoch() {
        val gate = TdUserInteractionGate()
        gate.update(required(5, 4))
        gate.update(LoginEmailSuggestions(revision = 4, requiredPresent = false))
        assertFalse(gate.allowsOrdinary())
        gate.update(required(3, 3))
        gate.satisfyRequired(3)
        assertFalse(gate.allowsOrdinary())
        gate.satisfyRequired(4)
        assertTrue(gate.allowsOrdinary(), "The original latest epoch remains the only active native condition.")
    }

    @Test fun optionalChangesCannotUnlockRequiredAndActualRequiredRemovalDoes() {
        val gate = TdUserInteractionGate()
        gate.update(required(1, 1))
        gate.update(required(2, 1).copy(optionalPresent = true, optionalEpoch = 9))
        assertFalse(gate.allowsOrdinary())
        gate.update(required(3, 1).copy(optionalPresent = false, optionalEpoch = 9))
        assertFalse(gate.allowsOrdinary())
        gate.update(LoginEmailSuggestions(revision = 4, requiredPresent = false, requiredEpoch = 1))
        assertTrue(gate.allowsOrdinary())
    }

    @Test fun lockedBoundaryDeniesReadsWritesDownloadsAndAllowsOnlyExactResourceRelinquishment() {
        val gate = TdUserInteractionGate()
        gate.update(required(1, 1))
        assertFalse(gate.allowsNative(TdApi.GetMe()))
        assertFalse(gate.allowsNative(TdApi.GetChat(100)))
        assertFalse(gate.allowsNative(TdApi.DownloadFile()))
        assertFalse(gate.allowsNative(TdApi.DeleteMessages()))
        assertFalse(gate.allowsNative(TdApi.SetLoginEmailAddress("setup@example.invalid")),
            "The independent broker must own an email control path; the ordinary native facade cannot gain it.")
        assertTrue(gate.allowsNative(TdApi.CloseChat(100)))
        assertTrue(gate.allowsNative(TdApi.CancelDownloadFile(42, false)))
        assertTrue(gate.allowsNative(TdApi.CancelDownloadFile(42, true)))
    }

    @Test fun guardedRequestsCheckTheCurrentLockAtInvocationAndInvokeRejectionWithoutSending() {
        val gate = TdUserInteractionGate()
        val native = mutableListOf<TdApi.Function<*>>()
        var failures = 0
        var results = 0
        val guarded = gate.guard { command, done, _ -> native += command; done(TdApi.Ok()) }
        guarded(TdApi.GetMe(), { results++ }, { failures++ })
        gate.update(required(1, 1))
        guarded(TdApi.GetMe(), { results++ }, { failures++ })
        guarded(TdApi.CloseChat(100), { results++ }, { failures++ })
        assertEquals(2, native.size)
        assertEquals(2, results)
        assertEquals(1, failures)
        assertTrue(native.last() is TdApi.CloseChat)
    }

    @Test fun retirementRejectsEveryNewNativeRequestAndCannotBeRevivedByLateProofOrUpdates() {
        val gate = TdUserInteractionGate()
        val values = mutableListOf<Boolean>()
        gate.observe(values::add)
        gate.retire()
        assertEquals(listOf(true, false), values)
        assertFalse(gate.allowsNative(TdApi.GetMe()))
        assertFalse(gate.allowsNative(TdApi.CloseChat(100)))
        assertFalse(gate.allowsNative(TdApi.CancelDownloadFile(42, false)))
        gate.update(required(1, 1)); gate.satisfyRequired(1)
        gate.update(LoginEmailSuggestions(revision = 2))
        assertFalse(gate.allowsOrdinary())
        assertEquals(listOf(true, false), values)
        val replacement = mutableListOf<Boolean>()
        gate.observe(replacement::add)
        assertEquals(listOf(false), replacement)
    }

    @Test fun anAlreadySentCallbackStillReleasesItsOwnedLeaseAfterLockAndRetirement() {
        val gate = TdUserInteractionGate()
        var nativeCallback: ((TdApi.Object) -> Unit)? = null
        var sent = 0
        var cleaned = 0
        var followupRejected = 0
        val uiLease = ownedAttachmentLease { cleaned++ }
        val pending = assertNotNull(uiLease.retain())
        val guarded = gate.guard { _, done, _ -> sent++; nativeCallback = done }
        guarded(TdApi.GetMe(), { pending.release() }, { pending.release() })
        uiLease.release()
        gate.update(required(1, 1)); gate.retire()
        assertEquals(0, cleaned, "Locking a page cannot pretend that TDLib stopped consuming an accepted request's resource.")
        assertNotNull(nativeCallback).invoke(TdApi.Ok())
        assertEquals(1, cleaned)
        assertNotNull(nativeCallback).invoke(TdApi.Ok())
        assertEquals(1, cleaned, "Original callback cleanup is idempotent even if a synthetic boundary delivers twice.")
        guarded(TdApi.GetMe(), {}, { followupRejected++ })
        assertEquals(1, sent)
        assertEquals(1, followupRejected)
    }

    @Test fun aLateOrdinaryCallbackCannotStartItsNextNativeStepAfterRequiredArrives() {
        val gate = TdUserInteractionGate()
        val pending = mutableListOf<(TdApi.Object) -> Unit>()
        val native = mutableListOf<TdApi.Function<*>>()
        var rejected = 0
        val guarded = gate.guard { command, done, _ -> native += command; pending += done }
        guarded(TdApi.GetMe(), {
            guarded(TdApi.GetChat(100), {}, { rejected++ })
        }, { rejected++ })
        gate.update(required(1, 1))
        pending.single()(TdApi.Ok())
        assertEquals(1, native.size, "Each actual send in a callback chain needs a fresh gate decision.")
        assertEquals(1, rejected)
    }

    private fun required(revision: Long, epoch: Long) = LoginEmailSuggestions(
        revision = revision, requiredPresent = true, requiredEpoch = epoch)
}
