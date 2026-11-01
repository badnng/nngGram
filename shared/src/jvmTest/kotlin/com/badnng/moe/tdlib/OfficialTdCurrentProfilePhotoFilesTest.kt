package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

/** Shared broker with distinct current/history source authorities and synthetic files. */
class OfficialTdCurrentProfilePhotoFilesTest {
    @Test fun typedCurrentRequestUsesCurrentAuthorityAndTwoFreshGetFileChecks() = Fixture().use { f ->
        val result = mutableListOf<SettingsResult<MediaFile>>()
        f.files.readCurrent(f.currentRequest(), { true }, result::add); f.drain()
        assertEquals(f.actual, result.single().value); assertEquals(2, f.calls.size)
        assertTrue(f.calls.all { assertIs<TdApi.GetFile>(it).fileId == 7 })
        assertEquals(0, f.historyReads); assertTrue(f.currentReads > 0)
    }

    @Test fun currentAnimationCanLearnUnknownUniqueButRejectsChangedFinalNativeIdentity() = Fixture().use { f ->
        f.animation = f.animation.copy(file = f.animation.file.copy(uniqueId = "", size = 0))
        val results = mutableListOf<SettingsResult<MediaFile>>()
        f.files.readCurrent(f.currentRequest(SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW), { true }, results::add)
        f.afterFirst = { f.actual = f.actual.copy(uniqueId = "other") }
        f.drain()
        assertEquals(SettingsError.CONFLICT, results.single().error); assertNull(results.single().value)
    }

    @Test fun currentAndHistoryUseIndependentAuthoritiesAndLeasesEvenForTheSameNativeFile() = Fixture().use { f ->
        val current = mutableListOf<SettingsResult<MediaFile>>(); val history = mutableListOf<SettingsResult<MediaFile>>()
        val one = f.files.readCurrent(f.currentRequest(), { true }, current::add)
        val two = f.files.read(f.historyRequest(), { true }, history::add)
        one.close(); f.drain()
        assertEquals(SettingsError.NOT_READY, current.single().error)
        assertEquals(f.actual, history.single().value); assertEquals(1, current.size)
        two.close(); assertTrue(Files.exists(f.path))
    }

    @Test fun forgedCurrentTicketAndOversizedMetadataCannotRequestAnyFile() = Fixture().use { f ->
        val result = mutableListOf<SettingsResult<MediaFile>>()
        f.files.readCurrent(f.currentRequest().copy(authorization = 99), { true }, result::add)
        assertNotNull(result.single().error); assertTrue(f.calls.isEmpty())
        result.clear(); f.animation = f.animation.copy(file = f.animation.file.copy(size = 24L * 1024 * 1024 + 1))
        f.files.readCurrent(f.currentRequest(SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW), { true }, result::add)
        assertNotNull(result.single().error); assertTrue(f.calls.isEmpty())
    }

    private class Fixture : AutoCloseable {
        val path = Files.createTempFile("current-profile-photo-", ".bin")
        var actual = MediaFile(7, size = 100, localPath = path.toString(), downloaded = true,
            downloadedSize = 100, uniqueId = "native-7")
        private val static = SettingsProfilePhotoVariant(actual, 160, 160)
        var animation = SettingsProfilePhotoAnimation(actual, 640, 1.0)
        var historyReads = 0; var currentReads = 0; var afterFirst: (() -> Unit)? = null
        val calls = mutableListOf<TdApi.Function<*>>()
        private val readyTasks = ArrayDeque<() -> Unit>()
        private val native = Native()
        private fun schedule(delay: Long, action: () -> Unit) { if (delay == 0L) readyTasks.addLast(action) }
        val downloads = TdDownloadBroker(native, ::schedule, { 0L })
        val files = OfficialTdProfilePhotoHistoryFiles(ProfilePhotoHistoryCatalog { request ->
            historyReads++
            if (request.userId == 43L && request.authorization == 1L && request.photoId == 91L && request.rawOffset == 0)
                selectProfilePhotoHistoryVariant(listOf(static), request) else SettingsResult(error = SettingsError.CONFLICT)
        }, downloads, { command, reply, _ ->
            calls += command
            if (calls.size > 1) afterFirst?.invoke()
            reply(nativeFile(actual))
        }, ::schedule, { 0L }, currentCatalog = object : CurrentProfilePhotoCatalog {
            private fun allowed(request: SettingsCurrentProfilePhotoAuthorizedFileRequest) =
                request.userId == 43L && request.authorization == 2L && request.photoId == 90L
            override fun resolve(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoVariant> {
                currentReads++
                return if (allowed(request)) selectCurrentProfilePhotoVariant(listOf(static), request)
                    else SettingsResult(error = SettingsError.CONFLICT)
            }
            override fun resolveAnimation(request: SettingsCurrentProfilePhotoAuthorizedFileRequest): SettingsResult<SettingsProfilePhotoAnimation> {
                currentReads++
                return if (allowed(request) && request.use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW)
                    SettingsResult(animation) else SettingsResult(error = SettingsError.CONFLICT)
            }
            override fun observeFile(request: SettingsCurrentProfilePhotoAuthorizedFileRequest, actual: MediaFile): SettingsResult<Unit> =
                if (allowed(request)) SettingsResult(Unit) else SettingsResult(error = SettingsError.CONFLICT)
        })
        fun currentRequest(use: SettingsCurrentProfilePhotoMediaUse = SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) =
            SettingsCurrentProfilePhotoAuthorizedFileRequest(43, 2, 90, use, 160, 160)
        fun historyRequest() = SettingsProfilePhotoHistoryAuthorizedFileRequest(43, 1, 91, 0,
            SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW, 160, 160)
        fun drain() { while (readyTasks.isNotEmpty()) readyTasks.removeFirst()() }
        override fun close() { files.retire(); downloads.close(); Files.deleteIfExists(path) }
        private inner class Native : DownloadBrokerNative {
            override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(actual))
            override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) = completed(ChatResult(0))
            override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(actual))
            override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) = error("No raw byte reads")
            override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) = completed(ChatResult(Unit))
        }
    }
    private companion object {
        fun nativeFile(file: MediaFile) = TdApi.File().apply {
            id = file.id; size = file.size; expectedSize = file.expectedSize
            remote = TdApi.RemoteFile().apply { uniqueId = file.uniqueId }
            local = TdApi.LocalFile().apply { path = file.localPath.orEmpty(); isDownloadingCompleted = file.downloaded; downloadedSize = file.downloadedSize }
        }
    }
}
