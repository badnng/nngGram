package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import kotlin.math.roundToInt

/** Telegram Desktop TabbedPanel::kHideTimeoutMs; allows crossing from a toggle to its panel. */
internal const val ComposerPanelHideDelayMillis = 300L

/** Official defaultInnerDropdown: its outer bottom touches the attachment button's top. */
internal val ComposerAttachmentMenuShadowPadding = 10.dp
/** User's final reference asks for a slight lift above the official touching outer bounds. */
internal val ComposerAttachmentMenuAnchorGap = 4.dp
private val ComposerAttachmentMenuDropShadow =
    Shadow(radius = 5.dp, color = Color.Black.copy(alpha = .25f), offset = DpOffset(0.dp, 1.dp))

/** The same Miuix reveal/scale/alpha container as group operations, with a sampled glass body. */
@Composable
internal fun ComposerAttachmentMenu(
    show: Boolean,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onHovered: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    var retainedAnchor by remember { mutableStateOf<Rect?>(null) }
    SideEffect { if (show) retainedAnchor = anchor }
    LaunchedEffect(show) {
        if (show) {
            mounted = true
            launch { fraction.animateTo(1f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(1f, ListPopupDefaults.AlphaEnterAnimationSpec)
        } else if (mounted) {
            launch { fraction.animateTo(0f, ListPopupDefaults.FractionAnimationSpec) }
            alpha.animateTo(0f, ListPopupDefaults.AlphaExitAnimationSpec)
            fraction.snapTo(0f)
            mounted = false
            retainedAnchor = null
        }
    }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val currentHovered by rememberUpdatedState(onHovered)
    LaunchedEffect(hovered, show) { currentHovered(hovered && show) }
    DisposableEffect(Unit) { onDispose { currentHovered(false) } }
    if (!show && !mounted) return

    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val padding = with(density) { ComposerAttachmentMenuShadowPadding.roundToPx() }
    // Count the official shadow allowance once, then apply only the requested 4dp lift.
    // The complete native window stops above the button and cannot intercept it.
    val gap = padding + with(density) { ComposerAttachmentMenuAnchorGap.roundToPx() }
    val currentAnchor = anchor.takeIf { show } ?: retainedAnchor
    val preferredAbove = currentAnchor == null || currentAnchor.top >= viewport.height - currentAnchor.bottom
    val position = remember(currentAnchor, padding, gap) { ComposerAttachmentMenuPosition(currentAnchor, padding, gap) }
    val focus = remember { FocusRequester() }
    val width = with(density) { (viewport.width - padding * 2).coerceAtLeast(1).toDp().coerceAtMost(208.dp) }
    val maxHeight = with(density) { (viewport.height - padding * 2).coerceAtLeast(1).toDp() }
    Popup(popupPositionProvider = position, onDismissRequest = { if (show) onDismiss() },
        properties = composerPopupProperties(focusable = show)) {
        ChatShortcutShadowSpace(padding = ComposerAttachmentMenuShadowPadding) {
            LaunchedEffect(show) { if (show) focus.requestFocus() }
            Column(Modifier.width(width).heightIn(max = maxHeight)
                .chatShortcutAnimatedSurface({ fraction.value }, { alpha.value },
                    PopupLayoutPosition(showBelow = !preferredAbove, showAbove = preferredAbove, isRightAligned = false),
                    shadow = ComposerAttachmentMenuDropShadow, shadowPadding = ComposerAttachmentMenuShadowPadding)
                .hoverable(interactions)
                .onPreviewKeyEvent {
                    if (!show) true else if (it.key == Key.Escape) {
                        if (it.type == KeyEventType.KeyUp) onDismiss()
                        true
                    } else false
                }.focusRequester(focus).focusable(show).pointerInput(show) {
                    if (!show) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
                .then(if (show) Modifier.semantics { paneTitle = "附件菜单" } else Modifier.clearAndSetSemantics {})
                .verticalScroll(rememberScrollState()).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

/** Place the card body above the actual button, accounting for transparent shadow padding. */
internal class ComposerAttachmentMenuPosition(
    private val anchor: Rect?, private val padding: Int, private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val bounds = anchor ?: Rect(anchorBounds.left.toFloat(), anchorBounds.top.toFloat(), anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat())
        val above = bounds.top.roundToInt() - gap + padding - popupContentSize.height
        val below = bounds.bottom.roundToInt() + gap - padding
        val y = if (above >= 0 || bounds.top >= windowSize.height - bounds.bottom) above else below
        val x = if (layoutDirection == LayoutDirection.Rtl) bounds.right.roundToInt() + padding - popupContentSize.width
            else bounds.left.roundToInt() - padding
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}
