package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import com.badnng.moe.media.AreaRasterImage
import com.badnng.moe.media.LocalNativeDialogOwner
import com.badnng.moe.media.LocalNativeFilePicker
import com.badnng.moe.media.NativeSaveTargets
import com.badnng.moe.media.SaveTargetBoundary
import com.badnng.moe.tdlib.ConfigurationStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** One bounded queue for decode and disk operations; no caller-runs/UI fallback. */
private val localWallpaperExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
    ArrayBlockingQueue<Runnable>(8), { task -> Thread(task, "local-wallpaper").apply { isDaemon = true } })
private val localWallpaperWorker = localWallpaperExecutor.asCoroutineDispatcher()
// Multiple window/controller instances still share the lifetime of the same local artwork.
private val localWallpaperDiskLeaseGuard = Any()
private val localWallpaperDiskLeases = mutableMapOf<Path, MutableSet<WallpaperPixels>>()

@Composable
internal actual fun rememberLocalWallpaperBackend(): LocalWallpaperBackend {
    val picker = LocalNativeFilePicker.current
    val owner = LocalNativeDialogOwner.current
    return remember(picker, owner) {
        JvmLocalWallpaperBackend(LocalWallpaperStore(ConfigurationStore.default().path.toAbsolutePath()
            .resolveSibling("local-wallpapers-v1")), { picker("选择聊天背景", setOf("jpg", "jpeg", "png", "webp"), owner) })
    }
}

internal class JvmLocalWallpaperBackend(
    private val store: LocalWallpaperStore,
    private val picker: suspend () -> String?,
) : LocalWallpaperBackend {
    override suspend fun pick(): LocalWallpaperFrame? {
        val path = picker() ?: return null
        var result: LocalWallpaperFrame? = null
        try {
            withContext(localWallpaperWorker) { result = LocalWallpaperImages.read(Path.of(path)) }
            coroutineContext.ensureActive()
            return result
        } catch (error: Throwable) { result?.close(); throw error }
    }

    override suspend fun load(): LocalWallpaperSelection? {
        var result: LocalWallpaperSelection? = null
        try {
            withContext(localWallpaperWorker) { result = store.load() }
            coroutineContext.ensureActive()
            return result
        } catch (error: Throwable) { result?.frame?.close(); throw error }
    }

    override suspend fun commit(frame: LocalWallpaperFrame, blurred: Boolean, current: () -> Boolean): Boolean {
        val lease = frame.retain()
        // Once admitted, report the original atomic result even if its settings page leaves.
        return try { withContext(NonCancellable) {
            withContext(localWallpaperWorker) { store.commit(lease, blurred, current) }
        } } finally { lease.close() }
    }

    override suspend fun restore(current: () -> Boolean): Boolean = withContext(NonCancellable) {
        withContext(localWallpaperWorker) { store.restore(current) }
    }
}

/** Testable isolated store. Only generated IDs and blur state cross the persistence boundary. */
internal class LocalWallpaperStore(
    directory: Path,
    private val boundary: () -> SaveTargetBoundary = NativeSaveTargets::capture,
    private val publish: (Path, Path) -> Unit = { temporary, target ->
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private val root = directory.toAbsolutePath().normalize()
    private val metadata = root.resolve("selection.properties")

    @Synchronized fun load(): LocalWallpaperSelection? {
        // Reads do not create a save destination. An initializing native client must not
        // hide an already committed wallpaper while its root inventory is still Unknown.
        return try {
            val value = readMetadata() ?: return null
            val id = value.first ?: return null
            val frame = LocalWallpaperImages.read(artwork(id)) as JvmLocalWallpaperFrame
            try {
                associate(frame.pixels, id)
                LocalWallpaperSelection(frame, value.second)
            } catch (error: Throwable) { frame.close(); throw error }
        } catch (_: IOException) { throw LocalWallpaperReadFailure(LocalWallpaperReadFailureKind.UNAVAILABLE) }
        catch (_: Exception) { throw LocalWallpaperReadFailure(LocalWallpaperReadFailureKind.INVALID) }
    }

    @Synchronized fun commit(frame: LocalWallpaperFrame, blurred: Boolean, current: () -> Boolean): Boolean {
        val pixels = (frame as? JvmLocalWallpaperFrame)?.livePixels() ?: return false
        if (blurred && !pixels.blurAvailable) return false
        val fence = boundary()
        var temporaryArtwork: Path? = null
        var temporaryMetadata: Path? = null
        var ownedArtwork: Path? = null
        var published = false
        return try {
            fence.require(root); fence.require(metadata)
            if (!current() || !fence.current()) return false
            Files.createDirectories(root)
            fence.require(root)
            // An explicit apply can replace a corrupt local manifest, but cannot grant
            // ownership of a filename that the old manifest failed to validate.
            val oldId = runCatching { readMetadata() }.getOrNull()?.first
            val id = UUID.randomUUID().toString()
            val target = artwork(id)
            fence.require(target)
            temporaryArtwork = newTemporary(fence, "artwork-")
            Files.write(temporaryArtwork, pixels.encoding)
            fence.require(temporaryArtwork); fence.require(target)
            if (!current() || !fence.current()) return false
            Files.move(temporaryArtwork, target, StandardCopyOption.ATOMIC_MOVE)
            temporaryArtwork = null
            ownedArtwork = target
            temporaryMetadata = writeMetadata(fence, id, blurred)
            fence.require(temporaryMetadata); fence.require(metadata); fence.require(target)
            if (!current() || !fence.current()) return false
            publish(temporaryMetadata, metadata)
            temporaryMetadata = null
            published = true
            associate(pixels, id)
            if (oldId != id) retire(oldId, fence)
            true
        } catch (_: Exception) { false }
        finally {
            cleanupOwned(temporaryArtwork, fence)
            cleanupOwned(temporaryMetadata, fence)
            if (!published) cleanupOwned(ownedArtwork, fence)
        }
    }

    @Synchronized fun restore(current: () -> Boolean): Boolean {
        val fence = boundary()
        var temporary: Path? = null
        return try {
            fence.require(root); fence.require(metadata)
            if (!current() || !fence.current()) return false
            Files.createDirectories(root)
            val oldId = runCatching { readMetadata() }.getOrNull()?.first
            // A tombstone uses the same atomic publication boundary as applying artwork.
            temporary = writeMetadata(fence, null, false)
            fence.require(temporary); fence.require(metadata)
            if (!current() || !fence.current()) return false
            publish(temporary, metadata)
            temporary = null
            retire(oldId, fence)
            true
        } catch (_: Exception) { false }
        finally { cleanupOwned(temporary, fence) }
    }

    private fun artwork(id: String): Path {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        return root.resolve("$id.image")
    }

    private fun readMetadata(): Pair<String?, Boolean>? {
        val attributes = try { Files.readAttributes(metadata, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return null }
        require(attributes.isRegularFile && attributes.size() <= 1024)
        val bytes = Files.newInputStream(metadata).use { it.readNBytes(1025) }
        require(bytes.size <= 1024)
        val data = Properties().apply { bytes.inputStream().reader(Charsets.UTF_8).use(::load) }
        require(data.getProperty("schema") == "1")
        require(data.containsKey("id"))
        val id = data.getProperty("id")?.takeIf(String::isNotEmpty)
        if (id != null) artwork(id)
        val blur = data.getProperty("blurred")
        require(blur == "true" || blur == "false")
        return id to (blur == "true")
    }

    private fun newTemporary(fence: SaveTargetBoundary, prefix: String): Path {
        // Check the exact generated path before its creation, including retired native roots.
        val path = root.resolve("$prefix${UUID.randomUUID()}.tmp")
        fence.require(path)
        Files.createFile(path)
        return path
    }

    private fun writeMetadata(fence: SaveTargetBoundary, id: String?, blurred: Boolean): Path {
        val path = newTemporary(fence, "selection-")
        try {
            val data = Properties().apply {
                setProperty("schema", "1")
                setProperty("id", id.orEmpty())
                setProperty("blurred", blurred.toString())
            }
            fence.require(path)
            Files.newBufferedWriter(path).use { data.store(it, "Local wallpaper") }
            return path
        } catch (error: Throwable) { cleanupOwned(path, fence); throw error }
    }

    private fun associate(pixels: WallpaperPixels, id: String) {
        val path = artwork(id)
        if (!synchronized(localWallpaperDiskLeaseGuard) {
                localWallpaperDiskLeases.getOrPut(path) { mutableSetOf() }.add(pixels)
            }) return
        pixels.onUnused {
            runCatching { localWallpaperExecutor.execute { synchronized(this) {
                val noLeases = synchronized(localWallpaperDiskLeaseGuard) {
                    val tracked = localWallpaperDiskLeases[path]
                    if (tracked?.remove(pixels) == true && tracked.isEmpty()) {
                        localWallpaperDiskLeases.remove(path)
                        true
                    } else false
                }
                if (noLeases) {
                    val fence = boundary()
                    runCatching {
                        fence.require(root); fence.require(metadata)
                        if (readMetadata()?.first != id) cleanupOwned(artwork(id), fence)
                    }
                }
            } } }
        }
    }

    private fun retire(id: String?, fence: SaveTargetBoundary) {
        if (id == null) return
        // A mounted frame keeps its application-owned encoding on disk until its last lease closes.
        if (synchronized(localWallpaperDiskLeaseGuard) {
                localWallpaperDiskLeases[artwork(id)]?.any { it.hasLeases() } == true
            }) return
        synchronized(localWallpaperDiskLeaseGuard) { localWallpaperDiskLeases.remove(artwork(id)) }
        cleanupOwned(artwork(id), fence)
    }

    private fun cleanupOwned(path: Path?, fence: SaveTargetBoundary) {
        if (path == null || path.parent != root) return
        runCatching { fence.require(path); if (fence.current()) Files.deleteIfExists(path) }
    }
}

internal object LocalWallpaperImages {
    const val MAX_INPUT_BYTES = 24_000_000L
    const val MAX_DIMENSION = 10_000
    const val MAX_PIXELS = 24_000_000L

    fun read(path: Path): LocalWallpaperFrame {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && attributes.size() in 1..MAX_INPUT_BYTES)
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES.toInt() + 1) }
        return prepare(bytes)
    }

    fun prepare(input: ByteArray): LocalWallpaperFrame {
        require(input.size.toLong() in 1..MAX_INPUT_BYTES)
        val bytes = input.copyOf()
        return Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
            // Header/format, animation, dimension and pixel admission precede all raster allocation/decode.
            require(codec.encodedImageFormat in setOf(EncodedImageFormat.JPEG, EncodedImageFormat.PNG, EncodedImageFormat.WEBP) &&
                codec.frameCount == 1 && codec.width in 1..MAX_DIMENSION && codec.height in 1..MAX_DIMENSION &&
                codec.width.toLong() * codec.height <= MAX_PIXELS)
            val info = ImageInfo(codec.width, codec.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, codec.colorSpace)
            val raw = Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info))
                codec.readPixels(bitmap)
                checkNotNull(bitmap.readPixels(info))
            }
            val swap = codec.encodedOrigin in setOf(EncodedOrigin.LEFT_TOP, EncodedOrigin.RIGHT_TOP,
                EncodedOrigin.RIGHT_BOTTOM, EncodedOrigin.LEFT_BOTTOM)
            val width = if (swap) codec.height else codec.width
            val height = if (swap) codec.width else codec.height
            val oriented = orientedOpaque(raw, codec.width, codec.height, codec.encodedOrigin, width, height)
            val image = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE,
                codec.colorSpace), oriented, width * 4)
            val clear = AreaRasterImage(image)
            try {
                val blur = blurred(clear, image)
                JvmLocalWallpaperFrame(WallpaperPixels(bytes, clear, blur))
            } catch (error: Throwable) { clear.close(); throw error }
        } }
    }

    private fun orientedOpaque(raw: ByteArray, rawWidth: Int, rawHeight: Int, origin: EncodedOrigin,
        width: Int, height: Int): ByteArray {
        val output = ByteArray(raw.size)
        for (y in 0 until height) for (x in 0 until width) {
            val input = when (origin) {
                EncodedOrigin.TOP_RIGHT -> (y * rawWidth + rawWidth - 1 - x) * 4
                EncodedOrigin.BOTTOM_RIGHT -> ((rawHeight - 1 - y) * rawWidth + rawWidth - 1 - x) * 4
                EncodedOrigin.BOTTOM_LEFT -> ((rawHeight - 1 - y) * rawWidth + x) * 4
                EncodedOrigin.LEFT_TOP -> (x * rawWidth + y) * 4
                EncodedOrigin.RIGHT_TOP -> ((rawHeight - 1 - x) * rawWidth + y) * 4
                EncodedOrigin.RIGHT_BOTTOM -> ((rawHeight - 1 - x) * rawWidth + rawWidth - 1 - y) * 4
                EncodedOrigin.LEFT_BOTTOM -> (x * rawWidth + rawWidth - 1 - y) * 4
                else -> (y * rawWidth + x) * 4
            }
            val target = (y * width + x) * 4
            val white = 255 - (raw[input + 3].toInt() and 255)
            for (channel in 0..2) output[target + channel] = ((raw[input + channel].toInt() and 255) + white)
                .coerceAtMost(255).toByte()
            output[target + 3] = 255.toByte()
        }
        return output
    }

    private fun blurred(clear: AreaRasterImage, image: Image): AreaRasterImage {
        val factor = minOf(1f, 256f / maxOf(image.width, image.height))
        val width = (image.width * factor).roundToInt().coerceAtLeast(1)
        val height = (image.height * factor).roundToInt().coerceAtLeast(1)
        clear.prepare(width, height)
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, image.colorSpace)
        val bytes = Surface.makeRaster(info).use { surface ->
            clear.draw(surface.canvas, org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()), requestPreparation = false)
            surface.makeImageSnapshot().use { small -> Bitmap().use { bitmap ->
                check(bitmap.allocPixels(info) && small.readPixels(bitmap))
                checkNotNull(bitmap.readPixels(info))
            } }
        }
        val scratch = ByteArray(bytes.size)
        repeat(3) {
            for (y in 0 until height) for (x in 0 until width) for (c in 0..2) {
                var sum = 0
                for (d in -3..3) sum += (bytes[(y * width + (x + d).coerceIn(0, width - 1)) * 4 + c].toInt() and 255) * (4 - kotlin.math.abs(d))
                scratch[(y * width + x) * 4 + c] = (sum / 16).toByte()
            }
            for (y in 0 until height) for (x in 0 until width) {
                for (c in 0..2) {
                    var sum = 0
                    for (d in -3..3) sum += (scratch[((y + d).coerceIn(0, height - 1) * width + x) * 4 + c].toInt() and 255) * (4 - kotlin.math.abs(d))
                    bytes[(y * width + x) * 4 + c] = (sum / 16).toByte()
                }
                bytes[(y * width + x) * 4 + 3] = 255.toByte()
            }
        }
        return AreaRasterImage(Image.makeRaster(info, bytes, width * 4))
    }
}

internal class WallpaperPixels(val encoding: ByteArray, private val clear: AreaRasterImage,
    private val blur: AreaRasterImage) {
    val width = clear.width
    val height = clear.height
    val blurAvailable = true
    private var references = 1
    private val callbacks = mutableListOf<() -> Unit>()
    @Synchronized fun retain() { check(references > 0); references++ }
    @Synchronized fun hasLeases() = references > 0
    @Synchronized fun onUnused(callback: () -> Unit) { if (references > 0) callbacks += callback else callback() }
    fun prepare(widthPx: Int, heightPx: Int, blurred: Boolean) {
        require(widthPx > 0 && heightPx > 0)
        val raster = if (blurred) blur else clear
        val factor = maxOf(widthPx.toFloat() / raster.width, heightPx.toFloat() / raster.height)
        raster.prepare((raster.width * factor).roundToInt().coerceAtLeast(1),
            (raster.height * factor).roundToInt().coerceAtLeast(1))
    }
    fun release() {
        val retired = synchronized(this) {
            check(references > 0)
            if (--references == 0) { clear.close(); blur.close(); callbacks.toList().also { callbacks.clear() } } else emptyList()
        }
        retired.forEach { it() }
    }
    @Synchronized fun draw(scope: DrawScope, destination: Rect, blurred: Boolean) {
        if (references <= 0 || destination.width <= 0f || destination.height <= 0f) return
        val raster = if (blurred) blur else clear
        val scale = maxOf(destination.width / raster.width, destination.height / raster.height)
        val width = raster.width * scale
        val height = raster.height * scale
        scope.drawIntoCanvas { canvas ->
            val native = canvas.skiaCanvas
            native.save()
            try {
                native.clipRect(org.jetbrains.skia.Rect.makeLTRB(destination.left, destination.top, destination.right, destination.bottom))
                raster.draw(native, org.jetbrains.skia.Rect.makeXYWH(destination.left + (destination.width - width) / 2f,
                    destination.top + (destination.height - height) / 2f, width, height), requestPreparation = false)
            } finally { native.restore() }
        }
    }
}

/** Each retain is a separately closeable lease; callers cannot double-release another owner. */
internal class JvmLocalWallpaperFrame(internal val pixels: WallpaperPixels) : LocalWallpaperFrame {
    private var closed = false
    override val width get() = pixels.width
    override val height get() = pixels.height
    override val blurAvailable get() = pixels.blurAvailable
    @Synchronized internal fun livePixels() = pixels.takeUnless { closed }
    @Synchronized override fun retain(): LocalWallpaperFrame {
        check(!closed)
        pixels.retain()
        return JvmLocalWallpaperFrame(pixels)
    }
    override suspend fun prepare(widthPx: Int, heightPx: Int, blurred: Boolean) {
        val lease = retain()
        try { withContext(localWallpaperWorker) { pixels.prepare(widthPx, heightPx, blurred) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Immutable source retains its ready cache/explicit-linear fallback. */ }
        finally { lease.close() }
    }
    @Synchronized override fun draw(scope: DrawScope, destination: Rect, blurred: Boolean) {
        if (!closed) pixels.draw(scope, destination, blurred)
    }
    @Synchronized override fun close() { if (!closed) { closed = true; pixels.release() } }
    override fun toString() = "LocalWallpaperFrame(<redacted>)"
}
