package com.badnng.moe.tdlib

import com.badnng.moe.settings.LoginEmailEntryKind
import com.badnng.moe.settings.LoginEmailSuggestions
import org.drinkless.tdlib.TdApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdLoginEmailSuggestionsTest {
    @Test fun anEarlyRequiredUpdateLocksBeforeAnyObserverAndReplaysItsActualIdentity() {
        val gate = TdUserInteractionGate()
        val source = TdLoginEmailSuggestions(gate)
        source.accept(delta(added = arrayOf(required())))
        assertFalse(gate.allowsOrdinary(), "Client.create may synchronously publish required before session/controller attachment.")
        val replay = mutableListOf<LoginEmailSuggestions>()
        source.observe(replay::add)
        assertEquals(listOf(source.snapshot()), replay)
        assertTrue(replay.single().requiredPresent)
        assertEquals(1L, replay.single().requiredEpoch)
        assertTrue(replay.single().contains(LoginEmailEntryKind.MANDATORY, 1))
        assertFalse(replay.single().contains(LoginEmailEntryKind.OPTIONAL, 1))
    }

    @Test fun optionalAndRequiredCoexistAndRemovingOptionalNeverUnlocksRequired() {
        val gate = TdUserInteractionGate()
        val source = TdLoginEmailSuggestions(gate)
        val observed = mutableListOf<LoginEmailSuggestions>()
        source.observe(observed::add)
        source.accept(delta(added = arrayOf(optional(), required())))
        val combined = source.snapshot()
        assertTrue(combined.optionalPresent); assertTrue(combined.requiredPresent)
        source.accept(delta(removed = arrayOf(optional())))
        val after = source.snapshot()
        assertFalse(after.optionalPresent); assertTrue(after.requiredPresent)
        assertEquals(combined.requiredEpoch, after.requiredEpoch)
        assertFalse(gate.allowsOrdinary())
        assertEquals(listOf(0L, 1L, 2L), observed.map { it.revision })
    }

    @Test fun duplicateAddsAndAbsentRemovalsCannotAdvanceRevisionOrChallengeEpochs() {
        val source = TdLoginEmailSuggestions()
        val observed = mutableListOf<LoginEmailSuggestions>()
        source.observe(observed::add)
        source.accept(delta(removed = arrayOf(optional(), required())))
        assertEquals(1, observed.size)
        source.accept(delta(added = arrayOf(required(), required(), optional(), optional())))
        val first = source.snapshot()
        source.accept(delta(added = arrayOf(required(), optional())))
        assertEquals(first, source.snapshot())
        assertEquals(2, observed.size)
        assertEquals(1L, first.requiredEpoch); assertEquals(1L, first.optionalEpoch)
    }

    @Test fun removalAndAdditionInOneDeltaStartsANewEpochAndRelocksSatisfiedRequired() {
        val gate = TdUserInteractionGate()
        val source = TdLoginEmailSuggestions(gate)
        source.accept(delta(added = arrayOf(optional(), required())))
        gate.satisfyRequired(source.snapshot().requiredEpoch)
        assertTrue(gate.allowsOrdinary())
        source.accept(delta(added = arrayOf(required()), removed = arrayOf(required())))
        val next = source.snapshot()
        assertEquals(2L, next.requiredEpoch)
        assertEquals(1L, next.optionalEpoch, "An independent optional identity cannot acquire a new epoch from required changes.")
        assertEquals(2L, next.revision)
        assertTrue(next.requiredPresent)
        assertFalse(gate.allowsOrdinary(), "A proof for the former required epoch cannot satisfy its replacement.")
    }

    @Test fun theTwoIdentitiesHaveIndependentEpochsAcrossSeparateRemoveAndAddUpdates() {
        val source = TdLoginEmailSuggestions()
        source.accept(delta(added = arrayOf(optional(), required())))
        source.accept(delta(removed = arrayOf(optional())))
        source.accept(delta(added = arrayOf(optional())))
        assertEquals(2L, source.snapshot().optionalEpoch)
        assertEquals(1L, source.snapshot().requiredEpoch)
        source.accept(delta(removed = arrayOf(required())))
        assertTrue(source.snapshot().optionalPresent)
        assertFalse(source.snapshot().requiredPresent)
    }

    @Test fun unrelatedNativeUpdatesAndOtherSuggestionKindsDoNotCreateLoginEmailFacts() {
        val source = TdLoginEmailSuggestions()
        source.accept(TdApi.Ok())
        source.accept(delta(added = arrayOf(TdApi.SuggestedActionSetBirthdate(), TdApi.SuggestedActionSetProfilePhoto())))
        source.accept(TdApi.UpdateSuggestedActions()) // Java native fields may be null.
        assertEquals(LoginEmailSuggestions(), source.snapshot())
    }

    @Test fun detachingTheObserverDoesNotDropUpdatesAndAReplacementGetsOnlyTheLatestReplay() {
        val source = TdLoginEmailSuggestions()
        val old = mutableListOf<LoginEmailSuggestions>()
        source.observe(old::add)
        source.accept(delta(added = arrayOf(optional())))
        source.observe(null)
        source.accept(delta(added = arrayOf(required()), removed = arrayOf(optional())))
        assertEquals(2, old.size)
        val replacement = mutableListOf<LoginEmailSuggestions>()
        source.observe(replacement::add)
        assertEquals(listOf(source.snapshot()), replacement)
        assertTrue(replacement.single().requiredPresent)
        assertFalse(replacement.single().optionalPresent)
    }

    @Test fun aNewSessionStartsWithoutPersistedSuggestionsOrOldEpochAuthority() {
        val old = TdLoginEmailSuggestions()
        old.accept(delta(added = arrayOf(required())))
        val current = TdLoginEmailSuggestions()
        assertEquals(LoginEmailSuggestions(), current.snapshot())
        assertFalse(current.snapshot().contains(LoginEmailEntryKind.MANDATORY, old.snapshot().requiredEpoch))
    }

    private fun optional() = TdApi.SuggestedActionSetLoginEmailAddress(true)
    private fun required() = TdApi.SuggestedActionSetLoginEmailAddress(false)
    private fun delta(added: Array<TdApi.SuggestedAction> = emptyArray(), removed: Array<TdApi.SuggestedAction> = emptyArray()) =
        TdApi.UpdateSuggestedActions(added, removed)
}
