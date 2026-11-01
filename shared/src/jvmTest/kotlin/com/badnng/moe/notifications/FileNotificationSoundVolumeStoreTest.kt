package com.badnng.moe.notifications

import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.NotificationSoundVolumeTarget
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNotificationSoundVolumeStoreTest {
    private val privateScope = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
    private val groupScope = NotificationSoundVolumeTarget.Scope(NotificationScope.GROUP_CHATS)
    private val chat = NotificationSoundVolumeTarget.Chat(-123)

    @Test fun missingFileIsUnsetAndGlobalZeroStaysSilent() = withStore { store, path ->
        assertNull(value(store.read(1, privateScope)).overridePercent)
        assertEquals(0, value(store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 0)))
        assertEquals(73, value(store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 73)))
        assertFalse(Files.exists(path))
    }

    @Test fun chatThenScopeThenGlobalAreDirectOverridesAndAccountIsolated() = withStore { store, _ ->
        set(store, 1, privateScope, 35)
        set(store, 1, groupScope, 61)
        set(store, 1, chat, 84)
        set(store, 2, privateScope, 22)
        assertEquals(84, value(store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 0)))
        assertEquals(35, value(store.resolve(1, -456, NotificationScope.PRIVATE_CHATS, 9)))
        assertEquals(61, value(store.resolve(1, -456, NotificationScope.GROUP_CHATS, 9)))
        assertEquals(22, value(store.resolve(2, -123, NotificationScope.PRIVATE_CHATS, 9)))
        assertEquals(9, value(store.resolve(3, -123, NotificationScope.PRIVATE_CHATS, 9)))
    }

    @Test fun explicitHundredDiffersFromUnsetAndNullRemovesOnlyItsTarget() = withStore { store, path ->
        set(store, 1, privateScope, 19)
        val hundred = set(store, 1, chat, 100)
        assertEquals(100, hundred.overridePercent)
        assertEquals(100, value(store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 0)))
        val removed = value(store.save(hundred, null))
        assertNull(removed.overridePercent)
        assertEquals(19, value(store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 0)))
        val reopened = FileNotificationSoundVolumeStore(path)
        assertNull(value(reopened.read(1, chat)).overridePercent)
        assertEquals(19, value(reopened.read(1, privateScope)).overridePercent)
    }

    @Test fun laterSavesPreserveAllUnrelatedAccountsAndTargetsOnDisk() = withStore { store, path ->
        set(store, Long.MAX_VALUE, NotificationSoundVolumeTarget.Chat(Long.MIN_VALUE), 18)
        set(store, 2, groupScope, 71)
        set(store, 1, privateScope, 42)
        set(store, 1, chat, 8)
        set(store, 1, chat, null)
        val reopened = FileNotificationSoundVolumeStore(path)
        assertEquals(18, value(reopened.read(Long.MAX_VALUE, NotificationSoundVolumeTarget.Chat(Long.MIN_VALUE))).overridePercent)
        assertEquals(71, value(reopened.read(2, groupScope)).overridePercent)
        assertEquals(42, value(reopened.read(1, privateScope)).overridePercent)
        assertNull(value(reopened.read(1, chat)).overridePercent)
        assertEquals(4, Files.readAllLines(path).size)
    }

    @Test fun invalidIdsAndRangesDoNotCreateOrAlterAFile() = withStore { store, path ->
        assertEquals(SettingsError.INVALID_VALUE, store.read(0, privateScope).error)
        assertEquals(SettingsError.INVALID_VALUE, store.read(-1, privateScope).error)
        assertEquals(SettingsError.INVALID_VALUE, store.read(1, NotificationSoundVolumeTarget.Chat(0)).error)
        val fresh = value(store.read(1, chat))
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE)) {
            assertEquals(SettingsError.INVALID_VALUE, store.save(fresh, invalid).error)
        }
        assertEquals(SettingsError.INVALID_VALUE, store.save(fresh.copy(accountUserId = 0), 20).error)
        assertEquals(SettingsError.INVALID_VALUE, store.save(fresh.copy(revision = 0), 20).error)
        assertEquals(SettingsError.INVALID_VALUE, store.save(fresh.copy(overridePercent = 0), 20).error)
        assertEquals(SettingsError.INVALID_VALUE, store.resolve(1, 0, NotificationScope.PRIVATE_CHATS, 20).error)
        assertEquals(SettingsError.INVALID_VALUE, store.resolve(-1, -123, NotificationScope.PRIVATE_CHATS, 20).error)
        assertEquals(SettingsError.INVALID_VALUE, store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, -1).error)
        assertEquals(SettingsError.INVALID_VALUE, store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 101).error)
        assertFalse(Files.exists(path))
        assertEquals(20, value(store.save(fresh, 20)).overridePercent)
    }

    @Test fun casChecksRevisionAccountTargetAndRawInsteadOfTrustingCallerCopies() = withStore { store, _ ->
        val first = value(store.read(1, chat))
        val other = value(store.read(2, groupScope))
        for (forged in listOf(
            first.copy(accountUserId = 2), first.copy(target = groupScope),
            first.copy(overridePercent = 100), first.copy(revision = Long.MAX_VALUE),
        )) assertEquals(SettingsError.CONFLICT, store.save(forged, 30).error)
        val saved = value(store.save(first, 30))
        assertNotEquals(first.revision, saved.revision)
        assertEquals(SettingsError.CONFLICT, store.save(first, 40).error)
        assertEquals(SettingsError.CONFLICT, store.save(other, 40).error)
        assertEquals(30, value(store.read(1, chat)).overridePercent)
    }

    @Test fun unrelatedReadsKeepAnExistingReadingUsableButNoOpConsumesItsRevision() = withStore { store, path ->
        val first = value(store.read(1, chat))
        value(store.read(2, groupScope))
        val noOp = value(store.save(first, null))
        assertNotEquals(first.revision, noOp.revision)
        assertEquals(SettingsError.CONFLICT, store.save(first, 50).error)
        assertFalse(Files.exists(path))
        assertEquals(50, value(store.save(noOp, 50)).overridePercent)
    }

    @Test fun externalWriterAndAbaContentRewriteRejectOldReadingsWithoutOverwriting() = withStore { store, path ->
        set(store, 1, chat, 23)
        val stale = value(store.read(1, chat))
        val foreignStore = FileNotificationSoundVolumeStore(path)
        set(foreignStore, 2, groupScope, 67)
        val foreignBytes = Files.readAllBytes(path)
        assertEquals(SettingsError.CONFLICT, store.save(stale, 99).error)
        assertContentEquals(foreignBytes, Files.readAllBytes(path))
        val beforeAba = value(store.read(1, chat))
        Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 10_000))
        assertEquals(SettingsError.CONFLICT, store.save(beforeAba, 99).error)
        assertEquals(67, value(store.read(2, groupScope)).overridePercent)
    }

    @Test fun externalCreationAndDeletionAreConflicts() = withStore { store, path ->
        val absent = value(store.read(1, chat))
        Files.writeString(path, "notification-sound-volumes=1\n2\tscope\tGROUP_CHATS\t31\n")
        assertEquals(SettingsError.CONFLICT, store.save(absent, 50).error)
        val present = value(store.read(1, chat))
        Files.delete(path)
        assertEquals(SettingsError.CONFLICT, store.save(present, 50).error)
        assertFalse(Files.exists(path))
    }

    @Test fun malformedFilesFailClosedAndCannotBeReplacedThroughOldReading() = withStore { store, path ->
        val beforeCorruption = value(store.read(1, chat))
        val malformed = listOf(
            "", "version=99\n", "notification-sound-volumes=1\n1\tchat\t0\t20\n",
            "notification-sound-volumes=1\n0\tchat\t-1\t20\n",
            "notification-sound-volumes=1\n-1\tchat\t-1\t20\n",
            "notification-sound-volumes=1\n9223372036854775808\tchat\t-1\t20\n",
            "notification-sound-volumes=1\n1\tchat\t9223372036854775808\t20\n",
            "notification-sound-volumes=1\n01\tchat\t-1\t20\n",
            "notification-sound-volumes=1\n1\tchat\t+1\t20\n",
            "notification-sound-volumes=1\n1\tscope\tUNKNOWN\t20\n",
            "notification-sound-volumes=1\n1\tchat\t-1\t0\n",
            "notification-sound-volumes=1\n1\tchat\t-1\t101\n",
            "notification-sound-volumes=1\n1\tchat\t-1\t020\n",
            "notification-sound-volumes=1\n1\tchat\t-1\t20\n1\tchat\t-1\t30\n",
            "notification-sound-volumes=1\n\n", "notification-sound-volumes=1\nunknown\n",
        )
        for (contents in malformed) {
            Files.writeString(path, contents)
            assertEquals(SettingsError.REQUEST_FAILED, store.read(1, chat).error)
            assertEquals(SettingsError.REQUEST_FAILED, store.resolve(1, -123, NotificationScope.PRIVATE_CHATS, 0).error)
            assertEquals(SettingsError.REQUEST_FAILED, store.save(beforeCorruption, 40).error)
            assertEquals(contents, Files.readString(path))
        }
        Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(SettingsError.REQUEST_FAILED, store.read(1, chat).error)
        assertContentEquals(byteArrayOf(0xc3.toByte(), 0x28), Files.readAllBytes(path))
    }

    @Test fun oversizedFileAndTooManyTargetsAreRejectedWithoutFallbackOrTruncation() = withStore { store, path ->
        val bytes = ByteArray(1_048_577) { 32 }
        Files.write(path, bytes)
        assertEquals(SettingsError.REQUEST_FAILED, store.read(1, chat).error)
        assertContentEquals(bytes, Files.readAllBytes(path))
        val tooMany = buildString {
            append("notification-sound-volumes=1\n")
            for (id in 1..32_769) append("1\tchat\t").append(id).append("\t1\n")
        }
        Files.writeString(path, tooMany)
        assertEquals(SettingsError.REQUEST_FAILED, store.read(1, chat).error)
        assertEquals(tooMany, Files.readString(path))
    }

    @Test fun fullStoreRejectsGrowthButAllowsReplacementAndRemoval() = withStore { store, path ->
        Files.writeString(path, buildString {
            append("notification-sound-volumes=1\n")
            for (id in 1..32_768) append("1\tchat\t").append(id).append("\t1\n")
        })
        val unchanged = Files.readAllBytes(path)
        assertEquals(SettingsError.INVALID_VALUE, store.save(value(store.read(1, chat)), 50).error)
        assertContentEquals(unchanged, Files.readAllBytes(path))
        assertEquals(99, set(store, 1, NotificationSoundVolumeTarget.Chat(1), 99).overridePercent)
        assertNull(set(store, 1, NotificationSoundVolumeTarget.Chat(1), null).overridePercent)
        assertEquals(50, set(store, 1, chat, 50).overridePercent)
    }

    @Test fun unboundedUnsetReadsEvictOldTokensWithoutGrowingPersistentStorage() = withStore { store, path ->
        val oldest = value(store.read(1, chat))
        for (id in 1..32_768) value(store.read(1, NotificationSoundVolumeTarget.Chat(id.toLong())))
        assertEquals(SettingsError.CONFLICT, store.save(oldest, 50).error)
        assertFalse(Files.exists(path))
        assertEquals(50, set(store, 1, chat, 50).overridePercent)
    }

    @Test fun readAndWriteIoFailureIsVisibleAndLeavesForeignTemporaryFilesUntouched() = withStore { store, path ->
        val expected = value(store.read(1, chat))
        val foreign = path.parent.resolve("notification-volumes-foreign.tmp")
        Files.writeString(foreign, "foreign temporary data")
        Files.createDirectory(path)
        assertEquals(SettingsError.REQUEST_FAILED, store.read(1, chat).error)
        assertEquals(SettingsError.REQUEST_FAILED, store.save(expected, 50).error)
        Files.delete(path)
        val fresh = value(store.read(1, chat))
        val moved = path.parent.resolveSibling(path.parent.fileName.toString() + "-moved")
        Files.move(path.parent, moved)
        try {
            Files.writeString(path.parent, "blocking parent")
            assertEquals(SettingsError.REQUEST_FAILED, store.save(fresh, 50).error)
            assertEquals("blocking parent", Files.readString(path.parent))
        } finally {
            Files.deleteIfExists(path.parent)
            Files.move(moved, path.parent)
        }
        assertEquals("foreign temporary data", Files.readString(foreign))
        assertEquals(50, set(store, 1, chat, 50).overridePercent)
        Files.list(path.parent).use { children ->
            assertEquals(setOf(path.fileName.toString(), foreign.fileName.toString()),
                children.map { it.fileName.toString() }.toList().toSet())
        }
    }

    @Test fun windowsReadOnlyDestinationReportsAtomicReplaceFailureAndCleansOnlyOwnedTemp() = withStore { store, path ->
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@withStore
        set(store, 1, chat, 35)
        val expected = value(store.read(1, chat))
        val bytes = Files.readAllBytes(path)
        val foreign = path.parent.resolve("notification-volumes-foreign.tmp")
        Files.writeString(foreign, "keep")
        Files.setAttribute(path, "dos:readonly", true)
        try {
            assertEquals(SettingsError.REQUEST_FAILED, store.save(expected, 91).error)
            assertContentEquals(bytes, Files.readAllBytes(path))
            assertEquals("keep", Files.readString(foreign))
            Files.list(path.parent).use { children ->
                assertEquals(setOf(path.fileName.toString(), foreign.fileName.toString()),
                    children.map { it.fileName.toString() }.toList().toSet())
            }
        } finally {
            Files.setAttribute(path, "dos:readonly", false)
        }
    }

    private fun set(
        store: FileNotificationSoundVolumeStore,
        account: Long,
        target: NotificationSoundVolumeTarget,
        volume: Int?,
    ) = value(store.save(value(store.read(account, target)), volume))

    private fun <T : Any> value(result: SettingsResult<T>): T {
        assertNull(result.error)
        return requireNotNull(result.value)
    }

    private inline fun withStore(block: (FileNotificationSoundVolumeStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("notification-volume-store-test-").toAbsolutePath().normalize()
        val path = directory.resolve("volumes.local")
        try {
            block(FileNotificationSoundVolumeStore(path), path)
        } finally {
            // This tree is created and exclusively owned by this test fixture.
            Files.walk(directory).use { children ->
                children.sorted(Comparator.reverseOrder()).forEach { child ->
                    assertTrue(child.toAbsolutePath().normalize().startsWith(directory))
                    Files.deleteIfExists(child)
                }
            }
        }
    }
}
