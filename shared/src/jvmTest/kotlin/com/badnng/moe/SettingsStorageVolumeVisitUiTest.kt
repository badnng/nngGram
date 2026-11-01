package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual storage controls and pointer input; the owned handle and snapshots are synthetic. */
class SettingsStorageVolumeVisitUiTest {
    @Test
    fun rejectedOwnedOpenIsAnExplicitFailureAndASecondAttemptCanRecover() {
        val f = Fixture(denyFirst = true, publishFirst = false, initial = OLD)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            assertEquals(1, f.attempts)
            assertTrue(scene.hasText("无法打开本地存储"))
            assertFalse(scene.hasText("正在等待设备空间结果…"), "A rejected open never started volume work")
            assertFalse(scene.hasText(OLD_CATEGORY))
            assertFalse(scene.hasDescription(OLD_VOLUME))
            assertTrue(f.volumeRefreshes.isEmpty())

            scene.clickText("管理本地存储")
            assertEquals(2, f.attempts)
            assertTrue(f.active.single().invoke())
            assertFalse(scene.hasText(OLD_CATEGORY), "The prior snapshot is still owned by the prior visit")
            assertFalse(scene.hasDescription(OLD_VOLUME))
            assertTrue(f.volumeRefreshes.isEmpty())

            onUi { f.statuses.last()(FRESH.token); f.snapshot = FRESH }
            scene.settledFrame()
            assertTrue(scene.hasDescription(FRESH_VOLUME))
            assertTrue(scene.hasText(FRESH_CATEGORY))
            scene.clickText("刷新设备空间")
            assertEquals(listOf(FRESH.token), f.volumeRefreshes)
            assertTrue(f.clears.isEmpty())
        }
        assertEquals(listOf(2), f.closed)
    }

    @Test
    fun reopeningHidesOldVolumeCacheAndConfirmationUntilTheNewTokenArrives() {
        val f = Fixture(denyFirst = false, publishFirst = true,
            initial = SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            assertTrue(scene.hasDescription(OLD_VOLUME))
            assertTrue(scene.hasText(OLD_CATEGORY))
            scene.clickText("清理所选缓存")
            assertTrue(scene.hasText("确认清理所选缓存"))

            scene.clickText("管理本地存储")
            assertEquals(2, f.attempts)
            assertEquals(listOf(1), f.closed)
            assertFalse(f.active.first().invoke())
            assertTrue(f.active.last().invoke())
            assertFalse(scene.hasText("确认清理所选缓存"))
            assertFalse(scene.hasDescription(OLD_VOLUME))
            assertFalse(scene.hasText(OLD_CATEGORY))
            assertTrue(f.clears.isEmpty())
            assertTrue(f.volumeRefreshes.isEmpty())

            onUi { f.statuses.last()(FRESH.token); f.snapshot = FRESH }
            scene.settledFrame()
            assertTrue(scene.hasDescription(FRESH_VOLUME))
            assertTrue(scene.hasText(FRESH_CATEGORY))
            scene.clickText("刷新设备空间")
            assertEquals(listOf(FRESH.token), f.volumeRefreshes)
            assertTrue(f.clears.isEmpty())
        }
        assertEquals(listOf(1, 2), f.closed)
    }

    @Test
    fun exactAdmissionReceiptAndSnapshotMayArriveInEitherOrderButOldVisitCannotAuthorizeEither() {
        for (receiptFirst in listOf(false, true)) {
            val f = Fixture(denyFirst = false, publishFirst = false,
                initial = SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
            f.scene().use { scene ->
                scene.settledFrame()
                scene.clickText("管理本地存储") // A is admitted, but A's snapshot has not reached the UI.
                onUi { f.statuses[0](OLD.token) }
                scene.settledFrame()
                scene.clickText("管理本地存储") // B still sees token 0 as the previous snapshot.
                assertEquals(listOf(1), f.closed)
                assertEquals(2, f.attempts)
                onUi { f.snapshot = OLD }
                scene.settledFrame()
                assertFalse(scene.hasDescription(OLD_VOLUME), "A's late value cannot authorize B")
                assertFalse(scene.hasText(OLD_CATEGORY))
                assertTrue(f.volumeRefreshes.isEmpty())

                if (receiptFirst) onUi { f.statuses[1](FRESH.token) }
                else onUi { f.snapshot = FRESH }
                scene.settledFrame()
                assertFalse(scene.hasDescription(FRESH_VOLUME), "B needs both its exact receipt and its matching snapshot")
                assertFalse(scene.hasText(FRESH_CATEGORY))
                if (receiptFirst) onUi { f.snapshot = FRESH }
                else onUi { f.statuses[1](FRESH.token) }
                scene.settledFrame()
                assertTrue(scene.hasDescription(FRESH_VOLUME))
                assertTrue(scene.hasText(FRESH_CATEGORY))

                onUi { f.statuses[0](null); f.statuses[0](OLD.token) }
                scene.settledFrame()
                assertTrue(scene.hasDescription(FRESH_VOLUME), "A late old status cannot close B")
                scene.clickText("刷新设备空间")
                assertEquals(listOf(FRESH.token), f.volumeRefreshes)
            }
            assertEquals(listOf(1, 2), f.closed)
        }
    }

    @Test
    fun observedFailureBeforeHandleAndLateStatusesCannotReviveARevokedVisit() {
        val f = Fixture(denyFirst = false, publishFirst = false, rejectFirstSynchronously = true,
            initial = SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            assertTrue(scene.hasText("无法打开本地存储"))
            assertFalse(scene.hasText("正在等待设备空间结果…"))
            assertEquals(listOf(1), f.closed, "A handle returned after a synchronous failure is closed")

            scene.clickText("管理本地存储")
            onUi { f.statuses[1](FRESH.token); f.snapshot = FRESH }
            scene.settledFrame()
            assertTrue(scene.hasDescription(FRESH_VOLUME))
            onUi { f.statuses[0](null) }
            scene.settledFrame()
            assertTrue(scene.hasDescription(FRESH_VOLUME), "A's delayed failure cannot close B")

            // Simulate a delayed stale success arriving after this visit's revocation, before a frame.
            onUi { f.statuses[1](null); f.statuses[1](FRESH.token) }
            scene.settledFrame()
            assertTrue(scene.hasText("无法打开本地存储"))
            assertFalse(scene.hasDescription(FRESH_VOLUME))
            assertEquals(listOf(1, 2), f.closed)
            assertTrue(f.volumeRefreshes.isEmpty())

            scene.clickText("管理本地存储")
            onUi { f.statuses[2](THIRD.token); f.snapshot = THIRD }
            scene.settledFrame()
            assertTrue(scene.hasDescription(THIRD_VOLUME))
            scene.clickText("刷新设备空间")
            assertEquals(listOf(THIRD.token), f.volumeRefreshes)
        }
        assertEquals(listOf(1, 2, 3), f.closed)
    }

    @Test
    fun legacyPreviewRetainsCacheActionsWithoutInventingAnAcceptedVolume() {
        var opens = 0
        val refreshes = mutableListOf<SettingsStorageToken>()
        val clears = mutableListOf<Triple<SettingsStorageToken, Long, Set<SettingsStorageFileType>>>()
        val actions = SettingsStorageActions(open = { opens++ }, refreshVolume = { refreshes += it },
            clear = { token, revision, types -> clears += Triple(token, revision, types) })
        ComposePixelScene(700, 1600) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                CompositionLocalProvider(LocalSettingsSecurityConfirmationHost provides InlineHost) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsStorageControls(OLD, actions)
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            assertEquals(1, opens)
            assertTrue(scene.hasText("系统报告总容量"))
            assertTrue(scene.hasText("未知"))
            assertFalse(scene.hasText("正在等待设备空间结果…"))
            assertFalse(scene.hasDescription(OLD_VOLUME), "An unowned fixture is not a native volume source")
            assertTrue(scene.hasText(OLD_CATEGORY), "The existing cache preview still works")
            scene.clickText("清理所选缓存")
            scene.clickText("确认清理所选缓存")
            assertEquals(listOf(Triple(OLD.token, OLD.revision, setOf(SettingsStorageFileType.PHOTO))), clears)
            assertTrue(refreshes.isEmpty())
        }
    }

    @Test
    fun queuedReceiptAfterNativeOwnerRevocationNeverMakesOldSnapshotVisible() {
        val f = Fixture(denyFirst = false, publishFirst = false,
            initial = SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            onUi { f.snapshot = OLD }
            scene.settledFrame()
            assertFalse(scene.hasDescription(OLD_VOLUME), "The receipt has not been delivered")

            // Native stop revoked the handle, but the independently scheduled null is still pending.
            onUi { f.owners.single().set(false); f.statuses.single()(OLD.token) }
            scene.settledFrame()
            assertFalse(scene.hasDescription(OLD_VOLUME), "The queued token cannot revive a revoked owner")
            assertFalse(scene.hasText(OLD_CATEGORY))
            assertTrue(f.volumeRefreshes.isEmpty())
            onUi { f.statuses.single()(null) }
            scene.settledFrame()
            assertTrue(scene.hasText("无法打开本地存储"))
            scene.clickText("重试打开本地存储")
            onUi { f.statuses.last()(FRESH.token); f.snapshot = FRESH }
            scene.settledFrame()
            assertTrue(scene.hasDescription(FRESH_VOLUME))
        }
    }

    @Test
    fun anAlreadyVisibleOwnerCannotActAfterRevocationBeforeUiRecomposition() {
        val f = Fixture(denyFirst = false, publishFirst = true,
            initial = SettingsStorageSnapshot(token = SettingsStorageToken(17, 0)))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("管理本地存储")
            assertTrue(scene.hasDescription(OLD_VOLUME))
            val frozenRefresh = scene.captureAction("刷新设备空间")
            scene.clickText("清理所选缓存")
            val frozenConfirm = scene.captureAction("确认清理所选缓存")
            onUi {
                f.owners.single().set(false)
                frozenRefresh()
                frozenConfirm()
            }
            assertTrue(f.volumeRefreshes.isEmpty())
            assertTrue(f.clears.isEmpty(), "A frozen old confirmation cannot act through a revoked owner")
            onUi { f.snapshot = f.snapshot.copy(revision = f.snapshot.revision + 1) }
            scene.settledFrame()
            assertFalse(scene.hasDescription(OLD_VOLUME))
            onUi { f.statuses.single()(null) }
            scene.settledFrame()
            assertTrue(scene.hasText("无法打开本地存储"))
        }
    }

    private class Fixture(val denyFirst: Boolean, val publishFirst: Boolean, initial: SettingsStorageSnapshot,
        val rejectFirstSynchronously: Boolean = false,
    ) {
        var snapshot by mutableStateOf(initial)
        var attempts = 0
        val active = mutableListOf<() -> Boolean>()
        val statuses = mutableListOf<(SettingsStorageToken?) -> Unit>()
        val owners = mutableListOf<AtomicBoolean>()
        val closed = mutableListOf<Int>()
        val volumeRefreshes = mutableListOf<SettingsStorageToken>()
        val clears = mutableListOf<Triple<SettingsStorageToken, Long, Set<SettingsStorageFileType>>>()
        private val owner = SettingsStoragePageOwner()
        private val actions = SettingsStorageActions(openOwnedObserved = { current, status ->
            val attempt = ++attempts
            statuses += status
            if (denyFirst && attempt == 1) null else {
                active += current
                val owner = AtomicBoolean(true).also(owners::add)
                if (publishFirst && attempt == 1) { snapshot = OLD; status(OLD.token) }
                if (rejectFirstSynchronously && attempt == 1) status(null)
                object : SettingsStorageHandle {
                    override fun current() = owner.get()
                    override fun close() { owner.set(false); closed += attempt }
                }
            }
        }, refreshVolume = { volumeRefreshes += it }, clear = { token, revision, types ->
            clears += Triple(token, revision, types)
        })

        fun scene() = ComposePixelScene(700, 1600) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                CompositionLocalProvider(LocalSettingsSecurityConfirmationHost provides InlineHost) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsStorageControls(snapshot, actions, pageOwner = owner)
                    }
                }
            }
        }
    }

    private object InlineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().widthIn(max = maxWidth)
                .background(MiuixTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                title?.let { Text(it) }
                content()
            }
        }
    }

    private companion object {
        const val OLD_CATEGORY = "照片 · 3.0 MB · 3 个"
        const val FRESH_CATEGORY = "照片 · 5.0 MB · 5 个"
        const val OLD_VOLUME = "文件目录所在位置，系统报告总容量 1.0 GB，当前用户可用 512.0 MB"
        const val FRESH_VOLUME = "文件目录所在位置，系统报告总容量 2.0 GB，当前用户可用 1.0 GB"
        const val THIRD_VOLUME = "文件目录所在位置，系统报告总容量 3.0 GB，当前用户可用 1.0 GB"
        val OLD = SettingsStorageSnapshot(SettingsStorageToken(17, 1), opened = true, revision = 8,
            details = SettingsStorageDetails(3_145_728, 3,
                listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 3_145_728, 3))),
            volume = SettingsStorageVolume.Available(1_073_741_824, 536_870_912))
        val FRESH = SettingsStorageSnapshot(SettingsStorageToken(17, 2), opened = true, revision = 9,
            details = SettingsStorageDetails(5_242_880, 5,
                listOf(SettingsStorageCategory(SettingsStorageFileType.PHOTO, 5_242_880, 5))),
            volume = SettingsStorageVolume.Available(2_147_483_648, 1_073_741_824))
        val THIRD = FRESH.copy(token = SettingsStorageToken(17, 3),
            volume = SettingsStorageVolume.Available(3_221_225_472, 1_073_741_824))

        fun ComposePixelScene.hasText(label: String): Boolean = semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
        }.isNotEmpty()
        fun ComposePixelScene.hasDescription(label: String): Boolean = semanticsBounds { config ->
            label in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.isNotEmpty()
        fun ComposePixelScene.clickText(label: String) {
            val bounds: Rect = assertNotNull(semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                    config.getOrNull(SemanticsProperties.Disabled) == null &&
                    config.getOrNull(SemanticsActions.OnClick)?.action != null
            }.firstOrNull(), "Missing enabled control: $label")
            moveTo(bounds.center.x, bounds.center.y)
            pressAt(bounds.center.x, bounds.center.y)
            releaseAt(bounds.center.x, bounds.center.y)
            settledFrame(5, 16)
        }
        fun ComposePixelScene.captureAction(label: String): () -> Boolean {
            var captured: (() -> Boolean)? = null
            semanticsBounds { config ->
                if (config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } &&
                    config.getOrNull(SemanticsProperties.Disabled) == null) {
                    captured = config.getOrNull(SemanticsActions.OnClick)?.action
                }
                false
            }
            return assertNotNull(captured, "Missing enabled action: $label")
        }
        fun onUi(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
        }
    }
}
