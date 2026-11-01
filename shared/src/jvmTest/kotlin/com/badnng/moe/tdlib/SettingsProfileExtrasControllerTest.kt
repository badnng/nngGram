package com.badnng.moe.tdlib

import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsProfileExtrasControllerTest {
    @Test fun pageLeaseClosingBeforeQueuedTokenCloseCannotPublishAcceptedOrStartRefresh() {
        val f = Fixture()
        var visible = true
        f.controller.open(43) { visible }
        f.session.reads.single().done(SettingsResult(f.data))
        f.controller.birthdate(f.snapshot.token, null, SettingsBirthdate(4, 5))
        val pending = f.snapshot
        visible = false // Dispose closes this synchronously, before token-based Close reaches the actor.
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(pending, f.snapshot)
        assertEquals(0, f.profileRefreshes)
        assertEquals(1, f.session.reads.size)
        assertFalse(f.session.writes.single().current())
        f.controller.close(pending.token)
        assertNull(f.snapshot.data)
    }
    @Test fun externalPageLeaseStopsQueuedOpenAndEveryLaterReadBeforeNativeTokenCleanup() {
        val f = Fixture()
        var visible = false
        f.controller.open(43) { visible }
        assertTrue(f.session.reads.isEmpty())
        visible = true
        f.controller.open(43) { visible }
        assertTrue(f.session.reads.single().current())
        visible = false
        assertFalse(f.session.reads.single().current())
        f.session.reads.single().done(SettingsResult(f.data))
        assertNull(f.snapshot.data, "An abandoned owner cannot publish even before its token-based close executes.")
        f.controller.close(f.snapshot.token)
        f.controller.open(43) { true }
        assertEquals(2, f.session.reads.size)
        f.session.reads.last().done(SettingsResult(f.data))
        assertEquals(f.data, f.snapshot.data)
    }
    @Test fun retiredReadCannotPublishAnotherOwnerAndOpeningAgainRetainsNativePendingGate() {
        val f = Fixture()
        f.controller.open(43)
        val old = f.snapshot.token
        f.controller.close(old)
        f.controller.open(43)
        f.session.reads[0].done(SettingsResult(f.data.copy(photoId = 91)))
        assertNull(f.snapshot.data)
        f.session.reads[1].done(SettingsResult(f.data))
        assertEquals(f.data, f.snapshot.data)
        assertFalse(f.controller.accepts(old))
    }
    @Test fun uploadRetainsTemporaryFileThroughPageRetirementUntilOriginalNativeResult() {
        val f = Fixture().apply { loaded() }
        var deleted = 0
        val source = ownedAttachmentLease { deleted++ }
        val photo = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), source)
        val retained = assertNotNull(f.pool.retain(source))
        f.controller.upload(f.snapshot.token, 91, photo, retained)
        assertTrue(f.snapshot.busy)
        f.controller.close(f.snapshot.token)
        source.release()
        assertEquals(0, deleted)
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(1, deleted)
        assertEquals(0, f.profileRefreshes, "A retired page's terminal cannot trigger a profile read.")
        assertNull(f.snapshot.data)
    }
    @Test fun reopenedOwnerCannotSubmitAnotherMutationUntilEarlierNativeUploadFinishes() {
        val f = Fixture().apply { loaded() }
        val source = ownedAttachmentLease {}
        f.controller.upload(f.snapshot.token, 91,
            SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), source), assertNotNull(f.pool.retain(source)))
        f.controller.close(f.snapshot.token)
        f.controller.open(43)
        f.session.reads.last().done(SettingsResult(f.data))
        assertTrue(f.snapshot.busy)
        f.controller.birthdate(f.snapshot.token, null, SettingsBirthdate(4, 5))
        assertEquals(1, f.session.writes.size)
        f.session.writes.single().done(SettingsResult(Unit))
        assertTrue(f.snapshot.loading)
        f.session.reads.last().done(SettingsResult(f.data.copy(photoId = 92)))
        assertFalse(f.snapshot.busy)
        assertEquals(0L, f.snapshot.acceptedRevision, "An earlier owner's result must not consume the new editor.")
        source.release()
    }
    @Test fun nativeAcceptanceAndFailedReadbackStayDistinctToPreventDuplicateUpload() {
        val f = Fixture().apply { loaded() }
        f.controller.birthdate(f.snapshot.token, null, SettingsBirthdate(4, 5))
        val revision = f.snapshot.startedRevision
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertTrue(f.snapshot.busy)
        f.session.reads.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertFalse(f.snapshot.busy)
        assertEquals(revision, f.snapshot.acceptedRevision)
        assertEquals(0L, f.snapshot.completedRevision)
        assertEquals(0L, f.snapshot.failedRevision)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
    }
    @Test fun failedSaveKeepsNativeDataAndInvalidDatesNeverCallSessionMutation() {
        val f = Fixture().apply { loaded() }
        f.controller.birthdate(f.snapshot.token, null, SettingsBirthdate(31, 4))
        assertTrue(f.session.writes.isEmpty())
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.error)
        f.controller.birthdate(f.snapshot.token, null, SettingsBirthdate(4, 5))
        f.session.writes.single().done(SettingsResult(error = SettingsError.RATE_LIMITED))
        assertFalse(f.snapshot.busy)
        assertEquals(f.snapshot.startedRevision, f.snapshot.failedRevision)
        assertEquals(f.data, f.snapshot.data)
    }
    @Test fun deactivationNeverReleasesNativeLeaseBeforeClosedButClosureReleasesExactlyOnce() {
        val f = Fixture().apply { loaded() }
        var deleted = 0
        val source = ownedAttachmentLease { deleted++ }
        val photo = SettingsPreparedProfilePhoto("synthetic.jpg", 512, 32, "00".repeat(32), source)
        f.controller.upload(f.snapshot.token, 91, photo, assertNotNull(f.pool.retain(source)))
        f.controller.deactivate()
        source.release()
        assertEquals(0, deleted)
        f.pool.releaseAll() // Exactly the engine's AuthorizationStateClosed boundary.
        assertEquals(1, deleted)
        f.session.writes.single().done(SettingsResult(error = SettingsError.NOT_READY))
        f.pool.releaseAll()
        assertEquals(1, deleted)
    }

    private class Fixture {
        val data = SettingsProfileExtrasData(43, 91, null, SettingsCalendarDate(25, 10, 2026))
        val session = Session()
        val pool = AttachmentSendLeases()
        var snapshot = SettingsProfileExtrasSnapshot()
        var profileRefreshes = 0
        val controller = SettingsProfileExtrasController(7, session, { it() }, { snapshot = it }, { profileRefreshes++ })
        fun loaded() { controller.open(43); session.reads.last().done(SettingsResult(data)) }
    }
    private class Session : TdSettingsProfileExtrasSession {
        class Read(val current: () -> Boolean, val done: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
        class Write(val current: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit)
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<Write>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) { reads += Read(isCurrent, result) }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(isCurrent, result) }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(isCurrent, result) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(isCurrent, result) }
    }
}
