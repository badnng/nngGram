package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatAdministrator
import com.badnng.moe.chat.ChatDetails
import com.badnng.moe.chat.ChatDetailsState
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMediaIndexState
import com.badnng.moe.chat.ChatSearchQuery
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.status.ConnectionPhase
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ConversationAdminsPopupRenderTest {
    @Test
    fun aLongAdministratorListFitsBelowTheToolbarInAShortWindowAndRemainsScrollable() {
        val chat = ChatItem(10, "Short window group", basicGroupId = 20)
        val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id),
            details = ChatDetailsState(ChatDetails(chat.id, administrators = (1..20).map { index ->
                ChatAdministrator(9000L + index, "Short native $index", title = "Native role $index", owner = index == 1)
            })))
        val fixture = AdminsFixture(snapshot)
        ComposePixelScene(900, 360) { fixture.Content() }.use { scene ->
            scene.settledFrame()
            val toolbar = fixture.toolbarBounds
            scene.click(assertNotNull(scene.buttonDescriptionBounds("管理员").firstOrNull()))
            val opened = scene.settledFrame()
            val card = assertNotNull(scene.semanticsBounds {
                it.getOrNull(SemanticsProperties.PaneTitle) == "管理员"
            }.singleOrNull(), "The actual administrator popup must expose its measured card.")
            assertTrue(card.top >= toolbar.bottom,
                "A long native list must not push the short-window card over the complete toolbar: $card vs $toolbar")
            assertTrue(card.bottom <= 328f,
                "The measured card must retain the real 32px lower shadow space inside the 360px window: $card")
            val firstIdentity = assertNotNull(scene.textBounds("Short native 1").firstOrNull {
                it.width > 0f && it.height > 0f && it.top >= card.top && it.bottom <= card.bottom
            }, "The compact viewport must still show an actual native administrator identity.")
            assertTrue(firstIdentity.height > 10f)
            val laterIdentity = "Short native 12"
            assertTrue(scene.textBounds(laterIdentity).none { it.height > 0f && it.top >= card.top && it.bottom <= card.bottom },
                "The later identity must start outside the visible card, so scrolling is required.")
            var scrolled = opened
            var revealed = false
            for (step in 0 until 36) {
                scene.scrollAt(card.center.x, card.center.y, deltaY = 1f)
                scrolled = scene.settledFrame(frameCount = 4)
                revealed = scene.textBounds(laterIdentity).any {
                    it.width > 0f && it.height > 0f && it.top >= card.top && it.bottom <= card.bottom
                }
                if (revealed) break
            }
            assertTrue(revealed, "Real wheel input inside the compact card must reveal a later native administrator.")
            assertTrue(changedPixels(opened, scrolled, card) > 100,
                "The card must actually repaint later identities as its native list scrolls.")
            assertEquals(listOf(snapshot.token), fixture.detailsRequests,
                "Browsing the already loaded administrator list must not request unrelated native data.")
            scene.keyStroke(java.awt.event.KeyEvent.VK_ESCAPE)
            scene.settledFrame()
            assertFalse(fixture.show, "Escape must dismiss the actual anchored administrator card.")
            assertEquals(1, fixture.dismissalFinishes)
            assertTrue(scene.semanticsBounds { it.getOrNull(SemanticsProperties.PaneTitle) == "管理员" }.isEmpty())
        }
    }

    @Test
    fun workspaceInformationEntryAnchorsTheSameAdministratorCardAndLoadsTheCurrentNativeDetails() {
        val chat = ChatItem(10, "Synthetic workspace group", basicGroupId = 20)
        val snapshot = ChatSnapshot(enabled = true, selected = chat, token = ChatToken(1, 1, chat.id),
            listEnd = true, historyEnd = true,
            mediaIndex = ChatMediaIndexState(chatId = chat.id, loaded = true, totalCount = 0),
            details = ChatDetailsState(ChatDetails(chat.id, administrators = listOf(
                ChatAdministrator(9103, "Workspace native owner", owner = true),
            ))))
        val detailsRequests = mutableListOf<ChatToken>()
        var searches = 0
        var unexpectedReads = 0
        val features = ChatFeatureActions(loadChatDetails = { detailsRequests += it },
            search = { _, _, _ -> searches++ },
            visibleMessages = { _, ids, _ -> if (ids.isNotEmpty()) unexpectedReads++ })
        ComposePixelScene(1300, 800) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            MiuixTheme(controller = controller) {
                ChatWorkspace(snapshot, ConnectionPhase.READY, drafts,
                    onSettings = {}, onSelectChat = { _, _ -> }, onLoadChats = {}, onLoadOlder = {},
                    onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> }, onMarkRead = {},
                    windowFocused = true, features = features, darkTheme = false, onDarkTheme = {},
                    persistUiPreferences = false)
            }
        }.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.buttonDescriptionBounds("展开群信息").firstOrNull()))
            scene.settledFrame()
            assertEquals(listOf(snapshot.token), detailsRequests)
            val entry = assertNotNull(scene.buttonTextBounds("管理员").firstOrNull())
            scene.click(entry)
            scene.settledFrame()
            assertEquals(listOf(snapshot.token, snapshot.token), detailsRequests,
                "The information pane's real administrator entry must load the current native selection once.")
            assertTrue(scene.textBounds("Workspace native owner").isNotEmpty())
            val close = assertNotNull(scene.buttonTextBounds("关闭").firstOrNull())
            assertTrue(close.top > entry.bottom,
                "The information entry must anchor its card below its actual button, rather than center a dialog.")
            scene.click(close)
            scene.settledFrame()
            assertTrue(scene.textBounds("Workspace native owner").isEmpty())
            assertTrue(scene.textBounds("简介").isNotEmpty(), "Closing the card must preserve the information pane.")
            assertEquals(0, searches, "Administrator information must not start a search.")
            assertEquals(0, unexpectedReads, "Administrator and information entries must not mark timeline messages read.")
        }
    }

    @Test
    fun realAdministratorButtonOpensBelowTheToolbarAndRetainsNativeIdentitiesThroughExit() {
        val chat = ChatItem(10, "Synthetic group", basicGroupId = 20)
        val token = ChatToken(1, 1, chat.id)
        val fixture = AdminsFixture(ChatSnapshot(enabled = true, selected = chat, token = token,
            details = ChatDetailsState(ChatDetails(chat.id, administrators = listOf(
                ChatAdministrator(9201, "Native owner", title = "Maintainer", owner = true),
                ChatAdministrator(9307, "Native administrator", title = "Moderator"),
            )))))
        ComposePixelScene(900, 600) { fixture.Content() }.use { scene ->
            val resting = scene.settledFrame()
            val toolbar = fixture.toolbarBounds
            val chatTitle = assertNotNull(scene.textBounds(chat.title).firstOrNull())
            val trigger = assertNotNull(scene.buttonDescriptionBounds("管理员").firstOrNull())
            assertTrue(scene.buttonTextBounds("关闭").isEmpty())

            scene.click(trigger)
            val opened = scene.settledFrame()
            assertTrue(fixture.show)
            assertEquals(listOf(token), fixture.detailsRequests,
                "Opening the actual administrator button must request this selection's native details once.")
            val title = assertNotNull(scene.textBounds("管理员").firstOrNull())
            val owner = assertNotNull(scene.textBounds("Native owner").firstOrNull())
            assertTrue(scene.textBounds("Native administrator").isNotEmpty())
            assertTrue(scene.textBounds("创建者 · Maintainer").isNotEmpty())
            assertTrue(scene.textBounds("管理员 · Moderator").isNotEmpty())
            val close = assertNotNull(scene.buttonTextBounds("关闭").firstOrNull())
            val popup = Rect(close.left, title.top, close.right, close.bottom)
            assertTrue(title.top >= toolbar.bottom,
                "The administrator card must remain below the complete toolbar: $title vs $toolbar.")
            assertTrue(popup.left > toolbar.center.x,
                "The card must open from the real administrator button on the toolbar's right side.")
            assertTrue(popup.right <= toolbar.right && owner.left >= popup.left,
                "The administrator card must remain inside the window beside its anchor.")
            assertEquals(0, changedPixels(resting, opened, chatTitle),
                "Opening the card must preserve the toolbar title without window dimming.")
            assertTrue(changedPixels(resting, opened, popup) > 200,
                "The anchored popup must actually paint its administrator card.")
            assertTrue(fixture.searchRequests.isEmpty(), "Displaying administrator identities must not launch a search.")

            scene.click(close)
            val exiting = scene.settledFrame(frameCount = 1)
            assertFalse(fixture.show)
            assertEquals(0, fixture.dismissalFinishes)
            assertTrue(changedPixels(resting, exiting, owner) > 10,
                "Closing must retain the administrator row's drawing while its interactions stop.")
            assertTrue(changedPixels(resting, exiting, popup) > 20,
                "The first exit frame must retain the popup's drawing.")

            scene.moveTo(10f, 570f)
            val closed = scene.settledFrame()
            assertTrue(scene.textBounds("Native owner").isEmpty())
            assertTrue(scene.buttonTextBounds("关闭").isEmpty())
            assertEquals(1, fixture.dismissalFinishes, "The finished callback must run once after exit.")
            assertEquals(0, changedPixels(resting, closed, popup), "The completed exit must leave no painted residue.")
            assertTrue(fixture.searchRequests.isEmpty(), "Closing administrator information must not launch a search.")
        }
    }

    @Test
    fun administratorLoadingErrorsRetryAndNativeListsStayIsolatedFromOtherSelections() {
        val chat = ChatItem(41, "Current group", supergroupId = 81)
        val token = ChatToken(2, 8, chat.id)
        val fixture = AdminsFixture(ChatSnapshot(enabled = true, selected = chat, token = token,
            details = ChatDetailsState(loading = true)))
        ComposePixelScene(900, 600) { fixture.Content() }.use { scene ->
            scene.settledFrame()
            scene.click(assertNotNull(scene.buttonDescriptionBounds("管理员").firstOrNull()))
            scene.settledFrame()
            assertTrue(scene.textBounds("正在加载管理员……").isNotEmpty())
            assertEquals(listOf(token), fixture.detailsRequests)

            fixture.snapshot = fixture.snapshot.copy(details = ChatDetailsState(error = ChatError.REQUEST_FAILED))
            scene.settledFrame()
            assertTrue(scene.textBounds("正在加载管理员……").isEmpty())
            assertTrue(scene.textBounds("请求未完成，请检查连接后重试。").isNotEmpty())
            scene.click(assertNotNull(scene.buttonTextBounds("重试").firstOrNull()))
            scene.settledFrame()
            assertEquals(listOf(token, token), fixture.detailsRequests,
                "Retry must use the current native selection token, without inventing administrator data.")

            fixture.snapshot = fixture.snapshot.copy(details = ChatDetailsState(ChatDetails(chat.id)))
            scene.settledFrame()
            assertTrue(scene.textBounds("Telegram 未提供可显示的管理员").isNotEmpty())
            assertTrue(scene.buttonTextBounds("重试").isEmpty())

            val administrator = ChatAdministrator(7135, "Fresh native identity", title = "Native title")
            fixture.snapshot = fixture.snapshot.copy(details = ChatDetailsState(ChatDetails(chat.id,
                administrators = listOf(administrator))))
            scene.settledFrame()
            assertTrue(scene.textBounds(administrator.name).isNotEmpty())
            assertTrue(scene.textBounds("管理员 · Native title").isNotEmpty())
            assertTrue(scene.textBounds("Telegram 未提供可显示的管理员").isEmpty())

            fixture.snapshot = fixture.snapshot.copy(details = ChatDetailsState(ChatDetails(999,
                administrators = listOf(ChatAdministrator(7777, "Wrong details identity", owner = true)))))
            scene.settledFrame()
            assertTrue(scene.textBounds(administrator.name).isEmpty())
            assertTrue(scene.textBounds("Wrong details identity").isEmpty())
            assertTrue(scene.textBounds("暂未取得管理员信息").isNotEmpty(),
                "Details belonging to another native chat must not appear in this popup.")

            val nextChat = ChatItem(42, "Next group", basicGroupId = 82)
            val nextToken = ChatToken(2, 9, nextChat.id)
            fixture.snapshot = fixture.snapshot.copy(selected = nextChat, token = nextToken,
                details = ChatDetailsState(ChatDetails(chat.id, administrators = listOf(administrator))))
            scene.settledFrame()
            assertTrue(scene.textBounds(administrator.name).isEmpty(),
                "A selection change must not retain the previous chat's administrator identities.")
            assertFalse(fixture.show, "Changing the conversation must dismiss its former administrator popup.")
            scene.click(assertNotNull(scene.buttonDescriptionBounds("管理员").firstOrNull()))
            scene.settledFrame()
            assertTrue(scene.textBounds("暂未取得管理员信息").isNotEmpty())

            fixture.snapshot = fixture.snapshot.copy(details = ChatDetailsState(error = ChatError.FORBIDDEN))
            scene.settledFrame()
            assertTrue(scene.textBounds("当前没有执行此操作的权限。").isNotEmpty())
            scene.click(assertNotNull(scene.buttonTextBounds("重试").firstOrNull()))
            scene.settledFrame()
            assertEquals(nextToken, fixture.detailsRequests.last(), "A later retry must follow the new selection.")
            assertTrue(fixture.searchRequests.isEmpty(), "Native detail changes must never start an author search.")
        }
    }

    private class AdminsFixture(initialSnapshot: ChatSnapshot) {
        var snapshot by mutableStateOf(initialSnapshot)
        var show by mutableStateOf(false)
        var toolbarBounds = Rect.Zero
        var dismissalFinishes = 0
        val detailsRequests = mutableListOf<ChatToken>()
        val searchRequests = mutableListOf<ChatSearchQuery>()
        private val features = ChatFeatureActions(
            loadChatDetails = { detailsRequests += it },
            search = { _, query, _ -> searchRequests += query },
        )

        @Composable
        fun Content() {
            val controller = remember { ThemeController(ColorSchemeMode.Dark) }
            MiuixTheme(controller = controller) {
                Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    Box(Modifier.fillMaxSize().background(Color(0xff17191c))) {
                        ConversationHeader(
                            chat = requireNotNull(snapshot.selected), snapshot = snapshot, features = features, active = true,
                            onBack = null, menuOpen = false, onMenuOpen = {}, onMarkRead = {},
                            onBounds = { toolbarBounds = it }, onSearch = {},
                            onDetails = { show = true; features.loadChatDetails(snapshot.token) },
                            onMedia = {}, showInfoButton = true, onPinned = {}, onTogglePinned = {}, pinnedHidden = false,
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 24.dp),
                            adminsPopup = {
                                ConversationAdminsPopup(show = show, snapshot = snapshot, features = features,
                                    onDismissRequest = { show = false }, onDismissFinished = { dismissalFinishes++ })
                            },
                        )
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonDescriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun changedPixels(first: ComposePixelFrame, second: ComposePixelFrame, bounds: Rect): Int {
        var changed = 0
        for (y in max(0, bounds.top.toInt()) until min(first.height, bounds.bottom.toInt())) {
            for (x in max(0, bounds.left.toInt()) until min(first.width, bounds.right.toInt())) {
                if (first[x, y] != second[x, y]) changed++
            }
        }
        return changed
    }
}
