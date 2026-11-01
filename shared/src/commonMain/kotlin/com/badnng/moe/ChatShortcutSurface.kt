package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

/** Native Popup bounds need real transparent space as well as any animated layer outsets. */
internal val ChatShortcutShadowPadding = 32.dp
internal val ChatShortcutDropShadow =
    Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 6.dp))

/** Put this before the surface's clip/glass so its downward shadow remains outside that clip. */
@Composable
internal fun Modifier.chatShortcutShadow(cornerRadius: Dp = 15.dp): Modifier {
    val shape = rememberMiuixSquircleShape(cornerRadius)
    return dropShadow(shape, ChatShortcutDropShadow)
}

/** Apply on the same layer that owns alpha/scale, before the shadow and surface clip. */
internal fun GraphicsLayerScope.chatShortcutShadowOutsets(padding: Dp = ChatShortcutShadowPadding) {
    outsets = LayerOutsets(padding)
}

@Composable
internal fun ChatShortcutShadowSpace(modifier: Modifier = Modifier, padding: Dp = ChatShortcutShadowPadding,
    content: @Composable BoxScope.() -> Unit) {
    Box(modifier.padding(padding), content = content)
}

/** Retain an existing menu's body placement while including its real transparent shadow space. */
internal class ChatShortcutPopupPosition(
    private val bodyPosition: PopupPositionProvider,
    private val shadowPadding: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val bodySize = IntSize((popupContentSize.width - shadowPadding * 2).coerceAtLeast(0),
            (popupContentSize.height - shadowPadding * 2).coerceAtLeast(0))
        val bodyOffset = bodyPosition.calculatePosition(anchorBounds, windowSize, layoutDirection, bodySize)
        return IntOffset(
            (bodyOffset.x - shadowPadding).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (bodyOffset.y - shadowPadding).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
