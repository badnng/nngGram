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
import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** The real Pane, real pointer dispatch and synthetic native navigation; no account or media. */
class ConversationReplyLatestButtonRenderTest {
    @Test
    fun theSingleDownButtonRestoresTheOriginalMeasuredOffsetThenReturnsToLatest() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                val origin = ConversationReadPosition(10, 65, 13)
                val fixture = Fixture(mode, density, initialPosition = origin)
                fixture.scene().use { scene ->
                    fixture.export(scene.settledFrame(), "original-offset")
                    val original = assertNotNull(fixture.measured[origin.messageId])
                    fixture.followReply(origin)
                    fixture.export(scene.settledFrame(), "reply-destination")
                    scene.assertNoSeparateReturnCapsule()
                    assertTrue(scene.timelineScrollValue() > 0f)

                    scene.click(scene.singleDownButton())
                    fixture.export(scene.settledFrame(), "returned-offset")
                    assertEquals(listOf(ReturnRequest(fixture.snapshot.token, 10, 65)), fixture.returns.toList())
                    assertNull(fixture.memory.replyReturn, "The first real click consumes only the reply return.")
                    assertNull(fixture.memory.requestedPosition(10), "Applying the native return releases its pending offset.")
                    val restored = assertNotNull(fixture.measured[origin.messageId])
                    assertTrue(abs(original.top - restored.top) <= 1f && abs(original.bottom - restored.bottom) <= 1f,
                        "The same actual message row must regain its pixel offset: $original -> $restored, density=$density")
                    assertTrue(fixture.latestRequests.isEmpty(), "The first click must not request the latest window.")
                    scene.assertNoSeparateReturnCapsule()

                    scene.click(scene.singleDownButton())
                    fixture.export(scene.settledFrame(), "second-click-latest")
                    assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                        "The second real click returns to the actual bottom of the loaded window.")
                    assertTrue(scene.downButtonBounds().isEmpty())
                    assertTrue(fixture.latestRequests.isEmpty(), "A complete loaded window needs no native replacement.")
                    assertEquals(1, fixture.returns.size)
                }
            }
        }
    }

    @Test
    fun aReplyReturnKeepsTheDownButtonAvailableEvenAtTheActualLatestEdge() {
        val fixture = Fixture(ColorSchemeMode.Light, 1.25f)
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f)
            assertTrue(scene.downButtonBounds().isEmpty())
            fixture.memory.pushReplyReturn(10, ConversationReadPosition(10, 40, 17))
            fixture.export(scene.settledFrame(), "latest-with-return")
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "Adding a reply return must not itself move the bottom-following timeline.")
            scene.assertNoSeparateReturnCapsule()
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(listOf(ReturnRequest(fixture.snapshot.token, 10, 40)), fixture.returns.toList())
            assertNull(fixture.memory.replyReturn)
            assertTrue(scene.timelineScrollValue() > 0f)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f)
            assertTrue(scene.downButtonBounds().isEmpty())
        }
    }

    @Test
    fun afterReturningAcrossANativeNewerGapTheSecondClickRequestsTheLatestWindow() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1.25f, initialPosition = ConversationReadPosition(10, 40, 0),
            hasNewer = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.memory.pushReplyReturn(10, ConversationReadPosition(10, 65, 11))
            scene.settledFrame(4)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(1, fixture.returns.size)
            assertTrue(fixture.latestRequests.isEmpty())
            assertTrue(fixture.snapshot.historyHasNewer)
            scene.click(scene.singleDownButton())
            scene.settledFrame(4)
            assertEquals(listOf(fixture.snapshot.token), fixture.latestRequests.toList(),
                "The second action uses the real native latest command when the loaded window has a gap.")
            assertEquals(1, fixture.returns.size)
            assertNotNull(scene.downButtonBounds().singleOrNull(),
                "The gap remains actionable until the native latest snapshot actually arrives.")
        }
    }

    @Test
    fun theSharedButtonHonorsThePageGateAndTheWorkspaceReturnCallback() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1f, initialPosition = ConversationReadPosition(10, 40, 0),
            delegateReturn = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val origin = ConversationReadPosition(10, 65, 19)
            fixture.memory.pushReplyReturn(10, origin)
            fixture.pageInteractive = false
            scene.settledFrame()
            scene.click(scene.singleDownButton())
            scene.settledFrame(4)
            assertEquals(origin, fixture.memory.replyReturn?.position,
                "An outgoing or entering page cannot consume the workspace's return stack.")
            assertTrue(fixture.returns.isEmpty())
            assertEquals(0, fixture.delegatedReturns)
            fixture.pageInteractive = true
            scene.settledFrame(4)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(1, fixture.delegatedReturns)
            assertEquals(listOf(ReturnRequest(fixture.snapshot.token, 10, 65)), fixture.returns.toList())
            assertNull(fixture.memory.replyReturn)
        }
    }

    @Test
    fun aFailedNativeReturnReleasesItsOffsetAndLeavesTheLatestActionUsable() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f, initialPosition = ConversationReadPosition(10, 40, 0),
            failReturn = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.memory.pushReplyReturn(10, ConversationReadPosition(10, 65, 9))
            scene.settledFrame(4)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(ChatError.REQUEST_FAILED, fixture.snapshot.historyError)
            assertEquals(1L, fixture.snapshot.navigationFailureRevision)
            assertNull(fixture.memory.requestedPosition(10), "Failure cannot leave the Pane waiting for an invalid anchor.")
            assertNull(fixture.memory.replyReturn)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "The ordinary latest action still works after native return validation fails.")
            assertTrue(scene.downButtonBounds().isEmpty())
            assertEquals(1, fixture.returns.size)
        }
    }

    @Test
    fun anotherChatsReturnOwnerIsNeverConsumedByThisPagesDownButton() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1.25f, initialPosition = ConversationReadPosition(10, 40, 0))
        fixture.scene().use { scene ->
            scene.settledFrame()
            val origin = ConversationReadPosition(30, 65, 23)
            fixture.memory.pushReplyReturn(20, origin)
            scene.settledFrame(4)
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f)
            assertEquals(20L, fixture.memory.replyReturn?.destinationChatId)
            assertEquals(origin, fixture.memory.replyReturn?.position)
            assertTrue(fixture.returns.isEmpty())
            assertTrue(scene.downButtonBounds().isEmpty(),
                "Another chat's stack must not expose an unrelated action at this page's latest edge.")
        }
    }

    @Test
    fun repeatedPointerClicksCannotConsumeAnotherNestedReturnBeforeTheDelegatedResultArrives() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f, initialPosition = ConversationReadPosition(10, 40, 0),
            delegateReturn = true, deferReturn = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            val earlier = ConversationReadPosition(10, 70, 7)
            val latest = ConversationReadPosition(10, 65, 13)
            fixture.memory.pushReplyReturn(10, earlier)
            fixture.memory.pushReplyReturn(10, latest)
            scene.settledFrame(4)
            val button = scene.singleDownButton()
            scene.click(button)
            scene.click(button) // No frame/recomposition or native response between real pointer clicks.
            scene.settledFrame(4)
            assertEquals(1, fixture.delegatedReturns,
                "The pending guard must exist synchronously before delegating to the workspace.")
            assertEquals(listOf(ReturnRequest(fixture.snapshot.token, 10, 65)), fixture.returns.toList())
            assertEquals(earlier, fixture.memory.replyReturn?.position,
                "The previous nested return stays owned until this native return actually completes.")
            assertEquals(latest, fixture.memory.requestedPosition(10))
            assertTrue(fixture.latestRequests.isEmpty())
            fixture.completeDeferredReturn()
            scene.settledFrame()
            assertNull(fixture.memory.requestedPosition(10))
            assertEquals(earlier, fixture.memory.replyReturn?.position)
            assertNotNull(fixture.measured[65])
        }
    }

    @Test
    fun deletionBeforeReturnPlacementReleasesThePendingOffsetWithoutNeedingANativeError() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1.25f, initialPosition = ConversationReadPosition(10, 40, 0),
            delegateReturn = true, deferReturn = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            fixture.memory.pushReplyReturn(10, ConversationReadPosition(10, 65, 13))
            scene.settledFrame(4)
            scene.click(scene.singleDownButton())
            scene.settledFrame(4)
            assertNotNull(fixture.memory.requestedPosition(10))
            fixture.completeDeferredReturn(deleteTarget = true)
            scene.settledFrame()
            assertNull(fixture.snapshot.historyError,
                "Native deletion is a successful newer snapshot, rather than a synthetic request failure.")
            assertNull(fixture.snapshot.jumpMessageId)
            assertTrue(fixture.snapshot.messages.none { it.id == 65L })
            assertNull(fixture.memory.requestedPosition(10),
                "A newer native jump revision cannot leave a deleted return anchor waiting for placement.")
            scene.click(scene.singleDownButton())
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "The shared down button must regain its ordinary latest action after target deletion.")
            assertTrue(scene.downButtonBounds().isEmpty())
            assertEquals(1, fixture.delegatedReturns)
            assertEquals(1, fixture.returns.size)
        }
    }

    @Test
    fun aCrossChatReturnRetainsItsRealRevisionAcrossAnIdleEmptyEntryBeforeTheNativeWindowArrives() {
        val origin = ConversationReadPosition(10, 65, 13)
        val reference = Fixture(ColorSchemeMode.Dark, 1.25f, initialPosition = origin)
        val expected = reference.scene().use { scene ->
            scene.settledFrame()
            assertNotNull(reference.measured[origin.messageId])
        }
        for (deleteTarget in listOf(false, true)) {
            val fixture = Fixture(ColorSchemeMode.Dark, 1.25f)
            val loadedMessages = fixture.snapshot.messages
            fixture.memory.pushReplyReturn(20, origin)
            assertEquals(origin, fixture.memory.beginReplyReturn(20, jumpRevision = 7))
            fixture.snapshot = fixture.snapshot.copy(token = ChatToken(1, 2, 10), messages = emptyList(),
                jumpMessageId = null, jumpRevision = 7, historyLoading = false, historyEnd = false)
            fixture.scene().use { scene ->
                scene.settledFrame()
                assertEquals(7L, fixture.memory.requestedJumpRevision(10),
                    "An idle empty snapshot on the new token has not acknowledged the return command.")
                fixture.snapshot = fixture.snapshot.copy(
                    messages = if (deleteTarget) loadedMessages.filterNot { it.id == 65L } else loadedMessages,
                    jumpMessageId = origin.messageId.takeUnless { deleteTarget }, jumpRevision = 8,
                    historyHasNewer = deleteTarget, historyEnd = true)
                scene.settledFrame()
                assertNull(fixture.memory.requestedJumpRevision(10),
                    "Actual placement or a newer native deletion releases the revision metadata.")
                if (!deleteTarget) {
                    val restored = assertNotNull(fixture.measured[65])
                    assertTrue(abs(expected.top - restored.top) <= 1f && abs(expected.bottom - restored.bottom) <= 1f,
                        "The cross-chat return must preserve its 13 px offset through the idle entry: $expected -> $restored")
                    scene.click(scene.singleDownButton())
                    scene.settledFrame()
                    assertEquals(0f, scene.timelineScrollValue(), 0.001f)
                    assertTrue(scene.downButtonBounds().isEmpty())
                } else {
                    assertNull(fixture.snapshot.historyError)
                    scene.click(scene.singleDownButton())
                    scene.settledFrame(4)
                    assertEquals(listOf(fixture.snapshot.token), fixture.latestRequests.toList(),
                        "A combined loaded-and-deleted snapshot must release the guard before the real latest click.")
                    fixture.snapshot = fixture.snapshot.copy(historyHasNewer = false,
                        jumpMessageId = 80, jumpRevision = 9)
                    scene.settledFrame()
                    assertEquals(0f, scene.timelineScrollValue(), 0.001f)
                    assertTrue(scene.downButtonBounds().isEmpty())
                }
            }
        }
    }

    private data class ReturnRequest(val token: ChatToken, val chatId: Long, val messageId: Long)

    private inner class Fixture(
        private val mode: ColorSchemeMode,
        val density: Float,
        initialPosition: ConversationReadPosition? = null,
        hasNewer: Boolean = false,
        private val delegateReturn: Boolean = false,
        private val failReturn: Boolean = false,
        private val deferReturn: Boolean = false,
    ) {
        private val chat = ChatItem(10, "Synthetic reply return regression")
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = chat, items = listOf(chat), historyEnd = true,
            messages = (1L..80L).map { ChatMessage(it, 10, text = "Synthetic reply-return row $it") },
            jumpMessageId = initialPosition?.messageId, jumpRevision = if (initialPosition == null) 0 else 1,
            historyHasNewer = hasNewer))
        var pageInteractive by mutableStateOf(true)
        val memory = ConversationSessionMemory().apply { initialPosition?.let(::rememberPosition) }
        val measured = ConcurrentHashMap<Long, Rect>()
        val returns = CopyOnWriteArrayList<ReturnRequest>()
        val latestRequests = CopyOnWriteArrayList<ChatToken>()
        var delegatedReturns = 0
        private var deferredRequest: ReturnRequest? = null

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(900.dp, 700.dp),
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.width(900.dp).height(700.dp)) {
                        val workspaceReturn: (() -> Unit)? = if (delegateReturn) ({
                            delegatedReturns++
                            memory.beginReplyReturn(10, snapshot.jumpRevision)?.let {
                                requestReturn(snapshot.token, it.chatId, it.messageId)
                            }
                        }) else null
                        ConversationPane(snapshot, chat, TextFieldValue(), {}, null,
                            onLoadOlder = {}, onSend = {}, onRetry = {}, onMarkRead = {},
                            features = ChatFeatureActions(returnToLatest = { latestRequests += it },
                                openReplyReturn = ::requestReturn), active = pageInteractive,
                            onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {}, showInfoButton = false,
                            sessionMemory = memory, pageInteractive = pageInteractive, onReplyReturn = workspaceReturn)
                    }
                }
            }
        }

        fun followReply(origin: ConversationReadPosition) {
            memory.pushReplyReturn(10, origin)
            snapshot = snapshot.copy(jumpMessageId = 40, jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = true)
        }

        private fun requestReturn(token: ChatToken, chatId: Long, messageId: Long) {
            assertEquals(snapshot.token, token)
            val request = ReturnRequest(token, chatId, messageId)
            returns += request
            if (deferReturn) {
                deferredRequest = request
                return
            }
            snapshot = if (failReturn) snapshot.copy(historyError = ChatError.REQUEST_FAILED,
                navigationFailureRevision = snapshot.navigationFailureRevision + 1)
            else snapshot.copy(jumpMessageId = messageId, jumpRevision = snapshot.jumpRevision + 1,
                jumpHighlight = true, historyLoading = false, historyError = null)
        }

        fun completeDeferredReturn(deleteTarget: Boolean = false) {
            val request = assertNotNull(deferredRequest, "A real pointer click must have requested the delayed return.")
            deferredRequest = null
            snapshot = snapshot.copy(
                messages = if (deleteTarget) snapshot.messages.filterNot { it.id == request.messageId } else snapshot.messages,
                jumpMessageId = request.messageId.takeUnless { deleteTarget },
                jumpRevision = snapshot.jumpRevision + 1, jumpHighlight = !deleteTarget,
                historyLoading = false, historyError = null)
        }

        fun export(frame: ComposePixelFrame, stage: String) {
            val directory = File(System.getProperty("java.io.tmpdir"), "conversation-reply-latest-button").apply { mkdirs() }
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
            val theme = if (mode == ColorSchemeMode.Light) "light" else "dark"
            val scale = if (density == 1f) "1x" else "1.25x"
            assertTrue(ImageIO.write(image, "png", File(directory, "$theme-$scale-$stage.png")))
        }
    }

    private fun ComposePixelScene.downButtonBounds() = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("回到最新消息") }
    }

    private fun ComposePixelScene.singleDownButton() = assertNotNull(downButtonBounds().singleOrNull(),
        "One shared down-arrow action must remain available.")

    private fun ComposePixelScene.assertNoSeparateReturnCapsule() {
        assertTrue(semanticsBounds { configuration ->
            configuration.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "返回引用前的位置" }
        }.isEmpty(), "The removed standalone return capsule must not remain in the actual Pane.")
    }

    private fun ComposePixelScene.timelineScrollValue(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds {
            it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        return values.maxOrNull() ?: 0f
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }
}
