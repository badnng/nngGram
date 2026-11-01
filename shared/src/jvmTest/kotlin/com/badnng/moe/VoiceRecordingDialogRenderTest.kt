package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.badnng.moe.chat.*
import com.badnng.moe.media.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class VoiceRecordingDialogRenderTest {
    @Test
    fun microphoneHoldAndLockRecordsThenExplicitConfirmationSubmitsAndRetainsTheNativeInput() = runBlocking {
        val directory = Files.createTempDirectory("voice-dialog-test-")
        var opened = 0
        var sent = 0
        var held: AttachmentLease? = null
        var sentPath: String? = null
        var recorder: VoiceRecordingSession? = null
        val captured = CountDownLatch(1)
        val chat = ChatItem(10, "合成录音会话")
        var snapshot by mutableStateOf(ChatSnapshot(enabled = true, token = ChatToken(1, 1, 10), selected = chat, historyEnd = true))
        ComposePixelScene(820, 640) {
            val controller = remember { ThemeController(ColorSchemeMode.Light) }
            CompositionLocalProvider(LocalVoiceRecordingFactory provides {
                opened++
                VoiceRecordingSession({ object : VoicePcmInput {
                    var blocks = 0
                    override fun read(destination: ByteArray): Int {
                        if (blocks++ >= 10) { captured.countDown(); return 0 }
                        destination.fill(1)
                        return destination.size
                    }
                    override fun stop() = Unit
                    override fun close() = Unit
                } }, { _, output -> Files.write(output, byteArrayOf(79, 103, 103, 83, 1)) }, directory).also { recorder = it }
            }) {
                MiuixTheme(controller = controller) {
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                        Box(Modifier.fillMaxSize()) {
                            ConversationPane(snapshot, chat, TextFieldValue(), {}, null, {}, {}, {}, {},
                                ChatFeatureActions(sendVoiceNote = { token, draft ->
                                    assertEquals(snapshot.token, token)
                                    held = assertNotNull(draft.lease.retain())
                                    sentPath = draft.path
                                    sent++
                                    snapshot = snapshot.copy(acceptedSendId = snapshot.acceptedSendId + 1,
                                        acceptedSendChatId = chat.id, acceptedSendText = null)
                                }), active = true, onSearch = {}, onSearchSender = {}, onAdmins = {}, onInfo = {}, showInfoButton = true)
                        }
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertEquals(0, opened, "Composing a conversation must never open a microphone.")
            val mic = scene.semanticsBounds { "录制语音" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.first()
            scene.pressAt(mic.center.x, mic.center.y)
            scene.settledFrame(2)
            scene.dragTo(mic.center.x, mic.center.y - 96f)
            scene.settledFrame(2)
            scene.releaseAt(mic.center.x, mic.center.y - 96f)
            val lockedFrame = scene.settledFrame()
            val preview = BufferedImage(lockedFrame.width, lockedFrame.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until lockedFrame.height) for (x in 0 until lockedFrame.width) preview.setRGB(x, y, lockedFrame[x, y])
            check(ImageIO.write(preview, "png", Path.of(System.getProperty("java.io.tmpdir"), "voice-inline-recording.png").toFile()))
            assertEquals(1, opened)
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            assertEquals(0, sent)
            val stop = scene.button("停止录音")
            scene.pressAt(stop.center.x, stop.center.y)
            scene.releaseAt(stop.center.x, stop.center.y)
            scene.settledFrame()
            withTimeout(5_000) { assertNotNull(recorder).state.first { it.stage == VoiceRecordingStage.READY } }
            scene.settledFrame()
            assertEquals(0, sent, "Stopping the microphone is not authorization to send a message.")
            val send = scene.button("发送语音")
            scene.pressAt(send.center.x, send.center.y)
            scene.releaseAt(send.center.x, send.center.y)
            scene.settledFrame()
            assertEquals(1, sent)
            assertTrue(Files.exists(Path.of(assertNotNull(sentPath))))
            assertTrue(scene.semanticsBounds {
                "发送语音" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.isEmpty())
        }
        assertNotNull(held).release()
        withTimeout(5_000) { while (Files.exists(directory)) kotlinx.coroutines.delay(5) }
    }

    private fun ComposePixelScene.button(text: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Role) == Role.Button &&
            (it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text } ||
                text in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
    }.first()
}
