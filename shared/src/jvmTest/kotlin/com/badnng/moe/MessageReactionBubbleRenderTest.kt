package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MessageReactionBubbleRenderTest {
    @Test fun corruptOrMissingCenterFilesKeepTheStaticGlyphAndFinishTheLocalEffectPromptly() {
        val directory = Files.createTempDirectory("synthetic-reaction-decode-failure-")
        try {
            val still = directory.resolve("static.png")
            val broken = directory.resolve("corrupt.tgs")
            Files.writeString(broken, "synthetic invalid TGS")
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xFF24BD76.toInt())
            check(ImageIO.write(bitmap, "png", still.toFile()))
            for (effectPath in listOf(broken, directory.resolve("missing.tgs"))) {
                val key = ReactionKey("🔥")
                val message = mutableStateOf(ChatMessage(1, 10, reactions = listOf(MessageReaction("🔥", 1))))
                val intents = ReactionAdditionIntents()
                val visual = ReactionVisual(key,
                    staticIcon = MessageMedia(MediaFile(1, localPath = still.toString(), downloaded = true), format = MediaFormat.PHOTO),
                    centerAnimation = MessageMedia(MediaFile(2, localPath = effectPath.toString(), downloaded = true), format = MediaFormat.TGS))
                ComposePixelScene(360, 240) {
                    val controller = remember { ThemeController(ColorSchemeMode.Light) }
                    val snapshot = ChatSnapshot(token = ChatToken(1, 1, 10), messages = listOf(message.value),
                        reactionVisuals = mapOf(key to visual))
                    CompositionLocalProvider(LocalReactionAdditionIntents provides intents) {
                        MiuixTheme(controller = controller) {
                            Box(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) {
                                MessageReactionRow(message.value, snapshot, ChatFeatureActions(), active = true) { Text("22:13") }
                            }
                        }
                    }
                }.use { scene ->
                    awaitFrame(scene) { frame -> count(frame) { isGreen(it) } > 100 }
                    intents.request(1, key, null)
                    scene.settledFrame(8)
                    val deadline = System.nanoTime() + 1_000_000_000L
                    while (intents.current != null && System.nanoTime() < deadline) {
                        assertTrue(count(scene.settledFrame(1)) { isGreen(it) } > 100,
                            "A failed center decoder must never hide the already decoded static artwork.")
                        Thread.sleep(5)
                    }
                    assertNull(intents.current, "A corrupt/missing center must finish without waiting for the four-second expiry.")
                    // A later independent native addition uses the same failure-safe renderer.
                    intents.acknowledge(1, key)
                    message.value = message.value.copy(reactions = listOf(MessageReaction("🔥", 1, true)))
                    repeat(8) {
                        assertTrue(count(scene.settledFrame(1)) { isGreen(it) } > 60,
                            "Native addition emphasis must keep its static glyph while failed effect decoding completes.")
                        Thread.sleep(5)
                    }
                    export(scene.settledFrame(), "addition-decode-failure-${effectPath.fileName}")
                }
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun interruptedEmphasisResetsGeometryAndRemovingAChosenReactionDoesNotLaunchAnAddition() {
        val message = mutableStateOf(ChatMessage(1, 10, reactions = listOf(MessageReaction("🔥", 1))))
        val active = mutableStateOf(true)
        val intents = ReactionAdditionIntents()
        val changes = mutableListOf<Boolean>()
        ComposePixelScene(360, 240) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalReactionAdditionIntents provides intents) {
                MiuixTheme(controller = controller) {
                    Box(Modifier.fillMaxSize().padding(40.dp)) {
                        MessageReactionRow(message.value, ChatSnapshot(token = ChatToken(1, 1, 10)),
                            ChatFeatureActions(setReaction = { _, _, _, add -> changes += add }), active.value) { Text("22:13") }
                    }
                }
            }
        }.use { scene ->
            fun chip() = assertNotNull(scene.semanticsBounds { node -> node.getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty().any { it.endsWith("条回应") } }.singleOrNull())
            scene.settledFrame()
            val initial = chip()
            message.value = message.value.copy(reactions = listOf(MessageReaction("🔥", 1, true)))
            scene.settledFrame(2)
            active.value = false
            scene.settledFrame(2)
            assertEquals(initial.size, chip().size, "Interrupting the pulse must restore natural chip geometry.")
            active.value = true
            scene.settledFrame(2)
            assertEquals(initial.size, chip().size, "Resuming must not replay an inactive native update.")
            scene.pressAt(chip().center.x, chip().center.y)
            scene.releaseAt(chip().center.x, chip().center.y)
            scene.settledFrame()
            assertEquals(listOf(false), changes)
            assertNull(intents.current, "Removing an existing selection must never launch an addition flight.")
        }
    }

    @Test fun allMessageKindsContainWrappedReactionsAndReserveTheirActualMetadataSpace() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            for ((index, kind) in listOf(MessageKind.TEXT, MessageKind.FILE, MessageKind.PHOTO, MessageKind.VIDEO).withIndex()) {
                // Official compact pills fit more choices on one line. Ten real
                // chips keep this fixture wider than every tested message card.
                val reactions = listOf("🔥", "👍", "❤️", "🎉", "😁", "🤔", "👌", "🤯", "😢").mapIndexed { i, emoji ->
                    MessageReaction(emoji, i + 1, isChosen = i == 0)
                } + MessageReaction(totalCount = 3, customEmojiId = 42)
                val message = ChatMessage(1, 10, outgoing = index % 2 == 1, date = 1_700_000_000,
                    text = "合成消息：正文和表情回应同属这个卡片", kind = kind, editDate = 1_700_000_001,
                    reactions = reactions, media = when (kind) {
                        MessageKind.FILE -> MessageMedia(MediaFile(1), format = MediaFormat.FILE, fileName = "synthetic-build.jar")
                        MessageKind.PHOTO -> MessageMedia(MediaFile(1), width = 240, height = 140)
                        MessageKind.VIDEO -> MessageMedia(MediaFile(1), width = 240, height = 140, format = MediaFormat.VIDEO)
                        else -> null
                    })
                var bubble = Rect.Zero
                ComposePixelScene((340 * density).roundToInt(), (600 * density).roundToInt()) {
                    val controller = remember { ThemeController(mode) }
                    val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10),
                        selected = ChatItem(10, "合成会话"), messages = listOf(message))
                    CompositionLocalProvider(LocalDensity provides Density(density),
                        LocalDesktopChatLayout provides DesktopChatLayout(compact = true)) {
                        MiuixTheme(controller = controller) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF6A987E)).padding(top = 24.dp)) {
                                Bubble(message, snapshot) { bubble = it }
                            }
                        }
                    }
                }.use { scene ->
                    val frame = scene.settledFrame()
                    val chips = scene.semanticsBounds { node -> node.getOrNull(SemanticsProperties.ContentDescription)
                        .orEmpty().any { it.endsWith("条回应") } }
                    assertEquals(reactions.size, chips.size)
                    val edited = assertNotNull(scene.semanticsBounds { node -> node.getOrNull(SemanticsProperties.Text)
                        .orEmpty().any { it.text == "已编辑" } }.singleOrNull())
                    val horizontalInset = (11 * density).roundToInt()
                    val bottomInset = (8 * density).roundToInt()
                    assertEquals(bubble.left + horizontalInset, chips.minOf { it.left },
                        "The first wrapped reaction uses the actual official 11dp message-content inset: $kind/$mode/$density")
                    chips.forEach { chip ->
                        assertTrue(chip.left >= bubble.left + horizontalInset && chip.right <= bubble.right - horizontalInset,
                            "Reaction chips must keep the official horizontal card inset: $kind/$mode/$density, chip=$chip, bubble=$bubble")
                        assertTrue(chip.bottom <= bubble.bottom - bottomInset && chip.top > bubble.top,
                            "The bottom surface must include every reaction with its official 8dp inset: $kind/$mode/$density, chip=$chip, bubble=$bubble")
                        assertFalse(chip.overlaps(edited), "The real timestamp/edit group must not overlap a reaction.")
                    }
                    assertTrue(chips.map { it.top }.distinct().size > 1, "Narrow bubbles must wrap their reaction chips.")
                    export(frame, "bubble-$kind-$mode-$density")
                }
            }
        }
    }

    @Test fun anImmediateChoiceFliesBeforeNativeConfirmationAndPlaysCenterAndUnclippedAroundExactlyOnce() {
        val directory = Files.createTempDirectory("synthetic-reaction-addition-")
        try {
            val still = directory.resolve("static.png")
            val center = directory.resolve("center.tgs")
            val around = directory.resolve("around.tgs")
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) bitmap.setRGB(x, y, 0xFF24BD76.toInt())
            check(ImageIO.write(bitmap, "png", still.toFile()))
            GZIPOutputStream(Files.newOutputStream(center)).use { it.write(vector("[0.9,0.1,0.1,1]", 10).toByteArray()) }
            // Around artwork must leave the center transparent. A filled disc
            // would conceal the center once both assets are correctly aligned.
            GZIPOutputStream(Files.newOutputStream(around)).use { it.write(vector("[0.1,0.2,0.95,1]", 28, outline = true).toByteArray()) }
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val key = ReactionKey("🔥")
            val message = mutableStateOf(ChatMessage(1, 10, date = 1_700_000_000, text = "合成消息"))
            val intents = ReactionAdditionIntents()
            fun media(id: Int, path: Path, format: MediaFormat) = MessageMedia(
                MediaFile(id, localPath = path.toString(), downloaded = true), format = format)
            val visual = ReactionVisual(key, staticIcon = media(1, still, MediaFormat.PHOTO),
                centerAnimation = media(2, center, MediaFormat.TGS), aroundAnimation = media(3, around, MediaFormat.TGS))
            ComposePixelScene((400 * density).roundToInt(), (300 * density).roundToInt()) {
                val controller = remember { ThemeController(mode) }
                val snapshot = ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10), messages = listOf(message.value),
                    reactionVisuals = mapOf(key to visual))
                CompositionLocalProvider(LocalReactionAdditionIntents provides intents, LocalDensity provides Density(density)) {
                    MiuixTheme(controller = controller) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                            .padding(start = 50.dp, top = 180.dp, end = 40.dp)) {
                            MessageReactionRow(message.value, snapshot, ChatFeatureActions(), active = true) { Text("22:13") }
                        }
                    }
                }
            }.use { scene ->
                scene.settledFrame()
                intents.request(1, key, Rect(250f * density, 25f * density, 282f * density, 57f * density))
                val flying = scene.settledFrame(3)
                export(flying, "addition-flight-before-native-$mode-$density")
                assertTrue(message.value.reactions.isEmpty(), "Immediate UI animation cannot synthesize server chosen/count state.")
                val landing = awaitFrame(scene) { frame -> count(frame) { isBlue(it) } > 30 && count(frame) { isRed(it) } > 10 }
                export(landing, "addition-center-around-before-native-$mode-$density")
                assertTrue(count(landing, top = (150 * density).roundToInt(), bottom = (178 * density).roundToInt()) { isBlue(it) } > 0,
                    "The around effect must be visible above the footer's own measured row, outside the chip clip.")
                Thread.sleep(1200)
                val ended = scene.settledFrame()
                assertNull(intents.current)
                assertEquals(0, count(ended) { isBlue(it) || isRed(it) })
                message.value = message.value.copy(reactions = listOf(MessageReaction("🔥", 1, true)))
                val confirmed = awaitFrame(scene) { frame -> count(frame) { isGreen(it) } > 10 }
                export(confirmed, "addition-native-confirmed-no-replay-$mode-$density")
                assertEquals(0, count(confirmed) { isRed(it) }, "The late native acknowledgement must not replay the center effect.")
                assertEquals(0, count(confirmed, bottom = (178 * density).roundToInt()) { isBlue(it) },
                    "The late native acknowledgement must not replay the around effect above the footer.")
            }
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Composable private fun Bubble(message: ChatMessage, snapshot: ChatSnapshot, onBounds: (Rect) -> Unit) {
        MessageBubble(message = message, sender = "合成发送者", showSender = false, endsGroup = true,
            onBounds = { if (it != null) onBounds(it) }, retrying = false, highlighted = false,
            read = false, onRetry = {}, snapshot = snapshot, features = ChatFeatureActions(),
            visible = false, animate = false, onEnlarge = {}, onSave = { _, _ -> },
            onPlay = {}, playing = false, onStopPlaying = {}, onVideoBounds = {},
            playbackSession = null, onExpandVideo = {}, menuOpen = false,
            onMenuOpen = {}, onMenuDismiss = {}, onReply = {}, onQuote = {},
            onJumpToReply = {}, onReact = {}, onReadDetails = {}, onEdit = {},
            onForward = {}, onDelete = {}, onSearchSender = {})
    }

    private fun awaitFrame(scene: ComposePixelScene, predicate: (ComposePixelFrame) -> Boolean): ComposePixelFrame {
        val deadline = System.nanoTime() + 3_000_000_000L
        do {
            val frame = scene.settledFrame(1)
            if (predicate(frame)) return frame
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        error("Synthetic native reaction assets never reached the expected frame.")
    }

    private fun count(frame: ComposePixelFrame, top: Int = 0, bottom: Int = frame.height, predicate: (Int) -> Boolean): Int {
        var total = 0
        for (y in top until bottom) for (x in 0 until frame.width) if (predicate(frame[x, y])) total++
        return total
    }

    private fun isRed(pixel: Int) = (pixel ushr 16 and 255) > 170 && (pixel ushr 8 and 255) < 100 && (pixel and 255) < 100
    private fun isBlue(pixel: Int) = (pixel and 255) > 170 && (pixel ushr 16 and 255) < 100 && (pixel ushr 8 and 255) < 100
    private fun isGreen(pixel: Int) = (pixel ushr 8 and 255) > 130 && (pixel ushr 16 and 255) < 80 && (pixel and 255) in 70..170

    private fun export(frame: ComposePixelFrame, name: String) {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("gradlew.bat")) }
        val directory = root.resolve("build/reactions-official-validation/frames")
        Files.createDirectories(directory)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) image.setRGB(x, y, frame[x, y])
        check(ImageIO.write(image, "png", directory.resolve("$name.png").toFile()))
    }

    private fun vector(color: String, diameter: Int, outline: Boolean = false): String {
        val paint = if (outline)
            """{"ty":"st","c":{"a":0,"k":$color},"o":{"a":0,"k":100},"w":{"a":0,"k":2},"lc":2,"lj":2}"""
        else """{"ty":"fl","c":{"a":0,"k":$color},"o":{"a":0,"k":100},"r":1}"""
        return """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":32,"h":32,"nm":"synthetic-native-reaction","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"circle","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[8,16,0],"e":[24,16,0],"i":{"x":[0.67],"y":[1]},"o":{"x":[0.33],"y":[0]}},{"t":30,"s":[24,16,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[$diameter,$diameter]},"d":1},$paint],"ip":0,"op":30,"st":0,"bm":0}]}"""
    }
}
