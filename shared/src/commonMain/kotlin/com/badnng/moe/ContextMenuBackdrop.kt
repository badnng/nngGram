package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/** Transient UI ownership only. Closing menus retain ownership through their exit frames. */
@Stable
internal class ContextMenuBackdropState {
    private data class Owner(val expanded: Boolean, val blurBackground: Boolean)

    private val owners = mutableStateMapOf<Any, Owner>()
    private val clearSources = mutableStateMapOf<Any, MessageMenuPreview>()
    private var clearSourceHosts by mutableIntStateOf(0)

    val isExpanded: Boolean get() = owners.values.any { it.expanded }
    val hasExpandedBlurOwners: Boolean get() = owners.values.any { it.expanded && it.blurBackground }
    val hasMountedMenus: Boolean get() = owners.isNotEmpty()
    internal val hasClearSourceHost: Boolean get() = clearSourceHosts > 0
    internal val clearSourcePreviews: Collection<MessageMenuPreview> get() = clearSources.values

    /** A closing source must not wait forever for a different expanded menu's blur. */
    internal fun hasExpandedBlurOwnerOtherThan(owner: Any): Boolean =
        owners.any { (key, value) -> key != owner && value.expanded && value.blurBackground }

    internal fun update(owner: Any, expanded: Boolean, blurBackground: Boolean = true) {
        owners[owner] = Owner(expanded, blurBackground)
    }
    internal fun remove(owner: Any) { owners.remove(owner) }
    internal fun attachClearSourceHost() { clearSourceHosts++ }
    internal fun detachClearSourceHost() { clearSourceHosts = (clearSourceHosts - 1).coerceAtLeast(0) }
    internal fun updateClearSource(owner: Any, source: MessageMenuPreview) { clearSources[owner] = source }
    internal fun removeClearSource(owner: Any) { clearSources.remove(owner) }
}

internal val LocalContextMenuBackdrop = staticCompositionLocalOf<ContextMenuBackdropState?> { null }

/** The workspace's actual animated blur value, read lazily without recomposing on each frame. */
internal val LocalContextMenuBlurProgress = staticCompositionLocalOf<() -> Float> { { 0f } }

/** Call only while the popup is mounted, including its retained closing content. */
@Composable
internal fun ContextMenuBackdropRegistration(expanded: Boolean, blurBackground: Boolean = true,
    owner: Any = remember { Any() }) {
    val state = LocalContextMenuBackdrop.current
    SideEffect { state?.update(owner, expanded, blurBackground) }
    DisposableEffect(state, owner) { onDispose { state?.remove(owner) } }
}

/** A visual tail is deliberately separate from input, focus and mounted-menu ownership. */
@Composable
internal fun ContextMenuClearSourceRegistration(owner: Any, source: MessageMenuPreview?) {
    val state = LocalContextMenuBackdrop.current
    SideEffect {
        if (source != null && state?.hasClearSourceHost == true) state.updateClearSource(owner, source)
        else state?.removeClearSource(owner)
    }
    DisposableEffect(state, owner) { onDispose { state?.removeClearSource(owner) } }
}

/** Draw above workspace blur in its own tree, without a native Popup hit rectangle. */
@Composable
internal fun MessageMenuClearSourceOverlay(modifier: Modifier = Modifier) {
    val state = LocalContextMenuBackdrop.current ?: return
    val origin = remember { arrayOf(Offset.Zero) }
    DisposableEffect(state) {
        state.attachClearSourceHost()
        onDispose { state.detachClearSourceHost() }
    }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { origin[0] = it.positionInWindow() }) {
        val offset = origin[0]
        state.clearSourcePreviews.forEach { source ->
            val visible = source.visibleBounds
            clipRect(visible.left - offset.x, visible.top - offset.y,
                visible.right - offset.x, visible.bottom - offset.y) {
                translate(source.bounds.left - offset.x, source.bounds.top - offset.y) {
                    drawLayer(source.layer)
                }
            }
        }
    }
}
