package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface

/** Adapts Miuix's public path builder for APIs that require a Shape, such as shadows. */
internal data class MiuixSquircleShape(
    val cornerRadius: Dp,
    val squircleEnabled: Boolean = true,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply {
            addSquircleRect(
                width = size.width,
                height = size.height,
                cornerRadius = with(density) { cornerRadius.toPx() },
                squircleEnabled = squircleEnabled,
            )
        })
}

@Composable
internal fun rememberMiuixSquircleShape(cornerRadius: Dp): Shape {
    val enabled = isSquircleEnabled()
    return remember(cornerRadius, enabled) { MiuixSquircleShape(cornerRadius, enabled) }
}

@Composable
internal fun Modifier.chatSquircleClip(cornerRadius: Dp): Modifier = squircleClip(cornerRadius)

@Composable
internal fun Modifier.chatSquircleBackground(color: Color, cornerRadius: Dp): Modifier =
    squircleBackground(color, cornerRadius)

/** Miuix owns the fill, subtree mask and feedback clipping, including its supported fallback. */
@Composable
internal fun Modifier.chatSquircleSurface(color: Color, cornerRadius: Dp): Modifier =
    squircleSurface(color, cornerRadius)
