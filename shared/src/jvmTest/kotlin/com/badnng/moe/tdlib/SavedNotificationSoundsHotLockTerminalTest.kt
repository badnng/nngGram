package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.notifications.NotificationSoundLoad
import com.badnng.moe.notifications.NotificationSoundPreview
import com.badnng.moe.notifications.NotificationSoundVolumeStore
import com.badnng.moe.settings.*
import kotlin.test.*

class SavedNotificationSoundsHotLockTerminalTest {
    @Test fun oldUploadSuccessAndErrorReleaseTheirOwnLeaseExactlyOnceAndRevalidateOnlyTheReplacementOwner() {
        for (accepted in listOf(true, false)) {
            val f = Fixture(); f.open(); f.loaded(data()); f.upload()
            val pending = f.session.adds.single(); val old = f.snapshot.token
            f.epoch++; f.allowed = false; f.act { close(old) }; f.allowed = true; f.open()
            assertFalse(pending.current()); assertTrue(f.snapshot.saving)
            val value = SavedNotificationSoundMutationResult(accepted, if (accepted) sound(99) else null,
                error = if (accepted) SettingsError.NOT_READY else SettingsError.FORBIDDEN,
                receipt = if (accepted) NotificationSettingsWriteReceipt.ACCEPTED else NotificationSettingsWriteReceipt.REJECTED)
            pending.reply(value); pending.reply(value)
            assertEquals(1, f.cleanup); f.drain()
            assertFalse(f.snapshot.saving); assertTrue(f.snapshot.loading)
            assertEquals(2, f.session.loads.size); assertEquals(1, f.session.adds.size)
            assertEquals(if (accepted) 1L else 0L, f.snapshot.acceptedRevision)
            assertNull(f.snapshot.data, "The old catalog must not populate a replacement page.")
            f.leases.releaseAll(); assertEquals(1, f.cleanup)
        }
    }

    @Test fun indeterminateUploadRetainsFileAndMutationUntilLateTerminalOrConfirmedClosed() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload()
        val pending = f.session.adds.single()
        pending.reply(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED,
            receipt = NotificationSettingsWriteReceipt.INDETERMINATE)); f.drain()
        assertEquals(0, f.cleanup); assertTrue(f.snapshot.saving); assertTrue(f.snapshot.readbackPending)
        f.epoch++; f.act { close(f.snapshot.token) }; f.open()
        assertEquals(1, f.session.loads.size); assertTrue(f.snapshot.saving)
        pending.reply(SavedNotificationSoundMutationResult(error = SettingsError.FORBIDDEN,
            receipt = NotificationSettingsWriteReceipt.REJECTED)); f.drain()
        assertEquals(1, f.cleanup); assertFalse(f.snapshot.saving); assertEquals(2, f.session.loads.size)
        f.leases.releaseAll(); assertEquals(1, f.cleanup)
        val never = Fixture(); never.open(); never.loaded(data()); never.upload()
        never.session.adds.single().reply(SavedNotificationSoundMutationResult(error = SettingsError.REQUEST_FAILED,
            receipt = NotificationSettingsWriteReceipt.INDETERMINATE)); never.drain()
        never.act { deactivate() }; assertEquals(0, never.cleanup)
        never.leases.releaseAll(); never.leases.releaseAll(); assertEquals(1, never.cleanup)
    }

    @Test fun retirementNeverPublishesOldGenerationContentButActualTerminalStillReleasesItsFile() {
        val f = Fixture(); f.open(); f.loaded(data()); f.upload(); val pending = f.session.adds.single()
        f.act { deactivate() }; val last = f.snapshot
        pending.reply(SavedNotificationSoundMutationResult(true, sound(99), data(sound(99))))
        pending.reply(SavedNotificationSoundMutationResult(true, sound(99), data(sound(99)))); f.drain()
        assertEquals(last, f.snapshot); assertEquals(1, f.cleanup); assertEquals(1, f.session.loads.size)
    }

    private class Load(val current: () -> Boolean, val reply: (SettingsResult<SavedNotificationSoundsData>) -> Unit)
    private class Read(val account: Long, val id: Long, val current: () -> Boolean,
        val reply: (SettingsResult<SavedNotificationSound>) -> Unit)
    private class Write(val current: () -> Boolean, val reply: (SavedNotificationSoundMutationResult) -> Unit,
        val account: Long = 100)
    private class Session : TdSavedNotificationSoundsSession {
        var observer: (() -> Unit)? = null
        var throwLoad = false; var throwAdd = false
        var currentNativeRevision = 0L
        val loads = mutableListOf<Load>(); val reads = mutableListOf<Read>()
        val adds = mutableListOf<Write>(); val removes = mutableListOf<Write>()
        override fun observe(observer: (() -> Unit)?) { this.observer = observer }
        override fun isCurrent(data: SavedNotificationSoundsData) = data.accountUserId == 100L &&
            data.nativeRevision > 0 && data.nativeRevision == currentNativeRevision
        override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) {
            if (throwLoad) error("synthetic")
            loads += Load(active, result)
        }
        override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean,
            result: (SettingsResult<SavedNotificationSound>) -> Unit,
        ) { reads += Read(accountUserId, soundId, active, result) }
        override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload,
            active: () -> Boolean, result: (SavedNotificationSoundMutationResult) -> Unit,
        ) {
            if (throwAdd) error("synthetic")
            adds += Write(active, result, expected.accountUserId)
        }
        override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit,
        ) { removes += Write(active, result, accountUserId) }
    }
    private class Preview : NotificationSoundPreview {
        class Play(val current: () -> Boolean, val volume: Int, val reply: (SettingsResult<Boolean>) -> Unit) {
            var closed = 0
        }
        val loads = mutableListOf<Play>(); var closed = 0; var throwPlay = false
        override fun play(accountUserId: Long, soundId: Long, volumePercent: Int, active: () -> Boolean,
            result: (SettingsResult<Boolean>) -> Unit,
        ): NotificationSoundLoad {
            if (throwPlay) error("synthetic")
            val play = Play(active, volumePercent, result); loads += play
            return NotificationSoundLoad { play.closed++ }
        }
        override fun close() { closed++ }
    }
    private class Warmer {
        class Warm(val account: Long, val sound: SavedNotificationSound, val current: () -> Boolean,
            val reply: (SettingsResult<Unit>) -> Unit,
        ) { var closed = 0 }
        val loads = mutableListOf<Warm>()
        var alive = 0; var peakAlive = 0; var synchronous = false; var throwWarm = false
        fun warm(account: Long, sound: SavedNotificationSound, active: () -> Boolean,
            result: (SettingsResult<Unit>) -> Unit,
        ): NotificationSoundLoad {
            if (throwWarm) error("synthetic")
            val request = Warm(account, sound, active, result); loads += request
            alive++; peakAlive = maxOf(peakAlive, alive)
            if (synchronous) result(SettingsResult(Unit))
            return NotificationSoundLoad { request.closed++; alive-- }
        }
    }
    private class Volumes : NotificationSoundVolumeStore {
        val reads = mutableListOf<Pair<Long, NotificationSoundVolumeTarget>>()
        val saves = mutableListOf<Pair<NotificationSoundVolumeReading, Int?>>()
        private val values = mutableMapOf<Pair<Long, NotificationSoundVolumeTarget>, Int>()
        private var revision = 1L
        var inside = false; var throwRead = false; var throwSave = false
        var failure: SettingsError? = null
        var onRead: () -> Unit = {}
        var readingTransform: (NotificationSoundVolumeReading) -> NotificationSoundVolumeReading = { it }
        override fun read(accountUserId: Long, target: NotificationSoundVolumeTarget): SettingsResult<NotificationSoundVolumeReading> {
            inside = true
            try {
                if (throwRead) error("synthetic")
                reads += accountUserId to target; onRead()
                return failure?.let { SettingsResult(error = it) } ?: SettingsResult(readingTransform(
                    NotificationSoundVolumeReading(accountUserId, target, values[accountUserId to target], revision)))
            } finally { inside = false }
        }
        override fun save(expected: NotificationSoundVolumeReading, overridePercent: Int?): SettingsResult<NotificationSoundVolumeReading> {
            inside = true
            try {
                if (throwSave) error("synthetic")
                saves += expected to overridePercent
                failure?.let { return SettingsResult(error = it) }
                val key = expected.accountUserId to expected.target
                if (expected.revision != revision || expected.overridePercent != values[key])
                    return SettingsResult(error = SettingsError.CONFLICT)
                if (overridePercent == null) values.remove(key) else values[key] = overridePercent
                revision++
                return SettingsResult(NotificationSoundVolumeReading(expected.accountUserId, expected.target, overridePercent, revision))
            } finally { inside = false }
        }
        override fun resolve(accountUserId: Long, chatId: Long, scope: NotificationScope, globalVolume: Int): SettingsResult<Int> =
            SettingsResult(error = SettingsError.UNSUPPORTED)
    }
    private class Fixture(warmEnabled: Boolean = false, inlineCallbacks: Boolean = false,
        volumeStore: NotificationSoundVolumeStore? = null,
    ) {
        val leases = AttachmentSendLeases()
        var epoch = 0L
        var allowed = true; var page = true; var invalidations = 0; var cleanup = 0; var volumeChanges = 0
        val queue = ArrayDeque<() -> Unit>()
        private var currentActor = false
        val session = Session(); val preview = Preview(); val warmer = Warmer()
        var snapshot = SavedNotificationSoundsSnapshot()
        val controller = SavedNotificationSoundsController(8, session, preview,
            { if (inlineCallbacks && currentActor) it() else queue.addLast(it) },
            { check(currentActor); snapshot = it }, { allowed }, { invalidations++ },
            warmSound = { account, sound, active, result ->
                if (warmEnabled) warmer.warm(account, sound, active, result)
                else { result(SettingsResult(Unit)); NotificationSoundLoad {} }
            }, soundVolumes = volumeStore, soundVolumeChanged = { volumeChanges++ },
            terminal = SettingsSpecialMutationBoundary(queue::addLast,
                authority = { val captured = epoch; { epoch == captured && allowed } }))
        fun act(action: SavedNotificationSoundsController.() -> Unit) {
            queue.addLast { controller.action() }; drain()
        }
        fun drain() {
            check(!currentActor); currentActor = true
            try { while (queue.isNotEmpty()) queue.removeFirst().invoke() } finally { currentActor = false }
        }
        fun open() = act { open { page } }
        fun loaded(data: SavedNotificationSoundsData?, error: SettingsError? = null) {
            session.loads.last().reply(SettingsResult(data, error)); drain()
        }
        fun upload(valid: Boolean = true, expectedRevision: Long = snapshot.revision) {
            val lease = ownedAttachmentLease { cleanup++ }
            val retained = checkNotNull(leases.retain(lease))
            val prepared = SettingsPreparedNotificationSound(PreparedNotificationSoundUpload(
                "C:/synthetic/notification.mp3", 512, 1000, if (valid) "a".repeat(64) else ""), lease)
            lease.release()
            act { upload(snapshot.token, expectedRevision, prepared, retained) }
        }
    }
    private companion object {
        fun sound(id: Long = 12) = SavedNotificationSound(id, 1, 1, "synthetic", "",
            MediaFile(id = 7, uniqueId = "fixture-file-$id"))
        fun data(vararg sounds: SavedNotificationSound = arrayOf(sound())) = SavedNotificationSoundsData(
            100, sounds.toList(), NotificationSoundLimits(1024, 3, 10))
    }
}
