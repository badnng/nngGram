package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatFolderInfo
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.event.KeyEvent
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Actual list input and semantics across removal of the pane, with no account or persistent state. */
class ChatListPaneRetentionRenderTest {
    @Test
    fun returningFromACompactConversationOrWorkspaceSearchRetainsListAndLabelPositions() {
        for ((density, mode, selectChat) in listOf(
            Triple(1f, ColorSchemeMode.Light, true),
            Triple(1.25f, ColorSchemeMode.Dark, false),
        )) {
            val fixture = Fixture(density, mode)
            fixture.scene().use { scene ->
                scene.settledFrame()
                assertTrue(scene.tabSelected("全部"))
                assertTrue(scene.visibleTitles().any { it.startsWith("Read") })
                assertTrue(scene.visibleTitles().any { it.startsWith("Unread") })
                scene.scrollList(density)
                assertTrue(scene.verticalPosition() >= 3f)

                scene.typeQuery("Alpha")
                scene.settledFrame()
                assertTrue(scene.editableQuery("Alpha"), "Actual keyboard input must update the list query before navigation.")
                assertEquals(0f, scene.verticalPosition(), 0.001f,
                    "Changing the actual editor query still returns the filtered list to its top.")
                scene.scrollList(density)
                val vertical = scene.verticalPosition()
                assertTrue(vertical >= 3f)

                // A selected tab can deliberately be scrolled away. Remounting must
                // not run a fresh bringIntoView effect and override that position.
                scene.scrollAt(160f * density, 92f * density, deltaY = 12f)
                scene.settledFrame()
                val horizontal = scene.horizontalPosition()
                assertTrue(horizontal > 0f, "The real folder label row must overflow and scroll.")
                val visibleRows = scene.visibleTitles()
                assertTrue(visibleRows.isNotEmpty())
                assertFalse(visibleRows.any { it.startsWith("Read") })

                if (selectChat) {
                    scene.click(assertNotNull(scene.visibleRowBounds().firstOrNull {
                        it.top >= 140f * density && it.bottom <= 480f * density
                    }))
                } else fixture.mounted.value = false
                scene.settledFrame()
                assertTrue(scene.visibleTitles().isEmpty(), "The pane must actually leave composition during navigation.")
                fixture.mounted.value = true
                scene.settledFrame()

                assertEquals(vertical, scene.verticalPosition(), 0.001f,
                    "Returning from conversation/search must retain the actual LazyColumn position.")
                assertEquals(horizontal, scene.horizontalPosition(), 1f,
                    "Returning must retain the user's horizontal label position.")
                assertTrue(scene.tabSelected("全部"), "The selected folder must survive pane disposal.")
                assertTrue(scene.editableQuery("Alpha"), "The transient query must survive pane disposal.")
                assertEquals(visibleRows, scene.visibleTitles(), "Returning must show the same filtered rows.")
            }
        }
    }

    @Test
    fun configuredTopFolderTabsKeepTheUsersHorizontalPositionAfterSearchCloses() {
        val fixture = Fixture(1.25f, ColorSchemeMode.Dark)
        fixture.scene(topTabs = true).use { scene ->
            scene.settledFrame()
            scene.scrollList(fixture.density)
            val vertical = scene.verticalPosition()
            assertTrue(vertical >= 3f)
            scene.scrollAt(160f * fixture.density, 92f * fixture.density, deltaY = 12f)
            scene.settledFrame()
            val horizontal = scene.horizontalPosition()
            assertTrue(horizontal > 0f, "The configured top folder labels must actually move away from the selected first tab.")

            fixture.mounted.value = false
            scene.settledFrame()
            fixture.mounted.value = true
            scene.settledFrame()

            assertEquals(vertical, scene.verticalPosition(), 0.001f)
            assertEquals(horizontal, scene.horizontalPosition(), 0.001f,
                "The top folder strip must not reveal the first selected tab again when search closes.")
        }
    }

    @Test
    fun changingSearchActualFolderOrAccountStillStartsTheCorrectListAtItsTop() {
        val fixture = Fixture(1f, ColorSchemeMode.Light)
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.scrollList(1f)
            scene.typeQuery("Alpha")
            scene.settledFrame()
            assertEquals(0f, scene.verticalPosition(), 0.001f)
            assertTrue(scene.tabSelected("全部"))
            assertFalse(scene.visibleTitles().any { it.startsWith("Read") })

            scene.scrollList(1f)
            fixture.snapshot.value = fixture.snapshot.value.copy(activeList = ChatListRef(1))
            scene.settledFrame()
            assertEquals(0f, scene.verticalPosition(), 0.001f)
            assertTrue(scene.editableQuery(""))
            assertTrue(scene.tabSelected("Synthetic folder 1"))
            assertTrue(scene.visibleTitles().any { it.startsWith("Read") })

            fixture.snapshot.value = fixture.snapshot.value.copy(activeList = ChatListRef())
            scene.settledFrame()
            assertEquals(0f, scene.verticalPosition(), 0.001f,
                "A real folder change must not restore a discarded old folder offset.")
            assertTrue(scene.editableQuery(""))
            assertTrue(scene.tabSelected("全部"))

            scene.typeQuery("Beta")
            scene.settledFrame()
            scene.scrollList(1f)
            fixture.snapshot.value = fixture.snapshot.value.copy(token = ChatToken(2, 0, null))
            scene.settledFrame()
            assertEquals(0f, scene.verticalPosition(), 0.001f)
            assertTrue(scene.editableQuery(""))
            assertTrue(scene.tabSelected("全部"), "A new account generation must discard the old presentation state.")
            assertTrue(scene.visibleTitles().any { it.startsWith("Read") })
            assertTrue(scene.visibleTitles().any { it.startsWith("Unread") })
        }
    }

    private class Fixture(val density: Float, val mode: ColorSchemeMode) {
        val mounted = mutableStateOf(true)
        val rows = (0 until 80).map { index ->
            ChatItem(index + 1L, if (index % 2 == 0) "Unread Alpha $index" else "Read Beta $index",
                order = 100L - index, unreadCount = if (index % 2 == 0) 1 else 0)
        }
        val snapshot = mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = rows, knownChats = rows, listEnd = true,
            folders = (1..6).map { ChatFolderInfo(it, "Synthetic folder $it") }))

        fun scene(topTabs: Boolean = false) = ComposePixelScene((320 * density).roundToInt(), (500 * density).roundToInt()) {
            val paneState = rememberChatListPaneState(snapshot.value.token.generation, snapshot.value.activeList)
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true, showRail = false)) {
                MiuixTheme(controller = theme) {
                    if (mounted.value) ChatListPane(snapshot.value,
                        onSelect = { chatId ->
                            snapshot.value = snapshot.value.copy(token = ChatToken(1, 1, chatId), selected = rows.first { it.id == chatId })
                            mounted.value = false
                        }, onRetryLoad = {}, onSelectList = {}, onManageFolders = {},
                        features = ChatFeatureActions(), state = paneState, modifier = Modifier.fillMaxSize(),
                        folderTabs = if (topTabs) ({
                            ChatFolderTopTabs(snapshot.value, {}, state = paneState.topTabsState)
                        }) else null)
                }
            }
        }
    }

    private fun ComposePixelScene.scrollList(density: Float) {
        repeat(3) {
            scrollAt(160f * density, 350f * density, deltaY = 4f)
            settledFrame(8)
        }
    }

    private fun ComposePixelScene.typeQuery(text: String) {
        val field = assertNotNull(semanticsBounds { it.getOrNull(SemanticsProperties.EditableText) != null }.firstOrNull())
        click(field)
        settledFrame(2)
        text.forEach { character ->
            keyStroke(KeyEvent.getExtendedKeyCodeForChar(character.code), character)
            settledFrame(2)
        }
    }

    private fun ComposePixelScene.tabSelected(label: String): Boolean {
        var selected = false
        semanticsBounds {
            if (it.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }) {
                selected = it.getOrNull(SemanticsProperties.Selected) == true
            }
            false
        }
        return selected
    }

    private fun ComposePixelScene.editableQuery(query: String): Boolean = semanticsBounds {
        it.getOrNull(SemanticsProperties.EditableText)?.text == query
    }.isNotEmpty()

    private fun ComposePixelScene.visibleRowBounds(): List<Rect> = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
            text.text.startsWith("Unread Alpha") || text.text.startsWith("Read Beta")
        }
    }.filter { it.width > 0f && it.height > 0f }

    private fun ComposePixelScene.visibleTitles(): List<String> = buildList {
        semanticsBounds {
            it.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
                .filter { text -> text.startsWith("Unread Alpha") || text.startsWith("Read Beta") }.forEach(::add)
            false
        }
    }

    private fun ComposePixelScene.verticalPosition(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range -> values += range.value() }
            false
        }
        return assertNotNull(values.maxOrNull())
    }

    private fun ComposePixelScene.horizontalPosition(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        return assertNotNull(values.maxOrNull())
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
