package com.badnng.moe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatListAction
import com.badnng.moe.chat.ChatListRef
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatResult
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real LazyColumn, native Popup and changing synthetic native snapshots; no account requests. */
class ChatListMenuOrderRenderTest {
    @Test
    fun incomingMovesInEitherDirectionKeepTheOperatedCardFixedAndItsNativeFieldsLive() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val fixture = Fixture(density, mode)
            var complete: ((ChatResult<Unit>) -> Unit)? = null
            var dispatched: ChatListAction? = null
            fixture.features = ChatFeatureActions(chatListAction = { _, id, _, action, done ->
                assertEquals(fixture.rows[2].id, id)
                dispatched = action
                complete = done
            })
            fixture.scene().use { scene ->
                val before = scene.settledFrame()
                val target = fixture.rows[2]
                val original = scene.row(target.title)
                saveFrame(before, "live-$density-$mode-before")
                scene.secondaryClickAt(original.center.x, original.center.y)
                scene.settledFrame()
                scene.assertFixed(original, target.title)

                // A native action may remain pending while further chats receive messages.
                scene.click(scene.text("置顶聊天"))
                scene.settledFrame()
                assertEquals(ChatListAction.PIN, dispatched)
                assertNotNull(complete)
                assertTrue(scene.hasText("正在处理……"))

                val down = listOf(fixture.rows[0], fixture.rows[4], fixture.rows[1], target,
                    fixture.rows[3], fixture.rows[5])
                fixture.publish(down)
                scene.settledFrame()
                scene.assertFixed(original, target.title)

                val refreshed = target.copy(title = "Updated operated group", unreadCount = 47,
                    lastMessage = ChatMessage(700, target.id, text = "Native preview is current", date = 1_700_000_020))
                fixture.publish(listOf(fixture.rows[0], refreshed, fixture.rows[1], fixture.rows[3],
                    fixture.rows[4], fixture.rows[5]))
                val during = scene.settledFrame()
                saveFrame(during, "live-$density-$mode-during")
                scene.assertFixed(original, refreshed.title)
                assertTrue(scene.hasText("Native preview is current"), "Holding geometry must not freeze native preview content.")
                assertEquals(1, scene.description("47 条未读").size, "Native unread state must refresh on the same original row.")
                assertTrue(scene.hasText("标记为已读"), "Native menu availability must use the refreshed row.")
                assertFalse(scene.hasText(target.title), "A title update must replace the old source, never duplicate it.")

                // Leave the initial top row unchanged so release has an unambiguous latest order.
                val latest = listOf(fixture.rows[0], fixture.rows[1], fixture.rows[3], fixture.rows[4],
                    refreshed, fixture.rows[5])
                fixture.publish(latest)
                scene.settledFrame()
                scene.assertFixed(original, refreshed.title)
                assertNotNull(complete).invoke(ChatResult(Unit))
                val closing = scene.settledFrame(5, 16)
                saveFrame(closing, "live-$density-$mode-closing")
                assertTrue(fixture.backdrop.hasMountedMenus, "Action acceptance must retain source ownership throughout menu exit.")
                sameBounds(original, scene.row(refreshed.title), "Accepted native actions cannot release order before exit completes.")
                val restored = scene.settledFrame()
                saveFrame(restored, "live-$density-$mode-restored")
                assertFalse(fixture.backdrop.hasMountedMenus)
                assertTrue(scene.description("会话卡片").isEmpty())
                scene.assertVisibleOrder(latest)
                assertTrue(scene.row(refreshed.title).top > original.top + 60f * density,
                    "Only after restoration may newer chats push the operated card down.")
            }
        }
    }

    @Test
    fun newcomersAndRemovedNeighboursWaitUntilExitFinishesAndOnlyTheLatestOrderIsApplied() {
        val fixture = Fixture(1f, ColorSchemeMode.Light)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val target = fixture.rows[2]
            val original = scene.row(target.title)
            val positions = fixture.rows.associate { it.id to scene.row(it.title) }
            scene.secondaryClickAt(original.center.x, original.center.y)

            val newcomer = ChatItem(90, "New native group", order = 900,
                lastMessage = ChatMessage(90, 90, text = "New native preview"))
            // Deliver before the opening Popup has rendered: pointer ownership must
            // capture this exact displayed order synchronously, without a one-frame race.
            fixture.publish(listOf(fixture.rows[0], newcomer, fixture.rows[4], target,
                fixture.rows[3], fixture.rows[5]))
            scene.settledFrame()
            scene.assertFixed(original, target.title)
            assertFalse(scene.hasText(newcomer.title), "New membership must wait rather than insert above the held card.")
            sameBounds(assertNotNull(positions[fixture.rows[1].id]), scene.row(fixture.rows[1].title),
                "Removing another row must not collapse its old slot while this source owns the menu.")

            fixture.publish(listOf(fixture.rows[0], target, fixture.rows[5], newcomer, fixture.rows[4]))
            scene.settledFrame()
            scene.assertFixed(original, target.title)
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            scene.settledFrame(1, 16)
            assertTrue(fixture.backdrop.hasMountedMenus)

            // This update arrives after dismissal but before the retained source is restored.
            val latest = listOf(fixture.rows[0], newcomer, fixture.rows[5], fixture.rows[4], target)
            fixture.publish(latest)
            saveFrame(scene.settledFrame(4, 16), "exit-update-closing")
            assertTrue(fixture.backdrop.hasMountedMenus, "An 80ms closing menu still owns its original list order.")
            sameBounds(original, scene.row(target.title), "An update during closing cannot move the card.")
            assertFalse(scene.hasText(newcomer.title))
            val restored = scene.settledFrame()
            saveFrame(restored, "exit-update-restored")
            assertFalse(fixture.backdrop.hasMountedMenus)
            scene.assertVisibleOrder(latest)
            assertFalse(scene.hasText(fixture.rows[1].title), "The deferred removed neighbour must disappear on restoration.")
            assertEquals(1, scene.rows(newcomer.title).size)
            assertTrue(scene.row(target.title).top > original.top + 100f)
        }
    }

    @Test
    fun reopeningTheFirstRowDuringSourceRestorationCannotLetTheOldReleaseUnlockItsNewHold() {
        val fixture = Fixture(1f, ColorSchemeMode.Light)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val target = fixture.rows.first()
            val original = scene.row(target.title)
            assertEquals(0, fixture.state.forList(ChatListRef()).listState.firstVisibleItemIndex)
            assertEquals(0, fixture.state.forList(ChatListRef()).listState.firstVisibleItemScrollOffset)
            scene.secondaryClickAt(original.center.x, original.center.y)
            scene.settledFrame()

            val newcomer = ChatItem(90, "First native newcomer", order = 900,
                lastMessage = ChatMessage(90, 90, text = "First native arrival"))
            fixture.publish(listOf(newcomer, fixture.rows[5], fixture.rows[4], target,
                fixture.rows[1], fixture.rows[2], fixture.rows[3]))
            scene.settledFrame()
            scene.assertFixed(original, target.title)
            scene.keyStroke(KeyEvent.VK_ESCAPE)

            // Stop at the first frame where the Popup has gone. Its original source
            // has just been restored, while the old hold's two-frame barrier is pending.
            var unmounted = false
            for (frame in 0 until 40) {
                scene.settledFrame(1, 16)
                if (!fixture.backdrop.hasMountedMenus) {
                    unmounted = true
                    break
                }
            }
            assertTrue(unmounted, "The retained Popup must finish its bounded exit animation.")
            sameBounds(original, scene.row(target.title),
                "Restoring the source must precede applying the queued native order.")
            assertTrue(scene.description("会话卡片").isEmpty())

            // Reopen before another frame can finish the old restoration barrier.
            scene.secondaryClickAt(original.center.x, original.center.y)
            val newest = ChatItem(91, "Newest native newcomer", order = 901,
                lastMessage = ChatMessage(91, 91, text = "Second native arrival"))
            val latest = listOf(newest, newcomer, fixture.rows[5], fixture.rows[4], target,
                fixture.rows[1], fixture.rows[2], fixture.rows[3])
            fixture.publish(latest)
            saveFrame(scene.settledFrame(), "restore-reopen-during")
            scene.assertFixed(original, target.title)
            assertFalse(scene.hasText(newest.title), "A superseded release cannot apply new membership under the reopened menu.")

            scene.keyStroke(KeyEvent.VK_ESCAPE)
            saveFrame(scene.settledFrame(), "restore-reopen-restored")
            assertFalse(fixture.backdrop.hasMountedMenus)
            assertTrue(scene.description("会话卡片").isEmpty())
            sameBounds(original, scene.row(newest.title),
                "A reader originally at the top must see the actual newest native row after final release.")
            scene.assertVisibleOrder(latest)
            assertTrue(scene.row(target.title).top > original.top + 200f,
                "After the reopened operation finishes, the original first card can be pushed down by new messages.")
            assertEquals(0, fixture.state.forList(ChatListRef()).listState.firstVisibleItemIndex,
                "Key retention must not hide the new top rows when the reader was originally at the top.")
            assertEquals(0, fixture.state.forList(ChatListRef()).listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun aNonTopScrolledReaderKeepsItsOffsetAndOriginalCardUntilTheMenuHasGone() {
        val fixture = Fixture(1.25f, ColorSchemeMode.Dark, rowCount = 40)
        fixture.scene().use { scene ->
            scene.settledFrame()
            repeat(3) {
                scene.scrollAt(180f * fixture.density, 550f * fixture.density, deltaY = 5f)
                scene.settledFrame(8)
            }
            val list = fixture.state.forList(ChatListRef()).listState
            assertTrue(list.firstVisibleItemIndex > 0, "The fixture must operate on an actual scrolled list.")
            val visible = list.layoutInfo.visibleItemsInfo.filter { info ->
                info.key is Long && info.offset > 180 && info.offset + info.size < fixture.heightPixels - 80
            }
            val targetInfo = assertNotNull(visible.getOrNull(1))
            val target = fixture.rows.single { it.id == targetInfo.key }
            val original = scene.row(target.title)
            val index = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            saveFrame(scene.settledFrame(), "scrolled-before")
            scene.secondaryClickAt(original.center.x, original.center.y)
            scene.settledFrame()

            val firstVisibleId = list.layoutInfo.visibleItemsInfo.first { it.key is Long }.key as Long
            val crossing = fixture.rows.last()
            val latest = fixture.rows.filterNot { it.id == crossing.id || it.id == target.id }.toMutableList().apply {
                val anchor = indexOfFirst { it.id == firstVisibleId }
                add(anchor + 1, crossing)
                add(anchor + 4, target)
            }
            fixture.publish(latest)
            val during = scene.settledFrame()
            saveFrame(during, "scrolled-during")
            scene.assertFixed(original, target.title)
            assertEquals(index, list.firstVisibleItemIndex, "Native reorder must not change the scrolled reader's held first index.")
            assertEquals(offset, list.firstVisibleItemScrollOffset, "Native reorder must not drift the held pixel offset.")
            scene.keyStroke(KeyEvent.VK_ESCAPE)
            saveFrame(scene.settledFrame(5, 16), "scrolled-closing")
            assertTrue(fixture.backdrop.hasMountedMenus)
            sameBounds(original, scene.row(target.title), "A scrolled source remains fixed while closing.")
            saveFrame(scene.settledFrame(), "scrolled-restored")
            assertFalse(fixture.backdrop.hasMountedMenus)
            assertEquals(offset, list.firstVisibleItemScrollOffset, "Restoration retains the user's existing pixel offset.")
            list.layoutInfo.visibleItemsInfo.filter { it.key is Long }.forEach { info ->
                assertEquals(latest[info.index].id, info.key, "The released window must use current native membership and order.")
            }
            assertTrue(abs(scene.row(target.title).top - original.top) > 20f,
                "After closing, the operated row must participate in the latest native order again.")
        }
    }

    @Test
    fun removingTheOperatedChatDisposesItsMenuAndReleasesOtherNativeUpdates() {
        val fixture = Fixture(1f, ColorSchemeMode.Light)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val target = fixture.rows[2]
            val original = scene.row(target.title)
            scene.secondaryClickAt(original.center.x, original.center.y)
            scene.settledFrame()
            val latest = listOf(fixture.rows[0], fixture.rows[4], fixture.rows[5], fixture.rows[1], fixture.rows[3])
            fixture.publish(latest)
            scene.settledFrame()
            assertFalse(fixture.backdrop.hasMountedMenus, "A vanished native target must release its source/menu ownership.")
            assertTrue(scene.description("会话卡片").isEmpty())
            assertFalse(scene.hasText(target.title), "A removed target cannot remain as an interactive frozen card.")
            scene.assertVisibleOrder(latest)
            val next = listOf(latest[0], latest[3], latest[2], latest[1], latest[4])
            fixture.publish(next)
            scene.settledFrame()
            scene.assertVisibleOrder(next)
        }
    }

    @Test
    fun changingTheListOrAccountGenerationCannotCarryTheOldOrderHoldIntoTheNewIdentity() {
        for (accountChange in listOf(false, true)) {
            val fixture = Fixture(1f, ColorSchemeMode.Light)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val source = scene.row(fixture.rows[2].title)
                scene.secondaryClickAt(source.center.x, source.center.y)
                scene.settledFrame()
                val latest = fixture.rows.reversed()
                val current = fixture.snapshot.value
                fixture.snapshot.value = current.copy(items = latest, knownChats = latest,
                    token = if (accountChange) ChatToken(2, 0, null) else current.token,
                    activeList = if (accountChange) current.activeList else ChatListRef(7))
                scene.settledFrame()
                assertFalse(fixture.backdrop.hasMountedMenus, "An old list/account identity cannot retain its popup owner.")
                assertTrue(scene.description("会话卡片").isEmpty())
                scene.assertVisibleOrder(latest)
                val next = listOf(latest.first()) + latest.drop(1).reversed()
                fixture.publish(next)
                scene.settledFrame()
                scene.assertVisibleOrder(next)
            }
        }
    }

    private class Fixture(val density: Float, mode: ColorSchemeMode, rowCount: Int = 6) {
        val heightPixels = (700 * density).roundToInt()
        val rows = (1L..rowCount.toLong()).map { id -> ChatItem(id, "Synthetic group $id", order = 1000 - id,
            lastMessage = ChatMessage(id, id, text = "Synthetic preview $id", date = 1_700_000_000)) }
        val snapshot = mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
            items = rows, knownChats = rows, listEnd = true))
        val backdrop = ContextMenuBackdropState()
        var features = ChatFeatureActions()
        lateinit var state: ChatListPaneState
        private val colorMode = mode

        fun publish(items: List<ChatItem>) {
            snapshot.value = snapshot.value.copy(items = items, knownChats = items)
        }

        fun scene() = ComposePixelScene((380 * density).roundToInt(), heightPixels) {
            val theme = remember { ThemeController(colorMode) }
            state = rememberChatListPaneState(snapshot.value.token.generation, snapshot.value.activeList)
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true, showRail = false),
                LocalContextMenuBackdrop provides backdrop) {
                MiuixTheme(controller = theme) {
                    ChatListPane(snapshot.value, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                        features = features, state = state)
                }
            }
        }
    }

    private fun ComposePixelScene.rows(title: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Selected) != null &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == title }
    }

    private fun ComposePixelScene.row(title: String): Rect = assertNotNull(rows(title).singleOrNull(),
        "The real list must contain one source row for $title.")

    private fun ComposePixelScene.description(text: String) = semanticsBounds {
        text in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.hasText(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.isNotEmpty()

    private fun ComposePixelScene.text(text: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull())

    private fun ComposePixelScene.assertFixed(expected: Rect, title: String) {
        sameBounds(expected, row(title), "The original mounted card must keep its window coordinates.")
        sameBounds(expected, assertNotNull(description("会话卡片").singleOrNull()),
            "Exactly one raised rendering must use the same original card coordinates.")
        assertEquals(1, semanticsBounds { it.getOrNull(SemanticsProperties.PaneTitle) == "聊天操作" }.size,
            "Native updates must not create a second menu or replace its operation owner.")
    }

    private fun ComposePixelScene.assertVisibleOrder(expected: List<ChatItem>) {
        val visible = expected.mapNotNull { chat -> rows(chat.title).singleOrNull()?.let { chat.id to it } }
            .filter { it.second.height > 0 }
            .sortedBy { it.second.top }
        assertEquals(expected.map { it.id }, visible.map { it.first },
            "The restored real list must apply the latest native order exactly once.")
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun sameBounds(expected: Rect, actual: Rect, reason: String) {
        assertTrue(abs(expected.left - actual.left) <= 1f && abs(expected.top - actual.top) <= 1f &&
            abs(expected.right - actual.right) <= 1f && abs(expected.bottom - actual.bottom) <= 1f,
            "$reason $expected -> $actual")
    }

    private fun saveFrame(frame: ComposePixelFrame, name: String) {
        val directory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "list-card-menu", "menu-order"))
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }
}
