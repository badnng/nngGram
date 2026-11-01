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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real pointer/scroll interactions with fake native responses, preparation and audio actions. */
class SettingsSavedNotificationSoundPickerUiTest {
    @Test fun restoringAnExplicitVolumePersistsNullOnlyAfterTheSoundSaveCheck() {
        val target = NotificationSoundVolumeTarget.Scope(NotificationScope.GROUP_CHATS)
        val f = Fixture(volumeTarget = target, initialVolume = 35)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("恢复默认本机音量", f)
            assertTrue(f.volumeWrites.isEmpty())
            scene.clickText("保存声音选择", f)
            f.onUi { f.snapshot = f.snapshot.copy(choosing = false); f.choices.single().complete(SettingsResult(-1L)) }
            scene.settledFrame()
            val save = f.volumeWrites.single()
            assertEquals(35, save.expected.overridePercent)
            assertNull(save.desired)
            f.onUi { save.complete(SettingsResult(save.expected.copy(overridePercent = null, revision = 2))) }
            scene.settledFrame()
            assertEquals(listOf(-1L), f.selected)
        }
    }

    @Test fun changingTheActualVolumeTargetRetiresThePreviousReadAndNeverAdoptsItsValue() {
        val scope = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
        val chat = NotificationSoundVolumeTarget.Chat(41001)
        val f = Fixture(volumeTarget = scope)
        f.automaticVolumeRead = false
        f.scene().use { scene ->
            scene.settledFrame()
            val old = f.volumeReads.single()
            val oldLease = f.leases.single()
            f.onUi { f.target = chat }
            scene.settledFrame()
            assertFalse(oldLease())
            assertEquals(listOf(scope, chat), f.volumeReads.map { it.target })
            f.onUi {
                old.complete(SettingsResult(NotificationSoundVolumeReading(70001, scope, 27, 1)))
                f.volumeReads.last().complete(SettingsResult(NotificationSoundVolumeReading(70001, chat, 80, 1)))
            }
            scene.settledFrame()
            assertTrue(scene.hasText("本机铃声音量：80%"))
            assertFalse(scene.hasText("本机铃声音量：27%"))
            assertTrue(f.volumeWrites.isEmpty())
        }
    }

    @Test fun failedVolumeReadBlocksSaveUntilExplicitRetryAndUntouchedNullNeverCreatesAnOverride() {
        val target = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)
        val f = Fixture(volumeTarget = target)
        f.automaticVolumeRead = false
        f.scene().use { scene ->
            scene.settledFrame()
            assertEquals(target, f.volumeReads.single().target)
            scene.clickText("保存声音选择", f)
            assertTrue(f.choices.isEmpty())
            f.onUi { f.volumeReads.single().complete(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            scene.settledFrame()
            assertTrue(scene.hasText("本机音量读取或保存失败，请重试。"))
            scene.clickText("保存声音选择", f)
            assertTrue(f.choices.isEmpty())
            scene.clickText("重新读取本机音量", f)
            f.onUi { f.volumeReads.last().complete(SettingsResult(NotificationSoundVolumeReading(70001, target, null, 2))) }
            scene.settledFrame()
            assertTrue(scene.hasText("未单独设置，显示默认 100%。"))
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            f.onUi {
                f.snapshot = f.snapshot.copy(choosing = false)
                f.choices.single().complete(SettingsResult(f.sound.id))
            }
            scene.settledFrame()
            assertEquals(listOf(f.sound.id), f.selected)
            assertTrue(f.volumeWrites.isEmpty())
        }
    }

    @Test fun sliderOnlyChangesTheLocalDraftAndPreviewUsesItWhileCancelWritesNothing() {
        val f = Fixture(volumeTarget = NotificationSoundVolumeTarget.Chat(41001), initialVolume = 35)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("本机铃声音量：35%"))
            val desired = scene.changeVolumeDraft(f) { assertTrue(f.previews.isEmpty(), "Dragging changes only the draft until the gesture ends.") }
            assertTrue(desired in 1..100 && desired != 35)
            assertEquals(listOf(Preview(f.snapshot.token, -1L, desired)), f.previews,
                "The completed pointer gesture auditions the selected sound exactly once.")
            scene.clickText(f.sound.title, f)
            assertEquals(desired, f.previews.last().volume)
            assertTrue(f.volumeWrites.isEmpty())
            assertTrue(f.choices.isEmpty())
            val beforeReset = f.previews.size
            scene.clickText("恢复默认本机音量", f)
            assertTrue(scene.hasText("未单独设置，显示默认 100%。"))
            assertEquals(beforeReset, f.previews.size, "Restoring default remains a local edit without audition.")
            scene.clickText("试听所选声音", f)
            assertEquals(100, f.previews.last().volume)
            scene.clickText("关闭声音选择", f)
            assertTrue(f.volumeWrites.isEmpty())
            assertTrue(f.selected.isEmpty())
        }
    }

    @Test fun keyboardAndAccessibilityStepsAuditionOnceWhileSilenceHidesAdjustmentButKeepsReadRecovery() {
        val target = NotificationSoundVolumeTarget.Chat(41001)
        val f = Fixture(volumeTarget = target, initialVolume = 35)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.previews.isEmpty())
            scene.reveal("本机铃声音量：35%", f)
            fun focused() = scene.semanticsBounds {
                "本机铃声音量" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    it.getOrNull(SemanticsProperties.Focused) == true
            }.isNotEmpty()
            repeat(40) {
                if (!focused()) { scene.keyStroke(KeyEvent.VK_TAB); scene.settledFrame(2, 16) }
            }
            assertTrue(focused(), "Tab must reach the actual focusable Miuix volume control.")
            scene.keyStroke(KeyEvent.VK_LEFT)
            scene.settledFrame()
            assertTrue(scene.hasText("本机铃声音量：34%"))
            assertEquals(listOf(Preview(f.snapshot.token, -1L, 34)), f.previews)
            scene.keyStroke(KeyEvent.VK_RIGHT, control = true)
            scene.settledFrame()
            assertTrue(scene.hasText("本机铃声音量：34%"))
            assertEquals(1, f.previews.size)
            scene.keyStroke(KeyEvent.VK_RIGHT)
            scene.settledFrame()
            assertTrue(scene.hasText("本机铃声音量：35%"))
            assertEquals(2, f.previews.size)
            var adjusted = false
            scene.semanticsBounds { config ->
                if (!adjusted && "本机铃声音量" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                    adjusted = config.getOrNull(SemanticsActions.SetProgress)?.action?.invoke(44f) == true
                false
            }
            scene.settledFrame()
            assertTrue(adjusted)
            assertTrue(scene.hasText("本机铃声音量：44%"))
            assertEquals(3, f.previews.size)
            assertEquals(44, f.previews.last().volume)
            scene.clickText("无声", f)
            assertTrue(scene.semanticsBounds { "本机铃声音量" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.isEmpty())
            assertFalse(scene.hasText("恢复默认本机音量"))
            f.automaticVolumeRead = false
            scene.clickText("重新读取本机音量", f)
            f.onUi { f.volumeReads.last().complete(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            scene.settledFrame()
            assertTrue(scene.hasText("本机音量读取或保存失败，请重试。"))
            scene.clickText("重新读取本机音量", f)
            f.onUi { f.volumeReads.last().complete(SettingsResult(NotificationSoundVolumeReading(70001, target, 60, 2))) }
            scene.settledFrame()
            assertEquals(3, f.previews.size, "Re-reading volume cannot play a sound, including while silence is selected.")
            scene.clickText("关闭声音选择", f)
            assertTrue(f.volumeWrites.isEmpty())
            assertTrue(f.selected.isEmpty())
        }
    }

    @Test fun dirtyVolumeSavesAfterFreshSoundChoiceAndFailureRetainsBothDraftsForExplicitRetry() {
        val target = NotificationSoundVolumeTarget.Chat(41001)
        val f = Fixture(volumeTarget = target)
        f.scene().use { scene ->
            scene.settledFrame()
            val desired = scene.changeVolumeDraft(f)
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            assertTrue(f.volumeWrites.isEmpty())
            f.onUi { f.snapshot = f.snapshot.copy(choosing = false); f.choices.single().complete(SettingsResult(f.sound.id)) }
            scene.settledFrame()
            val first = f.volumeWrites.single()
            assertEquals(target, first.expected.target)
            assertNull(first.expected.overridePercent)
            assertEquals(desired, first.desired)
            assertTrue(f.selected.isEmpty())
            f.onUi { first.complete(SettingsResult(error = SettingsError.REQUEST_FAILED)) }
            scene.settledFrame()
            assertTrue(scene.rowSelected(f.sound.title))
            assertTrue(scene.hasText("本机铃声音量：$desired%"))
            assertTrue(f.selected.isEmpty())
            scene.clickText("保存声音选择", f)
            assertEquals(2, f.choices.size)
            f.onUi { f.snapshot = f.snapshot.copy(choosing = false); f.choices.last().complete(SettingsResult(f.sound.id)) }
            scene.settledFrame()
            val retry = f.volumeWrites.last()
            assertEquals(desired, retry.desired)
            f.onUi { retry.complete(SettingsResult(retry.expected.copy(overridePercent = desired, revision = 2))) }
            scene.settledFrame()
            assertEquals(listOf(f.sound.id), f.selected)
        }
    }

    @Test fun sameFrameCancellationRetiresReadAndSaveCallbacksWithoutChangingTheParent() {
        val target = NotificationSoundVolumeTarget.Chat(41001)
        val unread = Fixture(volumeTarget = target)
        unread.automaticVolumeRead = false
        unread.scene().use { scene ->
            scene.settledFrame()
            val request = unread.volumeReads.single()
            unread.onUi { unread.show = false; request.complete(SettingsResult(NotificationSoundVolumeReading(70001, target, 27, 1))) }
            scene.settledFrame()
            assertFalse(unread.leases.single()())
            assertTrue(unread.volumeWrites.isEmpty())
            assertTrue(unread.selected.isEmpty())
        }
        val f = Fixture(volumeTarget = target)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.changeVolumeDraft(f)
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            f.onUi { f.snapshot = f.snapshot.copy(choosing = false); f.choices.single().complete(SettingsResult(f.sound.id)) }
            scene.settledFrame()
            val request = f.volumeWrites.single()
            f.onUi { f.show = false; request.complete(SettingsResult(request.expected.copy(overridePercent = request.desired, revision = 2))) }
            scene.settledFrame()
            assertFalse(f.leases.single()())
            assertTrue(f.selected.isEmpty())
            assertEquals(1, f.volumeWrites.size)
        }
    }

    @Test fun rowClicksPreviewOnlyTheLocalDraftAndCancelNeverChoosesOrChangesTheParent() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.previews.isEmpty())
            scene.clickText(f.sound.title, f)
            assertEquals(listOf(Preview(f.snapshot.token, f.sound.id, 100)), f.previews)
            assertTrue(scene.rowSelected(f.sound.title))
            assertTrue(f.choices.isEmpty())
            assertTrue(f.selected.isEmpty())
            scene.clickText("试听所选声音", f)
            assertEquals(2, f.previews.size)
            scene.clickText("无声", f)
            assertEquals(2, f.previews.size, "Silence must stop the owned preview without playing a sound.")
            assertEquals(listOf(f.snapshot.token), f.stops)
            assertTrue(scene.rowSelected("无声"))
            scene.clickText("关闭声音选择", f)
            assertTrue(f.choices.isEmpty())
            assertTrue(f.selected.isEmpty())
            assertEquals(-1L, f.parentSelected)
        }
    }

    @Test fun acceptedUploadSelectsItsRealNewIdentityLocallyAndStillRequiresAnExplicitSave() {
        val f = Fixture()
        val added = f.sound.copy(id = -7777L, title = "真实新增声音.标题", file = MediaFile(7000, uniqueId = "synthetic-added"))
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("添加声音", f)
            val base = CountingLease()
            f.onUi { f.preparer.deliver(SettingsPreparedNotificationSound(PreparedNotificationSoundUpload("synthetic/new.mp3", 100, 1000), base)) }
            scene.settledFrame()
            scene.clickText("确认添加声音", f)
            f.onUi {
                f.retained.removeAt(0).release()
                f.snapshot = f.snapshot.copy(saving = false, acceptedRevision = 1, lastAddedSoundId = added.id,
                    readbackPending = true, revision = 12, error = SettingsError.REQUEST_FAILED)
            }
            scene.settledFrame()
            assertEquals(0, base.references)
            assertTrue(f.choices.isEmpty())
            assertTrue(f.selected.isEmpty())
            scene.clickText("保存声音选择", f)
            assertTrue(f.choices.isEmpty(), "An accepted addition with an unknown list readback is not a complete selection.")
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(sounds = listOf(f.sound, added)),
                readbackPending = false, error = null, refreshRequired = false, revision = 13) }
            scene.settledFrame()
            assertTrue(scene.rowSelected(added.title), "The real accepted upload ID survives its pending readback as the local selection.")
            assertTrue(f.previews.isEmpty(), "Uploading and reading back a new item never automatically plays it.")
            assertEquals(-1L, f.parentSelected)
            scene.clickText("保存声音选择", f)
            val choice = f.choices.single()
            assertEquals(added.id, choice.id)
            f.onUi { f.snapshot = f.snapshot.copy(choosing = false); choice.complete(SettingsResult(added.id)) }
            scene.settledFrame()
            assertEquals(listOf(added.id), f.selected)
        }
    }

    @Test fun localSelectionSurvivesUnrelatedAndPendingReadsAndFallsBackOnlyAfterConfirmedRemoval() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText(f.sound.title, f)
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(sounds = emptyList()), readbackPending = true) }
            scene.settledFrame()
            assertFalse(scene.rowSelected("默认声音"), "An incomplete read cannot invent a missing selected item.")
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(sounds = listOf(f.sound)),
                readbackPending = false, revision = 12) }
            scene.settledFrame()
            assertTrue(scene.rowSelected(f.sound.title))
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(sounds = emptyList()), revision = 13) }
            scene.settledFrame()
            assertTrue(scene.rowSelected("默认声音"))
            assertTrue(f.choices.isEmpty())
            assertTrue(f.selected.isEmpty())
        }
    }

    @Test fun aFailedFreshChoiceLeavesTheParentSelectionAndDialogAvailableForAnExplicitRetry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            val request = f.choices.single()
            f.onUi {
                f.snapshot = f.snapshot.copy(choosing = false, revision = 12)
                request.complete(SettingsResult(error = SettingsError.REQUEST_FAILED))
            }
            scene.settledFrame()
            assertTrue(scene.hasText("请求未完成，请检查连接后重试。"))
            assertTrue(f.selected.isEmpty())
            assertEquals(-1L, f.parentSelected)
            assertEquals(0, f.dismissals)
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            assertEquals(2, f.choices.size)
            assertEquals(12L, f.choices.last().revision)
        }
    }

    @Test fun defaultsSilenceAndSignedSavedIdentityChooseOnceAfterTheirOwnRevisionUpdates() {
        for (id in listOf(-1L, 0L, -952L)) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.settledFrame()
                assertTrue(f.choices.isEmpty())
                scene.clickText(when (id) { -1L -> "默认声音"; 0L -> "无声"; else -> f.sound.title }, f)
                assertTrue(f.choices.isEmpty(), "Row selection stays local until Save.")
                scene.clickText("保存声音选择", f)
                val request = f.choices.single()
                assertEquals(id, request.id)
                assertEquals(10L, request.revision)
                assertTrue(f.selected.isEmpty())
                f.onUi {
                    f.snapshot = f.snapshot.copy(revision = 12, choosing = false)
                    request.complete(SettingsResult(id))
                    request.complete(SettingsResult(id))
                }
                scene.settledFrame()
                assertEquals(listOf(id), f.selected, "The request's own revision changes must not reject a successful selection.")
                assertEquals(1, f.dismissals)
                assertFalse(f.leases.single()())
            }
        }
    }

    @Test fun showAndLeaseOwnOnlyTheNewPageAndLateChoiceCannotChangeAnotherParent() {
        val f = Fixture(initiallyShown = false)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.opens.isEmpty())
            f.onUi { f.show = true }
            scene.settledFrame()
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            val old = f.choices.single()
            val oldLease = f.leases.single()
            f.onUi { f.show = false }
            scene.settledFrame()
            assertFalse(oldLease())
            f.onUi { f.admit = false; f.show = true }
            scene.settledFrame()
            assertTrue(scene.hasText("正在读取保存的声音…"))
            scene.clickText("默认声音", f)
            scene.clickText("保存声音选择", f)
            assertEquals(1, f.choices.size, "A replacement lease cannot adopt an already-open old snapshot.")
            f.onUi { old.complete(SettingsResult(old.id)) }
            scene.settledFrame()
            assertTrue(f.selected.isEmpty())
            val rejected = f.leases.last()
            f.onUi { f.admit = true; f.accountToken = f.accountToken.copy(revision = 2) }
            scene.settledFrame()
            assertFalse(rejected())
            scene.clickText(f.sound.title, f)
            scene.clickText("保存声音选择", f)
            val current = f.choices.last()
            f.onUi { f.parentSelected = 0; current.complete(SettingsResult(current.id)) }
            scene.settledFrame()
            assertTrue(f.selected.isEmpty(), "A changed parent sound invalidates the older choosing intention.")
            assertEquals(0, f.dismissals)
        }
    }

    @Test fun metadataBrowsingIsBoundedAndNeverStartsPreviewOrPreparation() {
        val f = Fixture(soundCount = 4096)
        f.scene().use { scene ->
            scene.settledFrame()
            val rows = scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text.startsWith("合成声音") }
            }
            assertTrue(rows.size in 1..32)
            assertTrue(scene.hasText("已保存 4096 个声音，第 1/128 页。"))
            assertFalse(scene.hasText("合成声音32"))
            assertTrue(f.previews.isEmpty())
            assertEquals(0, f.preparer.openings)
            scene.clickText("下一页声音", f)
            assertTrue(scene.hasText("已保存 4096 个声音，第 2/128 页。"))
            assertTrue(scene.hasText("合成声音32"))
            assertFalse(scene.hasText("合成声音0"))
            assertTrue(f.previews.isEmpty())
            assertTrue(f.uploads.isEmpty())
        }
    }

    @Test fun previewIsExplicitHasAnHonestFallbackAndStopsOnlyItsOwnedPreview() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.previews.isEmpty())
            scene.clickText(f.sound.title, f)
            assertEquals(listOf(Preview(f.snapshot.token, f.sound.id, 100)), f.previews)
            assertTrue(scene.hasText("正在准备试听…"))
            scene.clickText("停止试听", f)
            assertEquals(listOf(f.snapshot.token), f.stops)
            f.onUi { f.snapshot = f.snapshot.copy(previewLoading = false, previewDefaultFallback = true,
                previewError = SettingsError.REQUEST_FAILED) }
            scene.settledFrame()
            assertTrue(scene.hasText("所选声音不可用，本次试听使用默认声音。"))
            assertTrue(scene.hasText("试听未完成：请求未完成，请检查连接后重试。"))
            scene.clickText("关闭声音选择", f)
            assertFalse(f.leases.single()())
            assertEquals(listOf(f.closed.single()), f.stops)
            assertTrue(f.selected.isEmpty())
        }
    }

    @Test fun uploadFailureKeepsThePreparedBaseForRetryAndAcceptanceReleasesItWithoutRepeatingAnUnknownReadback() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("添加声音", f)
            assertEquals(1, f.preparer.openings)
            val lease = CountingLease()
            val prepared = SettingsPreparedNotificationSound(PreparedNotificationSoundUpload("synthetic/sound.mp3", 480, 1200, "fake-hash"), lease)
            f.onUi { f.preparer.deliver(prepared) }
            scene.settledFrame()
            assertEquals(1, lease.references)
            assertTrue(f.uploads.isEmpty(), "File preparation still needs the explicit upload confirmation.")
            scene.clickText("确认添加声音", f)
            assertEquals(2, lease.references, "Native submission retains independently while the UI keeps its base.")
            f.onUi {
                f.retained.removeAt(0).release()
                f.snapshot = f.snapshot.copy(saving = false, error = SettingsError.REQUEST_FAILED, revision = 12, refreshRequired = true)
            }
            scene.settledFrame()
            assertEquals(1, lease.references)
            scene.clickText("确认添加声音", f)
            assertEquals(1, f.uploads.size)
            scene.clickText("重新读取声音列表", f)
            f.onUi { f.snapshot = f.snapshot.copy(refreshRequired = false, error = null, revision = 13) }
            scene.settledFrame()
            scene.clickText("确认添加声音", f)
            assertSame(prepared, f.uploads.last().prepared)
            assertEquals(13L, f.uploads.last().revision)
            f.onUi {
                f.retained.removeAt(0).release()
                f.snapshot = f.snapshot.copy(saving = false, acceptedRevision = 1, revision = 14,
                    readbackPending = true, error = SettingsError.REQUEST_FAILED)
            }
            scene.settledFrame()
            assertEquals(0, lease.references)
            assertFalse(scene.hasText("确认添加声音"))
            assertTrue(scene.hasText("更改已提交，尚未读回当前铃声列表。"))
            scene.clickText("添加声音", f)
            assertEquals(1, f.preparer.openings, "Accepted upload with unknown readback cannot upload the same file again.")
            scene.clickText("重新读取声音列表", f)
            assertEquals(listOf(f.snapshot.token, f.snapshot.token), f.refreshes)
            assertTrue(f.selected.isEmpty())
        }
    }

    @Test fun cancellingPreparationOrThePageReleasesOnlyItsBaseAndLatePreparedFilesAreReleased() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("添加声音", f)
            val first = CountingLease()
            f.onUi { f.preparer.deliver(SettingsPreparedNotificationSound(PreparedNotificationSoundUpload("synthetic/a.ogg", 100, 1000), first)) }
            scene.settledFrame()
            scene.clickText("取消添加", f)
            assertEquals(0, first.references)
            assertTrue(f.uploads.isEmpty())
            scene.clickText("添加声音", f)
            val callback = assertNotNull(f.preparer.callback)
            scene.clickText("关闭声音选择", f)
            val late = CountingLease()
            f.onUi { callback(SettingsPreparedNotificationSound(PreparedNotificationSoundUpload("synthetic/late.ogg", 100, 1000), late)) }
            scene.settledFrame()
            assertEquals(0, late.references)
            assertTrue(f.uploads.isEmpty())
        }
    }

    @Test fun removalNeedsTheFrozenRealMetadataAndAConfirmedRetryUsesTheCurrentReadRevision() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText(f.sound.title, f)
            scene.clickText("删除所选声音", f)
            assertTrue(f.removals.isEmpty())
            scene.clickText("取消删除", f)
            assertTrue(f.removals.isEmpty())
            scene.clickText("删除所选声音", f)
            scene.clickText("确认删除声音", f)
            assertEquals(f.sound, f.removals.single().sound)
            f.onUi { f.snapshot = f.snapshot.copy(saving = false, revision = 12, error = SettingsError.REQUEST_FAILED, refreshRequired = true) }
            scene.settledFrame()
            assertTrue(scene.hasText("确认删除声音"))
            scene.clickText("确认删除声音", f)
            assertEquals(1, f.removals.size)
            scene.clickText("重新读取声音列表", f)
            f.onUi { f.snapshot = f.snapshot.copy(refreshRequired = false, error = null, revision = 13) }
            scene.settledFrame()
            scene.clickText("确认删除声音", f)
            assertEquals(13L, f.removals.last().revision)
            assertEquals(f.sound, f.removals.last().sound)
            f.onUi { f.snapshot = f.snapshot.copy(saving = false, acceptedRevision = 1, readbackPending = true, revision = 14) }
            scene.settledFrame()
            assertFalse(scene.hasText("确认删除声音"))
            assertTrue(scene.hasText(f.sound.title), "A native acceptance alone must not pretend a list row has disappeared.")
        }
    }

    @Test fun unknownUploadLimitsAndOversizedMetadataStayReadableErrorsWithoutOpeningAFilePicker() {
        val f = Fixture(knownLimits = false)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.hasText("添加限制尚未读取，暂不能添加声音。"))
            scene.clickText("添加声音", f)
            assertEquals(0, f.preparer.openings)
            f.onUi { f.snapshot = f.snapshot.copy(data = f.snapshot.data!!.copy(sounds = List(4097) { f.sound.copy(id = it.toLong() + 10) })) }
            scene.settledFrame()
            assertTrue(scene.hasText("保存的声音列表过大，暂无法显示。请重新读取后重试。"))
            scene.clickText("默认声音", f)
            assertTrue(f.choices.isEmpty())
            scene.clickText("重新读取声音列表", f)
            assertEquals(1, f.refreshes.size)
        }
    }

    @Test fun realSelectionPreviewUploadAndRemovalControlsFitWideAndLowWindowsInBothThemes() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val f = Fixture(width, height, mode, volumeTarget = NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS))
            f.scene().use { scene ->
                scene.settledFrame()
                scene.reveal("本机铃声音量：100%", f)
                scene.settledFrame().save("volume-${mode.name}-${width}x$height")
                val preview = scene.reveal(f.sound.title, f)
                assertTrue(preview.left >= 0 && preview.right <= width && preview.top >= 3 && preview.bottom <= height - 3)
                scene.settledFrame().save("catalog-${mode.name}-${width}x$height")
                scene.click(preview)
                assertEquals(1, f.previews.size)
                scene.clickText("删除所选声音", f)
                scene.reveal("确认删除声音", f)
                scene.settledFrame().save("delete-${mode.name}-${width}x$height")
                scene.clickText("取消删除", f)
                scene.clickText("添加声音", f)
                val prepared = SettingsPreparedNotificationSound(PreparedNotificationSoundUpload("synthetic/fixture.mp3", 480, 1200), CountingLease())
                f.onUi { f.preparer.deliver(prepared) }
                scene.settledFrame()
                val upload = scene.reveal("确认添加声音", f)
                assertTrue(upload.top >= 3 && upload.bottom <= height - 3)
                scene.settledFrame().save("upload-${mode.name}-${width}x$height")
                scene.clickText("取消添加", f)
            }
        }
    }

    private data class Choice(val token: SavedNotificationSoundsToken, val revision: Long, val id: Long,
        val complete: (SettingsResult<Long>) -> Unit)
    private data class Preview(val token: SavedNotificationSoundsToken, val id: Long, val volume: Int)
    private data class Upload(val token: SavedNotificationSoundsToken, val revision: Long, val prepared: SettingsPreparedNotificationSound)
    private data class Removal(val token: SavedNotificationSoundsToken, val revision: Long, val sound: SavedNotificationSound)
    private data class VolumeRead(val token: SavedNotificationSoundsToken, val target: NotificationSoundVolumeTarget,
        val complete: (SettingsResult<NotificationSoundVolumeReading>) -> Unit)
    private data class VolumeWrite(val token: SavedNotificationSoundsToken, val expected: NotificationSoundVolumeReading,
        val desired: Int?, val complete: (SettingsResult<NotificationSoundVolumeReading>) -> Unit)
    private class Fixture(val width: Int = 520, val height: Int = 740, val mode: ColorSchemeMode = ColorSchemeMode.Light,
        initiallyShown: Boolean = true, soundCount: Int = 1, knownLimits: Boolean = true,
        volumeTarget: NotificationSoundVolumeTarget? = null, val initialVolume: Int? = null) {
        var show by mutableStateOf(initiallyShown)
        var active by mutableStateOf(true)
        var accountToken by mutableStateOf(SettingsToken(7, 1))
        var parentSelected by mutableStateOf(-1L)
        var target by mutableStateOf(volumeTarget)
        var automaticVolumeRead = true
        val sound = SavedNotificationSound(-952L, 2, 1000, "合成声音0", "", MediaFile(981, uniqueId = "synthetic-sound-file"))
        private val data = SavedNotificationSoundsData(70001, List(soundCount) { index ->
            if (index == 0) sound else sound.copy(id = -952L - index, title = "合成声音$index", file = MediaFile(981 + index, uniqueId = "synthetic-$index"))
        }, if (knownLimits) NotificationSoundLimits(1048576, 30, 4096) else NotificationSoundLimits())
        var snapshot by mutableStateOf(SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(7), data = data, revision = 10))
        var owner = 0L
        var admit = true
        val opens = mutableListOf<SettingsToken>()
        val leases = mutableListOf<() -> Boolean>()
        val closed = mutableListOf<SavedNotificationSoundsToken>()
        val selected = mutableListOf<Long>()
        var dismissals = 0
        val choices = mutableListOf<Choice>()
        val previews = mutableListOf<Preview>()
        val stops = mutableListOf<SavedNotificationSoundsToken>()
        val uploads = mutableListOf<Upload>()
        val removals = mutableListOf<Removal>()
        val retained = mutableListOf<AttachmentLease>()
        val refreshes = mutableListOf<SavedNotificationSoundsToken>()
        val volumeReads = mutableListOf<VolumeRead>()
        val volumeWrites = mutableListOf<VolumeWrite>()
        val preparer = OfflinePreparer()
        val actions = SavedNotificationSoundsActions(
            open = { token, lease ->
                opens += token; leases += lease
                if (admit && lease()) snapshot = snapshot.copy(token = SavedNotificationSoundsToken(token.generation, ++owner), opened = true,
                    choosing = false, saving = false)
            },
            close = { closed += it }, refresh = { refreshes += it },
            choose = { token, revision, id, complete -> choices += Choice(token, revision, id, complete)
                snapshot = snapshot.copy(choosing = true, revision = snapshot.revision + 1) },
            preview = { token, id, volume -> previews += Preview(token, id, volume)
                snapshot = snapshot.copy(previewSoundId = id, previewLoading = true) },
            stopPreview = { stops += it; snapshot = snapshot.copy(previewLoading = false, previewSoundId = null) },
            upload = { token, revision, prepared ->
                uploads += Upload(token, revision, prepared); retained += assertNotNull(prepared.lease.retain())
                snapshot = snapshot.copy(saving = true, revision = snapshot.revision + 1)
            },
            remove = { token, revision, sound -> removals += Removal(token, revision, sound)
                snapshot = snapshot.copy(saving = true, revision = snapshot.revision + 1) },
            readVolume = { token, target, complete ->
                volumeReads += VolumeRead(token, target, complete)
                if (automaticVolumeRead) complete(SettingsResult(NotificationSoundVolumeReading(70001, target, initialVolume, 1)))
            },
            saveVolume = { token, expected, desired, complete -> volumeWrites += VolumeWrite(token, expected, desired, complete) },
        )
        fun onUi(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action) }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmationHost(height.dp), LocalPreparedSoundPickerHost provides preparer) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SettingsSavedNotificationSoundPicker(show, accountToken, snapshot, actions, parentSelected,
                            onSelected = { selected += it; parentSelected = it }, onDismiss = { dismissals++; show = false }, active = active,
                            volumeTarget = target)
                    }
                }
            }
        }
    }
    private class OfflinePreparer : SettingsPreparedSoundPickerHost {
        var openings = 0
        var callback: ((SettingsPreparedNotificationSound) -> Unit)? = null
        @Composable override fun Content(show: Boolean, limits: NotificationSoundLimits, onDismiss: () -> Unit,
            onPrepared: (SettingsPreparedNotificationSound) -> Unit) {
            androidx.compose.runtime.DisposableEffect(show) {
                if (show) { openings++; callback = onPrepared }
                onDispose { }
            }
        }
        fun deliver(prepared: SettingsPreparedNotificationSound) { assertNotNull(callback)(prepared) }
    }
    private class CountingLease private constructor(private val counter: IntArray) : AttachmentLease {
        constructor() : this(intArrayOf(1))
        private var released = false
        val references get() = counter[0]
        override fun retain(): AttachmentLease? {
            if (released) return null
            counter[0]++
            return CountingLease(counter)
        }
        override fun release() { if (!released) { released = true; counter[0]-- } }
    }
    private class OfflineConfirmationHost(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 24.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; summary?.let { Text(it) }; content()
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.rowSelected(label: String): Boolean {
        var selected = false
        semanticsBounds {
            if (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }) {
                if (it.getOrNull(SemanticsProperties.Selected) == true ||
                    it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "已选择" }) selected = true
            }
            false
        }
        return selected
    }
    private fun ComposePixelScene.changeVolumeDraft(f: Fixture, beforeRelease: (() -> Unit)? = null): Int {
        var before: Int? = null
        semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().forEach { value ->
                Regex("本机铃声音量：(\\d+)%").matchEntire(value.text)?.groupValues?.get(1)?.toInt()?.let { number -> before = number }
            }
            false
        }
        reveal("本机铃声音量：${assertNotNull(before)}%", f)
        val slider = assertNotNull(semanticsBounds { "本机铃声音量" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .firstOrNull { it.width > 0 && it.height > 0 })
        moveTo(slider.center.x, slider.center.y)
        pressAt(slider.center.x, slider.center.y)
        dragTo(slider.left + slider.width * .42f, slider.center.y)
        settledFrame(2, 16)
        beforeRelease?.invoke()
        releaseAt(slider.left + slider.width * .42f, slider.center.y)
        settledFrame()
        var desired: Int? = null
        semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().forEach { value ->
                Regex("本机铃声音量：(\\d+)%").matchEntire(value.text)?.groupValues?.get(1)?.toInt()?.let { number -> desired = number }
            }
            false
        }
        return assertNotNull(desired)
    }
    private fun ComposePixelScene.clickText(label: String, f: Fixture) = click(reveal(label, f))
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        fun visible() = texts(label).firstOrNull { it.width > 0 && it.height >= 10 && it.top >= 3 && it.bottom <= f.height - 3 }
        var scanDown = false
        repeat(1100) {
            visible()?.let { return it }
            val target = texts(label).firstOrNull { it.width > 0 && it.height > 0 }
            val scroll = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 24 && it.top < f.height && it.bottom > 0 }
            var range: Pair<Float, Float>? = null
            semanticsBounds {
                it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { axis -> range = axis.value() to axis.maxValue() }; false
            }
            if (range?.first?.let { it <= .5f } == true) scanDown = true
            val delta = when { target != null -> if (target.top < 3) -12f else 12f; !scanDown -> -12f; else -> 12f }
            scrollAt(scroll?.center?.x ?: f.width * .5f,
                scroll?.center?.y?.coerceIn(5f, f.height - 5f) ?: f.height * .5f, deltaY = delta)
            settledFrame(4, 32)
        }
        return assertNotNull(visible(), "Actual scrolling must reveal $label; bounds=${texts(label)} viewport=${f.width}x${f.height}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-saved-sounds-validation/frames/$name.png")
        output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
    }
}
