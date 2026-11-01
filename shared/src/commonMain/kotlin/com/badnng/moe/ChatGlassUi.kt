package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlurEffect
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Sources record only scrolling content, never consumers of this same backdrop. */
internal val LocalChatBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Menus can extend past an individual list or conversation's sampled content bounds. */
internal val LocalWorkspaceMenuBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** The conversation reserves this whole feather above its naturally measured input overlay. */
internal val ConversationComposerBlurFeather = 24.dp

@Composable
internal fun rememberChatBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop { drawRect(surface); drawContent() }
}

/**
 * Matches Hypernotesuper's back-button mixing, saturation and soft centered shadow.
 * [downwardShadow] lowers and slightly strengthens that shadow for controls resting on a
 * blurred band, like the official history-down button's separate bottom shadow layer.
 */
@Composable
internal fun Modifier.chatGlassSurface(cornerRadius: Dp, shadow: Boolean = true, circular: Boolean = false,
    tintAlpha: Float? = null, downwardShadow: Boolean = false): Modifier {
    val colors = MiuixTheme.colorScheme
    val backdrop = LocalChatBackdrop.current
    val samplingBackdrop = remember(backdrop) { backdrop?.let(::WindowAlignedChatBackdrop) }
    val dark = colors.surface.luminance() < 0.5f
    val tintOpacity = (tintAlpha ?: if (dark) 0.88f else 0.78f).coerceIn(0f, 1f)
    val shape: Shape = if (circular) CircleShape else rememberMiuixSquircleShape(cornerRadius)
    val shade = remember(dark, colors.onSurface, colors.surface, downwardShadow) {
        val color = if (dark) colors.surface else colors.onSurface
        if (downwardShadow) Shadow(8.dp, color = color.copy(alpha = if (dark) 0.5f else 0.22f),
            offset = DpOffset(0.dp, 3.dp))
        else Shadow(6.dp, color = color.copy(alpha = if (dark) 0.4f else 0.16f), offset = DpOffset.Zero)
    }
    // The outer Miuix clip owns the corner mask; the blur must not multiply its edge alpha.
    val effect = if (samplingBackdrop != null) Modifier.textureBlur(
        backdrop = samplingBackdrop, shape = RectangleShape, blurRadius = 12f, noiseCoefficient = 0f,
        colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry((if (dark) colors.surfaceContainer else Color.White)
                .copy(alpha = tintOpacity))), saturation = 1.1f),
    ) else Modifier.background(colors.surfaceContainer)
    return then(if (shadow) Modifier.dropShadow(shape, shade) else Modifier)
        .then(if (circular) Modifier.clip(CircleShape) else Modifier.chatSquircleClip(cornerRadius)).then(effect)
}

/** Stretch the reference falloff across the whole background, including its feather. */
internal fun chatProgressiveGradient(bottom: Boolean): ProgressiveBlur =
    (if (bottom) ProgressiveBlur.Bottom else ProgressiveBlur.Top).copy(
        startFraction = 0.15f, endFraction = 1f, curve = 1.25f)

@Composable
internal fun ChatProgressiveBlur(backdrop: LayerBackdrop?, bottom: Boolean, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val gradient = remember(bottom) { chatProgressiveGradient(bottom) }
    val radius = minOf(18f, 52f / LocalDensity.current.density)
    val tint = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(colors.surface.copy(alpha = 0.52f))))
    Box(if (backdrop == null) modifier.background(colors.surface) else modifier.drawBackdrop(
        backdrop = backdrop, shape = { RectangleShape }, progressiveGradient = gradient,
        effects = { progressiveTextureBlurEffect(blurRadiusX = radius, gradient = gradient,
            noiseCoefficient = 0f, colors = tint) },
    ))
}

/** Extends only the sampled background, keeping control measurements and hit regions unchanged. */
internal fun Modifier.blurFeather(extra: Dp, bottom: Boolean): Modifier = layout { measurable, constraints ->
    val feather = extra.roundToPx()
    val child = measurable.measure(constraints.offset(vertical = feather))
    layout(child.width, (child.height - feather).coerceAtLeast(0)) {
        child.placeRelative(0, if (bottom) -feather else 0)
    }
}
