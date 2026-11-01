package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.media.LocalMediaDrawObserver
import com.badnng.moe.media.LocalWindowActivity
import com.badnng.moe.media.MediaOutputGate
import com.badnng.moe.settings.*
import com.badnng.moe.status.RuntimeSnapshot
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic native state and renderer; no account or real codec result is implied. */
class SettingsCurrentProfilePhotoUiTest {
    @Test fun savedStickerCurrentRendersNativeGradientAndWhiteRepaintAtAllSizes() {
        for ((width, height) in listOf(1000 to 700, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode)
                f.scene().use { s ->
                    s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
                    worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
                    val preview = s.revealDescription("当前头像预览", f, (height - 210).coerceIn(80, 280).toFloat() - 2f)
                    s.await { f.saved.requests.isNotEmpty() }; val call = f.saved.requests.last()
                    assertEquals(SettingsSavedProfileStickerAvatarRequest.Current(f.extras.token, 5, 901), call.request)
                    assertTrue(kotlin.math.abs((preview.width * .67f).roundToInt() - call.size) <= 1,
                        "Decode size follows the physical foreground")
                    val clip = call.ready(repaint = true)
                    s.await { clip.times.isNotEmpty() && s.descriptions("已保存静态表情头像").isNotEmpty() }; val frame = s.settledFrame()
                    s.settledFrame(6, 32); assertEquals(listOf(0L), clip.times.toList(), "Static art has no frame loop")
                    val cx = preview.center.x.roundToInt(); val cy = preview.center.y.roundToInt()
                    assertEquals(0xffffffff.toInt(), frame[cx, cy], "Native repaint is white")
                    val top = frame[cx, (preview.top + preview.height * .1f).roundToInt()]
                    val bottom = frame[cx, (preview.bottom - preview.height * .1f).roundToInt()]
                    assertTrue((top ushr 16 and 255) > (top and 255), "Native zero-degree top color must remain red")
                    assertTrue((bottom and 255) > (bottom ushr 16 and 255), "Native zero-degree bottom color must remain blue")
                    frame.save("saved-current-preview-${width}x$height-$mode-$density")
                    s.revealDialogText("关闭", f); s.revealDialogText("刷新", f)
                    s.settledFrame().save("saved-current-actions-${width}x$height-$mode-$density")
                    s.clickDialogText("关闭", f); assertFalse(call.requestCurrent())
                }
            }
    }

    @Test fun savedStickerGradientUsesEveryNativeClockwiseAngle() {
        val cases = listOf(
            Triple(0, .5f to .1f, .5f to .9f), Triple(45, .86f to .20f, .14f to .80f),
            Triple(90, .9f to .5f, .1f to .5f), Triple(135, .86f to .80f, .14f to .20f),
            Triple(180, .5f to .9f, .5f to .1f), Triple(225, .14f to .80f, .86f to .20f),
            Triple(270, .1f to .5f, .9f to .5f), Triple(315, .14f to .20f, .86f to .80f))
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            val preview = s.revealDescription("当前头像预览", f, 278f)
            var count = 0
            for ((angle, topPoint, bottomPoint) in cases) {
                val sticker = f.savedPhoto.sticker!!.copy(background = SettingsProfilePhotoSticker.Background.Gradient(0xff0000, 0x0000ff, angle))
                f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto.copy(sticker = sticker))
                s.await { f.saved.requests.size > count }; count = f.saved.requests.size
                val clip = f.saved.requests.last().ready(); s.await { clip.times.isNotEmpty() && s.descriptions("已保存静态表情头像").isNotEmpty() }
                val frame = s.settledFrame()
                fun sample(point: Pair<Float, Float>) = frame[(preview.left + preview.width * point.first).roundToInt(),
                    (preview.top + preview.height * point.second).roundToInt()]
                val top = sample(topPoint); val bottom = sample(bottomPoint)
                assertTrue((top ushr 16 and 255) > (top and 255), "Top color at native angle $angle")
                assertTrue((bottom and 255) > (bottom ushr 16 and 255), "Bottom color at native angle $angle")
            }
        }
    }

    @Test fun savedStickerRetryUsesLiveSourceAndVideoMetadataTakesPriority() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val failed = f.saved.requests.last(); worker { failed.fail() }
            s.await { s.descriptions("重试表情头像").isNotEmpty() }
            s.click(s.revealDescription("重试表情头像", f)); s.await { f.saved.requests.last() !== failed }
            assertFalse(failed.requestCurrent()); val fresh = f.saved.requests.last(); val clip = fresh.ready()
            s.await { clip.times.isNotEmpty() }; assertTrue(fresh.requestCurrent())
            worker { fresh.source.set(false); fresh.fail() }; s.settledFrame()
            assertFalse(fresh.requestCurrent()); assertTrue(s.descriptions("重试表情头像").isEmpty(), "A stale source cannot revive retry")
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.photo.copy(hasSticker = true, sticker = f.savedPhoto.sticker))
            s.await { f.video().isNotEmpty() }; assertFalse(fresh.requestCurrent())
            val count = f.saved.requests.size; s.settledFrame(); assertEquals(count, f.saved.requests.size)
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto.copy(invalidAnimation = true))
            s.await { s.texts("视频信息暂不可用。").isNotEmpty() }; s.settledFrame()
            assertEquals(count, f.saved.requests.size, "A corrupt native video field is not an absent video")
        }
    }

    @Test fun savedStickerFailureRemainsReadableOnWhiteNativeBackgroundAndRetries() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }
            val white = f.savedPhoto.sticker!!.copy(background = SettingsProfilePhotoSticker.Background.Solid(0xffffff))
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto.copy(sticker = white))
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val failed = f.saved.requests.last(); worker { failed.fail() }
            s.await { s.descriptions("重试表情头像").isNotEmpty() }
            val label = s.revealText("不可用", f); val frame = s.settledFrame()
            val surface = frame[(label.left - 3f * f.density).roundToInt(), label.center.y.roundToInt()]
            assertTrue((surface ushr 16 and 255) < 90 && (surface ushr 8 and 255) < 90 && (surface and 255) < 90,
                "The actual failure text has a dark readable surface over a white native background")
            s.click(s.revealDescription("重试表情头像", f)); s.await { f.saved.requests.last() !== failed }
            assertFalse(failed.requestCurrent()); assertTrue(f.saved.requests.last().requestCurrent())
        }
    }

    @Test fun savedStickerWithLegacyProviderIsUnavailableWithoutInventingARequest() {
        val f = Fixture(); f.useLegacyProvider = true
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f)
            s.await { s.descriptions("表情头像暂不可用").isNotEmpty() }
            assertTrue(f.saved.requests.isEmpty()); assertTrue(s.descriptions("重试表情头像").isEmpty())
            assertTrue(s.descriptions("已保存动态表情头像").isEmpty())
        }
    }

    @Test fun savedStickerRetryIsReachableInLowWindowAcrossThemesAndDensities() {
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (density in listOf(1f, 1.25f)) {
            val f = Fixture(350, 300, density, mode)
            f.scene().use { s ->
                s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
                worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
                s.revealDescription("当前头像预览", f, 88f); s.await { f.saved.requests.isNotEmpty() }
                val failed = f.saved.requests.last(); worker { failed.fail() }
                s.await { s.descriptions("重试表情头像").isNotEmpty() }
                val button = s.revealDescription("重试表情头像", f, minHeightDp = 20f)
                val circle = s.description("当前头像预览")
                assertEquals(circle.width, circle.height, 2f * density)
                val dx = button.center.x - circle.center.x; val dy = button.center.y - circle.center.y
                assertTrue(dx * dx + dy * dy < circle.width * circle.width / 4f, "Retry hit point must lie within the visible circle")
                s.click(button); s.await { f.saved.requests.last() !== failed }
                assertFalse(failed.requestCurrent()); assertTrue(f.saved.requests.last().requestCurrent())
            }
        }
    }

    @Test fun savedStickerWindowLossBeforeFrameRevokesOldSourceAndFreshOwnerResumes() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val old = f.saved.requests.last(); val clip = old.ready(animated = true)
            s.await { clip.times.isNotEmpty() && s.descriptions("已保存动态表情头像").isNotEmpty() }
            onUi { clip.delayFrames = true }; s.await { clip.delayed.isNotEmpty() }; val disposed = f.saved.framesClosed.get()
            worker { f.activity.value = false }; waitWithoutFrames { !old.demand.current() }
            assertFalse(old.requestCurrent()); assertFalse(old.closed.get(), "Off-scene revocation does not dispose the UI lease")
            assertEquals(disposed, f.saved.framesClosed.get(), "The background observer must not destroy borrowed UI pixels")
            val delayedFrame: () -> Unit = clip.delayed[0]
            worker { delayedFrame(); f.activity.value = true }
            s.await { f.saved.requests.last() !== old }; assertTrue(old.closed.get()); assertFalse(old.requestCurrent())
            val fresh = f.saved.requests.last(); val next = fresh.ready(); s.await { next.times.isNotEmpty() }
            assertTrue(fresh.requestCurrent()); assertTrue(clip.closed.get())
        }
    }

    @Test fun savedStickerPendingRequestIsRevokedWithoutAComposeFrameAndCannotReviveOnRestore() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val old = f.saved.requests.last(); assertTrue(old.demand.current())
            worker { f.activity.value = false }; waitWithoutFrames { !old.demand.current() }
            assertFalse(old.requestCurrent()); assertFalse(old.closed.get())
            lateinit var late: SavedAvatarSyntheticProvider.Clip
            worker { late = old.ready(animated = true); f.activity.value = true }
            s.await { f.saved.requests.last() !== old && late.closed.get() }
            assertFalse(old.demand.current())
            val fresh = f.saved.requests.last(); assertTrue(fresh.demand.current()); assertTrue(fresh.requestCurrent())
            val clip = fresh.ready(); s.await { clip.times.isNotEmpty() }
        }
    }

    @Test fun savedStickerLateClipAfterSourceRevocationCannotInstallAndRefreshGetsNewEpoch() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val old = f.saved.requests.last(); lateinit var stale: SavedAvatarSyntheticProvider.Clip
            // ImageComposeScene uses an unconfined dispatcher: the worker may enter the
            // callback immediately. Hold its exact final source check, revoke the source,
            // and only then let that already-delivered callback continue.
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            onUi {
                old.beforeNextCurrentCheck.set { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
                val producer = Thread { try { stale = old.ready(animated = true) } catch (error: Throwable) { failure.set(error) } }
                producer.start()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "The delivered callback must reach its final source check")
                    old.source.set(false)
                } finally { resume.countDown(); producer.join(5000) }
                assertFalse(producer.isAlive); assertNull(failure.get())
            }
            s.await { stale.closed.get() }; assertTrue(stale.closed.get()); assertTrue(stale.times.isEmpty())
            assertFalse(old.requestCurrent())
            assertTrue(s.descriptions("已保存动态表情头像").isEmpty()); assertTrue(s.descriptions("重试表情头像").isEmpty())
            s.clickDialogText("刷新", f); s.await { f.refreshes.size == 1 }
            worker { f.refreshes.single().admitted(8) }
            val nextPhoto = f.savedPhoto.copy(photoId = 902, authorization = 14)
            f.publish(8, SettingsCurrentProfilePhotoStatus.READY, nextPhoto)
            s.await { s.descriptions("当前头像预览").isNotEmpty() }; s.revealDescription("当前头像预览", f, preferUp = true)
            s.await { f.saved.requests.any { it.request.epoch == 8L } }
            val fresh = f.saved.requests.last(); assertEquals(SettingsSavedProfileStickerAvatarRequest.Current(f.extras.token, 8, 902), fresh.request)
            val clip = fresh.ready(); s.await { clip.times.isNotEmpty() }; assertTrue(fresh.requestCurrent())
        }
    }

    @Test fun savedStickerClockStopsWhenSourceWitnessRevokesEvenIfClipClaimsActive() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.savedPhoto)
            s.revealDescription("当前头像预览", f); s.await { f.saved.requests.isNotEmpty() }
            val owner = f.saved.requests.last(); val clip = owner.ready(animated = true, ignoreClipSource = true)
            s.await { clip.times.size >= 2 && s.descriptions("已保存动态表情头像").isNotEmpty() }
            var count = 0
            onUi { worker { owner.source.set(false) }; count = clip.times.size }
            s.settledFrame(30, 32)
            assertTrue(clip.active()); assertFalse(owner.requestCurrent())
            assertEquals(count, clip.times.size, "The native source witness stops new frame requests")
        }
    }
    @Test fun explicitCurrentEntryIsReadableWhenCachedExtrasAndWritePermissionAreUnavailable() {
        val f = Fixture(noCachedExtras = true, saving = true)
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            assertTrue(f.opens.single().active()); assertTrue(f.media.isEmpty()); assertEquals(0, f.historyOpens)
            worker { f.opens.single().admitted(41) }; f.publish(41, SettingsCurrentProfilePhotoStatus.EMPTY)
            s.await { s.texts("尚未设置当前头像。").isNotEmpty() }
            assertTrue(f.media.isEmpty()); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun staleOpenAdmissionAndSnapshotCannotBeAdoptedBySameFrameReopening() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); val entry = assertNotNull(f.entry)
            s.clickText("查看当前头像", f); s.await { f.opens.size == 1 && f.probe != null }
            val old = f.opens.single(); val oldClose = assertNotNull(f.probe).close
            onUi { oldClose(); entry() }
            s.await { f.opens.size == 2 }; val fresh = f.opens.last()
            assertFalse(old.active()); assertTrue(fresh.active()); assertTrue(f.closes.any { it.second == 0L })
            worker { old.admitted(41) }; f.publish(41, SettingsCurrentProfilePhotoStatus.READY, f.photo)
            s.settledFrame(); assertTrue(f.media.isEmpty())
            worker { fresh.admitted(42) }; s.await { f.probe?.opening?.epoch == 42L && s.texts("正在等待当前头像资料……").isNotEmpty() }
            assertTrue(s.texts("正在等待当前头像资料……").isNotEmpty()); assertTrue(f.media.isEmpty())
            f.publish(42, SettingsCurrentProfilePhotoStatus.READY, f.otherPhoto)
            s.await { s.descriptions("当前头像预览").isNotEmpty() }
            s.revealDescription("当前头像预览", f)
            s.await { f.media.any { it.request.photoId == f.otherPhoto.photoId } }
            assertTrue(f.media.none { it.request.photoId == f.photo.photoId })
            assertTrue(f.closes.any { it.second == 41L }); assertTrue(fresh.active())
        }
    }

    @Test fun nativeStatusesRemainDistinctAndDoNotPretendToPlay() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }
            val cases = listOf(
                SettingsCurrentProfilePhotoStatus.LOADING to "正在读取当前头像……",
                SettingsCurrentProfilePhotoStatus.EMPTY to "尚未设置当前头像。",
                SettingsCurrentProfilePhotoStatus.UNKNOWN to "当前头像资料尚未就绪，请刷新。",
                SettingsCurrentProfilePhotoStatus.ROLE_CONFLICT to "当前头像角色不一致，请刷新后查看。",
                SettingsCurrentProfilePhotoStatus.CONFLICT to "当前头像资料已变化，请刷新后查看。",
                SettingsCurrentProfilePhotoStatus.ERROR to "当前头像读取失败，请刷新。")
            for ((status, label) in cases) {
                f.publish(5, status); s.await { s.texts(label).isNotEmpty() }
                assertTrue(f.media.isEmpty()); assertTrue(f.renderer.started.isEmpty())
            }
        }
    }

    @Test fun realCurrentMetadataChoosesLargeFileAndForwardsItsOwnStartTime() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() }
            worker { f.opens.single().admitted(5) }; s.settledFrame()
            assertTrue(f.closes.none { it.second == 5L }, "A duplicate admission cannot close the current epoch")
            val video = f.video().last(); assertEquals(901L, video.request.photoId)
            worker { video.deliver(f.videoReady) }; s.await { f.renderer.started.isNotEmpty() }
            assertEquals(101, f.renderer.started.single().file.id); assertEquals(1250L, f.renderer.started.single().start)
            assertTrue(video.active()); assertTrue(f.cover().last().active()); assertTrue(f.writes.isEmpty())
        }
    }

    @Test fun wrongFileIsRejectedAndExplicitRetryGetsANewLease() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() }; val old = f.video().last()
            worker { old.deliver(f.videoReady.copy(uniqueId = "wrong")) }
            s.await { s.texts("重试视频").isNotEmpty() }; assertTrue(old.closed.get()); assertTrue(f.renderer.started.isEmpty())
            s.clickText("重试视频", f); s.await { f.video().size >= 2 }; val fresh = f.video().last()
            worker { old.deliver(f.videoReady); fresh.deliver(f.videoReady) }
            s.await { f.renderer.started.isNotEmpty() }; assertEquals(1, f.renderer.started.size); assertFalse(fresh.closed.get())
        }
    }

    @Test fun missingLargeUsesTheSmallTimestampAndStickerOnlyIsClearlyUnavailable() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.photo.copy(animation = null))
            s.revealDescription("当前头像预览", f); s.await { f.video().isNotEmpty() }
            val old = f.video().last(); worker { old.deliver(f.smallReady) }
            s.await { f.renderer.started.isNotEmpty() }; assertEquals(102, f.renderer.started.single().file.id)
            assertEquals(400L, f.renderer.started.single().start)
            f.publish(5, SettingsCurrentProfilePhotoStatus.READY,
                f.photo.copy(hasAnimation = false, hasSticker = true, animation = null, smallAnimation = null))
            s.await { s.texts("表情头像动画暂不可播放。").isNotEmpty() }
            assertTrue(old.closed.get()); assertTrue(f.renderer.started.single().closed.get())
        }
    }

    @Test fun opaqueNegativePhotoIdIsNotMistakenForMissingNativeMetadata() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(5) }; f.publish(5, SettingsCurrentProfilePhotoStatus.READY, f.photo.copy(photoId = -9))
            s.revealDescription("当前头像预览", f); s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() }
            assertEquals(-9L, f.video().last().request.photoId); assertEquals(-9L, f.cover().last().request.photoId)
        }
    }

    @Test fun refreshUsesAdmittedEpochAndRevokesOldFilesBeforeItsLateResult() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() }
            val oldVideo = f.video().last(); val oldCover = f.cover().last()
            s.clickDialogText("刷新", f); s.await { f.refreshes.size == 1 }
            assertEquals(5L, f.refreshes.single().epoch); assertTrue(oldVideo.closed.get()); assertTrue(oldCover.closed.get())
            worker { oldVideo.deliver(f.videoReady); oldCover.deliver(f.coverReady) }
            s.settledFrame(); assertTrue(f.renderer.started.isEmpty())
            worker { f.refreshes.single().admitted(8) }; s.await { f.probe?.opening?.epoch == 8L && s.texts("正在等待当前头像资料……").isNotEmpty() }
            assertTrue(s.texts("正在等待当前头像资料……").isNotEmpty())
            f.publish(8, SettingsCurrentProfilePhotoStatus.READY, f.otherPhoto)
            s.await { s.descriptions("当前头像预览").isNotEmpty() }
            s.revealDescription("当前头像预览", f, preferUp = true)
            s.await { f.video().any { it.request.epoch == 8L && it.request.photoId == f.otherPhoto.photoId } }
            assertFalse(oldVideo.active()); assertTrue(f.video().last().active())
        }
    }

    @Test fun rejectedAdmissionCanBeRetriedWithoutInventingAnEpoch() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); s.clickText("查看当前头像", f); s.await { f.opens.size == 1 }
            worker { f.opens.single().admitted(null) }; s.await { s.texts("重试").isNotEmpty() }
            assertTrue(f.media.isEmpty()); s.clickText("重试", f); s.await { f.opens.size == 2 }
            assertTrue(f.closes.any { it.second == 0L }); assertFalse(f.opens.first().active()); assertTrue(f.opens.last().active())
            // Dispose the old keyed dialog before delivering the new opening's admission.
            s.settledFrame(); assertTrue(f.opens.last().active())
            worker { f.opens.last().admitted(7) }; f.publish(7, SettingsCurrentProfilePhotoStatus.ERROR)
            s.await { s.texts("当前头像读取失败，请刷新。").isNotEmpty() }
            s.clickDialogText("刷新", f); s.await { f.refreshes.size == 1 }; assertEquals(7L, f.refreshes.single().epoch)
            worker { f.refreshes.single().admitted(null) }; s.await { s.texts("重试").isNotEmpty() }
            s.clickText("重试", f); s.await { f.opens.size == 3 }; assertFalse(f.opens[1].active()); assertTrue(f.opens[2].active())
        }
    }

    @Test fun windowInactivityWithoutFramesClosesBothLeasesAndRestoresFreshOnes() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() }
            val video = f.video().last(); val cover = f.cover().last()
            worker { f.activity.value = false }; waitWithoutFrames { video.closed.get() && cover.closed.get() }
            assertFalse(video.active()); assertFalse(cover.active())
            worker { video.deliver(f.videoReady); cover.deliver(f.coverReady); f.activity.value = true }
            assertFalse(video.active()); assertFalse(cover.active())
            s.await { f.video().size >= 2 && f.cover().size >= 2 }
            val focusedVideo = f.video().last(); val focusedCover = f.cover().last()
            assertTrue(focusedVideo.active()); assertTrue(focusedCover.active()); assertTrue(f.renderer.started.isEmpty())
            onUi { f.focused = false }; s.settledFrame()
            assertTrue(focusedVideo.closed.get()); assertTrue(focusedCover.closed.get())
            onUi { f.focused = true }; s.await { f.video().size >= 3 && f.cover().size >= 3 }
            assertTrue(f.video().last().active()); assertTrue(f.cover().last().active())
        }
    }

    @Test fun nativeOutputInvalidationOffFrameStopsBothSurfacesAndRefreshOwnsNewFiles() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() }
            val video = f.video().last(); val cover = f.cover().last()
            worker { video.deliver(f.videoReady); cover.deliver(f.coverReady) }
            s.await { f.renderer.started.isNotEmpty() && s.descriptions("当前头像静态封面 已载入").isNotEmpty() && f.coverDraws.get() > 0 }
            val rendered = f.renderer.started.single(); val drawn = f.coverDraws.get()
            val videoCount = f.video().size; val coverCount = f.cover().size
            worker { video.invalidate(); cover.invalidate(); video.deliver(f.videoReady); cover.deliver(f.coverReady) }
            waitWithoutFrames { rendered.stopped.get() }
            assertFalse(video.active()); assertFalse(cover.active()); assertTrue(video.closed.get()); assertTrue(cover.closed.get())
            s.await { s.texts("头像资源暂不可用，请刷新。").isNotEmpty() }
            assertEquals(drawn, f.coverDraws.get(), "A revoked static owner must not draw another frame")
            assertEquals(videoCount, f.video().size); assertEquals(coverCount, f.cover().size)
            assertTrue(rendered.closed.get()); assertEquals(1, f.renderer.started.size)
            s.clickDialogText("刷新", f); s.await { f.refreshes.size == 1 }
            worker { f.refreshes.single().admitted(8) }; f.publish(8, SettingsCurrentProfilePhotoStatus.READY, f.otherPhoto)
            s.await { s.descriptions("当前头像预览").isNotEmpty() }
            s.revealDescription("当前头像预览", f, preferUp = true)
            s.await { f.video().any { it.request.epoch == 8L } && f.cover().any { it.request.epoch == 8L } }
            val freshVideo = f.video().last(); val freshCover = f.cover().last()
            worker {
                video.deliver(f.videoReady); cover.deliver(f.coverReady)
                freshVideo.deliver(f.videoReady.copy(id = 201, uniqueId = "other-large")); freshCover.deliver(f.coverReady)
            }
            s.await { f.renderer.started.size == 2 && f.coverDraws.get() > drawn }
            assertTrue(rendered.stopped.get()); assertTrue(freshVideo.active()); assertTrue(freshCover.active())
            assertEquals(201, f.renderer.started.last().file.id)
        }
    }

    @Test fun nativeOutputInvalidBeforeBindRejectsSynchronousDeliveryUntilExplicitRefresh() {
        val f = Fixture(); f.autoMedia = true; f.invalidateBeforeReturn = true
        f.scene().use { s ->
            f.openReady(s)
            s.await { f.video().isNotEmpty() && f.cover().isNotEmpty() && s.texts("头像资源暂不可用，请刷新。").isNotEmpty() }
            assertTrue(f.media.all { it.closed.get() }); assertTrue(f.renderer.started.isEmpty()); assertEquals(0, f.coverDraws.get())
            val videoCount = f.video().size; val coverCount = f.cover().size
            s.settledFrame(); assertEquals(videoCount, f.video().size); assertEquals(coverCount, f.cover().size)
            f.invalidateBeforeReturn = false
            s.clickDialogText("刷新", f); s.await { f.refreshes.size == 1 }
            worker { f.refreshes.single().admitted(8) }; f.publish(8, SettingsCurrentProfilePhotoStatus.READY, f.photo)
            s.await { s.descriptions("当前头像预览").isNotEmpty() }
            s.revealDescription("当前头像预览", f, preferUp = true)
            s.await { f.renderer.started.isNotEmpty() && f.coverDraws.get() > 0 }
            assertTrue(f.video().last().active()); assertTrue(f.cover().last().active())
        }
    }

    @Test fun synchronousNativeFailureKeepsSourceWitnessAndRequiresExplicitRetry() {
        val f = Fixture(); f.failVideoBeforeReturn = true
        f.scene().use { s ->
            f.openReady(s); s.await { s.texts("重试视频").isNotEmpty() }
            val failed = f.video().last()
            assertTrue(failed.closed.get()); assertFalse(failed.active()); assertTrue(failed.requestCurrent())
            assertTrue(f.renderer.started.isEmpty())
            f.failVideoBeforeReturn = false; s.clickText("重试视频", f)
            s.await { f.video().last() !== failed }; val fresh = f.video().last()
            assertFalse(failed.requestCurrent()); assertTrue(fresh.active())
            worker { failed.deliver(f.videoReady); fresh.deliver(f.videoReady) }
            s.await { f.renderer.started.isNotEmpty() }; assertEquals(1, f.renderer.started.size)
        }
    }

    @Test fun nativeFailureQueuedBeforeSourceRevocationCannotReviveRetry() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() }; val old = f.video().last()
            // Keep the UI dispatcher occupied so both native events precede callback consumption.
            onUi { worker { old.fail(); old.invalidate() } }
            assertFalse(old.requestCurrent()); assertFalse(old.active())
            s.settledFrame()
            assertTrue(s.texts("重试视频").isEmpty()); assertTrue(f.renderer.started.isEmpty())
            val count = f.video().size; s.settledFrame(); assertEquals(count, f.video().size)
            f.publish(5, SettingsCurrentProfilePhotoStatus.CONFLICT)
            s.await { s.texts("当前头像资料已变化，请刷新后查看。").isNotEmpty() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualCurrentRendererUsesAvatarFailureAndSemantics() {
        Dispatchers.setMain(object : MainCoroutineDispatcher() {
            override val immediate get() = this
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        val missing = Files.createTempFile("synthetic-current-unavailable-", ".mp4")
        Files.delete(missing)
        try {
            val f = Fixture(); f.useNativeRenderer = true
            f.scene().use { s ->
                f.openReady(s); s.await { f.video().isNotEmpty() }; val video = f.video().last()
                worker { video.deliver(f.videoReady.copy(localPath = missing.toString())) }
                s.awaitSlow { s.texts("头像动画 暂时无法播放").isNotEmpty() }
                assertTrue(s.descriptions("头像动画").isNotEmpty())
                assertTrue(s.texts("GIF 暂时无法播放").isEmpty()); assertTrue(f.renderer.started.isEmpty())
                worker { video.invalidate() }; s.await { s.texts("头像资源暂不可用，请刷新。").isNotEmpty() }
            }
        } finally { Dispatchers.resetMain(); Files.deleteIfExists(missing) }
    }

    @Test fun pageExitRevokesOpeningAndLateResultsCannotStartPlayback() {
        val f = Fixture()
        f.scene().use { s ->
            f.openReady(s); s.await { f.video().isNotEmpty() }; val old = f.video().last(); val opening = f.opens.single()
            onUi { f.active = false }; s.settledFrame(); assertFalse(opening.active()); assertTrue(old.closed.get())
            worker { old.deliver(f.videoReady) }; s.settledFrame(); assertTrue(f.renderer.started.isEmpty())
            assertTrue(f.closes.any { it.second == 5L }); assertTrue(f.closes.any { it.second == 0L })
        }
    }

    @Test fun currentViewerAndHistoryEntryRemainMutuallyExclusive() {
        val f = Fixture()
        f.scene().use { s ->
            s.settledFrame(); val staleEntry = assertNotNull(f.entry)
            f.openReady(s); s.clickText("头像历史", f, preferUp = true); assertEquals(0, f.historyOpens)
            assertTrue(s.semanticsBounds { c ->
                c.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "头像历史" } &&
                    c.getOrNull(SemanticsProperties.Disabled) != null
            }.any { it.width > 0 && it.height > 0 }, "History remains disabled while the current viewer owns the page")
            s.clickDialogText("关闭", f); s.clickText("头像历史", f, preferUp = true); assertEquals(1, f.historyOpens)
            onUi { staleEntry() }; s.settledFrame(); assertEquals(1, f.opens.size)
        }
    }

    @Test fun currentDialogRemainsWithinWideAndLowWorkspaceAtBothThemesAndDensities() {
        for ((width, height) in listOf(1000 to 700, 350 to 300)) for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark))
            for (density in listOf(1f, 1.25f)) {
                val f = Fixture(width, height, density, mode); f.autoMedia = true
                f.scene().use { s ->
                    f.openReady(s); val targetHeight = (height - 210).coerceIn(80, 280).toFloat() - 2f
                    val preview = s.revealDescription("当前头像预览", f, targetHeight); s.await { f.renderer.started.isNotEmpty() }
                    assertTrue(preview.width > 0 && preview.height > 0)
                    assertEquals(preview.width, preview.height, 2f * density)
                    val frame = s.settledFrame()
                    assertEquals(0xff6728c4.toInt(), frame[preview.center.x.roundToInt(), preview.center.y.roundToInt()],
                        "The full selected preview must draw the injected synthetic renderer")
                    frame.save("preview-${width}x$height-$mode-$density")
                    s.revealDialogText("关闭", f); s.revealDialogText("刷新", f)
                    assertTrue(s.texts("刷新").isNotEmpty())
                    s.settledFrame().save("actions-${width}x$height-$mode-$density")
                    if (width >= 760) assertTrue(s.texts("设置").isNotEmpty(), "The actual category column remains mounted")
                    s.clickDialogText("关闭", f); assertFalse(f.opens.single().active())
                }
            }
    }

    private class Open(val parent: SettingsProfileExtrasToken, val active: () -> Boolean, val admitted: (Long?) -> Unit)
    private class Refresh(val epoch: Long, val admitted: (Long?) -> Unit)
    private class Media(val request: SettingsCurrentProfilePhotoMediaRequest, val source: () -> Boolean,
        val result: (SettingsResult<MediaFile>) -> Unit) : SettingsCurrentProfilePhotoFileLease {
        val closed = AtomicBoolean()
        private val retired = AtomicBoolean()
        private val lock = Any()
        private var callback: (() -> Unit)? = null
        override fun active() = !closed.get() && requestCurrent()
        override fun requestCurrent() = !retired.get() && source()
        override fun onInvalidated(callback: () -> Unit): () -> Unit {
            val immediately = synchronized(lock) {
                if (!requestCurrent()) true else {
                    // A normally FAILED lease has no output observer; its request witness
                    // remains queryable until source revocation or explicit close.
                    if (!closed.get()) { check(this.callback == null); this.callback = callback }
                    false
                }
            }
            if (immediately) callback()
            return { synchronized(lock) { if (this.callback === callback) this.callback = null } }
        }
        fun invalidate() {
            val notify = synchronized(lock) {
                if (!retired.compareAndSet(false, true)) null else {
                    val wasFailed = closed.getAndSet(true)
                    callback.also { callback = null }.takeUnless { wasFailed }
                }
            }
            notify?.invoke()
        }
        override fun close() = invalidate()
        fun deliver(value: MediaFile) = result(SettingsResult(value = value))
        fun fail() {
            synchronized(lock) { if (!requestCurrent() || closed.getAndSet(true)) return; callback = null }
            result(SettingsResult(error = SettingsError.REQUEST_FAILED))
        }
    }
    private class Renderer : CurrentProfilePhotoRenderer {
        data class Started(val file: MediaFile, val start: Long, val active: Boolean,
            val closed: AtomicBoolean = AtomicBoolean(), val stopped: AtomicBoolean = AtomicBoolean())
        val started = CopyOnWriteArrayList<Started>()
        @Composable override fun Content(file: MediaFile, startPositionMillis: Long, active: Boolean,
            outputGate: MediaOutputGate, modifier: Modifier) {
            val value = remember(file, startPositionMillis, outputGate) { Started(file, startPositionMillis, active) }
            DisposableEffect(value, outputGate) {
                started += value; val cancel = outputGate.onInvalidated { value.stopped.set(true) }
                onDispose { cancel(); value.closed.set(true) }
            }
            Canvas(modifier.semantics { contentDescription = "合成当前头像动画" }) {
                if (outputGate.active()) drawRect(Color(0xff6728c4))
            }
        }
    }
    private class LegacyProvider : ProfileStickerAvatarArtworkProvider {
        override val generation = 7L
        override fun thumbnail(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            candidate: ProfileStickerAvatarCandidateTicket, physicalSizePx: Int, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarArtwork>) -> Unit): ProfileStickerAvatarArtworkLease =
            object : ProfileStickerAvatarArtworkLease { override fun close() {} }
        override fun preview(parent: SettingsProfileExtrasToken, picker: ProfileStickerAvatarPickerTicket,
            selection: ProfileStickerAvatarSelectionTicket, physicalSizePx: Int, active: () -> Boolean,
            result: (SettingsResult<ProfileStickerAvatarPreviewClip>) -> Unit): ProfileStickerAvatarArtworkLease =
            object : ProfileStickerAvatarArtworkLease { override fun close() {} }
        override fun close() {}
    }
    private inner class Fixture(val width: Int = 1000, val height: Int = 700, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light, noCachedExtras: Boolean = false, saving: Boolean = false) {
        val pixelHeight get() = (height * density).roundToInt()
        val detailLeft get() = if (width >= 760) (width * .28f).coerceIn(236f, 304f) + 1f else 0f
        val activity = MutableStateFlow(true)
        var active by mutableStateOf(true); var focused by mutableStateOf(true); var autoMedia = false; var invalidateBeforeReturn = false
        var useNativeRenderer = false; var failVideoBeforeReturn = false; var useLegacyProvider = false
        var entry: (() -> Unit)? = null; var probe: CurrentProfilePhotoUiCallbacks? = null
        val opens = CopyOnWriteArrayList<Open>(); val closes = CopyOnWriteArrayList<Pair<SettingsProfileExtrasToken, Long>>()
        val refreshes = CopyOnWriteArrayList<Refresh>(); val media = CopyOnWriteArrayList<Media>(); val writes = mutableListOf<String>()
        var historyOpens = 0; val renderer = Renderer(); val coverDraws = AtomicInteger(); val saved = SavedAvatarSyntheticProvider()
        val legacy = LegacyProvider()
        val image = SettingsProfilePhotoVariant(MediaFile(111, uniqueId = "current-cover"), 160, 160)
        val animation = SettingsProfilePhotoAnimation(MediaFile(101, size = 128, uniqueId = "current-large"), 640, 1.25)
        val small = SettingsProfilePhotoAnimation(MediaFile(102, size = 64, uniqueId = "current-small"), 160, .4)
        val photo = SettingsCurrentProfilePhoto(901, 1_700_000_000, listOf(image), hasAnimation = true,
            animation = animation, smallAnimation = small, authorization = 13)
        val savedPhoto = photo.copy(hasAnimation = false, animation = null, smallAnimation = null, hasSticker = true,
            sticker = SettingsProfilePhotoSticker(SettingsProfilePhotoSticker.Type.RegularOrMask(11, 22),
                SettingsProfilePhotoSticker.Background.Gradient(0xff0000, 0x0000ff, 0)))
        val otherPhoto = photo.copy(photoId = 902, authorization = 14,
            animation = animation.copy(file = animation.file.copy(id = 201, uniqueId = "other-large")))
        val videoReady = MediaFile(101, size = 128, uniqueId = "current-large", downloaded = true, localPath = "synthetic://video")
        val smallReady = MediaFile(102, size = 64, uniqueId = "current-small", downloaded = true, localPath = "synthetic://small")
        val coverReady get() = MediaFile(111, size = png.length(), uniqueId = "current-cover", downloaded = true, localPath = png.absolutePath)
        var extras by mutableStateOf(SettingsProfileExtrasSnapshot(SettingsProfileExtrasToken(7, 3, 43),
            if (noCachedExtras) null else SettingsProfileExtrasData(43, 901, null, SettingsCalendarDate(27, 10, 2026)), busy = noCachedExtras))
        val currentActions = SettingsCurrentProfilePhotoActions(
            open = { parent, current, admitted -> opens += Open(parent, current, admitted) },
            close = { parent, epoch -> closes += parent to epoch },
            refresh = { _, epoch, admitted -> refreshes += Refresh(epoch, admitted) },
            acquireFile = { request, current, result ->
                Media(request, current, result).also { media += it
                    if (failVideoBeforeReturn && request.use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW) it.fail()
                    else if (autoMedia) it.deliver(if (request.use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW) coverReady else videoReady)
                    if (invalidateBeforeReturn) it.invalidate()
                }
            })
        val actions = SettingsActions(profileExtras = SettingsProfileExtrasActions(
            currentPhoto = currentActions,
            history = SettingsProfilePhotoHistoryActions(open = { _, _ -> historyOpens++ }),
            removePhoto = { _, _ -> writes += "remove" }))
        val workspace = SettingsWorkspaceState().apply { destination = SettingsDestination.PROFILE; detailOpen = true }
        val savingSections = if (saving) setOf(SettingsSection.PROFILE) else emptySet()
        fun video() = media.filter { it.request.use == SettingsCurrentProfilePhotoMediaUse.ANIMATION_PREVIEW }
        fun cover() = media.filter { it.request.use == SettingsCurrentProfilePhotoMediaUse.STATIC_PREVIEW }
        fun publish(epoch: Long, status: SettingsCurrentProfilePhotoStatus, photo: SettingsCurrentProfilePhoto? = null) =
            onUi { extras = extras.copy(currentPhoto = SettingsCurrentProfilePhotoSnapshot(epoch, status, photo)) }
        fun openReady(s: ComposePixelScene) {
            s.settledFrame(); s.clickText("查看当前头像", this); s.await { opens.size == 1 }
            worker { opens.single().admitted(5) }; publish(5, SettingsCurrentProfilePhotoStatus.READY, photo)
            s.revealDescription("当前头像预览", this)
        }
        fun scene() = ComposePixelScene((width * density).roundToInt(), pixelHeight) {
            val native = LocalWindowInfo.current; val currentFocus by rememberUpdatedState(focused)
            val window = remember(native) { object : WindowInfo by native { override val isWindowFocused get() = currentFocus } }
            val defaultRenderer = LocalCurrentProfilePhotoRenderer.current
            val lifecycleOwner = remember { object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            } }
            val preferences = remember(mode) { SettingsPreferences(theme = mode) }
            val theme = remember(mode) { ThemeController(mode) }
            val settings = SettingsSnapshot(token = SettingsToken(7, 1), enabled = true, loaded = setOf(SettingsSection.PROFILE),
                saving = savingSections, profile = SettingsProfile(43, "Synthetic", "Profile", "fixture", "", ""), profileExtras = extras)
            val runtime = RuntimeSnapshot(configPath = "synthetic/config", generation = 7, settings = settings, chats = ChatSnapshot())
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window,
                LocalWindowActivity provides activity, LocalSettingsSecurityConfirmationHost provides InlineHost,
                LocalCurrentProfilePhotoEntryProbe provides { entry = it }, LocalCurrentProfilePhotoUiCallbacks provides { probe = it },
                LocalCurrentProfilePhotoRenderer provides if (useNativeRenderer) defaultRenderer else renderer,
                LocalProfileStickerAvatarArtwork provides ProfileStickerAvatarArtworkClient(if (useLegacyProvider) legacy else saved, 7),
                LocalLifecycleOwner provides lifecycleOwner, LocalMediaDrawObserver provides { coverDraws.incrementAndGet(); Unit },
                LocalSettingsPreferences provides preferences,
                LocalDesktopChatLayout provides desktopChatLayout(width.dp, height.dp)) {
                MiuixTheme(controller = theme) {
                    SettingsWorkspace(runtime, actions, ChatFeatureActions(), preferences, onPreferences = {},
                        folderPreferences = ChatFolderPreferences(), onFolderPreferences = {}, onClose = {}, onOpenConfig = {},
                        state = workspace, active = active)
                }
            }
        }
    }
    private object InlineHost : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Box(Modifier.fillMaxWidth().semantics { contentDescription = "当前头像测试对话框" }, contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp)
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    title?.let { Text(it) }; content()
                }
            }
        }
    }
    private fun ComposePixelScene.texts(label: String) = semanticsBounds { c -> c.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
    private fun ComposePixelScene.descriptions(label: String) = unmergedSemanticsBounds { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.description(label: String) = assertNotNull(descriptions(label).firstOrNull { it.width > 0 && it.height > 0 })
    private fun ComposePixelScene.click(rect: Rect) { moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame() }
    private fun ComposePixelScene.clickText(label: String, f: Fixture, preferUp: Boolean = false) = click(revealText(label, f, preferUp))
    private fun ComposePixelScene.revealText(label: String, f: Fixture, preferUp: Boolean = false) =
        reveal(f, preferUp = preferUp) { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } ||
            label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.clickDialogText(label: String, f: Fixture) = click(revealDialogText(label, f))
    private fun ComposePixelScene.revealDialogText(label: String, f: Fixture) =
        reveal(f, dialogOnly = true) { it.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
    private fun ComposePixelScene.revealDescription(label: String, f: Fixture, minHeightDp: Float = 8f, preferUp: Boolean = false) =
        reveal(f, minHeightDp, preferUp = preferUp) { label in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    private fun ComposePixelScene.reveal(f: Fixture, minHeightDp: Float = 8f, preferUp: Boolean = false,
        dialogOnly: Boolean = false, predicate: (SemanticsConfiguration) -> Boolean): Rect {
        val margin = 2f * f.density
        fun candidates(): List<Rect> {
            val all = semanticsBounds(predicate)
            if (!dialogOnly) return all
            val dialog = descriptions("当前头像测试对话框").firstOrNull { it.width > 0 && it.height > 0 } ?: return emptyList()
            return all.filter { it.width > 0 && it.height > 0 && it.left >= dialog.left && it.right <= dialog.right &&
                it.top >= dialog.top && it.bottom <= dialog.bottom }
        }
        fun visible() = candidates().firstOrNull { it.width > 0 && it.height >= minHeightDp * f.density && it.top >= margin && it.bottom <= f.pixelHeight - margin }
        var down = false
        repeat(480) { index ->
            visible()?.let { return it }
            val target = candidates().firstOrNull { it.width > 0 && it.height > 0 }
            var position = 0f
            semanticsBounds { c -> c.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { position = it.value() }; false }
            if (position <= .5f) down = true
            val delta = when {
                target != null && target.top < margin -> -8f
                target != null && target.bottom > f.pixelHeight - margin -> 8f
                target != null && target.height < minHeightDp * f.density -> if (target.center.y < f.pixelHeight / 2f) -8f else 8f
                target != null -> 8f
                dialogOnly -> if (index < 240) 8f else -8f
                preferUp -> if (index < 240) -8f else 8f
                down -> if (index < 240) 8f else -8f
                else -> if (index < 240) -8f else 8f
            }
            scrollAt((f.detailLeft + 2f) * f.density, f.pixelHeight / 2f, deltaY = delta * f.density); settledFrame(5, 32)
        }
        return assertNotNull(visible(), "Actual control must be reachable at ${f.width}x${f.height}: ${candidates()}")
    }
    private fun ComposePixelScene.await(predicate: () -> Boolean) {
        repeat(160) { if (predicate()) return; settledFrame(2, 16); Thread.sleep(5) }
        assertTrue(predicate(), "Current photo UI did not settle")
    }
    private fun ComposePixelScene.awaitSlow(predicate: () -> Boolean) {
        repeat(750) { if (predicate()) return; settledFrame(2, 16); Thread.sleep(20) }
        assertTrue(predicate(), "Current photo renderer did not settle")
    }
    private fun waitWithoutFrames(predicate: () -> Boolean) { repeat(200) { if (predicate()) return; Thread.sleep(5) }; assertTrue(predicate()) }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        File("build/settings-current-profile-photo-ui-validation/$name.png").also { it.parentFile.mkdirs(); ImageIO.write(image, "png", it) }
    }
    private fun onUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
    private fun worker(block: () -> Unit) { Thread(block).apply { start(); join() } }

    companion object {
        private val png: File by lazy {
            File("build/settings-current-profile-photo-ui-validation/fixture.png").also { file ->
                file.parentFile.mkdirs(); val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, 0xffe63ac4.toInt())
                ImageIO.write(image, "png", file)
            }
        }
    }
}
