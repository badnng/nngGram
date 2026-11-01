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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.settings.*
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Full parent pages and real child selection; fake asynchronous native responses and no OS UI. */
class SettingsSavedNotificationSoundsIntegrationTest {
    @Test fun aScopeSoundChoicePreservesEveryUnrelatedNotificationPreference() {
        val f = Fixture()
        val original = f.snapshot.notifications
        f.scene().use { scene ->
            scene.settledFrame()
            scene.clickText("选择私聊提示音", f)
            assertTrue(f.scopeSaves.isEmpty())
            scene.clickText(f.sound.title, f)
            assertTrue(f.choices.isEmpty(), "Choosing a row only updates the picker draft.")
            scene.clickText("保存声音选择", f)
            val pending = f.choices.single()
            assertEquals(f.sound.id, pending.id)
            assertTrue(f.scopeSaves.isEmpty(), "The parent cannot save before the native choice check completes.")
            f.onUi { f.complete(pending) }
            scene.settledFrame()
            val save = f.scopeSaves.single()
            assertEquals(SettingsToken(7, 1), save.token)
            assertEquals(NotificationScope.PRIVATE_CHATS, save.scope)
            assertEquals(original.getValue(save.scope).copy(soundId = f.sound.id), save.value)
            assertEquals(original, f.snapshot.notifications, "The UI dispatches the native save without inventing an accepted account snapshot.")
            assertTrue(f.exceptionSaves.isEmpty())
            assertEquals(listOf<NotificationSoundVolumeTarget>(NotificationSoundVolumeTarget.Scope(NotificationScope.PRIVATE_CHATS)), f.volumeReads)
            assertTrue(f.volumeWrites.isEmpty(), "Displaying the unset 100% must not create a local override.")
            assertFalse(f.soundLeases.single()())
        }
    }

    @Test fun aChatSoundChoiceOnlyChangesTheExistingRichDraftUntilTheUserSavesIt() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.openChatEditor(f)
            scene.clickText("消息预览：关闭", f)
            scene.clickText("选择聊天提示音", f)
            scene.clickText(f.sound.title, f)
            assertTrue(f.choices.isEmpty())
            scene.clickText("保存声音选择", f)
            assertTrue(f.exceptionSaves.isEmpty())
            assertTrue(f.scopeSaves.isEmpty())
            assertEquals(listOf<NotificationSoundVolumeTarget>(NotificationSoundVolumeTarget.Chat(f.peer.chatId)), f.volumeReads)
            assertTrue(f.volumeWrites.isEmpty())
            f.onUi { f.complete(f.choices.single()) }
            scene.settledFrame()
            assertTrue(f.exceptionSaves.isEmpty(), "A checked sound selection updates the parent draft, rather than submitting a native exception write.")
            scene.clickText("保存通知例外", f)
            val save = f.exceptionSaves.single()
            assertEquals(f.snapshot.notificationExceptions.token, save.token)
            assertEquals(5L, save.revision)
            assertEquals(setOf(NotificationExceptionField.PREVIEW, NotificationExceptionField.SOUND), save.edit.dirty)
            assertEquals(f.peer, save.edit.expected)
            assertEquals(f.peer.values.copy(useDefaultSound = false, soundId = f.sound.id,
                useDefaultShowPreview = false, showPreview = false), save.edit.desired)
            assertEquals(-444L, save.edit.desired.storySoundId)
            assertTrue(save.edit.desired.muteStories)
            assertFalse(save.edit.desired.showStoryPoster)
            assertTrue(f.scopeSaves.isEmpty())
        }
    }

    @Test fun disabledOrReplacedParentsCannotReceiveAnEarlierChoiceOrChangeTheNewEditorDraft() {
        for (replaceAccount in listOf(false, true)) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.settledFrame()
                scene.clickText("选择私聊提示音", f)
                scene.clickText(f.sound.title, f)
                scene.clickText("保存声音选择", f)
                val old = f.choices.single()
                val lease = f.soundLeases.single()
                f.onUi {
                    f.snapshot = if (replaceAccount) f.snapshot.copy(token = SettingsToken(7, 2)) else f.snapshot.copy(enabled = false)
                }
                scene.settledFrame()
                assertFalse(lease())
                f.onUi { f.complete(old) }
                scene.settledFrame()
                assertTrue(f.scopeSaves.isEmpty())
                assertTrue(f.exceptionSaves.isEmpty())
            }
        }
        for (replaceReadRevision in listOf(false, true)) {
            val f = Fixture()
            f.scene().use { scene ->
                scene.openChatEditor(f)
                scene.clickText("消息预览：关闭", f)
                scene.clickText("选择聊天提示音", f)
                scene.clickText(f.sound.title, f)
                scene.clickText("保存声音选择", f)
                val old = f.choices.single()
                val lease = f.soundLeases.single()
                f.onUi {
                    val child = f.snapshot.notificationExceptions
                    f.snapshot = f.snapshot.copy(notificationExceptions = if (replaceReadRevision)
                        child.copy(editorRevision = child.editorRevision + 1) else child.copy(refreshRequired = true))
                }
                scene.settledFrame()
                assertFalse(lease())
                f.onUi { f.complete(old) }
                scene.settledFrame()
                assertTrue(f.exceptionSaves.isEmpty())
                f.onUi { f.snapshot = f.snapshot.copy(notificationExceptions = f.snapshot.notificationExceptions.copy(refreshRequired = false)) }
                scene.settledFrame()
                if (!replaceReadRevision) scene.clickText("关闭声音选择", f)
                if (replaceReadRevision) scene.clickText("消息预览：关闭", f)
                scene.clickText("保存通知例外", f)
                val save = f.exceptionSaves.single()
                assertEquals(setOf(NotificationExceptionField.PREVIEW), save.edit.dirty,
                    "A retired sound callback must not add SOUND to the current editor's sparse write.")
                assertEquals(f.peer.values.useDefaultSound, save.edit.desired.useDefaultSound)
                assertEquals(f.peer.values.soundId, save.edit.desired.soundId)
                assertEquals(if (replaceReadRevision) 6L else 5L, save.revision)
                assertTrue(f.scopeSaves.isEmpty())
            }
        }
    }

    private data class Choice(val token: SavedNotificationSoundsToken, val revision: Long, val id: Long,
        val callback: (SettingsResult<Long>) -> Unit)
    private data class ScopeSave(val token: SettingsToken, val scope: NotificationScope, val value: NotificationPreferences)
    private data class ExceptionSave(val token: NotificationExceptionsToken, val revision: Long, val edit: NotificationExceptionEdit)
    private class Fixture(val width: Int = 620, val height: Int = 900) {
        val sound = SavedNotificationSound(-952L, 2, 1000, "合成真实保存声音", "", MediaFile(981, uniqueId = "synthetic-parent-sound"))
        val peer = NotificationExceptionPeer(41001, NotificationScope.PRIVATE_CHATS, "合成通知例外会话",
            ChatNotificationValues(useDefaultMuteFor = false, muteFor = 3600,
                useDefaultSound = true, soundId = -1, useDefaultShowPreview = true, showPreview = true,
                useDefaultMuteStories = false, muteStories = true, useDefaultStorySound = false, storySoundId = -444,
                useDefaultShowStoryPoster = false, showStoryPoster = false,
                useDefaultDisablePinnedMessageNotifications = true, disablePinnedMessageNotifications = true,
                useDefaultDisableMentionNotifications = true, disableMentionNotifications = true), 1000)
        private val preferences = NotificationPreferences(muteFor = 0, soundId = -77, showPreview = false,
            useDefaultMuteStories = false, muteStories = true, storySoundId = -333,
            showStoryPoster = false, disablePinnedMessageNotifications = true, disableMentionNotifications = true)
        var snapshot by mutableStateOf(SettingsSnapshot(token = SettingsToken(7, 1), enabled = true,
            loaded = setOf(SettingsSection.NOTIFICATIONS), notifications = NotificationScope.entries.associateWith { preferences },
            savedNotificationSounds = SavedNotificationSoundsSnapshot(token = SavedNotificationSoundsToken(7), revision = 10,
                data = SavedNotificationSoundsData(70001, listOf(sound), NotificationSoundLimits(1048576, 30, 100)))))
        var soundOwner = 0L
        var exceptionOwner = 0L
        val soundLeases = mutableListOf<() -> Boolean>()
        val closedSounds = mutableListOf<SavedNotificationSoundsToken>()
        val choices = mutableListOf<Choice>()
        val scopeSaves = mutableListOf<ScopeSave>()
        val exceptionSaves = mutableListOf<ExceptionSave>()
        val volumeReads = mutableListOf<NotificationSoundVolumeTarget>()
        val volumeWrites = mutableListOf<Int?>()
        private val soundActions = SavedNotificationSoundsActions(
            open = { token, lease ->
                soundLeases += lease
                if (lease()) snapshot = snapshot.copy(savedNotificationSounds = snapshot.savedNotificationSounds.copy(
                    token = SavedNotificationSoundsToken(token.generation, ++soundOwner), opened = true, choosing = false))
            }, close = { closedSounds += it },
            choose = { token, revision, id, callback ->
                choices += Choice(token, revision, id, callback)
                snapshot = snapshot.copy(savedNotificationSounds = snapshot.savedNotificationSounds.copy(choosing = true,
                    revision = snapshot.savedNotificationSounds.revision + 1))
            },
            readVolume = { _, target, result ->
                volumeReads += target
                result(SettingsResult(NotificationSoundVolumeReading(70001, target, null, 1)))
            },
            saveVolume = { _, expected, desired, result ->
                volumeWrites += desired
                result(SettingsResult(expected.copy(overridePercent = desired, revision = expected.revision + 1)))
            },
        )
        private val exceptionActions = NotificationExceptionsActions(
            open = { token, scope, lease -> if (lease()) snapshot = snapshot.copy(notificationExceptions = NotificationExceptionsSnapshot(
                token = NotificationExceptionsToken(token.generation, ++exceptionOwner, scope), opened = true, totalIds = 1,
                rows = listOf(NotificationExceptionRow(peer.chatId, peer)))) },
            edit = { _, id -> if (id == peer.chatId) snapshot = snapshot.copy(notificationExceptions = snapshot.notificationExceptions.copy(
                view = NotificationExceptionsView.EDITOR, editor = peer, editorRevision = 5)) },
            save = { token, revision, edit -> exceptionSaves += ExceptionSave(token, revision, edit) },
        )
        val actions = SettingsActions(savedNotificationSounds = soundActions, notificationExceptions = exceptionActions,
            saveNotifications = { token, scope, value -> scopeSaves += ScopeSave(token, scope, value) })
        fun complete(choice: Choice) {
            snapshot = snapshot.copy(savedNotificationSounds = snapshot.savedNotificationSounds.copy(choosing = false,
                revision = snapshot.savedNotificationSounds.revision + 1))
            choice.callback(SettingsResult(choice.id))
        }
        fun onUi(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action) }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides true,
                LocalSettingsSecurityConfirmationHost provides InlineConfirmationHost(height.dp), LocalPreparedSoundPickerHost provides NoFilesHost) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) { SettingsNotificationsPage(snapshot, actions) }
                }
            }
        }
    }
    private object NoFilesHost : SettingsPreparedSoundPickerHost {
        @Composable override fun Content(show: Boolean, limits: NotificationSoundLimits, onDismiss: () -> Unit,
            onPrepared: (SettingsPreparedNotificationSound) -> Unit) { check(!show) { "Selection must not open a file picker." } }
    }
    private class InlineConfirmationHost(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 32.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; summary?.let { Text(it) }; content()
            }
        }
    }
    private fun ComposePixelScene.openChatEditor(f: Fixture) {
        settledFrame(); clickText("私聊通知例外", f); clickText(f.peer.title, f)
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.clickText(label: String, f: Fixture) {
        val rect = reveal(label, f)
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        fun visible() = texts(label).firstOrNull { it.width > 0 && it.height >= 10 && it.top >= 3 && it.bottom <= f.height - 3 }
        var scanDown = false
        repeat(1400) {
            visible()?.let { return it }
            val target = texts(label).firstOrNull { it.width > 0 && it.height > 0 }
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 24 && it.top < f.height && it.bottom > 0 }
            var range: Pair<Float, Float>? = null
            semanticsBounds {
                it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { axis -> range = axis.value() to axis.maxValue() }; false
            }
            if (range?.first?.let { it <= .5f } == true) scanDown = true
            val delta = when { target != null -> if (target.top < 3) -12f else 12f; !scanDown -> -12f; else -> 12f }
            scrollAt(scroller?.center?.x ?: f.width * .5f,
                scroller?.center?.y?.coerceIn(5f, f.height - 5f) ?: f.height * .5f, deltaY = delta)
            settledFrame(4, 32)
        }
        return assertNotNull(visible(), "Actual scrolling must expose $label; bounds=${texts(label)}")
    }
}
