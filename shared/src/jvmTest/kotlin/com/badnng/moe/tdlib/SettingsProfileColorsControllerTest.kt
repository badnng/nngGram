package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class SettingsProfileColorsControllerTest {
    @Test fun closedOwnerAndOldGenerationCannotPublishIntoSameUserReopen() {
        val f = Fixture()
        f.controller.open(43)
        val old = f.snapshot.token
        f.controller.close(old)
        f.controller.open(43)
        assertNotEquals(old, f.snapshot.token)
        assertFalse(f.controller.accepts(old))
        assertFalse(f.controller.accepts(f.snapshot.token.copy(generation = 8)))
        f.session.reads[0].done(SettingsResult(f.data))
        f.drain()
        assertNull(f.snapshot.data)
        f.session.reads[1].done(SettingsResult(f.data))
        f.drain()
        assertEquals(f.data, f.snapshot.data)
    }

    @Test fun sameLiveOwnerOpenIsIdempotentAndClosedPageLeasePreventsLateRead() {
        val f = Fixture()
        val visible = AtomicBoolean(true)
        f.controller.open(43, visible::get)
        val token = f.snapshot.token
        f.controller.open(43)
        assertEquals(token, f.snapshot.token)
        assertEquals(1, f.session.reads.size)
        visible.set(false)
        assertFalse(f.session.reads.single().active())
        f.session.reads.single().done(SettingsResult(f.data))
        f.drain()
        assertNull(f.snapshot.data)
        f.controller.close(token)
        assertFalse(f.snapshot.opened)
    }

    @Test fun inactiveInvalidOrDisallowedOpenDoesNotCallNative() {
        val f = Fixture()
        f.controller.open(0)
        f.controller.open(43) { false }
        f.allowed = false
        f.controller.open(43)
        assertTrue(f.session.reads.isEmpty())
    }

    @Test fun callbackPublishesOnlyAfterEnqueueAndDuplicateReadIsIgnored() {
        val f = Fixture()
        f.controller.open(43)
        val read = f.session.reads.single()
        read.done(SettingsResult(f.data))
        read.done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        assertNull(f.snapshot.data)
        assertEquals(1, f.queue.size)
        f.drain()
        assertEquals(f.data, f.snapshot.data)
        assertNull(f.snapshot.error)
    }

    @Test fun wrongNativeSelfCannotBePublished() {
        val f = Fixture()
        f.controller.open(43)
        f.session.reads.single().done(SettingsResult(f.data.copy(userId = 44)))
        f.drain()
        assertNull(f.snapshot.data)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
    }

    @Test fun saveUsesExactPairAndContentRevisionAndPreservesOtherPart() {
        val f = Fixture().apply { loaded() }
        val profileEdit = f.edit(SettingsProfileColorPart.PROFILE, SettingsProfileColorValue(9), 4)
        f.controller.save(f.snapshot.token, profileEdit)
        val profileActual = f.data.copy(values = f.data.values.copy(profile = profileEdit.desired))
        f.complete(SettingsProfileColorMutationResult(true, profileActual))
        val profileOutcome = f.snapshot.outcomes.getValue(SettingsProfileColorPart.PROFILE)
        val nameEdit = f.edit(desired = SettingsProfileColorValue(2), revision = 7)
        f.controller.save(f.snapshot.token, nameEdit)
        assertEquals(profileActual, f.session.writes.last().expected)
        assertEquals(SettingsProfileColorPart.NAME, f.session.writes.last().part)
        f.complete(SettingsProfileColorMutationResult(true, profileActual.copy(values = profileActual.values.copy(name = nameEdit.desired))))
        assertEquals(profileOutcome, f.snapshot.outcomes[SettingsProfileColorPart.PROFILE])
        assertEquals(7L, f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME).attempt.edit.contentRevision)
        assertEquals(profileEdit.desired, f.snapshot.data?.values?.profile)
    }

    @Test fun lateAcceptanceRetainsOriginalRevisionWhenUserReselectsSameValue() {
        val f = Fixture().apply { loaded() }
        val original = f.edit(desired = SettingsProfileColorValue(2), revision = 21)
        f.controller.save(f.snapshot.token, original)
        val retyped = original.copy(contentRevision = 23)
        f.controller.save(f.snapshot.token, retyped)
        assertEquals(1, f.session.writes.size)
        f.complete(SettingsProfileColorMutationResult(true, f.data.copy(values = f.data.values.copy(name = original.desired))))
        assertEquals(original, f.snapshot.outcomes.getValue(original.part).attempt.edit)
        assertNotEquals(retyped.contentRevision, f.snapshot.outcomes.getValue(original.part).attempt.edit.contentRevision)
    }

    @Test fun mismatchedBaseAndNonPremiumFailBeforeNativeSave() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit().copy(base = SettingsProfileColorValue(6)))
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        assertTrue(f.session.writes.isEmpty())
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(f.data.copy(premium = false)))
        f.drain()
        f.controller.save(f.snapshot.token, f.edit())
        assertEquals(SettingsError.FORBIDDEN, f.snapshot.error)
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun oneNativeMutationBlocksOtherDomainUntilItsOriginalTerminalResult() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        val otherEdit = f.edit(SettingsProfileColorPart.PROFILE, SettingsProfileColorValue(9))
        val old = f.snapshot.token
        f.controller.close(old)
        f.controller.open(43)
        val next = f.snapshot.token
        assertTrue(f.snapshot.busy)
        assertTrue(f.snapshot.loading)
        assertEquals(1, f.session.reads.size)
        f.controller.save(next, otherEdit)
        assertEquals(1, f.session.writes.size)
        f.complete(SettingsProfileColorMutationResult(true, f.data))
        assertEquals(2, f.session.reads.size)
        assertTrue(f.snapshot.outcomes.isEmpty())
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertFalse(f.snapshot.busy)
        assertFalse(f.snapshot.loading)
        assertEquals(next, f.snapshot.token)
    }

    @Test fun acceptedUnknownReadbackBlocksRepeatedSetUntilExplicitSuccessfulRefresh() {
        val f = Fixture().apply { loaded() }
        val edit = f.edit()
        f.controller.save(f.snapshot.token, edit)
        f.complete(SettingsProfileColorMutationResult(true, error = SettingsError.REQUEST_FAILED))
        assertEquals(setOf(edit.part), f.snapshot.readbackPending)
        assertTrue(f.snapshot.outcomes.getValue(edit.part).locallyAccepted)
        f.controller.save(f.snapshot.token, edit)
        assertEquals(1, f.session.writes.size)
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.drain()
        assertEquals(setOf(edit.part), f.snapshot.readbackPending)
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(f.data.copy(values = f.data.values.copy(name = edit.desired))))
        f.drain()
        assertTrue(f.snapshot.readbackPending.isEmpty())
        assertNull(f.snapshot.outcomes.getValue(edit.part).error)
        assertTrue(f.snapshot.outcomes.getValue(edit.part).locallyAccepted)
    }

    @Test fun observerReadCannotSilentlySettleUnknownAcceptance() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.complete(SettingsProfileColorMutationResult(true, error = SettingsError.REQUEST_FAILED))
        f.session.observer?.invoke()
        f.drain()
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertEquals(setOf(SettingsProfileColorPart.NAME), f.snapshot.readbackPending)
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        val outcome = f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME)
        assertTrue(outcome.locallyAccepted)
        assertEquals(SettingsError.CONFLICT, outcome.error)
        assertTrue(f.snapshot.readbackPending.isEmpty())
    }

    @Test fun acceptedKnownMismatchReportsActualAndConflictWithoutInventedRollback() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.complete(SettingsProfileColorMutationResult(true, f.data))
        assertEquals(f.data, f.snapshot.data)
        assertTrue(f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME).locallyAccepted)
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        assertTrue(f.snapshot.refreshRequired)
        assertTrue(f.snapshot.readbackPending.isEmpty())
        assertEquals(1, f.session.writes.size)
    }

    @Test fun nativeFailureRetainsDataAndOtherOutcome() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.complete(SettingsProfileColorMutationResult(error = SettingsError.RATE_LIMITED))
        assertEquals(f.data, f.snapshot.data)
        assertFalse(f.snapshot.busy)
        assertFalse(f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME).locallyAccepted)
        assertEquals(SettingsError.RATE_LIMITED, f.snapshot.error)
    }

    @Test fun duplicateMutationCallbacksCannotReleaseOrPublishTwice() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        val done = f.session.writes.single().done
        done(SettingsProfileColorMutationResult(error = SettingsError.RATE_LIMITED))
        done(SettingsProfileColorMutationResult(true, f.data))
        f.drain()
        assertEquals(SettingsError.RATE_LIMITED, f.snapshot.error)
        assertFalse(f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME).locallyAccepted)
    }

    @Test fun observerUpdatesActualDataAndMarksRefreshWithoutMakingEditorDrafts() {
        val f = Fixture().apply { loaded() }
        f.session.observer?.invoke()
        f.session.observer?.invoke()
        assertEquals(1, f.queue.size)
        f.drain()
        val changed = f.data.copy(values = f.data.values.copy(profile = SettingsProfileColorValue(9)), nativeRevision = 1)
        f.session.reads.last().done(SettingsResult(changed))
        f.drain()
        assertEquals(changed, f.snapshot.data)
        assertTrue(f.snapshot.refreshRequired)
        assertTrue(f.snapshot.outcomes.isEmpty())
    }

    @Test fun observerDuringWriteWaitsForOriginalResultAndPreservesItsOutcome() {
        val f = Fixture().apply { loaded() }
        val edit = f.edit()
        f.controller.save(f.snapshot.token, edit)
        f.session.observer?.invoke()
        f.drain()
        assertEquals(1, f.session.reads.size)
        val actual = f.data.copy(values = f.data.values.copy(name = edit.desired))
        f.complete(SettingsProfileColorMutationResult(true, actual))
        assertEquals(2, f.session.reads.size)
        f.session.reads.last().done(SettingsResult(actual))
        f.drain()
        assertFalse(f.snapshot.refreshRequired)
        assertEquals(edit, f.snapshot.outcomes.getValue(edit.part).attempt.edit)
    }

    @Test fun observerCannotReplaceAnExplicitPendingRefreshRequest() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.complete(SettingsProfileColorMutationResult(true))
        f.controller.refresh(f.snapshot.token)
        f.session.observer?.invoke()
        f.drain()
        assertEquals(2, f.session.reads.size)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        assertTrue(f.snapshot.readbackPending.isEmpty())
    }

    @Test fun defaultMetadataIsExplicitAndNewPatternMustComeFromThisPagesRealResult() {
        val f = Fixture().apply { loaded() }
        assertTrue(f.session.backgrounds.isEmpty())
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue(2, 500)))
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.error)
        assertTrue(f.session.writes.isEmpty())
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.single().done(SettingsResult(listOf(emoji(500))))
        f.drain()
        f.controller.closeBackgrounds(f.snapshot.token)
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue(2, 500)))
        assertEquals(500L, f.session.writes.single().desired.backgroundCustomEmojiId)
    }

    @Test fun closingPickerRejectsLateMetadataAndNeverAuthorizesItsIds() {
        val f = Fixture().apply { loaded() }
        f.controller.openBackgrounds(f.snapshot.token)
        val request = f.session.backgrounds.single()
        f.controller.closeBackgrounds(f.snapshot.token)
        assertFalse(request.active())
        request.done(SettingsResult(listOf(emoji(500))))
        f.drain()
        assertTrue(f.snapshot.backgrounds.isEmpty())
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue(2, 500)))
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun metadataAuthorizationCannotCrossSameUserPageOwners() {
        val f = Fixture().apply { loaded() }
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.single().done(SettingsResult(listOf(emoji(500))))
        f.drain()
        f.controller.close(f.snapshot.token)
        f.controller.open(43)
        f.session.reads.last().done(SettingsResult(f.data))
        f.drain()
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue(2, 500)))
        assertTrue(f.session.writes.isEmpty())
    }

    @Test fun resolveOnlyUsesCurrentNativeOrAlreadyReturnedIdentitiesAndCapsBatch() {
        val f = Fixture()
        val original = f.data.copy(values = f.data.values.copy(name = SettingsProfileColorValue(1, -5),
            profile = SettingsProfileColorValue(-1, 6), gift = gift()))
        f.loaded(original)
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.single().done(SettingsResult(emptyList()))
        f.drain()
        listOf(listOf(0L), listOf(777L), List(201) { -5L }).forEach {
            f.controller.resolveBackgrounds(f.snapshot.token, it)
        }
        assertTrue(f.session.resolves.isEmpty())
        f.controller.resolveBackgrounds(f.snapshot.token, listOf(-5, 6, 7, 8))
        assertEquals(listOf(-5L, 6L, 7L, 8L), f.session.resolves.single().ids)
        f.session.resolves.single().done(SettingsResult(listOf(emoji(8), emoji(-5))))
        f.drain()
        assertEquals(listOf(8L, -5L), f.snapshot.backgrounds.map { it.customEmojiId })
    }

    @Test fun failedOrOversizedMetadataPreservesPreviousValidSelection() {
        val f = Fixture().apply { loaded() }
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.last().done(SettingsResult(listOf(emoji(500))))
        f.drain()
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.last().done(SettingsResult(error = SettingsError.REQUEST_FAILED))
        f.drain()
        assertEquals(listOf(500L), f.snapshot.backgrounds.map { it.customEmojiId })
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.last().done(SettingsResult(List(4097) { emoji(it.toLong() + 1) }))
        f.drain()
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.backgroundsError)
        assertEquals(listOf(500L), f.snapshot.backgrounds.map { it.customEmojiId })
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue(2, 500)))
        assertEquals(1, f.session.writes.size)
    }

    @Test fun resolveRejectsForeignReturnedIdsWithoutReplacingValidMetadata() {
        val f = Fixture().apply { loaded(fixtureData().copy(values = fixtureData().values.copy(name = SettingsProfileColorValue(1, 5)))) }
        f.controller.openBackgrounds(f.snapshot.token)
        f.session.backgrounds.last().done(SettingsResult(listOf(emoji(500))))
        f.drain()
        f.controller.resolveBackgrounds(f.snapshot.token, listOf(5))
        f.session.resolves.single().done(SettingsResult(listOf(emoji(700))))
        f.drain()
        assertEquals(listOf(500L), f.snapshot.backgrounds.map { it.customEmojiId })
        assertEquals(SettingsError.INVALID_VALUE, f.snapshot.backgroundsError)
    }

    @Test fun currentHiddenColorAndEmojiCanRemainAndResetNeedsNoFabricatedCatalog() {
        val f = Fixture()
        val data = f.data.copy(values = f.data.values.copy(name = SettingsProfileColorValue(40, -50)),
            catalog = SettingsProfileColorsCatalog())
        f.loaded(data)
        f.controller.save(f.snapshot.token, f.edit(desired = data.values.name))
        assertEquals(1, f.session.writes.size)
        f.complete(SettingsProfileColorMutationResult(error = SettingsError.REQUEST_FAILED))
        f.controller.save(f.snapshot.token, f.edit(desired = SettingsProfileColorValue((43L % 7).toInt())))
        assertEquals(2, f.session.writes.size)
        assertTrue(f.session.backgrounds.isEmpty())
    }

    @Test fun malformedCatalogAndGiftReplacementRequireExplicitPermission() {
        val f = Fixture()
        val data = f.data.copy(values = f.data.values.copy(gift = gift()), catalog = f.data.catalog.copy(nameError = SettingsError.INVALID_VALUE))
        f.loaded(data)
        f.controller.save(f.snapshot.token, f.edit().copy(replaceGift = true))
        assertEquals(SettingsError.CONFLICT, f.snapshot.error)
        assertTrue(f.session.writes.isEmpty())
        f.controller.refresh(f.snapshot.token)
        f.session.reads.last().done(SettingsResult(data.copy(catalog = f.data.catalog)))
        f.drain()
        f.controller.save(f.snapshot.token, f.edit())
        assertTrue(f.session.writes.isEmpty())
        f.controller.save(f.snapshot.token, f.edit().copy(replaceGift = true))
        assertTrue(f.session.writes.single().replaceGift)
    }

    @Test fun actorGateIsRecheckedAndNativeWorkerActiveNeverInvokesItOrActorMaps() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        val request = f.session.writes.single()
        var workerActive = false
        thread { workerActive = request.active() }.join()
        assertTrue(workerActive)
        val before = f.snapshot
        thread { request.done(SettingsProfileColorMutationResult(error = SettingsError.REQUEST_FAILED)) }.join()
        assertEquals(before, f.snapshot)
        f.drain()
        f.allowed = false
        f.controller.save(f.snapshot.token, f.edit())
        assertEquals(1, f.session.writes.size)
    }

    @Test fun thrownNativeOperationTerminatesExactlyOnceAndControllerCanRetry() {
        val f = Fixture().apply { loaded() }
        f.session.throwSave = true
        f.controller.save(f.snapshot.token, f.edit())
        f.drain()
        assertFalse(f.snapshot.busy)
        assertEquals(SettingsError.REQUEST_FAILED, f.snapshot.error)
        f.session.throwSave = false
        f.controller.save(f.snapshot.token, f.edit())
        assertTrue(f.snapshot.busy)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun retirementInvalidatesReadWritePickerAndQueuedObserverWithoutPublishing() {
        val f = Fixture().apply { loaded() }
        f.controller.openBackgrounds(f.snapshot.token)
        f.controller.save(f.snapshot.token, f.edit())
        val oldObserver = f.session.observer
        f.controller.deactivate()
        val previous = f.snapshot
        assertFalse(f.session.writes.single().active())
        assertFalse(f.session.backgrounds.single().active())
        assertNull(f.session.observer)
        oldObserver?.invoke()
        f.session.writes.single().done(SettingsProfileColorMutationResult(true, f.data))
        f.session.backgrounds.single().done(SettingsResult(listOf(emoji(500))))
        f.drain()
        f.controller.open(43)
        assertEquals(previous, f.snapshot)
        assertEquals(1, f.session.reads.size)
    }

    private class Fixture {
        val data = fixtureData()
        val session = FakeSession()
        val queue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        private val actorThread = Thread.currentThread()
        var allowed = true
        var snapshot = SettingsProfileColorsSnapshot()
        val controller = SettingsProfileColorsController(9, session, { queue.add(it) }, { snapshot = it }, {
            check(Thread.currentThread() === actorThread) { "Actor gate accessed from native worker" }
            allowed
        })
        fun drain() { while (true) (queue.poll() ?: break).invoke() }
        fun loaded(value: SettingsProfileColorsData = data) {
            controller.open(43)
            session.reads.last().done(SettingsResult(value))
            drain()
        }
        fun edit(part: SettingsProfileColorPart = SettingsProfileColorPart.NAME,
            desired: SettingsProfileColorValue = SettingsProfileColorValue(2), revision: Long = 1,
        ) = SettingsProfileColorEdit(part, checkNotNull(snapshot.data).values.value(part), desired, revision)
        fun complete(value: SettingsProfileColorMutationResult) { session.writes.last().done(value); drain() }
    }

    private class FakeSession : TdSettingsProfileColorsSession {
        var observer: (() -> Unit)? = null
        var throwSave = false
        data class Read(val active: () -> Boolean, val done: (SettingsResult<SettingsProfileColorsData>) -> Unit)
        data class Write(val expected: SettingsProfileColorsData, val part: SettingsProfileColorPart,
            val desired: SettingsProfileColorValue, val replaceGift: Boolean, val active: () -> Boolean,
            val done: (SettingsProfileColorMutationResult) -> Unit)
        data class Metadata(val active: () -> Boolean, val done: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit,
            val ids: List<Long> = emptyList())
        val reads = mutableListOf<Read>()
        val writes = mutableListOf<Write>()
        val backgrounds = mutableListOf<Metadata>()
        val resolves = mutableListOf<Metadata>()
        override fun observe(observer: (() -> Unit)?) { this.observer = observer }
        override fun catalog() = SettingsProfileColorsCatalog()
        override fun load(userId: Long, active: () -> Boolean, result: (SettingsResult<SettingsProfileColorsData>) -> Unit) {
            reads += Read(active, result)
        }
        override fun save(expected: SettingsProfileColorsData, part: SettingsProfileColorPart, desired: SettingsProfileColorValue,
            replaceGift: Boolean, active: () -> Boolean, result: (SettingsProfileColorMutationResult) -> Unit,
        ) {
            if (throwSave) error("Synthetic exception")
            writes += Write(expected, part, desired, replaceGift, active, result)
        }
        override fun backgroundEmojis(active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            backgrounds += Metadata(active, result)
        }
        override fun resolveEmojis(ids: List<Long>, active: () -> Boolean, result: (SettingsResult<List<SettingsBackgroundEmoji>>) -> Unit) {
            resolves += Metadata(active, result, ids)
        }
        override fun retire() = Unit
    }

    companion object {
        private fun fixtureData() = SettingsProfileColorsData(43, true,
            SettingsProfileColorsValues(SettingsProfileColorValue(1), SettingsProfileColorValue(-1)),
            SettingsProfileColorsCatalog(availableNameColorIds = listOf(0, 1, 2, 3), availableProfileColorIds = listOf(9)))
        private fun emoji(id: Long) = SettingsBackgroundEmoji(id,
            StickerItem(id + 1000, 0, "", MessageMedia(MediaFile(1))))
        private fun gift() = SettingsUpgradedGiftColors(12, 7, 8, 0, emptyList(), 0, emptyList())
    }
}
