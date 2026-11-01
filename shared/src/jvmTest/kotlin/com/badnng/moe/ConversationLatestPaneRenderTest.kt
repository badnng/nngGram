package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.ChatController
import com.badnng.moe.tdlib.TdChatSession
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real Pane visibility -> actor -> fake native ViewMessages, not a synthetic motion-only probe. */
class ConversationLatestPaneRenderTest {
    @Test
    fun latestFailureDisplaysTheRealErrorAndLeavesRetryInteractiveAfterTheExit() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("回到最新消息"))
            scene.settledFrame()
            assertEquals(0L, fixture.session.history.single().from)
            fixture.complete(0, ChatResult(error = ChatError.REQUEST_FAILED))
            scene.settledFrame()
            assertTrue(scene.hasText("消息暂时无法加载"), "A failed latest request must finish with the real error pane, not an empty viewport.")
            assertFalse(scene.hasText("旧窗口-101"))
            val retried = fixture.session.history.size
            scene.click(scene.button("重试加载消息"))
            scene.settledFrame()
            assertEquals(retried + 1, fixture.session.history.size)
            assertEquals(0L, fixture.session.history.last().from)
            assertTrue(fixture.snapshot.historyLoading)
        }
    }

    @Test
    fun changingChatsWhileWaitingDropsTheOldWindowAndDoesNotDisableTheNewChat() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.click(scene.description("回到最新消息"))
            scene.settledFrame()
            val abandoned = fixture.session.history.single()
            fixture.onActor { fixture.controller.select(fixture.snapshot.token, 11); fixture.flush() }
            scene.settledFrame()
            assertEquals(11L, fixture.snapshot.selected?.id)
            assertFalse(scene.hasText("旧窗口-101"))
            assertTrue(scene.hasText("其他会话"))
            scene.click(scene.description("在此群搜索内容"))
            scene.settledFrame()
            assertEquals(listOf(11L), fixture.searches.toList(), "The old pending window must not leave input disabled in another chat.")
            fixture.onActor {
                abandoned.done(ChatResult(listOf(message(1_000))))
                fixture.flush()
            }
            scene.settledFrame()
            assertEquals(11L, fixture.snapshot.selected?.id)
            assertTrue(fixture.snapshot.messages.isEmpty(), "The late old-chat latest callback must remain rejected by the native selection token.")
            assertFalse(scene.hasText("最新窗口-1000"))
        }
    }

    @Test
    fun actualNativePagesAndBothExitingPanesNeverReadAcrossTheHistoryGap() {
        val fixture = Fixture()
        fixture.scene().use { scene ->
            scene.settledFrame()
            scene.await { fixture.session.reads.isNotEmpty() }
            assertTrue(fixture.session.reads.flatMap { it.ids }.all { it in 101L..105L })
            val oldSearch = scene.description("在此群搜索内容")
            val priorReads = fixture.session.reads.size
            val oldToken = fixture.snapshot.token
            val priorVisibility = fixture.visibility.size
            scene.click(scene.description("回到最新消息"))
            scene.settledFrame()
            scene.click(oldSearch)
            scene.settledFrame(1)
            assertTrue(fixture.searches.isEmpty(), "The retained real Pane must reject old header input while loading.")
            // The production debounce uses wall time. Hold the deterministic animation clock
            // still while allowing any accidentally active outgoing visibility job to fire.
            Thread.sleep(360)
            assertEquals(priorReads, fixture.session.reads.size)

            fixture.complete(0, ChatResult(listOf(message(1_000))))
            scene.settledFrame(2)
            assertEquals(listOf(1_000L), fixture.snapshot.messages.map { it.id })
            Thread.sleep(360)
            assertEquals(priorReads, fixture.session.reads.size, "The newly entering real Pane cannot dispatch reads before its movement completes.")

            fixture.complete(1, ChatResult(listOf(message(998), message(999))))
            scene.settledFrame(1)
            assertEquals(listOf(998L, 999L, 1_000L), fixture.snapshot.messages.map { it.id })
            Thread.sleep(360)
            assertEquals(priorReads, fixture.session.reads.size)
            assertTrue(fixture.visibility.drop(priorVisibility).none { it.active && it.ids.isNotEmpty() },
                "Neither real Pane may advertise incoming visibility during the native-page/exit interval.")
            assertTrue(fixture.visibility.drop(priorVisibility).any { it.token == oldToken && !it.active && it.ids.isEmpty() })
            assertTrue(fixture.visibility.drop(priorVisibility).any { it.token == fixture.snapshot.token && !it.active && it.ids.isEmpty() },
                "The incoming real Pane must explicitly report inactive visibility before its transition completes.")
            fixture.complete(2, ChatResult(emptyList()))
            assertEquals(listOf(0L, 1_000L, 998L), fixture.session.history.map { it.from })
            scene.settledFrame()
            scene.await { fixture.session.reads.size > priorReads }
            val latestReads = fixture.session.reads.drop(priorReads)
            assertTrue(latestReads.isNotEmpty())
            assertTrue(latestReads.all { it.chatId == 10L && !it.force && it.ids.isNotEmpty() && it.ids.all { id -> id in 998L..1_000L } },
                "Native ViewMessages must contain only visible IDs in the new real window; no old messages or invented intervening interval.")
            assertFalse(scene.hasText("旧窗口-101"))
            assertTrue(scene.hasText("最新窗口-1000"))
            scene.click(scene.description("在此群搜索内容"))
            scene.settledFrame()
            assertEquals(listOf(10L), fixture.searches.toList(), "Current controls regain input after the old Pane is disposed.")
        }
    }

    private class Fixture {
        var snapshot by mutableStateOf(ChatSnapshot())
        val session = FakeSession()
        val visibility = CopyOnWriteArrayList<Visibility>()
        val searches = CopyOnWriteArrayList<Long>()
        private val queue = ArrayDeque<() -> Unit>()
        val controller = ChatController(1, session, queue::addLast, { snapshot = it })
        init {
            onActor {
                controller.start()
                controller.event(ChatEvent.NewChat(ChatItem(10, "原会话", order = 100,
                    unreadCount = 5, lastReadInboxId = 100, lastMessage = message(1_000))))
                controller.event(ChatEvent.NewChat(ChatItem(11, "其他会话", order = 90)))
                controller.select(snapshot.token, 10)
                flush()
                session.unread.single()(ChatResult((101L..105L).map { message(it, older = true) }))
                flush()
            }
        }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun complete(index: Int, result: ChatResult<List<ChatMessage>>) = onActor {
            session.history[index].done(result)
            flush()
        }
        fun <T> onActor(work: () -> T): T = onEventThread(work)
        fun scene() = ComposePixelScene(900, 700) {
            val theme = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = theme) {
                ConversationLatestTransition(snapshot, onReturnToLatest = {
                    controller.returnToLatest(it)
                    flush()
                }) { shown, interactive, returnLatest ->
                    Box(Modifier.fillMaxSize()) {
                        ConversationPane(shown, shown.selected!!, TextFieldValue(), {}, null,
                            // This fixture owns native latest pages explicitly; independent
                            // background preloads are covered by HistoryPreloadRenderTest.
                            onLoadOlder = { if (shown.historyError != null) { controller.loadOlder(shown.token); flush() } },
                            onSend = {}, onRetry = {}, onMarkRead = {},
                            features = ChatFeatureActions(visibleMessages = { token, ids, active ->
                                visibility += Visibility(token, ids.toList(), active)
                                controller.visibleMessages(token, ids, active)
                                flush()
                            }), active = interactive, onSearch = { searches += checkNotNull(shown.selected).id }, onSearchSender = {},
                            onAdmins = {}, onInfo = {}, showInfoButton = true, onReturnToLatest = returnLatest)
                    }
                }
            }
        }
    }

    private data class Visibility(val token: ChatToken, val ids: List<Long>, val active: Boolean)
    private data class Read(val chatId: Long, val ids: List<Long>, val force: Boolean)
    private data class History(val chatId: Long, val from: Long, val done: (ChatResult<List<ChatMessage>>) -> Unit)
    private class FakeSession : TdChatSession {
        val unread = mutableListOf<(ChatResult<List<ChatMessage>>) -> Unit>()
        val history = CopyOnWriteArrayList<History>()
        val reads = CopyOnWriteArrayList<Read>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) {
            history += History(chatId, fromMessageId, result)
        }
        override fun unreadHistory(chatId: Long, afterMessageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) { unread += result }
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) = Unit
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = error("Automatic reads must use force=false native ViewMessages")
        override fun viewMessages(chatId: Long, messageIds: List<Long>, forceRead: Boolean, result: (ChatResult<Unit>) -> Unit) {
            reads += Read(chatId, messageIds.toList(), forceRead)
            result(ChatResult(Unit))
        }
    }

    private fun ComposePixelScene.description(prefix: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { value -> value.startsWith(prefix) }
    }.firstOrNull(), "Missing actual Pane action: $prefix")
    private fun ComposePixelScene.button(text: String): Rect = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.firstOrNull())
    private fun ComposePixelScene.hasText(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.isNotEmpty()
    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
    private fun ComposePixelScene.await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            settledFrame(1)
        }
        assertTrue(condition(), "The actual Pane visibility debounce must reach fake native ViewMessages.")
    }
    companion object {
        private fun message(id: Long, older: Boolean = false) = ChatMessage(id, 10, senderId = 20,
            text = if (older) "旧窗口-$id" else "最新窗口-$id")
    }
}

private fun <T> onEventThread(work: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return work()
    val task = FutureTask(Callable(work))
    SwingUtilities.invokeAndWait(task)
    return task.get()
}
