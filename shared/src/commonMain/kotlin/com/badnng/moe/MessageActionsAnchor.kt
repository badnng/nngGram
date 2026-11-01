package com.badnng.moe

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** Safe space for menu actions, excluding the header and the composer's complete occlusion rectangle. */
internal val LocalConversationMessageMenuViewport = staticCompositionLocalOf<Rect?> { null }

/** The original message composition remains the sole owner of media, selection and avatar state. */
internal class MessageMenuPreview(
    val layer: GraphicsLayer,
    val size: IntSize,
    val bounds: Rect,
    val visibleBounds: Rect,
    val anchorBounds: Rect,
    val viewport: Rect?,
)

/**
 * Records the existing row without moving or recomposing it. Only its display list is drawn
 * in the same native Popup as the actions, above the workspace blur. The bubble's bounds are
 * independent of the row's full-width layer so its empty alignment space cannot block a menu.
 */
@Composable
internal fun MessageActionsAnchor(
    sourceKey: Any,
    expanded: Boolean,
    pageInteractive: Boolean = true,
    anchorBounds: Rect? = null,
    placementViewport: Rect? = LocalConversationMessageMenuViewport.current,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
    menu: @Composable (MessageMenuPreview?, (Boolean) -> Unit) -> Unit,
) {
    val layer = rememberGraphicsLayer()
    // Geometry belongs to this mounted layout node. A native token change does not have
    // to resize or reposition it, so its callbacks may legitimately remain silent.
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var clippedBounds by remember { mutableStateOf(Rect.Zero) }
    var mounted by remember(sourceKey) { mutableStateOf(false) }
    // Floating glass controls constrain the action body, not the original display list.
    // Only actual ancestor/window clips may trim the source; a blur feather is not one.
    val visible = clippedBounds
    val bubble = (anchorBounds ?: bounds).intersect(visible)
    val preview = if (size.width > 0 && size.height > 0 && !visible.isEmpty && !bubble.isEmpty)
        MessageMenuPreview(layer, size, bounds, visible, bubble, placementViewport) else null
    val dismiss by rememberUpdatedState(onDismiss)
    val owned by rememberUpdatedState(expanded || mounted)
    DisposableEffect(Unit) { onDispose { if (owned) dismiss() } }
    LaunchedEffect(expanded, pageInteractive, preview == null) {
        if (expanded && (!pageInteractive || size != IntSize.Zero && preview == null)) dismiss()
    }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentPageInteractive by rememberUpdatedState(pageInteractive)
    content(Modifier.onSizeChanged { size = it }.onGloballyPositioned {
        bounds = it.boundsInWindow(clipBounds = false)
        clippedBounds = it.boundsInWindow()
    }.drawWithCache {
        onDrawWithContent {
            // Popup disposal updates mounted during applyChanges. Read ownership here
            // so the same frame restores the timeline, without a further composition.
            val lifted = (currentExpanded && currentPageInteractive || mounted) && preview != null
            if (lifted) layer.record { this@onDrawWithContent.drawContent() }
            else drawContent()
        }
    })
    key(sourceKey) { menu(preview) { mounted = it } }
}
