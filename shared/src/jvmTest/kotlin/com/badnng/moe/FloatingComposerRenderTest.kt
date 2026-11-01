package com.badnng.moe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.MessageKind
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MediaFile
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class FloatingComposerRenderTest {
    @Test
    fun theLastSenderBubbleAndReactionsStayCloseToTheComposerAsReplyAndEditGrow() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f, 1.3f)) for (widthDp in listOf(900, 380)) {
            var composerMode by mutableStateOf(0)
            val base = fixture()
            val last = base.messages.last().copy(senderId = 20, text = "末条合成内容",
                reactions = listOf(MessageReaction("👍", 37), MessageReaction("❤", 11)))
            val snapshot = base.copy(messages = base.messages.dropLast(1) + last,
                senderNames = mapOf(20L to "末条发件人"))
            val measured = java.util.concurrent.ConcurrentHashMap<Long, Rect>()
            ComposePixelScene((widthDp * density).roundToInt(), (700 * density).roundToInt()) {
                val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density),
                    LocalDesktopChatLayout provides desktopChatLayout(widthDp.dp, 700.dp),
                    LocalConversationPreviewMessageBounds provides { id, bounds ->
                        if (bounds == null) measured.remove(id) else measured[id] = bounds
                    }) {
                    MiuixTheme(controller = controller) {
                        val state = when (composerMode) {
                            1 -> snapshot.copy(replyTarget = snapshot.messages.first())
                            2 -> snapshot.copy(editTarget = snapshot.messages.first().copy(text = "编辑第一行\n编辑第二行\n编辑第三行"))
                            else -> snapshot
                        }
                        ConversationPane(state,
                            snapshot.selected!!, if (composerMode == 1) TextFieldValue("第一行\n第二行\n第三行") else TextFieldValue(),
                            {}, null, {}, {}, {}, {}, ChatFeatureActions(), false, {}, {}, {}, {}, false)
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                val initialComposer = scene.composerBounds()
                repeat(3) { phase ->
                    if (phase > 0) {
                        composerMode = phase
                        scene.settledFrame()
                        assertTrue(scene.composerBounds().height > initialComposer.height,
                            "The real multiline reply or edit form must increase the measured overlay height.")
                    }
                    val composer = scene.composerBounds()
                    val compactSpacing = (8f * density).roundToInt().toFloat()
                    val messageSpacing = (2f * density).roundToInt().toFloat()
                    val blurTop = composer.top - compactSpacing - (ConversationComposerBlurFeather.value * density).roundToInt()
                    val wholeMessage = assertNotNull(measured[last.id], "The actual message callback must include sender and reactions.")
                    assertTrue(wholeMessage.bottom <= blurTop - messageSpacing + 1f,
                        "The whole final message must clear the blur feather, not merely the text field: " +
                            "$widthDp/$density/$phase/$wholeMessage vs blurTop=$blurTop and input=$composer")
                    val inputGapDp = (composer.top - wholeMessage.bottom) / density
                    assertTrue(inputGapDp in 30f..38f,
                        "The final painted message must sit within the lowered 34dp gap of the real input, " +
                            "including the feather and breathing room: $widthDp/$density/$phase gap=$inputGapDp")
                    assertTrue(abs(composer.bottom - 680f * density) <= 1.5f,
                        "Only message content moves: the actual input must keep its original 20dp bottom placement.")
                    if (phase == 2) {
                        for (label in listOf("取消编辑", "保存修改")) {
                            val action = assertNotNull(scene.semanticsBounds {
                                label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                                    it.getOrNull(SemanticsProperties.Role) == Role.Button
                            }.singleOrNull(), "The shared field must expose the real $label action.")
                            assertTrue(action.top >= composer.top && action.bottom <= composer.bottom,
                                "Both real edit actions must remain inside the measured input region: $action vs $composer")
                        }
                    }
                    val sender = scene.textBounds("末条发件人")
                    assertTrue(sender.top >= wholeMessage.top && sender.bottom <= wholeMessage.bottom)
                    // The fallback glyph is centered inside the official 33dp sender circle.
                    val avatar = scene.textBounds("末")
                    assertTrue(avatar.top >= wholeMessage.top && avatar.bottom <= wholeMessage.bottom &&
                        avatar.center.y + 16.5f * density <= blurTop - messageSpacing + 1f,
                        "The complete sender avatar must remain above the blur as well.")
                    for ((emoji, count) in listOf("👍" to 37, "❤" to 11)) {
                        val reaction = assertNotNull(scene.semanticsBounds {
                            "$emoji $count 条回应" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                        }.singleOrNull(), "The final reaction must actually be composed.")
                        assertTrue(reaction.top >= wholeMessage.top && reaction.bottom <= wholeMessage.bottom + 1f)
                        assertTrue(reaction.bottom <= blurTop - messageSpacing + 1f,
                            "The actual reaction chip cannot remain inside the progressive blur.")
                        assertTrue(scene.textBounds(count.toString()).height > 0f, "Its count must actually remain visible.")
                    }
                }
            }
        }
    }

    @Test
    fun theLoweredLatestButtonAndUnreadBadgeKeepTheirShadowAndNativeReturnAction() {
        for (dark in listOf(false, true)) for (density in listOf(1f, 1.25f)) for (widthDp in listOf(900, 380)) {
            val snapshot = fixture().let { it.copy(selected = it.selected!!.copy(unreadCount = 7), historyHasNewer = true) }
            var returned: ChatToken? = null
            var expanded by mutableStateOf(false)
            ComposePixelScene((widthDp * density).roundToInt(), (700 * density).roundToInt()) {
                val controller = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
                CompositionLocalProvider(LocalDensity provides Density(density),
                    LocalDesktopChatLayout provides desktopChatLayout(widthDp.dp, 700.dp)) {
                    MiuixTheme(controller = controller) {
                        val state = when {
                            !expanded -> snapshot
                            widthDp == 900 -> snapshot.copy(replyTarget = snapshot.messages.first())
                            else -> snapshot.copy(editTarget = snapshot.messages.first().copy(text = "编辑第一行\n编辑第二行\n编辑第三行"))
                        }
                        val draft = if (expanded && widthDp == 900) TextFieldValue("第一行\n第二行\n第三行") else TextFieldValue()
                        ConversationPane(state, snapshot.selected!!, draft, {}, null,
                            {}, {}, {}, {}, ChatFeatureActions(returnToLatest = { returned = it }), false, {}, {}, {}, {}, false)
                    }
                }
            }.use { scene ->
                scene.moveTo(2f, 2f)
                val frame = scene.settledFrame()
                frame.exportPlacement("${if (dark) "dark" else "light"}-$density-$widthDp-latest-normal")
                val initialComposer = scene.composerBounds()
                val button = assertNotNull(scene.semanticsBounds {
                    it.getOrNull(SemanticsProperties.Role) == Role.Button &&
                        "回到最新消息，7 条未读" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.singleOrNull(), "The real return action must remain mounted.")
                val blurTop = scene.composerBounds().top - (8f + ConversationComposerBlurFeather.value) * density
                assertTrue(abs(button.bottom - blurTop - 6f * density) <= 1.5f,
                    "The circle must enter the unchanged progressive feather by 6dp: $button vs $blurTop")
                assertTrue((scene.composerBounds().top - button.bottom) / density in 25f..27f,
                    "The lowered return action must leave 26dp before the actual input hit region.")
                // The button draws above the blur overlay, so its downward shadow darkens the
                // band directly below the circle instead of being covered by the blur.
                val shadowY = (button.bottom + 3f * density).roundToInt()
                val besideX = (button.left - 18f * density).roundToInt()
                assertTrue(luminance(frame[besideX, shadowY]) - luminance(frame[button.center.x.roundToInt(), shadowY]) >= 4,
                    "The downward shadow must remain visible over the blurred band.")
                val body = frame[(button.center.x - 12f * density).roundToInt(), button.center.y.roundToInt()]
                assertTrue(if (dark) luminance(body) < 100 else luminance(body) > 210,
                    "The circular glass body must actually paint in the current theme above the wallpaper.")
                var arrowPixels = 0
                for (y in (button.center.y - 10f * density).roundToInt()..(button.center.y + 10f * density).roundToInt())
                    for (x in (button.center.x - 10f * density).roundToInt()..(button.center.x + 10f * density).roundToInt()) {
                        val pixel = frame[x, y]
                        val contrast = luminance(pixel) - luminance(body)
                        if (if (dark) contrast > 30 else contrast < -30) arrowPixels++
                    }
                assertTrue(arrowPixels >= 10, "The arrow must remain visible, rather than only the blue unread badge.")
                var badgePixels = 0
                for (y in (button.top - 9f * density).roundToInt() until (button.top + 5f * density).roundToInt())
                    for (x in (button.center.x - 14f * density).roundToInt()..(button.center.x + 14f * density).roundToInt()) {
                        val pixel = frame[x, y]
                        if ((pixel ushr 16 and 255) < 130 && (pixel ushr 8 and 255) < 180 && (pixel and 255) > 180) badgePixels++
                    }
                assertTrue(badgePixels >= 20, "The real unread badge must paint above the intact button.")
                scene.moveTo(button.center.x, button.center.y)
                scene.pressAt(button.center.x, button.center.y)
                scene.releaseAt(button.center.x, button.center.y)
                scene.settledFrame()
                assertEquals(snapshot.token, returned, "Clicking the visible circle must request the native latest window.")
                expanded = true
                val expandedFrame = scene.settledFrame()
                expandedFrame.exportPlacement("${if (dark) "dark" else "light"}-$density-$widthDp-latest-expanded")
                val expandedComposer = scene.composerBounds()
                val expandedButton = assertNotNull(scene.latestButtonBounds().singleOrNull())
                assertTrue(expandedComposer.height > initialComposer.height,
                    "The action fixture must grow the real reply/edit overlay.")
                assertTrue((expandedComposer.top - expandedButton.bottom) / density in 25f..27f,
                    "The lowered return action must follow the grown input while retaining its 26dp clearance.")
                returned = null
                scene.moveTo(expandedButton.center.x, expandedButton.center.y)
                scene.pressAt(expandedButton.center.x, expandedButton.center.y)
                scene.releaseAt(expandedButton.center.x, expandedButton.center.y)
                scene.settledFrame()
                assertEquals(snapshot.token, returned, "The grown reply/edit overlay must not cover or intercept the return action.")
            }
        }
    }

    @Test
    fun scrollingInsideATallLatestMessageShowsTheButtonAndIncomingMessagesPreserveThatAnchor() {
        val initial = fixture().let { base ->
            base.copy(messages = base.messages.dropLast(1) + base.messages.last().copy(
                text = (1..90).joinToString("\n") { "长消息第${it}行" }))
        }
        var snapshot by mutableStateOf(initial)
        val measured = java.util.concurrent.ConcurrentHashMap<Long, Rect>()
        var nativeLatestRequests = 0
        ComposePixelScene(900, 700) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                    ConversationPane(snapshot, snapshot.selected!!, TextFieldValue(), {}, null,
                        {}, {}, {}, {}, ChatFeatureActions(returnToLatest = { nativeLatestRequests++ }), false,
                        {}, {}, {}, {}, false)
                }
            }
        }.use { scene ->
            scene.settledFrame()
            val resting = assertNotNull(measured[80L])
            assertTrue(resting.height > 700f, "The latest native message must actually be taller than the viewport.")
            assertTrue(scene.latestButtonBounds().isEmpty(), "The button must initially be hidden at the real bottom.")
            var moved = resting
            older@ for (delta in listOf(-3f, 3f)) for (step in 0 until 3) {
                scene.scrollAt(420f, 350f, deltaY = delta)
                scene.settledFrame(8)
                moved = assertNotNull(measured[80L])
                if (moved.bottom >= max(resting.bottom + 128f, 720f)) break@older
            }
            assertTrue(moved.bottom >= resting.bottom + 128f, "Real wheel input must move inside the long latest item.")
            assertTrue(moved.top < 0f && moved.bottom > 700f,
                "The same measured latest item must still span the viewport after scrolling inside it: $moved")
            assertTrue(scene.timelineScrollValue() > 0f,
                "The actual timeline must expose a nonzero scroll displacement.")
            assertNotNull(scene.latestButtonBounds().singleOrNull(),
                "A large offset within index zero must expose the return button.")

            snapshot = snapshot.copy(messages = snapshot.messages + ChatMessage(81, 10, text = "新增末条合成消息"))
            scene.settledFrame()
            val afterIncoming = assertNotNull(measured[80L])
            // Becoming a non-last message adds the real group-tail spacing below this
            // bubble. That small displacement must keep the same reading line, while
            // an incorrect automatic return would move by the much larger wheel offset.
            val readingLineHeight = moved.height / 90f
            assertTrue(abs(afterIncoming.top - moved.top) < readingLineHeight &&
                abs(afterIncoming.bottom - moved.bottom) < readingLineHeight,
                "A native incoming message must retain the same reading line after natural group-tail spacing: $moved -> $afterIncoming")
            assertTrue(afterIncoming.top < 0f && afterIncoming.bottom > 700f,
                "The reader must remain inside the same long previous message after the native insertion.")
            val button = assertNotNull(scene.latestButtonBounds().singleOrNull())
            scene.moveTo(button.center.x, button.center.y)
            scene.pressAt(button.center.x, button.center.y)
            scene.releaseAt(button.center.x, button.center.y)
            scene.settledFrame(40)
            assertEquals(0f, scene.timelineScrollValue(), 0.001f)
            val latest = assertNotNull(measured[81L])
            val blurTop = scene.composerBounds().top - 8f - ConversationComposerBlurFeather.value
            assertTrue(latest.bottom <= blurTop - 2f, "The real return must finish at the lowered 2dp clearance above the blur.")
            assertTrue(scene.latestButtonBounds().isEmpty(), "The actual button must finish its exit at the latest item.")
            assertEquals(0, nativeLatestRequests, "An already loaded latest window uses real list scrolling, not a native history reset.")
        }
    }

    @Test
    fun anIncomingPhotoWithItsOnlyUncoveredSliverInsideTheFeatherIsExcludedFromANewVisibilityDispatch() {
        val base = fixture()
        // Official photo canvases are at least 100dp high. Use a real multiline
        // composer so its measured occlusion can contain the complete photo row.
        val tallDraft = TextFieldValue("合成多行输入\n第二行\n第三行\n第四行")
        val photos = (1L..80L).map { id ->
            ChatMessage(id, 10, senderId = 20, date = 1_700_000_000 + id.toInt(), kind = MessageKind.PHOTO,
                media = MessageMedia(MediaFile(1_000 + id.toInt()), width = 32, height = 8))
        }
        val snapshot = base.copy(messages = photos, replyTarget = photos.first(),
            senderNames = mapOf(20L to "合成照片发送者"))
        val measured = java.util.concurrent.ConcurrentHashMap<Long, Rect>()
        var sceneReference: ComposePixelScene? = null
        var checked = false
        ComposePixelScene(900, 700) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measured.remove(id) else measured[id] = bounds
                }) {
                    ConversationPane(snapshot, snapshot.selected!!, tallDraft, {}, null,
                        {}, {}, {}, {}, ChatFeatureActions(visibleMessages = { _, ids, active ->
                            if (active) sceneReference?.let { currentScene ->
                                val composer = currentScene.composerBounds()
                                val occlusion = Rect(0f, composer.top - 8f - ConversationComposerBlurFeather.value, 900f, 700f)
                                val obscured = measured.filterValues {
                                    occlusion.fullyContains(it) && it.top < composer.top - 8f
                                }.keys
                                if (obscured.isNotEmpty()) {
                                    assertTrue(ids.none { it in obscured },
                                        "A real incoming visibility dispatch must omit a photo whose only area above the input lies in the feather.")
                                    checked = true
                                }
                            }
                        }), true, {}, {}, {}, {}, false)
                }
            }
        }.use { scene ->
            sceneReference = scene
            scene.settledFrame()
            val composer = scene.composerBounds()
            val occlusion = Rect(0f, composer.top - 8f - ConversationComposerBlurFeather.value, 900f, 700f)
            val diagnostics = mutableListOf("composer=$composer", "occlusion=$occlusion")
            val photoId = photos.last().id
            val initialPhoto = assertNotNull(measured[photoId])
            diagnostics += "initialPhoto=$initialPhoto"
            assertTrue(occlusion.height > initialPhoto.height,
                "The real multiline reply composer must have enough measured occlusion for the complete photo: $diagnostics")
            val targetTop = occlusion.top + minOf(ConversationComposerBlurFeather.value / 2,
                (occlusion.height - initialPhoto.height) / 2)
            // Calibrate real wheel motion first, including the clamped direction at latest.
            // Then move by measured pixels instead of retaining a fixed old 12-step budget.
            var pixelsPerWheel = 0f
            var observedMovement = 0f
            for (probe in listOf(-0.25f, 0.25f)) {
                val before = assertNotNull(measured[photoId])
                scene.scrollAt(420f, 350f, deltaY = probe)
                scene.settledFrame(6)
                val after = assertNotNull(measured[photoId])
                diagnostics += "probe=$probe before=$before after=$after"
                observedMovement = after.top - before.top
                if (abs(observedMovement) >= 1f) {
                    pixelsPerWheel = observedMovement / probe
                    break
                }
            }
            assertTrue(pixelsPerWheel != 0f, "Real wheel input must move the measured photo before testing visibility: $diagnostics")
            var obscured = emptySet<Long>()
            val positioningSteps = (kotlin.math.ceil(initialPhoto.height / abs(observedMovement)).toInt() + 4).coerceIn(4, 64)
            for (step in 0 until positioningSteps) {
                val current = assertNotNull(measured[photoId])
                val delta = ((targetTop - current.top) / pixelsPerWheel).coerceIn(-4f, 4f)
                scene.scrollAt(420f, 350f, deltaY = delta)
                scene.settledFrame(6)
                diagnostics += "step=$step delta=$delta photo=${measured[photoId]}"
                obscured = measured.filterValues {
                    occlusion.fullyContains(it) && it.top < composer.top - 8f
                }.keys.toSet()
                if (obscured.isNotEmpty()) break
            }
            val diagnosticDirectory = File(System.getProperty("java.io.tmpdir"), "conversation-placement").apply { mkdirs() }
            File(diagnosticDirectory, "feather-photo-geometry.txt").writeText(diagnostics.joinToString("\n"))
            scene.settledFrame().exportPlacement("feather-photo-positioned")
            assertTrue(obscured.isNotEmpty(),
                "The real photo must cross the input's top edge while all of its uncovered area lies inside the compact feather: $diagnostics")
            // Hold the found geometry still and wait only once for the production read debounce.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!checked && System.nanoTime() < deadline) {
                Thread.sleep(10)
                scene.settledFrame(1)
            }
            assertTrue(checked, "A fresh actual visibility dispatch must observe the photo hidden by the input and its feather.")
        }
    }

    @Test
    fun theLatestMessageStaysAboveTheActualComposerAsTheDraftGrows() {
        var draft by mutableStateOf(TextFieldValue())
        val snapshot = fixture()
        ComposePixelScene(900, 700) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                ConversationPane(snapshot, snapshot.selected!!, draft, { draft = it }, null,
                    {}, {}, {}, {}, ChatFeatureActions(), false, {}, {}, {}, {}, false)
            }
        }.use { scene ->
            scene.settledFrame()
            val initialComposer = scene.composerBounds()
            val initialLatest = assertNotNull(scene.messageBounds().firstOrNull { it.first == 80L }?.second)
            assertTrue(initialLatest.bottom <= initialComposer.top - 8f,
                "The latest message must end above the actual input surface: $initialLatest vs $initialComposer")
            draft = TextFieldValue("第一行\n第二行\n第三行")
            scene.settledFrame()
            val expandedComposer = scene.composerBounds()
            val expandedLatest = assertNotNull(scene.messageBounds().firstOrNull { it.first == 80L }?.second)
            assertTrue(expandedComposer.height > initialComposer.height,
                "The draft fixture must increase the measured composer height.")
            assertTrue(expandedLatest.bottom <= expandedComposer.top - 8f,
                "Bottom content padding must follow the measured multiline composer: $expandedLatest vs $expandedComposer")
        }
    }

    @Test
    fun scrollingMessagesPassBehindTheComposerAndRemainPaintedInTheBottomGap() {
        val snapshot = fixture().let { it.copy(replyTarget = it.messages.last()) }
        val measuredMessages = java.util.concurrent.ConcurrentHashMap<Long, Rect>()
        var visibilityGuardChecked = false
        var visibleDispatches = 0
        var sceneReference: ComposePixelScene? = null
        ComposePixelScene(900, 700) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(LocalConversationPreviewMessageBounds provides { id, bounds ->
                    if (bounds == null) measuredMessages.remove(id) else measuredMessages[id] = bounds
                }) {
                    ConversationPane(snapshot, snapshot.selected!!, TextFieldValue("第一行\n第二行\n第三行"), {}, null,
                        {}, {}, {}, {}, ChatFeatureActions(visibleMessages = { _, ids, active ->
                            if (active) {
                                visibleDispatches++
                                val composer = sceneReference?.composerBounds()
                                if (composer != null) {
                                    val covered = measuredMessages.filterValues { composer.fullyContains(it) }.keys
                                    if (covered.isNotEmpty()) {
                                        assertTrue(ids.none { it in covered },
                                            "An actual visible-message dispatch must exclude every whole message box hidden by the input.")
                                        visibilityGuardChecked = true
                                    }
                                }
                            }
                        }), true, {}, {}, {}, {}, false)
                }
            }
        }.use { scene ->
            sceneReference = scene
            val resting = scene.settledFrame()
            val composer = scene.composerBounds()
            var crossedComposer = false
            var paintedBelowComposer = false
            // The event stays in the actual timeline and away from the input, so its natural
            // scroll handler must remain reachable through the surrounding transparent space.
            scrolling@ for (delta in listOf(-1f, 1f)) for (step in 0 until 18) {
                scene.scrollAt(450f, 400f, deltaY = delta)
                val scrolled = scene.settledFrame(8)
                val rows = measuredMessages.values.toList()
                crossedComposer = crossedComposer || rows.any { bounds ->
                    bounds.top < composer.bottom && bounds.bottom > composer.top
                }
                if (!visibilityGuardChecked && rows.any { composer.fullyContains(it) }) {
                    // The production read debounce uses wall time. Advance rendering while
                    // yielding the test thread until a NEW dispatch can observe this geometry;
                    // a snapshot dispatched before the scroll is not evidence of a new read.
                    val before = visibleDispatches
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (visibleDispatches == before && System.nanoTime() < deadline) {
                        Thread.sleep(10)
                        scene.settledFrame(1)
                    }
                }
                if (changedPixels(resting, scrolled, Rect(24f, composer.bottom + 2f, 430f, 699f)) > 20)
                    paintedBelowComposer = true
                if (crossedComposer && visibilityGuardChecked && paintedBelowComposer) break@scrolling
            }
            assertTrue(crossedComposer, "The timeline must extend behind the input rather than end at its upper edge.")
            assertTrue(paintedBelowComposer, "Messages must really paint through the transparent bottom gap.")
            assertTrue(visibilityGuardChecked,
                "A new production visibility dispatch must be checked while a whole measured message box is hidden behind the input.")
        }
    }

    private fun Rect.fullyContains(other: Rect): Boolean = other.width > 0f && other.height > 0f &&
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    private fun ComposePixelScene.composerBounds(): Rect = assertNotNull(semanticsBounds { config ->
        "消息输入区域" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.firstOrNull())

    private fun ComposePixelScene.textBounds(value: String): Rect = assertNotNull(semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }
    }.singleOrNull(), "Missing actual text: $value")

    private fun ComposePixelScene.latestButtonBounds() = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("回到最新消息") }
    }

    private fun ComposePixelScene.timelineScrollValue(): Float {
        val values = mutableListOf<Float>()
        semanticsBounds { config ->
            config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                if (range.maxValue() > 0f) values += range.value()
            }
            false
        }
        return assertNotNull(values.maxOrNull(), "The real timeline must expose its scroll position.")
    }

    private fun ComposePixelScene.messageBounds(): List<Pair<Long, Rect>> {
        val ids = mutableListOf<Long>()
        val bounds = semanticsBounds { config ->
            val id = config.getOrNull(SemanticsProperties.Text).orEmpty().firstNotNullOfOrNull {
                it.text.takeIf { text -> text.startsWith("synthetic-") }?.removePrefix("synthetic-")?.toLongOrNull()
            }
            if (id != null) ids += id
            id != null
        }
        return ids.zip(bounds)
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

    private fun luminance(pixel: Int): Int =
        ((pixel ushr 16 and 255) * 299 + (pixel ushr 8 and 255) * 587 + (pixel and 255) * 114) / 1000

    private fun ComposePixelFrame.exportPlacement(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "conversation-placement").apply { mkdirs() }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        assertTrue(ImageIO.write(image, "png", File(directory, "$name.png")))
    }

    private fun fixture(): ChatSnapshot {
        val chat = ChatItem(10, "Synthetic floating input", basicGroupId = 1)
        return ChatSnapshot(enabled = true, token = ChatToken(1, 1, chat.id), selected = chat, historyEnd = true,
            messages = (1L..80L).map { ChatMessage(it, chat.id, text = "synthetic-$it") })
    }
}
