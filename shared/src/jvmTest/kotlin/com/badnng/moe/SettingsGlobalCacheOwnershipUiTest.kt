package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.media.DownloadDestinationRoots
import com.badnng.moe.media.LocalDownloadDestinationRootsResolver
import com.badnng.moe.settings.*
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Full workspace, real pointer and owned storage handle, with synthetic native results only. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SettingsGlobalCacheOwnershipUiTest {
    @Test
    fun confirmationAndRequiredFreshStatisticsRemainReachableAcrossTheFullMatrix() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.enterAdvanced(f)
                    val manager = scene.revealControl("管理本地存储", f)
                    if (width >= 760) {
                        val category = scene.tab(SettingsDestination.ADVANCED)
                        assertTrue(category.full.right < manager.full.left,
                            "The actual category rail and settings body must remain side by side.")
                    }
                    scene.clickControl("管理本地存储", f); scene.settledFrame()
                    val visit = f.opens.single()
                    assertTrue(visit.current())
                    assertEquals(0, f.legacyOpens)
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState,
                        "The actual clearable category is selected when the manager opens.")
                    scene.clickControl(PHOTO, f, visit = visit); scene.settledFrame()
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState,
                        "Clicking the last selected category must leave it selected.")
                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    val confirm = scene.revealControl("确认清理所选缓存", f)
                    assertTrue(confirm.enabled)
                    val cancel = scene.visibleControl("取消", f)
                    assertTrue(cancel.enabled)
                    assertTrue(f.state.scroll.getValue(SettingsDestination.ADVANCED).value > 0,
                        "The actual detail scroller must reach the confirmation's final actions.")
                    scene.settledFrame().save("confirm-${width}x$height-$mode-$density", f)
                    scene.clickControl("取消", f, visit = visit); scene.settledFrame()
                    assertTrue(f.clears.isEmpty(), "Cancel never submits a native clear.")

                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    scene.revealControl("确认清理所选缓存", f)
                    onUi { f.storage = f.storage.copy(needsFreshStatistics = true, lastClearError = SettingsError.FORBIDDEN) }
                    scene.settledFrame()
                    val disabledConfirm = scene.revealControl("确认清理所选缓存", f)
                    assertFalse(disabledConfirm.enabled, "A confirmation from before the failed clear is disabled.")
                    scene.clickControl("确认清理所选缓存", f, enabled = false, visit = visit); scene.settledFrame()
                    assertTrue(f.clears.isEmpty(), "The disabled old confirmation never submits a clear.")
                    scene.clickControl("取消", f, visit = visit); scene.settledFrame()
                    scene.revealText("上次缓存清理未完成", f)
                    val refresh = scene.revealControl("刷新缓存统计", f)
                    assertTrue(refresh.enabled)
                    val disabledClear = scene.visibleControl("清理所选缓存", f)
                    assertFalse(disabledClear.enabled, "A fresh statistics read is required before another clear.")
                    scene.clickControl("清理所选缓存", f, enabled = false, visit = visit); scene.settledFrame()
                    assertTrue(f.clears.isEmpty())

                    // The disabled click and its frame can move layout. Resolve the live
                    // refresh target again instead of reusing its earlier measured bounds.
                    scene.clickControl("刷新缓存统计", f, visit = visit); scene.settledFrame()
                    assertEquals(listOf(f.storage.token), f.refreshes)
                    onUi { f.storage = f.storage.copy(revision = f.storage.revision + 1, needsFreshStatistics = false, error = null) }
                    scene.settledFrame()
                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    scene.clickControl("确认清理所选缓存", f, visit = visit); scene.settledFrame()
                    assertEquals(listOf(Triple(f.storage.token, f.storage.revision, setOf(SettingsStorageFileType.PHOTO))), f.clears,
                        "Only a fresh confirmation after a successful reread may clear the selected category.")
                    scene.revealText("上次缓存清理未完成", f)
                    scene.settledFrame().save("needs-fresh-${width}x$height-$mode-$density", f)
                    assertTrue(scene.revealControl("刷新缓存统计", f).enabled)
                    assertFalse(scene.visibleControl("清理所选缓存", f).enabled,
                        "The synthetic native failure must require another successful statistics read.")
                }
                assertEquals(1, f.opens.single().closes.get())
                assertEquals(0, f.legacyCloses)
            }
    }

    @Test
    fun actualWorkspaceSameFrameReentryRetiresTheOldConfirmationAndItsHandle() {
        val f = Fixture(1000, 700, 1f, ColorSchemeMode.Light).apply { reopenOnClose = true }
        f.scene().use { scene ->
            scene.enterAdvanced(f)
            scene.clickControl("管理本地存储", f); scene.settledFrame()
            val old = f.opens.single()
            assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
            scene.clickControl(PHOTO, f, visit = old); scene.settledFrame()
            assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
            scene.clickControl("清理所选缓存", f, visit = old); scene.settledFrame()
            scene.revealControl("确认清理所选缓存", f)
            val staleApplicationClick = scene.freezeActualClick("确认清理所选缓存")
            scene.clickHeader("返回聊天", f, old)
            assertEquals(1, f.closeCount)
            assertTrue(f.active, "The workspace host reopens in the same input turn.")
            assertFalse(old.current(), "The actual workspace close retires the old visit synchronously.")
            assertEquals(1, old.closes.get())
            onUi { staleApplicationClick() }
            assertTrue(f.clears.isEmpty(), "A captured old confirmation cannot clear before another frame.")

            scene.settledFrame()
            scene.clickControl("管理本地存储", f); scene.settledFrame()
            val fresh = f.opens.last()
            assertEquals(2, f.opens.size)
            assertTrue(fresh.current())
            assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
            scene.clickControl(PHOTO, f, visit = fresh); scene.settledFrame()
            assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
            scene.clickControl("清理所选缓存", f, visit = fresh); scene.settledFrame()
            scene.revealControl("确认清理所选缓存", f)
            onUi { staleApplicationClick() }
            assertTrue(f.clears.isEmpty(), "An old confirmation cannot target the reentered manager.")
            assertTrue(scene.visibleControl("确认清理所选缓存", f).enabled)
            scene.clickControl("确认清理所选缓存", f, visit = fresh); scene.settledFrame()
            assertEquals(listOf(Triple(f.storage.token, f.storage.revision, setOf(SettingsStorageFileType.PHOTO))), f.clears)
            assertEquals(1, old.closes.get())
            assertEquals(0, fresh.closes.get())
        }
        assertEquals(1, f.opens[0].closes.get())
        assertEquals(1, f.opens[1].closes.get())
        assertEquals(0, f.legacyCloses)
    }

    @Test
    fun actualSelectionAndNativeTotalSummaryAcrossTheFullMatrix() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode, SUMMARY_DETAILS)
                f.scene().use { scene ->
                    scene.enterAdvanced(f)
                    scene.clickControl("管理本地存储", f); scene.settledFrame()
                    val visit = f.opens.single()
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
                    assertEquals(ToggleableState.On, scene.revealControl(VIDEO, f).toggleState)
                    assertTrue(scene.byLabel(SECRET).isEmpty(), "The native non-removable category has no toggle.")
                    scene.showSummary(f, "5.0 MB", "6.0 MB", "5 个文件 · 约 83.3%", 5f / 6f)
                    scene.settledFrame().save("all-${width}x$height-$mode-$density", f, "cache-visual")

                    scene.clickControl(VIDEO, f, visit = visit); scene.settledFrame()
                    assertEquals(ToggleableState.Off, scene.revealControl(VIDEO, f).toggleState)
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
                    scene.clickControl(PHOTO, f, visit = visit); scene.settledFrame()
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState,
                        "A real pointer cannot remove the final choice.")
                    scene.showSummary(f, "3.0 MB", "6.0 MB", "3 个文件 · 约 50.0%", .5f)
                    scene.settledFrame().save("one-${width}x$height-$mode-$density", f, "cache-visual")

                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    scene.clickControl("确认清理所选缓存", f, visit = visit); scene.settledFrame()
                    assertEquals(listOf(Triple(f.storage.token, 8L, setOf(SettingsStorageFileType.PHOTO))), f.clears)
                    assertTrue(f.storage.needsFreshStatistics)
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
                    assertEquals(ToggleableState.Off, scene.revealControl(VIDEO, f).toggleState,
                        "The native failure does not reset an existing selection.")

                    onUi { f.storage = f.storage.copy(revision = 9, details = EXPANDED_DETAILS,
                        needsFreshStatistics = false, error = null) }
                    scene.settledFrame()
                    assertEquals(ToggleableState.On, scene.revealControl(DOCUMENT, f).toggleState,
                        "A genuinely new removable type is selected after a fresh read.")
                    assertEquals(ToggleableState.Off, scene.revealControl(VIDEO, f).toggleState,
                        "A surviving deselected type remains deselected after a fresh read.")
                    scene.clickControl(DOCUMENT, f, visit = visit); scene.settledFrame()
                    f.succeedClear = true
                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    scene.clickControl("确认清理所选缓存", f, visit = visit); scene.settledFrame()
                    assertEquals(listOf(
                        Triple(f.storage.token, 8L, setOf(SettingsStorageFileType.PHOTO)),
                        Triple(f.storage.token, 9L, setOf(SettingsStorageFileType.PHOTO)),
                    ), f.clears, "Both actual confirmations retain the precise frozen type and revision.")
                    assertEquals(1L, f.storage.completedClearRevision)
                    assertEquals(ToggleableState.On, scene.revealControl(PHOTO, f).toggleState)
                    assertEquals(ToggleableState.On, scene.revealControl(VIDEO, f).toggleState)
                    assertEquals(ToggleableState.On, scene.revealControl(DOCUMENT, f).toggleState,
                        "A confirmed native success restores all actual removable choices.")
                    scene.showSummary(f, "6.0 MB", "7.0 MB", "6 个文件 · 约 85.7%", 6f / 7f)
                    scene.clickControl("清理所选缓存", f, visit = visit); scene.settledFrame()
                    scene.clickNestedConfirmation(f, visit); scene.settledFrame()
                    assertEquals(listOf(
                        Triple(f.storage.token, 8L, setOf(SettingsStorageFileType.PHOTO)),
                        Triple(f.storage.token, 9L, setOf(SettingsStorageFileType.PHOTO)),
                        Triple(f.storage.token, 10L, setOf(SettingsStorageFileType.PHOTO,
                            SettingsStorageFileType.VIDEO, SettingsStorageFileType.DOCUMENT)),
                    ), f.clears, "The reset confirmation includes all and only the real removable types.")
                    assertEquals(2L, f.storage.completedClearRevision)
                    assertTrue(visit.current())
                }
                assertEquals(1, f.opens.single().closes.get())
                assertEquals(0, f.legacyCloses)
            }
    }

    @Test
    fun unknownInvalidAndEmptyStatisticsDoNotInventACacheChoice() {
        val f = Fixture(1000, 700, 1f, ColorSchemeMode.Light)
        f.scene().use { scene ->
            scene.enterAdvanced(f)
            scene.clickControl("管理本地存储", f); scene.settledFrame()
            val visit = f.opens.single()
            onUi { f.storage = f.storage.copy(details = null) }
            scene.settledFrame()
            scene.revealText("尚无可用统计。", f)
            scene.revealText("占比未知", f)
            assertTrue(scene.byLabel("清理所选缓存").isEmpty())
            assertTrue(scene.controls { it.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null &&
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith("已选缓存 ") } }.isEmpty())

            onUi { f.storage = f.storage.copy(revision = 9, details = SettingsStorageDetails(0, 0, emptyList())) }
            scene.settledFrame()
            scene.revealText("0 个文件 · 暂无缓存量可比较", f)
            assertFalse(scene.revealControl("清理所选缓存", f).enabled)
            scene.clickControl("清理所选缓存", f, enabled = false, visit = visit); scene.settledFrame()
            assertTrue(scene.controls { it.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null &&
                it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith("已选缓存 ") } }.isEmpty())
            assertTrue(f.clears.isEmpty())

            onUi { f.storage = f.storage.copy(revision = 10, details = SettingsStorageDetails(0, 1,
                listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, -1, 1)))) }
            scene.settledFrame()
            scene.revealText("统计无效，请刷新。", f)
            scene.revealText("占比未知", f)
            assertFalse(scene.revealControl("清理所选缓存", f).enabled)
            scene.clickControl("清理所选缓存", f, enabled = false, visit = visit); scene.settledFrame()
            assertTrue(scene.controls { config -> config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("照片 ·") } }.isEmpty())
            assertTrue(f.clears.isEmpty())
            assertTrue(visit.current())
            onUi { f.storage = f.storage.copy(revision = 11, details = SettingsStorageDetails(2L * 1_048_576, 2,
                listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 0, 1),
                    SettingsStorageCategory(SettingsStorageFileType.SECRET_THUMBNAIL, 2L * 1_048_576, 1)))) }
            scene.settledFrame()
            scene.showSummary(f, "0 B", "2.0 MB", "1 个文件 · 约 0.0%", 0f)
            assertTrue(scene.revealControl("清理所选缓存", f).enabled)
            assertTrue(f.clears.isEmpty())
        }
        assertEquals(1, f.opens.single().closes.get())
    }

    @Test
    fun actualVolumeAvailableAndRetryAcrossTheFullMatrix() {
        for ((width, height) in listOf(1000 to 700, 350 to 300))
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { scene ->
                    scene.enterAdvanced(f)
                    scene.clickControl("管理本地存储", f); scene.settledFrame()
                    val visit = f.opens.single()
                    onUi { f.storage = f.storage.copy(volume = SettingsStorageVolume.Available(1_073_741_824L, 536_870_912L)) }
                    scene.settledFrame()
                    scene.revealText("文件目录所在位置", f)
                    scene.revealText("系统报告总容量", f)
                    scene.revealText("当前用户可用", f)
                    val bar = scene.reveal(f) { scene.controls { config ->
                        "文件目录所在位置，系统报告总容量 1.0 GB，当前用户可用 512.0 MB" in
                            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                            config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
                    } }
                    assertEquals(.5f, assertNotNull(bar.progress).current, .0001f)
                    scene.settledFrame().save("available-${width}x$height-$mode-$density", f, "storage-volume")
                    assertTrue(scene.revealControl("刷新设备空间", f).enabled)
                    scene.settledFrame().save("available-action-${width}x$height-$mode-$density", f, "storage-volume")
                    assertTrue(scene.revealControl("清理所选缓存", f).enabled)
                    assertTrue(scene.revealControl("刷新缓存统计", f).enabled)
                    onUi { f.storage = f.storage.copy(volume = SettingsStorageVolume.Unavailable) }
                    scene.settledFrame()
                    scene.revealText("系统报告总容量", f)
                    scene.revealText("当前用户可用", f)
                    assertTrue(scene.controls { config ->
                        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            .any { it.startsWith("文件目录所在位置，系统报告总容量 ") }
                    }.isEmpty(), "Unknown space has no fabricated capacity bar.")
                    val retry = scene.revealControl("重试设备空间", f)
                    assertTrue(retry.enabled)
                    scene.settledFrame().save("unavailable-${width}x$height-$mode-$density", f, "storage-volume")
                    scene.clickControl("重试设备空间", f, visit = visit); scene.settledFrame()
                    assertEquals(listOf(f.storage.token), f.volumeRefreshes)
                    assertFalse(scene.revealControl("正在等待设备空间结果…", f).enabled)
                    assertTrue(f.clears.isEmpty(), "The volume retry does not invoke native cleanup.")
                }
            }
    }

    @Test
    fun zeroUsableAndInvalidCapacityStayDistinctAndOldRetryCannotEscapeTheVisit() {
        val f = Fixture(1000, 700, 1f, ColorSchemeMode.Light).apply { reopenOnClose = true }
        f.scene().use { scene ->
            scene.enterAdvanced(f)
            scene.clickControl("管理本地存储", f); scene.settledFrame()
            val old = f.opens.single()
            onUi { f.storage = f.storage.copy(volume = SettingsStorageVolume.Available(1000, 0)) }
            scene.settledFrame()
            val empty = scene.reveal(f) { scene.controls { config ->
                "文件目录所在位置，系统报告总容量 1000 B，当前用户可用 0 B" in
                    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
            } }
            assertEquals(0f, assertNotNull(empty.progress).current)
            onUi { f.storage = f.storage.copy(volume = SettingsStorageVolume.Available(0, 0)) }
            scene.settledFrame()
            scene.revealControl("重试设备空间", f)
            assertTrue(scene.controls { config ->
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.startsWith("文件目录所在位置，系统报告总容量 ") }
            }.isEmpty())
            val staleRetry = scene.freezeActualClick("重试设备空间")
            scene.clickHeader("返回聊天", f, old)
            onUi { staleRetry() }
            assertTrue(f.volumeRefreshes.isEmpty())
            scene.settledFrame()
            scene.clickControl("管理本地存储", f); scene.settledFrame()
            onUi { staleRetry() }
            assertTrue(f.volumeRefreshes.isEmpty(), "A captured retry from the prior handle cannot target the new visit.")
        }
    }

    private class OpenCall(val current: () -> Boolean, val visit: SettingsStorageVisit) { val closes = AtomicInteger() }

    private class Fixture(val width: Int, val height: Int, val density: Float, val mode: ColorSchemeMode,
        val initialDetails: SettingsStorageDetails = DETAILS) {
        var active by mutableStateOf(true)
        var reopenOnClose = false
        var closeCount = 0
        var legacyOpens = 0
        var legacyCloses = 0
        var succeedClear = false
        val opens = mutableListOf<OpenCall>()
        val refreshes = mutableListOf<SettingsStorageToken>()
        val volumeRefreshes = mutableListOf<SettingsStorageToken>()
        val clears = mutableListOf<Triple<SettingsStorageToken, Long, Set<SettingsStorageFileType>>>()
        val state = SettingsWorkspaceState().apply {
            destination = SettingsDestination.ADVANCED
            detailOpen = width >= 760
        }
        var storage by mutableStateOf(SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
        private val storageActions = SettingsStorageActions(
            open = { legacyOpens++ },
            close = { legacyCloses++ },
            openOwnedObserved = { current, status ->
                val call = OpenCall(current, checkNotNull(state.storageOwner.current)).also(opens::add)
                storage = SettingsStorageSnapshot(token = SettingsStorageToken(17, opens.size.toLong()),
                    opened = true, revision = 8, details = initialDetails)
                status(storage.token)
                SettingsStorageHandle { call.closes.incrementAndGet() }
            },
            refresh = { token -> refreshes += token },
            refreshVolume = { token -> volumeRefreshes += token; storage = storage.copy(volume = SettingsStorageVolume.Loading) },
            clear = { token, revision, types ->
                clears += Triple(token, revision, types)
                storage = if (succeedClear) storage.copy(clearing = false, needsFreshStatistics = false,
                    revision = storage.revision + 1, completedClearRevision = storage.completedClearRevision + 1,
                    lastClearError = null, error = null)
                else storage.copy(clearing = false, needsFreshStatistics = true,
                    lastClearError = SettingsError.FORBIDDEN, error = SettingsError.FORBIDDEN)
            },
        )
        private val downloads = object : DownloadPreferencesAccess {
            private var value = SettingsDownloadPreferences()
            override suspend fun read(): DownloadPreferencesRead = DownloadPreferencesRead.Value(value)
            override suspend fun write(expected: SettingsDownloadPreferences, desired: SettingsDownloadPreferences): DownloadPreferencesWrite {
                if (expected != value) return DownloadPreferencesWrite.CONFLICT
                value = desired
                return DownloadPreferencesWrite.SAVED
            }
        }
        private val runtime = RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 17,
            chats = ChatSnapshot(), settings = SettingsSnapshot(token = SettingsToken(17, 3), enabled = true,
                loaded = setOf(SettingsSection.ADVANCED)))

        fun scene() = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val preferences = remember { SettingsPreferences(theme = mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPreferences provides preferences,
                LocalDownloadPreferencesAccess provides downloads,
                LocalDownloadDestinationRootsResolver provides { _, _ -> DownloadDestinationRoots(
                    java.nio.file.Path.of("C:/Synthetic/Downloads/nngGram"), java.nio.file.Path.of("C:/Synthetic/Account/downloads-temp/user-1")) },
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp),
                LocalSettingsStorageSnapshot provides storage, LocalSettingsStorageActions provides storageActions,
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        Box(Modifier.requiredSize(width.dp, height.dp)) {
                            SettingsWorkspace(runtime, SettingsActions(), ChatFeatureActions(), preferences, {}, ChatFolderPreferences(), {},
                                onClose = {
                                    closeCount++
                                    active = false
                                    if (reopenOnClose) active = true
                                }, onOpenConfig = {}, state = state, active = active)
                        }
                    }
                }
            }
        }
    }

    /** Only the native window is replaced; the production Miuix dialog body and buttons are unchanged. */
    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth)
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }

    private data class Control(val full: Rect, val clipped: Rect, val enabled: Boolean,
        val toggleState: ToggleableState?, val progress: ProgressBarRangeInfo?)

    /** Compare the unclipped semantics position/size with the actual clipped bounds. */
    private fun ComposePixelScene.controls(predicate: (SemanticsConfiguration) -> Boolean): List<Control> {
        var result = emptyList<Control>()
        onUi {
            val sceneField = ComposePixelScene::class.java.getDeclaredField("scene")
            check(sceneField.type == ImageComposeScene::class.java && sceneField.trySetAccessible())
            val scene = sceneField.get(this) as ImageComposeScene
            result = buildList {
                fun visit(node: SemanticsNode) {
                    if (predicate(node.config)) {
                        val position = node.positionInRoot
                        val size = node.size
                        add(Control(Rect(position.x, position.y, position.x + size.width, position.y + size.height),
                            node.boundsInRoot, node.config.getOrNull(SemanticsProperties.Disabled) == null,
                            node.config.getOrNull(SemanticsProperties.ToggleableState),
                            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)))
                    }
                    node.children.forEach(::visit)
                }
                scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
            }
        }
        return result
    }

    private fun ComposePixelScene.byLabel(label: String) = controls { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
            config.getOrNull(SemanticsActions.OnClick)?.action != null
    }
    private fun ComposePixelScene.tab(destination: SettingsDestination) = assertNotNull(controls { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == destination.title }
    }.firstOrNull(), destination.title)
    private fun ComposePixelScene.headerAction(description: String, f: Fixture): Rect {
        val target = assertNotNull(controls { config ->
            description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                config.getOrNull(SemanticsActions.OnClick)?.action != null
        }.firstOrNull(), description)
        assertFullyVisible(target, f, 0f)
        assertTrue(target.enabled, "The actual header action must be enabled: $description")
        return target.full
    }
    private fun ComposePixelScene.enterAdvanced(f: Fixture) {
        settledFrame()
        if (f.width < 760) {
            val category = reveal(f, header = false) { controls { config ->
                config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == SettingsDestination.ADVANCED.title }
            } }
            assertTrue(category.enabled)
            click(category.full); settledFrame()
            assertTrue(f.state.detailOpen)
        }
    }
    private fun ComposePixelScene.revealControl(label: String, f: Fixture) = reveal(f) { byLabel(label) }
    private fun ComposePixelScene.visibleControl(label: String, f: Fixture): Control {
        val target = assertNotNull(byLabel(label).firstOrNull { fullyVisible(it, f, 64f * f.density) },
            "Full current bounds must be visible for $label: ${byLabel(label)}")
        assertFullyVisible(target, f, 64f * f.density)
        return target
    }
    private fun ComposePixelScene.revealText(label: String, f: Fixture) {
        reveal(f) { controls { config -> config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } } }
    }

    private fun ComposePixelScene.showSummary(f: Fixture, amount: String, total: String, description: String, fraction: Float) {
        fun textControl(value: String) = controls { config ->
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
        }.firstOrNull { it.full.width > 0 && it.full.height > 0 }
        fun summaryBounds(): List<Control> {
            val bar = controls { config -> config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null &&
                "已选缓存 $amount，缓存总量 $total，$description" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.firstOrNull()
                ?: return emptyList()
            val label = textControl("已选缓存") ?: return emptyList()
            val value = textControl(amount) ?: return emptyList()
            val alternative = textControl(description) ?: return emptyList()
            val parts = listOf(label, value, alternative, bar)
            fun union(rects: List<Rect>) = Rect(rects.minOf { it.left }, rects.minOf { it.top },
                rects.maxOf { it.right }, rects.maxOf { it.bottom })
            return listOf(Control(union(parts.map { it.full }), union(parts.map { it.clipped }), true, null, bar.progress))
        }
        var stable: Control? = null
        repeat(4) {
            if (stable == null) {
                reveal(f) { summaryBounds() }
                settledFrame()
                stable = summaryBounds().firstOrNull { fullyVisible(it, f, 64f * f.density) }
            }
        }
        val summary = assertNotNull(stable, "The complete summary must remain visible after scroll settling.")
        assertEquals(fraction, assertNotNull(summary.progress).current, .0001f)
        assertFullyVisible(summary, f, 64f * f.density)
        for (value in listOf("已选缓存", amount, description))
            assertTrue(textControl(value)?.let { fullyVisible(it, f, 64f * f.density) } == true,
                "The summary text must be fully visible: $value")
    }

    private fun ComposePixelScene.reveal(f: Fixture, header: Boolean = true, candidates: () -> List<Control>): Control {
        val minimum = if (header) 64f * f.density else 0f
        val bottom = f.height * f.density
        var direction = 1; var step = 7f * f.density; var previous = 0
        repeat(220) {
            candidates().firstOrNull { fullyVisible(it, f, minimum) }?.let {
                assertFullyVisible(it, f, minimum)
                return it
            }
            val target = candidates().firstOrNull { it.full.width > 0 && it.full.height > 0 }
            var position = 0f; var maximum = 0f
            semanticsBounds { config -> config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                position = it.value(); maximum = it.maxValue()
            }; config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            direction = if (target != null) when {
                target.full.top < minimum || target.clipped.top > target.full.top + .75f -> -1
                else -> 1
            } else if (position <= .5f) 1 else if (position >= maximum - .5f) -1 else direction
            if (previous != 0 && previous != direction) step = (step / 2).coerceAtLeast(.02f)
            previous = direction
            val x = if (header && f.width >= 760) f.width * f.density * .78f else f.width * f.density / 2f
            scrollAt(x, bottom * .65f, deltaY = direction * step)
            settledFrame(4, 16)
        }
        return assertNotNull(candidates().firstOrNull { fullyVisible(it, f, minimum) },
            "Actual pointer scrolling must expose a complete control at ${f.width}x${f.height}: ${candidates()}")
    }

    private fun fullyVisible(target: Control, f: Fixture, minimum: Float): Boolean {
        val full = target.full; val clipped = target.clipped
        return full.width > 0f && full.height > 0f && full.left >= 0f && full.right <= f.width * f.density &&
            full.top >= minimum && full.bottom <= f.height * f.density &&
            abs(full.left - clipped.left) <= .75f && abs(full.top - clipped.top) <= .75f &&
            abs(full.right - clipped.right) <= .75f && abs(full.bottom - clipped.bottom) <= .75f
    }
    private fun assertFullyVisible(target: Control, f: Fixture, minimum: Float) {
        assertTrue(fullyVisible(target, f, minimum), "Full bounds ${target.full} must match clipped ${target.clipped} within the viewport.")
    }
    private fun Fixture.assertOwned(call: OpenCall) {
        assertTrue(call.current(), "The owned native witness must still be live before pointer input.")
        assertTrue(state.storageOwner.owns(call.visit), "The actual workspace must still own this exact visit.")
        assertEquals(0, call.closes.get(), "The current owned handle cannot already be closed.")
    }
    private fun ComposePixelScene.clickControl(label: String, f: Fixture, enabled: Boolean = true, visit: OpenCall? = null) {
        val current = revealControl(label, f)
        assertEquals(enabled, current.enabled, "The current complete control must have the expected state: $label")
        onUi {
            if (visit != null) f.assertOwned(visit)
            click(current.full)
        }
    }
    private fun ComposePixelScene.clickHeader(description: String, f: Fixture, visit: OpenCall) {
        val current = headerAction(description, f)
        onUi {
            f.assertOwned(visit)
            click(current)
        }
    }
    private fun ComposePixelScene.clickNestedConfirmation(f: Fixture, visit: OpenCall) {
        val current = revealCacheConfirmation(f.width * f.density, f.height * f.density,
            64f * f.density, f.density)
        assertTrue(current.enabled, "The fully visible confirmation must be enabled.")
        onUi {
            f.assertOwned(visit)
            click(current.full)
        }
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }

    /** Freeze the actual Foundation 1.12.1 application onClick while its semantics node is attached. */
    private fun ComposePixelScene.freezeActualClick(label: String): () -> Unit {
        var captured: (() -> Unit)? = null
        semanticsBounds { config ->
            val action = config.getOrNull(SemanticsActions.OnClick)?.action
            if (config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                config.getOrNull(SemanticsProperties.Disabled) == null && action != null) {
                captured = freezeApplicationClick(action, label)
                true
            } else false
        }
        return assertNotNull(captured, "The actual enabled control must expose its callback: $label")
    }
    private fun freezeApplicationClick(semantics: () -> Boolean, label: String): () -> Unit {
        val wrapper = semantics.javaClass
        val nodeType = Class.forName("androidx.compose.foundation.AbstractClickableNode", false, wrapper.classLoader)
        check(wrapper.isHidden && wrapper.isSynthetic && wrapper.nestHost == nodeType &&
            wrapper.name.startsWith("androidx.compose.foundation.AbstractClickableNode\$\$Lambda") &&
            wrapper.interfaces.singleOrNull() == Function0::class.java) { "Unexpected Foundation wrapper for $label: ${wrapper.name}" }
        val capture = wrapper.declaredFields.singleOrNull() ?: error("Expected one Foundation node capture for $label")
        check(capture.name == "arg\$1" && capture.type == nodeType &&
            java.lang.reflect.Modifier.isPrivate(capture.modifiers) && java.lang.reflect.Modifier.isFinal(capture.modifiers) &&
            capture.trySetAccessible()) { "Unexpected Foundation node capture for $label" }
        val node = capture.get(semantics) ?: error("Missing Foundation node for $label")
        val clickableType = Class.forName("androidx.compose.foundation.ClickableNode", false, wrapper.classLoader)
        check(node.javaClass == clickableType && nodeType.isInstance(node) &&
            clickableType.getMethod("isAttached").invoke(node) == true) { "Expected an attached ClickableNode for $label" }
        val field = nodeType.getDeclaredField("onClick")
        val signature = field.genericType as? java.lang.reflect.ParameterizedType ?: error("Expected Function0<Unit> for $label")
        check(field.type == Function0::class.java && java.lang.reflect.Modifier.isPrivate(field.modifiers) &&
            !java.lang.reflect.Modifier.isStatic(field.modifiers) && signature.rawType == Function0::class.java &&
            signature.actualTypeArguments.singleOrNull() == Unit::class.java && field.trySetAccessible()) {
            "Unexpected Foundation onClick field for $label"
        }
        val application = field.get(node) as? Function0<*> ?: error("Missing actual application callback for $label")
        return { check(application.invoke() === Unit) { "Unexpected application callback return for $label" } }
    }

    private fun ComposePixelFrame.save(name: String, f: Fixture, directory: String = "global-cache-ownership") {
        val image = BufferedImage((f.width * f.density).roundToInt(), (f.height * f.density).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, this[x, y])
        val target = File("build/settings-workspace-validation/$directory/$name.png")
        target.parentFile.mkdirs()
        assertTrue(ImageIO.write(image, "png", target))
    }
    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    companion object {
        private const val PHOTO = "照片 · 3.0 MB · 3 个"
        private const val VIDEO = "视频 · 2.0 MB · 2 个"
        private const val DOCUMENT = "文件 · 1.0 MB · 1 个"
        private const val SECRET = "私密聊天缩略图"
        private val DETAILS = SettingsStorageDetails(3L * 1_048_576, 3,
            listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 3L * 1_048_576, 3)))
        private val SUMMARY_DETAILS = SettingsStorageDetails(6L * 1_048_576, 6, listOf(
            SettingsStorageCategory(SettingsStorageFileType.PHOTO, 3L * 1_048_576, 3),
            SettingsStorageCategory(SettingsStorageFileType.VIDEO, 2L * 1_048_576, 2),
            SettingsStorageCategory(SettingsStorageFileType.SECRET_THUMBNAIL, 1_048_576, 1)))
        private val EXPANDED_DETAILS = SettingsStorageDetails(7L * 1_048_576, 7, listOf(
            SettingsStorageCategory(SettingsStorageFileType.PHOTO, 3L * 1_048_576, 3),
            SettingsStorageCategory(SettingsStorageFileType.VIDEO, 2L * 1_048_576, 2),
            SettingsStorageCategory(SettingsStorageFileType.DOCUMENT, 1_048_576, 1),
            SettingsStorageCategory(SettingsStorageFileType.SECRET_THUMBNAIL, 1_048_576, 1)))
    }
}
