package com.badnng.moe.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.badnng.moe.ChatFeatureActions
import com.badnng.moe.ChatWorkspace
import com.badnng.moe.chat.*
import com.badnng.moe.media.InlineVideoPlayer
import com.badnng.moe.media.LocalMediaDrawObserver
import com.badnng.moe.media.MediaDecoder
import com.badnng.moe.media.MediaTools
import com.badnng.moe.status.ConnectionPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

enum class IdleProbeMode { STATIC, DIALOG, ANIMATION, VIDEO }

/** Explicit opt-in only. The entry point must branch before constructing configuration or TDLib. */
data class IdleProbeConfig(
    val mode: IdleProbeMode,
    val output: Path,
    val seconds: Int = 30,
    val warmupSeconds: Int = 5,
) {
    init {
        require(output.isAbsolute) { "The idle probe output must be an absolute file path." }
        require(seconds in 5..120 && warmupSeconds in 0..60) { "Invalid idle probe duration." }
    }

    override fun toString() = "IdleProbeConfig(mode=$mode, seconds=$seconds, output=<redacted>)"

    companion object {
        fun parse(args: Array<String>): IdleProbeConfig? {
            val options = args.filter { it.startsWith("--idle-probe") }
            if (options.isEmpty()) return null
            val values = options.associate { option ->
                require('=' in option) { "Idle probe arguments require =value." }
                option.substringBefore('=') to option.substringAfter('=')
            }
            require(values.size == options.size && values.keys.all { it in setOf(
                "--idle-probe", "--idle-probe-output", "--idle-probe-seconds", "--idle-probe-warmup-seconds",
            ) }) { "Unknown or repeated idle probe argument." }
            val mode = when (values["--idle-probe"]) {
                "static" -> IdleProbeMode.STATIC
                "dialog" -> IdleProbeMode.DIALOG
                "animation" -> IdleProbeMode.ANIMATION
                "video" -> IdleProbeMode.VIDEO
                else -> error("Select static, dialog, animation or video for the idle probe.")
            }
            val output = values["--idle-probe-output"] ?: error("The idle probe needs --idle-probe-output.")
            fun seconds(key: String, fallback: Int) = values[key]?.let {
                it.toIntOrNull() ?: error("Invalid idle probe duration.")
            } ?: fallback
            return IdleProbeConfig(mode, Path.of(output), seconds("--idle-probe-seconds", 30),
                seconds("--idle-probe-warmup-seconds", 5))
        }
    }
}

/** Counts are atomics, not Compose state: observing the UI must not schedule redraws itself. */
internal class IdleProbeRecorder(private val nanoTime: () -> Long = System::nanoTime) {
    private val draws = AtomicLong()
    private val mediaDraws = AtomicLong()
    private val dialogDraws = AtomicLong()
    private var active = false
    private var measuring = false
    private var startNanos = 0L
    private var changedNanos = 0L
    private var activeNanos = 0L
    private var firstDraw = 0L
    private var firstMediaDraw = 0L
    private var firstDialogDraw = 0L

    fun draw() { draws.incrementAndGet() }
    fun mediaDraw() { mediaDraws.incrementAndGet() }
    fun dialogDraw() { dialogDraws.incrementAndGet() }
    fun hasDrawn() = draws.get() > 0
    fun drawCount() = draws.get()
    fun hasMediaDrawn() = mediaDraws.get() > 0
    fun hasDialogDrawn() = dialogDraws.get() > 0

    @Synchronized fun windowActive(value: Boolean) {
        val now = nanoTime()
        if (measuring && active) activeNanos += (now - changedNanos).coerceAtLeast(0)
        active = value
        changedNanos = now
    }

    @Synchronized fun begin() {
        startNanos = nanoTime()
        changedNanos = startNanos
        activeNanos = 0
        firstDraw = draws.get()
        firstMediaDraw = mediaDraws.get()
        firstDialogDraw = dialogDraws.get()
        measuring = true
    }

    @Synchronized fun finish(): IdleProbeMeasurement {
        val now = nanoTime()
        if (measuring && active) activeNanos += (now - changedNanos).coerceAtLeast(0)
        measuring = false
        return IdleProbeMeasurement((now - startNanos).coerceAtLeast(0) / 1_000_000,
            activeNanos / 1_000_000, draws.get() - firstDraw, mediaDraws.get() - firstMediaDraw,
            dialogDraws.get() - firstDialogDraw)
    }
}

internal data class IdleProbeMeasurement(
    val elapsedMillis: Long,
    val activeMillis: Long,
    val rootDraws: Long,
    val mediaDraws: Long,
    val dialogDraws: Long = 0,
) {
    val activeFraction: Double get() = if (elapsedMillis > 0) activeMillis.toDouble() / elapsedMillis else 0.0
}

/** Finish all measurement and file work inside this block before resuming the scene. */
internal suspend fun runIdleProbeOffScene(block: suspend CoroutineScope.() -> Unit) =
    withContext(Dispatchers.Default, block)

/** Synthetic chat UI only. No engine, credentials, saved settings, real messages, or sound device. */
@Composable
fun IdleProbeContent(
    config: IdleProbeConfig,
    windowActive: Boolean,
    modifier: Modifier = Modifier,
    onComplete: () -> Unit,
    windowActivity: Flow<Boolean>? = null,
) {
    val recorder = remember(config) { IdleProbeRecorder() }
    val assetHolder = remember(config) { ProbeAssetHolder() }
    val complete by rememberUpdatedState(onComplete)
    var assets by remember(config) { mutableStateOf<ProbeAssets?>(null) }
    val active by rememberUpdatedState(windowActive)
    var themeMode by remember { mutableStateOf(ColorSchemeMode.Light) }
    val theme = remember(themeMode) { ThemeController(themeMode) }
    DisposableEffect(assetHolder) { onDispose { assetHolder.close() } }

    LaunchedEffect(config) {
        // The scene dispatcher stops draining its queue while minimized. Both the
        // deadline and report must finish here, not after returning to that dispatcher.
        runIdleProbeOffScene {
            val activityJob = windowActivity?.let { activity ->
                launch { activity.collect { recorder.windowActive(it) } }
            }
            try {
                var status = "complete"
                var started = 0L
                var ended = 0L
                var measurement = IdleProbeMeasurement(0, 0, 0, 0)
                try {
                    withContext(Dispatchers.IO) {
                        require(!Files.exists(config.output)) { "Idle probe output already exists." }
                        Files.createDirectories(config.output.parent)
                        writeProbeFile(config.output.resolveSibling(config.output.fileName.toString() + ".ready.json"),
                            """{"pid":${ProcessHandle.current().pid()},"mode":"${config.mode.name.lowercase()}","seconds":${config.seconds},"warmupSeconds":${config.warmupSeconds}}""")
                    }
                    val previousDraws = recorder.drawCount()
                    assets = withContext(Dispatchers.IO) { assetHolder.install(createProbeAssets(config)) }
                    withTimeout(15_000) {
                        while (recorder.drawCount() <= previousDraws ||
                            config.mode in setOf(IdleProbeMode.ANIMATION, IdleProbeMode.VIDEO) && !recorder.hasMediaDrawn() ||
                            config.mode == IdleProbeMode.DIALOG && !recorder.hasDialogDrawn() ||
                            config.mode == IdleProbeMode.VIDEO && assets?.player?.hasFrame() != true) delay(25)
                    }
                    delay(config.warmupSeconds * 1_000L)
                    recorder.begin()
                    started = System.currentTimeMillis()
                    delay(config.seconds * 1_000L)
                    ended = System.currentTimeMillis()
                    measurement = recorder.finish()
                    if (assets?.player?.state?.value?.failed == true) status = "media_failed"
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    status = "surface_or_media_unavailable"
                } catch (_: Exception) {
                    status = "setup_failed"
                }
                val visible = windowActivity != null && status == "complete" && recorder.hasDrawn() && measurement.activeFraction >= 0.9
                val runtime = if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") "native-image" else "jvm"
                val report = """
                    {"schema":1,"pid":${ProcessHandle.current().pid()},"mode":"${config.mode.name.lowercase()}","runtime":"$runtime",
                    "status":"$status","measurementStartEpochMillis":$started,"measurementEndEpochMillis":$ended,
                    "elapsedMillis":${measurement.elapsedMillis},"activeMillis":${measurement.activeMillis},"activeFraction":${measurement.activeFraction},
                    "rootDrawCount":${measurement.rootDraws},"mediaDrawCount":${measurement.mediaDraws},"dialogDrawCount":${measurement.dialogDraws},"validForVisibleComparison":$visible,
                    "gpuMeasured":false,"accountDataUsed":false,"audioDeviceUsed":false}
                """.trimIndent()
                try {
                    withContext(Dispatchers.IO) { writeProbeFile(config.output, report) }
                } finally {
                    // Dispatchers.Main is Nucleus's global event dispatcher, not the
                    // paused scene dispatcher inherited by LaunchedEffect.
                    withContext(Dispatchers.Main) { complete() }
                }
            } finally {
                activityJob?.cancel()
            }
        }
    }

    MiuixTheme(controller = theme) {
        CompositionLocalProvider(LocalMediaDrawObserver provides recorder::mediaDraw) {
            Column(modifier.fillMaxSize().drawWithContent { recorder.draw(); drawContent() }) {
                Text("隔离性能预览 · ${config.mode.name.lowercase()} · 合成内容，无账号连接", modifier = Modifier.padding(10.dp))
                val current = assets
                if (current == null) {
                    Text("正在准备合成场景……", modifier = Modifier.padding(20.dp))
                } else {
                    val snapshot = remember(current) { syntheticSnapshot(current.animationPath) }
                    val drafts = remember { mutableStateMapOf<Long, TextFieldValue>() }
                    Box(Modifier.fillMaxSize()) {
                        ChatWorkspace(snapshot, ConnectionPhase.READY, drafts, {}, { _, _ -> }, {}, {}, { _, _, _ -> },
                            { _, _ -> }, {}, windowActive, ChatFeatureActions(), darkTheme = themeMode == ColorSchemeMode.Dark,
                            onDarkTheme = { themeMode = if (it) ColorSchemeMode.Dark else ColorSchemeMode.Light })
                        current.player?.let { player ->
                            LaunchedEffect(player, active) { if (active) player.play() else player.pause() }
                            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 32.dp).size(480.dp, 270.dp)
                                .background(Color.Black)) {
                                recorder.mediaDraw()
                                drawIntoCanvas { player.draw(it.skiaCanvas, size.width, size.height) }
                            }
                        }
                    }
                }
            }
            WindowDialog(show = assets != null && config.mode == IdleProbeMode.DIALOG, title = "合成静置弹层",
                maxWidth = 480.dp, onDismissRequest = {}) {
                Box(Modifier.fillMaxWidth().height(180.dp).drawWithContent { recorder.dialogDraw(); drawContent() },
                    contentAlignment = Alignment.Center) {
                    Text("弹层绘制检测 · 合成内容 · 无网络操作")
                }
            }
        }
    }
}

private fun writeProbeFile(path: Path, text: String) {
    Files.writeString(path, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
}

private class ProbeAssetHolder : AutoCloseable {
    private var disposed = false
    private var assets: ProbeAssets? = null
    @Synchronized fun install(value: ProbeAssets): ProbeAssets? {
        if (disposed) { value.close(); return null }
        assets = value
        return value
    }
    @Synchronized override fun close() {
        disposed = true
        assets?.close()
        assets = null
    }
}

private class ProbeAssets(val directory: Path?, val animationPath: Path?, val player: InlineVideoPlayer?) : AutoCloseable {
    override fun close() {
        player?.close()
        directory?.let { folder ->
            // Only the two exact generated files can be removed; never recursively delete a caller path.
            runCatching { Files.deleteIfExists(folder.resolve("animation.tgs")) }
            runCatching { Files.deleteIfExists(folder.resolve("video.mp4")) }
            runCatching { Files.deleteIfExists(folder) }
        }
    }
}

private fun createProbeAssets(config: IdleProbeConfig): ProbeAssets {
    if (config.mode == IdleProbeMode.STATIC || config.mode == IdleProbeMode.DIALOG) return ProbeAssets(null, null, null)
    val directory = Files.createTempDirectory(config.output.parent, "nnggram-idle-probe-")
    try {
        if (config.mode == IdleProbeMode.ANIMATION) {
            val path = directory.resolve("animation.tgs")
            val json = """{"v":"5.5.2","fr":30,"ip":0,"op":60,"w":96,"h":96,"nm":"synthetic","ddd":0,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"dot","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[24,48,0],"e":[72,48,0],"i":{"x":0.5,"y":1},"o":{"x":0.5,"y":0}},{"t":30,"s":[72,48,0],"e":[24,48,0],"i":{"x":0.5,"y":1},"o":{"x":0.5,"y":0}},{"t":60,"s":[24,48,0]}]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[28,28]},"nm":"circle"},{"ty":"fl","c":{"a":0,"k":[0.12,0.42,0.9,1]},"o":{"a":0,"k":100},"r":1}],"ip":0,"op":60,"st":0,"bm":0}]}"""
            GZIPOutputStream(Files.newOutputStream(path)).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return ProbeAssets(directory, path, null)
        }
        val path = directory.resolve("video.mp4")
        val ffmpeg = checkNotNull(MediaDecoder.findFfmpeg())
        val duration = config.seconds + config.warmupSeconds + 20
        MediaTools.capture(ffmpeg, listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            "testsrc2=s=320x180:r=30:d=$duration", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "1",
            "-pix_fmt", "yuv420p", "-y", path.toString()), timeoutSeconds = 30)
        val player = InlineVideoPlayer(path.toString(), autoPlay = false,
            audioFactory = { error("The synthetic probe has no audio track.") })
        return ProbeAssets(directory, null, player)
    } catch (error: Exception) {
        ProbeAssets(directory, null, null).close()
        throw error
    }
}

private fun syntheticSnapshot(animationPath: Path?): ChatSnapshot {
    val chatId = 100L
    val messages = (1L..10).map { index ->
        ChatMessage(index, chatId, senderId = 200, outgoing = index % 3 == 0L, date = 1_700_000_000 + index.toInt() * 60,
            text = "合成消息 $index · 用于静置绘制检测，不连接 Telegram。")
    }.toMutableList()
    if (animationPath != null) messages += ChatMessage(11, chatId, senderId = 200, kind = MessageKind.STICKER,
        date = 1_700_001_000, media = MessageMedia(MediaFile(1, localPath = animationPath.toString(), downloaded = true),
            width = 96, height = 96, format = MediaFormat.TGS, emoji = "●"))
    val selected = ChatItem(chatId, "合成聊天", order = 20, lastMessage = messages.last(), lastReadOutboxId = 11)
    val chats = listOf(selected) + (1L..5).map { ChatItem(chatId + it, "预览会话 $it", order = 20 - it) }
    return ChatSnapshot(enabled = true, token = ChatToken(1, 1, chatId), items = chats, selected = selected,
        messages = messages, listEnd = true, historyEnd = true, knownChats = chats, senderNames = mapOf(200L to "预览成员"))
}
