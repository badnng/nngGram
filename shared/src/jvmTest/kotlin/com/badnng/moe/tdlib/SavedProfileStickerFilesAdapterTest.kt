package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Synthetic shared core/broker only; saved-photo authority and actor wiring remain separate. */
class SavedProfileStickerFilesAdapterTest {
    @Test fun fullSavedSourceUsesTheExistingBudgetBrokerAndTwoMetadataChecks() {
        val pending = ArrayDeque<() -> Unit>()
        fun schedule(delay: Long, action: () -> Unit) { if (delay == 0L) pending.addLast(action) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst()() }
        val bytes = byteArrayOf(1, 2, 3)
        val file = MediaFile(7, size = 3, uniqueId = "native-7", downloaded = true, downloadedSize = 3)
        val native = object : DownloadBrokerNative {
            override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(file))
            override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) = completed(ChatResult(bytes.size.toLong() - offset))
            override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(file))
            override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) = completed(ChatResult(bytes.copyOfRange(offset.toInt(), offset.toInt() + count)))
            override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) = completed(ChatResult(Unit))
        }
        val broker = TdDownloadBroker(native, ::schedule, { 0L }, { schedule(0, it) })
        val budget = ProfileStickerAvatarEncodedBudget(); val readers = ProfileStickerAvatarReaderBudget()
        val builder = OfficialTdProfileStickerAvatar { _, _, failed -> failed() }
        val files = OfficialTdProfileStickerAvatarFiles(builder, broker, budget, readers, ::schedule, { 0L })
        val source = object : ProfileStickerAvatarFileSource {
            val alive = AtomicBoolean(true); var checks = 0
            override fun atomicCurrent() = alive.get()
            override fun resolve(active: () -> Boolean, terminal: () -> Unit,
                result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit): ProfileStickerAvatarMediaAdmission {
                checks++
                result(SettingsResult(ProfileStickerAvatarOwnedMedia(ProfileStickerAvatarMediaRole.FULL,
                    ProfileStickerAvatarAsset(file, MediaFormat.WEBP, 160, 160), false)))
                terminal(); return ProfileStickerAvatarMediaAdmission.ACCEPTED
            }
            override fun close() { alive.set(false) }
        }
        val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
        files.saved({ source }, { true }, results::add); drain()
        val output = assertNotNull(results.single().value)
        assertEquals(2, source.checks); assertFalse(source.atomicCurrent())
        assertTrue(output.borrow { assertContentEquals(bytes, it) }); assertTrue(budget.used() > 0)
        output.close(); drain(); assertEquals(0L, budget.used())
        files.retire(); broker.close()
    }

    @Test fun brokerLocksEveryAcceptedFileFactAndRevokesBothOwnersBeforeCallback() {
        for (change in listOf("size", "missing", "different")) {
            val scheduled = ArrayDeque<() -> Unit>()
            fun schedule(delay: Long, action: () -> Unit) { if (delay == 0L) scheduled.addLast(action) }
            fun drain() { while (scheduled.isNotEmpty()) scheduled.removeFirst()() }
            val first = MediaFile(7, size = 0, uniqueId = "native-7")
            val facts = SavedProfileStickerFacts()
            val metadata = ProfileStickerAvatarMediaFingerprint(ProfileStickerAvatarAsset(first, MediaFormat.WEBP, 160, 160), null, false)
            assertNotNull(facts.accept(metadata).value)
            var downloads = 0; var prefixes = 0; var inBroker = false; var invalidations = 0
            val sourceLive = AtomicBoolean(true)
            val native = object : DownloadBrokerNative {
                override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) { downloads++ }
                override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) { prefixes++; completed(ChatResult(0L)) }
                override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = Unit
                override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) = Unit
                override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) = completed(ChatResult(Unit))
            }
            val broker = TdDownloadBroker(native, ::schedule, { 0L }, { schedule(0, it) })
            val files = OfficialTdProfileStickerAvatarFiles(OfficialTdProfileStickerAvatar { _, _, failed -> failed() },
                broker, ProfileStickerAvatarEncodedBudget(), ProfileStickerAvatarReaderBudget(), ::schedule, { 0L })
            fun source() = object : ProfileStickerAvatarFileSource {
                private val alive = AtomicBoolean(true)
                override fun atomicCurrent() = alive.get() && sourceLive.get() && facts.current()
                override fun savedEvidence() = facts.evidence
                override fun hasConflict() = !facts.current()
                override fun conflict() {
                    assertFalse(inBroker, "source notification must run outside the broker monitor")
                    if (sourceLive.compareAndSet(true, false)) invalidations++
                }
                override fun resolve(active: () -> Boolean, terminal: () -> Unit,
                    result: (SettingsResult<ProfileStickerAvatarOwnedMedia>) -> Unit): ProfileStickerAvatarMediaAdmission {
                    result(facts.accept(metadata)); terminal(); return ProfileStickerAvatarMediaAdmission.ACCEPTED
                }
                override fun close() { alive.set(false) }
            }
            val results = mutableListOf<SettingsResult<ProfileStickerAvatarEncoded>>()
            files.saved({ source() }, { true }, results::add)
            files.saved({ source() }, { true }, results::add)
            assertEquals(1, downloads); assertEquals(1, prefixes)
            inBroker = true
            broker.observe(first.copy(size = 100))
            assertTrue(facts.current())
            val changed = when (change) {
                "size" -> first.copy(size = 200, downloaded = true, downloadedSize = 200)
                "missing" -> first.copy(size = 100, uniqueId = "", downloaded = true, downloadedSize = 100)
                else -> first.copy(size = 100, uniqueId = "native-other", downloaded = true, downloadedSize = 100)
            }
            broker.observe(changed)
            inBroker = false
            assertFalse(facts.current(), "both readers and any prior source lease share this atomic gate")
            assertTrue(sourceLive.get()); assertTrue(results.isEmpty())
            drain()
            assertEquals(1, invalidations); assertEquals(2, results.size)
            assertTrue(results.none { it.value != null }); assertEquals(1, downloads); assertEquals(1, prefixes)
            files.retire(); broker.close()
        }
    }
}
