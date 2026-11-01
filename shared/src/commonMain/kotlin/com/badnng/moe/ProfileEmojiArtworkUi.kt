package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.badnng.moe.media.*
import com.badnng.moe.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.*

internal data class ProfileEmojiArtworkClient(val provider: ProfileEmojiArtworkProvider?, val generation: Long,
    val accountUserId: Long)
internal val LocalProfileEmojiArtworkClient = staticCompositionLocalOf { ProfileEmojiArtworkClient(null, 0, 0) }
internal val LocalPeerAppearanceCatalog = staticCompositionLocalOf { SettingsProfileColorsCatalog() }
internal val LocalPeerProfilePatternOverlay = staticCompositionLocalOf { true }
internal val LocalProfileEmojiArtworkIdentity = staticCompositionLocalOf<Any?> { null }

/** One engine-owned provider; individual visible renderers own and close only their own leases. */
@Composable
fun ProfileEmojiArtworkHost(provider: ProfileEmojiArtworkProvider?, generation: Long, accountUserId: Long,
    catalog: SettingsProfileColorsCatalog, content: @Composable () -> Unit) {
    val client = remember(provider, generation, accountUserId) { ProfileEmojiArtworkClient(provider, generation, accountUserId) }
    CompositionLocalProvider(LocalProfileEmojiArtworkClient provides client, LocalPeerAppearanceCatalog provides catalog,
        LocalSettingsProfileColorPatternPreview provides PreparedSettingsProfileColorPatternPreview, content = content)
}

private object PreparedSettingsProfileColorPatternPreview : SettingsProfileColorPatternPreview {
    @Composable
    override fun Content(emoji: SettingsBackgroundEmoji?, customEmojiId: Long, part: SettingsProfileColorPart,
        role: SettingsProfileColorArtworkRole, tint: Color, active: Boolean, modifier: Modifier,
        giftModelCustomEmojiId: Long) {
        when (role) {
            SettingsProfileColorArtworkRole.OPTION_THUMBNAIL -> ProfileEmojiOptionArtwork(customEmojiId, emoji, tint, active, modifier)
            SettingsProfileColorArtworkRole.NAME_PREVIEW -> PeerReplyPatternArtwork(customEmojiId, emoji, tint, false,
                active, modifier, giftModelCustomEmojiId)
            SettingsProfileColorArtworkRole.PROFILE_PREVIEW -> BoxWithConstraints(modifier) {
                PeerProfilePatternArtwork(customEmojiId, emoji, tint, active, Modifier.fillMaxSize(),
                    Rect(maxWidth.value / 2 - 32, 32f, maxWidth.value / 2 + 32, 96f),
                    overlay = LocalPeerProfilePatternOverlay.current)
            }
        }
    }
}

private val profileArtworkOwnerSequence = MutableStateFlow(0L)
private fun nextProfileArtworkOwner(): Long {
    while (true) {
        val previous = profileArtworkOwnerSequence.value
        if (profileArtworkOwnerSequence.compareAndSet(previous, previous + 1)) return previous + 1
    }
}

/** The callback can arrive from a worker. Epoch/active checks precede installation on the composition scope. */
private class ProfileArtworkOwner {
    private val live = MutableStateFlow(true)
    private val eligible = MutableStateFlow(false)
    private val animationReady = MutableStateFlow(false)
    private val leases = mutableSetOf<ProfileEmojiArtworkLease>()
    fun active() = live.value && eligible.value
    fun animationActive() = active() && animationReady.value
    fun updateAnimationReady(ready: Boolean) { animationReady.value = ready }
    fun update(active: Boolean) { eligible.value = active }
    fun hold(lease: ProfileEmojiArtworkLease) { if (live.value) leases.add(lease) else lease.close() }
    fun install(lease: ProfileEmojiArtworkLease) {
        leases.filter { it !== lease }.forEach { it.close() }
        leases.retainAll(setOf(lease))
    }
    fun reject() { animationReady.value = false; leases.forEach { it.close() }; leases.clear() }
    fun close() { live.value = false; eligible.value = false; reject() }
}

private data class PreparedArtworkState(val value: ProfileEmojiArtwork? = null, val failed: Boolean = false)

@Composable
private fun rememberProfileArtwork(client: ProfileEmojiArtworkClient, id: Long, expected: SettingsBackgroundEmoji?,
    role: ProfileEmojiArtworkRole, physicalSize: Int, active: Boolean, revision: Long = 0): PreparedArtworkState {
    val scope = rememberCoroutineScope()
    val identity = LocalProfileEmojiArtworkIdentity.current
    val ownerId = remember(client, identity, id, expected, role, physicalSize, revision) { nextProfileArtworkOwner() }
    var frameTime by remember(ownerId) { mutableStateOf(0L) }
    var state by remember(ownerId) { mutableStateOf(PreparedArtworkState()) }
    var pending by remember(ownerId) { mutableStateOf(false) }
    val owner = remember(ownerId, active) { ProfileArtworkOwner().also { it.update(active) } }
    SideEffect { owner.update(active) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val usable = active && id != 0L && physicalSize in 1..256 && client.accountUserId > 0 &&
        client.provider?.generation == client.generation
    DisposableEffect(owner, usable, frameTime) {
        if (!usable) {
            state = PreparedArtworkState()
            onDispose { }
        } else if (state.failed) {
            // A frame already scheduled before a failure cannot restart an immutable failed asset.
            onDispose { }
        } else {
            pending = true
            var accepting = true
            var delivered = false
            val token = ProfileEmojiArtworkToken(client.generation, client.accountUserId, ownerId, revision, id)
            var lease: ProfileEmojiArtworkLease? = null
            lease = client.provider!!.acquire(token, expected, ProfileEmojiArtworkRequest(role, physicalSize, frameTime),
                owner::active) { result ->
                scope.launch {
                    yield() // also handles providers that complete synchronously before returning their lease
                    if (!delivered && accepting && owner.active()) {
                        delivered = true
                        val ready = result.value?.takeIf {
                            result.error == null && it.image != null &&
                                (role !in setOf(ProfileEmojiArtworkRole.REPLY_MASK, ProfileEmojiArtworkRole.PROFILE_MASK) || it.isAlphaMask) &&
                                (role != ProfileEmojiArtworkRole.GIFT || !it.isAlphaMask)
                        }
                        // Revoke the old frame loop before pending=false can let it schedule again.
                        // LaunchedEffect cancellation alone waits for the following composition.
                        owner.updateAnimationReady(role == ProfileEmojiArtworkRole.PROFILE_MASK &&
                            ready?.animated == true && ready.durationMillis > 0 && ready.frameDurationMillis > 0)
                        state = PreparedArtworkState(ready, ready == null)
                        if (ready != null) lease?.let(owner::install) else owner.reject()
                        pending = false
                    }
                }
            }
            owner.hold(checkNotNull(lease))
            onDispose { accepting = false }
        }
    }
    val artwork = state.value
    LaunchedEffect(owner, usable, artwork?.animated, artwork?.durationMillis, artwork?.frameDurationMillis) {
        if (usable && role == ProfileEmojiArtworkRole.PROFILE_MASK && artwork?.animated == true &&
            artwork.durationMillis > 0 && artwork.frameDurationMillis > 0) {
            val duration = artwork.durationMillis
            val interval = artwork.frameDurationMillis.coerceAtLeast(16)
            val start = withFrameNanos { it }
            while (owner.animationActive()) {
                val elapsed = withFrameNanos { (it - start) / 1_000_000 }
                if (!owner.animationActive()) break
                val next = ((elapsed % duration) / interval) * interval
                if (!pending && next != frameTime) frameTime = next
            }
        }
    }
    return state
}

/** boundsInWindow includes ancestor clipping; intersect with the actual window before acquiring anything. */
@Composable
private fun artworkVisibility(active: Boolean): Pair<Boolean, Modifier> {
    val window = LocalWindowInfo.current
    val viewport = window.containerSize
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val visible = bounds.width > 0 && bounds.height > 0 && bounds.overlaps(Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()))
    return (active && window.isWindowFocused && visible) to Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
}

@Composable
internal fun ProfileEmojiOptionArtwork(customEmojiId: Long, expected: SettingsBackgroundEmoji? = null,
    tint: Color, active: Boolean, modifier: Modifier) {
    val (eligible, visibility) = artworkVisibility(active)
    var retryRevision by remember(customEmojiId, expected) { mutableStateOf(0L) }
    val px = with(LocalDensity.current) { 36.dp.roundToPx() }.coerceIn(1, 256)
    val prepared = rememberProfileArtwork(LocalProfileEmojiArtworkClient.current, customEmojiId, expected,
        ProfileEmojiArtworkRole.OPTION_THUMBNAIL, px, eligible, retryRevision)
    Canvas(modifier.then(visibility).then(if (prepared.failed) Modifier.clickable(enabled = eligible,
        onClickLabel = "重试图案", onClick = { retryRevision++ }) else Modifier)) {
        val image = prepared.value?.image
        val side = minOf(36.dp.toPx(),size.width,size.height)
        val left = (size.width-side)/2
        val top = (size.height-side)/2
        if (image != null) drawPreparedProfileArtwork(prepared.value, Rect(left, top, left+side, top+side), tint, 1f)
        else drawCircle(tint.copy(alpha = .12f), radius = min(size.width, size.height) / 6)
    }
}

internal data class ProfileReplyPatternPoint(val x: Float, val y: Float, val size: Int, val opacity: Float)
internal fun officialProfileReplyPattern(widthDp: Float, quote: Boolean): List<ProfileReplyPatternPoint> {
    val shift = if (quote) 12 else 0
    fun p(x: Int, y: Int, size: Int, opacity: Float) = ProfileReplyPatternPoint(widthDp - x - shift, y.toFloat(), size, opacity)
    return listOf(p(28, 4, 20, .32f), p(51, 15, 16, .32f), p(64, -2, 12, .28f),
        p(87, 11, 16, .24f), p(125, -2, 20, .16f), p(28, 31, 16, .24f), p(72, 33, 20, .20f),
        p(46, 52, 16, .24f), p(24, 55, 20, .18f)) +
        if (quote) listOf(p(4, 23, 16, .28f), p(0, 48, 12, .24f)) else emptyList()
}

@Composable
internal fun PeerReplyPatternArtwork(customEmojiId: Long, expected: SettingsBackgroundEmoji? = null, tint: Color,
    quote: Boolean, active: Boolean, modifier: Modifier, giftModelCustomEmojiId: Long = 0) {
    val (eligible, visibility) = artworkVisibility(active)
    val density = LocalDensity.current
    val client = LocalProfileEmojiArtworkClient.current
    val masks = listOf(12, 16, 20).associateWith { logical ->
        rememberProfileArtwork(client, customEmojiId, expected, ProfileEmojiArtworkRole.REPLY_MASK,
            with(density) { logical.dp.roundToPx() }, eligible)
    }
    val gift = rememberProfileArtwork(client, giftModelCustomEmojiId, null, ProfileEmojiArtworkRole.GIFT,
        with(density) { 18.dp.roundToPx() }, eligible)
    Canvas(modifier.then(visibility)) {
        clipRect {
            officialProfileReplyPattern(size.width / density.density, quote).forEachIndexed { index, point ->
                val frame = if (index == 0 && gift.value?.image != null) gift.value else masks[point.size]?.value
                val logicalSize = if (index == 0 && gift.value?.image != null) 18 else point.size
                val target = Rect(point.x * density.density, point.y * density.density,
                    (point.x + logicalSize) * density.density, (point.y + logicalSize) * density.density)
                drawPreparedProfileArtwork(frame, target, tint, if (index == 0 && gift.value?.image != null) 1f else point.opacity)
            }
        }
    }
}

internal data class ProfileAnimatedPatternPoint(val center: Offset, val size: Int, val start: Float, val end: Float)
internal data class ProfilePatternPlacement(val rect: Rect, val alpha: Float)

/** Pinned GenerateAnimatedPattern: dp geometry is tied to the measured circular avatar, never the preview's dimensions. */
internal fun officialProfilePatternPoints(avatar: Rect): List<ProfileAnimatedPatternPoint> {
    val ax = avatar.left; val ay = avatar.top; val aw = avatar.width; val ah = avatar.height
    val cx = avatar.center.x; val cy = avatar.center.y
    val p24 = 19.2f; val p16 = 12.8f; val p8 = 6.4f; val p12 = 9.6f; val p48 = 38.4f; val p96 = 76.8f
    val r48 = (p48 + aw / 2) * cos(PI * 120 / 180).toFloat()
    val r16 = (p16 + ah / 2) * cos(PI * 160 / 180).toFloat()
    fun p(x: Float, y: Float, size: Int, start: Float, end: Float) = ProfileAnimatedPatternPoint(Offset(x, y), size, start, end)
    return listOf(p(cx, ay-p24,20,.02f,.42f), p(cx,ay+ah+p24,20,0f,.32f),
        p(ax-p16,cy-ah/4-p8,23,0f,.40f), p(ax+aw+p16,cy-ah/4-p8,18,0f,.40f),
        p(ax-p16,cy+ah/4+p8,24,0f,.40f), p(ax+aw+p16-4,cy+ah/4+p8,24,0f,.40f),
        p(ax-p48,cy,19,.14f,.60f), p(ax+aw+p48,cy,19,.16f,.64f),
        p(cx+r48,ay-p48+p12,17,.14f,.70f), p(cx-r48,ay-p48+p12,17,.14f,.90f),
        p(cx+r48,ay+ah+p48-p12,20,.20f,.75f), p(cx-r48,ay+ah+p48-p12,20,.20f,.85f),
        p(ax-p48-p8,cy+r16,20,.09f,.45f), p(ax+aw+p48+p8,cy+r16,19,.09f,.45f),
        p(ax-p48-p8,cy-r16,21,.09f,.45f), p(ax+aw+p48+p8,cy-r16,18,.11f,.45f),
        p(ax-p96,cy,19,.14f,.75f), p(ax+aw+p96,cy,19,.20f,.80f))
}

internal fun officialProfilePatternPlacement(point: ProfileAnimatedPatternPoint, avatar: Rect, progress: Float): ProfilePatternPlacement? {
    if (avatar.width <= 0 || avatar.height <= 0) return null
    val p = progress.coerceIn(0f, 1f)
    val collapse = 1 - ((1-p-point.start)/(point.end-point.start)).coerceIn(0f,1f)
    if (collapse <= 0) return null
    val base = Offset(point.center.x, point.center.y - 12 * (1-p))
    val center = avatar.center + (base-avatar.center) * collapse
    val radius = 8 + (point.size*.5f-8)*collapse
    // The official cached image is emojiSize=18; point.size governs collapse, not its final canvas.
    val half = 9 * radius/(point.size*.5f)
    val distance = (center-avatar.center).getDistance()/(avatar.width*2)
    val bottom = 1-((center.y-avatar.bottom-8)/56).coerceIn(0f,1f)
    val alpha = p*(1-distance.coerceIn(0f,1f))*.5f*bottom*collapse
    return ProfilePatternPlacement(Rect(center.x-half,center.y-half,center.x+half,center.y+half), alpha)
}

@Composable
internal fun PeerProfilePatternArtwork(customEmojiId: Long, expected: SettingsBackgroundEmoji? = null, tint: Color,
    active: Boolean, modifier: Modifier, avatarRectDp: Rect, progress: Float = 1f, overlay: Boolean = true) {
    val (eligible, visibility) = artworkVisibility(active)
    val density = LocalDensity.current.density
    val prepared = rememberProfileArtwork(LocalProfileEmojiArtworkClient.current, customEmojiId, expected,
        ProfileEmojiArtworkRole.PROFILE_MASK, (18 * .910f * density).roundToInt().coerceIn(1,256), eligible)
    Canvas(modifier.then(visibility)) {
        clipRect {
            officialProfilePatternPoints(avatarRectDp).forEach { point ->
                officialProfilePatternPlacement(point, avatarRectDp, progress)?.let { placement ->
                    val rect = placement.rect
                    // Official pre-raster scale is top-left anchored inside the 18dp Normal canvas.
                    val target = Rect(rect.left*density,rect.top*density,
                        (rect.left+rect.width*.910f)*density,(rect.top+rect.height*.910f)*density)
                    // useOverlayBlend is the pinned raster branch selecting black80 SrcIn;
                    // TopBar's final p.drawImage still uses ordinary SrcOver.
                    drawPreparedProfileArtwork(prepared.value, target, if (overlay) Color.Black.copy(alpha=.8f) else tint,
                        placement.alpha)
                }
            }
        }
    }
}

/** Prepared PREMUL pixels are sampled only; colorFilter uses SrcIn and retains the asset's real alpha. */
private fun DrawScope.drawPreparedProfileArtwork(artwork: ProfileEmojiArtwork?, target: Rect, tint: Color,
    alpha: Float, blendMode: BlendMode = BlendMode.SrcOver) {
    val image = artwork?.image ?: return
    if (target.width <= 0 || target.height <= 0 || alpha <= 0) return
    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width,image.height),
        dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
        dstSize = IntSize(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f,1f), colorFilter = if (artwork.isAlphaMask) ColorFilter.tint(tint,BlendMode.SrcIn) else null,
        filterQuality = FilterQuality.Low, blendMode = blendMode)
}
