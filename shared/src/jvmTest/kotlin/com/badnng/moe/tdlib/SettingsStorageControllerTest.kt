package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsStorageControllerTest {
    @Test fun staleConfirmationAndNonRemovableTypesNeverReachTheNativeWrite() {
        val fixture = Fixture()
        fixture.controller.open()
        fixture.session.reads.single().second(SettingsResult(details))
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision - 1, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(SettingsError.CONFLICT, fixture.snapshot.error)
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.SECRET_THUMBNAIL))
        assertTrue(fixture.session.writes.isEmpty())
    }

    @Test fun realWriteSuccessThenFailedRefreshRemainsDistinctFromNativeWriteFailure() {
        val fixture = Fixture()
        fixture.controller.open()
        fixture.session.reads.single().second(SettingsResult(details))
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        assertTrue(fixture.snapshot.clearing)
        fixture.session.writes.single().third(SettingsResult(Unit))
        assertEquals(1L, fixture.snapshot.completedClearRevision)
        assertTrue(fixture.snapshot.loading)
        fixture.session.reads.last().second(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertTrue(fixture.snapshot.refreshAfterClearFailed)
        assertEquals(details, fixture.snapshot.details)
        assertEquals(1L, fixture.snapshot.completedClearRevision)
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(1, fixture.session.writes.size, "A successful cleanup with a failed refresh requires fresh statistics before another deletion.")
        fixture.controller.refresh(fixture.snapshot.token)
        fixture.session.reads.last().second(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertTrue(fixture.snapshot.refreshAfterClearFailed)
        fixture.controller.refresh(fixture.snapshot.token)
        fixture.session.reads.last().second(SettingsResult(details))
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        fixture.session.writes.last().third(SettingsResult(error = SettingsError.FORBIDDEN))
        assertFalse(fixture.snapshot.refreshAfterClearFailed)
        assertTrue(fixture.snapshot.needsFreshStatistics)
        assertEquals(1L, fixture.snapshot.completedClearRevision)
        assertEquals(SettingsError.FORBIDDEN, fixture.snapshot.error)
        val revision = fixture.snapshot.revision
        fixture.controller.clear(fixture.snapshot.token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(2, fixture.session.writes.size)
        fixture.controller.refresh(fixture.snapshot.token)
        fixture.session.reads.last().second(SettingsResult(details))
        assertFalse(fixture.snapshot.needsFreshStatistics)
        assertEquals(SettingsError.FORBIDDEN, fixture.snapshot.lastClearError)
        fixture.controller.clear(fixture.snapshot.token, revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(2, fixture.session.writes.size)
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        assertEquals(3, fixture.session.writes.size)
    }

    @Test fun closingWhileClearIsPendingRetiresNativeLeaseAndPreventsTheFollowupRead() {
        val fixture = Fixture()
        fixture.controller.open()
        fixture.session.reads.single().second(SettingsResult(details))
        fixture.controller.clear(fixture.snapshot.token, fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        val write = fixture.session.writes.single()
        assertTrue(write.second())
        fixture.controller.close(fixture.snapshot.token)
        assertFalse(write.second())
        write.third(SettingsResult(Unit))
        assertFalse(fixture.snapshot.opened)
        assertEquals(1, fixture.session.reads.size)
        assertEquals(0L, fixture.snapshot.completedClearRevision)
    }

    @Test fun queuedOpenWithDeadPageLeaseAndRetiredEngineNeverReadOrMutate() {
        val fixture = Fixture()
        fixture.controller.open { false }
        assertTrue(fixture.session.reads.isEmpty())
        var active = true
        fixture.controller.open { active }
        active = false
        assertFalse(fixture.session.reads.single().first())
        fixture.session.reads.single().second(SettingsResult(details))
        assertNull(fixture.snapshot.details)
        fixture.controller.deactivate()
        fixture.controller.open()
        assertEquals(1, fixture.session.reads.size)
    }

    @Test fun newerOwnerAndDuplicateCallbacksCannotReplaceTheCurrentStatistics() {
        val fixture = Fixture()
        fixture.controller.open()
        val first = fixture.session.reads.single()
        fixture.controller.open()
        val second = fixture.session.reads.last()
        first.second(SettingsResult(details))
        assertNull(fixture.snapshot.details)
        second.second(SettingsResult(details))
        second.second(SettingsResult(details.copy(bytes = 999)))
        assertEquals(details, fixture.snapshot.details)
        assertEquals(1L, fixture.snapshot.revision)
        fixture.controller.clear(fixture.snapshot.token.copy(generation = 999), fixture.snapshot.revision, setOf(SettingsStorageFileType.PHOTO))
        assertTrue(fixture.session.writes.isEmpty())
    }

    private class Fixture {
        val session = Session()
        var snapshot = SettingsStorageSnapshot()
        val controller = SettingsStorageController(7, session, { it() }, { snapshot = it })
    }
    private class Session : TdSettingsStorageSession {
        val reads = mutableListOf<Pair<() -> Boolean, (SettingsResult<SettingsStorageDetails>) -> Unit>>()
        val writes = mutableListOf<Triple<Set<SettingsStorageFileType>, () -> Boolean, (SettingsResult<Unit>) -> Unit>>()
        override fun storageDetails(active: () -> Boolean, result: (SettingsResult<SettingsStorageDetails>) -> Unit) { reads += active to result }
        override fun clearStorage(types: Set<SettingsStorageFileType>, active: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Triple(types, active, result) }
    }
    private companion object {
        val details = SettingsStorageDetails(30, 3, listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 30, 3)))
    }
}
