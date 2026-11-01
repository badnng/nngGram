package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Fake catalog, broker native requests and scheduling only; no filesystem reads, client or audio. */
class OfficialTdNotificationSoundFilesTest {
    @Test fun completeNativeBytesUseTheActualFileIdConfirmedPrefixesAndAFinalFreshCatalogIdentity() {
        val f = Fixture(soundId = Long.MIN_VALUE); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 64, { true }, results::add)
        assertEquals(listOf(1L to Long.MIN_VALUE), f.catalog.calls.map { it.account to it.id })
        assertEquals(7, f.native.commands.single().fileId)
        assertTrue(f.native.reads.isEmpty()); assertTrue(results.isEmpty())
        f.accept(); assertTrue(results.isEmpty(), "Download acceptance is not complete byte evidence.")
        f.ready()
        val encoded = assertNotNull(results.single().value)
        assertContentEquals(f.native.content, encoded.bytes()); assertEquals(Long.MIN_VALUE, encoded.sound.id)
        assertEquals(7, encoded.sound.file.id); assertNull(encoded.sound.file.localPath)
        assertEquals(2, f.catalog.calls.size); assertTrue(f.native.prefixCalls >= 2)
        assertEquals(listOf(0L to 16), f.native.reads)
        val escaped = encoded.bytes(); escaped[0] = 99
        assertEquals(0, encoded.bytes()[0].toInt()); assertFalse(encoded.toString().contains("private"))
    }

    @Test fun alreadyDownloadedFilesStillGoThroughTheBrokerInsteadOfReadingTheirLocalPath() {
        val f = Fixture(downloaded = true); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.catalog.value = f.catalog.value.copy(file = f.sound.file.copy(expectedSize = Long.MAX_VALUE))
        f.files.read(1, f.sound, 64, { true }, results::add); f.clock.advance(0)
        assertContentEquals(f.native.content, results.single().value?.bytes())
        assertTrue(f.native.commands.isEmpty()); assertEquals(listOf(0L to 16), f.native.reads)
        assertNull(results.single().value?.sound?.file?.localPath)
    }

    @Test fun chunkedReadsNeverExceedConfirmedPrefixOrSixtyFourKiBAndReturnOwnedBytes() {
        val f = Fixture(size = 150_000, downloaded = true); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 200_000, { true }, results::add); f.clock.advance(0)
        assertEquals(listOf(0L to 65_536, 65_536L to 65_536, 131_072L to 18_928), f.native.reads)
        assertEquals(150_000, results.single().value?.size)
        assertContentEquals(f.native.content, results.single().value?.bytes())
    }

    @Test fun metadataMismatchesInvalidBudgetsAndKnownOversizeCannotAcquireAnyNativeDownload() {
        for (kind in 0..8) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
            when (kind) {
                0 -> f.catalog.value = f.sound.copy(id = Long.MIN_VALUE)
                1 -> f.catalog.value = f.sound.copy(file = f.sound.file.copy(id = 8))
                2 -> f.catalog.value = f.sound.copy(file = f.sound.file.copy(uniqueId = "another"))
            }
            val maximum = when (kind) { 3 -> 0; 4 -> 16 * 1024 * 1024 + 1; 5 -> 8; else -> 64 }
            val expected = when (kind) { 6 -> f.sound.copy(id = 0); 7 -> f.sound.copy(id = -1)
                8 -> f.sound.copy(file = f.sound.file.copy(uniqueId = "")); else -> f.sound }
            f.files.read(1, expected, maximum, { true }, results::add)
            assertNotNull(results.single().error); assertNull(results.single().value)
            assertTrue(f.native.commands.isEmpty()); assertTrue(f.native.reads.isEmpty())
            if (kind in 3..4) assertTrue(f.catalog.calls.isEmpty())
        }
    }

    @Test fun unknownSizeCannotDownloadBeyondTheBudgetOrTreatItsFinalPrefixAsACompleteSound() {
        val f = Fixture(size = 0); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 1000, { true }, results::add)
        assertEquals(1000L, f.native.commands.single().limit)
        f.accept(); f.native.prefixBytes = 1000
        f.clock.advance(16_000)
        assertNotNull(results.single().error); assertNull(results.single().value)
        assertEquals(1, f.native.commands.size); assertTrue(f.native.commands.all { it.offset + it.limit <= 1000 })
        assertTrue(f.native.reads.isEmpty()); assertEquals(1, f.catalog.calls.size)
    }

    @Test fun unknownSizeCanCompleteOnlyAfterNativeMetadataProvesItsActualBoundedSize() {
        val f = Fixture(size = 0); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 64, { true }, results::add); f.accept()
        f.native.content = ByteArray(16) { it.toByte() }
        f.native.file = f.native.file.copy(size = 16, expectedSize = 16)
        f.ready()
        assertEquals(16, results.single().value?.size); assertEquals(2, f.catalog.calls.size)
        assertEquals(7, f.native.commands.single().fileId)
    }

    @Test fun finalMetadataChangeOrReadFailureNeverPublishesPreviouslyOwnedBytesAsAValidSound() {
        for (changed in listOf(false, true)) {
            val f = Fixture(); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
            f.catalog.onRead = { index, call ->
                call.reply(if (index == 2 && changed) SettingsResult(f.catalog.value.copy(file = f.sound.file.copy(uniqueId = "replaced")))
                    else SettingsResult(f.catalog.value))
            }
            f.files.read(1, f.sound, 64, { true }, results::add); f.accept()
            if (!changed) f.native.readError = ChatError.REQUEST_FAILED
            f.ready()
            assertNotNull(results.single().error); assertNull(results.single().value)
        }
    }

    @Test fun closeRetireAndOldMetadataCallbacksCannotReviveADisposedOwnerOrConsumeANewLease() {
        val f = Fixture(); f.catalog.defer = true
        val old = mutableListOf<SettingsResult<NotificationSoundEncoded>>(); val fresh = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        val lease = f.files.read(1, f.sound, 64, { true }, old::add)
        val held = f.catalog.calls.single(); lease.close(); lease.close()
        f.files.read(1, f.sound, 64, { true }, fresh::add)
        held.reply(SettingsResult(f.sound)); held.reply(SettingsResult(f.sound))
        assertEquals(SettingsError.NOT_READY, old.single().error); assertTrue(f.native.commands.isEmpty())
        f.catalog.calls.last().reply(SettingsResult(f.sound)); f.accept()
        f.files.retire(); f.files.retire(); f.ready()
        assertEquals(SettingsError.NOT_READY, fresh.single().error); assertEquals(1, f.native.commands.size)
    }

    @Test fun pageLeaseCancellationAndADeadlineAlwaysSettleRequestsEvenIfMetadataNeverAnswers() {
        val f = Fixture(); f.catalog.defer = true; var active = true
        val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 64, { active }, results::add)
        active = false; f.clock.advance(100)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        f.catalog.calls.first().reply(SettingsResult(f.sound)); assertTrue(f.native.commands.isEmpty())
        val timed = Fixture(); timed.catalog.defer = true; val deadline = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        timed.files.read(1, timed.sound, 64, { true }, deadline::add); timed.clock.advance(30_000)
        assertEquals(SettingsError.REQUEST_FAILED, deadline.single().error)
        assertTrue(timed.native.commands.isEmpty())
    }

    @Test fun admissionsAreBoundedAndCatalogDuplicatesCannotStartAnotherDownload() {
        val f = Fixture(); f.catalog.defer = true
        val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        repeat(9) { f.files.read(1, f.sound, 64, { true }, results::add) }
        assertEquals(SettingsError.RATE_LIMITED, results.single().error); assertEquals(8, f.catalog.calls.size)
        val first = f.catalog.calls.first(); first.reply(SettingsResult(f.sound)); first.reply(SettingsResult(f.sound))
        assertEquals(1, f.native.commands.size)
        f.files.retire(); assertEquals(9, results.size)
    }

    @Test fun chatRetirementReleasesItsOwnLeaseAndDoesNotCancelTheSoundSharingItsNativeWindow() {
        val f = Fixture(); val chats = OfficialTdChatExtras(send = { _, _, _ -> error("No extra native boundary") }, sharedDownloads = f.broker)
        val chatOwner = MediaDownloadOwner(ChatToken(1, 2, 3), 5, MediaDownloadPurpose.USER, 100)
        chats.acquireDownload(chatOwner, f.sound.file); f.accept()
        val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        f.files.read(1, f.sound, 64, { true }, results::add)
        chats.retireDownloads(); chats.retireDownloads()
        assertTrue(f.native.cancelled.isEmpty()); assertEquals(1, f.native.commands.size)
        f.ready(); assertNotNull(results.single().value)
        assertEquals(1, f.native.commands.size)
    }

    @Test fun soundCancellationDoesNotCancelAnotherChatOwnerOrDeleteTheSharedCache() {
        val f = Fixture(); val chatResults = mutableListOf<ChatResult<MediaFile>>()
        val chats = OfficialTdChatExtras(send = { _, _, _ -> error("No extra native boundary") }, sharedDownloads = f.broker)
        chats.acquireDownload(MediaDownloadOwner(ChatToken(1, 2, 3), 5, MediaDownloadPurpose.USER, 100), f.sound.file, chatResults::add)
        f.accept(); val results = mutableListOf<SettingsResult<NotificationSoundEncoded>>()
        val lease = f.files.read(1, f.sound, 64, { true }, results::add)
        lease.close(); assertTrue(f.native.cancelled.isEmpty())
        f.ready(); assertTrue(chatResults.single().value?.downloaded == true)
        assertEquals(SettingsError.NOT_READY, results.single().error)
    }

    @Test fun officialSessionRoutesSoundOnlyFileUpdatesDirectlyAndFinalRetirementRejectsOldCallbacks() {
        val commands = CopyOnWriteArrayList<TdApi.Function<*>>()
        var ready = false
        var downloadReply: ((TdApi.Object) -> Unit)? = null
        val native = nativeSound()
        val session = OfficialTdSession(false) { command, reply, failed ->
            commands += command
            when (command) {
                is TdApi.GetMe -> reply(TdApi.User().apply { id = 1; type = TdApi.UserTypeRegular() })
                is TdApi.GetSavedNotificationSound -> reply(native)
                is TdApi.GetFileDownloadedPrefixSize -> reply(TdApi.FileDownloadedPrefixSize(if (ready) (16 - command.offset).coerceAtLeast(0) else 0))
                is TdApi.DownloadFile -> { downloadReply = reply; reply(native.sound) }
                is TdApi.ReadFilePart -> reply(TdApi.Data(ByteArray(command.count.toInt()) { it.toByte() }))
                is TdApi.CancelDownloadFile -> reply(TdApi.Ok())
                else -> failed()
            }
        }
        val result = mutableListOf<SettingsResult<NotificationSoundEncoded>>(); val complete = CountDownLatch(1)
        val expected = saved(native)
        assertNotNull(session.notificationSoundFiles).read(1, expected, 64, { true }) { synchronized(result) { result += it }; complete.countDown() }
        session.chats.retireDownloads()
        ready = true; native.sound.local.isDownloadingCompleted = true; native.sound.local.path = "must-not-read-this-private-path"
        session.acceptDownloadUpdate(TdApi.UpdateFile(native.sound))
        assertTrue(complete.await(3, TimeUnit.SECONDS))
        assertEquals(16, synchronized(result) { result.single().value?.size })
        assertEquals(1, commands.count { it is TdApi.DownloadFile })
        assertTrue(commands.filterIsInstance<TdApi.DownloadFile>().all { it.fileId == 7 })
        session.retireDownloads(); session.retireDownloads()
        val count = commands.size; downloadReply?.invoke(native.sound)
        session.acceptDownloadUpdate(TdApi.UpdateFile(native.sound))
        assertEquals(count, commands.size)
        session.close(); session.close()
        assertEquals(1, commands.count { it is TdApi.Close })
    }

    @Test fun standaloneChatBrokerObservationPrecedesThePresentationCacheCapacityFilter() {
        val observed = mutableListOf<Int>()
        val native = OfficialTdChatSession { _, _, failed -> failed() }
        val session = object : TdChatSession by native {
            override fun observeDownload(file: MediaFile) { observed += file.id }
        }
        val controller = ChatController(3, session, { it() }, {})
        controller.start()
        repeat(300) { controller.event(ChatEvent.FileUpdated(MediaFile(it + 1, 16))) }
        assertEquals((1..300).toList(), observed)
        controller.deactivate()
    }

    private class Clock {
        var now = 0L
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit) { scheduled += now + delay to action }
        fun advance(delta: Long) {
            val target = now + delta
            repeat(20_000) {
                val next = scheduled.filter { it.first <= target }.minByOrNull { it.first } ?: run { now = target; return }
                scheduled.remove(next); now = next.first; next.second()
            }
            error("Unbounded sound scheduling")
        }
    }
    private class Call(val account: Long, val id: Long, val active: () -> Boolean, val reply: (SettingsResult<SavedNotificationSound>) -> Unit)
    private class Catalog(var value: SavedNotificationSound) : TdSavedNotificationSoundsSession {
        var defer = false; var onRead: ((Int, Call) -> Unit)? = null
        val calls = mutableListOf<Call>()
        override fun read(accountUserId: Long, soundId: Long, active: () -> Boolean, result: (SettingsResult<SavedNotificationSound>) -> Unit) {
            val call = Call(accountUserId, soundId, active, result); calls += call
            if (onRead != null) onRead!!.invoke(calls.size, call) else if (!defer) result(SettingsResult(value))
        }
        override fun load(active: () -> Boolean, result: (SettingsResult<SavedNotificationSoundsData>) -> Unit) = error("No catalog scan")
        override fun add(expected: SavedNotificationSoundsData, prepared: PreparedNotificationSoundUpload, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit) = error("No upload")
        override fun remove(accountUserId: Long, expected: SavedNotificationSound, active: () -> Boolean,
            result: (SavedNotificationSoundMutationResult) -> Unit) = error("No removal")
    }
    private class Native(var file: MediaFile) : DownloadBrokerNative {
        class Command(val fileId: Int, val offset: Long, val limit: Long, val reply: (ChatResult<MediaFile>) -> Unit)
        val commands = mutableListOf<Command>(); val cancelled = mutableListOf<Int>(); val reads = mutableListOf<Pair<Long, Int>>()
        var content = ByteArray(file.size.toInt()) { it.toByte() }
        var available = file.downloaded; var prefixBytes = 0L; var prefixCalls = 0
        var readError: ChatError? = null
        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            commands += Command(fileId, offset, limit, completed)
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) {
            prefixCalls++; completed(ChatResult(if (available) (content.size.toLong() - offset).coerceAtLeast(0) else (prefixBytes - offset).coerceAtLeast(0)))
        }
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            reads += offset to count
            completed(if (readError != null) ChatResult(error = readError) else ChatResult(content.copyOfRange(offset.toInt(), offset.toInt() + count)))
        }
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) { completed(ChatResult(file)) }
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { cancelled += fileId; completed(ChatResult(Unit)) }
    }
    private class Fixture(size: Long = 16, downloaded: Boolean = false, soundId: Long = -2) {
        val clock = Clock()
        val sound = SavedNotificationSound(soundId, 1, 123, "private title", "private data",
            MediaFile(7, size, size, "must-not-read-this-private-path", downloaded, canDownload = true, uniqueId = "unique-seven"))
        val catalog = Catalog(sound); val native = Native(sound.file)
        val broker = TdDownloadBroker(native, clock::schedule, now = { clock.now })
        val files = OfficialTdNotificationSoundFiles(catalog, broker, clock::schedule) { clock.now }
        fun accept() { native.commands.last().reply(ChatResult(native.file)) }
        fun ready() {
            native.available = true; native.file = native.file.copy(downloaded = true)
            catalog.value = catalog.value.copy(file = native.file)
            broker.observe(native.file); clock.advance(0)
        }
    }
    private companion object {
        fun nativeSound() = TdApi.NotificationSound(-2, 1, 123, "synthetic", "data", TdApi.File().apply {
            id = 7; size = 16; expectedSize = 16
            local = TdApi.LocalFile().apply { canBeDownloaded = true }
            remote = TdApi.RemoteFile().apply { uniqueId = "unique-seven" }
        })
        fun saved(value: TdApi.NotificationSound) = SavedNotificationSound(value.id, value.duration, value.date, value.title, value.data, mapMediaFile(value.sound))
    }
}
