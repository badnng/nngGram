package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.tdlib.ChatController
import com.badnng.moe.tdlib.TdChatSession
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
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

/** Real composer input -> actor -> fake native acknowledgement -> measured LazyColumn geometry. */
class ConversationOutgoingFollowRenderTest {
    @Test
    fun aWheelOrPageDeactivationDuringRevealEndsTheEffectWithoutAReplay() {
        for (deactivate in listOf(false, true)) {
            val fixture = Fixture(80, ColorSchemeMode.Light, 1f)
            fixture.scene().use { scene ->
                scene.settledFrame()
                fixture.event(ChatEvent.NewMessage(ChatMessage(81, 10,
                    text = (1..6).joinToString("\n") { "动画取消合成场景 第${it}行" })))
                scene.settledFrame(1, stepMillis = 16)
                if (deactivate) fixture.pageInteractive = false else {
                    scene.scrollAt(420f, 350f, deltaY = -0.2f)
                    scene.settledFrame(1, stepMillis = 16)
                    if (scene.timelineScrollValue() == 0f) scene.scrollAt(420f, 350f, deltaY = 0.2f)
                }
                // Settle cancellation well before the normal 150 ms reveal can finish.
                scene.settledFrame(2, stepMillis = 16)
                if (!deactivate) assertTrue(scene.timelineScrollValue() > 0f,
                    "The real wheel must leave the bottom while the incoming reveal is running.")
                val (anchorId, anchor) = fixture.centerReadingAnchor()
                val newRow = fixture.measured[81]
                scene.settledFrame(6)
                fixture.assertAnchor(anchorId, anchor, "mid-reveal ${if (deactivate) "deactivation" else "wheel"}")
                assertEquals(newRow, fixture.measured[81], "A cancelled reveal must stop moving immediately.")
                if (deactivate) {
                    fixture.pageInteractive = true
                    scene.settledFrame(1, stepMillis = 16)
                    assertEquals(newRow, fixture.measured[81], "Reactivation cannot replay a consumed entrance.")
                }
            }
        }
    }

    @Test
    fun nativeIncomingRowsRevealAtTheirFullSizeAndMoveExistingContentWithoutMovingTheComposer() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(12, mode, density)
            fixture.scene().use { scene ->
                scene.settledFrame()
                val composer = scene.composerBounds()
                val lastBefore = assertNotNull(fixture.measured[12])
                fixture.event(ChatEvent.NewMessage(ChatMessage(13, 10,
                    text = "新到达的合成消息\n文字和头像保持原始显示大小\n现有内容自然向上让位\n第四行用于测量展开高度")))
                val first = scene.settledFrame(1)
                fixture.export(first, "incoming-start")
                val firstOld = assertNotNull(fixture.measured[12])
                val firstNew = assertNotNull(fixture.measured[13])
                // The animation's first frame establishes its clock; the following
                // frame advances it to a genuine partially revealed height.
                val middle = scene.settledFrame(2)
                fixture.export(middle, "incoming-middle")
                val middleOld = assertNotNull(fixture.measured[12])
                val middleNew = assertNotNull(fixture.measured[13])
                val end = scene.settledFrame(5)
                fixture.export(end, "incoming-end")
                val endOld = assertNotNull(fixture.measured[12])
                val endNew = assertNotNull(fixture.measured[13])
                assertTrue(lastBefore.top >= firstOld.top - density)
                assertTrue(firstOld.top > middleOld.top + density,
                    "The previous row must move through a real intermediate position: $firstOld -> $middleOld")
                assertTrue(middleOld.top > endOld.top + density,
                    "The measured reveal must continue until the full new row fits: $middleOld -> $endOld")
                assertEquals(firstNew.size, middleNew.size, "The message artwork and text are never scaled.")
                assertEquals(middleNew.size, endNew.size)
                assertEquals(composer, scene.composerBounds(), "Incoming content cannot displace the fixed editor.")
                fixture.assertAtBottom(scene, 13, "incoming reveal completed")
                repeat(3) { offset ->
                    fixture.event(ChatEvent.NewMessage(ChatMessage(14L + offset, 10, text = "连续到达 ${offset + 1}")))
                }
                scene.settledFrame(8)
                fixture.assertAtBottom(scene, 16, "coalesced incoming reveal completed")
            }
        }
    }

    @Test
    fun twentyEightOwnSendsKeepTheNewestBubbleAboveTheComposerAcrossDeliveryAndResize() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(3, mode, density, heightDp = 300)
            fixture.scene().use { scene ->
                scene.settledFrame()
                fixture.assertAtBottom(scene, fixture.snapshot.messages.last().id, "initial")
                repeat(28) { index ->
                    val lines = when (index % 4) { 0 -> 1; 1 -> 3; 2 -> 6; else -> 18 }
                    val text = (1..lines).joinToString("\n") { "合成连续发送 ${index + 1} / $it" }
                    fixture.draft = TextFieldValue(text)
                    scene.settledFrame(4)
                    scene.click(scene.description("发送消息"))
                    scene.settledFrame(3)
                    assertEquals(index + 1, fixture.session.sends.size,
                        "The real send control must submit exactly once to the fake native session.")
                    assertTrue(fixture.snapshot.sendPending)
                    val pending = outgoing(10_000L + index * 2, text)
                    fixture.acknowledge(index, pending)
                    scene.settledFrame(10)
                    fixture.assertAtBottom(scene, pending.id, "accepted $index")

                    val delivered = pending.copy(id = pending.id + 1, delivery = MessageDelivery.SENT)
                    fixture.event(ChatEvent.SendSucceeded(delivered, pending.id))
                    scene.settledFrame(8)
                    fixture.assertAtBottom(scene, delivered.id, "delivered $index")
                    assertFalse(fixture.snapshot.messages.any { it.id == pending.id },
                        "A native oldMessageId replacement must leave one logical row.")
                    if (index == 11) {
                        fixture.widthDp = 380
                        fixture.heightDp = 680
                        val narrow = scene.settledFrame()
                        fixture.assertAtBottom(scene, delivered.id, "narrow resize")
                        fixture.export(narrow, "narrow")
                    } else if (index == 21) {
                        fixture.widthDp = 900
                        fixture.heightDp = 300
                        scene.settledFrame()
                        fixture.assertAtBottom(scene, delivered.id, "short resize")
                    }
                }
                assertEquals(31, fixture.snapshot.messages.size)
                assertTrue(fixture.snapshot.messages.takeLast(28).all { it.delivery == MessageDelivery.SENT })
                fixture.export(scene.settledFrame(1), "short")
            }
        }
    }

    @Test
    fun aRealWheelAwayDuringPendingSendSurvivesDeliveryBeforeAcknowledgementAndIncoming() {
        val fixture = Fixture(80, ColorSchemeMode.Light, 1f)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.scrollWithinLatestProximity(scene)
            val (nearAnchorId, nearAnchor) = fixture.centerReadingAnchor()
            val readingLineHeight = scene.textBounds("原有合成消息 $nearAnchorId").height
            fixture.event(ChatEvent.NewMessage(ChatMessage(81, 10, text = "轻移触控板后到达的合成内容")))
            scene.settledFrame(10)
            // The formerly last row gains natural group-tail space when another
            // native message arrives. That small layout change may move a reading
            // line, while a return to the bottom would move by a complete new row.
            fixture.assertAnchor(nearAnchorId, nearAnchor, "incoming after a small deliberate wheel movement",
                tolerance = readingLineHeight)
            assertTrue(scene.timelineScrollValue() > 0f,
                "Incoming content must not erase a small deliberate displacement from the real latest edge.")
            assertNotNull(scene.latestButtonBounds().singleOrNull(),
                "The loaded incoming row remains below the retained reading position.")
            fixture.draft = TextFieldValue("延迟确认的合成消息")
            scene.settledFrame(4)
            scene.click(scene.description("发送消息"))
            scene.settledFrame(3)
            val pending = outgoing(101, fixture.draft.text)
            fixture.event(ChatEvent.NewMessage(pending))
            scene.settledFrame(10)
            fixture.assertAtBottom(scene, pending.id, "native update before acknowledgement")

            fixture.scrollToOlder(scene)
            val (anchorId, anchor) = fixture.centerReadingAnchor()
            val before = scene.timelineScrollValue()
            val delivered = pending.copy(id = 1_001, delivery = MessageDelivery.SENT)
            fixture.event(ChatEvent.SendSucceeded(delivered, pending.id))
            scene.settledFrame(8)
            fixture.assertAnchor(anchorId, anchor, "delivery while reading older messages")
            fixture.acknowledge(0, pending)
            scene.settledFrame(10)
            fixture.assertAnchor(anchorId, anchor, "late stale acknowledgement")
            fixture.event(ChatEvent.NewMessage(ChatMessage(5_000, 10, text = "新到的合成内容")))
            scene.settledFrame(10)
            fixture.assertAnchor(anchorId, anchor, "incoming while reading older messages")
            assertTrue(scene.timelineScrollValue() >= before,
                "The reader must remain away from the latest edge after native insertions.")
            assertNotNull(scene.latestButtonBounds().singleOrNull())
            assertFalse(fixture.snapshot.messages.any { it.id == pending.id })
        }
    }

    @Test
    fun explicitlySendingWhileReadingOlderMessagesReturnsToTheRealLatestEdge() {
        val fixture = Fixture(80, ColorSchemeMode.Dark, 1.25f)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.scrollToOlder(scene)
            assertNotNull(scene.latestButtonBounds().singleOrNull())
            fixture.draft = TextFieldValue("明确发送后回到末尾\n第二行\n第三行")
            scene.settledFrame(4)
            scene.click(scene.description("发送消息"))
            scene.settledFrame(10)
            assertTrue(fixture.snapshot.sendPending)
            val pending = outgoing(101, fixture.draft.text)
            fixture.acknowledge(0, pending)
            scene.settledFrame(10)
            fixture.assertAtBottom(scene, pending.id, "explicit send from older reading position")
        }
    }

    @Test
    fun anInactivePageCannotSubmitOrAcquireFollowingFromALateNativeSend() {
        val fixture = Fixture(80, ColorSchemeMode.Light, 1f)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.draft = TextFieldValue("离场之前的合成消息")
            scene.settledFrame(4)
            scene.click(scene.description("发送消息"))
            scene.settledFrame(3)
            fixture.scrollToOlder(scene)
            val (anchorId, anchor) = fixture.centerReadingAnchor()
            fixture.pageInteractive = false
            scene.settledFrame(4)
            fixture.acknowledge(0, outgoing(101, fixture.draft.text))
            scene.settledFrame(10)
            fixture.assertAnchor(anchorId, anchor, "acknowledgement after page departure")
            fixture.draft = TextFieldValue("离场后的编辑不可发送")
            scene.settledFrame(4)
            scene.click(scene.description("发送消息"))
            scene.settledFrame(4)
            assertEquals(1, fixture.session.sends.size,
                "Retained outgoing page pixels do not authorize another native send.")
            fixture.assertAnchor(anchorId, anchor, "inactive composer click")
        }
    }

    private inner class Fixture(
        count: Int,
        val mode: ColorSchemeMode,
        val density: Float,
        heightDp: Int = 700,
    ) {
        var snapshot by mutableStateOf(ChatSnapshot())
        var draft by mutableStateOf(TextFieldValue())
        var widthDp by mutableStateOf(900)
        var heightDp by mutableStateOf(heightDp)
        var pageInteractive by mutableStateOf(true)
        val measured = ConcurrentHashMap<Long, Rect>()
        val session = FakeSession((1L..count.toLong()).map { ChatMessage(it, 10, text = "原有合成消息 $it") })
        private val queue = ArrayDeque<() -> Unit>()
        val controller = ChatController(1, session, queue::addLast, { snapshot = it })

        init {
            onActor {
                controller.start()
                controller.event(ChatEvent.NewChat(ChatItem(10, "Synthetic outgoing follow", order = 100)))
                controller.select(snapshot.token, 10)
                flush()
            }
        }

        private fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun event(event: ChatEvent) = onActor { controller.event(event); flush() }
        fun acknowledge(index: Int, message: ChatMessage) = onActor {
            session.sends[index].done(ChatResult(message))
            flush()
            draft = TextFieldValue()
        }
        fun scene() = ComposePixelScene((900 * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(widthDp.dp, heightDp.dp),
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.width(widthDp.dp).height(heightDp.dp)) {
                        ConversationPane(snapshot, snapshot.selected!!, draft, { draft = it }, null,
                            onLoadOlder = {}, onSend = {
                                controller.sendText(snapshot.token, draft.text)
                                flush()
                            }, onRetry = {}, onMarkRead = {}, features = ChatFeatureActions(), active = pageInteractive,
                            onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {}, showInfoButton = false,
                            pageInteractive = pageInteractive)
                    }
                }
            }
        }

        fun assertAtBottom(scene: ComposePixelScene, id: Long, stage: String) {
            val row = assertNotNull(measured[id], "The real newest row must be laid out: $stage, id=$id")
            val composer = scene.composerBounds()
            val roundedGap = (8f * density).roundToInt()
            val messageGap = (2f * density).roundToInt()
            val blurTop = composer.top - roundedGap - (ConversationComposerBlurFeather.value * density).roundToInt()
            assertTrue(row.bottom <= blurTop - messageGap + 1f,
                "The complete newest row must clear the actual composer and feather: $stage, $row vs $composer, blur=$blurTop")
            assertTrue(row.bottom >= blurTop - messageGap - 4f * density,
                "The newest row must actually occupy the bottom edge rather than remain far above it: $stage, $row, blur=$blurTop")
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "The real reverse LazyColumn must remain attached to its end: $stage")
            assertTrue(scene.latestButtonBounds().isEmpty(), "No return action remains while following: $stage")
        }

        fun scrollToOlder(scene: ComposePixelScene) {
            // Compose's desktop wheel sign depends on the reverse-layout event adapter.
            // Choose by the actual movement, never by an inferred LazyList index.
            for (delta in listOf(-3f, 3f)) {
                repeat(4) {
                    scene.scrollAt(420f * density, 350f * density, deltaY = delta)
                    scene.settledFrame(8)
                    if (scene.timelineScrollValue() >= 8f) return
                }
            }
            assertTrue(scene.timelineScrollValue() >= 8f, "Real wheel input must move into older rows.")
        }

        fun scrollWithinLatestProximity(scene: ComposePixelScene) {
            val lastId = snapshot.messages.last().id
            val resting = assertNotNull(measured[lastId])
            var moved = resting
            for (delta in listOf(-0.2f, 0.2f)) {
                scene.scrollAt(420f * density, 350f * density, deltaY = delta)
                scene.settledFrame(8)
                moved = assertNotNull(measured[lastId])
                if (moved.bottom > resting.bottom + 2f) break
            }
            val displacement = (moved.bottom - resting.bottom) / density
            assertTrue(displacement in 2f..65f,
                "The actual touchpad-sized wheel movement must leave the latest edge without reaching the return-button threshold: $displacement")
            assertTrue(scene.latestButtonBounds().isEmpty(),
                "A deliberately small displacement remains inside the separate 72dp return-button threshold.")
        }

        fun centerReadingAnchor(): Pair<Long, Rect> = assertNotNull(measured.entries
            .filter { it.value.top > 110f * density && it.value.bottom < 550f * density }
            .minByOrNull { abs(it.value.center.y - 330f * density) }
            ?.let { it.key to it.value }, "A real fully visible middle row must anchor older reading.")

        fun assertAnchor(id: Long, expected: Rect, stage: String, tolerance: Float = density) {
            val actual = assertNotNull(measured[id], "Reading row must stay composed: $stage")
            assertTrue(abs(actual.top - expected.top) <= tolerance && abs(actual.bottom - expected.bottom) <= tolerance,
                "Native updates must preserve the actual reading line after deliberate wheel input: $stage, $expected -> $actual")
        }

        fun export(frame: ComposePixelFrame, layout: String) {
            val directory = File(System.getProperty("java.io.tmpdir"), "conversation-outgoing-follow").apply { mkdirs() }
            val width = (widthDp * density).roundToInt()
            val height = (heightDp * density).roundToInt()
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            // The canvas also hosts unused space while resizing the actual Pane.
            // Export only that Pane's measured client area, preserving native density.
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, frame[x, y])
            val theme = if (mode == ColorSchemeMode.Light) "light" else "dark"
            val scale = if (density == 1f) "1x" else "1.25x"
            assertTrue(ImageIO.write(image, "png", File(directory, "$theme-$scale-$layout.png")))
        }
    }

    private data class Send(val done: (ChatResult<ChatMessage>) -> Unit)
    private class FakeSession(private val initial: List<ChatMessage>) : TdChatSession {
        val sends = mutableListOf<Send>()
        override fun loadChats(limit: Int, result: (ChatResult<ChatLoadResult>) -> Unit) = result(ChatResult(ChatLoadResult(true)))
        override fun history(chatId: Long, fromMessageId: Long, limit: Int, result: (ChatResult<List<ChatMessage>>) -> Unit) =
            result(ChatResult(if (fromMessageId == 0L) initial else emptyList()))
        override fun open(chatId: Long) = Unit
        override fun close(chatId: Long) = Unit
        override fun sendText(chatId: Long, text: String, result: (ChatResult<ChatMessage>) -> Unit) { sends += Send(result) }
        override fun retry(chatId: Long, messageId: Long, result: (ChatResult<List<ChatMessage>>) -> Unit) = Unit
        override fun markRead(chatId: Long, messageIds: List<Long>, result: (ChatResult<Unit>) -> Unit) = Unit
    }

    private fun ComposePixelScene.composerBounds() = assertNotNull(semanticsBounds {
        "消息输入区域" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull())
    private fun ComposePixelScene.description(label: String) = assertNotNull(semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
            it.getOrNull(SemanticsProperties.Role) == Role.Button
    }.singleOrNull(), "Missing real composer action $label")
    private fun ComposePixelScene.textBounds(text: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull(), "Missing real message text $text")
    private fun ComposePixelScene.latestButtonBounds() = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("回到最新消息") }
    }
    private fun ComposePixelScene.timelineScrollValue(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        // A short initial history legitimately fits without exposing a scroll range.
        return values.maxOrNull() ?: 0f
    }
    private fun ComposePixelScene.click(rect: Rect) {
        moveTo(rect.center.x, rect.center.y)
        pressAt(rect.center.x, rect.center.y)
        releaseAt(rect.center.x, rect.center.y)
    }
    companion object {
        private fun outgoing(id: Long, text: String) = ChatMessage(id, 10, outgoing = true,
            text = text, delivery = MessageDelivery.PENDING)
        private fun <T> onActor(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            val task = FutureTask(Callable(action))
            SwingUtilities.invokeAndWait(task)
            return task.get()
        }
    }
}
