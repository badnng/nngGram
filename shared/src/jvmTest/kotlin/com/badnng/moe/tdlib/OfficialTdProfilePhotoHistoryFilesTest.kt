package com.badnng.moe.tdlib

import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import kotlin.test.*

/** Fake session, native metadata and clock. Only a disposable synthetic readable file is created. */
class OfficialTdProfilePhotoHistoryFilesTest {
    @Test fun staticGetFileKnownSizeAlsoParticipatesInLaterPageConflictDetection() {
        for (conflict in listOf(false, true)) {
            val pages = PageFixture()
            val firstSize = TdApi.PhotoSize().apply {
                width = 160; height = 160; photo = nativeFile(MediaFile(7, 0, uniqueId = "native-7"))
            }
            val first = pages.page(sizes = arrayOf(firstSize)).entries.single()
            val request = request(first)
            Fixture().use { transport ->
                val reads = mutableListOf<SettingsResult<MediaFile>>()
                val service = transport.filesFor(pages.history)
                try {
                    service.read(request, { true }, reads::add); transport.clock.current()
                    assertEquals(1024L, reads.single().value?.size)
                    val nextSize = TdApi.PhotoSize().apply {
                        width = 160; height = 160
                        photo = nativeFile(MediaFile(7, if (conflict) 2048 else 1024, uniqueId = "native-7"))
                    }
                    val next = pages.page(offset = 1, photoId = first.photoId, sizes = arrayOf(nextSize))
                    assertEquals(conflict, next.metadataConflict)
                    assertEquals(conflict, pages.history.resolve(request).error != null)
                } finally { service.retire() }
            }
        }
    }

    @Test fun authorizedGetFileFactsMakeLaterPageIdentityAndSizeConflictsObservable() {
        for (change in 0..2) {
            val pages = PageFixture()
            val unknown = nativeAnimation(7, 640, 1.25).apply {
                file.size = 0; file.remote.uniqueId = ""; file.local.isDownloadingCompleted = false
                file.local.path = ""; file.local.downloadedSize = 0
            }
            val first = pages.page(animation = unknown).entries.single()
            val request = request(first).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            Fixture().use { transport ->
                val reads = mutableListOf<SettingsResult<MediaFile>>()
                val service = transport.filesFor(pages.history)
                try {
                    service.read(request, { true }, reads::add)
                    // The fresh GetFile has learned native-7/1024, while broker publication is pending.
                    assertTrue(reads.isEmpty()); assertEquals(1, transport.calls.size)
                    val next = nativeAnimation(7, 640, 1.25)
                    when (change) {
                        1 -> next.file.remote.uniqueId = "different-native-unique"
                        2 -> next.file.size = 2048
                    }
                    val later = pages.page(offset = 1, photoId = first.photoId, animation = next)
                    assertEquals(change != 0, later.metadataConflict)
                    if (change == 0) assertNotNull(pages.history.resolveAnimation(request).value)
                    else assertNotNull(pages.history.resolveAnimation(request).error)
                    transport.clock.current()
                    if (change == 0) assertEquals("native-7", reads.single().value?.uniqueId)
                    else assertEquals(SettingsError.CONFLICT, reads.single().error)
                } finally { service.retire() }
            }
        }
    }

    @Test fun laterReadCannotReplacePreviouslyLearnedNativeIdentityOrKnownSize() {
        for (change in 0..3) {
            val pages = PageFixture()
            val unknown = nativeAnimation(7, 640, 1.25).apply {
                file.size = 0; file.remote.uniqueId = ""; file.local.isDownloadingCompleted = false
                file.local.path = ""; file.local.downloadedSize = 0
            }
            val entry = pages.page(animation = unknown).entries.single()
            val request = request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            Fixture().use { transport ->
                val first = mutableListOf<SettingsResult<MediaFile>>()
                val service = transport.filesFor(pages.history)
                try {
                    service.read(request, { true }, first::add); transport.clock.current()
                    assertNotNull(first.single().value)
                    transport.actual = when (change) {
                        0 -> transport.actual.copy(uniqueId = "different-native-unique")
                        1 -> transport.actual.copy(size = 2048, downloadedSize = 2048)
                        2 -> transport.actual.copy(size = 0, downloadedSize = 0)
                        else -> transport.actual.copy(uniqueId = "")
                    }
                    val second = mutableListOf<SettingsResult<MediaFile>>()
                    service.read(request, { true }, second::add); transport.clock.current()
                    assertEquals(SettingsError.CONFLICT, second.single().error)
                    assertNotNull(pages.history.resolveAnimation(request).error)
                } finally { service.retire() }
            }
        }
    }

    @Test fun sameNativePageConflictingDuplicateBlocksItsIdentityAndRevokesPriorTicket() {
        for (conflict in listOf(false, true)) {
            val f = PageFixture()
            val old = f.page(animation = nativeAnimation(31, 640, 1.25)).entries.single()
            val photos = arrayOf(
                TdApi.ChatPhoto().apply { id = old.photoId; animation = nativeAnimation(31, 640, 1.25) },
                TdApi.ChatPhoto().apply { id = old.photoId; animation = nativeAnimation(31, 640, if (conflict) 1.5 else 1.25) },
            )
            // Keep identical static fields on all three native copies.
            photos.forEach { photo -> photo.sizes = arrayOf(TdApi.PhotoSize().apply {
                width = 160; height = 160; this.photo = nativeFile(MediaFile(7, 1024, uniqueId = "native-7"))
            }) }
            val page = f.page(offset = 1, count = 2, photos = photos)
            val oldRequest = request(old).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            assertEquals(conflict, f.history.resolveAnimation(oldRequest).error != null)
            page.entries.forEach { entry ->
                val result = f.history.resolveAnimation(request(entry).copy(use = oldRequest.use))
                assertEquals(conflict, result.error != null)
            }
        }
    }

    @Test fun crossPageStableAnimationOrStaticConflictRevokesAllTicketsForThatPhoto() {
        for (change in 0..6) {
            val f = PageFixture()
            val big = nativeAnimation(31, 640, 1.25)
            val small = nativeAnimation(32, 160, 0.75)
            val old = f.page(animation = big, smallAnimation = small).entries.single()
            val animationRequest = request(old).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            val nextBig = nativeAnimation(31, 640, 1.25)
            val nextSmall = nativeAnimation(32, 160, 0.75)
            var nextStatic: Array<TdApi.PhotoSize>? = null
            when (change) {
                0 -> nextBig.mainFrameTimestamp = 1.5
                1 -> nextBig.file.id = 99
                2 -> nextBig.file.remote.uniqueId = "changed"
                3 -> nextBig.length = 800
                4 -> nextBig.file.size = 2048
                5 -> nextSmall.mainFrameTimestamp = 0.5
                else -> nextStatic = arrayOf(TdApi.PhotoSize().apply {
                    width = 160; height = 160; photo = nativeFile(MediaFile(8, 1024, uniqueId = "native-8"))
                })
            }
            val later = f.page(offset = 1, photoId = old.photoId, sizes = nextStatic,
                animation = nextBig, smallAnimation = nextSmall).entries.single()
            assertNotNull(f.history.resolveAnimation(animationRequest).error, "Changed stable metadata $change")
            assertNotNull(f.history.resolve(request(old)).error)
            assertEquals(SettingsError.CONFLICT, f.history.resolveAnimation(request(later).copy(use = animationRequest.use)).error)
        }
    }

    @Test fun repeatedPhotoCanGainKnownSizeButLaterContradictionRevokesEvenUnknownSizeTicket() {
        val f = PageFixture()
        val unknown = nativeAnimation(31, 640, 1.25).apply { file.size = 0; file.local.downloadedSize = 0 }
        val first = f.page(animation = unknown).entries.single()
        val firstRequest = request(first).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        val known = nativeAnimation(31, 640, 1.25).apply {
            file.size = 100; file.expectedSize = 900; file.local.downloadedSize = 100; file.local.path = "moved"
        }
        val second = f.page(offset = 1, photoId = first.photoId, animation = known).entries.single()
        val secondRequest = request(second).copy(use = firstRequest.use)
        assertNotNull(f.history.resolveAnimation(firstRequest).value)
        assertNotNull(f.history.resolveAnimation(secondRequest).value)
        val changed = nativeAnimation(31, 640, 1.25).apply { file.size = 200; file.local.downloadedSize = 200 }
        f.page(offset = 2, photoId = first.photoId, animation = changed)
        assertNotNull(f.history.resolveAnimation(firstRequest).error)
        assertNotNull(f.history.resolveAnimation(secondRequest).error)
    }

    @Test fun stableComparisonIgnoresOffsetTransferStateAndVariantOrderingButNotKnownFacts() {
        val first = SettingsProfilePhotoHistoryEntry(91, 1, 0,
            listOf(variant(1, 160), variant(2, 320)), hasAnimation = true,
            animation = SettingsProfilePhotoAnimation(MediaFile(31, 0, uniqueId = "animation"), 640, 1.25))
        val moved = first.copy(rawOffset = 99, authorization = 100,
            variants = first.variants.reversed().map { it.copy(file = it.file.copy(
                localPath = "moved", downloaded = true, expectedSize = 8000, downloadedSize = 100)) },
            animation = first.animation?.let { it.copy(file = it.file.copy(size = 100, expectedSize = 200)) })
        assertTrue(sameHistoryPhotoMetadata(first, moved))
        val known = assertNotNull(moved.animation)
        assertFalse(sameHistoryPhotoMetadata(moved, moved.copy(animation = known.copy(file = known.file.copy(size = 101)))))
        assertFalse(sameHistoryPhotoMetadata(first, moved.copy(animation = known.copy(mainFrameTimestampSeconds = 1.5))))
        assertFalse(sameHistoryPhotoMetadata(first, moved.copy(variants = moved.variants.drop(1))))
        val unknownIdentity = first.copy(animation = first.animation?.let { it.copy(file = it.file.copy(uniqueId = "")) })
        assertTrue(sameHistoryPhotoMetadata(unknownIdentity, moved))
        assertFalse(sameHistoryPhotoMetadata(moved, moved.copy(animation = known.copy(file = known.file.copy(uniqueId = "other")))))
        val staticUnknown = first.copy(variants = first.variants.mapIndexed { index, value ->
            if (index == 0) value.copy(file = value.file.copy(uniqueId = "")) else value
        })
        assertTrue(sameHistoryPhotoMetadata(staticUnknown, first))
        assertFalse(sameHistoryPhotoMetadata(first, first.copy(variants = first.variants.mapIndexed { index, value ->
            if (index == 0) value.copy(file = value.file.copy(uniqueId = "other")) else value
        })))
    }

    @Test fun emptyNativeAnimationUniqueIsMetadataNotAnInvalidAnimation() {
        val f = PageFixture(); val native = nativeAnimation(31, 640, 1.0).apply { file.remote.uniqueId = "" }
        val entry = f.page(animation = native).entries.single()
        assertTrue(entry.hasAnimation); assertFalse(entry.invalidAnimation)
        assertEquals("", entry.animation?.file?.uniqueId)
        assertNotNull(f.history.resolveAnimation(request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)).value)
    }

    @Test fun animationCanLearnUnknownUniqueFromFreshGetFileAndMustKeepIt() {
        val request = request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        Fixture().use { f ->
            f.canonicalAnimation = f.canonicalAnimation.copy(file = f.canonicalAnimation.file.copy(uniqueId = ""))
            f.read(request); f.clock.current()
            assertEquals("native-7", f.results.single().value?.uniqueId); assertEquals(2, f.calls.size)
        }
        for (unknown in listOf("", " ")) Fixture().use { f ->
            f.canonicalAnimation = f.canonicalAnimation.copy(file = f.canonicalAnimation.file.copy(uniqueId = ""))
            f.actual = f.actual.copy(uniqueId = unknown)
            f.read(request); f.clock.current()
            assertEquals(SettingsError.NOT_READY, f.results.single().error)
            assertNull(f.results.single().value); assertTrue(f.native.downloads.isEmpty())
        }
        for (changed in listOf("", "different-native-unique")) Fixture().use { f ->
            f.canonicalAnimation = f.canonicalAnimation.copy(file = f.canonicalAnimation.file.copy(uniqueId = ""))
            f.onSend = { call -> call.reply(nativeFile(if (f.calls.size == 1) f.actual else f.actual.copy(uniqueId = changed))) }
            f.read(request); f.clock.current()
            assertEquals(SettingsError.CONFLICT, f.results.single().error); assertNull(f.results.single().value)
            assertEquals(2, f.calls.size)
        }
        for (changed in listOf("", "different-native-unique")) Fixture(ready = false, size = 0).use { f ->
            f.canonicalAnimation = f.canonicalAnimation.copy(file = f.canonicalAnimation.file.copy(uniqueId = ""))
            f.actual = f.actual.copy(size = 100)
            f.read(request)
            f.actual = f.actual.copy(uniqueId = changed); f.complete(100); f.clock.current()
            assertEquals(SettingsError.CONFLICT, f.results.single().error); assertEquals(1, f.calls.size)
        }
        Fixture().use { f ->
            f.actual = f.actual.copy(uniqueId = "")
            f.read(request); f.clock.current()
            assertEquals(SettingsError.CONFLICT, f.results.single().error)
        }
    }

    @Test fun staticVariantWithUnknownUniqueRetainsItsExistingUnsupportedRule() = Fixture().use { f ->
        f.canonical = f.canonical.copy(file = f.canonical.file.copy(uniqueId = ""))
        f.read(); f.clock.current()
        assertEquals(SettingsError.UNSUPPORTED, f.results.single().error)
        assertTrue(f.calls.isEmpty()); assertTrue(f.native.downloads.isEmpty())
    }

    @Test fun unknownSizeBecomingKnownCannotChangeAtBrokerCompletionForEitherUse() {
        for (use in listOf(SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)) {
            Fixture(ready = false, size = 0).use { f ->
                f.actual = f.actual.copy(size = 100, expectedSize = 900)
                f.read(request().copy(use = use))
                assertEquals(1, f.calls.size); assertTrue(f.results.isEmpty())
                f.complete(200); f.clock.current()
                assertEquals(SettingsError.CONFLICT, f.results.single().error); assertNull(f.results.single().value)
                assertEquals(1, f.calls.size, "Changed broker size must fail before final GetFile.")
            }
        }
    }

    @Test fun unknownSizeBecomingKnownCanCompleteWithTheSameSizeForEitherUse() {
        for (use in listOf(SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)) {
            Fixture(ready = false, size = 0).use { f ->
                f.actual = f.actual.copy(size = 100, expectedSize = 900)
                f.read(request().copy(use = use)); f.complete(100); f.clock.current()
                assertEquals(100L, f.results.single().value?.size); assertEquals(2, f.calls.size)
            }
        }
    }

    @Test fun finalNativeSizeChangeOrReturnToUnknownIsRejectedForEitherUse() {
        for (use in listOf(SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)) {
            for (finalSize in listOf(0L, 200L)) Fixture(ready = true, size = 0).use { f ->
                f.actual = f.actual.copy(size = 100, downloadedSize = 100)
                f.onSend = { call ->
                    val value = if (f.calls.size == 1) f.actual else f.actual.copy(size = finalSize, downloadedSize = finalSize)
                    call.reply(nativeFile(value))
                }
                f.read(request().copy(use = use)); f.clock.current()
                assertEquals(SettingsError.CONFLICT, f.results.single().error); assertNull(f.results.single().value)
                assertEquals(2, f.calls.size)
            }
        }
    }

    @Test fun stableKnownSizeAllowsFinalAuthoritativeNativePathToMove() {
        for (use in listOf(SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)) {
            Fixture(ready = true, size = 0).use { f ->
                val moved = Files.createTempFile("profile-history-moved-", ".bin")
                try {
                    f.actual = f.actual.copy(size = 100, downloadedSize = 100, expectedSize = 900)
                    f.onSend = { call ->
                        val value = if (f.calls.size == 1) f.actual else f.actual.copy(localPath = moved.toString(), expectedSize = 901)
                        call.reply(nativeFile(value))
                    }
                    f.read(request().copy(use = use)); f.clock.current()
                    assertEquals(moved.toString(), f.results.single().value?.localPath)
                    assertEquals(100L, f.results.single().value?.size); assertEquals(2, f.calls.size)
                } finally { Files.deleteIfExists(moved) }
            }
        }
    }

    @Test fun nativeAnimationMetadataIsCopiedAndPublicSnapshotNeverLeaksReadyPathsOrProgress() {
        val f = PageFixture()
        val big = nativeAnimation(31, 640, 1.2349)
        val small = nativeAnimation(32, 160, 2.3759)
        val entry = f.page(animation = big, smallAnimation = small).entries.single()
        val visible = assertNotNull(entry.animation)
        val smallVisible = assertNotNull(entry.smallAnimation)
        assertEquals(31, visible.file.id); assertEquals(640, visible.length)
        assertEquals(1.2349, visible.mainFrameTimestampSeconds); assertEquals(1234L, visible.mainFrameTimestampMillis)
        assertEquals(32, smallVisible.file.id); assertEquals(2375L, smallVisible.mainFrameTimestampMillis)
        for (metadata in listOf(visible, smallVisible)) {
            assertNull(metadata.file.localPath); assertFalse(metadata.file.downloaded); assertFalse(metadata.file.downloading)
            assertEquals(0, metadata.file.downloadedSize); assertFalse(metadata.file.uploading); assertEquals(0, metadata.file.uploadedSize)
        }
        val request = request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        val canonical = assertNotNull(f.history.resolveAnimation(request).value)
        assertEquals("native-ready-31", canonical.file.localPath)
        assertTrue(canonical.file.downloaded); assertTrue(canonical.file.downloading); assertTrue(canonical.file.uploading)
        big.length = 1; big.mainFrameTimestamp = 9.0; big.file.id = 900; big.file.remote.uniqueId = "changed"
        small.file.id = 901; small.mainFrameTimestamp = 10.0
        assertEquals(canonical, f.history.resolveAnimation(request).value)
        assertEquals(visible, entry.preferredAnimation()); assertEquals(32, entry.smallAnimation?.file?.id)
        assertEquals(Int.MAX_VALUE.toLong(), visible.copy(mainFrameTimestampSeconds = Double.MAX_VALUE).mainFrameTimestampMillis)
        assertNull(visible.copy(mainFrameTimestampSeconds = Double.NaN).mainFrameTimestampMillis)
    }

    @Test fun selectedAnimationPrefersLargeAndUsesActualSmallOnlyWhenLargeIsAbsent() {
        val both = PageFixture()
        val entry = both.page(animation = nativeAnimation(31, 640, 3.25),
            smallAnimation = nativeAnimation(32, 160, 0.75)).entries.single()
        val animationRequest = request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        assertEquals(31, both.history.resolveAnimation(animationRequest).value?.file?.id)
        assertEquals(3.25, entry.preferredAnimation()?.mainFrameTimestampSeconds)
        val largeOnly = PageFixture(); val largeEntry = largeOnly.page(animation = nativeAnimation(33, 800, 4.0)).entries.single()
        assertEquals(33, largeOnly.history.resolveAnimation(request(largeEntry).copy(use = animationRequest.use)).value?.file?.id)
        // Fixed TDLib normally discards an isolated small; this tests a real field in a hostile fake response.
        val smallOnly = PageFixture(); val smallEntry = smallOnly.page(smallAnimation = nativeAnimation(34, 160, 2.5)).entries.single()
        val resolved = assertNotNull(smallOnly.history.resolveAnimation(request(smallEntry).copy(use = animationRequest.use)).value)
        assertEquals(34, resolved.file.id); assertEquals(160, resolved.length); assertEquals(2.5, resolved.mainFrameTimestampSeconds)
    }

    @Test fun malformedNativeAnimationIsRejectedWithoutInventingAFallbackOrLosingStaticPhoto() {
        val corruptions: List<(TdApi.AnimatedChatPhoto) -> Unit> = listOf(
            { it.length = 0 }, { it.length = 1281 }, { it.mainFrameTimestamp = -0.1 },
            { it.mainFrameTimestamp = Double.NaN }, { it.mainFrameTimestamp = Double.POSITIVE_INFINITY },
            { it.file.id = 0 }, { it.file.size = -1 },
            { it.file.expectedSize = -1 }, { it.file.local.downloadedSize = -1 }, { it.file = null },
        )
        corruptions.forEach { corrupt ->
            val f = PageFixture(); val invalid = nativeAnimation(31, 640, 1.0); corrupt(invalid)
            val entry = f.page(animation = invalid, smallAnimation = nativeAnimation(32, 160, 0.0)).entries.single()
            assertTrue(entry.hasAnimation); assertTrue(entry.invalidAnimation); assertNull(entry.animation)
            val animationRequest = request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
            assertEquals(SettingsError.INVALID_VALUE, f.history.resolveAnimation(animationRequest).error)
            assertNull(entry.preferredAnimation())
            assertNotNull(f.history.resolve(request(entry)).value)
        }
        val f = PageFixture(); val invalidSmall = nativeAnimation(31, 161, 1.0)
        val entry = f.page(smallAnimation = invalidSmall).entries.single()
        assertTrue(entry.invalidSmallAnimation)
        assertEquals(SettingsError.INVALID_VALUE, f.history.resolveAnimation(
            request(entry).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)).error)
        val f2 = PageFixture(); val large = f2.page(animation = nativeAnimation(33, 640, 0.0),
            smallAnimation = invalidSmall).entries.single()
        assertEquals(33, f2.history.resolveAnimation(request(large).copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)).value?.file?.id)
    }

    @Test fun animationAuthorizationRequiresItsNativeTicketSourceAndExplicitUse() {
        var active = true; val f = PageFixture()
        val entry = f.page(current = { active }, sizes = emptyArray(), animation = nativeAnimation(31, 640, 1.0)).entries.single()
        val cell = request(entry); val preview = cell.copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW)
        val animation = cell.copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        assertNotNull(f.history.resolve(cell).error); assertNotNull(f.history.resolve(preview).error)
        assertNotNull(f.history.resolve(animation).error)
        assertNotNull(f.history.resolveAnimation(cell).error); assertNotNull(f.history.resolveAnimation(preview).error)
        assertNotNull(f.history.resolveAnimation(animation).value)
        listOf(animation.copy(authorization = 0), animation.copy(authorization = Long.MAX_VALUE),
            animation.copy(userId = 44), animation.copy(photoId = 999), animation.copy(rawOffset = 1)).forEach {
            assertNotNull(f.history.resolveAnimation(it).error)
        }
        active = false; assertEquals(SettingsError.NOT_READY, f.history.resolveAnimation(animation).error)
        active = true; assertNotNull(f.history.resolveAnimation(animation).error)
    }

    @Test fun animationFileUsesTheSameTwoIdentityChecksAndIndependentTwentyFourMiBBound() {
        val request = request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
        Fixture().use { f ->
            f.read(request); f.clock.current()
            assertEquals(f.actual, f.results.single().value)
            assertEquals(2, f.calls.size); assertTrue(f.calls.all { assertIs<TdApi.GetFile>(it.command).fileId == 7 })
            assertEquals(0, f.native.reads)
        }
        Fixture().use { f ->
            f.actual = f.actual.copy(uniqueId = "replaced")
            f.read(request); f.clock.current()
            assertNotNull(f.results.single().error); assertTrue(f.native.downloads.isEmpty())
        }
        Fixture().use { f ->
            f.onSend = { call -> call.reply(nativeFile(if (f.calls.size == 1) f.actual else f.actual.copy(id = 9))) }
            f.read(request); f.clock.current()
            assertNotNull(f.results.single().error); assertNull(f.results.single().value)
        }
        Fixture().use { f ->
            f.read(request)
            f.canonicalAnimation = f.canonicalAnimation.copy(mainFrameTimestampSeconds = 7.0)
            f.clock.current()
            assertNotNull(f.results.single().error); assertNull(f.results.single().value)
        }
        Fixture(ready = false, size = 0).use { f ->
            f.read(request); assertEquals(512L * 1024, f.native.downloads.single().limit)
            f.complete(8L * 1024 * 1024 + 1); f.clock.current()
            assertEquals(8L * 1024 * 1024 + 1, f.results.single().value?.size)
        }
        Fixture(ready = false, size = 0).use { f ->
            f.read(request); f.complete(24L * 1024 * 1024); f.clock.current()
            assertEquals(24L * 1024 * 1024, f.results.single().value?.size)
        }
        Fixture(ready = false, size = 0).use { f ->
            f.read(request); f.complete(24L * 1024 * 1024 + 1); f.clock.current()
            assertNotNull(f.results.single().error); assertNull(f.results.single().value)
            assertTrue(f.native.downloads.all { it.offset + it.limit <= 24L * 1024 * 1024 })
        }
    }

    @Test fun animationFilesDefensivelyRejectMalformedInternalCatalogWithoutAnyNativeCall() {
        for (bad in 0..4) Fixture().use { f ->
            val good = f.canonicalAnimation
            f.canonicalAnimation = when (bad) {
                0 -> good.copy(length = 0)
                1 -> good.copy(length = 1281)
                2 -> good.copy(mainFrameTimestampSeconds = Double.NaN)
                3 -> good.copy(file = good.file.copy(id = 0))
                else -> good.copy(file = good.file.copy(size = 24L * 1024 * 1024 + 1))
            }
            f.read(request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION))
            assertNotNull(f.results.single().error)
            assertTrue(f.calls.isEmpty()); assertTrue(f.native.downloads.isEmpty())
        }
    }

    @Test fun nativePagesIssueTicketsAndExposeMetadataWithoutTheCanonicalReadyPath() {
        val f = PageFixture()
        val file = nativeFile(MediaFile(7, 40, localPath = "canonical-private", downloaded = true, uniqueId = "native-7"))
        val size = TdApi.PhotoSize().apply { width = 160; height = 180; photo = file }
        val page = f.page(sizes = arrayOf(size))
        val entry = page.entries.single()
        assertTrue(entry.authorization > 0)
        assertNull(entry.variants.single().file.localPath)
        assertFalse(entry.variants.single().file.downloaded)
        assertEquals(0, entry.variants.single().file.downloadedSize)
        val canonical = assertNotNull(f.history.resolve(request(entry)).value)
        assertEquals("canonical-private", canonical.file.localPath)
        assertTrue(canonical.file.downloaded)
        file.id = 99; file.remote.uniqueId = "replaced"; size.width = 999
        assertEquals(canonical, f.history.resolve(request(entry)).value)
        assertFailsWith<UnsupportedOperationException> { (entry.variants as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (page.entries as MutableList<*>).clear() }
    }

    @Test fun unknownZeroAndForgedTicketUserPhotoAndOffsetCannotResolveNativeFiles() {
        val f = PageFixture(); val entry = f.page().entries.single(); val good = request(entry)
        val invalid = listOf(good.copy(authorization = 0), good.copy(authorization = Long.MAX_VALUE),
            good.copy(userId = 44), good.copy(photoId = 123), good.copy(rawOffset = 1))
        invalid.forEach { assertNotNull(f.history.resolve(it).error) }
        assertNotNull(f.history.resolve(good).value)
        assertEquals(3, f.commands.size)
    }

    @Test fun sourceOwnershipFalseIsLatchedAndCannotBeRevivedByTheSameClosure() {
        var current = true; val f = PageFixture(); val entry = f.page(current = { current }).entries.single()
        current = false
        assertEquals(SettingsError.NOT_READY, f.history.resolve(request(entry)).error)
        current = true
        assertNotNull(f.history.resolve(request(entry)).error)
    }

    @Test fun accountOrCurrentPersonalPublicAndBotRoleChangesInvalidateEarlierTickets() {
        for (change in 0..4) {
            val f = PageFixture(); val first = f.page().entries.single()
            when (change) {
                0 -> f.selfId = 44
                1 -> f.currentPhoto = 92
                2 -> f.personalPhoto = 93
                3 -> f.publicPhoto = 94
                4 -> f.bot = true
            }
            f.pageOrError()
            assertNotNull(f.history.resolve(request(first)).error, "Role/account change $change")
        }
    }

    @Test fun conflictingNativeCurrentPhotoSourcesAlsoDiscardEarlierTickets() {
        val f = PageFixture(); val first = f.page().entries.single()
        f.fullPhoto = 99
        assertEquals(SettingsError.CONFLICT, f.pageOrError().error)
        assertNotNull(f.history.resolve(request(first)).error)
    }

    @Test fun catalogRetainsShortPageTicketsAndBoundsFiveHundredTwelveRawItems() {
        val f = PageFixture(); val first = f.page().entries.single()
        repeat(16) { f.page(offset = it + 1) }
        assertNotNull(f.history.resolve(request(first)).value)
        val large = PageFixture(); val old = large.page(count = 100).entries.first()
        repeat(5) { large.page(offset = (it + 1) * 100, count = 100) }
        assertNotNull(large.history.resolve(request(old)).error)
        val fresh = large.page(offset = 600).entries.single()
        assertNotNull(large.history.resolve(request(fresh)).value)
    }

    @Test fun emptyPagesDoNotDisplaceShortPageTicketsAndSourceRetirementRevokesThem() {
        var active = true
        val f = PageFixture(); val first = f.page(current = { active }).entries.single()
        repeat(16) { f.page(offset = it + 1, current = { active }) }
        repeat(40) { f.page(offset = 17 + it, count = 0, current = { active }) }
        assertNotNull(f.history.resolve(request(first)).value)
        active = false; assertEquals(SettingsError.NOT_READY, f.history.resolve(request(first)).error)
        active = true; assertNotNull(f.history.resolve(request(first)).error)
    }

    @Test fun singleRawPageTicketsAreBoundedAtFiveHundredTwelve() {
        val f = PageFixture(); val first = f.page().entries.single()
        repeat(511) { f.page(offset = it + 1) }
        assertNotNull(f.history.resolve(request(first)).value)
        val next = f.page(offset = 512).entries.single()
        assertNotNull(f.history.resolve(request(first)).error)
        assertNotNull(f.history.resolve(request(next)).value)
    }

    @Test fun ticketsAreFreshForEachNativePageAndRetirementPermanentlyStopsTheCatalog() {
        val f = PageFixture(); val first = f.page().entries.single(); val next = f.page().entries.single()
        assertTrue(next.authorization > first.authorization)
        f.history.retire(); f.history.retire()
        assertNotNull(f.history.resolve(request(next)).error)
        val before = f.commands.size
        assertEquals(SettingsError.NOT_READY, f.pageOrError().error)
        assertEquals(before, f.commands.size)
    }

    @Test fun visibleCellsSelectTheSmallestCoveringRealSmallVariantAndNeverAnOriginal() {
        val variants = listOf(variant(1, 80), variant(2, 160), variant(3, 320), variant(4, 512), variant(5, 1280))
        assertEquals(2, selectProfilePhotoHistoryVariant(variants, request().copy(targetWidthPx = 150)).value?.file?.id)
        assertEquals(4, selectProfilePhotoHistoryVariant(variants, request().copy(targetWidthPx = 576, targetHeightPx = 576)).value?.file?.id)
        assertEquals(4, selectProfilePhotoHistoryVariant(variants, request().copy(targetWidthPx = 4096)).value?.file?.id)
        assertEquals(SettingsError.UNSUPPORTED, selectProfilePhotoHistoryVariant(listOf(variant(5, 1280)), request()).error)
    }

    @Test fun previewSelectsLargestStaticVariantWithinTheByteAndPixelBudgets() {
        val values = listOf(variant(1, 160), variant(2, 4096), variant(3, 5000),
            variant(4, 4500).let { it.copy(file = it.file.copy(size = 24L * 1024 * 1024 + 1)) })
        assertEquals(2, selectProfilePhotoHistoryVariant(values, request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW)).value?.file?.id)
        assertEquals(1, selectProfilePhotoHistoryVariant(listOf(variant(1, 160), variant(2, 8192)), request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW)).value?.file?.id)
    }

    @Test fun invalidTargetDimensionsAndMalformedVariantsFailWithoutInventingFiles() {
        for ((w, h) in listOf(0 to 1, 1 to 0, -1 to 4, 4097 to 1, 1 to Int.MAX_VALUE)) {
            assertEquals(SettingsError.INVALID_VALUE, selectProfilePhotoHistoryVariant(listOf(variant(1, 160)),
                request().copy(targetWidthPx = w, targetHeightPx = h)).error)
        }
        val base = variant(1, 160)
        for (bad in listOf(base.copy(width = 0), base.copy(height = -1),
            base.copy(file = base.file.copy(id = 0)), base.copy(file = base.file.copy(uniqueId = " ")),
            base.copy(file = base.file.copy(size = -1)), base.copy(file = base.file.copy(size = 8L * 1024 * 1024 + 1)))) {
            assertNotNull(selectProfilePhotoHistoryVariant(listOf(bad), request()).error)
        }
    }

    @Test fun readyNativeFileIsReusedAfterTwoFreshGetFileChecksWithoutReadingOrCopyingBytes() = Fixture().use { f ->
        f.read(); assertTrue(f.results.isEmpty()); f.clock.current()
        assertEquals(f.actual, f.results.single().value)
        assertEquals(2, f.calls.size)
        assertTrue(f.calls.all { assertIs<TdApi.GetFile>(it.command).fileId == 7 })
        assertTrue(f.native.downloads.isEmpty()); assertEquals(0, f.native.reads)
        assertTrue(Files.exists(f.path))
    }

    @Test fun preDownloadNativeIdUniqueSizeAndDownloadedByteCountMustStillMatch() {
        for (change in 0..5) Fixture().use { f ->
            f.actual = when (change) {
                0 -> f.actual.copy(id = 8)
                1 -> f.actual.copy(uniqueId = "changed")
                2 -> f.actual.copy(uniqueId = "")
                3 -> f.actual.copy(size = 1025)
                4 -> f.actual.copy(size = 8L * 1024 * 1024 + 1)
                else -> f.actual.copy(downloadedSize = 8L * 1024 * 1024 + 1)
            }
            f.read(); f.clock.current()
            assertNotNull(f.results.single().error, "Pre-check $change")
            assertTrue(f.native.downloads.isEmpty())
        }
    }

    @Test fun rawNegativeNativeSizeCannotBeNormalizedIntoAnUnknownAuthorizedFile() = Fixture().use { f ->
        f.onSend = { call -> call.reply(nativeFile(f.actual).apply { size = -1 }) }
        f.read(); assertNotNull(f.results.single().error); assertTrue(f.native.downloads.isEmpty())
    }

    @Test fun postDownloadIdentitySizePathAndReadinessAreAllRechecked() {
        for (change in 0..6) Fixture().use { f ->
            f.onSend = { call ->
                val file = if (f.calls.size == 1) f.actual else when (change) {
                    0 -> f.actual.copy(id = 8)
                    1 -> f.actual.copy(uniqueId = "replaced")
                    2 -> f.actual.copy(size = 1025)
                    3 -> f.actual.copy(localPath = null)
                    4 -> f.actual.copy(localPath = f.path.resolveSibling("absent-history-image").toString())
                    5 -> f.actual.copy(downloaded = false)
                    else -> f.actual.copy(size = 0)
                }
                call.reply(nativeFile(file))
            }
            f.read(); f.clock.current()
            assertNotNull(f.results.single().error, "Post-check $change")
            assertTrue(Files.exists(f.path))
        }
    }

    @Test fun ticketRevocationAtPublicationCannotInstallThePreviouslyReadyNativePath() = Fixture().use { f ->
        f.read(); f.catalogAllowed = false; f.clock.current()
        assertNotNull(f.results.single().error); assertNull(f.results.single().value)
        assertEquals(2, f.calls.size)
    }

    @Test fun unknownSizeUsesFiniteCellWindowsAndNeedsActualNativeCompletion() = Fixture(ready = false, size = 0).use { f ->
        f.read()
        assertEquals(512L * 1024, f.native.downloads.single().limit,
            "The total cell budget does not enlarge the shared broker's finite native window.")
        assertTrue(f.results.isEmpty())
        f.complete(1024); f.clock.current()
        assertNotNull(f.results.single().value)
        assertEquals(2, f.calls.size); assertEquals(0, f.native.reads)
    }

    @Test fun explicitPreviewUsesItsSeparateTwentyFourMiBBudget() {
        Fixture(ready = false, size = 0).use { f ->
            f.read(request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW))
            assertEquals(512L * 1024, f.native.downloads.single().limit)
            f.complete(8L * 1024 * 1024 + 1); f.clock.current()
            assertEquals(8L * 1024 * 1024 + 1, assertNotNull(f.results.single().value).size,
                "Explicit preview can complete beyond the cell budget while remaining below its own bound.")
            assertEquals(0, f.native.reads)
        }
        Fixture(ready = false, size = 0).use { f ->
            f.read(request().copy(use = SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW))
            assertEquals(512L * 1024, f.native.downloads.single().limit)
            f.complete(24L * 1024 * 1024 + 1); f.clock.current()
            assertNotNull(f.results.single().error); assertNull(f.results.single().value)
        }
    }

    @Test fun unknownSizeOverTheCellCapFailsWithoutReadingAnyNativeBytes() = Fixture(ready = false, size = 0).use { f ->
        f.read(); f.complete(8L * 1024 * 1024 + 1); f.clock.current()
        assertNotNull(f.results.single().error); assertEquals(0, f.native.reads)
        assertTrue(f.native.downloads.all { it.offset + it.limit <= 8L * 1024 * 1024 })
    }

    @Test fun twoHistoryOwnersShareTheSessionLaneAndClosingOneLeavesTheOtherAlive() = Fixture(ready = false).use { f ->
        val first = mutableListOf<SettingsResult<MediaFile>>()
        val second = mutableListOf<SettingsResult<MediaFile>>()
        val lease = f.files.read(request(), { true }, first::add)
        f.files.read(request(), { true }, second::add)
        assertEquals(1, f.native.downloads.size)
        lease.close(); assertEquals(SettingsError.NOT_READY, first.single().error)
        assertTrue(f.native.cancelled.isEmpty())
        f.complete(); f.clock.current()
        assertNotNull(second.single().value); assertEquals(1, first.size)
    }

    @Test fun retiringHistoryRequestsDoesNotRetireAnotherPurposeOrDeleteTheNativeCache() = Fixture(ready = false).use { f ->
        val other = mutableListOf<ChatResult<MediaFile>>()
        val account = MediaDownloadOwner(ChatToken(0, 0, null), 0, MediaDownloadPurpose.ARTWORK, 9876)
        f.downloads.full(account, f.actual, other::add)
        f.read(); f.files.retire(); f.files.retire()
        assertTrue(f.native.cancelled.isEmpty()); assertEquals(1, f.native.downloads.size)
        f.complete(); f.clock.current()
        assertNotNull(other.single().value); assertNotNull(f.results.single().error)
        assertTrue(Files.exists(f.path))
    }

    @Test fun closeBeforeTheNativeResponseAndAllLateDuplicateCallbacksStayTerminal() = Fixture().use { f ->
        f.defer = true; val lease = f.read()
        val call = f.calls.single(); lease.close(); lease.close()
        call.reply(nativeFile(f.actual)); call.reply(nativeFile(f.actual)); call.failed(); f.clock.current()
        assertEquals(1, f.results.size); assertEquals(SettingsError.NOT_READY, f.results.single().error)
        assertTrue(f.native.downloads.isEmpty()); assertEquals(1, f.calls.size)
    }

    @Test fun closingAfterBrokerCompletionButBeforeScheduledPublicationCannotPublishAPath() = Fixture().use { f ->
        val lease = f.read(); lease.close(); f.clock.current()
        assertEquals(1, f.results.size); assertNull(f.results.single().value); assertEquals(1, f.calls.size)
    }

    @Test fun duplicateNativeAndBrokerCompletionNeverDuplicateResultsOrFollowupQueries() = Fixture(ready = false).use { f ->
        f.onSend = { call -> call.reply(nativeFile(f.actual)); call.reply(nativeFile(f.actual)); call.failed() }
        f.read(); f.complete(); f.downloads.observe(f.actual); f.clock.current()
        assertEquals(1, f.results.size); assertNotNull(f.results.single().value); assertEquals(2, f.calls.size)
    }

    @Test fun falseCallerActivityIsLatchedWithoutStartingOrRevivingNativeWork() = Fixture().use { f ->
        var active = false
        f.files.read(request(), { active }, f.results::add)
        active = true; f.clock.current()
        assertEquals(SettingsError.NOT_READY, f.results.single().error); assertTrue(f.calls.isEmpty())
    }

    @Test fun eightPendingReadersAreBoundedAndRetirementReleasesAllExactlyOnce() = Fixture().use { f ->
        f.defer = true
        repeat(9) { f.read() }
        assertEquals(8, f.calls.size); assertEquals(SettingsError.RATE_LIMITED, f.results.single().error)
        f.files.retire(); f.files.retire()
        assertEquals(9, f.results.size)
        f.calls.forEach { it.reply(nativeFile(f.actual)) }; f.clock.current()
        assertEquals(9, f.results.size); assertTrue(f.native.downloads.isEmpty())
        f.read(); assertEquals(SettingsError.NOT_READY, f.results.last().error)
    }

    @Test fun aThirtySecondTimeoutIsTerminalAndDoesNotAutomaticallyRetry() = Fixture().use { f ->
        f.defer = true; f.read(); f.clock.advance(29_999); assertTrue(f.results.isEmpty())
        f.clock.advance(1); assertEquals(SettingsError.REQUEST_FAILED, f.results.single().error)
        f.calls.single().reply(nativeFile(f.actual)); f.clock.advance(60_000)
        assertEquals(1, f.calls.size); assertEquals(1, f.results.size)
    }

    @Test fun nativeErrorsFailuresAndThrowsCompleteOnceWithoutDownloadsOrPathPublication() {
        for (mode in 0..2) Fixture().use { f ->
            f.onSend = { call -> when (mode) {
                0 -> call.reply(TdApi.Error(403, "private native text"))
                1 -> { call.failed(); call.reply(nativeFile(f.actual)) }
                else -> error("synthetic failure")
            } }
            f.read(); f.clock.current()
            assertNotNull(f.results.single().error); assertTrue(f.native.downloads.isEmpty())
            assertFalse(f.results.single().toString().contains("private native text"))
        }
    }

    @Test fun brokerCompletionDefersForeignActivityCatalogAndConsumerUntilAfterItsMonitor(): Unit = Fixture(ready = false).use { f ->
        val monitor = TdDownloadBroker::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(f.downloads)
        var checks = 0
        f.catalogCheck = { assertFalse(Thread.holdsLock(monitor)); checks++ }
        f.files.read(request(), { assertFalse(Thread.holdsLock(monitor)); true }) {
            assertFalse(Thread.holdsLock(monitor)); f.results += it
        }
        val before = checks
        f.complete()
        assertEquals(before, checks); assertTrue(f.results.isEmpty())
        f.clock.current(); assertNotNull(f.results.single().value)
    }

    @Test fun callbacksCanReenterAndAcquireANewReadAfterTerminalCleanup() = Fixture().use { f ->
        f.files.read(request(), { true }) {
            f.results += it
            if (f.results.size == 1) f.read()
        }
        f.clock.current()
        assertEquals(2, f.results.size); assertTrue(f.results.all { it.value != null })
    }

    @Test fun extrasBindIsOnceOnlyAndRetirementClearsThePrivateTicketCatalog() {
        Fixture().use { f ->
            val api = OfficialTdSettingsProfileExtras({ _, _, _ -> })
            api.bindHistoryDownloads(f.downloads)
            assertFailsWith<IllegalStateException> { api.bindHistoryDownloads(f.downloads) }
            api.retireHistoryFiles(); api.retireHistoryFiles()
            api.acquireHistoryFile(request(), { true }, f.results::add)
            assertEquals(SettingsError.NOT_READY, f.results.single().error)
            assertTrue(Files.exists(f.path))
        }
    }

    private class PageFixture {
        val commands = mutableListOf<TdApi.Function<*>>()
        var selfId = 43L; var currentPhoto = 90L; var fullPhoto: Long? = null
        var personalPhoto = 0L; var publicPhoto = 0L; var bot = false
        private var pending: ((TdApi.Object) -> Unit)? = null
        val history = OfficialTdSettingsProfilePhotoHistory { command, result, _ ->
            commands += command
            when (command) {
                is TdApi.GetMe -> result(TdApi.User().apply {
                    id = selfId; profilePhoto = TdApi.ProfilePhoto().apply { id = currentPhoto }
                    type = if (bot) TdApi.UserTypeBot() else TdApi.UserTypeRegular()
                })
                is TdApi.GetUserFullInfo -> result(TdApi.UserFullInfo().apply {
                    photo = TdApi.ChatPhoto().apply { id = fullPhoto ?: currentPhoto }
                    personalPhoto = TdApi.ChatPhoto().apply { id = this@PageFixture.personalPhoto }
                    publicPhoto = TdApi.ChatPhoto().apply { id = this@PageFixture.publicPhoto }
                })
                is TdApi.GetUserProfilePhotos -> pending = result
                else -> error("Unexpected command")
            }
        }
        fun pageOrError(offset: Int = 0, count: Int = 1, current: () -> Boolean = { true }, sizes: Array<TdApi.PhotoSize>? = null,
            animation: TdApi.AnimatedChatPhoto? = null, smallAnimation: TdApi.AnimatedChatPhoto? = null,
            photoId: Long? = null, photos: Array<TdApi.ChatPhoto>? = null,
        ): SettingsResult<SettingsProfilePhotoHistoryPage> {
            var response: SettingsResult<SettingsProfilePhotoHistoryPage>? = null
            pending = null
            history.page(43, offset, maxOf(1, count), current) { response = it }
            pending?.invoke(TdApi.ChatPhotos(1000, photos ?: Array(count) { index -> TdApi.ChatPhoto().apply {
                id = photoId ?: 91L + offset + index
                this.sizes = sizes ?: arrayOf(TdApi.PhotoSize().apply {
                    width = 160; height = 160; photo = nativeFile(MediaFile(7, 1024, uniqueId = "native-7"))
                })
                this.animation = animation; this.smallAnimation = smallAnimation
            } }))
            return assertNotNull(response)
        }
        fun page(offset: Int = 0, count: Int = 1, current: () -> Boolean = { true }, sizes: Array<TdApi.PhotoSize>? = null,
            animation: TdApi.AnimatedChatPhoto? = null, smallAnimation: TdApi.AnimatedChatPhoto? = null,
            photoId: Long? = null, photos: Array<TdApi.ChatPhoto>? = null,
        ) = assertNotNull(pageOrError(offset, count, current, sizes, animation, smallAnimation, photoId, photos).value)
    }

    private data class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val failed: () -> Unit)

    private class Fixture(ready: Boolean = true, size: Long = 1024) : AutoCloseable {
        val path = Files.createTempFile("profile-history-test-", ".bin")
        var actual = MediaFile(7, size = size, localPath = path.toString().takeIf { ready }, downloaded = ready,
            downloadedSize = if (ready) size else 0, canDownload = true, uniqueId = "native-7")
        var canonical = SettingsProfilePhotoVariant(actual, 160, 160)
        var canonicalAnimation = SettingsProfilePhotoAnimation(actual, 160, 1.25)
        val clock = Clock(); val native = Native(); val downloads = TdDownloadBroker(native, clock::schedule, { clock.now })
        var catalogAllowed = true; var catalogCheck: () -> Unit = {}
        val calls = mutableListOf<Call>(); var defer = false
        var onSend: ((Call) -> Unit)? = null
        val results = mutableListOf<SettingsResult<MediaFile>>()
        val files = OfficialTdProfilePhotoHistoryFiles(object : ProfilePhotoHistoryCatalog {
            private fun allowed(request: SettingsProfilePhotoHistoryAuthorizedFileRequest): Boolean {
                catalogCheck()
                return catalogAllowed && request.authorization == 1L && request.userId == 43L &&
                    request.photoId == 91L && request.rawOffset == 0
            }
            override fun resolve(request: SettingsProfilePhotoHistoryAuthorizedFileRequest) =
                if (allowed(request)) selectProfilePhotoHistoryVariant(listOf(canonical), request)
                else SettingsResult(error = SettingsError.CONFLICT)
            override fun resolveAnimation(request: SettingsProfilePhotoHistoryAuthorizedFileRequest) =
                if (allowed(request) && request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_ANIMATION)
                    SettingsResult(canonicalAnimation) else SettingsResult(error = SettingsError.CONFLICT)
        }, downloads, { command, reply, failed ->
            val call = Call(command, reply, failed); calls += call
            if (!defer) onSend?.invoke(call) ?: reply(nativeFile(actual))
        }, clock::schedule, { clock.now })
        init { native.file = { actual } }
        fun filesFor(catalog: ProfilePhotoHistoryCatalog) = OfficialTdProfilePhotoHistoryFiles(catalog, downloads,
            { command, reply, failed ->
                val call = Call(command, reply, failed); calls += call
                if (!defer) onSend?.invoke(call) ?: reply(nativeFile(actual))
            }, clock::schedule, { clock.now })
        fun read(request: SettingsProfilePhotoHistoryAuthorizedFileRequest = request()) = files.read(request, { true }, results::add)
        fun complete(size: Long = actual.size) {
            actual = actual.copy(size = size, localPath = path.toString(), downloaded = true, downloading = false, downloadedSize = size)
            downloads.observe(actual)
        }
        override fun close() { files.retire(); downloads.close(); Files.deleteIfExists(path) }
    }

    private class Native : DownloadBrokerNative {
        data class Download(val id: Int, val offset: Long, val limit: Long)
        val downloads = mutableListOf<Download>(); val cancelled = mutableListOf<Int>(); var reads = 0
        lateinit var file: () -> MediaFile
        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long, completed: (ChatResult<MediaFile>) -> Unit) {
            downloads += Download(fileId, offset, limit); completed(ChatResult(file()))
        }
        override fun prefix(fileId: Int, offset: Long, completed: (ChatResult<Long>) -> Unit) = completed(ChatResult(0))
        override fun refresh(fileId: Int, completed: (ChatResult<MediaFile>) -> Unit) = completed(ChatResult(file()))
        override fun read(fileId: Int, offset: Long, count: Int, completed: (ChatResult<ByteArray>) -> Unit) {
            reads++; error("History must not copy file bytes")
        }
        override fun cancel(fileId: Int, completed: (ChatResult<Unit>) -> Unit) { cancelled += fileId; completed(ChatResult(Unit)) }
    }

    private class Clock {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, task: () -> Unit) { tasks += now + delay to task }
        fun current() = advance(0)
        fun advance(delta: Long) {
            val end = now + delta
            repeat(100_000) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: run { now = end; return }
                tasks.remove(next); now = next.first; next.second()
            }
            error("Unbounded synthetic scheduling")
        }
    }

    private companion object {
        fun request(entry: SettingsProfilePhotoHistoryEntry? = null) = SettingsProfilePhotoHistoryAuthorizedFileRequest(
            43, entry?.authorization ?: 1, entry?.photoId ?: 91, entry?.rawOffset ?: 0,
            SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL, 150, 150)
        fun variant(id: Int, size: Int) = SettingsProfilePhotoVariant(MediaFile(id, 1024, uniqueId = "native-$id"), size, size)
        fun nativeAnimation(id: Int, length: Int, timestamp: Double) = TdApi.AnimatedChatPhoto().apply {
            this.length = length; mainFrameTimestamp = timestamp
            file = nativeFile(MediaFile(id, 1024, expectedSize = 1024, localPath = "native-ready-$id",
                downloaded = true, downloading = true, downloadedSize = 1024, uploading = true, uploadedSize = 50,
                uniqueId = "native-$id"))
        }
        fun nativeFile(value: MediaFile) = TdApi.File().apply {
            id = value.id; size = value.size; expectedSize = value.expectedSize
            remote = TdApi.RemoteFile().apply {
                uniqueId = value.uniqueId; isUploadingActive = value.uploading; uploadedSize = value.uploadedSize
            }
            local = TdApi.LocalFile().apply {
                path = value.localPath.orEmpty(); isDownloadingCompleted = value.downloaded
                isDownloadingActive = value.downloading; downloadedSize = value.downloadedSize; canBeDownloaded = value.canDownload
            }
        }
    }
}
