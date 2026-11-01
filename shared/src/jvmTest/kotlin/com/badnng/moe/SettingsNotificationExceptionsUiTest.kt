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
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual Compose pointer/scroll controls with synthetic account values; never opens an OS dialog. */
class SettingsNotificationExceptionsUiTest {
    @Test fun aFullClearPlanComposesOnlyTwelvePriorityRowsAndConfirmsTheOriginalCompletePlan() {
        val f = Fixture()
        val targets = List(32768) { index -> f.first.copy(chatId = 100000L + index, title = "大量合成目标$index") }
        val failed = targets[30000]
        val plan = NotificationExceptionsClearPlan(987654L, targets, completed = setOf(targets[20000].chatId),
            failures = mapOf(failed.chatId to SettingsError.CONFLICT))
        f.scene().use { scene ->
            scene.open(f)
            f.onUi { f.snapshot = f.snapshot.copy(clearPlan = plan) }
            scene.settledFrame()
            assertTrue(scene.hasText("只恢复本次已核实的 32768 个聊天的消息通知默认值。故事通知保持原样。"))
            assertTrue(scene.hasText("已完成 1 项，失败 1 项。"))
            assertTrue(scene.hasText("另有 32756 个目标未在此展开，仍属于本次清除范围。"))
            val composedTargetRows = scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text.startsWith("大量合成目标") }
            }
            assertTrue(composedTargetRows.size in 1..12, "A full plan must not compose tens of thousands of target rows.")
            assertTrue(scene.hasText(failed.title), "A failure near the end of the full plan is displayed before ordinary targets.")
            assertFalse(scene.hasText(targets.last().title))
            scene.clickText("重试本次未完成项", f)
            assertEquals(listOf(f.snapshot.token to plan.id), f.clears)
            assertEquals(32768, assertNotNull(f.snapshot.clearPlan).targets.size)
            assertEquals(plan, f.snapshot.clearPlan, "Bounded rendering cannot shorten or replace the frozen cleanup plan.")
        }
    }

    @Test fun savingEditorAndClearConfirmationCanCloseTheirOwnerWithoutQueuingAnotherWrite() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.openEditor(f)
            val first = f.snapshot.token
            val firstLease = f.leases.single()
            f.onUi { f.snapshot = f.snapshot.copy(saving = true) }
            scene.settledFrame()
            assertTrue(scene.hasText("已提交的更改仍会处理。"))
            scene.clickText("关闭通知例外", f)
            assertEquals(listOf(first), f.closed)
            assertFalse(firstLease())
            assertTrue(f.saves.isEmpty())
            f.onUi { f.snapshot = NotificationExceptionsSnapshot(token = first.copy(owner = ++f.owner)) }
            scene.settledFrame()
            scene.clickText("私聊通知例外", f)
            val next = f.snapshot.token
            scene.clickText("清除此分类例外", f)
            f.onUi { f.snapshot = f.snapshot.copy(saving = true) }
            scene.settledFrame()
            scene.clickText("停止后续清除并关闭", f)
            assertEquals(listOf(first, next), f.closed)
            assertFalse(f.leases.last()())
            assertTrue(f.clears.isEmpty(), "Closing the in-flight page must not retry or start another clear.")
            assertTrue(f.saves.isEmpty())
            assertFalse(scene.hasText("停止后续清除并关闭"))
        }
    }

    @Test fun anExternalMatchingCloseRestoresScopeEntriesWhileAnUnadmittedOpenRemainsPending() {
        val f = Fixture()
        f.admit = false
        f.scene().use { scene ->
            scene.open(f)
            assertTrue(scene.hasText("正在打开…"))
            assertTrue(scene.hasText("取消打开例外"))
            assertEquals(1, f.opens.size, "An initial closed snapshot must not cancel a not-yet-admitted open.")
            f.onUi {
                f.admit = true
                f.snapshot = f.snapshot.copy(token = NotificationExceptionsToken(7, ++f.owner), opened = true,
                    rows = listOf(NotificationExceptionRow(41, f.first)), totalIds = 1)
            }
            scene.settledFrame()
            val first = f.snapshot.token
            val firstLease = f.leases.single()
            f.onUi { f.snapshot = NotificationExceptionsSnapshot(token = first.copy(owner = ++f.owner)) }
            scene.settledFrame()
            assertFalse(firstLease())
            assertFalse(scene.hasText("正在打开…"))
            assertFalse(scene.hasText("取消打开例外"))
            assertTrue(f.closed.isEmpty(), "The external close already retired this owner; disposal must not close it twice.")
            scene.clickText("私聊通知例外", f)
            assertEquals(2, f.opens.size)
            assertTrue(f.snapshot.opened)
            val current = f.snapshot
            f.onUi { f.snapshot = NotificationExceptionsSnapshot(token = first) }
            scene.settledFrame()
            assertTrue(scene.hasText("取消打开例外"), "An old closed owner cannot clear the newer page intention.")
            f.onUi { f.snapshot = current }
            scene.settledFrame()
            assertTrue(scene.hasText("关闭通知例外"))
            assertEquals(2, f.opens.size)
        }
    }

    @Test fun loadingUnknownCountFailureAndAnActualEmptyListHaveDistinctReachableStates() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.open(f)
            f.onUi { f.snapshot = f.snapshot.copy(loading = true, totalIds = null, rows = emptyList(), hasMore = false) }
            scene.settledFrame()
            assertTrue(scene.hasText("正在读取通知例外…"))
            assertTrue(scene.hasText("尚未读取"))
            scene.clickText("添加通知例外", f)
            assertEquals(NotificationExceptionsView.LIST, f.snapshot.view)
            f.onUi { f.snapshot = f.snapshot.copy(loading = false, error = SettingsError.REQUEST_FAILED) }
            scene.settledFrame()
            assertTrue(scene.hasText("请求未完成，请检查连接后重试。"))
            scene.clickText("重新读取例外列表", f)
            assertEquals(listOf(f.snapshot.token), f.refreshes)
            f.onUi { f.snapshot = f.snapshot.copy(totalIds = 0, error = null) }
            scene.settledFrame()
            assertTrue(scene.hasText("此分类没有新消息通知例外。"))
            assertFalse(scene.hasText("清除此分类例外"))
        }
    }

    @Test fun scopeListUsesNativeCountAndUnresolvedRowsWithoutAuthorizingThem() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.open(f)
            assertEquals(NotificationScope.PRIVATE_CHATS, f.opens.single())
            assertTrue(scene.hasText("4"))
            assertTrue(scene.hasText("未核实的聊天"))
            scene.clickText("未核实的聊天", f)
            assertTrue(f.edits.isEmpty())
            scene.clickText("加载更多例外", f)
            assertEquals(listOf(f.snapshot.token), f.more)
            scene.clickText("重新读取例外列表", f)
            assertEquals(listOf(f.snapshot.token), f.refreshes)
            scene.clickText("合成聊天甲", f)
            assertEquals(listOf(41L), f.edits)
            assertTrue(scene.hasText("返回例外列表"))
            scene.clickText("返回例外列表", f)
            assertEquals(NotificationExceptionsView.LIST, f.snapshot.view)
        }
    }

    @Test fun failedSaveRetainsOnlyTheChosenDirtyGroupAndExplicitReloadRebasesAConflict() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.openEditor(f)
            scene.clickText("消息预览：关闭", f)
            scene.clickText("保存通知例外", f)
            val submitted = f.saves.single()
            assertEquals(setOf(NotificationExceptionField.PREVIEW), submitted.edit.dirty)
            assertFalse(submitted.edit.desired.showPreview)
            assertEquals(-882L, submitted.edit.desired.soundId)
            assertEquals(f.first.values.useDefaultMuteStories, submitted.edit.desired.useDefaultMuteStories)
            assertEquals(f.first.values.storySoundId, submitted.edit.desired.storySoundId)
            f.onUi { f.snapshot = f.snapshot.copy(editorError = SettingsError.REQUEST_FAILED) }
            scene.settledFrame()
            scene.clickText("保存通知例外", f)
            assertEquals(submitted, f.saves.last(), "Failure preserves the same rich draft and read version.")

            f.onUi { f.snapshot = f.snapshot.copy(refreshRequired = true, editorError = SettingsError.CONFLICT,
                editor = f.first.copy(values = f.first.values.copy(showPreview = false))) }
            scene.settledFrame()
            scene.clickText("保存通知例外", f)
            assertEquals(2, f.saves.size)
            scene.clickText("重新读取聊天设置", f)
            assertEquals(1, f.reloads)
            assertEquals(2, f.saves.size, "Reload is an explicit read, not a write.")
            scene.clickText("消息预览：开启", f)
            scene.clickText("保存通知例外", f)
            assertEquals(3, f.saves.size)
            assertEquals(f.snapshot.editorRevision, f.saves.last().revision)
            assertFalse(f.saves.last().edit.expected.values.showPreview)
        }
    }

    @Test fun resetRestoresOnlyFiveMessageGroupsAndLocalAcceptanceDoesNotInventReadback() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.openEditor(f)
            scene.clickText("恢复此聊天的默认通知", f)
            val edit = f.saves.single().edit
            assertEquals(setOf(NotificationExceptionField.MUTE, NotificationExceptionField.SOUND), edit.dirty)
            assertTrue(edit.desired.useDefaultMuteFor)
            assertTrue(edit.desired.useDefaultSound)
            assertEquals(-999L, edit.desired.storySoundId)
            assertFalse(edit.desired.useDefaultMuteStories)
            assertTrue(edit.desired.muteStories)
            f.onUi { f.snapshot = f.snapshot.copy(readbackPending = true, acceptedRevision = 1) }
            scene.settledFrame()
            scene.clickText("恢复此聊天的默认通知", f)
            assertEquals(1, f.saves.size)
            assertTrue(scene.hasText("更改已提交，尚未读回当前设置。"))
            scene.clickText("重新读取聊天设置", f)
            assertEquals(1, f.reloads)
        }
    }

    @Test fun selectorChoosesRealChatIdAndServerSearchRequiresItsOwnAction() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.open(f)
            scene.clickText("添加通知例外", f)
            assertEquals(NotificationExceptionsView.SELECTOR, f.snapshot.view)
            assertTrue(f.searches.isEmpty())
            scene.clickText("搜索服务器上的聊天", f)
            assertTrue(f.searches.isEmpty(), "An empty query does not trigger a server search.")
            val field = scene.semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.single()
            scene.click(field)
            scene.keyStroke(java.awt.event.KeyEvent.VK_X, 'x')
            scene.settledFrame()
            assertEquals(listOf("x" to false), f.searches)
            scene.clickText("搜索服务器上的聊天", f)
            assertEquals(listOf("x" to false, "x" to true), f.searches)
            scene.clickText("合成候选乙", f)
            assertEquals(listOf(-55002L), f.edits)
            assertEquals(-55002L, f.snapshot.editor?.chatId)
        }
    }

    @Test fun clearRequiresThePreparedPlanAndPartialRetryKeepsTheSameIdentity() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.open(f)
            scene.clickText("清除此分类例外", f)
            assertEquals(1, f.prepares)
            assertTrue(f.clears.isEmpty(), "Preparation does not mutate any chat.")
            assertTrue(scene.hasText("另有 2 项未核实，不会清除。"))
            scene.clickText("取消清除", f)
            assertNull(f.snapshot.clearPlan)
            assertTrue(f.clears.isEmpty())
            scene.clickText("清除此分类例外", f)
            val frozen = assertNotNull(f.snapshot.clearPlan)
            scene.clickText("确认清除例外", f)
            assertEquals(listOf(f.snapshot.token to frozen.id), f.clears)
            f.onUi { f.snapshot = f.snapshot.copy(clearPlan = frozen.copy(completed = setOf(41),
                failures = mapOf(42L to SettingsError.CONFLICT)), rows = f.snapshot.rows + NotificationExceptionRow(88,
                    f.first.copy(chatId = 88, title = "确认后新加的合成聊天"))) }
            scene.settledFrame()
            scene.clickText("重试本次未完成项", f)
            assertEquals(listOf(f.snapshot.token to frozen.id, f.snapshot.token to frozen.id), f.clears)
            assertEquals(listOf(41L, 42L), f.snapshot.clearPlan?.targets?.map { it.chatId })
        }
    }

    @Test fun retiringAPageOrAccountRevisionInvalidatesTheOldLeaseAndCannotAdoptItsToken() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.openEditor(f)
            scene.clickText("消息预览：关闭", f)
            val first = f.snapshot.token
            val oldLease = f.leases.single()
            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(oldLease())
            assertEquals(listOf(first), f.closed)
            scene.clickText("保存通知例外", f)
            assertTrue(f.saves.isEmpty())

            f.onUi { f.active = true; f.admit = false }
            scene.settledFrame()
            scene.clickText("保存通知例外", f)
            assertTrue(f.saves.isEmpty(), "A replacement lease cannot claim the old native token.")
            val replacement = f.leases.last()
            f.onUi { f.accountToken = f.accountToken.copy(revision = f.accountToken.revision + 1) }
            scene.settledFrame()
            assertFalse(replacement())
            assertEquals(3, f.leases.size)
            assertEquals(listOf(first), f.closed, "Unadmitted leases must not close somebody else's owner.")
        }
    }

    @Test fun actualListEditorAndConfirmationActionsRemainReachableInWideAndLowLightAndDarkFrames() {
        for ((width, height) in listOf(620 to 900, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val f = Fixture(width, height, mode)
            f.scene().use { scene ->
                scene.open(f)
                scene.reveal("清除此分类例外", f).also { assertTrue(it.bottom <= height - 3f) }
                scene.settledFrame().save("list-${mode.name}-${width}x$height")
                scene.clickText("合成聊天甲", f)
                scene.clickText("消息预览：关闭", f)
                val save = scene.reveal("保存通知例外", f)
                assertTrue(save.left >= 0 && save.right <= width && save.top >= 3 && save.bottom <= height - 3)
                scene.settledFrame().save("editor-${mode.name}-${width}x$height")
                scene.click(save)
                assertEquals(setOf(NotificationExceptionField.PREVIEW), f.saves.single().edit.dirty)
                scene.clickText("返回例外列表", f)
                scene.clickText("清除此分类例外", f)
                val confirm = scene.reveal("确认清除例外", f)
                assertTrue(confirm.top >= 3 && confirm.bottom <= height - 3)
                scene.settledFrame().save("confirm-${mode.name}-${width}x$height")
                scene.click(confirm)
                assertEquals(1, f.clears.size)
            }
        }
    }

    @Test fun dirtyComparisonIgnoresInheritedPayloadAndRecognizesSignedSoundMinusOneAsInheritance() {
        val base = ChatNotificationValues()
        assertTrue(notificationExceptionDirtyFields(base, base.copy(muteFor = 50, soundId = -982,
            showPreview = false, disableMentionNotifications = true, storySoundId = 999)).isEmpty())
        assertTrue(notificationExceptionDirtyFields(base, base.copy(useDefaultSound = false, soundId = -1)).isEmpty())
        assertEquals(setOf(NotificationExceptionField.SOUND), notificationExceptionDirtyFields(base,
            base.copy(useDefaultSound = false, soundId = -55)))
    }

    private data class Save(val token: NotificationExceptionsToken, val revision: Long, val edit: NotificationExceptionEdit)
    private class Fixture(val width: Int = 520, val height: Int = 740, val mode: ColorSchemeMode = ColorSchemeMode.Light) {
        var active by mutableStateOf(true)
        var accountToken by mutableStateOf(SettingsToken(7, 1))
        var snapshot by mutableStateOf(NotificationExceptionsSnapshot(token = NotificationExceptionsToken(7)))
        var admit = true
        var owner = 0L
        val first = NotificationExceptionPeer(41, NotificationScope.PRIVATE_CHATS, "合成聊天甲",
            ChatNotificationValues(useDefaultMuteFor = false, muteFor = 0, useDefaultSound = false, soundId = -882,
                useDefaultMuteStories = false, muteStories = true, useDefaultStorySound = false, storySoundId = -999), 1000)
        val second = first.copy(chatId = 42, title = "合成聊天丙")
        val candidate = first.copy(chatId = -55002, title = "合成候选乙")
        val opens = mutableListOf<NotificationScope>()
        val leases = mutableListOf<() -> Boolean>()
        val closed = mutableListOf<NotificationExceptionsToken>()
        val edits = mutableListOf<Long>()
        val more = mutableListOf<NotificationExceptionsToken>()
        val refreshes = mutableListOf<NotificationExceptionsToken>()
        val searches = mutableListOf<Pair<String, Boolean>>()
        val saves = mutableListOf<Save>()
        val clears = mutableListOf<Pair<NotificationExceptionsToken, Long>>()
        var reloads = 0
        var prepares = 0
        val actions = NotificationExceptionsActions(
            open = { token, scope, lease ->
                opens += scope; leases += lease
                if (admit && lease()) snapshot = snapshot.copy(token = NotificationExceptionsToken(token.generation, ++owner, scope),
                    opened = true, totalIds = 4, rows = listOf(NotificationExceptionRow(41, first),
                        NotificationExceptionRow(42, second), NotificationExceptionRow(43, error = SettingsError.REQUEST_FAILED)), hasMore = true)
            },
            close = { closed += it },
            more = { more += it }, refresh = { refreshes += it },
            select = { snapshot = snapshot.copy(view = NotificationExceptionsView.SELECTOR,
                candidates = listOf(NotificationExceptionRow(candidate.chatId, candidate))) },
            search = { _, query, server -> searches += query to server },
            edit = { _, id ->
                edits += id
                snapshot = snapshot.copy(view = NotificationExceptionsView.EDITOR,
                    editor = if (id == candidate.chatId) candidate else first, editorRevision = snapshot.editorRevision + 1)
            },
            back = { snapshot = snapshot.copy(view = NotificationExceptionsView.LIST) },
            reloadEditor = {
                reloads++
                snapshot = snapshot.copy(editorRevision = snapshot.editorRevision + 1, editorError = null,
                    refreshRequired = false, readbackPending = false)
            },
            save = { token, revision, edit -> saves += Save(token, revision, edit) },
            prepareClear = {
                prepares++
                snapshot = snapshot.copy(clearPlan = NotificationExceptionsClearPlan(prepares.toLong(), listOf(first, second),
                    unresolved = listOf(NotificationExceptionRow(43, error = SettingsError.REQUEST_FAILED),
                        NotificationExceptionRow(44, error = SettingsError.FORBIDDEN))))
            },
            cancelClear = { snapshot = snapshot.copy(clearPlan = null) },
            clear = { token, id -> clears += token to id },
        )
        fun onUi(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides OfflineConfirmationHost(height.dp)) {
                MiuixTheme(controller = theme) {
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SettingsNotificationExceptionControls(accountToken, snapshot, actions, active)
                    }
                }
            }
        }
    }

    private class OfflineConfirmationHost(private val viewport: Dp) : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().heightIn(max = (viewport - 32.dp).coerceAtLeast(96.dp))
                .chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp)) {
                title?.let { Text(it) }
                summary?.let { Text(it) }
                content()
            }
        }
    }
    private fun ComposePixelScene.open(f: Fixture) { settledFrame(); clickText("私聊通知例外", f); settledFrame() }
    private fun ComposePixelScene.openEditor(f: Fixture) { open(f); clickText("合成聊天甲", f) }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.clickText(label: String, f: Fixture) = click(reveal(label, f))
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        fun visible() = texts(label).firstOrNull { it.width > 0 && it.height >= 10 && it.top >= 3 && it.bottom <= f.height - 3 }
        var scanDown = false
        repeat(700) {
            visible()?.let { return it }
            val target = texts(label).firstOrNull { it.width > 0 && it.height > 0 }
            val scroll = semanticsBounds { it.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                .lastOrNull { it.width > 0 && it.height > 24 && it.top < f.height && it.bottom > 0 }
            var range: Pair<Float, Float>? = null
            semanticsBounds {
                it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { axis -> range = axis.value() to axis.maxValue() }
                false
            }
            if (range?.first?.let { it <= .5f } == true) scanDown = true
            val delta = when {
                target != null -> if (target.top < 3f) -8f else 8f
                !scanDown -> -8f
                else -> 8f
            }
            scrollAt(scroll?.center?.x ?: f.width * .5f,
                scroll?.center?.y?.coerceIn(5f, f.height - 5f) ?: f.height * .5f, deltaY = delta)
            settledFrame(4, 32)
        }
        return assertNotNull(visible(), "Actual scrolling must reveal '$label'; bounds=${texts(label)}; viewport=${f.width}x${f.height}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val output = File("build/settings-notification-exceptions-validation/frames/$name.png")
        output.parentFile.mkdirs(); ImageIO.write(image, "png", output)
    }
}

