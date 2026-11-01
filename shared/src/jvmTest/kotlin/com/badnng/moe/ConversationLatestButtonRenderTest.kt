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

/** Real Pane geometry and pointer input with synthetic native snapshots; no account or media. */
class ConversationLatestButtonRenderTest {
    @Test
    fun anAppliedJumpAtTheRealLatestEdgeDoesNotKeepTheButtonAcrossThemesDensitiesAndTallRows() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (density in listOf(1f, 1.25f)) {
                for (tallLatest in listOf(false, true)) {
                    val fixture = Fixture(mode, density, tallLatest = tallLatest, jumpId = 80)
                    fixture.scene().use { scene ->
                        val initial = scene.settledFrame()
                        fixture.assertAtBottom(scene, "applied initial jump")
                        if (!tallLatest) fixture.export(initial, "initial-bottom")
                        assertEquals(80L, fixture.snapshot.jumpMessageId,
                            "The test must retain the native jump identity after its UI placement.")

                        fixture.appendIncoming(tall = tallLatest)
                        scene.settledFrame()
                        fixture.assertAtBottom(scene, "incoming immediately after initial jump reached latest")

                        fixture.scrollToOlder(scene)
                        val button = assertNotNull(scene.latestButtonBounds().singleOrNull())
                        if (!tallLatest) fixture.export(scene.settledFrame(1), "away-from-bottom")
                        scene.click(button)
                        val returned = scene.settledFrame()
                        fixture.assertAtBottom(scene, "real local return action")
                        if (!tallLatest) fixture.export(returned, "clicked-back-to-bottom")
                        assertTrue(fixture.latestRequests.isEmpty(),
                            "An applied jump with no native history gap returns within the loaded window.")
                        assertEquals(80L, fixture.snapshot.jumpMessageId)

                        fixture.appendIncoming()
                        scene.settledFrame()
                        fixture.assertAtBottom(scene, "incoming after explicit local return")
                        assertNull(fixture.memory.takeEntryPosition(10),
                            "The real latest edge must be remembered as latest even while the applied jump ID remains.")

                        fixture.widthDp = 380
                        fixture.heightDp = 420
                        scene.settledFrame()
                        fixture.assertAtBottom(scene, "narrow resize after returning")
                        fixture.snapshot = fixture.snapshot.copy(readPending = true)
                        scene.settledFrame(4)
                        fixture.assertAtBottom(scene, "unrelated native snapshot update")
                    }
                }
            }
        }
    }

    @Test
    fun nativeJumpCompletionIsOwnedByItsTokenAndRevisionAndWaitsForTheRealLatestEdge() {
        for (jumpId in listOf(80L, 78L)) {
            val fixture = Fixture(ColorSchemeMode.Light, 1f, jumpId = jumpId, emulateComplete = true)
            fixture.scene().use { scene ->
                scene.settledFrame()
                if (jumpId == 78L) {
                    assertTrue(scene.timelineScrollValue() > 0f)
                    assertTrue(fixture.completedJumps.isEmpty(),
                        "Laying out an earlier jump must not complete the native navigation boundary.")
                    assertEquals(jumpId, fixture.snapshot.jumpMessageId)
                    fixture.appendIncoming()
                    scene.settledFrame()
                    assertTrue(fixture.completedJumps.isEmpty(),
                        "A native arrival does not complete the earlier jump while the reader stays away.")
                    fixture.scrollToLatest(scene)
                    scene.settledFrame(4)
                }
                fixture.assertAtBottom(scene, "native navigation completion, initial jump=$jumpId")
                fixture.export(scene.settledFrame(1), "native-completion-jump-$jumpId")
                assertEquals(listOf(fixture.snapshot.token to 1L), fixture.completedJumps.toList(),
                    "The completed layout must acknowledge exactly its current native token and jump revision. " +
                        "Initial jump=$jumpId, current jump=${fixture.snapshot.jumpMessageId}, " +
                        "revision=${fixture.snapshot.jumpRevision}, scroll=${scene.timelineScrollValue()}, " +
                        "interactive=${fixture.pageInteractive}, newest=${fixture.snapshot.messages.last().id}, " +
                        "buttons=${scene.latestButtonBounds().size}, diagnostics=${fixture.latestDiagnostics}")
                assertNull(fixture.snapshot.jumpMessageId,
                    "The fake native boundary only clears its matching completed jump.")
                fixture.appendIncoming()
                scene.settledFrame()
                fixture.assertAtBottom(scene, "incoming after native marker completion")
                assertEquals(1, fixture.completedJumps.size,
                    "Unrelated native arrivals must not complete the same navigation twice.")
            }
        }
    }

    @Test
    fun anEnteringSingleColumnPageCompletesItsLatestJumpOnlyAfterItBecomesInteractive() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f, jumpId = 80, emulateComplete = true)
        fixture.widthDp = 380
        fixture.pageInteractive = false
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertTrue(fixture.completedJumps.isEmpty(),
                "The incoming single-column page cannot complete native navigation while its transition gates input.")
            assertEquals(80L, fixture.snapshot.jumpMessageId)
            fixture.pageInteractive = true
            scene.settledFrame()
            fixture.assertAtBottom(scene, "single-column transition became interactive")
            assertEquals(listOf(fixture.snapshot.token to 1L), fixture.completedJumps.toList())
            assertNull(fixture.snapshot.jumpMessageId)
            fixture.appendIncoming()
            scene.settledFrame()
            fixture.assertAtBottom(scene, "incoming immediately after single-column entry")
        }
    }

    @Test
    fun anEarlierAppliedJumpKeepsItsReadingPositionUntilARealWheelReachesLatest() {
        val fixture = Fixture(ColorSchemeMode.Dark, 1.25f, jumpId = 78)
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertTrue(scene.timelineScrollValue() > 0f,
                "Applying an earlier native jump must not immediately acquire bottom following.")
            assertNotNull(scene.latestButtonBounds().singleOrNull())
            val anchor = assertNotNull(fixture.measured[78])
            fixture.appendIncoming()
            scene.settledFrame()
            val after = assertNotNull(fixture.measured[78])
            assertTrue(abs(anchor.top - after.top) <= fixture.density &&
                abs(anchor.bottom - after.bottom) <= fixture.density,
                "A native arrival cannot pull the reader away from the already applied earlier jump: $anchor -> $after")

            fixture.scrollToLatest(scene)
            fixture.assertAtBottom(scene, "real wheel return")
            fixture.appendIncoming()
            scene.settledFrame()
            fixture.assertAtBottom(scene, "incoming after wheel reached latest")
            assertTrue(fixture.latestRequests.isEmpty())
            assertEquals(78L, fixture.snapshot.jumpMessageId)
        }
    }

    @Test
    fun aNativeNewerGapKeepsTheButtonAtTheLoadedWindowBottomAndRequestsTheRealLatestWindow() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val fixture = Fixture(mode, 1.25f, hasNewer = true)
            fixture.scene().use { scene ->
                val gap = scene.settledFrame()
                assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                    "The current loaded window is physically at its bottom.")
                fixture.export(gap, "native-newer-gap")
                scene.click(assertNotNull(scene.latestButtonBounds().singleOrNull(),
                    "A native newer-history gap still needs a return action at that physical bottom."))
                scene.settledFrame(4)
                assertEquals(listOf(fixture.snapshot.token), fixture.latestRequests.toList(),
                    "The native gap must request the real latest window, not just scroll the old window.")
                assertNotNull(scene.latestButtonBounds().singleOrNull(),
                    "The action remains until a native snapshot actually closes the gap.")
            }
        }
    }

    @Test
    fun aStillPendingJumpRemainsAnExplicitReturnActionAtTheProvisionalBottom() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f, jumpId = 40, loading = true)
        fixture.scene().use { scene ->
            scene.settledFrame()
            assertEquals(0f, scene.timelineScrollValue(), 0.001f)
            scene.click(assertNotNull(scene.latestButtonBounds().singleOrNull(),
                "A not-yet-applied native jump cannot be mistaken for an ordinary latest position."))
            scene.settledFrame(4)
            assertEquals(listOf(fixture.snapshot.token), fixture.latestRequests.toList())
        }
    }

    @Test
    fun anOrdinaryLatestWindowWithoutAJumpHasNoReturnAction() {
        for ((mode, density) in listOf(ColorSchemeMode.Light to 1f, ColorSchemeMode.Dark to 1.25f)) {
            val fixture = Fixture(mode, density)
            fixture.scene().use { scene ->
                scene.settledFrame()
                fixture.assertAtBottom(scene, "ordinary latest window")
                fixture.appendIncoming()
                scene.settledFrame()
                fixture.assertAtBottom(scene, "ordinary native incoming")
            }
        }
    }

    private inner class Fixture(
        private val mode: ColorSchemeMode,
        val density: Float,
        tallLatest: Boolean = false,
        jumpId: Long? = null,
        hasNewer: Boolean = false,
        loading: Boolean = false,
        private val emulateComplete: Boolean = false,
    ) {
        private val chat = ChatItem(10, "Synthetic latest-button regression")
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
            selected = chat, items = listOf(chat), historyEnd = true,
            messages = (1L..80L).map { id ->
                ChatMessage(id, 10, text = if (id == 80L && tallLatest)
                    (1..48).joinToString("\n") { "Tall latest synthetic row $it" }
                else "Synthetic latest-button row $id")
            }, jumpMessageId = jumpId, jumpRevision = if (jumpId == null) 0 else 1,
            historyHasNewer = hasNewer, historyLoading = loading))
        var widthDp by mutableStateOf(900)
        var heightDp by mutableStateOf(700)
        var pageInteractive by mutableStateOf(true)
        val measured = ConcurrentHashMap<Long, Rect>()
        val latestRequests = CopyOnWriteArrayList<ChatToken>()
        val completedJumps = CopyOnWriteArrayList<Pair<ChatToken, Long>>()
        val latestDiagnostics = CopyOnWriteArrayList<String>()
        val memory = ConversationSessionMemory()

        fun scene() = ComposePixelScene((900 * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides desktopChatLayout(widthDp.dp, heightDp.dp),
                LocalConversationPreviewLatestState provides { latestDiagnostics += it },
                LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.width(widthDp.dp).height(heightDp.dp)) {
                        ConversationPane(snapshot, chat, TextFieldValue(), {}, null,
                            onLoadOlder = {}, onSend = {}, onRetry = {}, onMarkRead = {},
                            features = ChatFeatureActions(returnToLatest = { latestRequests += it },
                                completeHistoryJump = { token, revision ->
                                    if (emulateComplete) {
                                        assertEquals(snapshot.token, token,
                                            "Navigation completion must use the current native selection token.")
                                        assertEquals(snapshot.jumpRevision, revision,
                                            "Navigation completion must acknowledge the currently placed jump revision.")
                                        assertNotNull(snapshot.jumpMessageId,
                                            "Only a still-owned native jump can be completed.")
                                        completedJumps += token to revision
                                        snapshot = snapshot.copy(jumpMessageId = null)
                                    }
                                }), active = pageInteractive,
                            onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {}, showInfoButton = false,
                            sessionMemory = memory, pageInteractive = pageInteractive)
                    }
                }
            }
        }

        fun appendIncoming(tall: Boolean = false) {
            val id = snapshot.messages.last().id + 1
            snapshot = snapshot.copy(messages = snapshot.messages +
                ChatMessage(id, 10, text = if (tall)
                    (1..48).joinToString("\n") { "Tall synthetic native arrival $id / $it" }
                else "Synthetic native arrival $id", incomingAnimationId = id))
        }

        fun export(frame: ComposePixelFrame, stage: String) {
            val directory = File(System.getProperty("java.io.tmpdir"), "conversation-latest-button").apply { mkdirs() }
            val width = (widthDp * density).roundToInt()
            val height = (heightDp * density).roundToInt()
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, frame[x, y])
            val theme = if (mode == ColorSchemeMode.Light) "light" else "dark"
            val scale = if (density == 1f) "1x" else "1.25x"
            assertTrue(ImageIO.write(image, "png", File(directory, "$theme-$scale-$stage.png")))
        }

        fun assertAtBottom(scene: ComposePixelScene, stage: String) {
            val latest = assertNotNull(measured[snapshot.messages.last().id],
                "The actual newest row must be composed: $stage")
            val composer = assertNotNull(scene.semanticsBounds {
                "消息输入区域" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.singleOrNull())
            val blurTop = composer.top - (8f * density).roundToInt() -
                (ConversationComposerBlurFeather.value * density).roundToInt()
            val expectedBottom = blurTop - (2f * density).roundToInt()
            assertTrue(latest.bottom in (expectedBottom - 4f * density)..(expectedBottom + 1f),
                "The real newest row must occupy the edge above the composer and feather: $stage, $latest vs $composer")
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "The real reverse LazyColumn must be at its end: $stage")
            assertTrue(scene.latestButtonBounds().isEmpty(),
                "An applied jump identity cannot keep a return action at the actual latest edge: $stage")
        }

        fun scrollToOlder(scene: ComposePixelScene) {
            for (delta in listOf(-3f, 3f)) {
                repeat(4) {
                    scene.scrollAt(420f * density, 350f * density, deltaY = delta)
                    scene.settledFrame(8)
                    if (scene.timelineScrollValue() > 0f && scene.latestButtonBounds().isNotEmpty()) return
                }
            }
            assertTrue(scene.timelineScrollValue() > 0f && scene.latestButtonBounds().isNotEmpty(),
                "Real wheel input must leave the latest-button proximity, including within one very tall newest row.")
        }

        fun scrollToLatest(scene: ComposePixelScene) {
            val before = scene.timelineScrollValue()
            scene.scrollAt(420f * density, 350f * density, deltaY = -8f)
            scene.settledFrame(8)
            val delta = if (scene.timelineScrollValue() < before) -8f else 8f
            repeat(24) {
                if (scene.timelineScrollValue() == 0f) return
                scene.scrollAt(420f * density, 350f * density, deltaY = delta)
                scene.settledFrame(8)
            }
            assertEquals(0f, scene.timelineScrollValue(), 0.001f,
                "Real wheel input must be able to reach the latest loaded edge.")
        }
    }

    private fun ComposePixelScene.latestButtonBounds() = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label ->
                label.startsWith("回到最新消息")
            }
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
