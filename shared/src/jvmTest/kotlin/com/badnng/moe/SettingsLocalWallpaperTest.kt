package com.badnng.moe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.badnng.moe.media.SaveTargetBoundary
import com.badnng.moe.media.SaveTargetIssue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** All bytes, selections and IO roots are synthetic; no picker, account or user configuration is opened. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLocalWallpaperTest {
    @Test fun cancelledPickerRevisionCannotConsumeTheNextExplicitSelection() = runTest {
        val gate = CompletableDeferred<LocalWallpaperFrame?>()
        val backend = WallpaperTestBackend().apply { pickGate = gate }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        controller.cancel()
        val retired = WallpaperTestFrame()
        gate.complete(retired); runCurrent()
        assertNull(controller.preview)
        assertEquals(0, retired.liveLeases)
        backend.pickGate = null
        val next = WallpaperTestFrame()
        backend.nextPick = next
        controller.pick { true }; runCurrent()
        assertSame(next, controller.preview?.frame)
        controller.apply { true }; runCurrent()
        assertNotNull(controller.committed)
        assertEquals(1, backend.commits)
        assertEquals(1, next.liveLeases)
        controller.close()
        assertEquals(0, next.liveLeases)
    }

    @Test fun closingControllerReleasesLatePickerWithoutPublishingAnything() = runTest {
        val gate = CompletableDeferred<LocalWallpaperFrame?>()
        val backend = WallpaperTestBackend().apply { pickGate = gate }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        controller.close()
        val late = WallpaperTestFrame()
        gate.complete(late); runCurrent()
        assertNull(controller.preview)
        assertNull(controller.committed)
        assertEquals(0, late.liveLeases)
        assertEquals(0, backend.commits)
    }

    @Test fun cancelledPickerDoesNotReplaceCommittedOrReportAnError() = runTest {
        val original = WallpaperTestFrame()
        val backend = WallpaperTestBackend().apply { loaded = LocalWallpaperSelection(original, false) }
        val controller = LocalWallpaperController(backend, this)
        controller.load(); runCurrent()
        controller.pick { true }; runCurrent()
        assertNull(controller.preview)
        assertSame(original, controller.committed?.frame)
        assertNull(controller.error)
        assertEquals(0, backend.commits)
        controller.close()
    }

    @Test fun invalidPickerResultShowsSafeErrorAndKeepsPreviousSelection() = runTest {
        val backend = WallpaperTestBackend().apply { pickFailure = IOException("synthetic-private-path") }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        assertNull(controller.preview)
        assertNotNull(controller.error)
        assertFalse(controller.error!!.contains("synthetic-private-path"))
        assertFalse(controller.busy)
        controller.close()
    }

    @Test fun failedReloadPreservesCommittedSelectionAndShowsSafeError() = runTest {
        val original = WallpaperTestFrame()
        val backend = WallpaperTestBackend().apply { loaded = LocalWallpaperSelection(original, false) }
        val controller = LocalWallpaperController(backend, this)
        controller.load(); runCurrent()
        backend.loadFailure = IOException("synthetic-private-directory")
        controller.load(); runCurrent()
        assertSame(original, controller.committed?.frame)
        assertNotNull(controller.error)
        assertFalse(controller.error!!.contains("synthetic-private-directory"))
        assertFalse(controller.busy)
        controller.close()
    }

    @Test fun cancelledOrRetiredPickerCannotRevivePreviewAndReleasesLateFrame() = runTest {
        for (cancel in listOf(false, true)) {
            val gate = CompletableDeferred<LocalWallpaperFrame?>()
            val backend = WallpaperTestBackend().apply { pickGate = gate }
            val controller = LocalWallpaperController(backend, this)
            var owner = true
            controller.pick { owner }; runCurrent()
            if (cancel) controller.cancel() else owner = false
            val late = WallpaperTestFrame()
            gate.complete(late); runCurrent()
            assertNull(controller.preview)
            assertEquals(0, late.liveLeases, "Late non-cancellable picker output must be released.")
            assertEquals(0, backend.commits)
            controller.close()
        }
    }

    @Test fun cancelReleasesOnlyPreviewAndNeverPersistsIt() = runTest {
        val picked = WallpaperTestFrame()
        val backend = WallpaperTestBackend().apply { nextPick = picked }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        val displayed = controller.preview!!.frame.retain()
        controller.cancel()
        assertNull(controller.preview)
        assertEquals(1, picked.liveLeases, "A retained drawing lease survives cancellation.")
        assertEquals(0, backend.commits)
        displayed.close(); displayed.close()
        assertEquals(0, picked.liveLeases, "Closing one lease twice is harmless.")
        controller.close()
    }

    @Test fun failedApplyRetainsPreviewForExplicitRetry() = runTest {
        val backend = WallpaperTestBackend().apply { nextPick = WallpaperTestFrame(); commitResult = false }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        controller.setBlurred(true)
        controller.apply { true }; runCurrent()
        assertNull(controller.committed)
        assertNotNull(controller.preview)
        assertTrue(controller.preview!!.blurred)
        assertNotNull(controller.error)
        assertEquals(1, backend.commits)
        backend.commitResult = true
        controller.apply { true }; runCurrent()
        assertNotNull(controller.committed)
        assertTrue(controller.committed!!.blurred)
        assertNull(controller.preview)
        assertEquals(2, backend.commits)
        controller.close()
    }

    @Test fun retiredOwnerCannotAdmitApply() = runTest {
        val backend = WallpaperTestBackend().apply { nextPick = WallpaperTestFrame() }
        val controller = LocalWallpaperController(backend, this)
        controller.pick { true }; runCurrent()
        controller.apply { false }; runCurrent()
        assertEquals(0, backend.commits)
        assertNull(controller.committed)
        controller.close()
    }

    @Test fun admittedApplySettlesAfterPageLeavesWithoutRevivingCancelledPreview() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val backend = WallpaperTestBackend().apply { nextPick = WallpaperTestFrame(); commitGate = gate }
        val controller = LocalWallpaperController(backend, this)
        var owner = true
        controller.pick { owner }; runCurrent()
        controller.apply { owner }; runCurrent()
        assertEquals(1, backend.commits)
        owner = false; controller.cancel()
        gate.complete(true); runCurrent()
        assertNotNull(controller.committed, "An already admitted write settles its original result.")
        assertNull(controller.preview)
        assertFalse(controller.busy)
        controller.close()
    }

    @Test fun successfulRestoreClearsSelectionButFailedRestorePreservesIt() = runTest {
        val original = WallpaperTestFrame()
        val backend = WallpaperTestBackend().apply { loaded = LocalWallpaperSelection(original, true); restoreResult = false }
        val controller = LocalWallpaperController(backend, this)
        controller.load(); runCurrent()
        controller.restore { true }; runCurrent()
        assertSame(original, controller.committed?.frame)
        assertNotNull(controller.error)
        backend.restoreResult = true
        controller.restore { true }; runCurrent()
        assertNull(controller.committed)
        assertNull(controller.preview)
        assertEquals(2, backend.restores)
        controller.close()
        assertEquals(0, original.liveLeases)
    }

    @Test fun storeRestartUsesOpaquePreparedArtworkWithoutOriginalInput() = isolatedStore { directory ->
        val store = store(directory)
        val frame = LocalWallpaperImages.prepare(syntheticPng())
        assertTrue(store.commit(frame, true) { true })
        frame.close()
        val metadata = Files.readString(directory.resolve("selection.properties"))
        assertFalse(metadata.contains("synthetic-original"))
        val loaded = assertNotNull(store(directory).load())
        try {
            assertEquals(4, loaded.frame.width)
            assertEquals(3, loaded.frame.height)
            assertTrue(loaded.blurred)
        } finally { loaded.frame.close() }
        assertTrue(store.restore { true })
        assertNull(store(directory).load())
    }

    @Test fun atomicPublicationFailurePreservesPreviousMetadataAndRestartSelection() = isolatedStore { directory ->
        val good = LocalWallpaperImages.prepare(syntheticPng())
        val replacement = LocalWallpaperImages.prepare(syntheticPng(8, 6))
        try {
            assertTrue(store(directory).commit(good, false) { true })
            val before = Files.readAllBytes(directory.resolve("selection.properties"))
            val failing = LocalWallpaperStore(directory, ::allowSyntheticTarget) { _, _ -> throw IOException("synthetic") }
            assertFalse(failing.commit(replacement, true) { true })
            assertContentEquals(before, Files.readAllBytes(directory.resolve("selection.properties")))
            val restarted = assertNotNull(store(directory).load())
            try { assertEquals(4, restarted.frame.width); assertFalse(restarted.blurred) }
            finally { restarted.frame.close() }
        } finally { good.close(); replacement.close() }
    }

    @Test fun retiredStoreOperationCannotPublishOrRestore() = isolatedStore { directory ->
        val frame = LocalWallpaperImages.prepare(syntheticPng())
        try {
            assertTrue(store(directory).commit(frame, false) { true })
            val before = Files.readAllBytes(directory.resolve("selection.properties"))
            assertFalse(store(directory).commit(frame, true) { false })
            assertFalse(store(directory).restore { false })
            assertContentEquals(before, Files.readAllBytes(directory.resolve("selection.properties")))
        } finally { frame.close() }
    }

    @Test fun capturedNativeBoundaryRejectionPreservesPreviouslyPublishedSelection() = isolatedStore { directory ->
        val frame = LocalWallpaperImages.prepare(syntheticPng())
        try {
            assertTrue(store(directory).commit(frame, false) { true })
            val before = Files.readAllBytes(directory.resolve("selection.properties"))
            val rejected = LocalWallpaperStore(directory, { object : SaveTargetBoundary {
                override fun check(target: Path) = SaveTargetIssue.NATIVE_SCAN_TREE
            } })
            assertFalse(rejected.commit(frame, true) { true })
            assertFalse(rejected.restore { true })
            assertContentEquals(before, Files.readAllBytes(directory.resolve("selection.properties")))
        } finally { frame.close() }
    }

    @Test fun malformedInputAndUnsafeMetadataCannotBecomeASelection() = isolatedStore { directory ->
        assertFails { LocalWallpaperImages.prepare(byteArrayOf(1, 2, 3, 4)) }
        assertNull(store(directory).load())
        for (metadata in listOf("schema=9\nid=invalid.image\nblurred=false\n",
                "schema=1\nid=../outside.image\nblurred=false\n", "schema=1\nid=C:/outside.image\nblurred=false\n")) {
            Files.writeString(directory.resolve("selection.properties"), metadata)
            assertFailsWith<LocalWallpaperReadFailure>("Untrusted metadata cannot escape the generated-file namespace.") {
                store(directory).load()
            }
        }
    }

    @Test fun realCodecHeadersAboveDimensionOrPixelLimitsAreRejected() {
        // A normal tiny PNG is generated locally. Only IHDR and its checksum change;
        // no giant bitmap or user image is allocated to manufacture these inputs.
        val normal = syntheticPng()
        LocalWallpaperImages.prepare(normal).close()
        for ((width, height) in listOf(10_001 to 1, 1 to 10_001, 6_000 to 4_001)) {
            val oversized = normal.copyOf()
            ByteBuffer.wrap(oversized).putInt(16, width).putInt(20, height)
            val checksum = CRC32().apply { update(oversized, 12, 17) }.value.toInt()
            ByteBuffer.wrap(oversized).putInt(29, checksum)
            assertFails("Synthetic IHDR $width x $height must not be admitted") {
                LocalWallpaperImages.prepare(oversized)
            }
        }
    }

    @Test fun explicitRestoreAtomicallyRecoversFromCorruptMetadata() = isolatedStore { directory ->
        Files.writeString(directory.resolve("selection.properties"), "schema=broken\nid=../unowned.image\nblurred=garbage\n")
        val unowned = directory.resolve("unowned-synthetic.txt")
        Files.writeString(unowned, "leave unrelated files alone")
        assertFailsWith<LocalWallpaperReadFailure> { store(directory).load() }
        assertTrue(store(directory).restore { true })
        assertNull(store(directory).load())
        assertEquals("leave unrelated files alone", Files.readString(unowned))
    }

    private fun store(directory: Path) = LocalWallpaperStore(directory, ::allowSyntheticTarget)
    private fun allowSyntheticTarget() = object : SaveTargetBoundary {
        override fun check(target: Path): SaveTargetIssue? = null
    }
    private fun isolatedStore(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nnggram-wallpaper-test-")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }
    private fun syntheticPng(width: Int = 4, height: Int = 3): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0x4080a0c0)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)); output.toByteArray()
        }
    }
}

internal class WallpaperTestBackend : LocalWallpaperBackend {
    var loaded: LocalWallpaperSelection? = null
    var nextPick: LocalWallpaperFrame? = null
    var pickFailure: Throwable? = null
    var loadFailure: Throwable? = null
    var pickGate: CompletableDeferred<LocalWallpaperFrame?>? = null
    var commitGate: CompletableDeferred<Boolean>? = null
    var commitResult = true
    var restoreResult = true
    var commits = 0
    var restores = 0
    override suspend fun load(): LocalWallpaperSelection? {
        loadFailure?.let { throw it }
        return loaded.also { loaded = null }
    }
    override suspend fun pick(): LocalWallpaperFrame? {
        pickFailure?.let { throw it }
        return pickGate?.let { withContext(NonCancellable) { it.await() } } ?: nextPick.also { nextPick = null }
    }
    override suspend fun commit(frame: LocalWallpaperFrame, blurred: Boolean, current: () -> Boolean): Boolean {
        commits++
        return commitGate?.let { withContext(NonCancellable) { it.await() } } ?: commitResult
    }
    override suspend fun restore(current: () -> Boolean): Boolean { restores++; return restoreResult }
}

internal class WallpaperTestFrame private constructor(private val resource: Resource) : LocalWallpaperFrame {
    constructor() : this(Resource())
    private class Resource { var leases = 1; val targets = CopyOnWriteArrayList<Triple<Int, Int, Boolean>>() }
    private var closed = false
    val liveLeases get() = resource.leases
    val preparedTargets get() = resource.targets.toList()
    override val width = 4
    override val height = 3
    override val blurAvailable = true
    override suspend fun prepare(widthPx: Int, heightPx: Int, blurred: Boolean) {
        check(!closed)
        resource.targets += Triple(widthPx, heightPx, blurred)
    }
    override fun retain(): LocalWallpaperFrame {
        check(!closed); resource.leases++
        return WallpaperTestFrame(resource)
    }
    override fun draw(scope: DrawScope, bounds: Rect, blurred: Boolean) {
        check(!closed)
        with(scope) { drawRect(if (blurred) Color.Blue else Color.Red, bounds.topLeft, bounds.size) }
    }
    override fun close() { if (!closed) { closed = true; resource.leases-- } }
}
