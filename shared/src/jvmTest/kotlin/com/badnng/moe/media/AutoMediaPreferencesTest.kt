package com.badnng.moe.media

import com.badnng.moe.SettingsDownloadDirectory
import com.badnng.moe.SettingsDownloadPreferences
import com.badnng.moe.writeDownloadPreferences
import com.badnng.moe.chat.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AutoMediaPreferencesTest {
    @Test fun completeAccountPolicyAtomicRoundTripPreservesLongLimitsAndNativePeerIdentity() = temporary { root ->
        val path = root.resolve("ui-settings/user-123/auto-media.properties")
        assertEquals(AutoMediaStored(), AutoMediaPreferencesStore.readFile(path))
        assertFalse(Files.exists(path))
        val policy = AutoMediaPolicy(peers = mapOf(-100123L to AutoMediaOverride.DENY, 1234L to AutoMediaOverride.ALLOW))
            .withSource(AutoMediaSource.CHANNEL, AutoMediaSourceRules.defaults(AutoMediaSource.CHANNEL)
                .withLimit(AutoMediaType.FILE, MAX_AUTO_MEDIA_BYTES))
        assertEquals(AutoMediaWriteResult.SAVED, AutoMediaPreferencesStore.writeFile(path, AutoMediaStored(), policy) { true })
        assertEquals(AutoMediaStored(1, policy), AutoMediaPreferencesStore.readFile(path))
        Files.list(path.parent).use { assertEquals(1L, it.count()) }
        assertFalse(policy.toString().contains("100123"))
    }

    @Test fun oldRevisionAndRetiredWorkersCannotConsumeOrReplaceANewerPolicy() = temporary { root ->
        val path = root.resolve("policy.properties")
        val original = AutoMediaStored()
        val changed = original.policy.sourceEnabled(AutoMediaSource.USER, false)
        assertEquals(AutoMediaWriteResult.RETIRED, AutoMediaPreferencesStore.writeFile(path, original, changed) { false })
        assertFalse(Files.exists(path))
        assertEquals(AutoMediaWriteResult.SAVED, AutoMediaPreferencesStore.writeFile(path, original, changed) { true })
        assertEquals(AutoMediaWriteResult.CONFLICT, AutoMediaPreferencesStore.writeFile(path, original, original.policy) { true })
        assertEquals(AutoMediaStored(1, changed), AutoMediaPreferencesStore.readFile(path))
        var checks = 0
        assertEquals(AutoMediaWriteResult.RETIRED, AutoMediaPreferencesStore.writeFile(path, AutoMediaStored(1, changed), original.policy) { ++checks < 3 })
        assertEquals(AutoMediaStored(1, changed), AutoMediaPreferencesStore.readFile(path))
    }

    @Test fun corruptVersionUnknownValuesAndInvalidPolicyRemainVisibleFailures() = temporary { root ->
        val path = root.resolve("policy.properties")
        val original = AutoMediaStored()
        assertEquals(AutoMediaWriteResult.FAILED, AutoMediaPreferencesStore.writeFile(path, original,
            original.policy.copy(sources = emptyMap())) { true })
        assertFalse(Files.exists(path))
        Files.writeString(path, "version=999\nrevision=1\n")
        assertNull(AutoMediaPreferencesStore.readFile(path))
        assertEquals(AutoMediaWriteResult.FAILED, AutoMediaPreferencesStore.writeFile(path, original, original.policy) { true })
        assertEquals("version=999\nrevision=1\n", Files.readString(path))
        assertNull(AutoMediaPreferencesStore.readFile(root))
    }

    @Test fun twoNativeUsersAndTwoDataRootsPersistIndependentlyWithoutCreatingDownloadDirectories() = temporary { root ->
        val global = root.resolve("synthetic-downloads.properties")
        var rootCalls = 0
        val roots: (String?, Long?) -> DownloadDestinationRoots = { dataPath, userId ->
            rootCalls++
            DownloadDestinationRoots(root.resolve("synthetic-downloads"), accountTemporaryDownloadDirectory(dataPath, userId))
        }
        val first = AutoMediaPreferencesStore(root.resolve("data-a"), global, roots)
        val second = AutoMediaPreferencesStore(root.resolve("data-b"), global, roots)
        val off = AutoMediaPolicy().sourceEnabled(AutoMediaSource.GROUP, false)
        assertEquals(AutoMediaWriteResult.SAVED, await<AutoMediaWriteResult> { first.write(123, AutoMediaStored(), off, { true }, it) })
        assertEquals(AutoMediaStored(1, off), await<AutoMediaStored?> { first.read(123, it) })
        assertEquals(AutoMediaStored(), await<AutoMediaStored?> { first.read(456, it) })
        assertEquals(AutoMediaStored(), await<AutoMediaStored?> { second.read(123, it) })
        assertEquals(0, rootCalls)
        val destination = await<AutoMediaDestination?> { first.destination(123, it) }
        assertEquals(root.resolve("synthetic-downloads"), destination?.directory)
        assertEquals(1, rootCalls)
        assertFalse(Files.exists(root.resolve("synthetic-downloads")))
    }

    @Test fun destinationUsesInjectedActualUserRootsAndAskNeverResolvesOrCreatesAnExternalTarget() = temporary { root ->
        val global = root.resolve("synthetic-downloads.properties")
        var calls = 0
        val store = AutoMediaPreferencesStore(root.resolve("data"), global) { dataPath, userId ->
            calls++
            DownloadDestinationRoots(null, accountTemporaryDownloadDirectory(dataPath, userId))
        }
        val initial = SettingsDownloadPreferences()
        val temp = initial.copy(directory = SettingsDownloadDirectory.ACCOUNT_TEMP)
        writeDownloadPreferences(global, initial, temp)
        assertEquals(root.resolve("data/downloads-temp/user-123"), await<AutoMediaDestination?> { store.destination(123, it) }?.directory)
        assertEquals(root.resolve("data/downloads-temp/user-456"), await<AutoMediaDestination?> { store.destination(456, it) }?.directory)
        writeDownloadPreferences(global, temp, temp.copy(askEveryTime = true))
        val before = calls
        val ask = await<AutoMediaDestination?> { store.destination(123, it) }
        assertTrue(ask!!.askEveryTime)
        assertNull(ask.directory)
        assertEquals(before, calls)
        assertFalse(Files.exists(root.resolve("data")))
    }

    private fun <T> await(call: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var result: Any? = null
        call { result = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("nnggram-auto-preferences-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
