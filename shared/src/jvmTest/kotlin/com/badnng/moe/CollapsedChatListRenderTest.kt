package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class CollapsedChatListRenderTest {
    @Test
    fun aMinimumWidthTopLayoutRetainsAccountSearchAndScrollableFolders() {
        val snapshot = ChatSnapshot(enabled = true, listEnd = true, folders = listOf(ChatFolderInfo(1, "工作")))
        val preferences = ChatFolderPreferences(placement = FolderTabPlacement.TOP)
        var accountClicks = 0
        var searchClicks = 0
        var selectedList: ChatListRef? = null
        ComposePixelScene(120, 400) {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            CompositionLocalProvider(LocalDesktopChatLayout provides DesktopChatLayout(compact = true, showRail = false)) {
                MiuixTheme(controller = controller) {
                    ChatListPane(snapshot, onSelect = {}, onRetryLoad = {}, onSelectList = { selectedList = it },
                        onManageFolders = {}, features = ChatFeatureActions(), collapsed = true,
                        modifier = Modifier.width(72.dp).fillMaxHeight(), onSearch = { searchClicks++ },
                        leadingContent = {
                            AccountAvatarButton(snapshot, ChatFeatureActions(), expanded = false, onBounds = {},
                                onClick = { accountClicks++ })
                        },
                        folderTabs = { ChatFolderTopTabs(snapshot, { selectedList = it }, preferences) })
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val account = assertNotNull(scene.descriptionBounds("打开账号菜单").firstOrNull())
            scene.click(account)
            scene.settledFrame()
            assertEquals(1, accountClicks, "The top-layout account/settings entry must survive collapse.")
            val search = assertNotNull(scene.descriptionBounds("搜索聊天").firstOrNull())
            scene.click(search)
            scene.settledFrame()
            assertEquals(1, searchClicks)

            val firstFolder = assertNotNull(scene.descriptionBounds("全部", Role.Tab).firstOrNull())
            scene.scrollAt(firstFolder.center.x, firstFolder.center.y, deltaY = 2f)
            scene.settledFrame()
            val work = assertNotNull(scene.descriptionBounds("工作", Role.Tab).firstOrNull())
            assertTrue(work.width >= 40f, "A 72dp list must allow the last top folder to scroll fully into view.")
            scene.click(work)
            scene.settledFrame()
            assertEquals(ChatListRef(1), selectedList)
        }
    }

    @Test
    fun cappedUnreadBadgeFitsAndPaintsCompletelyInsideAMinimumWidthChatRow() {
        val chat = ChatItem(10, "Synthetic chat", order = 1, unreadCount = 1_000)
        val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, chat.id),
            items = listOf(chat), selected = chat, listEnd = true)
        var primary = 0
        var selectedChat: Long? = null
        ComposePixelScene(120, 400) {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            CompositionLocalProvider(LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = controller) {
                    val color = MiuixTheme.colorScheme.primary.toArgb()
                    SideEffect { primary = color }
                    ChatListPane(snapshot, onSelect = { selectedChat = it }, onRetryLoad = {}, onSelectList = {},
                        onManageFolders = {}, features = ChatFeatureActions(), collapsed = true,
                        modifier = Modifier.width(72.dp).fillMaxHeight())
                }
            }
        }.use { scene ->
            val frame = scene.settledFrame()
            val row = assertNotNull(scene.descriptionBounds(chat.title).firstOrNull())
            val badge = assertNotNull(scene.descriptionBounds("1000 条未读").firstOrNull())
            assertTrue(badge.left >= row.left && badge.right <= row.right && badge.bottom <= row.bottom,
                "The 999+ badge must fit inside the actual 55dp row at the 72dp pane minimum: $badge vs $row.")
            val fillSamples = listOf(
                (badge.left + 2).toInt() to badge.center.y.toInt(),
                (badge.right - 3).toInt() to badge.center.y.toInt(),
                badge.center.x.toInt() to (badge.top + 2).toInt(),
                badge.center.x.toInt() to (badge.bottom - 3).toInt(),
            )
            fillSamples.forEach { (x, y) ->
                assertEquals(primary, frame[x, y], "The selection surface must not clip the badge at ($x, $y).")
            }
            scene.click(badge)
            scene.settledFrame()
            assertEquals(chat.id, selectedChat, "The badge must not block selection of its chat row.")
        }
    }

    private fun ComposePixelScene.descriptionBounds(description: String, role: Role? = null): List<Rect> = semanticsBounds { config ->
        description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
            (role == null || config.getOrNull(SemanticsProperties.Role) == role)
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
