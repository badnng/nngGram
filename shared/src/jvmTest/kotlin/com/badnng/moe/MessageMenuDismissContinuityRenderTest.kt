package com.badnng.moe

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
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

/** Real outside-pointer dismissal, including frames after the native Popup releases its source. */
class MessageMenuDismissContinuityRenderTest {
    @Test
    fun theClearVisualTailCannotInterceptANewMouseSelectionOrRightClick() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val fixture = Fixture(mode, density, textMessage())
            fixture.scene.use { scene ->
                scene.settledFrame()
                val reference = fixture.reference(scene)
                fixture.open(scene, reference)
                scene.clickBlank(density)
                var reachedTail = false
                for (index in 0 until 24) {
                    fixture.assertFrame(scene, scene.settledFrame(1, 16), reference, "pointer-tail-$index")
                    if (!fixture.backdrop.hasMountedMenus && fixture.backdrop.clearSourcePreviews.isNotEmpty()) {
                        reachedTail = true
                        break
                    }
                }
                assertTrue(reachedTail, "The probe must begin while the clear visual tail is still mounted.")
                val start = scene.caret(fixture.message.text, 2)
                val middle = scene.caret(fixture.message.text, 9)
                val end = scene.caret(fixture.message.text, 15)
                scene.moveTo(start.x, start.y)
                scene.pressAt(start.x, start.y)
                scene.dragTo(middle.x, middle.y)
                scene.settledFrame(2, 16)
                scene.dragTo(end.x, end.y)
                scene.releaseAt(end.x, end.y)
                scene.secondaryClickAt(end.x, end.y)
                scene.settledFrame()
                assertTrue(fixture.menuOpen, "The next real right-click must reach the actual source message.")
                val quote = scene.button("引文回复")
                scene.moveTo(quote.center.x, quote.center.y)
                scene.pressAt(quote.center.x, quote.center.y)
                scene.releaseAt(quote.center.x, quote.center.y)
                scene.settledFrame()
                val selected = assertNotNull(fixture.quote,
                    "A drag started under the visual tail must reach the real SelectionContainer.")
                assertEquals(2, selected.position)
                assertEquals(fixture.message.text.substring(2, 15), selected.content.text)
                assertEquals(1, fixture.sourceMounts)
            }
        }
    }

    @Test
    fun aStandaloneHostWithoutTheVisualOverlayKeepsABoundedClearSourceFallback() {
        val fixture = Fixture(ColorSchemeMode.Light, 1f, textMessage(), withClearSourceHost = false)
        fixture.scene.use { scene ->
            scene.settledFrame()
            val reference = fixture.reference(scene)
            fixture.open(scene, reference)
            fixture.dismissAndCapture(scene, reference, "no-overlay-host")
            assertTrue(fixture.backdrop.clearSourcePreviews.isEmpty())
        }
    }

    @Test
    fun aPhotoAndItsExternalReactionStayClearUntilTheWorkspaceBlurHasActuallyFinished() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            withPhoto { path ->
                val fixture = Fixture(mode, density, photoMessage(path))
                fixture.scene.use { scene ->
                    fixture.awaitPhoto(scene)
                    val reference = fixture.reference(scene)
                    assertTrue(reference.artworkPixels > 500, "The fixture must contain real high-frequency photo pixels.")
                    assertTrue(reference.reaction.top >= reference.photo!!.bottom + 4f * density,
                        "This photo must exercise the external bottom-edge reaction footer.")
                    // MediaPreview owns an uncached decoded native image. Recreating that owner now fails its load.
                    Files.delete(path)
                    fixture.open(scene, reference)
                    fixture.dismissAndCapture(scene, reference, "photo")
                    assertEquals(0, fixture.downloads, "Closing the menu cannot request native photo bytes.")
                }
                assertEquals(0, fixture.sourceOwners)
            }
        }
    }

    @Test
    fun textAndItsReactionNeverBlinkAtThePopupToWorkspaceHandoff() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            val fixture = Fixture(mode, density, textMessage())
            fixture.scene.use { scene ->
                scene.settledFrame()
                val reference = fixture.reference(scene)
                fixture.open(scene, reference)
                fixture.dismissAndCapture(scene, reference, "text")
            }
            assertEquals(0, fixture.sourceOwners)
        }
    }

    @Test
    fun reopeningTheSamePhotoDuringExitKeepsTheNewOwnerDespiteOldClosingCallbacks() {
        for (density in listOf(1f, 1.25f)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            withPhoto { path ->
                val fixture = Fixture(mode, density, photoMessage(path))
                fixture.scene.use { scene ->
                    fixture.awaitPhoto(scene)
                    val reference = fixture.reference(scene)
                    Files.delete(path)
                    fixture.open(scene, reference)
                    scene.clickBlank(density)
                    repeat(3) { index ->
                        fixture.assertFrame(scene, scene.settledFrame(1, 16), reference, "rapid-close-$index")
                    }
                    assertFalse(fixture.menuOpen, "The real blank click must begin closing before reopening.")
                    // A newer request for this same anchor can arrive while its old native Popup is exiting.
                    fixture.menuOpen = true
                    repeat(20) { index ->
                        fixture.assertFrame(scene, scene.settledFrame(1, 16), reference, "rapid-reopen-$index")
                        assertTrue(fixture.menuOpen, "A stale exit callback cannot dismiss the newer menu request.")
                        assertTrue(fixture.backdrop.hasMountedMenus, "The reopened source must retain its mounted owner.")
                    }
                    assertTrue(fixture.backdrop.hasExpandedBlurOwners)
                    assertEquals(1, scene.menus().size, "Reopening cannot create a second native action body.")
                    fixture.dismissAndCapture(scene, reference, "rapid-final-close")
                    assertEquals(0, fixture.downloads)
                }
                assertEquals(0, fixture.sourceOwners)
            }
        }
    }

    @Test
    fun anotherBlurOwnerMayTakeOverButANonBlurOwnerCannotReleaseTheClearSourceEarly() {
        for (otherBlurs in listOf(false, true)) withPhoto { path ->
            val fixture = Fixture(ColorSchemeMode.Light, 1f, photoMessage(path))
            fixture.scene.use { scene ->
                fixture.awaitPhoto(scene)
                val reference = fixture.reference(scene)
                Files.delete(path)
                fixture.open(scene, reference)
                fixture.otherOwnerBlurBackground = otherBlurs
                fixture.assertFrame(scene, scene.settledFrame(1, 16), reference, "other-$otherBlurs-open")
                scene.clickBlank(1f)
                var blurredSourceFrames = 0
                repeat(32) { index ->
                    val frame = scene.settledFrame(1, 16)
                    assertFalse(fixture.menuOpen)
                    assertEquals(reference.bubble, fixture.bubble)
                    assertEquals(reference.reaction, scene.reaction())
                    assertEquals(1, fixture.sourceMounts, "Transferring backdrop ownership cannot recreate the media.")
                    assertEquals(1, fixture.sourceOwners)
                    assertEquals(listOf(reference.photo), scene.descriptions("聊天图片"))
                    if (!otherBlurs) {
                        fixture.assertFrame(scene, frame, reference, "nonblur-owner-$index")
                    } else {
                        val artwork = fixture.photoArtworkPixels(frame, reference.photo!!)
                        assertTrue(artwork <= reference.artworkPixels * 1.005f,
                            "Transferring the source to the blurred background cannot duplicate its artwork.")
                        if (artwork < reference.artworkPixels * .05f) blurredSourceFrames++
                    }
                }
                assertTrue(fixture.backdrop.hasMountedMenus, "The separate owner must remain registered.")
                if (otherBlurs) {
                    assertTrue(fixture.blurProgress > .99f, "The separate expanded owner must keep the real blur active.")
                    assertTrue(blurredSourceFrames >= 3,
                        "The closing Popup must release its clear source instead of waiting forever for another owner's blur.")
                } else {
                    assertTrue(fixture.blurProgress <= .001f,
                        "An expanded non-blurring owner cannot keep the context blur alive.")
                }
                fixture.otherOwnerBlurBackground = null
                val afterOtherUnmount = scene.settledFrame(1, 16)
                assertFalse(fixture.backdrop.hasMountedMenus,
                    "Removing the separate owner must reveal that the original Popup has already unmounted.")
                if (!otherBlurs) fixture.assertFrame(scene, afterOtherUnmount, reference, "nonblur-owner-removed")
                fixture.assertFrame(scene, scene.settledFrame(20, 16), reference, "other-$otherBlurs-restored")
                assertEquals(0, fixture.downloads)
            }
            assertEquals(0, fixture.sourceOwners)
        }
    }

    private inner class Fixture(val mode: ColorSchemeMode, val density: Float, val message: ChatMessage,
        val withClearSourceHost: Boolean = true) {
        var menuOpen by mutableStateOf(false)
        var otherOwnerBlurBackground by mutableStateOf<Boolean?>(null)
        private val otherOwner = Any()
        var bubble = Rect.Zero
        private var readBlurProgress: () -> Float = { 0f }
        val blurProgress: Float get() = readBlurProgress()
        var sourceMounts = 0
        var sourceOwners = 0
        var downloads = 0
        var dismissals = 0
        var quote: MessageQuote? = null
        val backdrop = ContextMenuBackdropState()
        private val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, message.chatId),
            selected = ChatItem(message.chatId, "Synthetic dismissal"), messages = listOf(message),
            actionsMessageId = message.id, messageActions = MessageActions(canForward = true),
            reactionMessageId = message.id, reactionOptions = MessageReactionOptions(listOf("👍"), canAdd = true))
        private val features = ChatFeatureActions(downloadFile = { _, _ -> downloads++ })
        private fun dismiss() { dismissals++; menuOpen = false }
        val scene = ComposePixelScene((840 * density).roundToInt(), (700 * density).roundToInt()) {
            val theme = remember { ThemeController(mode) }
            // Use the production Workspace target, duration, easing and radius. A Boolean mounted mask
            // would hide the short interval between the Popup exit and the remaining blur fade.
            val progress by animateFloatAsState(if (backdrop.hasExpandedBlurOwners) 1f else 0f,
                tween(180, easing = FastOutSlowInEasing), label = "context menu backdrop")
            SideEffect { readBlurProgress = { progress } }
            CompositionLocalProvider(LocalDensity provides Density(density),
                LocalDesktopChatLayout provides DesktopChatLayout(compact = true),
                LocalContextMenuBackdrop provides backdrop,
                LocalContextMenuBlurProgress provides { progress },
                LocalConversationMessageMenuViewport provides Rect(0f, 40f * density, 840f * density, 640f * density)) {
                otherOwnerBlurBackground?.let {
                    ContextMenuBackdropRegistration(expanded = true, blurBackground = it, owner = otherOwner)
                }
                MiuixTheme(controller = theme) {
                    Box(Modifier.requiredSize(840.dp, 700.dp).clipToBounds().background(Color(0xffadc99c))) {
                        Box(Modifier.fillMaxSize().focusProperties {
                            // Match Workspace: an expanded modal owns focus; its visual
                            // blur tail must let the next real message gesture acquire it.
                            canFocus = !backdrop.hasExpandedBlurOwners
                        }.graphicsLayer {
                            clip = true
                            val radius = 14.dp.toPx() * progress
                            renderEffect = if (radius > .01f) BlurEffect(radius, radius) else null
                        }.drawBehind {
                            for (index in 0..40) drawRect(if (index % 2 == 0) Color(0xff557b64) else Color(0xffc8d8b3),
                                topLeft = Offset(index * 24.dp.toPx(), 0f), size = Size(9.dp.toPx(), size.height))
                        }) {
                            Box(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 60.dp).clipToBounds()) {
                                Box(Modifier.offset(54.dp, 110.dp).width(732.dp)) {
                                    DisposableEffect(Unit) {
                                        sourceMounts++; sourceOwners++
                                        onDispose { sourceOwners-- }
                                    }
                                    MessageBubble(message, "", showSender = false, endsGroup = true,
                                        onBounds = { if (it != null) bubble = it }, retrying = false, highlighted = false,
                                        read = false, onRetry = {}, snapshot = snapshot, features = features,
                                        visible = true, animate = false, onEnlarge = {}, onSave = { _, _ -> },
                                        onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                                        playbackSession = null, onExpandVideo = {}, menuOpen = menuOpen,
                                        onMenuOpen = { menuOpen = true }, onMenuDismiss = ::dismiss,
                                        onReply = ::dismiss, onQuote = { quote = it; dismiss() }, onJumpToReply = {}, onReact = {},
                                        onReadDetails = {}, onEdit = {}, onForward = {}, onDelete = {}, onSearchSender = {},
                                        showSenderName = false, showAvatar = false, reserveAvatarSpace = false)
                                }
                            }
                        }
                        if (withClearSourceHost) MessageMenuClearSourceOverlay()
                    }
                }
            }
        }

        fun awaitPhoto(scene: ComposePixelScene) {
            scene.settledFrame()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (scene.descriptions("聊天图片").isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5)
                scene.settledFrame(1, 16)
            }
            assertEquals(1, scene.descriptions("聊天图片").size, "The real synthetic image must finish worker preparation.")
            scene.settledFrame(4, 16)
        }

        fun reference(scene: ComposePixelScene): Reference {
            scene.moveTo(820f * density, 680f * density)
            val frame = scene.settledFrame(4, 16)
            val photo = scene.descriptions("聊天图片").singleOrNull()
            val reaction = scene.reaction()
            val clear = if (photo != null) Rect(photo.left + 12f * density, photo.top + 6f * density,
                photo.right - 12f * density, photo.bottom - 30f * density)
            else inset(bubble, 15f * density, 4f * density)
            return Reference(frame, bubble, photo, reaction, clear,
                if (photo != null) photoArtworkPixels(frame, photo) else artworkPixels(frame))
        }

        fun open(scene: ComposePixelScene, reference: Reference) {
            val target = reference.photo ?: reference.bubble
            scene.secondaryClickAt(target.center.x, target.center.y)
            val opened = scene.settledFrame()
            assertTrue(menuOpen && backdrop.hasMountedMenus && backdrop.hasExpandedBlurOwners)
            assertEquals(1, scene.menus().size, "The actual native message Popup must be open.")
            assertTrue(!scene.menus().single().overlaps(reference.bubble), "The test source cannot be hidden behind menu rows.")
            assertTrue(blurProgress > .99f, "The production-shaped workspace blur must really be active.")
            assertFrame(scene, opened, reference, "fully-open")
        }

        fun dismissAndCapture(scene: ComposePixelScene, reference: Reference, stage: String) {
            val beforeDismissals = dismissals
            scene.clickBlank(density)
            var afterUnmount = 0
            var finished = false
            for (index in 0 until 48) {
                val frame = scene.settledFrame(1, 16)
                assertFalse(menuOpen, "A real pointer click in blank workspace must close the menu.")
                assertFrame(scene, frame, reference, "$stage-$index/blur=$blurProgress/mounted=${backdrop.hasMountedMenus}")
                if (!backdrop.hasMountedMenus && backdrop.clearSourcePreviews.isEmpty()) afterUnmount++
                if (afterUnmount >= 3 && blurProgress <= .001f) { finished = true; break }
            }
            assertTrue(dismissals > beforeDismissals, "The outside pointer must dispatch the real menu dismissal callback.")
            assertTrue(finished && afterUnmount >= 3,
                "Capture must continue through the complete blur fade and at least three frames after Popup unmount.")
            assertFalse(backdrop.hasMountedMenus)
            assertTrue(scene.menus().isEmpty() && scene.descriptions("原位消息").isEmpty())
            assertEquals(1, sourceMounts, "Dismissal must retain the original message and media composition.")
        }

        fun assertFrame(scene: ComposePixelScene, frame: ComposePixelFrame, reference: Reference, stage: String) {
            val label = "$stage/$mode/$density/${message.kind}"
            exportIfRequested(frame, label)
            assertEquals(reference.bubble, bubble, "The source position cannot move during the handoff: $label")
            assertEquals(reference.reaction, scene.reaction(), "The real reaction footer cannot move: $label")
            assertEquals(1, sourceOwners, "There must be exactly one original composition: $label")
            assertEquals(1, sourceMounts, "The message/media owner cannot be recreated: $label")
            if (reference.photo != null) {
                assertEquals(listOf(reference.photo), scene.descriptions("聊天图片"),
                    "Lifting or dismissing the source cannot recreate or duplicate the decoder: $label")
                val actualArtwork = photoArtworkPixels(frame, reference.photo)
                assertTrue(abs(reference.artworkPixels - actualArtwork) <= reference.artworkPixels * .005f,
                    "One sharp photo must remain painted once at every frame: $label, ${reference.artworkPixels} -> $actualArtwork")
            } else assertEquals(1, scene.textNodes(message.text).size, "The actual text composition must remain singular: $label")
            assertPixels(reference.frame, frame, reference.clear, label)
            assertPixels(reference.frame, frame, inset(reference.reaction, 10f * density, 3f * density), "$label/reaction")
        }

        fun photoArtworkPixels(frame: ComposePixelFrame, photo: Rect) = artworkPixels(frame, hoverMetadataStrip(photo))

        private fun hoverMetadataStrip(photo: Rect) = Rect(photo.left, photo.bottom - 30f * density,
            photo.right, photo.bottom)
    }

    private data class Reference(val frame: ComposePixelFrame, val bubble: Rect, val photo: Rect?,
        val reaction: Rect, val clear: Rect, val artworkPixels: Int)

    private fun ComposePixelScene.menus() = semanticsBounds {
        it.getOrNull(SemanticsProperties.PaneTitle) == "消息操作面板"
    }
    private fun ComposePixelScene.descriptions(label: String) = unmergedSemanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ComposePixelScene.reaction() = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.contains("条回应") }
    }.singleOrNull(), "The actual selected native reaction pill must remain in the message.")
    private fun ComposePixelScene.textNodes(text: String) = unmergedSemanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
    }
    private fun ComposePixelScene.button(label: String) = assertNotNull(semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }.singleOrNull(), "Missing actual message action: $label")
    private fun ComposePixelScene.caret(text: String, offset: Int): Offset {
        val layouts = mutableListOf<TextLayoutResult>()
        val bounds = unmergedSemanticsBounds {
            val matches = it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text }
            if (matches) it.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            matches
        }.single()
        val caret = layouts.single().getCursorRect(offset)
        return Offset(bounds.left + caret.left, bounds.top + caret.center.y)
    }
    private fun ComposePixelScene.clickBlank(density: Float) {
        moveTo(820f * density, 680f * density)
        pressAt(820f * density, 680f * density)
        releaseAt(820f * density, 680f * density)
    }
    private fun assertPixels(before: ComposePixelFrame, after: ComposePixelFrame, bounds: Rect, stage: String) {
        var count = 0
        var same = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                count++
                if (distance(before[x, y], after[x, y]) <= 2) same++
            }
        assertTrue(count > 100 && same >= count * .995f,
            "The original source must stay clear without one blurred, absent or doubled frame: $stage, $same/$count")
    }
    private fun artworkPixels(frame: ComposePixelFrame, hoverMetadata: Rect? = null): Int {
        var count = 0
        for (y in 0 until frame.height step 3) for (x in 0 until frame.width step 3) {
            // Moving to the real image trigger may legitimately show its native time overlay.
            // Exclude that fixed source strip while still detecting a second photo anywhere else.
            if (hoverMetadata != null && x >= hoverMetadata.left && x < hoverMetadata.right &&
                y >= hoverMetadata.top && y < hoverMetadata.bottom) continue
            val color = frame[x, y]
            if (distance(color, PHOTO_RED) <= 3 || distance(color, PHOTO_CYAN) <= 3) count++
        }
        return count
    }
    private fun exportIfRequested(frame: ComposePixelFrame, label: String) {
        val root = System.getProperty("nngGram.messageMenuDismissFrames") ?: return
        val directory = Path.of(root)
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve(label.replace('/', '-') + ".png").toFile()))
    }

    private companion object {
        const val PHOTO_RED = 0xffdb125d.toInt()
        const val PHOTO_CYAN = 0xff17c2e3.toInt()
        fun inset(bounds: Rect, horizontal: Float, vertical: Float) = Rect(bounds.left + horizontal,
            bounds.top + vertical, bounds.right - horizontal, bounds.bottom - vertical)
        fun distance(first: Int, second: Int) = maxOf(
            abs((first ushr 16 and 255) - (second ushr 16 and 255)),
            abs((first ushr 8 and 255) - (second ushr 8 and 255)),
            abs((first and 255) - (second and 255)),
        )
        fun textMessage() = ChatMessage(100, 10, text = "A stable original message\nText stays clear while the menu closes.",
            date = 1_700_000_000, canBeSaved = true, reactions = listOf(MessageReaction("👍", 2, true)))
        fun photoMessage(path: Path) = textMessage().copy(text = "", kind = MessageKind.PHOTO,
            media = MessageMedia(MediaFile(900, downloaded = true, localPath = path.toString()), width = 240, height = 156))
        fun withPhoto(action: (Path) -> Unit) {
            val path = Files.createTempFile("message-menu-dismiss-continuity-", ".png")
            try {
                val image = BufferedImage(240, 156, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width)
                    image.setRGB(x, y, if ((x / 3 + y / 3) % 2 == 0) PHOTO_RED else PHOTO_CYAN)
                check(ImageIO.write(image, "png", path.toFile()))
                action(path)
            } finally { Files.deleteIfExists(path) }
        }
    }
}
