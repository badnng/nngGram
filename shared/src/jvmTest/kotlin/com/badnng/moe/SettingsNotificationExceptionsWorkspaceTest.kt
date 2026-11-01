package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.settings.*
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Full Workspace integration through its actual category, header, scrolling and child controls. */
class SettingsNotificationExceptionsWorkspaceTest {
    @Test fun savingNarrowEditorHeaderClosesItsPageAndEscapeCannotReuseTheRetiredToken() {
        val f = Fixture(width = 390)
        f.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.reveal("私聊通知例外", f))
            scene.click(scene.reveal("合成例外会话", f))
            val token = f.exceptions.token
            val lease = f.leases.single()
            SwingUtilities.invokeAndWait {
                f.runtime = f.runtime.copy(settings = f.runtime.settings.copy(notificationExceptions = f.exceptions.copy(saving = true)))
            }
            scene.settledFrame()
            scene.click(scene.description("返回通知设置"))
            assertEquals(listOf(token), f.closed)
            assertTrue(f.backs.isEmpty(), "A pending edit leaves through close, rather than becoming an editable list.")
            assertFalse(lease())
            assertFalse(f.exceptions.opened)
            assertTrue(f.state.detailOpen)
            assertTrue(scene.texts("取消打开例外").isEmpty())
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertFalse(f.state.detailOpen)
            assertEquals(listOf(token), f.closed)
            assertTrue(f.backs.isEmpty())
            assertTrue(f.staleCalls.isEmpty())
        }
    }

    @Test fun wideWorkspaceKeepsCategoriesAndReplacesOnlyTheRightNotificationPane() {
        val f = Fixture(width = 1040)
        f.scene().use { scene ->
            scene.settledFrame()
            val originalTab = scene.tab(SettingsDestination.NOTIFICATIONS)
            val entry = scene.reveal("私聊通知例外", f)
            assertTrue(originalTab.right < entry.left)
            assertTrue(scene.texts("显示通知").isNotEmpty())
            scene.click(entry)
            assertTrue(f.exceptions.opened)
            assertEquals(SettingsDestination.NOTIFICATIONS, f.state.destination)
            assertEquals(originalTab, scene.tab(SettingsDestination.NOTIFICATIONS), "Opening a child must not move the category column.")
            assertTrue(scene.texts("显示通知").isEmpty(), "Default notification groups leave the right pane while the child is open.")
            val peer = scene.reveal("合成例外会话", f)
            assertTrue(peer.left > originalTab.right)
            scene.click(peer)
            assertEquals(NotificationExceptionsView.EDITOR, f.exceptions.view)
            assertNotNull(scene.tab(SettingsDestination.PROFILE))
            assertEquals(originalTab, scene.tab(SettingsDestination.NOTIFICATIONS))
            scene.click(scene.text("刷新"))
            assertEquals(listOf(f.exceptions.token), f.reloads, "The fixed header refresh belongs to the child editor.")
            assertTrue(f.loads.isEmpty())
            scene.click(scene.tab(SettingsDestination.CHAT))
            assertEquals(SettingsDestination.CHAT, f.state.destination)
            assertFalse(f.leases.single()())
            assertEquals(1, f.closed.size)
            assertTrue(scene.texts("合成例外会话").isEmpty())
        }
    }

    @Test fun narrowHeaderBackReturnsEditorThenListThenDefaultsThenCategoriesAndRetiresItsOldOwner() {
        val f = Fixture(width = 390)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.tabs().isEmpty())
            scene.click(scene.reveal("私聊通知例外", f))
            val firstToken = f.exceptions.token
            val firstLease = f.leases.single()
            scene.click(scene.reveal("合成例外会话", f))
            assertEquals(NotificationExceptionsView.EDITOR, f.exceptions.view)
            scene.click(scene.description("返回例外列表"))
            assertEquals(NotificationExceptionsView.LIST, f.exceptions.view)
            assertEquals(listOf(firstToken), f.backs)
            assertTrue(f.state.detailOpen)
            scene.click(scene.description("返回通知设置"))
            assertFalse(f.exceptions.opened)
            assertEquals(listOf(firstToken), f.closed)
            assertFalse(firstLease())
            assertTrue(f.state.detailOpen)
            assertTrue(scene.texts("显示通知").isNotEmpty())
            assertTrue(scene.texts("取消打开例外").isEmpty(), "An external header close must retire the UI's opening intention.")
            scene.click(scene.description("返回设置分类"))
            assertFalse(f.state.detailOpen)
            assertNotNull(scene.tab(SettingsDestination.NOTIFICATIONS))
            assertTrue(scene.texts("私聊通知例外").isEmpty())

            scene.click(scene.tab(SettingsDestination.NOTIFICATIONS))
            scene.click(scene.reveal("私聊通知例外", f))
            val nextToken = f.exceptions.token
            assertNotEquals(firstToken, nextToken)
            assertEquals(2, f.leases.size)
            assertFalse(firstLease())
            scene.click(scene.reveal("合成例外会话", f))
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertEquals(NotificationExceptionsView.LIST, f.exceptions.view)
            assertEquals(listOf(firstToken, nextToken), f.backs, "Escape must use the newly owned child token.")
            scene.click(scene.description("返回通知设置"))
            assertEquals(listOf(firstToken, nextToken), f.closed)
            assertFalse(f.leases.last()())
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertFalse(f.state.detailOpen)
            assertEquals(listOf(firstToken, nextToken), f.backs)
            assertEquals(listOf(firstToken, nextToken), f.closed, "Default/category navigation cannot dispatch the retired exception token.")
            assertTrue(f.staleCalls.isEmpty())
        }
    }

    private class Fixture(val width: Int, val height: Int = 844) {
        var runtime by mutableStateOf(RuntimeSnapshot(configPath = "synthetic/settings.properties", generation = 17,
            settings = SettingsSnapshot(token = SettingsToken(17), enabled = true,
                loaded = SettingsSection.entries.toSet(),
                notifications = NotificationScope.entries.associateWith { NotificationPreferences() }),
            chats = ChatSnapshot(enabled = true, token = ChatToken(17, 0, null),
                account = AccountProfile(700001, "Synthetic Notification Account"), listEnd = true)))
        val state = SettingsWorkspaceState().apply { destination = SettingsDestination.NOTIFICATIONS; detailOpen = true }
        val peer = NotificationExceptionPeer(41001, NotificationScope.PRIVATE_CHATS, "合成例外会话",
            ChatNotificationValues(useDefaultShowPreview = false, showPreview = false), 1000)
        var owner = 0L
        val exceptions get() = runtime.settings.notificationExceptions
        val leases = mutableListOf<() -> Boolean>()
        val backs = mutableListOf<NotificationExceptionsToken>()
        val closed = mutableListOf<NotificationExceptionsToken>()
        val reloads = mutableListOf<NotificationExceptionsToken>()
        val staleCalls = mutableListOf<NotificationExceptionsToken>()
        val loads = mutableListOf<SettingsSection>()
        private fun update(value: NotificationExceptionsSnapshot) { runtime = runtime.copy(settings = runtime.settings.copy(notificationExceptions = value)) }
        private fun admit(token: NotificationExceptionsToken): Boolean {
            val matches = exceptions.opened && token == exceptions.token
            if (!matches) staleCalls += token
            return matches
        }
        val childActions = NotificationExceptionsActions(
            open = { token, scope, lease ->
                leases += lease
                if (lease()) update(NotificationExceptionsSnapshot(token = NotificationExceptionsToken(token.generation, ++owner, scope),
                    opened = true, totalIds = 1, rows = listOf(NotificationExceptionRow(peer.chatId, peer))))
            },
            close = { token -> if (admit(token)) {
                closed += token
                update(NotificationExceptionsSnapshot(token = token.copy(owner = ++owner)))
            } },
            edit = { token, id -> if (admit(token) && id == peer.chatId)
                update(exceptions.copy(view = NotificationExceptionsView.EDITOR, editor = peer, editorRevision = exceptions.editorRevision + 1)) },
            back = { token -> if (admit(token)) {
                backs += token
                update(exceptions.copy(view = NotificationExceptionsView.LIST, editor = null))
            } },
            reloadEditor = { token -> if (admit(token)) reloads += token },
        )
        val actions = SettingsActions(notificationExceptions = childActions, load = { _, section -> loads += section })
        fun scene() = ComposePixelScene(width, height) { Content() }
        @Composable private fun Content() {
            val preferences = remember { SettingsPreferences(theme = ColorSchemeMode.Light) }
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSettingsPreferences provides preferences,
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences, onPreferences = {},
                            folderPreferences = ChatFolderPreferences(), onFolderPreferences = {}, onClose = {}, onOpenConfig = {}, state = state)
                    }
                }
            }
        }
    }
    private fun ComposePixelScene.tabs() = semanticsBounds { it.getOrNull(SemanticsProperties.Role) == Role.Tab }
    private fun ComposePixelScene.tab(destination: SettingsDestination) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == destination.title }
    }.singleOrNull(), "Expected the actual category ${destination.title}")
    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.filter { it.width > 0 && it.height > 0 }
    private fun ComposePixelScene.text(label: String) = assertNotNull(texts(label).singleOrNull(), label)
    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), label)
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y); pressAt(bounds.center.x, bounds.center.y); releaseAt(bounds.center.x, bounds.center.y); settledFrame()
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        repeat(240) {
            texts(label).firstOrNull { it.height >= 12 && it.top >= 68 && it.bottom <= f.height - 3 }?.let { return it }
            val target = texts(label).firstOrNull()
            scrollAt(f.width - 40f, f.height * .6f, deltaY = if (target != null && target.top < 68) -8f else 8f)
            settledFrame(4, 32)
        }
        return assertNotNull(texts(label).firstOrNull { it.height >= 12 && it.top >= 68 && it.bottom <= f.height - 3 }, label)
    }
}
