package com.badnng.moe

import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.settings.*
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsPersonalChannelUiTest {
    @Test fun oldSnapshotArrivingBeforeTheNewOwnerCannotKeepARejectedSelectorLeaseAlive() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("个人频道")); scene.settledFrame()
            scene.click(scene.text("Native choice two")); scene.settledFrame()
            f.mounted = false; scene.settledFrame()
            f.ignoreNextOpen = true // Actor will reject this queued request for the old token.
            f.mounted = true; scene.settledFrame()
            assertEquals(2, f.opens)
            assertTrue(assertNotNull(f.lease).invoke())
            assertFalse(f.extras.channelsOpened)
            f.extras = f.extras.copy(token = SettingsProfileExtrasToken(7, 4, 43), loading = true)
            scene.settledFrame()
            assertFalse(assertNotNull(f.lease).invoke(), "A new owner invalidates the locally alive lease from an earlier snapshot.")
            f.extras = f.extras.copy(loading = false)
            scene.settledFrame()
            assertEquals(3, f.opens)
            assertEquals(listOf(3L, 3L, 4L), f.openTokens.map { it.owner })
            assertTrue(assertNotNull(f.lease).invoke())
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(-9L to -2L, f.saves.single(), "The draft identity survives while its native permission is reloaded.")
        }
    }
    @Test fun restoredChannelDraftWaitsForTheNewProfileOwnerToFinishLoadingBeforeOpeningCandidates() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("个人频道")); scene.settledFrame()
            scene.click(scene.text("Native choice two")); scene.settledFrame()
            f.mounted = false; scene.settledFrame()
            assertFalse(assertNotNull(f.lease).invoke())
            f.extras = f.extras.copy(token = SettingsProfileExtrasToken(7, 4, 43), loading = true)
            f.mounted = true; scene.settledFrame()
            assertEquals(1, f.opens, "A restored editor cannot open against the still-loading new page owner.")
            f.extras = f.extras.copy(loading = false)
            scene.settledFrame()
            assertEquals(2, f.opens)
            assertTrue(assertNotNull(f.lease).invoke())
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(-9L to -2L, f.saves.single())
        }
    }
    @Test fun nativeChoiceUsesActualConfirmationAndARejectedSaveRetainsTheChosenIdentity() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(0, f.opens)
            assertNotNull(scene.text("Existing native channel"))
            scene.click(scene.text("个人频道")); scene.settledFrame()
            assertEquals(1, f.opens)
            scene.click(scene.text("Native choice two")); scene.settledFrame()
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            assertTrue(f.saves.isEmpty())
            scene.reveal("取消", f); scene.click(scene.text("取消")); scene.settledFrame()
            assertTrue(f.saves.isEmpty())
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(-9L to -2L, f.saves.single())
            f.extras = f.extras.copy(busy = false, failedRevision = 1, error = SettingsError.FORBIDDEN)
            scene.settledFrame()
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(listOf(-9L to -2L, -9L to -2L), f.saves)
        }
    }
    @Test fun currentChannelRemovalFreezesTheOriginalIdentityAndOnlyConfirmsExplicitZero() {
        val f = Fixture(height = 480)
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("个人频道")); scene.settledFrame()
            scene.reveal("移除个人频道", f); scene.click(scene.text("移除个人频道")); scene.settledFrame()
            assertTrue(f.saves.isEmpty())
            scene.reveal("取消", f); scene.click(scene.text("取消")); scene.settledFrame()
            assertTrue(f.saves.isEmpty())
            scene.reveal("移除个人频道", f); scene.click(scene.text("移除个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(-9L to 0L, f.saves.single())
        }
    }
    @Test fun realMoreControlRequestsOnlyOnClickAndCancelledSelectionClosesItsSynchronousLease() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("个人频道")); scene.settledFrame()
            assertEquals(0, f.more)
            assertTrue(assertNotNull(f.lease).invoke())
            scene.reveal("更多频道", f); scene.click(scene.text("更多频道")); scene.settledFrame()
            assertEquals(1, f.more)
            assertNotNull(scene.text("Next native choice"))
            scene.reveal("取消频道修改", f); scene.click(scene.text("取消频道修改")); scene.settledFrame()
            assertFalse(assertNotNull(f.lease).invoke())
            assertTrue(f.saves.isEmpty())
            assertEquals(1, f.closed)
        }
    }
    @Test fun acceptedResultFromCancelledSelectorCannotCloseOrSubmitANewDraft() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame(); scene.click(scene.text("个人频道")); scene.settledFrame()
            scene.click(scene.text("Native choice two")); scene.settledFrame()
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            scene.reveal("取消频道修改", f); scene.click(scene.text("取消频道修改")); scene.settledFrame()
            f.extras = f.extras.copy(busy = false)
            scene.settledFrame()
            scene.reveal("个人频道", f); scene.click(scene.text("个人频道")); scene.settledFrame()
            scene.click(scene.text("Native choice one")); scene.settledFrame()
            f.extras = f.extras.copy(acceptedRevision = 1, completedRevision = 1)
            scene.settledFrame()
            assertNotNull(scene.text("取消频道修改"))
            scene.reveal("保存个人频道", f); scene.click(scene.text("保存个人频道")); scene.settledFrame()
            scene.reveal("确认", f); scene.click(scene.text("确认")); scene.settledFrame()
            assertEquals(listOf(-9L to -2L, -9L to -1L), f.saves)
        }
    }
    private class Fixture(val width: Int = 390, val height: Int = 844) {
        var mounted by mutableStateOf(true)
        var extras by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            SettingsProfileExtrasData(43, null, null, SettingsCalendarDate(25, 10, 2026), -9,
                SettingsPersonalChannel(-9, "Existing native channel", "existing_native"))))
        var opens = 0; var more = 0; var closed = 0
        var ignoreNextOpen = false
        val openTokens = mutableListOf<SettingsProfileExtrasToken>()
        var lease: (() -> Boolean)? = null
        val saves = mutableListOf<Pair<Long, Long>>()
        val actions = SettingsProfileExtrasActions(
            openPersonalChannels = { token, active ->
                opens++; lease = active; openTokens += token
                if (ignoreNextOpen) ignoreNextOpen = false
                else extras = extras.copy(channelsOpened = true, channels = listOf(SettingsPersonalChannel(-1, "Native choice one", "native_one"),
                    SettingsPersonalChannel(-2, "Native choice two", "native_two")), channelsHasMore = true, channelsTotal = 3)
            },
            closePersonalChannels = { closed++; extras = extras.copy(channelsOpened = false, channels = emptyList()) },
            morePersonalChannels = { more++; extras = extras.copy(channels = extras.channels +
                SettingsPersonalChannel(-3, "Next native choice", "native_three"), channelsHasMore = false) },
            savePersonalChannel = { _, expected, desired -> saves += expected to desired
                extras = extras.copy(busy = true, startedRevision = extras.startedRevision + 1, operation = SettingsProfileExtrasOperation.PERSONAL_CHANNEL) },
        )
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides true,
                LocalSettingsSecurityConfirmationHost provides OfflineHost(height.dp)) {
                MiuixTheme(controller = theme) {
                    if (mounted) holder.SaveableStateProvider("personal-channel") {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SettingsPersonalChannelControls(SettingsToken(7, 1), 43, extras, actions, true)
                        }
                    }
                }
            }
        }
    }
    private class OfflineHost(val height: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit,
        ) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = height - 32.dp)
                .chatSquircleBackground(DialogDefaults.backgroundColor(), 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; content()
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).lastOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture) {
        repeat(45) {
            val candidates = texts(label)
            if (candidates.any { it.width > 0 && it.height >= 12 && it.top >= 8 && it.bottom <= f.height - 8 }) return
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < f.height }
            val direction = if (candidates.any { it.width > 0 && it.height > 0 && it.bottom < 8 }) -5f else 5f
            scrollAt(scroller?.center?.x ?: f.width / 2f, scroller?.center?.y?.coerceIn(8f, f.height - 8f) ?: f.height * .6f,
                deltaY = direction); settledFrame(5, 32)
        }
        assertTrue(texts(label).any { it.width > 0 && it.height > 0 && it.top >= 8 && it.bottom <= f.height - 8 }, label)
    }
}
