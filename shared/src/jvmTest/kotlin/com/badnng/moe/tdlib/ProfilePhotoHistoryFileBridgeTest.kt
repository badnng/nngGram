package com.badnng.moe.tdlib

import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsProfilePhotoHistoryFileLease
import com.badnng.moe.settings.SettingsResult
import kotlin.test.*

/** Pure ownership tests; no engine, native file, worker, decoder or account. */
class ProfilePhotoHistoryFileBridgeTest {
    @Test fun closeBeforeBindPublishesCancellationOnceAndReturnsLateNativeHandle() {
        val results = mutableListOf<SettingsResult<MediaFile>>()
        val bridge = ProfilePhotoHistoryFileBridge({ true }, results::add)
        assertTrue(bridge.current())
        bridge.close(); bridge.close()
        assertFalse(bridge.current())
        assertEquals(SettingsError.NOT_READY, results.single().error)
        val native = NativeLease()
        bridge.bind(native)
        bridge.finish(SettingsResult(ready)); bridge.close()
        assertEquals(1, native.closes)
        assertEquals(1, results.size)
        assertNull(results.single().value)
    }

    @Test fun successfulCallbackMayCloseBeforeBindWithoutLosingReadyResultOrLeakingNative() {
        val results = mutableListOf<SettingsResult<MediaFile>>()
        lateinit var bridge: ProfilePhotoHistoryFileBridge
        bridge = ProfilePhotoHistoryFileBridge({ true }) { response ->
            results += response
            bridge.close()
        }
        bridge.finish(SettingsResult(ready))
        assertEquals(listOf(ready), results.map { it.value })
        assertFalse(bridge.current())
        val native = NativeLease()
        bridge.bind(native)
        bridge.finish(SettingsResult(error = SettingsError.REQUEST_FAILED)); bridge.close()
        assertEquals(1, native.closes)
        assertEquals(1, results.size)
    }

    @Test fun errorOrEmptyResponseClosesBoundHandleOnceAndIgnoresDuplicateCallbacks() {
        for (response in listOf(SettingsResult<MediaFile>(error = SettingsError.FORBIDDEN), SettingsResult<MediaFile>())) {
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val bridge = ProfilePhotoHistoryFileBridge({ true }, results::add)
            val native = NativeLease()
            bridge.bind(native)
            bridge.finish(response)
            assertEquals(response.error ?: SettingsError.REQUEST_FAILED, results.single().error)
            assertEquals(1, native.closes)
            assertFalse(bridge.current())
            bridge.finish(SettingsResult(ready)); bridge.finish(response); bridge.close()
            assertEquals(1, results.size)
            assertEquals(1, native.closes)
        }
        // A second handle cannot replace the one currently retained by the bridge.
        val results = mutableListOf<SettingsResult<MediaFile>>()
        val bridge = ProfilePhotoHistoryFileBridge({ true }, results::add)
        val retained = NativeLease()
        val duplicate = NativeLease()
        bridge.bind(retained); bridge.bind(duplicate)
        assertEquals(0, retained.closes)
        assertEquals(1, duplicate.closes)
        bridge.finish(SettingsResult(ready))
        assertEquals(0, retained.closes)
        bridge.close()
        assertEquals(1, retained.closes)
        assertEquals(1, duplicate.closes)
        assertEquals(listOf(ready), results.map { it.value })
    }

    @Test fun externalInactiveOrThrowingPredicateRejectsReadyAndStillReturnsNativeOnce() {
        val predicates: List<() -> Boolean> = listOf({ false }, { throw IllegalStateException("synthetic active failure") })
        for (active in predicates) {
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val bridge = ProfilePhotoHistoryFileBridge(active, results::add)
            val native = NativeLease()
            bridge.bind(native)
            assertFalse(bridge.current())
            bridge.finish(SettingsResult(ready))
            assertEquals(SettingsError.NOT_READY, results.single().error)
            assertNull(results.single().value)
            assertEquals(1, native.closes)
            bridge.finish(SettingsResult(ready)); bridge.close()
            assertEquals(1, results.size)
            assertEquals(1, native.closes)
        }
    }

    @Test fun throwingNativeCloseCannotSuppressCancellationOrErrorResultAndIsNeverRetried() {
        for (cancel in listOf(true, false)) {
            val results = mutableListOf<SettingsResult<MediaFile>>()
            val bridge = ProfilePhotoHistoryFileBridge({ true }, results::add)
            val native = NativeLease(throwsOnClose = true)
            bridge.bind(native)
            if (cancel) bridge.close() else bridge.finish(SettingsResult(error = SettingsError.FORBIDDEN))
            assertEquals(if (cancel) SettingsError.NOT_READY else SettingsError.FORBIDDEN, results.single().error)
            assertEquals(1, native.closes)
            bridge.close(); bridge.finish(SettingsResult(ready))
            assertEquals(1, native.closes)
            assertEquals(1, results.size)
        }
    }

    @Test fun throwingForeignResultReleasesReadyNativeOwnershipAndNeverInvokesConsumerAgain() {
        var callbacks = 0
        val bridge = ProfilePhotoHistoryFileBridge({ true }) { response ->
            callbacks++
            assertEquals(ready, response.value)
            throw IllegalStateException("synthetic consumer failure")
        }
        val native = NativeLease()
        bridge.bind(native)
        bridge.finish(SettingsResult(ready))
        assertEquals(1, callbacks)
        assertEquals(1, native.closes)
        assertFalse(bridge.current())
        bridge.close(); bridge.finish(SettingsResult(ready))
        assertEquals(1, callbacks)
        assertEquals(1, native.closes)
    }

    private class NativeLease(private val throwsOnClose: Boolean = false) : SettingsProfilePhotoHistoryFileLease {
        var closes = 0
        override fun close() {
            closes++
            if (throwsOnClose) throw IllegalStateException("synthetic native close failure")
        }
    }

    private companion object {
        val ready = MediaFile(8, size = 100, localPath = "synthetic-ready.jpg", downloaded = true, uniqueId = "native-8")
    }
}
