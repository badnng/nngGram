package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.badnng.moe.chat.AccountProfile
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.ChatToken
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageForwardInfo
import com.badnng.moe.chat.MessageForwardOrigin
import com.badnng.moe.chat.MessageKind
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.MessageReaction
import com.badnng.moe.chat.MessageReply
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real photo pixels, text geometry and pointer input; no native engine or account is opened. */
class PhotoMessageSurfaceRenderTest {
    @Test
    fun barePhotosUseOnlyTheirImageBoundsAndOverlayMetadataOnThePixels() = withPhoto { path ->
        for (mode in modes) for (density in densities) for (outgoing in listOf(false, true)) {
            val message = photoMessage(path, outgoing)
            var bubble = Rect.Zero
            var enlarged: MessageMedia? = null
            photoScene(message, mode, density, onEnlarge = { enlarged = it }, onBounds = { bubble = it }).use { scene ->
                val photo = scene.awaitPhoto()
                assertClose(photo.left, bubble.left, "A bare photo starts at the message's actual left edge.")
                assertClose(photo.top, bubble.top, "A bare photo starts at the message's actual top edge.")
                assertClose(photo.right, bubble.right, "A bare photo ends at the message's actual right edge.")
                assertClose(photo.bottom, bubble.bottom, "A bare photo has no footer card below its pixels.")
                assertClose(240f * density, photo.width, "A narrow photo retains its independent native canvas.")
                assertClose(if (outgoing) 320f * density else 0f,
                    if (outgoing) bubble.right else bubble.left, "Private photos keep their ordinary conversation alignment.")

                scene.moveTo(319f * density, 500f * density)
                val imageFrame = scene.settledFrame()
                scene.moveTo(photo.center.x, photo.center.y)
                val frame = scene.settledFrame()
                writeFrame(frame, "bare-$mode-$density-$outgoing")
                val clock = scene.textBounds(messageClock(message.date))
                assertInside(clock, photo, "The actual clock overlays the photo rather than a separate card.")
                assertTrue(clock.center.x > photo.center.x && clock.center.y > photo.center.y)
                if (outgoing) {
                    val delivery = scene.descriptionBounds("已发送")
                    assertInside(delivery, photo, "The delivery glyph belongs to the same on-photo overlay.")
                    assertTrue(delivery.left >= clock.right)
                }
                assertPhotoPixel(imageFrame, photo.left + 2f * density, photo.center.y,
                    "Ready image pixels reach the left edge without a surrounding bubble inset.")
                assertPhotoPixel(imageFrame, photo.right - 2f * density, photo.center.y,
                    "Ready image pixels reach the right edge without a surrounding bubble inset.")
                assertImageCornerPixels(imageFrame, photo, density, joinedAbove = false, joinedBelow = false)
                val outsideX = if (outgoing) photo.left - 3f * density else photo.right + 3f * density
                assertBackgroundPixel(frame, outsideX, photo.center.y,
                    "The conversation remains visible immediately beside the image.")

                scene.pressAt(photo.center.x, photo.center.y)
                scene.releaseAt(photo.center.x, photo.center.y)
                scene.settledFrame(2)
                assertEquals(message.media, enlarged, "The edge-aligned image retains its real enlargement action.")
            }
        }
    }

    @Test
    fun captionAndHeaderSectionsKeepTheirOwnPaddingAroundEdgeAlignedImages() = withPhoto { path ->
        for (mode in modes) for (density in densities) for (outgoing in listOf(false, true)) {
            for (section in listOf("caption-below", "caption-above", "reply", "forward", "sender")) {
                val caption = "合成图片说明：这段文字只用于验证实际排版、原生说明顺序与完整文字边距。"
                val message = when (section) {
                    "caption-below", "caption-above" -> photoMessage(path, outgoing).copy(
                        text = caption, showCaptionAboveMedia = section == "caption-above")
                    "reply" -> photoMessage(path, outgoing).copy(replyTo = MessageReply(10, 99,
                        text = "合成引用内容", senderId = 20))
                    "forward" -> photoMessage(path, outgoing).copy(forwardInfo = MessageForwardInfo(
                        MessageForwardOrigin.HiddenUser("合成原作者")))
                    else -> photoMessage(path, outgoing).copy(senderId = 20, senderIsChat = outgoing)
                }
                var bubble = Rect.Zero
                photoScene(message, mode, density, senderHeader = section == "sender", onBounds = { bubble = it }).use { scene ->
                    val photo = scene.awaitPhoto()
                    scene.moveTo(319f * density, 500f * density)
                    val imageFrame = scene.settledFrame()
                    scene.moveTo(photo.center.x, photo.center.y)
                    val frame = scene.settledFrame()
                    writeFrame(frame, "$section-$mode-$density-$outgoing")
                    assertClose(bubble.left, photo.left, "Photos with $section have no image-side gutter.")
                    assertClose(bubble.right, photo.right, "Photos with $section fill the complete bubble width.")
                    assertTrue(bubble.left >= 0f && bubble.right <= 320f * density + 1f,
                        "Both incoming and outgoing $section photos fit the actual narrow viewport.")
                    val sectionBounds = when (section) {
                        "caption-below", "caption-above" -> scene.textBounds(caption)
                        "reply" -> assertNotNull(scene.semanticsBounds {
                            it.getOrNull(SemanticsActions.OnClick)?.label == "跳转到原消息"
                        }.singleOrNull(), "The real reply reference keeps its jump action.")
                        "forward" -> scene.textBounds("转发自 合成原作者")
                        else -> scene.textBounds("合成发送者")
                    }
                    assertClose(11f * density, sectionBounds.left - bubble.left,
                        "The $section section retains its own official 11dp horizontal padding.")
                    if (section == "caption-below") {
                        assertClose(bubble.top, photo.top, "A below-caption photo starts at the bubble's top edge.")
                        assertTrue(sectionBounds.top > photo.bottom,
                            "The native below-caption order remains in the real drawing.")
                        val clock = scene.textBounds(messageClock(message.date))
                        val layout = scene.textLayout(caption)
                        val last = layout.lineCount - 1
                        assertTrue(clock.top >= sectionBounds.bottom ||
                            clock.left >= sectionBounds.left + layout.getLineRight(last) + 9f * density &&
                                clock.top >= sectionBounds.top + layout.getLineTop(last) - 1f,
                            "Caption metadata uses only clear final-line space or a separate footer row.")
                        assertTrue(clock.bottom <= bubble.bottom - 7f * density,
                            "The text footer retains its own bottom padding.")
                    } else {
                        assertClose(8f * density, sectionBounds.top - bubble.top,
                            "The $section section retains its own official 8dp top padding.")
                        assertTrue(sectionBounds.bottom < photo.top,
                            "The $section section precedes the edge-aligned image.")
                        assertClose(photo.bottom, bubble.bottom,
                            "The bottom photo edge is the actual bubble edge when metadata overlays it.")
                        assertInside(scene.textBounds(messageClock(message.date)), photo,
                            "A bottom-aligned image owns the on-photo metadata.")
                    }
                    assertPhotoPixel(imageFrame, photo.left + 2f * density, photo.center.y,
                        "The image of a $section message reaches its bubble edge.")
                    assertImageCornerPixels(imageFrame, photo, density,
                        joinedAbove = section != "caption-below", joinedBelow = section == "caption-below")
                    assertTrue(colorDistance(frame[(bubble.left + 4f * density).roundToInt(), sectionBounds.center.y.roundToInt()],
                        java.awt.Color.MAGENTA.rgb) > 10,
                        "The actual text/header section still paints its readable Miuix surface.")
                }
            }
        }
    }

    @Test
    fun headersAndCaptionsJoinThePhotoIntoOneContinuousCard() = withPhoto { path ->
        val samples = mutableListOf<Pair<String, BufferedImage>>()
        for (mode in modes) for (density in densities) for (outgoing in listOf(false, true)) {
            for (header in listOf("sender", "reply", "forward")) for (captionAbove in listOf(false, true)) {
                val caption = "合成图片说明：图片和文字共用完整卡片。"
                val base = photoMessage(path, outgoing).copy(text = caption, showCaptionAboveMedia = captionAbove)
                val message = when (header) {
                    "sender" -> base.copy(senderId = 20, senderIsChat = outgoing)
                    "reply" -> base.copy(replyTo = MessageReply(10, 99, text = "合成引用内容", senderId = 20))
                    else -> base.copy(forwardInfo = MessageForwardInfo(MessageForwardOrigin.HiddenUser("合成原作者")))
                }
                var bubble = Rect.Zero
                photoScene(message, mode, density, senderHeader = header == "sender", onBounds = { bubble = it }).use { scene ->
                    val photo = scene.awaitPhoto()
                    scene.moveTo(319f * density, 500f * density)
                    val frame = scene.settledFrame()
                    val captionBounds = scene.textBounds(caption)
                    assertClose(bubble.left, photo.left, "The complete $header/caption card keeps its photo flush left.")
                    assertClose(bubble.right, photo.right, "The complete $header/caption card keeps its photo flush right.")
                    assertClose(11f * density, captionBounds.left - bubble.left,
                        "Joining the image does not remove the caption's own text padding.")
                    assertImageCornerPixels(frame, photo, density, joinedAbove = true, joinedBelow = !captionAbove)
                    assertCardCornerPixels(frame, bubble, density)
                    if (captionAbove) assertTrue(captionBounds.bottom < photo.top,
                        "The native above-caption remains above the joined photo.")
                    else assertClose(5f * density, captionBounds.top - photo.bottom,
                        "The below-caption retains the real 5dp media gap.")
                    val label = "$header-${if (captionAbove) "above" else "below"}-$mode-$density-$outgoing"
                    writeFrame(frame, "joined-$label")
                    // Incoming previews keep the overview compact; both directions are
                    // still rendered and checked above at both physical densities.
                    if (!outgoing) samples += label to cropFrame(frame, bubble, density)
                }
            }
        }
        writeContactSheet(samples, "joined-card-contact-sheet")
    }

    @Test
    fun imageMetadataFollowsHoverAndLatestSelfRulesWithoutChangingPhotoGeometry() = withPhoto { path ->
        for (mode in modes) for (density in densities) {
            for (scenario in listOf("incoming", "latest-outgoing", "older-outgoing", "saved-outgoing")) {
                val message = photoMessage(path, outgoing = scenario != "incoming")
                val alwaysVisible = scenario == "latest-outgoing"
                var bubble = Rect.Zero
                photoScene(message, mode, density, latest = scenario != "older-outgoing",
                    savedMessages = scenario == "saved-outgoing", onBounds = { bubble = it }).use { scene ->
                    val photo = scene.awaitPhoto()
                    scene.moveTo(319f * density, 500f * density)
                    scene.settledFrame(2)
                    val restingBounds = bubble
                    assertEquals(alwaysVisible, scene.hasText(messageClock(message.date)),
                        "Resting metadata follows the official latest-self rule for $scenario.")
                    scene.moveTo(photo.center.x, photo.center.y)
                    scene.settledFrame(2)
                    assertInside(scene.textBounds(messageClock(message.date)), photo,
                        "Hover exposes the actual image metadata for $scenario.")
                    assertEquals(restingBounds, bubble, "Hover metadata must not add any layout footprint.")
                    scene.moveTo(319f * density, 500f * density)
                    scene.settledFrame(2)
                    assertEquals(alwaysVisible, scene.hasText(messageClock(message.date)),
                        "Leaving the message restores resting metadata visibility for $scenario.")
                    assertEquals(restingBounds, bubble, "Removing image metadata must not change the message's bounds.")
                }
            }
        }
    }

    @Test
    fun barePhotoReactionsRemainSeparateChipsWithoutAFullWidthCard() = withPhoto { path ->
        for (mode in modes) for (density in densities) for (outgoing in listOf(false, true)) {
            val message = photoMessage(path, outgoing).copy(reactions = listOf(MessageReaction("👍", 7)))
            var bubble = Rect.Zero
            var reacted = false
            val features = ChatFeatureActions(setReaction = { token, id, emoji, chosen ->
                assertEquals(ChatToken(1, 1, message.chatId), token)
                assertEquals(message.id, id)
                assertEquals("👍", emoji)
                reacted = chosen
            })
            photoScene(message, mode, density, features = features, onBounds = { bubble = it }).use { scene ->
                val photo = scene.awaitPhoto()
                val reaction = scene.descriptionBounds("👍 7 条回应")
                assertClose(5f * density, reaction.top - photo.bottom,
                    "A bare photo keeps the official 5dp reaction gap outside its image.")
                if (outgoing) assertClose(photo.right, reaction.right,
                    "The outgoing reaction chip aligns to the actual photo's right edge.")
                else assertClose(photo.left, reaction.left,
                    "The incoming reaction chip aligns to the actual photo's left edge.")
                assertClose(reaction.bottom, bubble.bottom,
                    "The reaction chip defines the message bottom without an extra padded card.")
                val frame = scene.settledFrame()
                val emptyX = if (outgoing) photo.left + 8f * density else photo.right - 8f * density
                assertBackgroundPixel(frame, emptyX, reaction.center.y,
                    "The empty reaction row stays transparent rather than becoming a full-width bubble.")
                scene.moveTo(photo.center.x, photo.center.y)
                scene.settledFrame(2)
                assertInside(scene.textBounds(messageClock(message.date)), photo,
                    "Reactions do not move the clock out of the image.")
                writeFrame(scene.settledFrame(), "reactions-$mode-$density-$outgoing")
                scene.pressAt(reaction.center.x, reaction.center.y)
                scene.releaseAt(reaction.center.x, reaction.center.y)
                scene.settledFrame(2)
                assertTrue(reacted, "The independently drawn reaction retains its native toggle action.")
            }
        }
    }

    @Test
    fun everyOutgoingPhotoReactionRowMirrorsNativeOrderAgainstTheImageRightEdge() = withPhoto { path ->
        val reactions = listOf("👍", "❤", "🔥", "🎉", "👏", "😁", "🤔").map {
            MessageReaction(it, 123)
        }
        for (density in densities) {
            val message = photoMessage(path, outgoing = true).copy(reactions = reactions)
            photoScene(message, ColorSchemeMode.Light, density, widthDp = 180, onBounds = {}).use { scene ->
                val photo = scene.awaitPhoto()
                val chips = reactions.map { scene.descriptionBounds("${it.emoji} 123 条回应") }
                val rows = chips.groupBy { it.top.roundToInt() }.values
                assertTrue(rows.size >= 2, "The narrow real photo must exercise more than one reaction row.")
                for (row in rows) {
                    assertClose(photo.right, row.first().right,
                        "Every outgoing row begins at the image's right edge, including its partial final row.")
                    row.zipWithNext().forEach { (first, next) ->
                        assertClose(4f * density, first.left - next.right,
                            "The original native reaction order mirrors right-to-left with the real 4dp gap.")
                    }
                    row.forEach { assertTrue(it.left >= photo.left && it.right <= photo.right) }
                }
                writeFrame(scene.settledFrame(), "reaction-rows-$density")
            }
        }
    }

    @Test
    fun tinyEditedPhotosReserveOneLineForTheirActualImageInfo() = withPhoto(width = 8, height = 6) { path ->
        for (density in densities) {
            val message = photoMessage(path, outgoing = true).let { it.copy(editDate = it.date + 1,
                media = it.media!!.copy(width = 8, height = 6)) }
            var bubble = Rect.Zero
            photoScene(message, ColorSchemeMode.Light, density, widthDp = 180, onBounds = { bubble = it }).use { scene ->
                val photo = scene.awaitPhoto()
                val clock = scene.textBounds(messageClock(message.date))
                val edited = scene.textBounds("已编辑")
                val delivery = scene.descriptionBounds("已发送")
                assertTrue(photo.width > 100f * density && photo.width <= 180f * density,
                    "The tiny native image canvas grows beyond its ordinary 100dp minimum for measured edited info.")
                assertClose(clock.center.y, edited.center.y,
                    "The actual edited label remains beside its clock instead of wrapping in the image overlay.")
                assertTrue(clock.right < edited.left && edited.right <= delivery.left,
                    "Time, edited state and delivery keep their native one-line order.")
                for (info in listOf(clock, edited, delivery)) assertInside(info, photo,
                    "The complete measured info fits inside the tiny image's expanded canvas.")
                assertEquals(photo, bubble, "Reserving metadata width must not recreate exterior image padding.")
                writeFrame(scene.settledFrame(), "tiny-edited-$density")
            }
        }
    }

    private fun photoMessage(path: Path, outgoing: Boolean) = ChatMessage(1, 10, outgoing = outgoing,
        date = 1_700_000_000, kind = MessageKind.PHOTO,
        media = MessageMedia(MediaFile(1, localPath = path.toString(), downloaded = true), width = 240, height = 180))

    private fun photoScene(message: ChatMessage, mode: ColorSchemeMode, density: Float,
        senderHeader: Boolean = false, features: ChatFeatureActions = ChatFeatureActions(),
        latest: Boolean = true, savedMessages: Boolean = false,
        widthDp: Int = 320,
        onEnlarge: (MessageMedia) -> Unit = {}, onBounds: (Rect) -> Unit): ComposePixelScene =
        ComposePixelScene((widthDp * density).roundToInt(), (600f * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            val snapshot = remember {
                ChatSnapshot(enabled = true, token = ChatToken(1, 1, message.chatId),
                    selected = ChatItem(message.chatId, "合成聊天", privateUserId = 20,
                        lastMessage = if (latest) message else message.copy(id = message.id + 1)),
                    messages = listOf(message), senderNames = mapOf(20L to "合成引用者"),
                    account = if (savedMessages) AccountProfile(20, "合成帐号") else null)
            }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                MiuixTheme(controller = theme) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        MessageBubble(message, sender = "合成发送者", showSender = senderHeader, endsGroup = true,
                            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
                            read = false, onRetry = {}, snapshot = snapshot, features = features,
                            visible = true, animate = false, onEnlarge = onEnlarge, onSave = { _, _ -> },
                            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                            playbackSession = null, onExpandVideo = {}, menuOpen = false, onMenuOpen = {},
                            onMenuDismiss = {}, onReply = {}, onQuote = {}, onJumpToReply = {}, onReact = {},
                            onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                            showSenderName = senderHeader, showAvatar = false, reserveAvatarSpace = false)
                    }
                }
            }
        }

    private fun ComposePixelScene.textBounds(text: String): Rect = assertNotNull(unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.singleOrNull(), "The actual message must compose the text '$text'.")

    private fun ComposePixelScene.hasText(text: String): Boolean = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }.isNotEmpty()

    private fun ComposePixelScene.textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        unmergedSemanticsBounds {
            val matches = it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
            if (matches) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            matches
        }
        return layouts.single()
    }

    private fun ComposePixelScene.descriptionBounds(description: String): Rect = assertNotNull(semanticsBounds {
        description in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.singleOrNull(), "The actual message must compose '$description'.")

    private fun ComposePixelScene.awaitPhoto(): Rect {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            settledFrame(2)
            unmergedSemanticsBounds { "聊天图片" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .singleOrNull()?.let { return it }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated synthetic photo did not finish decoding.")
    }

    private fun withPhoto(width: Int = 240, height: Int = 180, test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-photo-surface-")
        val path = directory.resolve("synthetic.png")
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = java.awt.Color(36, 189, 118)
                graphics.fillRect(0, 0, image.width, image.height)
            } finally { graphics.dispose() }
        }
        check(ImageIO.write(image, "png", path.toFile()))
        try { test(path) } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun assertInside(content: Rect, container: Rect, reason: String) {
        assertTrue(content.left >= container.left && content.top >= container.top &&
            content.right <= container.right && content.bottom <= container.bottom,
            "$reason content=$content, photo=$container")
    }

    private fun assertPhotoPixel(frame: ComposePixelFrame, x: Float, y: Float, reason: String) {
        assertTrue(colorDistance(frame[x.roundToInt(), y.roundToInt()], java.awt.Color(36, 189, 118).rgb) <= 3, reason)
    }

    private fun assertBackgroundPixel(frame: ComposePixelFrame, x: Float, y: Float, reason: String) {
        assertTrue(colorDistance(frame[x.roundToInt(), y.roundToInt()], java.awt.Color.MAGENTA.rgb) <= 1, reason)
    }

    private fun assertImageCornerPixels(frame: ComposePixelFrame, photo: Rect, density: Float,
        joinedAbove: Boolean, joinedBelow: Boolean) {
        val inset = 1.5f * density
        for (x in listOf(photo.left + inset, photo.right - inset)) {
            if (joinedAbove) assertPhotoPixel(frame, x, photo.top + inset,
                "The top image corners must be square where header/above-caption text joins the same card.")
            else assertBackgroundPixel(frame, x, photo.top + inset,
                "An exposed top image corner keeps the outer Miuix card silhouette.")
            if (joinedBelow) assertPhotoPixel(frame, x, photo.bottom - inset,
                "The bottom image corners must be square where a below-caption joins the same card.")
            else assertBackgroundPixel(frame, x, photo.bottom - inset,
                "An exposed bottom image corner keeps the outer Miuix card silhouette.")
        }
    }

    private fun assertCardCornerPixels(frame: ComposePixelFrame, bubble: Rect, density: Float) {
        val inset = 1.5f * density
        for (x in listOf(bubble.left + inset, bubble.right - inset)) {
            assertBackgroundPixel(frame, x, bubble.top + inset,
                "The complete card has one rounded top silhouette, including its header.")
            assertBackgroundPixel(frame, x, bubble.bottom - inset,
                "The complete card has one rounded bottom silhouette, including its caption.")
        }
    }

    private fun colorDistance(first: Int, second: Int): Int = listOf(16, 8, 0).maxOf { shift ->
        abs((first ushr shift and 255) - (second ushr shift and 255))
    }

    private fun assertClose(expected: Float, actual: Float, reason: String) {
        assertTrue(abs(expected - actual) <= 1.5f, "$reason Expected=$expected, actual=$actual")
    }

    private fun writeFrame(frame: ComposePixelFrame, name: String) {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "behavior-alignment-media", "photo-surface")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun cropFrame(frame: ComposePixelFrame, bounds: Rect, density: Float): BufferedImage {
        val left = bounds.left.roundToInt().coerceIn(0, frame.width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, frame.height - 1)
        val width = (bounds.right.roundToInt() - left).coerceIn(1, frame.width - left)
        val height = (bounds.bottom.roundToInt() - top).coerceIn(1, frame.height - top)
        val image = BufferedImage((width / density).roundToInt(), (height / density).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        val native = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) native.setRGB(x, y, frame[left + x, top + y])
        image.createGraphics().let { graphics ->
            try { graphics.drawImage(native, 0, 0, image.width, image.height, null) }
            finally { graphics.dispose() }
        }
        return image
    }

    private fun writeContactSheet(samples: List<Pair<String, BufferedImage>>, name: String) {
        val columns = 6
        val tileWidth = samples.maxOf { it.second.width } + 16
        val tileHeight = samples.maxOf { it.second.height } + 40
        val image = BufferedImage(columns * tileWidth, ((samples.size + columns - 1) / columns) * tileHeight,
            BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = java.awt.Color(226, 226, 230)
                graphics.fillRect(0, 0, image.width, image.height)
                samples.forEachIndexed { index, (label, sample) ->
                    val left = index % columns * tileWidth + 8
                    val top = index / columns * tileHeight
                    graphics.color = java.awt.Color.BLACK
                    graphics.drawString(label, left, top + 18)
                    graphics.drawImage(sample, left, top + 28, null)
                }
            } finally { graphics.dispose() }
        }
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "behavior-alignment-media", "photo-surface")
        Files.createDirectories(directory)
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private val modes = listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)
    private val densities = listOf(1f, 1.25f)
}
