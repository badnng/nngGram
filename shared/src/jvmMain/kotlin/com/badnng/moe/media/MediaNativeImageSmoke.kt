package com.badnng.moe.media

import com.badnng.moe.chat.ChatError
import com.badnng.moe.chat.ChatResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit offline EXE acceptance: synthetic video, private loopback, raster output, no audio device. */
object MediaNativeImageSmoke {
    fun run(report: Path) = runBlocking {
        require(report.isAbsolute && !Files.exists(report) && Files.isDirectory(report.parent))
        val directory = Files.createTempDirectory(report.parent, "synthetic-native-video-")
        val file = directory.resolve("fixture.mp4")
        var stage = "fixture"
        var passed = false
        try {
            val ffmpeg = checkNotNull(MediaDecoder.findFfmpeg())
            MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=64x48:r=30:d=2.4", "-vf", "drawbox=color=red:t=fill:enable='gte(t,1)'",
                "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-y", file.toString()), timeoutSeconds = 15)
            RandomAccessFile(file.toFile(), "rw").use {
                val original = it.length()
                it.seek(original); it.writeInt(32 * 1024 * 1024); it.writeBytes("free")
                it.setLength(original + 32 * 1024 * 1024)
            }
            val bytesRead = AtomicLong()
            val blocked = AtomicBoolean()
            FileChannel.open(file).use { channel ->
                ProgressiveVideoSource(Files.size(file)) { offset, count, callback ->
                    if (blocked.get()) callback(ChatResult(error = ChatError.NOT_READY))
                    else {
                        val buffer = ByteBuffer.allocate(count)
                        val countRead = channel.read(buffer, offset)
                        if (countRead > 0) {
                            bytesRead.addAndGet(countRead.toLong())
                            callback(ChatResult(buffer.array().copyOf(countRead)))
                        } else callback(ChatResult(error = ChatError.REQUEST_FAILED))
                    }
                }.use { source ->
                    stage = "stream_first_frame"
                    InlineVideoPlayer("", autoPlay = false, progressiveSource = source, audioFactory = { error("SILENT_FIXTURE") }).use { player ->
                        waitFor(player) { it.ready && !it.loading && player.hasFrame() }
                        check(bytesRead.get() < Files.size(file))
                        val blue = centerColor(player)
                        check((blue and 255) > 180 && ((blue ushr 16) and 255) < 60)
                        stage = "buffered_seek"
                        blocked.set(true)
                        player.seek(1.6)
                        waitFor(player) { it.buffering }
                        blocked.set(false)
                        waitFor(player) { !it.loading && !it.buffering }
                        val red = centerColor(player)
                        check(((red ushr 16) and 255) > 180 && (red and 255) < 60)
                        stage = "playback_clock"
                        player.setVolume(0f)
                        val resumedFrom = player.currentPosition()
                        player.play()
                        // A retained raster keeps loading=false while the replacement decoder
                        // starts. Observe the real clock, rather than treating that UI flag as it.
                        waitFor(player) { !it.loading && it.playing && player.currentPosition() > resumedFrom }
                        val position = player.currentPosition()
                        delay(45)
                        check(player.currentPosition() > position)
                        player.pause()
                        val paused = player.currentPosition()
                        delay(75)
                        check(player.currentPosition() == paused)
                        stage = "play_to_end"
                        player.play()
                        waitFor(player) { it.ended }
                        check(!player.state.value.failed)
                        passed = true
                        stage = "complete"
                    }
                }
            }
        } finally {
            repeat(50) {
                if (Files.exists(file)) runCatching { Files.deleteIfExists(file) }
                if (Files.exists(file)) delay(20)
            }
            runCatching { Files.deleteIfExists(directory) }
            val native = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
            Files.writeString(report, "{\"kind\":\"media_native_smoke\",\"passed\":$passed,\"stage\":\"$stage\",\"nativeImage\":$native,\"accountAccessed\":false,\"audioDeviceOpened\":false}\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        check(passed) { "MEDIA_NATIVE_SMOKE_FAILED" }
    }

    private suspend fun waitFor(player: InlineVideoPlayer, predicate: (VideoPlayerState) -> Boolean) = withTimeout(20_000) {
        while (!predicate(player.state.value)) {
            check(!player.state.value.failed) { "SYNTHETIC_VIDEO_FAILED" }
            delay(20)
        }
    }

    private fun centerColor(player: InlineVideoPlayer): Int = Surface.makeRasterN32Premul(64, 48).use { surface ->
        player.draw(surface.canvas, 64f, 48f)
        surface.makeImageSnapshot().use { image ->
            Bitmap().use { pixels ->
                check(pixels.allocN32Pixels(64, 48))
                check(image.readPixels(pixels))
                pixels.getColor(32, 24)
            }
        }
    }
}
