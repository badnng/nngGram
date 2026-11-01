package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

class SettingsPersonalChannelControllerTest {
    @Test fun openingProfileDoesNotLoadOrResolveThePersonalChannelCatalog() {
        val f = Fixture()
        f.controller.open(43)
        assertEquals(1, f.session.reads.size)
        f.session.reads.last().done(SettingsResult(f.data))
        assertTrue(f.session.catalogs.isEmpty())
        assertTrue(f.session.pages.isEmpty())
        assertFalse(f.snapshot.channelsOpened)
    }
    @Test fun explicitMoreAdvancesByRawSliceAndFailedPageRetriesItsExactOffset() {
        val f = Fixture().apply { loaded(); select(70) }
        assertEquals((1L..32).map { -it }, f.session.pages.single().ids)
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1)), 31)))
        assertEquals(31, f.snapshot.channelsUnavailable)
        assertEquals(1, f.snapshot.channels.size)
        assertTrue(f.snapshot.channelsHasMore)
        assertEquals(1, f.session.pages.size, "Initial selection resolves only 32 identities.")
        f.controller.morePersonalChannels(f.snapshot.token)
        assertEquals((33L..64).map { -it }, f.session.pages.last().ids)
        f.session.pages.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertEquals(1, f.snapshot.channels.size)
        f.controller.morePersonalChannels(f.snapshot.token)
        assertEquals((33L..64).map { -it }, f.session.pages.last().ids)
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-33)), 31)))
        f.controller.morePersonalChannels(f.snapshot.token)
        assertEquals((65L..70).map { -it }, f.session.pages.last().ids)
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(emptyList(), 6)))
        assertFalse(f.snapshot.channelsHasMore)
        assertEquals(68, f.snapshot.channelsUnavailable)
    }
    @Test fun staleSelectorCatalogAndPageCannotReplaceANewSelectionOwner() {
        val f = Fixture().apply { loaded() }
        var first = true
        f.controller.openPersonalChannels(f.snapshot.token) { first }
        val old = f.session.catalogs.last()
        first = false
        f.controller.closePersonalChannels(f.snapshot.token)
        f.controller.openPersonalChannels(f.snapshot.token) { true }
        val current = f.session.catalogs.last()
        old.done(SettingsResult(SettingsPersonalChannelCatalog(listOf(-77), 1, false)))
        assertTrue(f.session.pages.isEmpty())
        current.done(SettingsResult(SettingsPersonalChannelCatalog(listOf(-88), 1, false)))
        assertEquals(listOf(-88L), f.session.pages.single().ids)
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-88)), 0)))
        assertEquals(listOf(-88L), f.snapshot.channels.map { it.chatId })
    }
    @Test fun deadExternalSelectorLeaseStopsQueuedOpenBeforeCatalogRequest() {
        val f = Fixture().apply { loaded() }
        f.controller.openPersonalChannels(f.snapshot.token) { false }
        assertFalse(f.snapshot.channelsOpened)
        assertTrue(f.session.catalogs.isEmpty())
        assertTrue(f.session.pages.isEmpty())
    }
    @Test fun unknownOrUnresolvedIdentitiesNeverAuthorizeSaveAndRemovalRemainsExplicitZero() {
        val f = Fixture().apply { loaded(); select(2) }
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1)), 1)))
        f.controller.savePersonalChannel(f.snapshot.token, -9, -2)
        f.controller.savePersonalChannel(f.snapshot.token, -9, -100)
        assertTrue(f.session.writes.isEmpty())
        f.controller.savePersonalChannel(f.snapshot.token, -9, 0)
        assertEquals(-9L, f.session.writes.single().expected)
        assertEquals(0L, f.session.writes.single().desired)
        assertTrue(f.snapshot.busy)
    }
    @Test fun refreshedCurrentChannelConflictsWithAnEarlierFrozenEdit() {
        val f = Fixture().apply { loaded(); select(1) }
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1)), 0)))
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(f.data.copy(personalChatId = -99)))
        f.controller.savePersonalChannel(f.snapshot.token, -9, -1)
        assertTrue(f.session.writes.isEmpty())
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
    }
    @Test fun acceptedWriteAndReadbackFailureAreSeparateAndRetainTheEarlierKnownCurrentValue() {
        val f = Fixture().apply { loaded(); select(1) }
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1)), 0)))
        f.controller.savePersonalChannel(f.snapshot.token, -9, -1)
        val write = f.snapshot.startedRevision
        f.session.writes.single().done(SettingsResult(Unit))
        assertEquals(write, f.snapshot.acceptedRevision)
        assertTrue(f.snapshot.busy)
        f.session.reads.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertEquals(0L, f.snapshot.completedRevision)
        assertEquals(write, f.snapshot.acceptedRevision)
        assertEquals(-9L, f.snapshot.data?.personalChatId)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
    }
    @Test fun aCancelledOriginalWriteCanOnlyRefreshDataAndCannotConsumeANewSelectorDraft() {
        val f = Fixture().apply { loaded() }
        var first = true
        f.controller.openPersonalChannels(f.snapshot.token) { first }
        f.session.catalogs.last().done(SettingsResult(SettingsPersonalChannelCatalog(listOf(-1), 1, false)))
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1)), 0)))
        f.controller.savePersonalChannel(f.snapshot.token, -9, -1)
        val old = f.session.writes.single()
        first = false
        f.controller.closePersonalChannels(f.snapshot.token)
        f.controller.openPersonalChannels(f.snapshot.token) { true }
        f.session.catalogs.last().done(SettingsResult(SettingsPersonalChannelCatalog(listOf(-2), 1, false)))
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-2)), 0)))
        assertFalse(old.current())
        old.done(SettingsResult(Unit))
        assertEquals(0L, f.snapshot.acceptedRevision)
        f.session.reads.last().done(SettingsResult(f.data.copy(personalChatId = -1)))
        assertEquals(listOf(-2L), f.snapshot.channels.map { it.chatId })
        assertTrue(f.snapshot.channelsOpened)
        assertFalse(f.snapshot.busy)
    }
    @Test fun forgedPageRowsCannotAuthorizeUnrequestedNativeIdentities() {
        val f = Fixture().apply { loaded(); select(2) }
        f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(listOf(channel(-1), channel(-100)), 0)))
        assertEquals(listOf(-1L), f.snapshot.channels.map { it.chatId })
        assertEquals(1, f.snapshot.channelsUnavailable)
        f.controller.savePersonalChannel(f.snapshot.token, -9, -100)
        assertTrue(f.session.writes.isEmpty())
    }
    @Test fun boundedCatalogExposesTheLimitIndependentlyOfItsFinalLoadedSlice() {
        val f = Fixture().apply { loaded(); select(700) }
        repeat(16) { page ->
            assertEquals(32, f.session.pages.last().ids.size)
            f.session.pages.last().done(SettingsResult(SettingsPersonalChannelsPage(emptyList(), 32)))
            if (page < 15) f.controller.morePersonalChannels(f.snapshot.token)
        }
        assertEquals(16, f.session.pages.size)
        assertEquals(512, f.snapshot.channelsUnavailable)
        assertEquals(700, f.snapshot.channelsTotal)
        assertTrue(f.snapshot.channelsLimited)
        assertFalse(f.snapshot.channelsHasMore)
        f.controller.morePersonalChannels(f.snapshot.token)
        assertEquals(16, f.session.pages.size)
    }
    private class Fixture {
        val data = SettingsProfileExtrasData(43, null, null, SettingsCalendarDate(25, 10, 2026), -9, channel(-9))
        val session = Session()
        var snapshot = SettingsProfileExtrasSnapshot()
        val controller = SettingsProfileExtrasController(7, session, { it() }, { snapshot = it })
        fun loaded() { controller.open(43); session.reads.last().done(SettingsResult(data)) }
        fun select(count: Int) {
            controller.openPersonalChannels(snapshot.token) { true }
            session.catalogs.last().done(SettingsResult(SettingsPersonalChannelCatalog((1L..count.toLong()).map { -it }, count, false)))
        }
    }
    private class Session : TdSettingsProfileExtrasSession {
        class Read(val current: () -> Boolean, val done: (SettingsResult<SettingsProfileExtrasData>) -> Unit)
        class Catalog(val current: () -> Boolean, val done: (SettingsResult<SettingsPersonalChannelCatalog>) -> Unit)
        class Page(val ids: List<Long>, val current: () -> Boolean, val done: (SettingsResult<SettingsPersonalChannelsPage>) -> Unit)
        class Write(val expected: Long, val desired: Long, val current: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit)
        val reads = mutableListOf<Read>(); val catalogs = mutableListOf<Catalog>(); val pages = mutableListOf<Page>(); val writes = mutableListOf<Write>()
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) { reads += Read(isCurrent, result) }
        override fun personalChannelCatalog(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelCatalog>) -> Unit) { catalogs += Catalog(isCurrent, result) }
        override fun personalChannelsPage(userId: Long, ids: List<Long>, isCurrent: () -> Boolean, result: (SettingsResult<SettingsPersonalChannelsPage>) -> Unit) { pages += Page(ids, isCurrent, result) }
        override fun setPersonalChannel(userId: Long, expectedChatId: Long, desiredChatId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { writes += Write(expectedChatId, desiredChatId, isCurrent, result) }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { error("Unrelated birthday write") }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { error("Unrelated avatar write") }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) { error("Unrelated avatar removal") }
    }
    private companion object { fun channel(id: Long) = SettingsPersonalChannel(id, "Native channel $id", "channel_${-id}") }
}
