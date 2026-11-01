package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.folders.*
import com.badnng.moe.settings.SettingsError
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real Miuix controls and pointer/scroll input; the actor/native boundary is an explicit fake. */
class FolderSettingsFollowupUiTest {
    @Test
    fun actualOrderRecommendationAndParentApplyUseOneRetainedDraftAndExplicitConfirmation() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("下移 Work", fixture)
            scene.click(scene.description("下移 Work"))
            scene.settledFrame()
            assertEquals(listOf(FolderDraftEntry.Main, FolderDraftEntry.Existing(3), FolderDraftEntry.Existing(2)), fixture.settings.draft?.order)
            scene.click(scene.text("Work"))
            scene.reveal("创建新文件夹", fixture)
            scene.click(scene.text("创建新文件夹"))
            assertTrue(fixture.editorOpens.isEmpty(), "An overview draft must disable old editing and creation entrances.")
            scene.reveal("Unread", fixture)
            scene.click(scene.text("Unread"))
            scene.settledFrame()
            assertTrue(FolderDraftEntry.Recommended(41) in fixture.settings.draft!!.order)
            assertTrue(fixture.writes.isEmpty(), "Selecting a recommendation only stages the known entry token.")
            scene.revealDescription("取消添加 Unread", fixture)
            scene.click(scene.description("取消添加 Unread"))
            scene.settledFrame()
            assertEquals(listOf(FolderDraftEntry.Main, FolderDraftEntry.Existing(3), FolderDraftEntry.Existing(2)), fixture.settings.draft!!.order,
                "Cancelling a staged recommendation retains the independently changed complete order.")
            assertTrue(fixture.writes.isEmpty())
            scene.reveal("Unread", fixture)
            scene.click(scene.text("Unread"))
            scene.settledFrame()
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            assertTrue(fixture.writes.isEmpty(), "The first Apply click is reviewable confirmation, not a native write.")
            scene.reveal("确认应用更改", fixture)
            val submitted = fixture.settings.draft!!.revision
            scene.click(scene.text("确认应用更改"))
            scene.settledFrame()
            assertEquals(listOf("apply:$submitted"), fixture.writes)
            assertTrue(fixture.settings.busy)
            fixture.onUi { fixture.settings = fixture.settings.copy(busy = false, acceptedSteps = 2,
                draft = FolderOverviewDraft.from(fixture.settings.nativeState, submitted + 1)) }
            scene.settledFrame()
            scene.reveal("TDLib 已接受 2 项操作并安排同步；跨设备结果仍以服务端同步为准。", fixture)
            assertNotNull(scene.text("TDLib 已接受 2 项操作并安排同步；跨设备结果仍以服务端同步为准。"))
        }
    }

    @Test
    fun removalRestoreAndCancelledApplyNeverWriteOrCallTheOldImmediateDelete() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("移除 Work", fixture)
            scene.click(scene.description("移除 Work"))
            scene.settledFrame()
            assertEquals(setOf(2), fixture.settings.draft!!.removed)
            scene.click(scene.description("恢复 Work"))
            scene.settledFrame()
            assertTrue(fixture.settings.draft!!.removed.isEmpty())
            assertFalse(fixture.settings.draft!!.dirty)
            assertTrue(fixture.writes.isEmpty())
            scene.click(scene.description("移除 Work"))
            scene.settledFrame()
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            scene.click(scene.text("取消应用"))
            scene.settledFrame()
            assertTrue(fixture.writes.isEmpty())
            assertEquals(setOf(2), fixture.settings.draft!!.removed, "Cancelling confirmation retains the original unsubmitted draft.")
            assertEquals(0, fixture.oldDeletes)
        }
    }

    @Test
    fun pendingOpenLeaseIsRevokedBeforeAnyTokenAndOutgoingPageCannotCloseAnotherOwner() {
        val fixture = Fixture(completeOpening = false)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val request = fixture.opens.single()
            assertTrue(request.lease())
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertFalse(request.lease(), "The synchronous page lease exists before an actor publishes a token.")
            assertTrue(fixture.closes.isEmpty())
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            val reopened = fixture.opens.last()
            assertTrue(reopened.pageId != request.pageId)
            fixture.onUi { fixture.publishOpen(request) }
            scene.settledFrame()
            assertTrue(fixture.settings.token.pageId != reopened.pageId)
            fixture.onUi { fixture.publishOpen(reopened) }
            scene.settledFrame()
            val own = fixture.settings.token
            fixture.onUi { fixture.settings = fixture.settings.copy(token = own.copy(owner = own.owner + 1, pageId = request.pageId)) }
            scene.settledFrame()
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertEquals(listOf(own), fixture.closes, "Disposal closes only this page's first claimed token, never the latest global owner.")
            assertFalse(reopened.lease())
        }
    }

    @Test
    fun departureAndNewDraftInvalidateTheActualDangerousConfirmation() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("移除 Work", fixture)
            scene.click(scene.description("移除 Work"))
            scene.settledFrame()
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            val stale = scene.text("确认应用更改")
            fixture.onUi { fixture.active = false }
            scene.settledFrame()
            assertTrue(scene.texts("确认应用更改").isEmpty())
            scene.click(stale)
            assertTrue(fixture.writes.isEmpty())
            fixture.onUi { fixture.active = true }
            scene.settledFrame()
            assertEquals(setOf(2), fixture.settings.draft!!.removed)
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            fixture.onUi { fixture.settings = fixture.settings.copy(draft = fixture.settings.draft!!.copy(revision = fixture.settings.draft!!.revision + 1)) }
            scene.settledFrame()
            assertTrue(scene.texts("确认应用更改").isEmpty(), "An equal-looking new revision cannot inherit an old confirmation.")
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun narrowLowAndScaledDarkPagesReachRealControlsWithoutClippingAndKeepLocalChoices() {
        listOf(Triple(390, 300, 1f), Triple(490, 390, 1.25f), Triple(780, 620, 2f)).forEachIndexed { index, dimensions ->
            val fixture = Fixture(dimensions.first, dimensions.second, dimensions.third, dark = index > 0)
            fixture.scene().use { scene ->
                scene.settledFrame().save("narrow-low-$index")
                scene.revealDescription("移除 Work", fixture)
                val remove = scene.description("移除 Work")
                assertTrue(remove.left >= 0 && remove.right <= fixture.width && remove.top >= 0 && remove.bottom <= fixture.height)
                scene.click(remove)
                scene.settledFrame()
                scene.click(scene.description("恢复 Work"))
                scene.settledFrame()
                scene.reveal("将分组显示在顶部", fixture)
                scene.click(scene.text("将分组显示在顶部"))
                scene.settledFrame()
                assertEquals(FolderTabPlacement.TOP, fixture.preferences.placement)
                scene.reveal("仅限图标", fixture)
                scene.click(scene.text("仅限图标"))
                scene.settledFrame()
                assertEquals(FolderTabAppearance.ICON_ONLY, fixture.preferences.appearance)
                assertTrue(fixture.writes.isEmpty(), "Local appearance and pre-apply restore never submit account mutations.")
                scene.settledFrame().save("local-choices-$index")
            }
        }
    }

    @Test
    fun premiumTagsColorsAndConflictsDispatchOnlyEnabledNativeActions() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("设置 Work 的颜色", fixture)
            scene.click(scene.description("设置 Work 的颜色"))
            scene.settledFrame()
            scene.reveal("粉色", fixture)
            scene.click(scene.text("粉色"))
            scene.settledFrame()
            assertEquals(listOf("color:2:6"), fixture.writes)
            scene.reveal("显示文件夹标签", fixture)
            scene.click(scene.text("显示文件夹标签"))
            scene.settledFrame()
            assertEquals("tags:false", fixture.writes.last())
            fixture.onUi { fixture.settings = fixture.settings.copy(capabilities = fixture.settings.capabilities!!.copy(premium = false),
                conflict = true, error = SettingsError.CONFLICT) }
            scene.settledFrame()
            scene.click(scene.text("显示文件夹标签"))
            assertEquals(2, fixture.writes.size)
            scene.reveal("放弃草稿", fixture)
            assertNotNull(scene.text(settingsErrorText(SettingsError.CONFLICT)))
            scene.click(scene.text("放弃草稿"))
            scene.settledFrame()
            assertFalse(fixture.settings.conflict)
            assertTrue(fixture.settings.error == null)
        }
    }

    @Test
    fun ordinaryMainAndSharedDefinitionControlsCannotStageForbiddenChanges() {
        val fixture = Fixture()
        fixture.onUi {
            val state = fixture.settings.nativeState.copy(folders = fixture.source.folders.map { if (it.id == 3) it.copy(isShareable = true) else it })
            fixture.settings = fixture.settings.copy(nativeState = state, draft = FolderOverviewDraft.from(state),
                capabilities = fixture.settings.capabilities!!.copy(premium = false))
        }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("下移 全部", fixture)
            scene.click(scene.description("下移 全部"))
            scene.settledFrame()
            assertEquals(FolderDraftEntry.Main, fixture.settings.draft!!.order.first())
            scene.revealDescription("移除 Social", fixture)
            scene.click(scene.description("移除 Social"))
            scene.settledFrame()
            assertTrue(fixture.settings.draft!!.removed.isEmpty())
            scene.revealDescription("设置 Social 的颜色", fixture)
            scene.click(scene.description("设置 Social 的颜色"))
            scene.settledFrame()
            assertTrue(scene.texts("粉色").isEmpty())
            scene.reveal("显示文件夹标签", fixture)
            scene.click(scene.text("显示文件夹标签"))
            scene.settledFrame()
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test
    fun rawPremiumLossImmediatelyRevokesStaleCapabilitiesButPreservesOrdinaryDraftActions() {
        val fixture = Fixture()
        fixture.onUi { fixture.account = AccountProfile(71, "Synthetic account", isPremium = true) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("设置 Work 的颜色", fixture)
            scene.click(scene.description("设置 Work 的颜色"))
            scene.settledFrame()
            scene.reveal("粉色", fixture)
            val color = scene.text("粉色")
            scene.moveTo(color.center.x, color.center.y)
            scene.pressAt(color.center.x, color.center.y)
            fixture.onUi { fixture.account = fixture.account!!.copy(isPremium = false) }
            scene.settledFrame()
            scene.releaseAt(color.center.x, color.center.y)
            scene.settledFrame()
            assertTrue(fixture.settings.capabilities!!.premium, "The test must retain the stale capability response.")
            assertTrue(fixture.settings.nativeState.tagsEnabled, "Native enabled tags do not authorize further Premium edits.")
            assertTrue(scene.texts("粉色").isEmpty(), "Losing native Premium removes an already expanded color picker.")
            scene.revealDescription("设置 Work 的颜色", fixture)
            scene.click(scene.description("设置 Work 的颜色"))
            scene.settledFrame()
            assertTrue(scene.texts("粉色").isEmpty())
            scene.reveal("显示文件夹标签", fixture)
            scene.click(scene.text("显示文件夹标签"))
            scene.settledFrame()
            scene.revealDescription("下移 全部", fixture)
            val original = fixture.settings.draft
            scene.click(scene.description("下移 全部"))
            scene.settledFrame()
            assertEquals(original, fixture.settings.draft)
            assertTrue(fixture.writes.isEmpty(), "Neither held color input, tags nor Main movement can reuse stale Premium eligibility.")

            scene.revealDescription("上移 Social", fixture)
            scene.click(scene.description("上移 Social"))
            scene.settledFrame()
            assertEquals(listOf(FolderDraftEntry.Main, FolderDraftEntry.Existing(3), FolderDraftEntry.Existing(2)), fixture.settings.draft!!.order)
            scene.revealDescription("移除 Work", fixture)
            scene.click(scene.description("移除 Work"))
            scene.settledFrame()
            val ordinary = fixture.settings.draft
            assertEquals(setOf(2), ordinary!!.removed)
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            scene.reveal("取消应用", fixture)
            scene.click(scene.text("取消应用"))
            scene.settledFrame()
            assertEquals(ordinary, fixture.settings.draft, "Ordinary ordering/removal remains usable and confirmation cancellation retains its draft.")
            assertTrue(fixture.writes.isEmpty())

            scene.reveal("放弃草稿", fixture)
            scene.click(scene.text("放弃草稿"))
            scene.settledFrame()
            fixture.onUi { fixture.account = null }
            scene.settledFrame()
            scene.revealDescription("设置 Work 的颜色", fixture)
            scene.click(scene.description("设置 Work 的颜色"))
            scene.settledFrame()
            scene.reveal("粉色", fixture)
            scene.click(scene.text("粉色"))
            scene.settledFrame()
            assertEquals(listOf("color:2:6"), fixture.writes, "An unknown native account leaves the actual Premium capability usable.")
        }
    }

    @Test
    fun rawPremiumLossClearsOnlyThePremiumDependentApplyAndKeepsItsOriginalDraft() {
        val fixture = Fixture()
        fixture.onUi { fixture.account = AccountProfile(71, "Synthetic account", isPremium = true) }
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.revealDescription("下移 全部", fixture)
            scene.click(scene.description("下移 全部"))
            scene.settledFrame()
            val draft = fixture.settings.draft
            assertEquals(1, draft!!.order.indexOf(FolderDraftEntry.Main))
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            scene.reveal("确认应用更改", fixture)
            val confirm = scene.text("确认应用更改")
            scene.moveTo(confirm.center.x, confirm.center.y)
            scene.pressAt(confirm.center.x, confirm.center.y)
            fixture.onUi { fixture.account = fixture.account!!.copy(isPremium = false) }
            scene.settledFrame()
            scene.releaseAt(confirm.center.x, confirm.center.y)
            scene.settledFrame()
            assertTrue(scene.texts("确认应用更改").isEmpty())
            assertEquals(draft, fixture.settings.draft)
            assertTrue(fixture.writes.isEmpty())
            scene.reveal("应用更改", fixture)
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            assertTrue(scene.texts("确认应用更改").isEmpty(), "A nonzero final Main position cannot be applied with known non-Premium account state.")
            fixture.onUi { fixture.account = null }
            scene.settledFrame()
            assertTrue(scene.texts("确认应用更改").isEmpty(), "Restored eligibility cannot revive a revoked confirmation.")
            scene.click(scene.text("应用更改"))
            scene.settledFrame()
            scene.reveal("取消应用", fixture)
            scene.click(scene.text("取消应用"))
            scene.settledFrame()
            assertEquals(draft, fixture.settings.draft)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    private data class Open(val pageId: Long, val lease: () -> Boolean)
    private class Fixture(val width: Int = 900, val height: Int = 660, val density: Float = 1f,
        val dark: Boolean = false, val completeOpening: Boolean = true) {
        val source = FolderNativeState(listOf(ChatFolderInfo(2, "Work"), ChatFolderInfo(3, "Social")), tagsEnabled = true)
        var settings by mutableStateOf(FolderSettingsSnapshot(token = FolderSettingsToken(17), nativeState = source,
            capabilities = FolderCapabilities(true, 10, 100), recommendations = listOf(FolderRecommendation(41, "Unread", "Unread synthetic chats")),
            draft = FolderOverviewDraft.from(source)))
        var active by mutableStateOf(true)
        var account by mutableStateOf<AccountProfile?>(null)
        var preferences by mutableStateOf(ChatFolderPreferences())
        val opens = mutableListOf<Open>()
        val closes = mutableListOf<FolderSettingsToken>()
        val writes = mutableListOf<String>()
        val editorOpens = mutableListOf<Int>()
        var oldDeletes = 0
        private var nextOwner = 0L
        fun publishOpen(open: Open) { settings = settings.copy(opened = true, token = FolderSettingsToken(17, ++nextOwner, open.pageId)) }
        private fun edit(transform: (FolderOverviewDraft) -> FolderOverviewDraft) {
            val old = settings.draft!!
            settings = settings.copy(draft = transform(old).copy(revision = old.revision + 1))
        }
        private val actions = FolderSettingsActions(open = { _, pageId, lease ->
            val open = Open(pageId, lease)
            opens += open
            if (completeOpening) publishOpen(open)
        }, close = closes::add,
            move = { _, entry, delta -> edit { draft -> val index = draft.order.indexOf(entry)
                draft.copy(order = draft.order.toMutableList().apply { add(index + delta, removeAt(index)) }) } },
            stageRecommendation = { _, key -> edit { val entry = FolderDraftEntry.Recommended(key)
                it.copy(order = if (entry in it.order) it.order - entry else it.order + entry) } },
            remove = { _, id -> edit { it.copy(removed = it.removed + id) } },
            restore = { _, id -> edit { it.copy(removed = it.removed - id) } },
            reset = { _ -> settings = settings.copy(conflict = false, error = null, draft = FolderOverviewDraft.from(settings.nativeState, settings.draft!!.revision + 1)) },
            apply = { _, revision -> writes += "apply:$revision"; settings = settings.copy(busy = true) },
            toggleTags = { _, enabled -> writes += "tags:$enabled" }, color = { _, folder, color -> writes += "color:${folder.id}:$color" })
        private val features = ChatFeatureActions(folders = actions, editFolder = { _, id -> editorOpens += id },
            deleteFolder = { _, _ -> oldDeletes++ })
        fun onUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
        fun scene() = ComposePixelScene(width, height) {
            MiuixTheme(controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }) {
                CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                    LocalSettingsSecurityConfirmationHost provides OfflineConfirmation((height / density).dp)) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(8.dp)) {
                        FolderSettingsFollowupOverview(ChatSnapshot(enabled = true, token = ChatToken(17, 0, null), account = account, folders = source.folders,
                            folderSettings = settings), features, preferences, { preferences = it }, remember { LazyListState() }, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    private class OfflineConfirmation(val height: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().heightIn(max = height - 16.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title?.let { Text(it) }
                    content()
                }
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).lastOrNull(), label)
    private fun ComposePixelScene.descriptions(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).lastOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.reveal(label: String, fixture: Fixture) = revealBounds(fixture) { texts(label) }
    private fun ComposePixelScene.revealDescription(label: String, fixture: Fixture) = revealBounds(fixture) { descriptions(label) }
    private fun ComposePixelScene.revealBounds(fixture: Fixture, target: () -> List<Rect>) {
        if (target().any { it.top >= 4 && it.bottom <= fixture.height - 4 && it.height >= 12 }) return
        val initial = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < fixture.height }
        // Lazy items above the viewport may have no semantics node. Reset using a
        // real wheel event before looking forward, rather than treating absence as below.
        scrollAt(initial?.center?.x ?: fixture.width * .7f,
            initial?.center?.y?.coerceIn(4f, fixture.height - 4f) ?: fixture.height * .6f, deltaY = -1000f)
        settledFrame(8, 32)
        repeat(60) {
            val bounds = target()
            if (bounds.any { it.top >= 4 && it.bottom <= fixture.height - 4 && it.height >= 12 }) return
            val scroller = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 20 && it.bottom > 0 && it.top < fixture.height }
            val direction = if (bounds.any { it.bottom < 4 }) -5f else 5f
            scrollAt(scroller?.center?.x ?: fixture.width * .7f,
                scroller?.center?.y?.coerceIn(4f, fixture.height - 4f) ?: fixture.height * .6f, deltaY = direction)
            settledFrame(4, 32)
        }
        assertTrue(target().any { it.top >= 4 && it.bottom <= fixture.height - 4 && it.height >= 12 }, "Unreachable actual control: ${target()}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val output = File("build/settings-folders-followup-validation/frames/$name.png")
        output.parentFile.mkdirs()
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) bitmap.setRGB(x, y, this[x, y])
        ImageIO.write(bitmap, "png", output)
    }
}
