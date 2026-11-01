package com.badnng.moe.tdlib

import com.badnng.moe.chat.ChatEvent
import com.badnng.moe.settings.*
import com.badnng.moe.status.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Engine/parent/child dispatch, with an intentionally ungated and non-deduplicating native facade. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileStickerAvatarEngineTest {
    @Test fun nativeResultQueuedBeforeAuthorizationOrMandatoryLossCannotPublish() {
        runTest {
            for (mandatory in listOf(false, true)) withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, results::add)); runCurrent()
                val read = f.native.defaultReads.single()
                onWorker { read.done(SettingsResult(f.native.candidatePage())) }
                assertTrue(results.isEmpty(), "The native completion is still queued on the actor")
                if (mandatory) f.block(true) else f.auth(AuthorizationPhase.WAITING_CODE)
                // The queued native result precedes the actor's authorization/gate event.
                runCurrent()
                assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error }, "mandatory=$mandatory")
                assertNull(results.single().value)
                onWorker { read.done(SettingsResult(f.native.candidatePage())) }
                runCurrent(); assertEquals(1, results.size)
            }
        }
    }

    @Test fun dispatchedSaveKeepsItsReceiptAfterAuthorizationOrMandatoryLoss() {
        runTest {
            for (mandatory in listOf(false, true)) withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admissions::add, outcomes::add)); runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                assertTrue(write.active())
                if (mandatory) f.block(true) else f.auth(AuthorizationPhase.WAITING_CODE)
                assertFalse(write.active(), "Loss revokes the original native lease before the actor drains")
                onWorker { write.done(SettingsResult(Unit)); write.done(SettingsResult(error = SettingsError.CONFLICT)) }
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                runCurrent(); assertEquals(1, outcomes.size)
            }
        }
    }

    @Test fun windowCapabilityCrossesEngineOnceAndCannotPublishAfterClose() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val cursor = ProfileStickerAvatarCatalogCursor()
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarCatalogWindow>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarWindow(parent, picker, cursor, { true }, results::add))
                runCurrent()
                val read = f.native.windows.single()
                assertSame(picker, read.picker); assertSame(cursor, read.cursor)
                assertTrue(engine.closeSettingsProfileStickerAvatar(parent, picker)); runCurrent()
                assertEquals(SettingsError.NOT_READY, results.single().error)
                read.done(SettingsResult(ProfileStickerAvatarCatalogWindow.Candidates(f.native.candidatePage())))
                read.done(SettingsResult(error = SettingsError.FORBIDDEN)); runCurrent()
                assertEquals(1, results.size)
                assertTrue(f.native.writes.isEmpty())
            }
        }
    }

    @Test fun readyEngineRoutesFullCatalogSelectionAndWorkerSaveReceipt() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                assertSame(picker, f.native.opens.single().picker)
                assertEquals(43L, f.native.opens.single().userId)

                val defaults = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, defaults::add))
                assertTrue(f.native.defaultReads.isEmpty())
                runCurrent()
                val defaultRead = f.native.defaultReads.single()
                defaultRead.done(SettingsResult(f.native.candidatePage()))
                defaultRead.done(SettingsResult(error = SettingsError.FORBIDDEN))
                assertTrue(defaults.isEmpty())
                runCurrent()
                assertSame(f.native.candidate, defaults.single().value?.candidates?.single()?.ticket)

                val packs = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarPacks(parent, picker,
                    ProfileStickerAvatarPackKind.CUSTOM_EMOJI, { true }, packs::add))
                runCurrent()
                val packRead = f.native.packLists.single()
                assertEquals(ProfileStickerAvatarPackKind.CUSTOM_EMOJI, packRead.kind)
                packRead.done(SettingsResult(f.native.packPage())); runCurrent()
                assertSame(f.native.pack, packs.single().value?.packs?.single()?.ticket)

                val opened = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.openSettingsProfileStickerAvatarPack(parent, picker, f.native.pack, { true }, opened::add))
                runCurrent()
                val pack = f.native.packReads.single()
                assertSame(f.native.pack, pack.pack)
                pack.done(SettingsResult(f.native.candidatePage())); runCurrent()
                assertSame(f.native.candidate, opened.single().value?.candidates?.single()?.ticket)
                val selection = select(engine, parent, picker, f.native.candidate)
                assertSame(selection, f.native.selections.single().selection)

                val background = background(1, 0xFEDCBA, 3)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                var receiptBeforeObserver = false
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background, { true },
                    admissions::add) { outcome ->
                    receiptBeforeObserver = engine.profileMutationReceipts.snapshot().any {
                        it.key.token == parent && it.key.revision == outcome.revision &&
                            it.key.operation == SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR && it.result == SettingsResult(Unit)
                    }
                    outcomes += outcome
                })
                assertTrue(admissions.isEmpty())
                assertTrue(f.native.writes.isEmpty())
                runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                assertSame(picker, write.picker)
                assertSame(selection, write.selection)
                assertEquals(91L, write.expectedPhotoId)
                assertEquals(listOf(1, 0xFEDCBA, 3), write.background.colors())
                assertTrue(write.active())
                val before = engine.state.value.settings.profileExtras
                assertEquals(revision, before.startedRevision)
                assertEquals(SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR, before.operation)
                assertTrue(before.busy)
                onWorker { write.done(SettingsResult(Unit)); write.done(SettingsResult(error = SettingsError.FORBIDDEN)) }
                assertEquals(before, engine.state.value.settings.profileExtras)
                assertTrue(receiptBeforeObserver)
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                runCurrent()
                val after = engine.state.value.settings.profileExtras
                assertEquals(revision, after.acceptedRevision)
                assertEquals(revision, after.completedRevision)
                assertFalse(after.busy)
            }
        }
    }

    @Test fun readyAndMandatoryGatesRejectImmediatelyAndAfterQueueAdmission() {
        runTest {
            for (mandatory in listOf(false, true)) for (queued in listOf(false, true)) {
                withEngine { engine, f ->
                    val parent = openExtras(engine)
                    val results = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
                    if (!queued) f.block(mandatory)
                    val accepted = engine.openSettingsProfileStickerAvatar(parent, { true }, results::add)
                    if (queued) {
                        assertTrue(accepted)
                        assertTrue(results.isEmpty())
                        f.block(mandatory)
                        runCurrent()
                    } else assertFalse(accepted)
                    assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
                    assertTrue(f.native.opens.isEmpty())
                    runCurrent()
                    assertEquals(1, results.size)
                }
            }
        }
    }

    @Test fun staleCapabilitiesAndOneWayCallerLeaseAreRejectedAtTheirActualBoundaries() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val stale = parent.copy(owner = parent.owner + 1)
                val old = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
                assertFalse(engine.openSettingsProfileStickerAvatar(stale, { true }, old::add))
                assertEquals(SettingsError.NOT_READY, old.single().error)

                val wrongPicker = ProfileStickerAvatarPickerTicket()
                val invalid = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, wrongPicker, { true }, invalid::add))
                runCurrent()
                assertEquals(SettingsError.NOT_READY, invalid.single().error)
                assertTrue(f.native.defaultReads.isEmpty())

                val inactive = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertFalse(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { false }, inactive::add))
                assertEquals(SettingsError.NOT_READY, inactive.single().error)
                val lease = AtomicBoolean(true)
                val queued = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, lease::get, queued::add))
                lease.set(false); runCurrent(); lease.set(true)
                assertEquals(SettingsError.NOT_READY, queued.single().error)
                assertTrue(f.native.defaultReads.isEmpty())

                val inactiveSave = mutableListOf<Long?>()
                val noResult = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertFalse(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1),
                    { false }, inactiveSave::add, noResult::add))
                assertEquals(listOf<Long?>(null), inactiveSave)
                assertTrue(noResult.isEmpty())
                val admitted = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, ProfileStickerAvatarSelectionTicket(),
                    91, background(1), { true }, admitted::add, outcomes::add))
                runCurrent()
                assertEquals(listOf<Long?>(null), admitted)
                assertTrue(outcomes.isEmpty())
                assertTrue(f.native.writes.isEmpty())
            }
        }
    }

    @Test fun stopRevisionRejectsQueuedWorkBeforeTheActorCanCallNative() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, results::add))
                val admitted = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admitted::add, outcomes::add))
                assertTrue(f.native.defaultReads.isEmpty())
                assertTrue(f.native.writes.isEmpty())
                f.closeImmediately = false
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                runCurrent()
                assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
                assertEquals(listOf<Long?>(null), admitted)
                assertTrue(outcomes.isEmpty())
                assertTrue(f.native.defaultReads.isEmpty())
                assertTrue(f.native.writes.isEmpty())
                assertEquals(listOf(picker), f.native.closes)
                assertFalse(stopping.isCompleted)
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
                f.auth(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
            }
        }
    }

    @Test fun cleanupAndStopRevokeInflightReadsWithoutRevivingLateCallbacks() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val visible = AtomicBoolean(true)
                val picker = openPicker(engine, parent, visible::get)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, results::add)); runCurrent()
                val read = f.native.defaultReads.single()
                visible.set(false)
                assertTrue(engine.closeSettingsProfileStickerAvatar(parent, picker)); runCurrent()
                assertEquals(listOf(picker), f.native.closes)
                assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
                read.done(SettingsResult(f.native.candidatePage())); read.done(SettingsResult(error = SettingsError.FORBIDDEN))
                runCurrent()
                assertEquals(1, results.size)
            }
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val results = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, results::add)); runCurrent()
                val read = f.native.defaultReads.single()
                val source = f.native.opens.single().active
                assertTrue(source())
                f.closeImmediately = false
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                assertFalse(source(), "Stop revision revokes the captured native lease before actor drain")
                runCurrent()
                assertEquals(listOf(SettingsError.NOT_READY), results.map { it.error })
                assertEquals(listOf(picker), f.native.closes)
                read.done(SettingsResult(f.native.candidatePage())); read.done(SettingsResult(error = SettingsError.FORBIDDEN))
                runCurrent()
                assertEquals(1, results.size)
                f.auth(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
            }
        }
    }

    @Test fun dispatchedSaveKeepsItsRawResultAndReceiptWhenStopDiscardsActorWork() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admissions::add, outcomes::add)); runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                assertTrue(write.active())
                f.closeImmediately = false
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                assertFalse(write.active())
                runCurrent()
                val stopped = engine.state.value.settings
                assertTrue(outcomes.isEmpty())
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
                onWorker { write.done(SettingsResult(Unit)); write.done(SettingsResult(error = SettingsError.CONFLICT)) }
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                assertEquals(stopped, engine.state.value.settings)
                runCurrent()
                assertEquals(stopped, engine.state.value.settings)
                assertEquals(1, outcomes.size)
                f.auth(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
            }
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admissions::add, outcomes::add)); runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                assertTrue(engine.closeSettingsProfileStickerAvatar(parent, picker)); runCurrent()
                assertEquals(listOf(picker), f.native.closes)
                assertFalse(write.active())
                assertTrue(outcomes.isEmpty())
                onWorker { write.done(SettingsResult(Unit)) }
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                runCurrent()
                assertEquals(0L, engine.state.value.settings.profileExtras.acceptedRevision)
                assertFalse(engine.state.value.settings.profileExtras.busy)
            }
        }
    }

    @Test fun indeterminateSaveRetainsItsSlotUntilALateNativeTerminalAfterRetirement() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admissions::add, outcomes::add)); runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                val pending = engine.state.value.settings.profileExtras
                assertTrue(pending.busy)
                onWorker { write.done(SettingsResult<Unit>()) }
                assertTrue(outcomes.isEmpty())
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
                assertTrue(write.active())
                runCurrent()
                assertEquals(pending, engine.state.value.settings.profileExtras)
                f.closeImmediately = false
                val stopping = async(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
                runCurrent()
                val stopped = engine.state.value.settings
                assertFalse(write.active())
                assertFalse(stopping.isCompleted)
                onWorker { write.done(SettingsResult(Unit)); write.done(SettingsResult(error = SettingsError.CONFLICT)) }
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                assertEquals(stopped, engine.state.value.settings)
                runCurrent()
                assertEquals(stopped, engine.state.value.settings)
                assertEquals(1, f.native.writes.size)
                assertEquals(1, outcomes.size)
                f.auth(AuthorizationPhase.CLOSED); runCurrent()
                assertTrue(stopping.await())
            }
        }
    }

    @Test fun synchronousNativeResultCanPrecedeAdmissionAndBothCallbacksRemainOnce() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                f.native.onSave = { done -> done(SettingsResult(Unit)); done(SettingsResult(error = SettingsError.FORBIDDEN)) }
                val order = mutableListOf<String>()
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    { order += "admitted"; admissions += it }, { order += "result"; outcomes += it }))
                assertTrue(order.isEmpty())
                runCurrent()
                assertEquals(listOf("result", "admitted"), order)
                val revision = assertNotNull(admissions.single())
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                assertEquals(revision, engine.state.value.settings.profileExtras.acceptedRevision)
            }
        }
    }

    @Test fun busySaveRejectsOldCatalogActionsWithoutCancellingItsNativeLease() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                val admissions = mutableListOf<Long?>()
                val outcomes = mutableListOf<ProfileStickerAvatarMutationResult>()
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    admissions::add, outcomes::add)); runCurrent()
                val revision = assertNotNull(admissions.single())
                val write = f.native.writes.single()
                assertTrue(write.active())
                val defaults = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                val packs = mutableListOf<SettingsResult<ProfileStickerAvatarPackPage>>()
                val opened = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                val selected = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
                val pickers = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, defaults::add))
                assertTrue(engine.loadSettingsProfileStickerAvatarPacks(parent, picker, ProfileStickerAvatarPackKind.REGULAR,
                    { true }, packs::add))
                assertTrue(engine.openSettingsProfileStickerAvatarPack(parent, picker, f.native.pack, { true }, opened::add))
                assertTrue(engine.selectSettingsProfileStickerAvatar(parent, picker, f.native.candidate, { true }, selected::add))
                assertTrue(engine.openSettingsProfileStickerAvatar(parent, { true }, pickers::add))
                runCurrent()
                assertEquals(SettingsError.NOT_READY, defaults.single().error)
                assertEquals(SettingsError.NOT_READY, packs.single().error)
                assertEquals(SettingsError.NOT_READY, opened.single().error)
                assertEquals(SettingsError.NOT_READY, selected.single().error)
                assertEquals(SettingsError.NOT_READY, pickers.single().error)
                assertTrue(f.native.defaultReads.isEmpty())
                assertTrue(f.native.packLists.isEmpty())
                assertTrue(f.native.packReads.isEmpty())
                assertEquals(1, f.native.selections.size)
                assertEquals(1, f.native.opens.size)
                assertTrue(f.native.closes.isEmpty())
                assertTrue(write.active())
                assertTrue(outcomes.isEmpty())
                assertTrue(engine.profileMutationReceipts.snapshot().isEmpty())
                write.done(SettingsResult(Unit)); runCurrent()
                assertEquals(listOf(ProfileStickerAvatarMutationResult(parent, revision, null)), outcomes)
                assertEquals(revision, engine.state.value.settings.profileExtras.acceptedRevision)
            }
        }
    }

    @Test fun throwingUserCallbacksDoNotInterruptReceiptOrLaterActorWork() {
        runTest {
            withEngine { engine, f ->
                val parent = openExtras(engine)
                val picker = openPicker(engine, parent)
                val selection = select(engine, parent, picker, f.native.candidate)
                var admitted: Long? = null
                var admittedCalls = 0
                var resultCalls = 0
                assertTrue(engine.saveSettingsProfileStickerAvatar(parent, picker, selection, 91, background(1), { true },
                    { admitted = it; admittedCalls++; error("synthetic admission failure") },
                    { resultCalls++; error("synthetic observer failure") }))
                runCurrent()
                val revision = assertNotNull(admitted)
                assertEquals(1, admittedCalls)
                onWorker { f.native.writes.single().done(SettingsResult(Unit)) }
                assertEquals(1, resultCalls)
                assertReceipt(engine, parent, revision, SettingsResult(Unit))
                runCurrent()
                assertEquals(revision, engine.state.value.settings.profileExtras.acceptedRevision)
                val next = mutableListOf<SettingsResult<ProfileStickerAvatarCandidatePage>>()
                assertTrue(engine.loadSettingsProfileStickerAvatarDefault(parent, picker, { true }, next::add)); runCurrent()
                f.native.defaultReads.single().done(SettingsResult(f.native.candidatePage())); runCurrent()
                assertNotNull(next.single().value)
                assertEquals(1, admittedCalls)
                assertEquals(1, resultCalls)
            }
        }
    }

    private suspend fun TestScope.withEngine(block: suspend TestScope.(TdEngine, Fixture) -> Unit) {
        val f = Fixture()
        val engine = syntheticOpenEngine(f.store, f, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(engine.start()); runCurrent()
            f.auth(AuthorizationPhase.WAITING_PARAMETERS); runCurrent()
            f.auth(AuthorizationPhase.READY); runCurrent()
            assertTrue(engine.loadSettings(engine.state.value.settings.token, SettingsSection.PROFILE)); runCurrent()
            assertEquals(43L, engine.state.value.settings.profile?.userId)
            block(engine, f)
        } finally {
            f.closeImmediately = true
            if (f.closeCalls > 0 && !f.closed) f.auth(AuthorizationPhase.CLOSED)
            val close = async { engine.close() }
            runCurrent()
            val closed = close.await()
            if (closed) f.delete()
            assertTrue(closed)
        }
    }

    private fun TestScope.openExtras(engine: TdEngine): SettingsProfileExtrasToken {
        assertTrue(engine.openSettingsProfileExtras(engine.state.value.settings.token, 43)); runCurrent()
        val page = engine.state.value.settings.profileExtras
        assertEquals(43L, page.token.userId)
        assertEquals(91L, page.data?.photoId)
        assertFalse(page.loading)
        return page.token
    }

    private fun TestScope.openPicker(engine: TdEngine, parent: SettingsProfileExtrasToken,
        active: () -> Boolean = { true },
    ): ProfileStickerAvatarPickerTicket {
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarPickerTicket>>()
        assertTrue(engine.openSettingsProfileStickerAvatar(parent, active, results::add)); runCurrent()
        return assertNotNull(results.single().value)
    }

    private fun TestScope.select(engine: TdEngine, parent: SettingsProfileExtrasToken,
        picker: ProfileStickerAvatarPickerTicket, candidate: ProfileStickerAvatarCandidateTicket,
    ): ProfileStickerAvatarSelectionTicket {
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarSelectionTicket>>()
        assertTrue(engine.selectSettingsProfileStickerAvatar(parent, picker, candidate, { true }, results::add)); runCurrent()
        return assertNotNull(results.single().value)
    }

    private fun assertReceipt(engine: TdEngine, parent: SettingsProfileExtrasToken, revision: Long,
        result: SettingsResult<Unit>,
    ) {
        val receipt = engine.profileMutationReceipts.snapshot().single()
        assertEquals(parent, receipt.key.token)
        assertEquals(revision, receipt.key.revision)
        assertEquals(SettingsProfileExtrasOperation.UPLOAD_STICKER_AVATAR, receipt.key.operation)
        assertEquals(result, receipt.result)
    }

    private fun onWorker(action: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "profile-sticker-engine-worker") {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { completed.countDown() }
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
    }

    private class Fixture : TdTransport {
        val root: Path = Files.createTempDirectory("nnggram-profile-sticker-engine-").toRealPath()
        val store = ConfigurationStore(root.resolve("tdlib.properties"), root.resolve("data"))
        val gate = TdUserInteractionGate()
        val ready = AtomicBoolean(false)
        val native = FakeAvatar()
        val settings = FakeSettings(FakeExtras(native))
        var closeImmediately = true
        var closeCalls = 0
        var closed = false
        private lateinit var authorization: (AuthorizationDetails) -> Unit
        init { Files.writeString(store.path, "api_id=12345\napi_hash=${"a".repeat(32)}\n") }

        fun auth(phase: AuthorizationPhase) {
            ready.set(phase == AuthorizationPhase.READY)
            if (phase == AuthorizationPhase.CLOSED) closed = true
            authorization(AuthorizationDetails(phase))
        }
        fun block(mandatory: Boolean) {
            if (mandatory) gate.update(LoginEmailSuggestions(1, requiredPresent = true, requiredEpoch = 1))
            else ready.set(false)
        }
        override fun load() = NativeInfo("synthetic", "synthetic", "synthetic")
        override fun create(onAuthorization: (AuthorizationDetails) -> Unit, onConnection: (ConnectionPhase) -> Unit,
            onError: () -> Unit, onChatEvent: (ChatEvent) -> Unit,
        ): TdSession {
            authorization = onAuthorization
            return object : SyntheticOpenKeySession {
                override fun authorizationReady() = ready.get()
                override val interactionGate = gate
                override val settings: TdSettingsSession = this@Fixture.settings
                override fun setParameters(config: TdConfiguration, directories: DataDirectoryLease, result: (Int?) -> Unit) { result(null) }
                override fun configureProxy(proxy: ProxyConfiguration?, result: (Int?) -> Unit) { result(null) }
                override fun submitLogin(kind: LoginRequestKind, value: String, result: (LoginResult) -> Unit) = error("No login in sticker tests")
                override fun close() { closeCalls++; if (closeImmediately) auth(AuthorizationPhase.CLOSED) }
            }
        }

        fun delete() {
            val parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            val checked = root.toRealPath()
            check(checked.parent == parent && checked.fileName.toString().startsWith("nnggram-profile-sticker-engine-"))
            Files.walk(checked).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    check(path.toAbsolutePath().normalize().startsWith(checked))
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private class FakeAvatar : TdProfileStickerAvatarSession {
        data class Open(val userId: Long, val active: () -> Boolean, val picker: ProfileStickerAvatarPickerTicket)
        data class CandidateRead(val picker: ProfileStickerAvatarPickerTicket, val done: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
        data class PackList(val picker: ProfileStickerAvatarPickerTicket, val kind: ProfileStickerAvatarPackKind,
            val done: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit)
        data class PackRead(val picker: ProfileStickerAvatarPickerTicket, val pack: ProfileStickerAvatarPackTicket,
            val done: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit)
        data class WindowRead(val picker: ProfileStickerAvatarPickerTicket, val cursor: ProfileStickerAvatarCatalogCursor,
            val done: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit)
        data class Selection(val picker: ProfileStickerAvatarPickerTicket, val candidate: ProfileStickerAvatarCandidateTicket,
            val selection: ProfileStickerAvatarSelectionTicket)
        data class Write(val picker: ProfileStickerAvatarPickerTicket, val selection: ProfileStickerAvatarSelectionTicket,
            val expectedPhotoId: Long?, val background: ProfileStickerAvatarBackground, val active: () -> Boolean,
            val done: (SettingsResult<Unit>) -> Unit)
        val candidate = ProfileStickerAvatarCandidateTicket()
        val pack = ProfileStickerAvatarPackTicket()
        val opens = mutableListOf<Open>()
        val closes = mutableListOf<ProfileStickerAvatarPickerTicket>()
        val defaultReads = mutableListOf<CandidateRead>()
        val packLists = mutableListOf<PackList>()
        val packReads = mutableListOf<PackRead>()
        val windows = mutableListOf<WindowRead>()
        val selections = mutableListOf<Selection>()
        val writes = mutableListOf<Write>()
        var onSave: (((SettingsResult<Unit>) -> Unit) -> Unit)? = null

        override fun open(userId: Long, active: () -> Boolean): ProfileStickerAvatarPickerTicket =
            ProfileStickerAvatarPickerTicket().also { opens += Open(userId, active, it) }
        override fun close(ticket: ProfileStickerAvatarPickerTicket) { closes += ticket }
        override fun retire() {}
        override fun loadDefault(ticket: ProfileStickerAvatarPickerTicket, result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit) {
            defaultReads += CandidateRead(ticket, result)
        }
        override fun loadInstalledPacks(ticket: ProfileStickerAvatarPickerTicket, kind: ProfileStickerAvatarPackKind,
            result: (SettingsResult<ProfileStickerAvatarPackPage>) -> Unit,
        ) { packLists += PackList(ticket, kind, result) }
        override fun openPack(ticket: ProfileStickerAvatarPickerTicket, packTicket: ProfileStickerAvatarPackTicket,
            result: (SettingsResult<ProfileStickerAvatarCandidatePage>) -> Unit,
        ) { packReads += PackRead(ticket, packTicket, result) }
        override fun loadWindow(ticket: ProfileStickerAvatarPickerTicket, cursor: ProfileStickerAvatarCatalogCursor,
            result: (SettingsResult<ProfileStickerAvatarCatalogWindow>) -> Unit,
        ) { windows += WindowRead(ticket, cursor, result) }
        override fun select(ticket: ProfileStickerAvatarPickerTicket,
            candidateTicket: ProfileStickerAvatarCandidateTicket,
        ): ProfileStickerAvatarSelectionTicket = ProfileStickerAvatarSelectionTicket().also {
            selections += Selection(ticket, candidateTicket, it)
        }
        override fun save(ticket: ProfileStickerAvatarPickerTicket, selectionTicket: ProfileStickerAvatarSelectionTicket,
            expectedPhotoId: Long?, background: ProfileStickerAvatarBackground, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ) {
            // This fake models the final native save reply, not a preflight/read result.
            // Merely invoking an unmarked callback is no longer native receipt evidence.
            val nativeResult: (SettingsResult<Unit>) -> Unit = { response ->
                val receipt = when {
                    response.value != null && response.error == null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                    response.error != null -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, response.error)
                    else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                }
                SettingsSpecialMutationContext.withReceipt(receipt) { result(response) }
            }
            writes += Write(ticket, selectionTicket, expectedPhotoId, background, active, nativeResult)
            onSave?.invoke(nativeResult)
        }
        fun candidatePage() = ProfileStickerAvatarCandidatePage(listOf(ProfileStickerAvatarCandidate(candidate,
            ProfileStickerAvatarIdentity.CustomEmoji(-73), 0, 0, 100, 100, true)), false)
        fun packPage() = ProfileStickerAvatarPackPage(listOf(ProfileStickerAvatarPack(pack, "synthetic", 1,
            ProfileStickerAvatarPackKind.CUSTOM_EMOJI)), false)
    }

    private class FakeExtras(override val stickerAvatar: TdProfileStickerAvatarSession) : TdSettingsProfileExtrasSession {
        override fun load(userId: Long, isCurrent: () -> Boolean, result: (SettingsResult<SettingsProfileExtrasData>) -> Unit) {
            result(SettingsResult(SettingsProfileExtrasData(userId, 91, null, SettingsCalendarDate(27, 10, 2026))))
        }
        override fun setBirthdate(userId: Long, expected: SettingsBirthdate?, desired: SettingsBirthdate?,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun uploadPhoto(userId: Long, expectedPhotoId: Long?, photo: SettingsPreparedProfilePhoto,
            isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit,
        ) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
        override fun removePhoto(userId: Long, photoId: Long, isCurrent: () -> Boolean, result: (SettingsResult<Unit>) -> Unit) {
            result(SettingsResult(error = SettingsError.UNSUPPORTED))
        }
    }

    private class FakeSettings(override val profileExtras: FakeExtras) : TdSettingsSession {
        override fun profile(result: (SettingsResult<SettingsProfile>) -> Unit) {
            result(SettingsResult(SettingsProfile(43, "Synthetic", "", "", "", "")))
        }
        override fun saveProfile(edit: SettingsProfileEdit, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun notifications(result: (SettingsResult<Map<NotificationScope, NotificationPreferences>>) -> Unit) = unsupported(result)
        override fun saveNotifications(scope: NotificationScope, value: NotificationPreferences, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun privacy(result: (SettingsResult<SettingsPrivacyData>) -> Unit) = unsupported(result)
        override fun savePrivacy(key: PrivacyKey, base: PrivacyBase, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun setAccountTtl(days: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun terminateSession(id: Long, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun unblock(sender: SettingsBlockedSender, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun advanced(result: (SettingsResult<SettingsAdvancedData>) -> Unit) = unsupported(result)
        override fun optimizeStorage(result: (SettingsResult<SettingsStorage>) -> Unit) = unsupported(result)
        override fun addProxy(draft: SettingsProxyDraft, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun enableProxy(id: Int?, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        override fun removeProxy(id: Int, result: (SettingsResult<Unit>) -> Unit) = unsupported(result)
        private fun <T> unsupported(result: (SettingsResult<T>) -> Unit) { result(SettingsResult(error = SettingsError.UNSUPPORTED)) }
    }

    private companion object {
        fun background(vararg values: Int) = checkNotNull(ProfileStickerAvatarBackground.fromColors(values.toList()))
    }
}
