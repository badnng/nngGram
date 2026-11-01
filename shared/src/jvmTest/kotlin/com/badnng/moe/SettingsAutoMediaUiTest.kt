package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AutoMediaOverride
import com.badnng.moe.chat.AutoMediaCopyNotice
import com.badnng.moe.chat.AutoMediaPolicy
import com.badnng.moe.chat.AutoMediaSettings
import com.badnng.moe.chat.AutoMediaSource
import com.badnng.moe.chat.AutoMediaSourceRules
import com.badnng.moe.chat.AutoMediaType
import com.badnng.moe.chat.MIB
import com.badnng.moe.chat.autoMediaSizeByIndex
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual in-memory Miuix controls: no account, picker, filesystem or native media request. */
class SettingsAutoMediaUiTest {
    @Test
    fun parentAndChildCancellationDoNotSaveAndOpeningDoesNotRoundTheOriginalLimits() {
        val original = AutoMediaPolicy().withSource(AutoMediaSource.USER,
            AutoMediaSourceRules.defaults(AutoMediaSource.USER)
                .withLimit(AutoMediaType.PHOTO, 8 * MIB)
                .withLimit(AutoMediaType.FILE, 17 * MIB))
        val fixture = Fixture(original)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.clickVisible("自动下载照片", fixture)
            scene.clickVisible("取消私聊规则", fixture)
            assertTrue(fixture.saves.isEmpty(), "Cancelling the child cannot submit the borrowed parent draft.")

            scene.clickVisible("群组自动媒体", fixture)
            scene.clickVisible("取消自动媒体更改", fixture)
            assertTrue(fixture.saves.isEmpty(), "Cancelling the parent cannot change persisted rules.")
            assertEquals(original, fixture.settings.policy)

            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.clickVisible("应用私聊规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val request = fixture.saves.single()
            assertEquals(8L, request.revision)
            assertEquals(original, request.policy,
                "Merely opening the shared size editor must preserve both the exact 8 MiB and unequal 17 MiB limits.")
        }
    }

    @Test
    fun turningOneSourceOffAndOnRestoresItsDefaultsWithoutChangingOtherSourcesOrPeerExceptions() {
        val original = AutoMediaPolicy(peers = mapOf(91L to AutoMediaOverride.DENY))
            .withSource(AutoMediaSource.USER, AutoMediaSourceRules.defaults(AutoMediaSource.USER)
                .withLimit(AutoMediaType.PHOTO, 17 * MIB).withLimit(AutoMediaType.FILE, 29 * MIB))
            .withSource(AutoMediaSource.GROUP, AutoMediaSourceRules.defaults(AutoMediaSource.GROUP)
                .withLimit(AutoMediaType.VIDEO, 71 * MIB))
        val fixture = Fixture(original)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("私聊自动媒体", fixture)
            scene.clickVisible("私聊自动媒体", fixture)
            scene.clickVisible("编辑群组规则", fixture)
            scene.clickVisible("自动下载文件", fixture)
            scene.clickVisible("应用群组规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val saved = fixture.saves.single().policy
            assertEquals(AutoMediaSourceRules.defaults(AutoMediaSource.USER), saved.rules(AutoMediaSource.USER))
            assertEquals(original.rules(AutoMediaSource.GROUP).withLimit(AutoMediaType.FILE, 0L),
                saved.rules(AutoMediaSource.GROUP))
            assertEquals(original.rules(AutoMediaSource.CHANNEL), saved.rules(AutoMediaSource.CHANNEL))
            assertEquals(original.peers, saved.peers, "The read-only peer summary must preserve existing exceptions.")
        }
    }

    @Test
    fun draggingTheTwoActualSizeSlidersOnlyChangesTheEditedSourcesEnabledMediaGroups() {
        val original = AutoMediaPolicy().withSource(AutoMediaSource.USER,
            AutoMediaSourceRules.defaults(AutoMediaSource.USER)
                .withLimit(AutoMediaType.PHOTO, 8 * MIB)
                .withLimit(AutoMediaType.FILE, 17 * MIB)
                .withLimit(AutoMediaType.VIDEO, 50 * MIB)
                .withLimit(AutoMediaType.GIF, 80 * MIB))
        val fixture = Fixture(original)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.dragSlider("自动下载大小上限", .43f, fixture)
            scene.dragSlider("自动播放大小上限", .67f, fixture)
            scene.clickVisible("应用私聊规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val saved = fixture.saves.single().policy
            val user = saved.rules(AutoMediaSource.USER)
            val discreteSizes = (0..99).map(::autoMediaSizeByIndex).toSet()
            assertTrue(user.limit(AutoMediaType.PHOTO) in discreteSizes)
            assertTrue(user.limit(AutoMediaType.VIDEO) in discreteSizes)
            assertEquals(user.limit(AutoMediaType.PHOTO), user.limit(AutoMediaType.FILE))
            assertEquals(user.limit(AutoMediaType.VIDEO), user.limit(AutoMediaType.GIF))
            assertFalse(user.limit(AutoMediaType.PHOTO) == 8 * MIB)
            assertFalse(user.limit(AutoMediaType.VIDEO) == 50 * MIB)
            listOf(AutoMediaType.VOICE, AutoMediaType.MUSIC, AutoMediaType.VIDEO_NOTE).forEach {
                assertEquals(original.rules(AutoMediaSource.USER).limit(it), user.limit(it),
                    "The editable download/play groups must preserve the separate $it rule.")
            }
            assertEquals(original.rules(AutoMediaSource.GROUP), saved.rules(AutoMediaSource.GROUP))
            assertEquals(original.rules(AutoMediaSource.CHANNEL), saved.rules(AutoMediaSource.CHANNEL))
        }
    }

    @Test
    fun savingWaitsForTheMatchingSnapshotGatesDuplicatesAndRetainsTheDraftAfterFailure() {
        val fixture = Fixture()
        fixture.onUi { fixture.autoCompletion = null }
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("群组自动媒体", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(1, fixture.saves.size, "Repeated clicks cannot submit the same pending draft twice.")
            scene.reveal("正在保存自动媒体规则……", fixture)
            assertTrue(scene.texts("正在保存自动媒体规则……").isNotEmpty())
            fixture.onUi { fixture.settings = fixture.settings.copy(saving = true) }
            scene.settledFrame()
            assertTrue(scene.disabledText("保存自动媒体规则"))
            assertTrue(scene.texts("编辑自动媒体规则").isEmpty(),
                "Submitting is not an acceptance; the parent editor must remain open.")

            fixture.onUi { fixture.settings = fixture.settings.copy(saving = false, error = true) }
            scene.settledFrame()
            fixture.completionCallbacks.single()(false)
            scene.settledFrame()
            scene.reveal("自动媒体设置未保存，草稿已保留，请重试。", fixture)
            fixture.onUi { fixture.autoCompletion = true }
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(2, fixture.saves.size)
            assertEquals(fixture.saves[0], fixture.saves[1], "An explicit retry must retain its original content and revision.")
            assertFalse(fixture.saves.last().policy.rules(AutoMediaSource.GROUP).enabled)

            fixture.onUi {
                fixture.settings = fixture.settings.copy(revision = 9, policy = fixture.saves.last().policy,
                    saving = false, error = false)
            }
            scene.settledFrame()
            scene.reveal("自动媒体规则已保存。", fixture)
            assertTrue(scene.texts("编辑自动媒体规则").isNotEmpty())
            assertTrue(scene.texts("保存自动媒体规则").isEmpty())
        }
    }

    @Test
    fun newerRevisionRequiresExplicitReloadAndAnAccountSwapCannotSubmitThePreviousOwnersDraft() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("私聊自动媒体", fixture)
            val current = fixture.settings.policy.sourceEnabled(AutoMediaSource.CHANNEL, false)
            fixture.onUi { fixture.settings = fixture.settings.copy(revision = 9, policy = current) }
            scene.settledFrame()
            scene.reveal("自动媒体规则已变化，草稿已保留。请放弃草稿后载入当前规则。", fixture)
            assertTrue(scene.disabledText("保存自动媒体规则"))
            scene.clickVisible("保存自动媒体规则", fixture)
            assertTrue(fixture.saves.isEmpty())
            scene.clickVisible("放弃草稿并载入当前规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(Save(9, current), fixture.saves.single())

            fixture.onUi {
                fixture.settings = AutoMediaSettings(accountUserId = 202, revision = 2, loaded = true)
            }
            scene.settledFrame()
            assertTrue(scene.texts("保存自动媒体规则").isEmpty())
            scene.openParent(fixture)
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertTrue(scene.disabledText("保存自动媒体规则"))
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(1, fixture.saves.size, "An inactive settings page cannot save even its current draft.")
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(Save(2, AutoMediaPolicy()), fixture.saves.last())
        }
    }

    @Test
    fun narrowLowLightAndDarkPagesReachTheRealChildControlsAndTrailingParentActionsAtBothDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val fixture = Fixture(width = 390, height = 360, mode = mode, density = density)
            fixture.scene().use { scene ->
                scene.openParent(fixture)
                scene.clickVisible("编辑频道规则", fixture)
                scene.reveal("频道自动媒体规则", fixture)
                scene.reveal("自动下载照片", fixture)
                scene.revealDescription("自动下载大小上限", fixture)
                scene.reveal("自动播放 GIF", fixture)
                scene.revealDescription("自动播放大小上限", fixture)
                scene.reveal("语音与音乐按需流式加载", fixture)
                scene.reveal("圆视频自动播放暂未支持", fixture)
                scene.clickVisible("取消频道规则", fixture)
                scene.reveal("聊天例外暂未支持编辑", fixture)
                scene.reveal("取消自动媒体更改", fixture)
                val cancel = assertNotNull(scene.visibleTexts("取消自动媒体更改", fixture).lastOrNull())
                assertTrue(cancel.top >= 0 && cancel.bottom <= fixture.height,
                    "The actual trailing action must fit the $mode, ${density}x 390×360 viewport.")
                scene.click(cancel)
                scene.settledFrame()
                assertTrue(fixture.saves.isEmpty())
            }
        }
    }

    @Test
    fun actualSizeSliderSupportsSingleStepArrowKeysAndIgnoresControlModifiedArrows() {
        val fixture = Fixture(height = 1_200)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.revealDescription("自动下载大小上限", fixture)
            scene.click(scene.description("自动下载大小上限"))
            scene.settledFrame()
            var focused = scene.descriptionFocused("自动下载大小上限")
            repeat(20) {
                if (!focused) {
                    scene.keyStroke(KeyEvent.VK_TAB)
                    scene.settledFrame(2, 16)
                    focused = scene.descriptionFocused("自动下载大小上限")
                }
            }
            assertTrue(focused, "The actual download slider must accept keyboard focus after pointer or Tab input.")
            scene.keyStroke(KeyEvent.VK_RIGHT)
            scene.settledFrame()
            assertTrue(scene.texts("最多 9 MB").isNotEmpty(), "Right must advance the shared size by exactly one discrete step.")
            scene.keyStroke(KeyEvent.VK_RIGHT, control = true)
            scene.settledFrame()
            assertTrue(scene.texts("最多 9 MB").isNotEmpty(), "Ctrl+Right must not change an ordinary slider value.")
            scene.keyStroke(KeyEvent.VK_LEFT)
            scene.settledFrame()
            assertTrue(scene.texts("最多 8 MB").isNotEmpty(), "Left must return exactly one step.")
            scene.keyStroke(KeyEvent.VK_RIGHT)
            scene.settledFrame()
            scene.clickVisible("应用私聊规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val rules = fixture.saves.single().policy.rules(AutoMediaSource.USER)
            assertEquals(9 * MIB, rules.limit(AutoMediaType.PHOTO))
            assertEquals(9 * MIB, rules.limit(AutoMediaType.FILE))
            assertEquals(50 * MIB, rules.limit(AutoMediaType.VIDEO))
        }
    }

    @Test
    fun changingDensityAndAvailableWidthKeepsTheOpenChildDraftUntilItsParentIsSaved() {
        val fixture = Fixture(width = 640, height = 900)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.clickVisible("自动下载照片", fixture)
            scene.revealDescription("自动下载大小上限", fixture)
            val wideSlider = scene.description("自动下载大小上限")
            fixture.onUi { fixture.density = 1.25f; fixture.availableWidth = 312f }
            scene.settledFrame()
            scene.revealDescription("自动下载大小上限", fixture)
            val narrowSlider = scene.description("自动下载大小上限")
            assertTrue(narrowSlider.width < wideSlider.width,
                "The same child must really reflow into the smaller physical content width.")
            scene.reveal("私聊自动媒体规则", fixture)
            assertTrue(scene.texts("编辑自动媒体规则").isEmpty(), "Reflow cannot silently replace the in-memory child editor.")
            fixture.onUi { fixture.density = 1f; fixture.availableWidth = 640f }
            scene.settledFrame()
            scene.clickVisible("应用私聊规则", fixture)
            assertTrue(fixture.saves.isEmpty(), "Applying a child after reflow still only changes the parent draft.")
            scene.clickVisible("保存自动媒体规则", fixture)
            val saved = fixture.saves.single().policy
            assertEquals(0L, saved.rules(AutoMediaSource.USER).limit(AutoMediaType.PHOTO),
                "The unsaved toggle made before both density changes must survive.")
            assertEquals(8 * MIB, saved.rules(AutoMediaSource.USER).limit(AutoMediaType.FILE))
            assertEquals(AutoMediaSourceRules.defaults(AutoMediaSource.GROUP), saved.rules(AutoMediaSource.GROUP))
            assertEquals(AutoMediaSourceRules.defaults(AutoMediaSource.CHANNEL), saved.rules(AutoMediaSource.CHANNEL))
        }
    }

    @Test
    fun workspaceDraftSurvivesExclusiveUnmountAndAccountChangeDropsTheSingleSlot() {
        val fixture = Fixture(retainDraftSession = true)
        val session = assertNotNull(fixture.hoistedDraftSession)
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("群组自动媒体", fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.clickVisible("自动下载照片", fixture)
            val owner = assertNotNull(session.draftFor(101))
            assertNotNull(owner.child)

            fixture.onUi { fixture.controlsMounted = false }
            scene.settledFrame()
            assertTrue(scene.texts("应用私聊规则").isEmpty(), "The exclusive route must actually unmount the editor controls.")
            fixture.onUi { fixture.controlsMounted = true }
            scene.settledFrame()
            assertTrue(session.draftFor(101) === owner, "Remounting must reconnect to the same workspace-owned editor.")
            scene.clickVisible("应用私聊规则", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val accepted = fixture.saves.single()
            assertEquals(8L, accepted.revision)
            assertFalse(accepted.policy.rules(AutoMediaSource.GROUP).enabled)
            assertEquals(0L, accepted.policy.rules(AutoMediaSource.USER).limit(AutoMediaType.PHOTO))

            fixture.onUi { fixture.settings = fixture.settings.copy(revision = 9, policy = accepted.policy) }
            scene.settledFrame()
            assertNull(session.draftFor(101), "A matching published revision consumes the retained draft.")
            assertEquals(accepted.policy, fixture.settings.policy)

            scene.openParent(fixture)
            scene.clickVisible("群组自动媒体", fixture)
            assertNotNull(session.draftFor(101))
            scene.clickVisible("取消自动媒体更改", fixture)
            assertNull(session.draftFor(101), "Explicit parent cancellation discards the session draft.")
            assertEquals(accepted.policy, fixture.settings.policy)
            assertEquals(1, fixture.saves.size, "Cancelling must not submit another policy.")

            scene.openParent(fixture)
            scene.clickVisible("编辑私聊规则", fixture)
            scene.clickVisible("自动下载文件", fixture)
            assertNotNull(session.draftFor(101)?.child)
            fixture.onUi { fixture.settings = AutoMediaSettings(accountUserId = 202, revision = 2, loaded = true) }
            scene.settledFrame()
            assertNull(session.draftFor(202))
            assertTrue(scene.texts("应用私聊规则").isEmpty(), "A new real account cannot see the previous account's editor.")

            fixture.onUi { fixture.settings = AutoMediaSettings(accountUserId = 101, revision = 9,
                policy = accepted.policy, loaded = true) }
            scene.settledFrame()
            assertNull(session.draftFor(101), "Switching back cannot resurrect a draft kept in a per-account map.")
            assertTrue(scene.texts("应用私聊规则").isEmpty())
        }
    }

    @Test
    fun rejectedAdmissionAfterUnmountRestoresRetryAndLateOwnerCallbacksStayBounded() {
        val fixture = Fixture(retainDraftSession = true)
        val session = assertNotNull(fixture.hoistedDraftSession)
        fixture.onUi { fixture.autoAdmission = null; fixture.autoCompletion = null }
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("群组自动媒体", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val rejectedRequest = fixture.saves.single()
            val owner = assertNotNull(session.draftFor(101))
            assertTrue(owner.pending)

            fixture.onUi { fixture.controlsMounted = false }
            scene.settledFrame()
            fixture.admissionCallbacks.single()(false)
            scene.settledFrame()
            assertFalse(owner.pending)
            assertTrue(owner.failed)
            assertEquals(rejectedRequest.policy, owner.policy)

            fixture.onUi { fixture.controlsMounted = true }
            scene.settledFrame()
            scene.reveal("自动媒体设置未保存，草稿已保留，请重试。", fixture)
            assertTrue(scene.texts("自动媒体设置未保存，草稿已保留，请重试。").isNotEmpty())
            assertTrue(session.draftFor(101) === owner)

            fixture.onUi { fixture.autoAdmission = true; fixture.autoCompletion = true }
            scene.clickVisible("保存自动媒体规则", fixture)
            assertEquals(2, fixture.saves.size)
            assertEquals(rejectedRequest, fixture.saves.last())
            assertTrue(owner.pending)
            assertFalse(owner.failed)

            val retry = fixture.saves.last()
            fixture.onUi { fixture.settings = fixture.settings.copy(revision = 9, policy = retry.policy) }
            scene.settledFrame()
            assertNull(session.draftFor(101), "Only the accepted revision finishes the retry.")
            fixture.admissionCallbacks.first()(false)
            scene.settledFrame()
            assertNull(session.draftFor(101), "A late callback cannot recreate or alter a consumed owner.")
        }
    }

    @Test
    fun sameRevisionOldErrorDoesNotSettleRetryAndFailureCompletionSurvivesUnmount() {
        val fixture = Fixture(retainDraftSession = true)
        val session = assertNotNull(fixture.hoistedDraftSession)
        fixture.onUi {
            fixture.settings = fixture.settings.copy(error = true)
            fixture.autoCompletion = false
        }
        fixture.scene().use { scene ->
            scene.openParent(fixture)
            scene.clickVisible("群组自动媒体", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val owner = assertNotNull(session.draftFor(101))
            assertFalse(owner.pending)
            assertTrue(owner.failed, "The completed first write failure makes the next submit a retry.")

            fixture.onUi { fixture.autoCompletion = null }
            scene.clickVisible("保存自动媒体规则", fixture)
            assertTrue(owner.pending)
            val retry = fixture.saves.last()

            fixture.onUi { fixture.controlsMounted = false }
            scene.settledFrame()
            fixture.onUi { fixture.controlsMounted = true }
            scene.settledFrame()
            assertTrue(owner.pending, "The previous same-revision error is not the retry's terminal result.")
            assertFalse(owner.failed)

            fixture.onUi { fixture.controlsMounted = false }
            scene.settledFrame()
            fixture.completionCallbacks[1](false)
            scene.settledFrame()
            assertFalse(owner.pending)
            assertTrue(owner.failed)
            assertEquals(retry.policy, owner.policy)

            fixture.onUi { fixture.controlsMounted = true; fixture.autoCompletion = true }
            scene.settledFrame()
            scene.reveal("自动媒体设置未保存，草稿已保留，请重试。", fixture)
            scene.clickVisible("保存自动媒体规则", fixture)
            val accepted = fixture.saves.last()
            assertTrue(owner.pending, "Successful terminal completion still waits for published state.")
            fixture.onUi { fixture.settings = fixture.settings.copy(revision = 9, policy = accepted.policy, error = false) }
            scene.settledFrame()
            assertNull(session.draftFor(101), "Only the matching revision consumes the completed retry.")
        }
    }

    @Test
    fun copyJournalPendingAndUserChangesShowTheirActualOutcomeWithoutReportingTotalWriteFailure() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            fixture.onUi { fixture.settings = fixture.settings.copy(copyError = true, copyNotice = AutoMediaCopyNotice.RECORD_PENDING) }
            scene.settledFrame()
            scene.reveal("文件已写入下载目录，保存记录尚未提交；不会自动另建第二份。", fixture)
            assertTrue(scene.texts("自动文件副本保存失败，原生缓存仍保留。").isEmpty())
            fixture.onUi { fixture.settings = fixture.settings.copy(copyNotice = AutoMediaCopyNotice.USER_CHANGED_TARGET) }
            scene.settledFrame()
            scene.reveal("先前自动保存的文件已被删除或修改，当前不会自动重建。", fixture)
            assertTrue(fixture.saves.isEmpty())
        }
    }

    private data class Save(val revision: Long, val policy: AutoMediaPolicy)

    private class Fixture(policy: AutoMediaPolicy = AutoMediaPolicy(), val width: Int = 640,
        val height: Int = 900, val mode: ColorSchemeMode = ColorSchemeMode.Light, density: Float = 1f,
        retainDraftSession: Boolean = false,
    ) {
        var settings by mutableStateOf(AutoMediaSettings(accountUserId = 101, revision = 8, policy = policy, loaded = true))
        var active by mutableStateOf(true)
        var density by mutableStateOf(density)
        var availableWidth by mutableStateOf(width / density)
        var controlsMounted by mutableStateOf(true)
        val hoistedDraftSession = if (retainDraftSession) AutoMediaEditorDraftSession() else null
        val saves = mutableListOf<Save>()
        val admissionCallbacks = mutableListOf<(Boolean) -> Unit>()
        val completionCallbacks = mutableListOf<(Boolean) -> Unit>()
        var autoAdmission: Boolean? = true
        var autoCompletion: Boolean? = true

        fun scene() = ComposePixelScene(width, height) {
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active) {
                MiuixTheme(controller = remember { ThemeController(mode) }) {
                    Column(Modifier.widthIn(max = availableWidth.dp).fillMaxWidth().fillMaxHeight()
                        .background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (controlsMounted) SettingsAutoMediaControls(settings,
                            { revision, policy, admission, completed -> submit(revision, policy, admission, completed) },
                            hoistedDraft = hoistedDraftSession)
                    }
                }
            }
        }

        private fun submit(revision: Long, policy: AutoMediaPolicy, admission: (Boolean) -> Unit, completed: (Boolean) -> Unit) {
            saves += Save(revision, policy)
            admissionCallbacks += admission
            completionCallbacks += completed
            autoAdmission?.let { accepted ->
                admission(accepted)
                if (accepted) autoCompletion?.let(completed)
            }
        }

        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }

    private fun ComposePixelScene.texts(label: String): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }.filter { it.width > 0 && it.height > 0 }

    private fun ComposePixelScene.disabledText(label: String): Boolean = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
            it.getOrNull(SemanticsProperties.Disabled) != null
    }.isNotEmpty()

    private fun ComposePixelScene.description(label: String): Rect = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.lastOrNull { it.width > 0 && it.height > 0 }, "Missing actual size slider: $label")

    private fun ComposePixelScene.descriptionFocused(label: String): Boolean = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
            it.getOrNull(SemanticsProperties.Focused) == true
    }.isNotEmpty()

    private fun ComposePixelScene.visibleTexts(label: String, fixture: Fixture) = texts(label).filter {
        it.height >= 12 && it.top >= 1 && it.bottom <= fixture.height - 1
    }

    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }

    private fun ComposePixelScene.clickVisible(label: String, fixture: Fixture) {
        reveal(label, fixture)
        click(assertNotNull(visibleTexts(label, fixture).lastOrNull(), "Missing actual control: $label"))
        settledFrame()
    }

    private fun ComposePixelScene.openParent(fixture: Fixture) {
        settledFrame()
        clickVisible("编辑自动媒体规则", fixture)
        reveal("私聊自动媒体", fixture)
    }

    private fun ComposePixelScene.reveal(label: String, fixture: Fixture) = revealBounds(label, fixture) { texts(label) }

    private fun ComposePixelScene.revealDescription(label: String, fixture: Fixture) = revealBounds(label, fixture) {
        semanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    }

    private fun ComposePixelScene.revealBounds(label: String, fixture: Fixture, candidates: () -> List<Rect>) {
        fun visible() = candidates().any { it.width > 0 && it.height >= 12 && it.top >= 1 && it.bottom <= fixture.height - 1 }
        fun ranges(): List<Pair<Float, Float>> = buildList {
            semanticsBounds { config ->
                config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { add(it.value() to it.maxValue()) }
                false
            }
        }
        val initiallyVisible = visible()
        val initial = ranges()
        var moved = false
        var direction = 5f
        var previousRanges: List<Pair<Float, Float>>? = null
        repeat(60) {
            val current = ranges()
            if (current.zip(initial).any { (now, start) -> abs(now.first - start.first) > .5f }) moved = true
            if (visible()) {
                assertTrue(initiallyVisible || moved, "Clipped control must be reached by actual scrolling: $label")
                return
            }
            val scroll = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.height > 20 && it.bottom > 0 && it.top < fixture.height }
            // Fully clipped children report Rect.Zero. Search to one real scroll edge, then
            // reverse, so opening a child from a scrolled parent can reach earlier controls too.
            val actualBounds = candidates().filter { it.width > 0 && it.height > 0 }
            direction = when {
                actualBounds.any { it.bottom < 1 } -> -5f
                actualBounds.any { it.top >= fixture.height } -> 5f
                previousRanges != null && current == previousRanges -> -direction
                else -> direction
            }
            previousRanges = current
            scrollAt(scroll?.center?.x ?: fixture.width * .7f,
                scroll?.center?.y?.coerceIn(8f, fixture.height - 8f) ?: fixture.height * .6f, deltaY = direction)
            settledFrame(5, 32)
        }
        assertTrue(visible(), "Unreachable actual control: $label; bounds=${candidates()}; initial=$initial; current=${ranges()}")
    }

    /** Pinned Miuix sliders accept dragging rather than click-to-seek. */
    private fun ComposePixelScene.dragSlider(label: String, fraction: Float, fixture: Fixture) {
        revealDescription(label, fixture)
        val bounds = assertNotNull(semanticsBounds {
            label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.lastOrNull { it.width > 0 && it.height >= 12 && it.top >= 1 && it.bottom <= fixture.height - 1 })
        val start = bounds.left + bounds.width * .15f
        val end = bounds.left + bounds.width * fraction
        moveTo(start, bounds.center.y)
        pressAt(start, bounds.center.y)
        settledFrame(1, 16)
        dragTo(end + 12f, bounds.center.y)
        settledFrame(1, 16)
        dragTo(end, bounds.center.y)
        settledFrame(1, 16)
        releaseAt(end, bounds.center.y)
        settledFrame()
    }
}
