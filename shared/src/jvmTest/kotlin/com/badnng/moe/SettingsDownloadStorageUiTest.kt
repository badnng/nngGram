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
import com.badnng.moe.media.LocalNativeDirectoryPicker
import com.badnng.moe.media.LocalNativeSavePicker
import com.badnng.moe.media.SaveMediaFile
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.media.accountTemporaryDownloadDirectory
import com.badnng.moe.settings.*
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.basic.Text

class SettingsDownloadStorageUiTest {
    @Test fun actualCacheControlsFreezeExactTargetAndCancelNeverRequestsDeletion() {
        val fixture = StorageFixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            scene.settledFrame()
            assertEquals(1, fixture.opens)
            scene.reveal("照片 · 3.0 MB · 3 个", 390, 360)
            scene.clickText("照片 · 3.0 MB · 3 个")
            scene.settledFrame()
            scene.reveal("清理所选缓存", 390, 360)
            scene.clickText("清理所选缓存")
            scene.settledFrame()
            scene.reveal("取消", 390, 360)
            scene.clickText("取消")
            scene.settledFrame()
            assertTrue(fixture.clears.isEmpty())
            scene.reveal("清理所选缓存", 390, 360)
            scene.clickText("清理所选缓存")
            scene.settledFrame()
            scene.reveal("确认清理所选缓存", 390, 360)
            scene.clickText("确认清理所选缓存")
            scene.settledFrame()
            assertEquals(listOf(Triple(SettingsStorageToken(7, 2), 8L, setOf(SettingsStorageFileType.PHOTO))), fixture.clears)
        }
        assertTrue(fixture.closes.isNotEmpty())
    }

    @Test fun refreshedCacheRevisionDisablesAnOldConfirmation() {
        val fixture = StorageFixture(height = 760)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            scene.settledFrame()
            scene.reveal("照片 · 3.0 MB · 3 个", 390, fixture.height)
            scene.clickText("照片 · 3.0 MB · 3 个")
            scene.reveal("清理所选缓存", 390, fixture.height)
            scene.clickText("清理所选缓存")
            scene.settledFrame()
            scene.reveal("确认清理所选缓存", 390, fixture.height)
            assertTrue(scene.texts("确认清理所选缓存").isNotEmpty(), "The actual confirmation must be opened and reachable before its source revision changes.")
            fixture.onUi { fixture.snapshot = fixture.snapshot.copy(revision = 9) }
            scene.settledFrame()
            scene.reveal("确认清理所选缓存", 390, fixture.height)
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "确认清理所选缓存" } &&
                    it.getOrNull(SemanticsProperties.Disabled) != null
            }.isNotEmpty(), "The actual stale confirmation button must expose disabled semantics.")
            scene.clickText("确认清理所选缓存")
            scene.settledFrame()
            assertTrue(fixture.clears.isEmpty())
        }
    }

    @Test fun failedCleanupDisablesAnAlreadyOpenConfirmationUntilFreshStatistics() {
        val fixture = StorageFixture(height = 760)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            scene.reveal("照片 · 3.0 MB · 3 个", 390, fixture.height)
            scene.clickText("照片 · 3.0 MB · 3 个")
            scene.reveal("清理所选缓存", 390, fixture.height)
            scene.clickText("清理所选缓存")
            scene.reveal("确认清理所选缓存", 390, fixture.height)
            fixture.onUi {
                fixture.snapshot = fixture.snapshot.copy(needsFreshStatistics = true, lastClearError = SettingsError.FORBIDDEN)
            }
            scene.settledFrame()
            assertFalse(scene.revealCacheConfirmation(390f, fixture.height.toFloat(), 0f, 1f).enabled,
                "The current confirmation must still be fully visible and disabled after the error row moves it.")
            assertTrue(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "确认清理所选缓存" } &&
                    it.getOrNull(SemanticsProperties.Disabled) != null
            }.isNotEmpty())
            scene.clickText("确认清理所选缓存")
            assertTrue(fixture.clears.isEmpty())
            fixture.onUi { fixture.snapshot = fixture.snapshot.copy(needsFreshStatistics = false, revision = 9) }
            scene.settledFrame()
            assertFalse(scene.revealCacheConfirmation(390f, fixture.height.toFloat(), 0f, 1f).enabled,
                "The prior revision stays disabled after the fresh read.")
            scene.clickText("确认清理所选缓存")
            assertTrue(fixture.clears.isEmpty(), "A successful reread requires a new confirmation.")
        }
    }

    @Test fun downloadToggleFailureDoesNotPretendThePreferenceWasSaved() {
        val store = Store(SettingsDownloadPreferences(false))
        store.fail = true
        var pickerCalls = 0
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalNativeDirectoryPicker provides { _: Long? -> pickerCalls++; null }) {
                SettingsDownloadControls(7, null)
            }
        }.use { scene ->
            scene.settledFrame()
            scene.clickText("每次下载都询问保存位置")
            scene.settledFrame()
            assertFalse(store.value.askEveryTime)
            assertEquals(1, store.writes)
            assertEquals(0, pickerCalls)
            assertTrue(scene.texts("下载设置未保存；原设置保留，请检查配置目录权限。").isNotEmpty())
            assertTrue(scene.texts("下载路径").isNotEmpty())
        }
    }

    @Test fun actualSaveConsumerUsesCustomDirectoryAndNeverOpensTheChooserWhenAskIsOff() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "synthetic native bytes") }
        val directory = root.resolve("copies")
        val store = Store(SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, directory.toString()))
        var pickerCalls = 0
        var dismissed = 0
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalNativeSavePicker provides { _: String, _: Long? -> pickerCalls++; null },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "file.txt") { dismissed++ }
            }
        }.use { scene ->
            repeat(100) { scene.settledFrame(2, 16); if (dismissed > 0) return@repeat }
            assertEquals(0, pickerCalls)
            assertEquals(1, dismissed)
            assertEquals("synthetic native bytes", Files.readString(directory.resolve("file.txt")))
            assertEquals(0, store.writes)
        }
    }

    @Test fun cancelledOrMalformedChooserNeverWritesAnAttachmentOrChangesPreferences() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "synthetic bytes") }
        val store = Store(SettingsDownloadPreferences(true))
        var dismissed = 0
        var calls = 0
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalNativeSavePicker provides { _: String, _: Long? -> calls++; null }) {
                SaveMediaFile(true, source.toString(), "cancel.txt") { dismissed++ }
            }
        }.use {
            it.awaitActualCallback { dismissed == 1 }
            assertEquals(1, dismissed)
            assertEquals(1, calls)
        }
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalNativeSavePicker provides { _: String, _: Long? -> "bad\u0000path" },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "bad.txt") { dismissed++ }
            }
        }.use {
            it.awaitActualCallback { it.texts("保存失败。请确认目标目录可写、空间足够，且附件未被清理。").isNotEmpty() }
            assertTrue(it.texts("保存失败。请确认目标目录可写、空间足够，且附件未被清理。").isNotEmpty())
        }
        assertEquals(0, store.writes)
        Files.list(root).use { assertEquals(1L, it.count()) }
    }

    @Test fun explicitSaveAsAlwaysChoosesDespiteTheOrdinaryAutomaticDestinationPreference() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "explicit save as bytes") }
        val ordinary = root.resolve("ordinary")
        val target = root.resolve("chosen.txt")
        val store = Store(SettingsDownloadPreferences(false, SettingsDownloadDirectory.CUSTOM, ordinary.toString()))
        var calls = 0
        var dismissed = 0
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalNativeSavePicker provides { _: String, _: Long? -> calls++; target.toString() },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SaveMediaFile(true, source.toString(), "ordinary.txt", forceChooseLocation = true) { dismissed++ }
            }
        }.use { scene ->
            repeat(100) { scene.settledFrame(2, 16) }
            assertEquals(1, calls)
            assertEquals(1, dismissed)
            assertEquals("explicit save as bytes", Files.readString(target))
            assertFalse(Files.exists(ordinary))
            assertEquals(0, store.writes)
        }
    }

    @Test fun actualTemporaryCopiesFollowTheCurrentNativeAccountAndUnknownIdentityNeverFallsBackToAnotherAccount() = temporary { root ->
        val source = root.resolve("source.bin").also { Files.writeString(it, "account-owned bytes") }
        val store = Store(SettingsDownloadPreferences(false, SettingsDownloadDirectory.ACCOUNT_TEMP))
        var account by mutableStateOf<Long?>(123)
        var showing by mutableStateOf(true)
        var dismissed = 0
        var pickerCalls = 0
        scene(390, 360) {
            CompositionLocalProvider(LocalDownloadPreferencesAccess provides store,
                LocalDownloadAccountDataPath provides root.toString(), LocalDownloadAccountUserId provides account,
                LocalDownloadDestinationRootsResolver provides { path, userId ->
                    DownloadDestinationRoots(null, accountTemporaryDownloadDirectory(path, userId))
                }, LocalNativeSavePicker provides { _: String, _: Long? -> pickerCalls++; null },
                LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                if (showing) SaveMediaFile(true, source.toString(), "account.txt") { dismissed++; showing = false }
            }
        }.use { scene ->
            scene.awaitActualCallback { dismissed == 1 }
            assertEquals("account-owned bytes", Files.readString(root.resolve("downloads-temp/user-123/account.txt")))
            SwingUtilities.invokeAndWait { account = 456; showing = true }
            scene.awaitActualCallback { dismissed == 2 }
            assertEquals("account-owned bytes", Files.readString(root.resolve("downloads-temp/user-456/account.txt")))
            SwingUtilities.invokeAndWait { account = null; showing = true }
            scene.awaitActualCallback { scene.texts("保存失败。请确认目标目录可写、空间足够，且附件未被清理。").isNotEmpty() }
            assertEquals(2, dismissed)
            assertEquals(0, pickerCalls)
            Files.list(root.resolve("downloads-temp")).use { assertEquals(setOf("user-123", "user-456"), it.map { path -> path.fileName.toString() }.toList().toSet()) }
            assertFalse(Files.exists(root.resolve("downloads-temp/account.txt")))
        }
    }

    private class Store(var value: SettingsDownloadPreferences) : DownloadPreferencesAccess {
        var fail = false
        var writes = 0
        override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
        override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
            writes++
            if (fail) return DownloadPreferencesWrite.FAILED
            if (expected != value) return DownloadPreferencesWrite.CONFLICT
            value = desired
            return DownloadPreferencesWrite.SAVED
        }
    }

    private class StorageFixture(val height: Int = 360) {
        var snapshot by mutableStateOf(SettingsStorageSnapshot(SettingsStorageToken(7, 2), opened = true, revision = 8,
            details = SettingsStorageDetails(3_145_728, 3, listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 3_145_728, 3)))))
        var opens = 0
        val clears = mutableListOf<Triple<SettingsStorageToken, Long, Set<SettingsStorageFileType>>>()
        val closes = mutableListOf<SettingsStorageToken>()
        fun scene() = scene(390, height) {
            CompositionLocalProvider(LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                SettingsStorageControls(snapshot, SettingsStorageActions(open = { opens++ }, close = closes::add,
                    clear = { token, revision, types -> clears += Triple(token, revision, types) }))
            }
        }
        fun onUi(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    }

    private object OfflineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).background(MiuixTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title?.let { Text(it) }
                content()
            }
        }
    }

    private companion object {
        fun scene(width: Int, height: Int, content: @Composable () -> Unit): ComposePixelScene = ComposePixelScene(width, height) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                CompositionLocalProvider(LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), null) }) {
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
                }
            }
        }
        fun ComposePixelScene.texts(label: String): List<Rect> = semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
        }.filter { it.width > 0 && it.height > 0 }
        fun ComposePixelScene.clickText(label: String) {
            val bounds = texts(label).lastOrNull() ?: error("Missing actual control: $label")
            moveTo(bounds.center.x, bounds.center.y)
            pressAt(bounds.center.x, bounds.center.y)
            releaseAt(bounds.center.x, bounds.center.y)
            settledFrame(2, 16)
        }
        fun ComposePixelScene.reveal(label: String, width: Int, height: Int) {
            repeat(30) {
                val bounds = texts(label).lastOrNull()
                if (bounds != null && bounds.top >= 0 && bounds.bottom < height) return
                scrollAt(width * .7f, height * .6f, deltaY = 5f)
                settledFrame(3, 32)
            }
            assertTrue(texts(label).isNotEmpty(), "Unreachable actual control: $label")
        }
        fun ComposePixelScene.awaitActualCallback(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            do { settledFrame(2, 16) } while (!condition() && System.nanoTime() < deadline)
            assertTrue(condition(), "The real asynchronous consumer callback did not arrive within the bounded wait.")
        }
        fun temporary(block: (java.nio.file.Path) -> Unit) {
            val root = Files.createTempDirectory("nnggram-download-consumer-ui-")
            try { block(root) } finally { root.toFile().deleteRecursively() }
        }
    }
}
