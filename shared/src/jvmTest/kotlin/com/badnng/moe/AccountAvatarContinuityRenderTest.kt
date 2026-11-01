package com.badnng.moe

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
import com.badnng.moe.status.ConnectionPhase
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AccountAvatarContinuityRenderTest {
    @Test
    fun menuAndProfilePhotosAreReadyOnTheirFirstLayoutWithoutOpeningTheFileAgain() {
        for (density in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) withAvatarPhoto { photo ->
                val state = mutableStateOf(photoSnapshot(photo))
                avatarScene(1160, density, mode, state).use { scene ->
                    scene.settledFrame()
                    val trigger = assertNotNull(scene.triggerBounds("打开账号菜单").singleOrNull())
                    scene.awaitPhoto(trigger, density)
                    Files.delete(photo)
                    scene.click(trigger)
                    var sawHeader = false
                    repeat(7) {
                        scene.settledFrame(1)
                        if (scene.textBounds("合成照片账号").isNotEmpty()) {
                            sawHeader = true
                            assertNotNull(scene.photoBounds().firstOrNull { bounds ->
                                bounds.center.y > trigger.bottom + 10f * density
                            }, "The newly composed menu header must immediately draw the already decoded photo.")
                        }
                    }
                    assertTrue(sawHeader, "The real menu opening must actually compose its header.")
                    val opened = scene.settledFrame()
                    val header = assertNotNull(scene.photoBounds().firstOrNull {
                        it.center.y > trigger.bottom + 10f * density
                    })
                    assertPhoto(opened, header, density, "Menu header after removing the synthetic source")

                    scene.click(assertNotNull(scene.buttonTextBounds("我的资料").firstOrNull()))
                    var sawProfile = false
                    repeat(7) {
                        scene.settledFrame(1)
                        if (scene.textBounds("Telegram 账号资料").isNotEmpty()) {
                            sawProfile = true
                            assertNotNull(scene.photoBounds().firstOrNull { it.width > 50f * density },
                                "The profile's first layout must use the shared decoded photo, without a loading placeholder.")
                        }
                    }
                    assertTrue(sawProfile, "The real profile action must open its dialog.")
                    val profile = assertNotNull(scene.photoBounds().firstOrNull { it.width > 50f * density })
                    assertPhoto(scene.settledFrame(), profile, density, "Profile photo after removing the synthetic source")
                }
            }
        }
    }

    @Test
    fun changingTheClientGenerationAccountOrFileDoesNotKeepThePreviousDecodedPhoto() {
        for (identityPart in listOf("generation", "account", "file")) withAvatarPhoto { photo ->
            val state = mutableStateOf(photoSnapshot(photo))
            avatarScene(1160, 1f, ColorSchemeMode.Light, state).use { scene ->
                scene.settledFrame()
                val trigger = assertNotNull(scene.triggerBounds("打开账号菜单").singleOrNull())
                scene.awaitPhoto(trigger, 1f)
                Files.delete(photo)
                val current = state.value
                val account = assertNotNull(current.account)
                val file = assertNotNull(account.avatar)
                state.value = when (identityPart) {
                    "generation" -> current.copy(token = current.token.copy(generation = current.token.generation + 1))
                    "account" -> current.copy(account = account.copy(userId = account.userId + 1))
                    else -> current.copy(mediaFiles = mapOf(file.id to file.copy(size = file.size + 1)))
                }
                scene.settledFrame()
                assertTrue(scene.photoBounds().isEmpty(),
                    "Changing $identityPart must replace the old owner instead of carrying its decoded account image forward.")
            }
        }
    }

    @Test
    fun decodedAccountPhotoSurvivesEveryOpeningAndClosingFrameWithoutRemounting() {
        for (widthDp in listOf(1494, 380)) for (densityScale in listOf(1f, 1.25f)) {
            for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) withAvatarPhoto { photo ->
                val state = mutableStateOf(photoSnapshot(photo))
                avatarScene(widthDp, densityScale, mode, state).use { scene ->
                    scene.settledFrame()
                    val trigger = assertNotNull(scene.triggerBounds("打开账号菜单").singleOrNull())
                    scene.awaitPhoto(trigger, densityScale)
                    // Removing only this synthetic source after the first decode makes decoder
                    // reuse observable: a newly mounted MediaPreview cannot reload the photo.
                    Files.delete(photo)
                    repeat(2) {
                        scene.click(trigger)
                        repeat(7) { frame ->
                            assertPhoto(scene.settledFrame(1), trigger, densityScale, "Opening frame $frame: $widthDp/$densityScale/$mode")
                        }
                        assertEquals(1, scene.triggerBounds("关闭账号菜单").size, "Only the persistent trigger owns avatar input.")
                        scene.click(trigger)
                        repeat(7) { frame ->
                            assertPhoto(scene.settledFrame(1), trigger, densityScale, "Closing frame $frame: $widthDp/$densityScale/$mode")
                        }
                        assertEquals(1, scene.triggerBounds("打开账号菜单").size)
                    }
                }
            }
        }
    }

    @Test
    fun navigatingToANarrowConversationRemovesTheFloatingAccountTriggerAndReturningRestoresIt() = withAvatarPhoto { photo ->
        val chat = ChatItem(20, "合成头像回归会话")
        val state = mutableStateOf(photoSnapshot(photo).copy(items = listOf(chat), knownChats = listOf(chat)))
        avatarScene(380, 1f, ColorSchemeMode.Light, state, onSelect = { token, chatId ->
            state.value = state.value.copy(token = token.copy(selection = token.selection + 1, chatId = chatId), selected = chat)
        }).use { scene ->
            scene.settledFrame()
            val trigger = assertNotNull(scene.triggerBounds("打开账号菜单").singleOrNull())
            scene.awaitPhoto(trigger, 1f)
            val chatRow = assertNotNull(scene.textBounds(chat.title).singleOrNull())
            scene.click(chatRow)
            scene.settledFrame()
            assertTrue(scene.triggerBounds("打开账号菜单").isEmpty() && scene.triggerBounds("关闭账号菜单").isEmpty(),
                "The previous list coordinates must not leave an account trigger above a narrow conversation.")
            val back = assertNotNull(scene.descriptionBounds("返回聊天列表").firstOrNull())
            scene.click(back)
            scene.settledFrame()
            val restored = assertNotNull(scene.triggerBounds("打开账号菜单").singleOrNull())
            scene.awaitPhoto(restored, 1f)
        }
    }

    private fun avatarScene(widthDp: Int, densityScale: Float, mode: ColorSchemeMode, state: State<ChatSnapshot>,
        onSelect: (ChatToken, Long) -> Unit = { _, _ -> }) =
        ComposePixelScene((widthDp * densityScale).roundToInt(), (840 * densityScale).roundToInt()) {
            val controller = remember { ThemeController(mode) }
            val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
            CompositionLocalProvider(LocalDensity provides Density(densityScale)) {
                MiuixTheme(controller = controller) {
                    // Exercise window-to-workspace coordinates with the actual title-bar offset.
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(44.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            ChatWorkspace(state.value, ConnectionPhase.READY, drafts, onSettings = {}, onSelectChat = onSelect,
                                onLoadChats = {}, onLoadOlder = {}, onSendText = { _, _, _ -> }, onRetryMessage = { _, _ -> },
                                onMarkRead = {}, windowFocused = true, features = ChatFeatureActions(),
                                darkTheme = mode == ColorSchemeMode.Dark, onDarkTheme = {}, persistUiPreferences = false)
                        }
                    }
                }
            }
        }

    private fun photoSnapshot(photo: Path) = ChatSnapshot(enabled = true, token = ChatToken(1, 0, null),
        account = AccountProfile(900001, "合成照片账号", avatar = MediaFile(700, downloaded = true, localPath = photo.toString())),
        listEnd = true, historyEnd = true)

    private fun ComposePixelScene.awaitPhoto(trigger: Rect, density: Float) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            if (hasPhoto(settledFrame(2), trigger, density)) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("The isolated synthetic account photo did not finish decoding.")
    }

    private fun assertPhoto(frame: ComposePixelFrame, trigger: Rect, density: Float, detail: String) {
        assertTrue(hasPhoto(frame, trigger, density), "The decoded photo disappeared or moved during $detail")
    }

    private fun hasPhoto(frame: ComposePixelFrame, trigger: Rect, density: Float): Boolean {
        val x = trigger.center.x.roundToInt()
        val y = trigger.center.y.roundToInt()
        val center = frame[x, y]
        val wing = frame[(trigger.center.x - 8 * density).roundToInt(), y]
        // Allow the native Miuix press/hold feedback to shade the image. Its cyan center
        // and green wing still distinguish a photo from fallback text or an empty circle.
        return (center ushr 16 and 255) < 90 && (center ushr 8 and 255) > 130 && (center and 255) > 155 &&
            (wing ushr 16 and 255) < 90 && (wing ushr 8 and 255) > 120 && (wing and 255) < 150
    }

    private fun ComposePixelScene.triggerBounds(description: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.descriptionBounds(description: String): List<Rect> = semanticsBounds { config ->
        description in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.photoBounds(): List<Rect> = unmergedSemanticsBounds { config ->
        "我的头像" in config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun ComposePixelScene.textBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.buttonTextBounds(text: String): List<Rect> = semanticsBounds { config ->
        config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    private fun ComposePixelScene.click(bounds: Rect) {
        moveTo(bounds.center.x, bounds.center.y)
        pressAt(bounds.center.x, bounds.center.y)
        releaseAt(bounds.center.x, bounds.center.y)
    }

    private fun withAvatarPhoto(test: (Path) -> Unit) {
        val photo = Files.createTempFile("nnggram-avatar-continuity-", ".png")
        try {
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 64) for (x in 0 until 64) {
                image.setRGB(x, y, if (x in 28..35) 0xff0ccde9.toInt() else 0xff29c478.toInt())
            }
            check(ImageIO.write(image, "png", photo.toFile()))
            test(photo)
        } finally {
            Files.deleteIfExists(photo)
        }
    }
}
