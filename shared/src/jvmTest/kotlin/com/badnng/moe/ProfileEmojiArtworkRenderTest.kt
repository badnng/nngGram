package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.media.ProfileEmojiArtworkRole
import com.badnng.moe.settings.SettingsBackgroundEmoji
import com.badnng.moe.settings.SettingsProfileColorsCatalog
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** CPU scenes and an actual asymmetric alpha bitmap; these establish no live-account acceptance. */
class ProfileEmojiArtworkRenderTest {
    @Test fun replacingThePageIdentityRevokesTheOldLeaseEvenWhenItsEmojiAndPixelsAreIdentical() {
        ProfileRendererArtworkFixture().use { provider ->
            val identity = mutableStateOf("old-page")
            scene(provider) {
                CompositionLocalProvider(LocalProfileEmojiArtworkIdentity provides identity.value) {
                    ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true,
                        modifier = Modifier.offset(16.dp, 16.dp).size(48.dp))
                }
            }.use { scene ->
                scene.settledFrame()
                val old = provider.calls.single()
                identity.value = "new-page"
                scene.settledFrame()
                val current = provider.calls.last()
                assertEquals(2, provider.calls.size)
                assertEquals(1, old.closes.get())
                assertFalse(old.active())
                assertNotEquals(old.token.ownerId, current.token.ownerId)
                old.complete(0xffff0000.toInt(), mask = false, ignoreClosed = true)
                current.complete()
                val ready = scene.settledFrame()
                assertEquals(0, count(ready, ::red))
                assertTrue(count(ready, ::green) > 100)
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun optionUsesPreparedAlphaAndTintWithoutPaintingTransparentPixels() {
        for (density in DENSITIES) {
            ProfileRendererArtworkFixture(completeImmediately = true).use { provider ->
                scene(provider, density) {
                    ProfileEmojiOptionArtwork(-91, EXPECTED, GREEN, true,
                        Modifier.offset(16.dp, 16.dp).size(48.dp))
                }.use { scene ->
                    val frame = scene.settledFrame()
                    assertEquals(1, provider.calls.size)
                    val call = provider.calls.single()
                    assertEquals(EXPECTED, call.expected)
                    assertEquals(-91L, call.token.customEmojiId)
                    assertEquals(ProfileEmojiArtworkRole.OPTION_THUMBNAIL, call.request.role)
                    assertEquals((36 * density).roundToInt(), call.request.physicalSizePx)
                    assertTrue(call.active())
                    // Probe interiors, away from fractional target edges at1.25x.
                    // The36dp artwork is centered inside its48dp interaction target.
                    near(0xff008000.toInt(), frame.px(31, 40, density))
                    near(0xffffffff.toInt(), frame.px(40, 40, density))
                    near(0xffffffff.toInt(), frame.px(18, 40, density))
                    val half = frame.px(49, 31, density)
                    assertTrue((half ushr 16 and 255) in 120..136,
                        "The detached half-alpha square must retain its true prepared coverage at $density.")
                    assertTrue((half ushr 8 and 255) in 184..200)
                    profileRendererExport(frame, "prepared-alpha-option-$density")
                }
                assertEquals(1, provider.calls.single().closes.get())
                assertEquals(0, provider.providerCloses.get(), "A renderer must not close the shared engine provider.")
            }
        }
    }

    @Test fun inactiveUnfocusedEmptyAndClippedConsumersDoNotAcquireAnyArtwork() {
        for (condition in listOf("inactive", "unfocused", "empty", "offscreen", "old-generation")) {
            ProfileRendererArtworkFixture().use { provider ->
                val focused = mutableStateOf(condition != "unfocused")
                scene(provider, focused = focused, hostGeneration = if (condition == "old-generation") 8 else 7) {
                    val modifier = when (condition) {
                        "empty" -> Modifier.size(0.dp)
                        "offscreen" -> Modifier.offset(500.dp, 500.dp).size(48.dp)
                        else -> Modifier.size(48.dp)
                    }
                    ProfileEmojiOptionArtwork(-91, EXPECTED, GREEN, condition != "inactive", modifier)
                }.use { scene ->
                    scene.settledFrame()
                    assertEquals(0, provider.calls.size, condition)
                }
            }
        }
    }

    @Test fun replacingTheIdentityRevokesItsLeaseAndRejectsItsLatePreparedPixels() {
        ProfileRendererArtworkFixture().use { provider ->
            val id = mutableStateOf(-91L)
            scene(provider) {
                ProfileEmojiOptionArtwork(id.value, tint = GREEN, active = true,
                    modifier = Modifier.offset(16.dp, 16.dp).size(48.dp))
            }.use { scene ->
                scene.settledFrame()
                val old = provider.calls.single()
                id.value = -92L
                scene.settledFrame()
                assertEquals(2, provider.calls.size)
                val current = provider.calls.last()
                assertEquals(1, old.closes.get())
                assertFalse(old.active())
                assertNotEquals(old.token.ownerId, current.token.ownerId)
                assertEquals(-92L, current.token.customEmojiId)
                old.complete(0xffff0000.toInt(), mask = false, ignoreClosed = true)
                val stale = scene.settledFrame()
                assertEquals(0, count(stale, ::red), "A late old identity must not paint over the new pending owner.")
                current.complete()
                val ready = scene.settledFrame()
                assertTrue(count(ready, ::green) > 100)
                assertEquals(0, count(ready, ::red))
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun losingFocusReleasesTheOwnerAndRefocusingCreatesAnIndependentLease() {
        ProfileRendererArtworkFixture(completeImmediately = true).use { provider ->
            val focused = mutableStateOf(true)
            scene(provider, focused = focused) {
                ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true, modifier = Modifier.size(48.dp))
            }.use { scene ->
                scene.settledFrame()
                val first = provider.calls.single()
                focused.value = false
                scene.settledFrame()
                assertEquals(1, first.closes.get())
                assertFalse(first.active())
                focused.value = true
                scene.settledFrame()
                assertEquals(2, provider.calls.size)
                assertTrue(provider.calls.last().active())
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun changingTheAccountRevokesTheOldOwnerEvenWhenTheCustomEmojiIdentityIsIdentical() {
        ProfileRendererArtworkFixture().use { provider ->
            val account = mutableStateOf(11L)
            scene(provider, accountUserId = { account.value }) {
                ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true, modifier = Modifier.size(48.dp))
            }.use { scene ->
                scene.settledFrame()
                val old = provider.calls.single()
                account.value = 12
                scene.settledFrame()
                val current = provider.calls.last()
                assertEquals(2, provider.calls.size)
                assertEquals(11L, old.token.accountUserId)
                assertEquals(12L, current.token.accountUserId)
                assertEquals(1, old.closes.get())
                old.complete(0xffff0000.toInt(), mask = false, ignoreClosed = true)
                current.complete()
                val frame = scene.settledFrame()
                assertEquals(0, count(frame, ::red))
                assertTrue(count(frame, ::green) > 100)
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun animatedProfileFramesKeepOnlyReadyAndPendingLeasesAndStopRequestingWhenUnfocused() {
        ProfileRendererArtworkFixture().use { provider ->
            val focused = mutableStateOf(true)
            scene(provider, focused = focused) {
                PeerProfilePatternArtwork(-91, tint = GREEN, active = true, modifier = Modifier.size(280.dp, 180.dp),
                    avatarRectDp = Rect(108f, 32f, 172f, 96f), overlay = false)
            }.use { scene ->
                scene.settledFrame()
                val first = provider.calls.single()
                assertEquals(ProfileEmojiArtworkRole.PROFILE_MASK, first.request.role)
                first.complete(animated = true)
                scene.settledFrame(30)
                assertEquals(2, provider.calls.size,
                    "A pending frame must bound acquisitions even when many animation frames elapse.")
                val pending = provider.calls.last()
                assertTrue(provider.calls.all { it.closes.get() == 0 },
                    "The old ready pixels remain leased while the next prepared frame is pending.")
                focused.value = false
                scene.settledFrame(30)
                assertTrue(provider.calls.all { it.closes.get() == 1 })
                assertEquals(2, provider.calls.size, "Losing focus must stop the animation clock's acquisitions.")
                focused.value = true
                scene.settledFrame()
                assertEquals(3, provider.calls.size)
                val resumed = provider.calls.last()
                resumed.complete(animated = true)
                scene.settledFrame(30)
                assertEquals(4, provider.calls.size)
                val failed = provider.calls.last()
                failed.fail()
                scene.settledFrame(30)
                assertEquals(1, resumed.closes.get(), "A failed replacement must release the former ready bitmap.")
                assertEquals(1, failed.closes.get(), "A failure must immediately release its pending lease too.")
                assertTrue(provider.calls.all { it.closes.get() == 1 },
                    "Every lease must close exactly once after failure; frameMillis/closes=" +
                        provider.calls.map { it.request.frameTimeMillis to it.closes.get() })
                assertEquals(4, provider.calls.size, "A failure must not start an idle retry loop; frameMillis/closes=" +
                    provider.calls.map { it.request.frameTimeMillis to it.closes.get() })
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun animatedReadyFramesContinueWithOnlyTheirReadyAndPendingLeasesOpen() {
        ProfileRendererArtworkFixture().use { provider ->
            scene(provider) {
                PeerProfilePatternArtwork(-91, tint = GREEN, active = true, modifier = Modifier.size(280.dp, 180.dp),
                    avatarRectDp = Rect(108f, 32f, 172f, 96f), overlay = false)
            }.use { scene ->
                scene.settledFrame()
                repeat(3) { index ->
                    val ready = provider.calls.last()
                    ready.complete(animated = true)
                    scene.settledFrame(30)
                    assertEquals(index + 2, provider.calls.size, "Each completed frame must allow exactly one next frame.")
                    val pending = provider.calls.last()
                    assertNotEquals(ready.request.frameTimeMillis, pending.request.frameTimeMillis)
                    assertEquals(0, ready.closes.get(), "The current ready pixels remain leased until replacement.")
                    assertEquals(0, pending.closes.get(), "The single pending preparation owns its own lease.")
                    assertTrue(provider.calls.dropLast(2).all { it.closes.get() == 1 })
                    assertEquals(2, provider.calls.count { it.closes.get() == 0 })
                }
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun optionReplyAndGiftKeepTheirOfficialStaticFramesEvenIfTheAssetCanAnimate() {
        ProfileRendererArtworkFixture().use { provider ->
            scene(provider) {
                ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true, modifier = Modifier.size(48.dp))
                PeerReplyPatternArtwork(-91, tint = GREEN, quote = false, active = true,
                    modifier = Modifier.offset(0.dp, 64.dp).size(220.dp, 80.dp), giftModelCustomEmojiId = -500)
            }.use { scene ->
                scene.settledFrame()
                assertEquals(5, provider.calls.size)
                assertEquals(setOf(ProfileEmojiArtworkRole.OPTION_THUMBNAIL, ProfileEmojiArtworkRole.REPLY_MASK,
                    ProfileEmojiArtworkRole.GIFT), provider.calls.map { it.request.role }.toSet())
                provider.calls.forEach { it.complete(animated = true, mask = it.request.role != ProfileEmojiArtworkRole.GIFT) }
                scene.settledFrame(60)
                assertEquals(5, provider.calls.size,
                    "Only the profile mask owns an animation clock; reply, gift and picker retain frame zero.")
                assertTrue(provider.calls.all { it.request.frameTimeMillis == 0L })
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun aFailedOptionRetriesOnlyAfterItsActualPointerActionAndChangesTheNativeRetryRevision() {
        for (density in DENSITIES) {
            ProfileRendererArtworkFixture().use { provider ->
                scene(provider, density) {
                    ProfileEmojiOptionArtwork(-91, EXPECTED, GREEN, true,
                        Modifier.offset(16.dp, 16.dp).size(48.dp))
                }.use { scene ->
                    scene.settledFrame()
                    val failed = provider.calls.single()
                    failed.fail()
                    scene.settledFrame(30)
                    assertEquals(1, failed.closes.get())
                    assertEquals(1, provider.calls.size, "Failure must remain quiet until the user's explicit retry.")
                    val retry = scene.semanticsBounds {
                        it.getOrNull(SemanticsActions.OnClick)?.label == "重试图案"
                    }.single()
                    scene.pressAt(retry.center.x, retry.center.y)
                    scene.releaseAt(retry.center.x, retry.center.y)
                    scene.settledFrame()
                    assertEquals(2, provider.calls.size)
                    val current = provider.calls.last()
                    assertEquals(failed.token.revision + 1, current.token.revision)
                    assertNotEquals(failed.token.ownerId, current.token.ownerId)
                    assertEquals(failed.token.customEmojiId, current.token.customEmojiId)
                    assertEquals(EXPECTED, current.expected)
                    current.complete()
                    val frame = scene.settledFrame()
                    near(0xff008000.toInt(), frame.px(31, 40, density))
                    assertTrue(scene.semanticsBounds {
                        it.getOrNull(SemanticsActions.OnClick)?.label == "重试图案"
                    }.isEmpty())
                    profileRendererExport(frame, "prepared-option-pointer-retry-$density")
                }
                assertTrue(provider.calls.all { it.closes.get() == 1 })
            }
        }
    }

    @Test fun twoVisibleConsumersOwnIndependentLeasesAndDisposingOneRetainsTheOthersPixels() {
        ProfileRendererArtworkFixture(completeImmediately = true).use { provider ->
            val firstVisible = mutableStateOf(true)
            scene(provider) {
                key("first") {
                    if (firstVisible.value) ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true,
                        modifier = Modifier.offset(16.dp, 16.dp).size(48.dp))
                }
                key("second") {
                    ProfileEmojiOptionArtwork(-91, tint = GREEN, active = true,
                        modifier = Modifier.offset(88.dp, 16.dp).size(48.dp))
                }
            }.use { scene ->
                scene.settledFrame()
                assertEquals(2, provider.calls.size)
                val first = provider.calls[0]
                val second = provider.calls[1]
                assertNotEquals(first.token.ownerId, second.token.ownerId)
                firstVisible.value = false
                val remaining = scene.settledFrame()
                assertEquals(2, provider.calls.size, "Removing an independent owner must not reacquire this ready owner.")
                assertEquals(1, first.closes.get())
                assertEquals(0, second.closes.get())
                near(0xffffffff.toInt(), remaining[31, 40])
                near(0xff008000.toInt(), remaining[103, 40])
            }
            assertTrue(provider.calls.all { it.closes.get() == 1 })
        }
    }

    @Test fun replyRequestsOnlyTheThreePhysicalMaskSizesAndClipsToItsOwnSurface() {
        for (density in DENSITIES) for (quote in listOf(false, true)) {
            ProfileRendererArtworkFixture(completeImmediately = true).use { provider ->
                scene(provider, density) {
                    PeerReplyPatternArtwork(-91, EXPECTED, GREEN, quote, true,
                        Modifier.offset(16.dp, 24.dp).size(220.dp, 70.dp))
                }.use { scene ->
                    val frame = scene.settledFrame()
                    assertEquals(3, provider.calls.size)
                    assertEquals(setOf(ProfileEmojiArtworkRole.REPLY_MASK), provider.calls.map { it.request.role }.toSet())
                    assertEquals(setOf(12, 16, 20).map { (it * density).roundToInt() }.toSet(),
                        provider.calls.map { it.request.physicalSizePx }.toSet())
                    assertTrue(count(frame, ::green) > 10)
                    for (y in 0 until frame.height) for (x in 0 until frame.width) {
                        if (x < (16 * density).roundToInt() || x >= (236 * density).roundToInt() ||
                            y < (24 * density).roundToInt() || y >= (94 * density).roundToInt())
                            assertFalse(green(frame[x, y]), "Reply artwork escaped its real surface clip: $quote/$density/$x,$y")
                    }
                    profileRendererExport(frame, "prepared-reply-clip-$quote-$density")
                }
                assertTrue(provider.calls.all { it.closes.get() == 1 })
            }
        }
    }

    @Test fun readyGiftOwnsOnlyTheFirstReplyInstanceAndKeepsItsOriginalColor() {
        ProfileRendererArtworkFixture().use { provider ->
            scene(provider) {
                PeerReplyPatternArtwork(-91, EXPECTED, GREEN, false, true,
                    Modifier.offset(16.dp, 24.dp).size(220.dp, 80.dp), giftModelCustomEmojiId = -500)
            }.use { scene ->
                scene.settledFrame()
                assertEquals(4, provider.calls.size)
                provider.calls.filter { it.request.role == ProfileEmojiArtworkRole.REPLY_MASK }.forEach { it.complete() }
                val masksOnly = scene.settledFrame()
                assertEquals(0, count(masksOnly, ::red))
                assertTrue(count(masksOnly, ::green) > 10)
                val gift = provider.calls.single { it.request.role == ProfileEmojiArtworkRole.GIFT }
                assertEquals(-500L, gift.token.customEmojiId)
                assertEquals(18, gift.request.physicalSizePx)
                gift.complete(0xffff0000.toInt(), mask = false)
                val combined = scene.settledFrame()
                assertTrue(count(combined, ::red) > 20, "The gift's prepared color must survive the reply tint.")
                assertTrue(count(combined, ::green) > 10, "The other official mask instances must remain visible.")
                for (y in 0 until combined.height) for (x in 0 until combined.width) if (red(combined[x, y])) {
                    assertTrue(x in 208 until 226 && y in 28 until 46,
                        "The gift substitutes only the first right-anchored 18dp instance.")
                }
                profileRendererExport(combined, "prepared-reply-gift-first-point")
            }
        }
    }

    @Test fun profileUsesItsSeparateNormalMaskBudgetAndMeasuredAvatarGeometry() {
        for (density in DENSITIES) {
            ProfileRendererArtworkFixture(completeImmediately = true).use { provider ->
                scene(provider, density) {
                    PeerProfilePatternArtwork(-91, EXPECTED, GREEN, true,
                        Modifier.offset(16.dp, 16.dp).size(280.dp, 180.dp),
                        Rect(108f, 32f, 172f, 96f), overlay = false)
                }.use { scene ->
                    val frame = scene.settledFrame()
                    val call = provider.calls.single()
                    assertEquals(ProfileEmojiArtworkRole.PROFILE_MASK, call.request.role)
                    assertEquals((18 * .910f * density).roundToInt(), call.request.physicalSizePx)
                    assertTrue(count(frame, ::green) > 10)
                    near(0xffffffff.toInt(), frame.px(156, 80, density))
                    profileRendererExport(frame, "prepared-profile-eighteen-points-$density")
                }
                assertEquals(1, provider.calls.single().closes.get())
            }
        }
    }

    private fun scene(provider: ProfileRendererArtworkFixture, density: Float = 1f,
        focused: MutableState<Boolean> = mutableStateOf(true), hostGeneration: Long = 7,
        accountUserId: () -> Long = { 11 },
        content: @Composable () -> Unit) =
        ComposePixelScene((320 * density).roundToInt(), (220 * density).roundToInt()) {
            val native = LocalWindowInfo.current
            val window = remember(native) { object : WindowInfo by native {
                override val isWindowFocused get() = focused.value
                override val containerSize = IntSize((320 * density).roundToInt(), (220 * density).roundToInt())
            } }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window) {
                ProfileEmojiArtworkHost(provider, hostGeneration, accountUserId(), SettingsProfileColorsCatalog()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) { content() }
                }
            }
        }

    private fun ComposePixelFrame.px(x: Int, y: Int, density: Float) = this[(x * density).roundToInt(), (y * density).roundToInt()]
    private fun near(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 3,
            "Expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }
    private fun count(frame: ComposePixelFrame, predicate: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) if (predicate(frame[x, y])) count++
        return count
    }
    // The neutral pending circle has only12% tint; it cannot satisfy real-artwork readiness.
    private fun green(pixel: Int) = (pixel ushr 8 and 255) > (pixel ushr 16 and 255) + 20 &&
        (pixel ushr 8 and 255) > (pixel and 255) + 20
    private fun red(pixel: Int) = (pixel ushr 16 and 255) > 200 && (pixel ushr 8 and 255) < 100 && (pixel and 255) < 100

    companion object {
        private val DENSITIES = listOf(1f, 1.25f, 1.5f, 2f)
        private val GREEN = Color(0xff008000)
        private val EXPECTED = SettingsBackgroundEmoji(-91, StickerItem(90, 0, "",
            MessageMedia(MediaFile(901), width = 32, height = 32)))
    }
}
