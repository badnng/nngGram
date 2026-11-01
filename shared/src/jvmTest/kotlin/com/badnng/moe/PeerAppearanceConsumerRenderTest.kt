package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Real message/reply/account consumers; generated PNGs are synthetic offline evidence only. */
class PeerAppearanceConsumerRenderTest {
    @Test fun actualSenderSlotUsesNativeNameColorsAndAnOrdinaryPrivateMessageStillHasNoSlot() {
        for (dark in listOf(false, true)) for (density in DENSITIES) for (private in listOf(false, true)) {
            val message = ChatMessage(1, 100, senderId = 7, date = 1_700_000_000, text = "Synthetic body")
            val chat = if (private) ChatItem(100, "Private", privateUserId = 7)
                else ChatItem(100, "Group", basicGroupId = 100)
            val snapshot = snapshot().copy(selected = chat, messages = listOf(message))
            var bubble = Rect.Zero
            scene(dark, density) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    val presentation = conversationMessagePresentation(message, chat, startsGroup = true, endsGroup = true)
                    MessageBubble(message, "Actual sender", true, true, { if (it != null) bubble = it },
                        retrying = false, highlighted = false, read = false, onRetry = {}, snapshot = snapshot,
                        features = ChatFeatureActions(), visible = false, animate = false, onEnlarge = {},
                        onSave = { _, _ -> }, onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
                        playbackSession = null, onExpandVideo = {}, menuOpen = false, onMenuOpen = {}, onMenuDismiss = {},
                        onReply = {}, onQuote = {}, onJumpToReply = {}, onReact = {}, onReadDetails = {}, onEdit = {},
                        onForward = {}, onDelete = {}, onSearchSender = {}, showSenderName = presentation.showSenderName,
                        showAvatar = presentation.showAvatar, reserveAvatarSpace = presentation.reserveAvatarSpace)
                }
            }.use { scene ->
                val frame = scene.settledFrame()
                val sender = scene.textBounds("Actual sender")
                if (private) {
                    assertTrue(sender.isEmpty(), "An account appearance must not invent an ordinary private-message name row.")
                    val body = assertNotNull(scene.textBounds(message.text).firstOrNull())
                    assertTrue(abs(body.left - bubble.left - 11 * density) <= 1,
                        "Private content retains its natural text gutter without an avatar reservation.")
                } else {
                    val name = assertNotNull(sender.singleOrNull())
                    assertTrue(countNear(frame, if (dark) USER_DARK else USER_LIGHT, name) > 2,
                        "The actual sender glyph must paint the correct native name color.")
                }
                profileRendererExport(frame, "sender-${if (private) "private" else "group"}-$dark-$density")
            }
        }
    }

    @Test fun actualReplyReferenceUsesTheForwardedOriginalsThreeColorOutlineInBothThemes() {
        for (dark in listOf(false, true)) for (density in DENSITIES) {
            val original = ChatMessage(1, 100, senderId = 7, text = "Original body",
                forwardInfo = MessageForwardInfo(MessageForwardOrigin.Chat(7)))
            val reply = MessageReply(100, 1, "One quote line\nTwo quote lines\nThree quote lines", senderId = 7, isQuote = true)
            val snapshot = snapshot().copy(messages = listOf(original))
            scene(dark, density) {
                Box(Modifier.padding(16.dp).width(280.dp)) { ReplyReference(reply, snapshot, active = false) }
            }.use { scene ->
                val frame = scene.settledFrame()
                val name = assertNotNull(scene.textBounds("Actual sender").singleOrNull())
                val palette = if (dark) CHAT_DARK else CHAT_LIGHT
                assertTrue(countNear(frame, palette.first(), name) > 2,
                    "The reply name's color follows the forwarded original peer even though the label is its actual sender.")
                val outline = Rect(22 * density, 22 * density, 25 * density, 88 * density)
                for (rgb in palette) assertTrue(countNear(frame, rgb, outline) > 1,
                    "The actual reply outline must retain every native stripe color: $dark/$density/${rgb.toString(16)}")
                profileRendererExport(frame, "reply-forwarded-three-colors-$dark-$density")
            }
        }
    }

    @Test fun actualAccountDialogUsesProfileBackgroundColorsIndependentlyFromTheNamePalette() {
        for (dark in listOf(false, true)) for (density in DENSITIES) {
            val snapshot = snapshot()
            scene(dark, density, width = 620, height = 700) {
                AccountProfileDialog(true, snapshot, ChatFeatureActions(), false) {}
            }.use { scene ->
                val frame = scene.settledFrame()
                assertTrue(scene.textBounds("Synthetic account").isNotEmpty())
                assertTrue(scene.textBounds("我的资料").isNotEmpty())
                val backgrounds = if (dark) PROFILE_DARK else PROFILE_LIGHT
                assertTrue(countNear(frame, backgrounds.first(), tolerance = 24) > 20,
                    "The actual account header must paint the independent native profile edge color.")
                assertTrue(countNear(frame, backgrounds.last(), tolerance = 24) > 20,
                    "The actual account header must paint the second native background color at the radial center.")
                val name = scene.textBounds("名称").single()
                val close = scene.textBounds("关闭").single()
                val right = close.center.x * 2 - name.left
                assertTrue(countNear(frame, backgrounds.first(), tolerance = 24,
                    bounds = Rect(right - 12 * density, name.top, right - 4 * density, name.bottom)) > 10,
                    "The profile background reaches the available dialog width independently of short field text.")
                profileRendererExport(frame, "account-dialog-radial-profile-$dark-$density")
            }
        }
    }

    private fun scene(dark: Boolean, density: Float, width: Int = 360, height: Int = 230,
        content: @Composable () -> Unit) = ComposePixelScene((width * density).roundToInt(), (height * density).roundToInt()) {
        val theme = remember { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
        CompositionLocalProvider(LocalDensity provides Density(density),
            LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize().background(if (dark) Color(0xff252525) else Color.White)) { content() }
            }
        }
    }

    private fun ComposePixelScene.textBounds(text: String) = unmergedSemanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }
    private fun countNear(frame: ComposePixelFrame, rgb: Int,
        bounds: Rect = Rect(0f, 0f, frame.width.toFloat(), frame.height.toFloat()), tolerance: Int = 9): Int {
        var count = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(frame.height))
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(frame.width)) {
                val pixel = frame[x, y]
                if (listOf(0, 8, 16).all { abs((pixel ushr it and 255) - (rgb ushr it and 255)) <= tolerance }) count++
            }
        return count
    }

    private fun snapshot(): ChatSnapshot {
        val profileLight = SettingsProfileAccentPalette(listOf(0xabcdef), PROFILE_LIGHT, listOf(0x123456, 0x654321))
        val profileDark = SettingsProfileAccentPalette(listOf(0xfedcba), PROFILE_DARK, listOf(0x654321, 0x123456))
        return ChatSnapshot(enabled = true, token = ChatToken(7, 1, 100),
            account = AccountProfile(77, "Synthetic account", "synthetic_name", appearance =
                SettingsProfileColorsValues(SettingsProfileColorValue(10), SettingsProfileColorValue(40))),
            senderNames = mapOf(7L to "Actual sender"), senderAppearances = mapOf(7L to values(10)),
            knownChats = listOf(ChatItem(7, "Original channel", appearance = values(11))),
            appearanceCatalog = SettingsProfileColorsCatalog(nameColors = listOf(
                SettingsNameAccentColor(10, 0, listOf(USER_LIGHT), listOf(USER_DARK)),
                SettingsNameAccentColor(11, 1, CHAT_LIGHT, CHAT_DARK)), availableNameColorIds = emptyList(),
                profileColors = listOf(SettingsProfileAccentColor(40, profileLight, profileDark))))
    }
    private fun values(id: Int) = SettingsProfileColorsValues(SettingsProfileColorValue(id), SettingsProfileColorValue(-1))

    companion object {
        private val DENSITIES = listOf(1f, 1.25f, 1.5f, 2f)
        private const val USER_LIGHT = 0xb01020
        private const val USER_DARK = 0xf58090
        private val CHAT_LIGHT = listOf(0xd02020, 0x20a020, 0x2040d0)
        private val CHAT_DARK = listOf(0xf06060, 0x50d050, 0x6080f0)
        private val PROFILE_LIGHT = listOf(0x9b160f, 0x0ed5c3)
        private val PROFILE_DARK = listOf(0x15196f, 0x9d4fd2)
    }
}

/** Runtime reports stay under the renderer draft until the parent explicitly integrates this batch. */
internal fun profileRendererExport(frame: ComposePixelFrame, name: String) {
    val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("gradlew.bat")) }
    val directory = root.resolve("build/profile-colors-renderer-draft/fixtures/render-reports")
    Files.createDirectories(directory)
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
    check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
}
