package com.badnng.moe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ownedAttachmentLease
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalNativeFilePicker
import com.badnng.moe.settings.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsNotificationSoundPickerTest {
    @Test fun unknownLimitsNeverOpenTheNativeChooserAndCancelNeverStartsPreparation() {
        val f = Fixture(NotificationSoundLimits())
        f.selection = null
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(0, f.picks)
            assertTrue(scene.hasText("铃声限制尚未就绪或已改变，请刷新后重新选择。"))
            f.onUi { f.limits = known }
            scene.settledFrame()
            assertEquals(0, f.picks, "A limits update cannot open a system chooser without a new explicit retry.")
            scene.clickText("重新选择")
            assertEquals(1, f.picks)
            assertEquals(321L, f.pickerOwner)
            assertEquals(setOf("mp3", "mpeg3", "ogg", "oga"), f.extensions)
            assertEquals(0, f.prepares)
            assertEquals(1, f.dismissals)
            assertTrue(f.received.isEmpty())
        }
    }

    @Test fun aPreparationFailureCanBeReselectedAndSuccessfulTransferLeavesTheCallerOwningTheLease() {
        val f = Fixture()
        val cleaned = AtomicInteger()
        val prepared = prepared(cleaned)
        f.prepare = { _, _, _ -> if (f.prepares == 1) SettingsResult(error = SettingsError.INVALID_VALUE) else SettingsResult(prepared) }
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("文件格式、大小或时长不符合要求，请重新选择。"))
            assertTrue(f.received.isEmpty())
            scene.clickText("重新选择")
            assertEquals(2, f.picks)
            assertEquals(listOf(prepared), f.received)
            assertEquals(1, f.dismissals)
            assertEquals(0, cleaned.get())
        }
        assertEquals(0, cleaned.get(), "Picker disposal cannot release a transferred caller-owned handle.")
        f.received.single().lease.release()
        assertEquals(1, cleaned.get())
    }

    @Test fun closingWhilePreparationCompletesLateReleasesOnlyItsUntransferredLease() {
        val f = Fixture()
        val cleaned = AtomicInteger()
        val complete = CompletableDeferred<SettingsResult<SettingsPreparedNotificationSound>>()
        f.prepare = { _, _, _ -> withContext(NonCancellable) { complete.await() } }
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(1, f.prepares)
            assertTrue(f.leases.single()())
            scene.clickText("取消")
            assertFalse(f.leases.single()())
            f.onUi { complete.complete(SettingsResult(prepared(cleaned))) }
            scene.settledFrame()
            assertTrue(f.received.isEmpty())
            assertEquals(1, cleaned.get())
            assertEquals(1, f.dismissals)
        }
    }

    @Test fun changedLimitsRetireTheOriginalPreparationAndOnlyExplicitReselectionCanTransfer() {
        val f = Fixture()
        val oldCleaned = AtomicInteger()
        val freshCleaned = AtomicInteger()
        val complete = CompletableDeferred<SettingsResult<SettingsPreparedNotificationSound>>()
        val fresh = prepared(freshCleaned)
        f.prepare = { _, _, _ -> if (f.prepares == 1) complete.await() else SettingsResult(fresh) }
        f.scene().use { scene ->
            scene.settledFrame()
            f.onUi { f.limits = known.copy(maximumBytes = 500_000) }
            scene.settledFrame()
            assertFalse(f.leases.single()())
            f.onUi { complete.complete(SettingsResult(prepared(oldCleaned))) }
            scene.settledFrame()
            assertTrue(f.received.isEmpty())
            assertEquals(1, oldCleaned.get())
            assertEquals(1, f.picks)
            scene.clickText("重新选择")
            assertEquals(listOf(fresh), f.received)
            assertEquals(0, freshCleaned.get())
        }
        fresh.lease.release()
        assertEquals(1, freshCleaned.get())
    }

    private class Fixture(initial: NotificationSoundLimits = known) {
        var limits by mutableStateOf(initial)
        var show by mutableStateOf(true)
        var selection: String? = "C:/Synthetic/sound.mp3"
        var picks = 0
        var prepares = 0
        var dismissals = 0
        var pickerOwner: Long? = null
        var extensions: Set<String>? = null
        val leases = mutableListOf<() -> Boolean>()
        val received = mutableListOf<SettingsPreparedNotificationSound>()
        var prepare: suspend (String, NotificationSoundLimits, () -> Boolean) -> SettingsResult<SettingsPreparedNotificationSound> =
            { _, _, _ -> SettingsResult(error = SettingsError.REQUEST_FAILED) }
        fun onUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
        fun scene() = ComposePixelScene(500, 500) {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.Light) }) {
                CompositionLocalProvider(LocalNativeDialogOwner provides 321L,
                    LocalNativeFilePicker provides { _, allowed, owner -> picks++; extensions = allowed; pickerOwner = owner; selection },
                    LocalNotificationSoundPreparation provides { path, limit, active -> prepares++; leases += active; prepare(path, limit, active) },
                    LocalSettingsSecurityConfirmationHost provides OfflineHost) {
                    SettingsNotificationSoundPicker(show, limits, onDismiss = { dismissals++; show = false }, onPrepared = { received += it })
                }
            }
        }
    }

    private object OfflineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().padding(12.dp)) { title?.let { Text(it) }; content() }
        }
    }

    private fun ComposePixelScene.hasText(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.isNotEmpty()
    private fun ComposePixelScene.clickText(label: String) {
        val target = semanticsBounds { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
            .first { it.width > 0 && it.height > 0 }
        click(target)
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private companion object {
        val known = NotificationSoundLimits(1_000_000, 2, 100)
        fun prepared(cleaned: AtomicInteger) = SettingsPreparedNotificationSound(
            PreparedNotificationSoundUpload("C:/Synthetic/staged.mp3", 100, 1_000, "00".repeat(32)),
            ownedAttachmentLease { cleaned.incrementAndGet() })
    }
}
