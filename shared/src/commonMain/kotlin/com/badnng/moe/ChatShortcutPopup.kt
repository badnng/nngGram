package com.badnng.moe

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider as ComposePopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupLayoutPosition
import top.yukonga.miuix.kmp.basic.PopupPositionProvider

/** Offline pixel probes only; production never exports menu geometry or account content. */
internal val LocalChatShortcutPreviewBounds = staticCompositionLocalOf<((Rect?) -> Unit)?> { null }

/** One surface and one animation layer; its shadow follows the revealed outline outside the clip. */
@Composable
internal fun Modifier.chatShortcutAnimatedSurface(
    fractionProgress: () -> Float,
    alphaProgress: () -> Float,
    popupLayoutPosition: PopupLayoutPosition,
    surfaceOpacity: Float? = null,
    shadow: Shadow = ChatShortcutDropShadow,
    shadowPadding: Dp = ChatShortcutShadowPadding,
): Modifier {
    val shape = rememberMiuixSquircleShape(15.dp)
    val context = LocalGraphicsContext.current
    val painter = remember(context, shape, shadow) { context.shadowContext.createDropShadowPainter(shape, shadow) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val observer = LocalChatShortcutPreviewBounds.current
    DisposableEffect(observer) { onDispose { observer?.invoke(null) } }
    val origin = remember(popupLayoutPosition) {
        TransformOrigin(if (popupLayoutPosition.isRightAligned) 1f else 0f,
            if (popupLayoutPosition.showBelow) 0f else if (popupLayoutPosition.showAbove) 1f else 0.5f)
    }
    return drawWithContent {
        // Refresh the parent display list as the outset layer's alpha paint changes.
        if (fractionProgress() > 0f && alphaProgress() > 0f) drawContent() else observer?.invoke(null)
    }.graphicsLayer {
        val scale = 0.15f + 0.85f * fractionProgress()
        scaleX = scale
        scaleY = scale
        alpha = alphaProgress()
        transformOrigin = origin
        chatShortcutShadowOutsets(shadowPadding)
    }.onGloballyPositioned { coordinates[0] = it }.drawWithCache {
        onDrawWithContent {
            val progress = fractionProgress().coerceIn(0f, 1f)
            val visibleHeight = size.height * progress
            if (visibleHeight <= 0f) return@onDrawWithContent
            val clipTop = when {
                popupLayoutPosition.showBelow -> 0f
                popupLayoutPosition.showAbove -> size.height - visibleHeight
                else -> (size.height - visibleHeight) / 2f
            }
            val visibleSize = Size(size.width, visibleHeight)
            val outline = shape.createOutline(visibleSize, layoutDirection, this)
            val path = when (outline) {
                is Outline.Generic -> outline.path
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            }
            translate(top = clipTop) {
                with(painter) { draw(visibleSize) }
                clipPath(path) { translate(top = -clipTop) { this@onDrawWithContent.drawContent() } }
            }
            coordinates[0]?.takeIf { it.isAttached }?.let { local ->
                val topLeft = local.localToWindow(Offset(0f, clipTop))
                val bottomRight = local.localToWindow(Offset(size.width, clipTop + visibleHeight))
                observer?.invoke(Rect(topLeft, bottomRight))
            }
        }
    }.chatGlassSurface(15.dp, shadow = false, tintAlpha = surfaceOpacity)
}

/** A button-anchored Miuix reveal with a single glass body and room for its bottom shadow. */
@Composable
internal fun ChatShortcutPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    width: Dp = 232.dp,
    verticalMargin: Dp = 20.dp,
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End,
    paneTitle: String = "快捷操作菜单",
    surfaceOpacity: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fraction = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    var mounted by remember { mutableStateOf(false) }
    val dismissFinished by rememberUpdatedState(onDismissFinished)
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
            dismissFinished()
        }
    }
    if (!show && !mounted) return
    val density = LocalDensity.current
    val viewport = LocalWindowInfo.current.containerSize
    val padding = with(density) { ChatShortcutShadowPadding.roundToPx() }
    val gap = with(density) { verticalMargin.roundToPx() }
    val preferredBelow = remember { mutableStateOf(true) }
    var availableHeight by remember { mutableStateOf<Int?>(null) }
    val rightAligned = alignment == PopupPositionProvider.Align.End || alignment == PopupPositionProvider.Align.TopEnd ||
        alignment == PopupPositionProvider.Align.BottomEnd
    val bodyPosition = remember(gap, rightAligned, padding) {
        ShortcutDropdownPosition(gap, rightAligned, padding) { below, height ->
            preferredBelow.value = below
            availableHeight = height
        }
    }
    val position = remember(bodyPosition, padding) { ChatShortcutPopupPosition(bodyPosition, padding) }
    val focus = remember { FocusRequester() }
    val maxWidth = with(density) { (viewport.width - padding * 2).coerceAtLeast(1).toDp() }
    val maxHeight = with(density) {
        minOf(viewport.height - padding * 2, availableHeight ?: Int.MAX_VALUE).coerceAtLeast(1).toDp()
    }
    Popup(popupPositionProvider = position, onDismissRequest = { if (show) onDismissRequest() },
        properties = PopupProperties(focusable = show)) {
        ChatShortcutShadowSpace(Modifier.pointerInput(show) {
            detectTapGestures { if (show) onDismissRequest() }
        }) {
            LaunchedEffect(show) { if (show) focus.requestFocus() }
            Column(Modifier.width(minOf(width, maxWidth)).heightIn(max = maxHeight)
                .chatShortcutAnimatedSurface({ fraction.value }, { alpha.value },
                    PopupLayoutPosition(preferredBelow.value, !preferredBelow.value, rightAligned), surfaceOpacity)
                .semantics { contentDescription = paneTitle; this.paneTitle = paneTitle }
                .onPreviewKeyEvent {
                    if (!show) true else if (it.key == Key.Escape) {
                        if (it.type == KeyEventType.KeyUp) onDismissRequest()
                        true
                    } else false
                }.focusRequester(focus).focusable(show).pointerInput(Unit) {
                    // The shadow space dismisses, while taps on the card itself stay inside it.
                    detectTapGestures {}
                }.pointerInput(show) {
                    if (!show) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.verticalScroll(rememberScrollState()).padding(8.dp), content = content)
        }
    }
}

private class ShortcutDropdownPosition(
    private val gap: Int,
    private val rightAligned: Boolean,
    private val shadowPadding: Int,
    private val onDirection: (Boolean, Int) -> Unit,
) : ComposePopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val belowSpace = (windowSize.height - shadowPadding - anchorBounds.bottom - gap).coerceAtLeast(1)
        val aboveSpace = (anchorBounds.top - gap - shadowPadding).coerceAtLeast(1)
        val below = when {
            belowSpace >= popupContentSize.height -> true
            aboveSpace >= popupContentSize.height -> false
            else -> belowSpace >= aboveSpace
        }
        onDirection(below, if (below) belowSpace else aboveSpace)
        val end = if (layoutDirection == LayoutDirection.Rtl) !rightAligned else rightAligned
        return IntOffset(
            if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left,
            if (below) anchorBounds.bottom + gap else anchorBounds.top - gap - popupContentSize.height,
        )
    }
}
