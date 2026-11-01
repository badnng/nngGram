package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import kotlinx.coroutines.delay

/** Selector artwork flies for the official 300 ms, then center/around play once without changing message state. */
@Composable
internal fun ReactionAdditionFlight(intent: ReactionAdditionIntent, footer: Rect, chip: Rect?,
    snapshot: ChatSnapshot, features: ChatFeatureActions,
    chipArtwork: Rect? = null,
    onCenterRendering: (Boolean) -> Unit = {}, onFinished: () -> Unit) {
    val progress = remember(intent.id) { Animatable(0f) }
    var centerFinished by remember(intent.id) { mutableStateOf(false) }
    var centerReady by remember(intent.id) { mutableStateOf(false) }
    var aroundFinished by remember(intent.id) { mutableStateOf(false) }
    val finish by rememberUpdatedState(onFinished)
    val centerRendering by rememberUpdatedState(onCenterRendering)
    DisposableEffect(snapshot.token, intent.messageId, intent.key, intent.id) {
        val finishThisIntent = onFinished
        val releaseThisCenter = onCenterRendering
        onDispose { releaseThisCenter(false); finishThisIntent() }
    }
    LaunchedEffect(intent.id) {
        progress.animateTo(1f, tween(300, easing = LinearEasing))
        // Download/decode may be late; the local intent still has a bounded visual lifetime.
        delay(4000)
        finish()
    }
    val density = LocalDensity.current
    val edge = with(density) { reactionInlineSlotEdge.toPx() }
    val inset = with(density) { 10.dp.toPx() }
    // An acknowledgement can insert/resize the chip during the flight. Follow
    // its measured icon, including the smaller inset of avatar-bearing chips.
    val target = chipArtwork ?: Rect((chip?.left ?: footer.left) + inset,
        (chip?.center?.y ?: footer.center.y) - edge / 2,
        (chip?.left ?: footer.left) + inset + edge,
        (chip?.center?.y ?: footer.center.y) + edge / 2)
    val source = intent.source ?: target
    val arc = with(density) { 50.dp.toPx() }
    val flying by remember(intent.id) { derivedStateOf { progress.value < 1f } }
    Box(Modifier.requiredSize(reactionInlineSlotEdge).clearAndSetSemantics {}.graphicsLayer {
        val p = progress.value
        val x = source.center.x + (target.center.x - source.center.x) * p - edge / 2 - footer.left
        val y = reactionFlightTop(source.center.y, target.center.y, arc, p) - edge / 2 - footer.top
        val size = source.height.coerceAtLeast(edge) / edge * (1f - p) + p
        translationX = x; translationY = y
        scaleX = size; scaleY = size
        transformOrigin = TransformOrigin.Center
    }) {
        // Keep the native static glyph while an effect loads, or when its decoder rejects the file.
        ReactionArtwork(intent.key, snapshot, features, false, Modifier.fillMaxSize(), 16,
            hiddenWhile = { !flying && (chip != null || !centerFinished && centerReady) })
        if (!flying && !centerFinished) {
            ReactionAdditionArtwork(intent.key, snapshot, features, intent.id, false, Modifier.fillMaxSize(),
                onReady = {
                    centerReady = it
                    centerRendering(it && !centerFinished)
                }) {
                centerFinished = true
                centerRendering(false)
            }
        }
        // The native 64 dp around canvas remains centered on the 18 dp slot.
        // requiredSize already centers its oversized drawing in that allocation.
        if (!flying && !aroundFinished) ReactionAdditionArtwork(intent.key, snapshot, features, intent.id, true,
            Modifier.requiredSize(reactionInlineAroundEdge)) {
            aroundFinished = true
        }
    }
    // Once both available streams finish, a rejected/slow request must not leave a phantom glyph.
    val visual = snapshot.reactionVisuals[intent.key]
    LaunchedEffect(centerFinished, aroundFinished, visual) {
        if (centerFinished && (aroundFinished || visual?.aroundAnimation == null)) finish()
    }
}
