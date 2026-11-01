package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.AttachmentLease
import com.badnng.moe.media.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class VoiceRecordingBarRenderTest {
    @Test
    fun releasingAHeldRecordingWaitsForEncodingAndDispatchesOnlyOnce() = runBlocking {
        val directory = Files.createTempDirectory("voice-bar-held-")
        val captured = CountDownLatch(1)
        val encodeEntered = CountDownLatch(1)
        val finishEncoding = CountDownLatch(1)
        val control = VoiceRecordingControl()
        var shown by mutableStateOf(false)
        var opened = 0
        var sent = 0
        var held: AttachmentLease? = null
        var session: VoiceRecordingSession? = null
        ComposePixelScene(400, 240) {
            CompositionLocalProvider(LocalVoiceRecordingFactory provides {
                opened++
                VoiceRecordingSession({ FakeBarInput(captured) }, { _, output ->
                    encodeEntered.countDown()
                    check(finishEncoding.await(3, TimeUnit.SECONDS))
                    Files.write(output, byteArrayOf(79, 103, 103, 83, 1))
                }, directory).also { session = it }
            }) {
                MiuixTheme(colors = lightColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        VoiceRecordingBar(shown, false, null, { shown = false }, { draft ->
                            held = assertNotNull(draft.lease.retain())
                            sent++
                        }, Modifier.width(320.dp), control)
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertEquals(0, opened, "A mounted conversation/control must not open a device before a user action.")
            control.beginHeldRecording()
            shown = true
            scene.settledFrame()
            assertEquals(1, opened)
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            control.send()
            scene.settledFrame()
            assertTrue(encodeEntered.await(3, TimeUnit.SECONDS))
            assertEquals(0, sent, "Releasing must wait for a real encoded draft rather than sending the input buffer.")
            finishEncoding.countDown()
            withTimeout(5_000) { assertNotNull(session).state.first { it.stage == VoiceRecordingStage.READY } }
            scene.settledFrame()
            assertEquals(1, sent)
            scene.settledFrame()
            assertEquals(1, sent, "Recomposition after READY must not repeat the held-release submission.")
            shown = false
            scene.settledFrame()
            assertTrue(Files.exists(directory.resolve("voice.ogg")), "The native send handle must outlive the bar.")
        }
        assertNotNull(held).release()
        withTimeout(5_000) { while (Files.exists(directory)) delay(5) }
    }

    @Test
    fun lockingAndStoppingKeepAnInlineReviewUntilAnExplicitSend() = runBlocking {
        val directory = Files.createTempDirectory("voice-bar-review-")
        val captured = CountDownLatch(1)
        val control = VoiceRecordingControl()
        var shown by mutableStateOf(true)
        var pending by mutableStateOf(false)
        var sent = 0
        var session: VoiceRecordingSession? = null
        ComposePixelScene(320, 220) {
            CompositionLocalProvider(LocalVoiceRecordingFactory provides {
                VoiceRecordingSession({ FakeBarInput(captured) }, { _, output ->
                    Files.write(output, byteArrayOf(79, 103, 103, 83, 2))
                }, directory).also { session = it }
            }) {
                MiuixTheme(colors = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        VoiceRecordingBar(shown, pending, null, { shown = false }, { sent++ },
                            Modifier.width(260.dp), control)
                    }
                }
            }
        }.use { scene ->
            scene.settledFrame()
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            control.lock()
            scene.settledFrame()
            assertTrue(scene.descriptionBounds("录音已锁定").isNotEmpty())
            val bar = scene.descriptionBounds("语音录制栏").single()
            assertTrue(bar.top > 100 && bar.bottom <= 220, "The recording state belongs at the composer, not the window center.")
            control.review()
            scene.settledFrame()
            withTimeout(5_000) { assertNotNull(session).state.first { it.stage == VoiceRecordingStage.READY } }
            scene.settledFrame()
            assertEquals(0, sent, "Stopping for review must never submit a voice note.")
            val send = scene.descriptionBounds("发送语音").single()
            scene.pressAt(send.center.x, send.center.y)
            scene.releaseAt(send.center.x, send.center.y)
            scene.settledFrame()
            assertEquals(1, sent)
            pending = true
            scene.settledFrame()
            control.send()
            scene.settledFrame()
            pending = false
            scene.settledFrame()
            assertEquals(1, sent, "A SEND command during a pending native submission must not become an automatic retry.")
            control.cancel()
            scene.settledFrame()
            assertFalse(shown)
        }
        withTimeout(5_000) { while (Files.exists(directory)) delay(5) }
    }

    private fun ComposePixelScene.descriptionBounds(label: String) = semanticsBounds {
        label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private class FakeBarInput(private val captured: CountDownLatch) : VoicePcmInput {
        private var blocks = 0
        override fun read(destination: ByteArray): Int {
            if (blocks++ >= 10) { captured.countDown(); return 0 }
            destination.fill(1)
            return destination.size
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
