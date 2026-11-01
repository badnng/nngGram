package com.badnng.moe

import androidx.compose.runtime.saveable.SaverScope
import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsProfileFormSaveStateTest {
    private val token = SettingsToken(5, 9)
    private val edits = listOf(
        SettingsProfileEdit(ProfileField.NAME, "First", "Last"),
        SettingsProfileEdit(ProfileField.BIO, "Bio"),
        SettingsProfileEdit(ProfileField.USERNAME, "username"),
    )
    private fun snapshot(started: Long = 0, completed: Long = 0, failed: Long = 0) = SettingsSnapshot(
        token = token, startedSaveRevision = mapOf(SettingsSection.PROFILE to started),
        completedSaveRevision = mapOf(SettingsSection.PROFILE to completed),
        failedSaveRevision = mapOf(SettingsSection.PROFILE to failed),
    )

    @Test fun serialFrozenBatchWaitsForEachMatchingReadback() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7)
        assertEquals(edits[0], state.next(snapshot(started = 4)))
        assertNull(state.next(snapshot(started = 4)))
        // A refresh consumed revisions 5 and 6 before this mutation began.
        assertFalse(state.reconcile(snapshot(started = 7, completed = 4), 7))
        assertFalse(state.reconcile(snapshot(started = 7, completed = 6), 7))
        assertNull(state.next(snapshot(started = 7)))
        assertFalse(state.reconcile(snapshot(started = 7, completed = 7), 7))
        assertEquals(1, state.acceptedCount)
        assertEquals(edits[1], state.next(snapshot(started = 7, completed = 7)))
        assertFalse(state.reconcile(snapshot(started = 8, completed = 8), 7))
        assertEquals(edits[2], state.next(snapshot(started = 8, completed = 8)))
        assertTrue(state.reconcile(snapshot(started = 9, completed = 9), 7))
        assertFalse(state.pending)
        assertEquals(3, state.acceptedCount)
    }

    @Test fun acceptedWriteWithFailedReadbackDoesNotResendOrAdvance() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot())
        val unknown = snapshot(started = 1).copy(errors = mapOf(SettingsSection.PROFILE to SettingsError.REQUEST_FAILED))
        repeat(3) {
            assertFalse(state.reconcile(unknown, 7))
            assertTrue(state.awaiting)
            assertNull(state.next(unknown))
        }
        assertFalse(state.failed)
        assertFalse(state.reconcile(snapshot(started = 1, completed = 1), 7))
        assertEquals(edits[1], state.next(snapshot(started = 1, completed = 1)))
    }

    @Test fun partialFailureStopsRemainingWritesAndKeepsAcceptedCount() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot())
        state.reconcile(snapshot(started = 1, completed = 1), 7)
        state.next(snapshot(started = 1, completed = 1))
        assertFalse(state.reconcile(snapshot(started = 2, completed = 1, failed = 2), 7))
        assertFalse(state.pending); assertTrue(state.failed); assertEquals(1, state.acceptedCount)
        assertNull(state.next(snapshot(started = 2, completed = 1, failed = 2)))
    }

    @Test fun deletedRetypedIdenticalContentRevisionIsNeverConsumedOrContinued() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot())
        assertFalse(state.reconcile(snapshot(started = 1, completed = 1), 9))
        assertFalse(state.pending); assertNull(state.next(snapshot(started = 1, completed = 1)))
    }

    @Test fun cancelAndNewBatchCannotConsumeTheOldCompletion() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot())
        state.cancel()
        state.begin(token, listOf(edits.last()), 10)
        assertEquals(edits.last(), state.next(snapshot(started = 1)))
        assertFalse(state.reconcile(snapshot(started = 1, completed = 1), 10))
        assertTrue(state.pending)
        assertTrue(state.reconcile(snapshot(started = 2, completed = 2), 10))
    }

    @Test fun tokenReplacementRetiresBatchWithoutTreatingItsCompletionAsAccepted() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot())
        assertFalse(state.reconcile(snapshot(started = 1, completed = 1).copy(token = token.copy(revision = 10)), 7))
        assertFalse(state.pending); assertFalse(state.failed); assertTrue(state.interrupted); assertEquals(0, state.acceptedCount)
    }

    @Test fun retryOnlyContainsFieldsStillDifferentFromNativeProfile() {
        val profile = SettingsProfile(1, "First", "Last", "old_name", "Old bio", "", bioLengthMax = 140)
        assertEquals(listOf(edits[1], edits[2]), settingsProfileFormEdits(profile, "First", "Last", "Bio", " @username "))
        assertTrue(settingsProfileFormEdits(profile, "First", "Last", "Old bio", "old_name").isEmpty())
    }

    @Test fun savedCategoryBucketRestoresTheExactOutstandingRevisionAndRemainingFields() {
        val state = SettingsProfileFormSaveState()
        state.begin(token, edits, 7); state.next(snapshot(started = 4))
        state.reconcile(snapshot(started = 7), 7)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(SettingsProfileFormSaveState.Saver) { scope.save(state) }
        val restored = assertNotNull(SettingsProfileFormSaveState.Saver.restore(assertNotNull(saved)))
        assertTrue(restored.awaiting); assertNull(restored.next(snapshot(started = 7)))
        assertFalse(restored.reconcile(snapshot(started = 7, completed = 7), 7))
        assertEquals(edits[1], restored.next(snapshot(started = 7, completed = 7)))
    }
}
