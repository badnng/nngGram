package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Pinned Telegram Desktop/lib_ui: ordinary whole-message highlight, without a hold.
internal const val MessageJumpHighlightFadeInMillis = 500
internal const val MessageJumpHighlightFadeOutMillis = 3000
internal const val MessageJumpHighlightDurationMillis = MessageJumpHighlightFadeInMillis + MessageJumpHighlightFadeOutMillis

/** Element::paintCustomHighlight paints beneath the row; its normal bubble stays intact. */
@Composable
internal fun Modifier.messageJumpHighlight(highlighted: Boolean, owner: Any): Modifier {
    val progress = remember(owner) { Animatable(0f) }
    val overlay = MiuixTheme.colorScheme.primary.copy(alpha = 76f / 255f)
    LaunchedEffect(progress, highlighted) {
        progress.snapTo(0f)
        if (highlighted) {
            progress.animateTo(1f, tween(MessageJumpHighlightFadeInMillis, easing = LinearEasing))
            progress.animateTo(0f, tween(MessageJumpHighlightFadeOutMillis, easing = LinearEasing))
        }
    }
    return drawBehind {
        val opacity = progress.value
        if (opacity > 0f) drawRect(overlay.copy(alpha = overlay.alpha * opacity))
    }
}
