package com.badnng.moe

import com.badnng.moe.chat.AutoMediaOverride
import com.badnng.moe.chat.AutoMediaPolicy
import com.badnng.moe.chat.AutoMediaSettings
import com.badnng.moe.chat.AutoMediaSource
import com.badnng.moe.chat.AutoMediaSourceRules
import com.badnng.moe.chat.AutoMediaType
import com.badnng.moe.chat.MIB
import com.badnng.moe.chat.autoMediaSizeByIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsAutoMediaDraftTest {
    @Test fun openingAndCancellingChildrenRetainsExactBytesAndTheParentDraft() {
        val rules = AutoMediaSourceRules.defaults(AutoMediaSource.USER)
            .withLimit(AutoMediaType.PHOTO, 8L * MIB + 17)
            .withLimit(AutoMediaType.FILE, 3L * MIB)
        val settings = settings(AutoMediaPolicy().withSource(AutoMediaSource.USER, rules))
        val draft = AutoMediaEditorDraft(settings)
        draft.edit(AutoMediaSource.USER)
        val child = assertNotNull(draft.child)
        assertEquals(8L * MIB + 17, child.downloadLimit)
        assertEquals(rules, child.rules(), "Opening a shared slider must not round or normalize persisted values.")
        child.size(true, 99)
        child.toggle(AutoMediaType.VIDEO, false)
        draft.cancelChild()
        assertEquals(settings.policy, draft.policy)
        assertFalse(draft.changed)
        assertEquals("最多 ${8L * MIB + 17} 字节", autoMediaLimitText(8L * MIB + 17))
    }

    @Test fun touchedGroupsUseOneOfficialLimitAndRetainUnsupportedAndUntouchedTypes() {
        val rules = AutoMediaSourceRules.defaults(AutoMediaSource.CHANNEL)
            .withLimit(AutoMediaType.VIDEO, 50L * MIB + 3)
            .withLimit(AutoMediaType.VOICE, 7L * MIB + 1)
            .withLimit(AutoMediaType.MUSIC, 9L * MIB + 2)
            .withLimit(AutoMediaType.VIDEO_NOTE, 80L * MIB + 3)
        val child = AutoMediaSourceDraft(AutoMediaSource.CHANNEL, rules)
        child.toggle(AutoMediaType.FILE, true)
        child.size(true, 99)
        val result = child.rules()
        assertEquals(4_000L * MIB, result.limit(AutoMediaType.PHOTO))
        assertEquals(4_000L * MIB, result.limit(AutoMediaType.FILE))
        (setOf(AutoMediaType.VIDEO, AutoMediaType.GIF, AutoMediaType.VOICE, AutoMediaType.MUSIC, AutoMediaType.VIDEO_NOTE))
            .forEach { assertEquals(rules.limit(it), result.limit(it), "$it must retain its original exact bytes.") }
        child.size(false, 9)
        child.toggle(AutoMediaType.GIF, false)
        assertEquals(10L * MIB, child.rules().limit(AutoMediaType.VIDEO))
        assertEquals(0L, child.rules().limit(AutoMediaType.GIF))
    }

    @Test fun fullyDisabledGroupsUseOnlyTheEditingFallbackAndSourceToggleRestoresDefaults() {
        val disabled = AutoMediaSourceRules.disabled()
        val child = AutoMediaSourceDraft(AutoMediaSource.GROUP, disabled)
        assertEquals(10L * MIB, child.downloadLimit)
        assertEquals(50L * MIB, child.playLimit)
        assertEquals(disabled, child.rules())
        val policy = AutoMediaPolicy(peers = mapOf(717L to AutoMediaOverride.DENY))
        val draft = AutoMediaEditorDraft(settings(policy))
        draft.source(AutoMediaSource.CHANNEL, false)
        assertEquals(disabled, draft.policy.rules(AutoMediaSource.CHANNEL))
        assertEquals(policy.rules(AutoMediaSource.USER), draft.policy.rules(AutoMediaSource.USER))
        assertEquals(policy.rules(AutoMediaSource.GROUP), draft.policy.rules(AutoMediaSource.GROUP))
        draft.source(AutoMediaSource.CHANNEL, true)
        assertEquals(AutoMediaSourceRules.defaults(AutoMediaSource.CHANNEL), draft.policy.rules(AutoMediaSource.CHANNEL))
        assertEquals(policy.peers, draft.policy.peers)
    }

    @Test fun savesWaitForPublicationAndFailureRetainsTheDraftForExplicitRetry() {
        val initial = settings()
        val draft = AutoMediaEditorDraft(initial)
        draft.source(AutoMediaSource.GROUP, false)
        val requested = assertNotNull(draft.begin())
        assertTrue(draft.pending)
        assertNull(draft.begin(), "A pending request must not be issued twice.")
        draft.reportAdmission(requested.identity, true)
        assertTrue(draft.pending, "Admission is not save success.")
        assertFalse(draft.observe(initial.copy(saving = true)))
        assertTrue(draft.pending)
        val previousError = initial.copy(error = true)
        assertFalse(draft.observe(previousError))
        assertTrue(draft.pending, "A same-revision error snapshot is not this request's terminal result.")
        draft.reportCompletion(requested.identity, false)
        assertFalse(draft.pending)
        assertTrue(draft.failed)
        assertEquals(requested.policy, draft.policy)
        val retry = assertNotNull(draft.begin())
        assertEquals(requested.policy, retry.policy)
        assertTrue(retry.identity > requested.identity)
        draft.reportAdmission(requested.identity, false)
        assertTrue(draft.pending, "A late rejection from the prior request cannot release the retry.")
        draft.reportAdmission(retry.identity, true)
        assertFalse(draft.observe(previousError))
        assertTrue(draft.pending, "Admission and the previous same-revision error still do not settle this retry.")
        draft.reportCompletion(retry.identity, true)
        assertTrue(draft.pending, "Successful completion still waits for the matching published revision.")
        assertTrue(draft.observe(initial.copy(revision = initial.revision + 1, policy = retry.policy)))
        assertFalse(draft.pending)
    }

    @Test fun changedRevisionsAndOtherAccountResponsesCannotConsumeTheDraft() {
        val initial = settings()
        val draft = AutoMediaEditorDraft(initial)
        draft.source(AutoMediaSource.USER, false)
        val requested = assertNotNull(draft.begin())
        assertFalse(draft.observe(initial.copy(accountUserId = 22, revision = 99, policy = requested.policy)))
        assertTrue(draft.pending)
        val changed = initial.copy(revision = 12, policy = initial.policy.sourceEnabled(AutoMediaSource.CHANNEL, false))
        assertFalse(draft.observe(changed))
        assertTrue(draft.conflict)
        assertEquals(requested.policy, draft.policy)
        assertNull(draft.begin())
        draft.reload(changed)
        assertEquals(changed.revision, draft.revision)
        assertEquals(changed.policy, draft.policy)
        assertFalse(draft.conflict)
        assertFalse(draft.changed)
        val retry = assertNotNull(draft.begin())
        assertTrue(retry.identity > requested.identity)
        draft.reportCompletion(requested.identity, false)
        assertTrue(draft.pending, "A terminal result from the conflicted request cannot affect a retry.")
        draft.reportCompletion(retry.identity, true)
        assertTrue(draft.pending)
        assertTrue(draft.observe(changed.copy(revision = changed.revision + 1, policy = retry.policy)))
    }

    @Test fun workspaceSessionRetainsChildAndParentUntilSaveCancelOrRealAccountChange() {
        val initial = settings()
        val session = AutoMediaEditorDraftSession()
        val draft = assertNotNull(session.open(initial))
        draft.source(AutoMediaSource.GROUP, false)
        draft.edit(AutoMediaSource.USER)
        val child = assertNotNull(draft.child)
        child.toggle(AutoMediaType.PHOTO, false)

        session.bindAccount(null)
        val remountedDraft = assertNotNull(session.draftFor(initial.accountUserId))
        assertTrue(remountedDraft === draft, "A route remount must reconnect to the workspace-owned parent draft.")
        assertTrue(remountedDraft.child === child, "The open child editor must survive the same route remount.")
        assertEquals(0L, assertNotNull(remountedDraft.child).rules().limit(AutoMediaType.PHOTO))

        remountedDraft.cancelChild()
        assertEquals(initial.policy.sourceEnabled(AutoMediaSource.GROUP, false), remountedDraft.policy,
            "Cancelling a child drops its edits while keeping the parent's independent edit.")
        remountedDraft.edit(AutoMediaSource.USER)
        assertNotNull(remountedDraft.child).toggle(AutoMediaType.PHOTO, false)
        remountedDraft.applyChild()
        val submitted = assertNotNull(remountedDraft.begin())
        assertFalse(remountedDraft.observe(initial.copy(saving = true)))
        val previousError = initial.copy(error = true)
        assertFalse(remountedDraft.observe(previousError))
        assertTrue(remountedDraft.pending, "The old same-revision error cannot settle a fresh submission.")
        session.reportCompletion(draft, submitted.identity, false)
        assertFalse(remountedDraft.pending)
        assertTrue(remountedDraft.failed)
        assertTrue(session.draftFor(initial.accountUserId) === draft,
            "A failed save must leave the exact editor in the owning session for retry.")
        val retry = assertNotNull(remountedDraft.begin())
        assertEquals(submitted.policy, retry.policy, "Retry uses the retained submitted policy.")
        assertTrue(retry.identity > submitted.identity)
        session.reportCompletion(draft, submitted.identity, false)
        assertTrue(remountedDraft.pending, "A late completion cannot release a newer retry.")
        session.reportAdmission(draft, retry.identity, true)
        assertFalse(remountedDraft.observe(previousError))
        assertTrue(remountedDraft.pending)
        session.reportCompletion(draft, retry.identity, true)
        assertTrue(remountedDraft.pending, "Successful completion still waits for the accepted snapshot.")
        val accepted = initial.copy(revision = initial.revision + 1, policy = retry.policy)
        assertTrue(remountedDraft.observe(accepted))
        assertTrue(session.consumeAccepted(draft))
        assertNull(session.draftFor(initial.accountUserId), "Only the matching accepted revision consumes the owner draft.")
        session.reportCompletion(draft, retry.identity, false)
        assertNull(session.draftFor(initial.accountUserId), "A stale completion after acceptance cannot recreate a consumed draft.")

        val cancelled = assertNotNull(session.open(accepted))
        cancelled.source(AutoMediaSource.CHANNEL, false)
        session.discard(cancelled)
        assertNull(session.draftFor(initial.accountUserId), "Parent cancellation discards the unsaved editor.")

        val otherAccount = accepted.copy(accountUserId = 22, revision = 3)
        val second = assertNotNull(session.open(otherAccount))
        second.source(AutoMediaSource.CHANNEL, false)
        session.bindAccount(11)
        assertNull(session.draftFor(11), "Changing accounts clears the single current-account slot.")
        session.bindAccount(22)
        assertNull(session.draftFor(22), "Returning to a prior account must not restore a per-account cached draft.")
    }

    @Test fun sharedScaleHasAllHundredPositionsWithoutIntOverflow() {
        val all = (0..99).map(::autoMediaSizeByIndex)
        assertEquals(100, all.distinct().size)
        assertTrue(all.zipWithNext().all { (left, right) -> left < right })
        assertEquals(MIB, all.first())
        assertEquals(4_000L * MIB, all.last())
    }

    private fun settings(policy: AutoMediaPolicy = AutoMediaPolicy()) =
        AutoMediaSettings(accountUserId = 11, revision = 9, loaded = true, policy = policy)
}
