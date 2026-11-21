package com.badnng.moe

import com.badnng.moe.platform.DesktopPaths
import com.badnng.moe.platform.DesktopPlatform
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal actual suspend fun translateOfflineChatText(source: String, text: String): ChatTranslationResult =
    withContext(Dispatchers.IO) {
        // Only English → Simplified Chinese is installed by this provider.
        if (text.none { it in 'A'..'Z' || it in 'a'..'z' }) return@withContext ChatTranslationResult.Original
        val helper = offlineTranslator() ?: return@withContext ChatTranslationResult.Failure.OFFLINE_ENGINE
        if (source == "auto") {
            val identified = runOfflineTranslator(helper, helper.parent, listOf("--detect"), text)
                ?: return@withContext ChatTranslationResult.Failure.OFFLINE_ENGINE
            if (identified != "en") return@withContext ChatTranslationResult.Original
        }
        val model = try { OfflineEnglishChineseModel.directory() }
            catch (_: IOException) { return@withContext ChatTranslationResult.Failure.MODEL_DOWNLOAD }
            catch (_: SecurityException) { return@withContext ChatTranslationResult.Failure.MODEL_DOWNLOAD }
        currentCoroutineContext().ensureActive()
        val translated = runOfflineTranslator(helper, model, emptyList(), text)?.takeIf(String::isNotBlank)
            ?: return@withContext ChatTranslationResult.Failure.OFFLINE_ENGINE
        if (translated.trim() == text.trim()) ChatTranslationResult.Original
        else ChatTranslationResult.Translated(translated)
    }

private fun offlineTranslator(): Path? {
    val name = DesktopPlatform.current.executable("nnggram-translator")
    val executableDirectory = runCatching {
        ProcessHandle.current().info().command().orElse(null)?.let(Path::of)?.toAbsolutePath()?.parent
    }.getOrNull()
    return listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { Path.of(it) },
        System.getProperty("nnggram.tdlib.dir")?.let { Path.of(it) },
        executableDirectory,
        executableDirectory?.resolve("resources"),
        executableDirectory?.resolve("app/resources"),
    ).firstNotNullOfOrNull { root ->
        root.resolve("translation").resolve(name).toAbsolutePath().takeIf {
            Files.isRegularFile(it, NOFOLLOW_LINKS) && Files.isExecutable(it)
        }
    }
}

private val translationProcessTimeout by lazy {
    Executors.newSingleThreadScheduledExecutor { Thread(it, "offline-translation-timeout").apply { isDaemon = true } }
}

internal fun runOfflineTranslator(helper: Path, workingDirectory: Path, args: List<String>, text: String): String? {
    val input = text.encodeToByteArray()
    if (input.size > 16_384) return null
    val process = try {
        ProcessBuilder(listOf(helper.toString()) + args).directory(workingDirectory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (_: IOException) { return null }
    val deadline = translationProcessTimeout.schedule({ process.destroyForcibly() }, 30, TimeUnit.SECONDS)
    return try {
        process.outputStream.use { it.write(input) }
        val output = process.inputStream.use { it.readNBytes(128 * 1024 + 1) }
        if (output.size > 128 * 1024 || !process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) null
        else output.decodeToString()
    } catch (_: IOException) {
        null
    } finally {
        deadline.cancel(false)
        process.destroyForcibly()
    }
}

private object OfflineEnglishChineseModel {
    private const val PACK_URL = "https://huggingface.co/Second-Dawn-Lab/owll-language-packs/resolve/" +
        "d024a4c5340ae7a4d73d28482579b43afc0fc238/opus-mt-en-zh-ct2-int8.zip"
    private const val PACK_SHA256 = "44cb14586160b847d0999fd161d032f505bd9c50fb17c58348ec9fe7f39c40a0"
    private const val PACK_SIZE = 71_072_458L
    private data class ModelFile(val size: Long, val sha256: String)
    private val files = mapOf(
        "config.json" to ModelFile(233, "72901fbd8abd89fb5cf4a388f26fc681f5c4c58a1e1a88b30b879f107270e7ee"),
        "model.bin" to ModelFile(79_567_635, "327584c20bb83c7e89d595bcfa30b6ef3771c10816f707e892c4bbb1f808a8fb"),
        "shared_vocabulary.json" to ModelFile(1_368_888, "4821251fdc0a6c9e889837b42427a90cb3240f71aef89681a5a6de525182a634"),
        "source.spm" to ModelFile(806_435, "5775ddc9e3ff2fae91554da56468ad35ff56edaba870fea74447bc7234bfdaa8"),
        "target.spm" to ModelFile(804_600, "81dc94efa84e4025ef38d25d5d07429fe41e3eb29d44003f1db6fe98487b0052"),
    )
    private val modelLock = Mutex()
    private var verified: Path? = null

    suspend fun directory(): Path = modelLock.withLock {
        val context = currentCoroutineContext()
        val root = DesktopPaths.applicationRoot().resolve("translation-models")
        Files.createDirectories(root)
        val destination = root.resolve("opus-mt-en-zh-int8-${PACK_SHA256.take(12)}")
        FileChannel.open(root.resolve("install.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                context.ensureActive()
                if (!installed(destination, context)) install(root, destination, context)
                verified = destination
            }
        }
        destination
    }

    private fun installed(directory: Path, context: CoroutineContext): Boolean = Files.isDirectory(directory, NOFOLLOW_LINKS) &&
        files.all { (name, expected) ->
            val file = directory.resolve(name)
            Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == expected.size &&
                (directory == verified || matchesDigest(file, expected.sha256, context))
        }

    private fun matchesDigest(file: Path, expected: String, context: CoroutineContext): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                context.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest()) == expected
    }

    private fun install(root: Path, destination: Path, context: CoroutineContext) {
        val staging = Files.createTempDirectory(root, ".install-")
        try {
            val archive = staging.resolve("model.zip")
            download(archive, context)
            val candidate = Files.createDirectory(staging.resolve("model"))
            ZipFile(archive.toFile()).use { zip ->
                for ((name, expected) in files) {
                    val entry = zip.getEntry(name) ?: throw IOException("Missing model entry")
                    if (entry.isDirectory || entry.size != expected.size) throw IOException("Invalid model entry")
                    zip.getInputStream(entry).use { input -> writeBounded(input, candidate.resolve(name), expected.size, context) }
                }
            }
            context.ensureActive()
            val previous = staging.resolve("previous")
            if (Files.exists(destination, NOFOLLOW_LINKS)) Files.move(destination, previous, ATOMIC_MOVE)
            try {
                Files.move(candidate, destination, ATOMIC_MOVE)
            } catch (failure: IOException) {
                if (Files.exists(previous, NOFOLLOW_LINKS)) runCatching { Files.move(previous, destination, ATOMIC_MOVE) }
                throw failure
            }
        } finally {
            runCatching {
                Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun download(destination: Path, context: CoroutineContext) {
        var url = URI(PACK_URL)
        val proxy = translationWebProxy()
        repeat(4) {
            val connection = executeTranslationRequest(url, proxy, connectTimeout = 10_000, readTimeout = 15_000)
            try {
                when (connection.responseCode) {
                    200 -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        connection.inputStream.use { input -> writeBounded(input, destination, PACK_SIZE, context, digest) }
                        if (HexFormat.of().formatHex(digest.digest()) != PACK_SHA256) throw IOException("Invalid model checksum")
                        return
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing model location")
                        url = try { url.resolve(location) } catch (_: IllegalArgumentException) { throw IOException("Invalid model location") }
                        val host = url.host ?: throw IOException("Invalid model host")
                        if (url.scheme != "https" || url.userInfo != null ||
                            !(host == "huggingface.co" || host.endsWith(".hf.co") || host.endsWith(".huggingface.co"))
                        ) throw IOException("Unexpected model host")
                    }
                    else -> throw IOException("Model download returned an error")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many model redirects")
    }

    private fun writeBounded(input: InputStream, destination: Path, expected: Long,
        context: CoroutineContext, digest: MessageDigest? = null,
    ) {
        var size = 0L
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > expected) throw IOException("Unexpected model size")
                digest?.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        if (size != expected) throw IOException("Incomplete model file")
    }
}
