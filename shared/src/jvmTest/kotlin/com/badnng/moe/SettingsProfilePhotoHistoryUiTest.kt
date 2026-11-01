package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Compose input, real MediaPreview worker decoding, and inline production confirmation bodies. */
class SettingsProfilePhotoHistoryUiTest {
    @Test fun realAvatarGroupEntryOpensHistoryOnlyAfterItsPointerClick() {
        val f = Fixture(fullProfile = true)
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(0, f.opens)
            assertTrue(f.files.isEmpty())
            scene.clickText("头像历史", f)
            assertEquals(1, f.opens)
            assertTrue(assertNotNull(f.selector).invoke())
            assertTrue(scene.hasText("已显示 24 张 · 总数未知"))
            scene.clickText("返回头像设置", f)
            assertEquals(1, f.closes)
            assertFalse(assertNotNull(f.selector).invoke())
            assertTrue(scene.description("选择图片或视频头像").width > 0)
            assertFalse(scene.hasText("设置新头像"))
            assertFalse(scene.hasText("设置视频头像"))
        }
    }

    @Test fun visibleGridUsesMeasuredPhysicalSmallRequestsAndOnlyPointerSelectionRequestsPreview() {
        val f = Fixture(density = 1.25f)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.files.isNotEmpty())
            assertTrue(f.files.all { it.request.use == SettingsProfilePhotoHistoryMediaUse.VISIBLE_CELL })
            assertTrue(f.files.all { it.request.targetWidthPx in 1..4096 && it.request.targetHeightPx in 1..4096 })
            val bounds = scene.description("查看头像 2")
            val cell = f.files.first { it.request.photoId == 1001L }
            assertEquals(bounds.width.roundToInt(), cell.request.targetWidthPx)
            scene.clickDescription("查看头像 2", f)
            val preview = f.files.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
            assertEquals(1001L, preview.request.photoId)
            assertEquals((216 * 1.25f).roundToInt(), preview.request.targetWidthPx)
            assertTrue(cell.closed.get())
            scene.clickText("下一张", f)
            assertEquals(1002L, f.files.last().request.photoId)
            assertTrue(preview.closed.get())
            scene.clickText("上一张", f)
            assertEquals(1001L, f.files.last().request.photoId)
        }
    }

    @Test fun sameNativeFileHasIndependentCellOwnersAndOuterScrollReleasesOnlyInvisibleOwners() {
        val f = Fixture(width = 620, height = 900, count = 96)
        f.scene().use { scene ->
            scene.settledFrame()
            val first = f.files.filter { it.active() }
            assertTrue(first.size >= 2)
            assertTrue(first.map { it.request.photoId }.distinct().size >= 2)
            assertTrue(first.size < 96, "A lazy grid must not acquire all history originals.")
            val grid = scene.scrollers().last()
            repeat(10) { scene.scrollAt(grid.center.x, grid.center.y, deltaY = 10f); scene.settledFrame(5, 32) }
            assertTrue(first.any { it.closed.get() })
            assertTrue(f.files.any { it !in first && it.active() })
            val survivor = assertNotNull(f.files.firstOrNull { it.active() })
            // Closing an earlier request for the same native file cannot close another cell's lease.
            assertTrue(first.first { it.closed.get() }.closed.get())
            assertTrue(survivor.active())
        }
        assertTrue(f.files.all { it.closed.get() })
    }

    @Test fun focusLossClosesVisibleRequestsAndLateNativeResultsCannotReviveThem() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            val old = f.files.toList()
            onUi { f.focused = false }; scene.settledFrame()
            assertTrue(old.all { it.closed.get() && !it.active() })
            Thread { old.forEach { it.deliver(f.ready) } }.apply { start(); join() }
            scene.settledFrame()
            assertTrue(scene.loadedDescriptions().isEmpty())
            val size = f.files.size
            onUi { f.focused = true }; scene.settledFrame()
            assertTrue(f.files.size > size)
            assertTrue(f.files.drop(size).all { it.active() })
        }
    }

    @Test fun pageExitOwnerReplacementAndDisposalRevokeTheSelectorAndEveryChildRequest() {
        for (exit in listOf("page", "owner", "dispose")) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.settledFrame()
                val selector = assertNotNull(f.selector)
                val children = f.files.toList()
                assertTrue(children.isNotEmpty())
                onUi {
                    when (exit) {
                        "page" -> f.active = false
                        "owner" -> f.extras = f.extras.copy(token = f.extras.token.copy(owner = 99))
                        else -> f.mounted = false
                    }
                }
                scene.settledFrame()
                assertFalse(selector(), exit)
                assertTrue(children.all { it.closed.get() && !it.active() }, exit)
                assertNull(f.state.session, exit)
                assertEquals(1, f.closes, exit)
            }
        }
    }

    @Test fun incompleteNativeFileNeverEntersMediaPreviewAndRealRetryCreatesANewOwner() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            val pending = f.files.last()
            Thread { pending.deliver(f.ready.copy(localPath = null, downloaded = false)) }.apply { start(); join() }
            scene.settledFrame()
            assertTrue(scene.hasText("重试图片"))
            assertTrue(scene.loadedDescriptions().isEmpty())
            val before = f.files.size
            scene.clickText("重试图片", f)
            assertTrue(pending.closed.get())
            assertTrue(f.files.size > before)
            val retry = f.files.last()
            assertEquals(pending.request, retry.request)
            Thread { retry.deliver(f.ready) }.apply { start(); join() }
            scene.awaitLoaded("已选头像静态预览 已载入")
            assertTrue(retry.active())
        }
    }

    @Test fun newEpochInvalidatesOldFilesSelectionAndConfirmationEvenForTheSamePhotoId() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f); scene.clickText("删除这张头像", f)
            // Retain the actual application callback: the toolkit's Semantics OnClick wrapper
            // intentionally cannot play click feedback after its Modifier node is disposed.
            val staleConfirm = f.captureAppConfirm()
            val requests = f.files.toList()
            onUi { f.extras = f.extras.copy(history = f.extras.history.copy(epoch = f.extras.history.epoch + 1,
                entries = f.extras.history.entries.map { it.copy(authorization = it.authorization + 1000) })) }
            scene.settledFrame()
            sameFrame(staleConfirm)
            assertTrue(f.removes.isEmpty())
            assertNull(f.state.session?.selection)
            assertNull(f.state.session?.confirmation)
            assertTrue(requests.all { it.closed.get() })
            Thread { requests.forEach { it.deliver(f.ready) } }.apply { start(); join() }
            scene.settledFrame()
            assertTrue(scene.loadedDescriptions().isEmpty())
            assertTrue(f.files.any { it.request.epoch == f.extras.history.epoch && it.active() })
        }
    }

    @Test fun realFrozenConfirmationUsesExactOldPhotoAndNeverTheCurrentRemovalAction() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            scene.clickText("删除这张头像", f)
            assertTrue(f.removes.isEmpty())
            scene.clickText("确认", f)
            assertEquals(1001L, f.removes.single().target.photoId)
            assertEquals(1, f.removes.single().target.rawOffset)
            assertTrue(f.removes.single().active())
            assertTrue(f.currentRemovals.isEmpty())
        }
        assertFalse(f.removes.single().active())
    }

    @Test fun sameFrameCancelThenSamePhotoNewConfirmationCannotBeClosedOrSubmittedByOldCallbacks() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            val remove = scene.captureTextClick("删除这张头像")
            scene.clickText("删除这张头像", f)
            val cancel = scene.captureTextClick("取消")
            val confirm = scene.captureTextClick("确认")
            val old = assertNotNull(f.state.session?.confirmation)
            sameFrame(cancel, remove, cancel, confirm)
            assertFalse(old.lease.active())
            assertNotNull(f.state.session?.confirmation)
            assertTrue(f.state.session?.confirmation !== old)
            assertTrue(f.removes.isEmpty())
            scene.settledFrame(); scene.clickText("确认", f)
            assertEquals(1001L, f.removes.single().target.photoId)
        }
    }

    @Test fun sameFrameReturnToListReselectSameIdRevokesOldSelectionAndItsFrozenConfirm() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            val back = scene.captureTextClick("返回列表")
            val staleBack = f.captureAppBack()
            val selected = assertNotNull(f.state.session?.selection)
            scene.clickText("删除这张头像", f)
            val confirm = f.captureAppConfirm()
            sameFrame(back, confirm)
            assertFalse(selected.lease.active())
            assertTrue(f.removes.isEmpty())
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            assertTrue(f.state.session?.selection !== selected)
            sameFrame(confirm, staleBack)
            assertNotNull(f.state.session?.selection)
            assertTrue(f.removes.isEmpty())
        }
    }

    @Test fun oldBackAndConfirmCannotTouchReopenedHistoryWithIdenticalTokenAndPhoto() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f)
            val back = scene.captureTextClick("返回头像设置")
            scene.clickText("删除这张头像", f)
            val confirm = scene.captureTextClick("确认")
            val old = assertNotNull(f.state.session)
            sameFrame(back, { f.open(); true }, back, confirm)
            scene.settledFrame()
            assertFalse(old.lease.active())
            assertTrue(f.state.session !== old)
            assertTrue(assertNotNull(f.state.session).lease.active())
            assertTrue(f.removes.isEmpty())
            assertTrue(f.files.filter { it.request.epoch < f.extras.history.epoch }.all { it.closed.get() })
        }
    }

    @Test fun roleChangingToCurrentBetweenFreezeAndConfirmNeverFallsThroughToCurrentDeletion() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f); scene.clickText("删除这张头像", f)
            val confirm = f.captureAppConfirm()
            onUi { f.extras = f.extras.copy(history = f.extras.history.copy(identity = f.identity.copy(currentPhotoId = 1001))) }
            scene.settledFrame(); sameFrame(confirm)
            assertTrue(f.removes.isEmpty()); assertTrue(f.currentRemovals.isEmpty())
            assertFalse(scene.hasText("删除这张头像"))
        }
    }

    @Test fun botAndProtectedEntriesRemainReadableWithoutHistoryWriteControls() {
        for (role in listOf("current", "public", "personal", "bot")) {
            val identity = when (role) {
                "public" -> SELF.copy(publicPhotoId = 1001)
                "personal" -> SELF.copy(personalPhotoId = 1001)
                "bot" -> SELF.copy(isBot = true)
                else -> SELF
            }
            val f = Fixture(identity = identity)
            f.scene().use { scene ->
                scene.settledFrame(); scene.clickDescription(if (role == "current") "查看头像 1" else "查看头像 2", f)
                assertFalse(scene.hasText("删除这张头像"), role)
                assertFalse(scene.hasText("设为当前头像"), role)
                assertTrue(f.removes.isEmpty()); assertTrue(f.reuses.isEmpty())
                assertTrue(f.files.any { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW })
            }
        }
    }

    @Test fun acceptedOldWriteCannotDismissANewUnsubmittedConfirmationAndNativeFailureAllowsExplicitRetry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.clickDescription("查看头像 2", f); scene.clickText("设为当前头像", f)
            scene.clickText("确认", f)
            assertEquals(1, f.reuses.size)
            scene.clickText("取消", f)
            onUi { f.extras = f.extras.copy(busy = false) }; scene.settledFrame()
            scene.clickText("删除这张头像", f)
            val fresh = assertNotNull(f.state.session?.confirmation)
            onUi { f.extras = f.extras.copy(acceptedRevision = 1, completedRevision = 1,
                operation = SettingsProfileExtrasOperation.REUSE_PHOTO) }
            scene.settledFrame()
            assertSame(fresh, f.state.session?.confirmation)
            scene.clickText("确认", f)
            assertEquals(1, f.removes.size)
            onUi { f.extras = f.extras.copy(busy = false, failedRevision = 2, error = SettingsError.FORBIDDEN) }
            scene.settledFrame()
            assertSame(fresh, f.state.session?.confirmation)
            assertNull(fresh.submittedAfter)
            scene.clickText("确认", f)
            assertEquals(2, f.removes.size)
        }
    }

    @Test fun unknownCountEmptyFailureMoreRefreshAnd512LimitHaveDistinctActualControls() {
        val f = Fixture(count = 0)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("已显示 0 张 · 总数未知"))
            assertTrue(scene.hasText("还没有头像历史。"))
            onUi { f.extras = f.extras.copy(history = f.extras.history.copy(error = SettingsError.REQUEST_FAILED, hasMore = true)) }
            scene.settledFrame(); assertTrue(scene.hasText("头像历史读取失败，请重试。"))
            scene.clickText("更多头像", f); assertEquals(1, f.more)
            scene.clickText("重新读取", f); assertEquals(1, f.refreshes)
            onUi { f.extras = f.extras.copy(history = f.extras.history.copy(totalCount = 900, limited = true)) }
            scene.settledFrame()
            assertTrue(scene.hasText("已达到本次 512 张的读取上限。"))
            assertTrue(scene.hasText("已显示 0 张 · 共 900 张"))
            assertFalse(scene.hasText("更多头像"))
        }
    }

    @Test fun readyNativePngTraversesRealMediaPreviewAndLatePreviewResultCannotInstallAfterExit() {
        val f = Fixture(autoReady = true)
        f.scene().use { scene ->
            scene.settledFrame(); scene.awaitLoaded("查看头像 2 已载入")
            val image = scene.description("查看头像 2")
            val frame = scene.settledFrame()
            assertTrue(isFixturePixel(frame[image.center.x.roundToInt(), image.center.y.roundToInt()]),
                "The real PNG must paint image pixels through MediaPreview, rather than an empty placeholder.")
            onUi { f.autoReady = false }
            scene.clickDescription("查看头像 2", f)
            val preview = f.files.last { it.request.use == SettingsProfilePhotoHistoryMediaUse.EXPLICIT_PREVIEW }
            scene.clickText("返回列表", f)
            Thread { preview.deliver(f.ready) }.apply { start(); join() }
            scene.settledFrame()
            assertTrue(preview.closed.get())
            assertTrue(scene.descriptions("已选头像静态预览 已载入").isEmpty())
            assertNull(f.state.session?.selection)
        }
    }

    @Test fun realGridAndSelectedControlsRemainReachableAtLowHeightAndWriteNewThemeDensityPngs() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density = density, mode = mode, autoReady = true, count = 32)
                f.scene().use { scene ->
                    scene.settledFrame(); scene.clickDescription("查看头像 2", f)
                    scene.revealText("返回列表", f)
                    scene.awaitLoaded("已选头像静态预览 已载入", requiredVisible = false)
                    scene.settledFrame().save("selected-${width}x$height-$mode-$density")
                    scene.clickText("删除这张头像", f)
                    scene.revealText("确认", f)
                    scene.settledFrame().save("confirm-${width}x$height-$mode-$density")
                    scene.clickText("取消", f)
                    scene.clickText("返回列表", f)
                    scene.revealDescription("查看头像 2", f)
                    scene.awaitLoaded("查看头像 2 已载入")
                    scene.settledFrame().save("grid-${width}x$height-$mode-$density")
                    scene.clickText("更多头像", f)
                    assertEquals(1, f.more)
                    scene.clickText("返回头像设置", f)
                    assertFalse(assertNotNull(f.selector).invoke())
                }
            }
        }
    }

    @Test fun datesUseActualTimezoneIncludingOffsetsAcrossUtcMidnight() {
        val seconds = Instant.parse("2026-10-26T23:30:00Z").epochSecond.toInt()
        assertEquals("2026 年 10 月 27 日", formatProfileHistoryDate(seconds, ZoneId.of("Asia/Singapore")))
        assertEquals("2026 年 10 月 26 日", formatProfileHistoryDate(seconds, ZoneId.of("America/Los_Angeles")))
        assertEquals(formatProfileHistoryDate(seconds, ZoneId.systemDefault()), profileHistoryDate(seconds))
        assertEquals("日期未知", profileHistoryDate(0))
    }

    private class Pending(val request: SettingsProfilePhotoHistoryMediaRequest, val active: () -> Boolean,
        val callback: (SettingsResult<MediaFile>) -> Unit,
    ) : SettingsProfilePhotoHistoryFileLease {
        val closed = AtomicBoolean()
        override fun close() { closed.set(true) }
        fun deliver(file: MediaFile) = callback(SettingsResult(value = file))
    }
    private class Write(val target: SettingsProfilePhotoHistoryTarget, val active: () -> Boolean)

    private class Fixture(val width: Int = 620, val height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light, val fullProfile: Boolean = false,
        val identity: SettingsProfilePhotoIdentity = SELF, count: Int = 24, autoReady: Boolean = false,
    ) {
        val pixelWidth get() = (width * density).roundToInt()
        val pixelHeight get() = (height * density).roundToInt()
        var focused by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var active by mutableStateOf(true)
        var autoReady = autoReady
        val state = SettingsProfilePhotoHistoryUiState()
        private val entries = (0 until count).map { index -> SettingsProfilePhotoHistoryEntry(1000L + index,
            1_700_000_000 - index * 86400, index, listOf(SettingsProfilePhotoVariant(MediaFile(7, uniqueId = "same-native-file"), 160, 160)),
            hasAnimation = index == 2, hasSticker = index == 3, authorization = index + 1L) }
        var extras by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            SettingsProfileExtrasData(43, 1000, null, SettingsCalendarDate(27, 10, 2026))))
        var opens = 0; var closes = 0; var more = 0; var refreshes = 0
        var selector: (() -> Boolean)? = null
        var callbacks: ProfileHistoryUiCallbacks? = null
        val files = mutableListOf<Pending>()
        val removes = mutableListOf<Write>()
        val reuses = mutableListOf<Write>()
        val currentRemovals = mutableListOf<Long>()
        val ready = MediaFile(7, size = fixtureFile.length(), localPath = fixtureFile.absolutePath,
            downloaded = true, uniqueId = "same-native-file")
        val actions = SettingsProfilePhotoHistoryActions(
            open = { _, lease ->
                opens++; selector = lease
                extras = extras.copy(history = SettingsProfilePhotoHistorySnapshot(opened = true, epoch = extras.history.epoch + 1,
                    identity = identity, entries = entries, nextOffset = entries.size, hasMore = entries.isNotEmpty()))
            },
            close = { closes++; extras = extras.copy(history = extras.history.copy(opened = false)) },
            more = { more++ },
            refresh = { refreshes++; extras = extras.copy(history = extras.history.copy(epoch = extras.history.epoch + 1,
                error = null, refreshRequired = false)) },
            reuse = { _, _, target, lease -> reuses += Write(target, lease); begin(SettingsProfileExtrasOperation.REUSE_PHOTO) },
            remove = { _, _, target, lease -> removes += Write(target, lease); begin(SettingsProfileExtrasOperation.REMOVE_HISTORY_PHOTO) },
            acquireFile = { request, lease, callback ->
                Pending(request, lease, callback).also { files += it; if (this.autoReady) it.deliver(ready) }
            },
        )
        private val profileActions = SettingsProfileExtrasActions(history = actions, removePhoto = { _, id -> currentRemovals += id })
        init { if (!fullProfile) open() }
        fun open() = state.open(extras, actions)
        private fun begin(operation: SettingsProfileExtrasOperation) {
            extras = extras.copy(busy = true, startedRevision = extras.startedRevision + 1, operation = operation, error = null)
        }
        fun scene() = ComposePixelScene(pixelWidth, pixelHeight) {
            val theme = remember { ThemeController(mode) }
            val nativeWindow = LocalWindowInfo.current
            val currentFocus by rememberUpdatedState(focused)
            val window = remember(nativeWindow) { object : WindowInfo by nativeWindow {
                override val isWindowFocused get() = currentFocus
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalSettingsPageActive provides active, LocalSettingsSecurityConfirmationHost provides InlineConfirmation,
                LocalProfileHistoryUiCallbacks provides { callbacks = it }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        if (mounted) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (fullProfile) SettingsProfileExtrasContent(SettingsToken(7, 1), 43, extras, profileActions, true)
                            else SettingsProfilePhotoHistoryUi(state, extras, actions, true)
                        }
                    }
                }
            }
        }
    }

    /** Native WindowDialog replacement only; the real production dialog body and its callbacks are unchanged. */
    private object InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val height = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
                Column(Modifier.fillMaxWidth().heightIn(max = (height - 16.dp).coerceAtLeast(120.dp))
                    .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title?.let { Text(it) }; content()
                }
            }
        }
    }

    private fun ComposePixelScene.hasText(label: String) = semanticsBounds { it.hasText(label) }.isNotEmpty()
    private fun SemanticsConfiguration.hasText(label: String) = getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    private fun ComposePixelScene.descriptions(label: String) = unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(descriptions(label).lastOrNull(), label)
    private fun ComposePixelScene.loadedDescriptions() = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.endsWith(" 已载入") }
    }
    private fun ComposePixelScene.scrollers() = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
    private fun ComposePixelScene.captureTextClick(label: String): () -> Boolean {
        var callback: (() -> Boolean)? = null
        semanticsBounds {
            if (it.hasText(label)) it.getOrNull(SemanticsActions.OnClick)?.action?.let { found -> callback = found }
            false
        }
        return assertNotNull(callback, "Actual composed action: $label")
    }
    // The observer supplies the exact callbacks also passed to production controls. Preserve that
    // captured object across disposal; resolving the newest callback here would weaken the test.
    private fun Fixture.captureAppConfirm(): () -> Boolean {
        val observed = assertNotNull(callbacks)
        assertSame(assertNotNull(state.session?.confirmation), observed.confirmation)
        val captured = observed.confirm
        return { captured(); true }
    }
    private fun Fixture.captureAppBack(): () -> Boolean {
        val observed = assertNotNull(callbacks)
        assertSame(assertNotNull(state.session?.selection), observed.selection)
        val captured = observed.returnToList
        return { captured(); true }
    }
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
        settledFrame()
    }
    private fun ComposePixelScene.clickText(label: String, f: Fixture) = click(revealText(label, f))
    private fun ComposePixelScene.clickDescription(label: String, f: Fixture) = click(revealDescription(label, f))
    private fun ComposePixelScene.revealText(label: String, f: Fixture) = reveal(f) { it.hasText(label) }
    private fun ComposePixelScene.revealDescription(label: String, f: Fixture) = reveal(f) {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.reveal(f: Fixture, predicate: (SemanticsConfiguration) -> Boolean): Rect {
        val margin = 2f * f.density
        fun visible() = semanticsBounds(predicate).firstOrNull {
            it.width > 0 && it.height >= 8 * f.density && it.top >= margin && it.bottom <= f.pixelHeight - margin
        }
        var down = false
        repeat(240) {
            visible()?.let { return it }
            val target = semanticsBounds(predicate).firstOrNull { it.width > 0 && it.height > 0 }
            var outerPosition = 0f
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { outerPosition = it.value() }; false
            }
            if (outerPosition <= .5f) down = true
            val delta = when { target != null -> if (target.top < margin) -8f else 8f; down -> 8f; else -> -8f }
            // Left strip belongs to the outer profile scroller, outside the nested bounded lazy grid.
            scrollAt(2f * f.density, f.pixelHeight * .5f, deltaY = delta * f.density)
            settledFrame(5, 32)
        }
        return assertNotNull(visible(), "Pointer scrolling must expose target at ${f.width}x${f.height}: ${semanticsBounds(predicate)}")
    }
    private fun ComposePixelScene.awaitLoaded(label: String, requiredVisible: Boolean = true) {
        repeat(120) {
            val bounds = descriptions(label)
            if (bounds.any { !requiredVisible || it.width > 0 && it.height > 0 }) return
            settledFrame(3, 16); Thread.sleep(5)
        }
        assertTrue(descriptions(label).any { !requiredVisible || it.width > 0 && it.height > 0 }, "MediaPreview worker load: $label")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-profile-photo-history-validation/frames/$name.png")
        output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
    }
    private fun sameFrame(vararg actions: () -> Boolean) = onUi { actions.forEach { it() } }
    private fun onUi(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
    private fun isFixturePixel(color: Int) = (color ushr 16 and 255) > 180 && (color ushr 8 and 255) < 110 && (color and 255) > 140

    companion object {
        private val SELF = SettingsProfilePhotoIdentity(43, currentPhotoId = 1000)
        private val fixtureFile: File by lazy {
            File("build/settings-profile-photo-history-validation/fixture/actual-native-ready.png").also { file ->
                file.parentFile.mkdirs()
                val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y,
                    if ((x / 8 + y / 8) % 2 == 0) 0xffe63ac4.toInt() else 0xffd72bb3.toInt())
                ImageIO.write(image, "png", file)
            }
        }
    }
}
