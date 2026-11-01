package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.*

class SettingsProfileColorsHotLockControllerTest {
    @Test fun queuedActualReceiptSettlesWhileLockedWithoutPublishingOrContinuing() {
        listOf(SettingsProfileColorMutationResult(true),
            SettingsProfileColorMutationResult(error = SettingsError.RATE_LIMITED)).forEach { response ->
            val f = Fixture().apply { loaded() }
            f.controller.save(f.snapshot.token, f.edit())
            f.session.writes.single().done(response) // already queued before synchronous hot lock
            f.allowed = false
            val before = f.snapshot
            f.drain()
            assertEquals(before, f.snapshot)
            assertEquals(1, f.session.reads.size)
            assertEquals(1, f.session.writes.size)
            f.allowed = true
            f.controller.close(f.snapshot.token)
            f.controller.open(43)
            assertFalse(f.snapshot.busy)
            assertEquals(2, f.session.reads.size)
            assertEquals(1, f.session.writes.size)
        }
    }

    @Test fun newOwnerWaitsForPhysicalTerminalAndRelockPreventsItsReadback() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.controller.close(f.snapshot.token)
        f.controller.open(43)
        assertTrue(f.snapshot.busy)
        f.session.writes.single().done(SettingsProfileColorMutationResult(true))
        f.allowed = false
        val before = f.snapshot
        f.drain()
        assertEquals(before, f.snapshot)
        assertEquals(1, f.session.reads.size)
        f.allowed = true
        f.controller.close(f.snapshot.token)
        f.controller.open(43)
        assertFalse(f.snapshot.busy)
        assertEquals(2, f.session.reads.size)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun retiredGenerationCannotReadOrPublishItsPhysicalAcceptance() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        f.controller.deactivate()
        val before = f.snapshot
        f.complete(SettingsProfileColorMutationResult(true, f.data))
        assertEquals(before, f.snapshot)
        assertEquals(1, f.session.reads.size)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun indeterminateFinalWriteIsPendingWithoutClaimingAcceptanceOrRetrying() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        SettingsSpecialMutationContext.write(org.drinkless.tdlib.TdApi.SetAccentColor(2, 0),
            { _, _, failed -> failed() },
            { error("No native response was supplied") },
            { f.session.writes.single().done(SettingsProfileColorMutationResult(error = SettingsError.NOT_READY)) })
        f.drain()
        assertTrue(f.snapshot.busy) // unknown does not release the physical slot
        assertEquals(setOf(SettingsProfileColorPart.NAME), f.snapshot.readbackPending)
        assertFalse(f.snapshot.outcomes.getValue(SettingsProfileColorPart.NAME).locallyAccepted)
        f.controller.save(f.snapshot.token, f.edit())
        assertEquals(1, f.session.writes.size)
        f.controller.refresh(f.snapshot.token)
        assertEquals(1, f.session.reads.size)
        f.complete(SettingsProfileColorMutationResult(error = SettingsError.RATE_LIMITED))
        assertFalse(f.snapshot.busy)
        assertTrue(f.snapshot.readbackPending.isEmpty())
        assertEquals(SettingsError.RATE_LIMITED, f.snapshot.error)
        assertEquals(1, f.session.writes.size)
    }

    @Test fun changedAuthorityCannotContinueOldWriteAndFreshOwnerOnlyReads() {
        val f = Fixture().apply { loaded() }
        f.controller.save(f.snapshot.token, f.edit())
        val request = f.session.writes.single()
        f.epoch++
        assertFalse(request.active())
        f.controller.close(f.snapshot.token)
        f.controller.open(43)
        val current = f.snapshot.token
        f.complete(SettingsProfileColorMutationResult(true))
        assertEquals(current, f.snapshot.token)
        assertTrue(f.snapshot.outcomes.isEmpty())
        assertFalse(f.snapshot.busy)
        assertEquals(2, f.session.reads.size)
        assertEquals(1, f.session.writes.size)
    }

    private class Fixture {
        val data = fixtureData()
        val session = FakeSession()
        val queue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        private val actorThread = Thread.currentThread()
        var allowed = true
        var epoch = 0L
        var snapshot = SettingsProfileColorsSnapshot()
        val controller = SettingsProfileColorsController(9, session, { queue.add(it) }, { snapshot = it }, {
            check(Thread.currentThread() === actorThread) { "Actor gate accessed from native worker" }
            allowed
        }, terminal = SettingsSpecialMutationBoundary({ queue.add(it) }, authority = {
            val captured = epoch;
            { captured == epoch }
        }))
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
