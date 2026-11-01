package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.media.LocalNativeDirectoryPicker
import com.badnng.moe.media.LocalNativeSavePicker
import com.badnng.moe.media.NativeSaveTargets
import com.badnng.moe.media.LocalSavedMediaFileIo
import com.badnng.moe.media.SaveMediaFile
import com.badnng.moe.media.SavedMediaPublicationTest
import com.badnng.moe.media.SavedMediaFileIo
import com.badnng.moe.media.NioSavedMediaFileIo
import com.badnng.moe.media.AutoMediaCopyIssue
import com.badnng.moe.media.AutoMediaCopyResult
import com.badnng.moe.chat.AutoMediaSettings
import com.badnng.moe.tdlib.autoMediaCopyNotice
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.*

/** Real composables and synthetic paths only; no native session, dialogs, or account data. */
class SaveTargetBoundaryUiTest {
    @Test fun customSelectionIsRejectedAndCancellationKeepsTheStoredPreferenceBeforeSafeReselection() = fixture { f ->
        val original = SettingsDownloadPreferences(false)
        val store = Store(original)
        val unsafe = f.files.resolve("new-copies")
        val safe = f.root.resolve("independent-copies")
        var pickerCalls = 0
        scene {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(f.root.resolve("downloads"), f.root.resolve("temp")) },
                LocalNativeDirectoryPicker provides { _: Long? -> if (pickerCalls++ == 0) unsafe.toString() else safe.toString() },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SettingsDownloadControls(7, f.account.toString())
            }
        }.use { scene ->
            scene.await { scene.texts("下载路径").isNotEmpty() }
            scene.click("下载路径")
            scene.click("自定义目录")
            scene.await { scene.texts(unsafe.toString()).isNotEmpty() }
            scene.click("保存下载路径")
            scene.await { scene.texts(SETTINGS_REJECTED).isNotEmpty() }
            assertEquals(original, store.value)
            assertEquals(0, store.writes)
            assertFalse(Files.exists(unsafe))
            scene.click("取消")
            assertEquals(original, store.value)
            scene.click("下载路径")
            scene.click("自定义目录")
            scene.await { scene.texts(safe.toString()).isNotEmpty() }
            scene.click("保存下载路径")
            scene.await { store.writes == 1 }
            assertEquals(SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, safe.toString()), store.value)
            assertFalse(Files.exists(safe), "Validating a setting must not create the selected directory.")
        }
        assertEquals(2, pickerCalls)
    }

    @Test fun unsafeOrUnknownDefaultNeverSilentlyFallsBackToAccountTemp() {
        for (unknown in listOf(false, true)) fixture(unknown) { f ->
            val source = f.root.resolve("source.bin").also { Files.writeString(it, "source bytes") }
            val default = if (unknown) f.root.resolve("downloads") else f.files.resolve("redirected-downloads")
            val fallback = f.root.resolve("account-temp")
            var pickerCalls = 0
            var dismissals = 0
            scene {
                CompositionLocalProvider(LocalDownloadPreferencesAccess provides Store(SettingsDownloadPreferences(false)),
                    LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(default, fallback) },
                    LocalNativeSavePicker provides { _: String, _: Long? -> pickerCalls++; null },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SaveMediaFile(true, source.toString(), "copy.bin") { dismissals++ }
                }
            }.use { scene ->
                scene.await { scene.texts(SAVE_REJECTED).isNotEmpty() }
                assertFalse(Files.exists(default))
                assertFalse(Files.exists(fallback))
                assertEquals("source bytes", Files.readString(source))
                assertEquals(0, pickerCalls)
                assertEquals(0, dismissals)
                scene.click("取消")
                assertEquals(1, dismissals)
            }
        }
    }

    @Test fun saveAsRejectsExistingProtectedTargetAndSameSourceWithoutOverwritingEither() = fixture { f ->
        val source = f.root.resolve("source.bin").also { Files.writeString(it, "native bytes") }
        val existing = f.database.resolve("existing.bin").also { Files.writeString(it, "earlier copy") }
        var calls = 0
        var dismissals = 0
        scene {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides Store(SettingsDownloadPreferences(true)),
                LocalNativeSavePicker provides { _: String, _: Long? -> if (calls++ == 0) existing.toString() else source.toString() },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "copy.bin", forceChooseLocation = true) { dismissals++ }
            }
        }.use { scene ->
            scene.await { scene.texts(SAVE_REJECTED).isNotEmpty() }
            assertEquals("earlier copy", Files.readString(existing))
            scene.click("重新选择位置")
            scene.await { scene.texts("所选位置已有同名文件，是否替换？").isNotEmpty() }
            scene.click("替换")
            scene.await { scene.texts(SAVE_REJECTED).isNotEmpty() }
            assertEquals("native bytes", Files.readString(source))
            assertEquals("earlier copy", Files.readString(existing))
            assertEquals(0, dismissals)
            scene.click("取消")
            assertEquals(1, dismissals)
        }
        assertEquals(2, calls)
    }

    @Test fun inventoryChangeWhileOverwriteConfirmationIsOpenRequiresAnExplicitRetry() = fixture { f ->
        val source = f.root.resolve("source.bin").also { Files.writeString(it, "new source") }
        val target = f.root.resolve("existing.bin").also { Files.writeString(it, "earlier copy") }
        var calls = 0
        scene {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides Store(SettingsDownloadPreferences(true)),
                LocalNativeSavePicker provides { _: String, _: Long? -> calls++; target.toString() },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "copy.bin", forceChooseLocation = true) {}
            }
        }.use { scene ->
            scene.await { scene.texts("所选位置已有同名文件，是否替换？").isNotEmpty() }
            val unknown = NativeSaveTargets.registry.registerUnknown(99)
            try {
                scene.settledFrame()
                assertEquals(1, calls, "A registry revision must not reopen the picker or start a new save.")
                scene.click("替换")
                scene.await { scene.texts(SAVE_REJECTED).isNotEmpty() }
                assertEquals("new source", Files.readString(source))
                assertEquals("earlier copy", Files.readString(target))
                assertEquals(1, calls)
            } finally { unknown.confirmedClosed() }
        }
    }

    @Test fun partialPublicWriteShowsTheUncertainResultAndNeverFallsBack() = fixture { f ->
        val source = f.root.resolve("source.bin").also { Files.writeString(it, "source bytes") }
        val downloads = f.root.resolve("downloads")
        val fallback = f.root.resolve("account-temp")
        var dismissed = 0
        scene {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides Store(SettingsDownloadPreferences(false)),
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(downloads, fallback) },
                LocalSavedMediaFileIo provides SavedMediaPublicationTest.partialWriteIo(),
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "copy.bin") { dismissed++ }
            }
        }.use { scene ->
            scene.await { scene.texts("保存结果需要核实：目标位置可能已写入，原文件保留。请先检查目标位置，再决定是否重试。").isNotEmpty() }
            assertEquals("sour", Files.readString(downloads.resolve("copy.bin")))
            assertFalse(Files.exists(fallback))
            assertEquals("source bytes", Files.readString(source))
            assertEquals(0, dismissed)
        }
    }

    @Test fun cleanupFailureAfterPublicationNeverFallsBackOrPretendsThatSavingFinished() = fixture { f ->
        val source = f.root.resolve("source.bin").also { Files.writeString(it, "source bytes") }
        val downloads = f.root.resolve("downloads")
        val fallback = f.root.resolve("account-temp")
        val io = object : SavedMediaFileIo by NioSavedMediaFileIo {
            override fun deleteTemporary(temporary: Path) { throw IOException("synthetic cleanup failure") }
        }
        var dismissed = 0
        scene {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides Store(SettingsDownloadPreferences(false)),
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(downloads, fallback) },
                LocalSavedMediaFileIo provides io, LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "copy.bin") { dismissed++ }
            }
        }.use { scene ->
            scene.await { scene.texts("保存结果需要核实：目标位置可能已写入，原文件保留。请先检查目标位置，再决定是否重试。").isNotEmpty() }
            assertEquals("source bytes", Files.readString(downloads.resolve("copy.bin")))
            assertFalse(Files.exists(fallback))
            assertEquals("source bytes", Files.readString(source))
            assertEquals(0, dismissed)
        }
    }

    @Test fun uncertainAutomaticPublicationRendersTheRecoveryNotice() = fixture { _ ->
        val result = AutoMediaCopyResult.Pending(AutoMediaCopyIssue.COMMIT_UNAVAILABLE, publicFileMayExist = true)
        scene {
            SettingsAutoMediaControls(AutoMediaSettings(accountUserId = 43, loaded = true, copyError = true,
                copyNotice = autoMediaCopyNotice(result)), onSave = { _, _, _, _ -> })
        }.use { scene ->
            scene.await { scene.texts("先前自动保存的结果无法安全确认，当前不会改名重试或覆盖文件。").isNotEmpty() }
            assertTrue(scene.texts("自动文件副本保存失败，原生缓存仍保留。").isEmpty())
        }
    }

    @Test fun moveFinishingAfterOwnerChangeRereadsDiskAndDoesNotConsumeTheNewDraft() = fixture { f ->
        val settingsPath = f.root.resolve("device/ui-downloads.properties")
        val enteredMove = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        val file = FileDownloadPreferencesAccess({ settingsPath }) { temporary, target ->
            enteredMove.countDown()
            check(releaseMove.await(5, TimeUnit.SECONDS))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val access = object : DownloadPreferencesAccess by file {
            @Volatile var reads = 0
            override suspend fun read(): DownloadPreferencesRead = file.read().also { reads++ }
        }
        val newDraft = f.root.resolve("new-draft")
        var added: com.badnng.moe.media.NativeSaveTargetRegistry.Registration? = null
        try {
            scene {
                CompositionLocalProvider(LocalDownloadPreferencesAccess provides access,
                    LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(f.root.resolve("downloads"), null) },
                    LocalNativeDirectoryPicker provides { _: Long? -> newDraft.toString() },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SettingsDownloadControls(7, f.account.toString())
                }
            }.use { scene ->
                scene.await { access.reads >= 1 && scene.texts("下载路径").isNotEmpty() }
                scene.click("每次下载都询问保存位置")
                scene.await { enteredMove.count == 0L }
                added = NativeSaveTargets.registry.registerUnknown(88).also { it.accepted(f.database, f.files) }
                scene.await { access.reads >= 2 }
                assertFalse((readDownloadPreferences(settingsPath) as DownloadPreferencesRead.Value).preferences.askEveryTime)
                scene.click("下载路径")
                scene.click("自定义目录")
                scene.await { scene.texts(newDraft.toString()).isNotEmpty() }
                releaseMove.countDown()
                scene.await {
                    access.reads >= 3 && (readDownloadPreferences(settingsPath) as? DownloadPreferencesRead.Value)?.preferences?.askEveryTime == true
                }
                assertTrue(scene.texts(newDraft.toString()).isNotEmpty(), "The current editor remains intact after the reread.")
                scene.click("保存下载路径")
                scene.await { scene.texts("下载设置已在其他位置改变，请重新读取后再保存。").isNotEmpty() }
                assertTrue((readDownloadPreferences(settingsPath) as DownloadPreferencesRead.Value).preferences.askEveryTime)
                assertTrue(scene.texts(newDraft.toString()).isNotEmpty())
                assertFalse(Files.exists(newDraft))
            }
        } finally {
            releaseMove.countDown()
            added?.confirmedClosed()
        }
    }

    @Test fun lateReadFromAnOlderRevisionCannotReplaceTheNewerDisplayedPreference() = fixture { f ->
        val firstStarted = CountDownLatch(1)
        val finishFirst = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val access = object : DownloadPreferencesAccess {
            override val revision = MutableStateFlow(0L)
            override suspend fun read(): DownloadPreferencesRead = if (reads.incrementAndGet() == 1) {
                firstStarted.countDown()
                withContext(NonCancellable) { finishFirst.await() }
                DownloadPreferencesRead.Value(SettingsDownloadPreferences(false))
            } else DownloadPreferencesRead.Value(SettingsDownloadPreferences(true))
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences) = DownloadPreferencesWrite.FAILED
        }
        try {
            scene {
                CompositionLocalProvider(LocalDownloadPreferencesAccess provides access,
                    LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(f.root.resolve("downloads"), null) },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SettingsDownloadControls(7, f.account.toString())
                }
            }.use { scene ->
                scene.await { firstStarted.count == 0L }
                access.revision.value = 1
                scene.await { reads.get() >= 2 && scene.texts("每次下载都询问保存位置").isNotEmpty() }
                assertTrue(scene.texts("下载路径").isEmpty())
                finishFirst.complete(Unit)
                scene.settledFrame(20, 16)
                assertTrue(scene.texts("下载路径").isEmpty())
            }
        } finally { finishFirst.complete(Unit) }
    }

    @Test fun blockedWriteFinishingAfterPageReturnRefreshesTheActivePage() = fixture { f ->
        val path = f.root.resolve("device/ui-downloads.properties")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val file = FileDownloadPreferencesAccess({ path }) { temporary, target ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        val access = object : DownloadPreferencesAccess by file {
            @Volatile var reads = 0
            override suspend fun read(): DownloadPreferencesRead = file.read().also { reads++ }
        }
        var active by mutableStateOf(true)
        try {
            scene {
                CompositionLocalProvider(LocalDownloadPreferencesAccess provides access, LocalSettingsPageActive provides active,
                    LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(f.root.resolve("downloads"), null) },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SettingsDownloadControls(7, f.account.toString())
                }
            }.use { scene ->
                scene.await { access.reads >= 1 && scene.texts("下载路径").isNotEmpty() }
                scene.click("每次下载都询问保存位置")
                scene.await { entered.count == 0L }
                SwingUtilities.invokeAndWait { active = false }
                scene.settledFrame()
                SwingUtilities.invokeAndWait { active = true }
                scene.await { access.reads >= 2 }
                release.countDown()
                scene.await { access.reads >= 3 && scene.texts("下载路径").isEmpty() }
                assertTrue((readDownloadPreferences(path) as DownloadPreferencesRead.Value).preferences.askEveryTime)
            }
        } finally { release.countDown() }
    }

    @Test fun lateSuccessfulWriteCallbackCannotReplaceANewerPersistedValue() = fixture { f ->
        val completedMove = CountDownLatch(1)
        val finishCallback = CompletableDeferred<Unit>()
        val access = object : DownloadPreferencesAccess {
            override val revision = MutableStateFlow(0L)
            @Volatile var value = SettingsDownloadPreferences(false)
            override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences) = DownloadPreferencesWrite.FAILED
            override suspend fun writeIfCurrent(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences,
                active: () -> Boolean): DownloadPreferencesWrite {
                value = desired
                revision.value++
                completedMove.countDown()
                withContext(NonCancellable) { finishCallback.await() }
                return DownloadPreferencesWrite.SAVED
            }
            fun newerWrite(value: SettingsDownloadPreferences) { this.value = value; revision.value++ }
        }
        try {
            scene {
                CompositionLocalProvider(LocalDownloadPreferencesAccess provides access,
                    LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(f.root.resolve("downloads"), null) },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SettingsDownloadControls(7, f.account.toString())
                }
            }.use { scene ->
                scene.await { scene.texts("下载路径").isNotEmpty() }
                scene.click("每次下载都询问保存位置")
                scene.await { completedMove.count == 0L && scene.texts("下载路径").isEmpty() }
                access.newerWrite(SettingsDownloadPreferences(false))
                scene.await { scene.texts("下载路径").isNotEmpty() }
                finishCallback.complete(Unit)
                scene.settledFrame(20, 16)
                assertTrue(scene.texts("下载路径").isNotEmpty())
                assertFalse(access.value.askEveryTime)
            }
        } finally { finishCallback.complete(Unit) }
    }

    private class Store(@Volatile var value: SettingsDownloadPreferences) : DownloadPreferencesAccess {
        @Volatile var writes = 0
        override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
        override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
            writes++
            if (expected != value) return DownloadPreferencesWrite.CONFLICT
            value = desired
            return DownloadPreferencesWrite.SAVED
        }
    }

    private object OfflineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title?.let { Text(it) }
                content()
            }
        }
    }

    private class Fixture(val root: Path) {
        val account = Files.createDirectories(root.resolve("account"))
        val database = Files.createDirectories(account.resolve("database"))
        val files = Files.createDirectories(account.resolve("files"))
    }

    private fun fixture(unknown: Boolean = false, block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("save-target-boundary-ui-")
        val f = Fixture(root)
        val registration = NativeSaveTargets.registry.registerUnknown(7)
        try {
            if (!unknown) registration.accepted(f.database, f.files)
            block(f)
        } finally {
            registration.confirmedClosed()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
                Files.deleteIfExists(path)
            } }
        }
    }

    private companion object {
        const val SETTINGS_REJECTED = "下载路径未保存：目标位于缓存扫描目录内，或当前原生目录无法核实。原设置保留，请重新选择。"
        const val SAVE_REJECTED = "此保存目标无法确认为独立副本位置。原文件保留，请重新选择缓存扫描目录之外的位置。"
        fun scene(content: @Composable () -> Unit) = ComposePixelScene(540, 900) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
        fun ComposePixelScene.texts(value: String): List<Rect> = semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value }
        }.filter { it.width > 0 && it.height > 0 }
        fun ComposePixelScene.click(value: String) {
            val bounds = texts(value).lastOrNull() ?: error("Missing actual control: $value")
            moveTo(bounds.center.x, bounds.center.y)
            pressAt(bounds.center.x, bounds.center.y)
            releaseAt(bounds.center.x, bounds.center.y)
            settledFrame(2, 16)
        }
        fun ComposePixelScene.await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do { settledFrame(2, 16) } while (!condition() && System.nanoTime() < deadline)
            assertTrue(condition(), "The bounded composable callback did not arrive.")
        }
    }
}
