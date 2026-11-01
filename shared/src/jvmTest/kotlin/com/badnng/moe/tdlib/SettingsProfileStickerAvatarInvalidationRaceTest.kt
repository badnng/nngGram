package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import kotlin.test.*

/** Actor scheduling and update races only; the fake does not grant native source permission. */
class SettingsProfileStickerAvatarInvalidationRaceTest {
    @Test fun queuedReadCannotPublishAfterNativeInvalidationButAFreshReadCan() {
        val f = Fixture()
        val picker = f.open()
        val old = f.loadDefault(picker)
        f.native.defaults.single()(SettingsResult(page())) // queued on the actor
        picker.markCatalogInvalidated() // native owner has revoked, before UI notification
        assertNull(f.controller.captureMedia(f.parent, picker, null))
        f.drain()
        assertEquals(SettingsError.NOT_READY, old.single().error)
        f.native.defaults.single()(SettingsResult(page())) // a duplicated late callback
        f.drain()
        assertEquals(1, old.size)

        val fresh = f.loadDefault(picker)
        f.native.defaults.last()(SettingsResult(page()))
        f.drain()
        assertNotNull(fresh.single().value)
        val selection = f.select(picker)
        assertTrue(assertNotNull(f.controller.captureMedia(f.parent, picker, selection))())
        f.close(picker)
    }

    @Test fun invalidationInsideNativeSelectPreventsPublishingItsReturnedTicket() {
        val f = Fixture()
        val picker = f.open()
        f.native.onSelect = { picker.markCatalogInvalidated(); ProfileStickerAvatarSelectionTicket() }
        val result = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        f.controller.select(f.parent, picker, f.native.candidate, { true }, result::add)
        assertEquals(SettingsError.NOT_READY, result.single().error)
        assertNull(result.single().value)
        val forged = ProfileStickerAvatarSelectionTicket()
        assertNull(f.controller.captureMedia(f.parent, picker, forged))
        assertEquals(listOf<Long?>(null), f.save(picker, forged).first)
        assertTrue(f.native.writes.isEmpty())
        f.close(picker)
    }

    @Test fun updateRevokesMediaAndNewSaveWhileAnAlreadyDispatchedWriteKeepsItsReceipt() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val oldMedia = assertNotNull(f.controller.captureMedia(f.parent, picker, selection))
        assertTrue(oldMedia())
        val (admissions, results) = f.save(picker, selection)
        assertEquals(listOf<Long?>(101L), admissions)
        val write = f.native.writes.single()
        assertTrue(write.active())

        picker.markCatalogInvalidated()
        assertFalse(oldMedia())
        assertFalse(write.active())
        assertNull(f.controller.captureMedia(f.parent, picker, selection))
        assertNull(f.controller.captureMedia(f.parent, picker, null))
        assertEquals(listOf<Long?>(null), f.save(picker, selection).first)
        assertEquals(1, f.native.writes.size)
        write.done(SettingsResult(Unit))
        write.done(SettingsResult(error = SettingsError.CONFLICT))
        assertEquals(ProfileStickerAvatarMutationResult(f.parent, 101, null), results.single())
        f.close(picker)
    }

    @Test fun queuedSameSourceWindowCannotReviveAfterUpdateAndFreshReadCanStartAgain() {
        val f = Fixture()
        val picker = f.open()
        val selection = f.select(picker)
        val oldMedia = assertNotNull(f.controller.captureMedia(f.parent, picker, selection))
        val old = f.loadWindow(picker)
        assertFalse(oldMedia())
        assertNull(f.controller.captureMedia(f.parent, picker, selection))
        assertEquals(listOf<Long?>(null), f.save(picker, selection).first)
        f.native.windows.single()(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(page())))
        picker.markCatalogInvalidated()
        f.drain()
        assertEquals(SettingsError.NOT_READY, old.single().error)
        val stale = f.loadWindow(picker)
        assertEquals(SettingsError.CONFLICT, stale.single().error)
        assertEquals(1, f.native.windows.size, "An old window must not be sent to the native session")
        assertNull(f.controller.captureMedia(f.parent, picker, selection))
        assertEquals(listOf<Long?>(null), f.save(picker, selection).first)

        val fresh = f.loadDefault(picker)
        f.native.defaults.single()(SettingsResult(page()))
        f.drain()
        assertNotNull(fresh.single().value)
        val freshSelection = f.select(picker)
        val successful = f.loadWindow(picker)
        f.native.windows.last()(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(page())))
        f.drain()
        assertNotNull(successful.single().value)
        assertTrue(assertNotNull(f.controller.captureMedia(f.parent, picker, freshSelection))())
        assertEquals(listOf<Long?>(101L), f.save(picker, freshSelection).first)
        f.close(picker)
    }

    private class Fixture {
        val parent = SettingsProfileExtrasToken(7, 1, 43)
        private val queue = ArrayDeque<() -> Unit>()
        val native = Native()
        val controller = SettingsProfileStickerAvatarController(native, { queue.addLast(it) },
            allows = { it == parent }, captureParent = { if (it == parent) ({ true }) else null },
            allowsSave = { token, photo -> token == parent && photo == 91L },
            mutate = ProfileStickerAvatarMutationGate { _, _, owns, observed, work ->
                if (!owns()) null else { work(owns) { observed(101, it) }; 101L }
            })
        fun open(): ProfileStickerAvatarPickerTicket {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
            controller.open(parent, { true }, result::add)
            return assertNotNull(result.single().value)
        }
        fun close(picker: ProfileStickerAvatarPickerTicket) = controller.close(parent, picker)
        fun loadDefault(picker: ProfileStickerAvatarPickerTicket): MutableList<SettingsResult<ProfileStickerAvatarCandidatePage>> =
            mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>().also {
                controller.loadDefault(parent, picker, { true }, it::add)
            }
        fun loadWindow(picker: ProfileStickerAvatarPickerTicket): MutableList<SettingsResult<ProfileStickerAvatarCatalogWindow>> =
            mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>().also {
                controller.loadWindow(parent, picker, ProfileStickerAvatarCatalogCursor(), { true }, it::add)
            }
        fun select(picker: ProfileStickerAvatarPickerTicket): ProfileStickerAvatarSelectionTicket {
            val result = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
            controller.select(parent, picker, native.candidate, { true }, result::add)
            return assertNotNull(result.single().value)
        }
        fun save(picker: ProfileStickerAvatarPickerTicket, selection: ProfileStickerAvatarSelectionTicket):
            Pair<List<Long?>, List<ProfileStickerAvatarMutationResult>> {
            val admissions = mutableListOf<Long?>()
            val results = mutableListOf<ProfileStickerAvatarMutationResult>()
            controller.save(parent, picker, selection, 91, checkNotNull(ProfileStickerAvatarBackground.fromColors(listOf(1))),
                { true }, admissions::add, results::add)
            return admissions to results
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    private class Native : TdProfileStickerAvatarSession {
        data class Write(val active: () -> Boolean, val done: (SettingsResult<Unit>) -> Unit)
        val candidate = ProfileStickerAvatarCandidateTicket()
        val defaults = mutableListOf<(SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit>()
        val windows = mutableListOf<(SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit>()
        val writes = mutableListOf<Write>()
        var onSelect: (() -> ProfileStickerAvatarSelectionTicket)? = null
        override fun open(userId: Long, active: () -> Boolean) = ProfileStickerAvatarPickerTicket()
        override fun close(ticket: ProfileStickerAvatarPickerTicket) {}
        override fun retire() {}
        override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) { defaults += result }
        override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
            result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
        override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) = result(SettingsResult(error = SettingsError.UNSUPPORTED))
        override fun loadWindow(ticket: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor,
            result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit) { windows += result }
        override fun select(ticket: ProfileStickerAvatarPickerTicket, candidateTicket: ProfileStickerAvatarCandidateTicket): ProfileStickerAvatarSelectionTicket? =
            if (candidateTicket === candidate) onSelect?.invoke() ?: ProfileStickerAvatarSelectionTicket() else null
        override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
            expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit) { writes += Write(active, result) }
    }

    private companion object {
        fun page() = ProfileStickerAvatarCandidatePage(emptyList(), false)
    }
}
