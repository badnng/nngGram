package com.badnng.moe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.assertNotNull

/** Geometry only: no semantics action, model write, or production test observer. */
internal data class CacheConfirmationControl(val full: Rect, val clipped: Rect, val enabled: Boolean,
    val scrolls: List<CacheConfirmationScroll>)

internal data class CacheConfirmationScroll(val full: Rect, val clipped: Rect, val value: Float, val maximum: Float)

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal fun ComposePixelScene.cacheConfirmationControls(): List<CacheConfirmationControl> {
    var result = emptyList<CacheConfirmationControl>()
    val read = {
        val field = ComposePixelScene::class.java.getDeclaredField("scene")
        check(field.type == ImageComposeScene::class.java && field.trySetAccessible())
        val scene = field.get(this) as ImageComposeScene
        fun full(node: SemanticsNode): Rect {
            val position = node.positionInRoot
            val size = node.size
            return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
        }
        result = buildList {
            fun visit(node: SemanticsNode, parents: List<CacheConfirmationScroll>) {
                val config = node.config
                if (config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "确认清理所选缓存" } &&
                    config.getOrNull(SemanticsActions.OnClick)?.action != null) {
                    add(CacheConfirmationControl(full(node), node.boundsInRoot,
                        config.getOrNull(SemanticsProperties.Disabled) == null, parents))
                }
                val axis = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val children = if (axis == null) parents else parents +
                    CacheConfirmationScroll(full(node), node.boundsInRoot, axis.value(), axis.maxValue())
                node.children.forEach { visit(it, children) }
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode, emptyList()) }
        }
    }
    if (SwingUtilities.isEventDispatchThread()) read() else SwingUtilities.invokeAndWait(read)
    return result
}

private fun cacheIntersection(a: Rect, b: Rect): Rect? {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
}

private fun CacheConfirmationControl.isFullyVisible(viewport: Rect): Boolean =
    full.width > 0f && full.height > 0f && full.left >= viewport.left && full.top >= viewport.top &&
        full.right <= viewport.right && full.bottom <= viewport.bottom &&
        abs(full.left - clipped.left) <= .75f && abs(full.top - clipped.top) <= .75f &&
        abs(full.right - clipped.right) <= .75f && abs(full.bottom - clipped.bottom) <= .75f

/** Scroll the actually clipped ancestor under the pointer, then remeasure after settling. */
internal fun ComposePixelScene.revealCacheConfirmation(width: Float, height: Float, topInset: Float,
    density: Float): CacheConfirmationControl {
    val viewport = Rect(0f, topInset, width, height)
    var lastIndex = -1
    var lastDirection = 0
    var step = 7f * density
    val recentSteps = ArrayDeque<String>(16)
    repeat(300) {
        val target = assertNotNull(cacheConfirmationControls().singleOrNull(),
            "The actual confirmation must exist exactly once: ${cacheConfirmationControls()}")
        if (target.isFullyVisible(viewport)) {
            settledFrame()
            val settled = cacheConfirmationControls().singleOrNull()
            if (settled != null && settled.isFullyVisible(viewport)) return settled
            return@repeat
        }
        val scrolls = target.scrolls
        check(scrolls.isNotEmpty()) { "No actual scroll ancestor for confirmation: $target" }
        var selectedIndex = -1
        var direction = 0
        var blockingChild: Rect? = null

        // An inline test host can put the inner dialog scroller mostly outside the
        // outer page. Expose that scroller before trying to wheel inside it.
        for (index in 1 until scrolls.size) {
            val child = scrolls[index]
            val visible = cacheIntersection(child.clipped, viewport)
            val parentView = cacheIntersection(scrolls[index - 1].clipped, viewport) ?: viewport
            val required = minOf(child.full.height, parentView.height)
            if (visible == null || visible.height + .75f < required) {
                direction = if (child.full.bottom < parentView.top + required) -1 else 1
                selectedIndex = index - 1
                blockingChild = child.full
                break
            }
        }

        // Scroll an inner viewport only if it is the one clipping the target.
        if (selectedIndex < 0) {
            for (index in scrolls.indices.reversed()) {
                val scroll = scrolls[index]
                val desired = when {
                    target.full.top < scroll.full.top - .75f -> -1
                    target.full.bottom > scroll.full.bottom + .75f -> 1
                    else -> 0
                }
                if (desired != 0 && ((desired < 0 && scroll.value > .5f) ||
                        (desired > 0 && scroll.value < scroll.maximum - .5f))) {
                    selectedIndex = index
                    direction = desired
                    break
                }
            }
        }

        // Otherwise the page/window clips an already internally visible target.
        if (selectedIndex < 0) {
            direction = if (target.full.top < viewport.top || target.clipped.top > target.full.top + .75f) -1 else 1
            selectedIndex = 0
            blockingChild = scrolls.getOrNull(1)?.full
        }

        // Find a movable ancestor with some visible area; never scroll via a model setter.
        while (selectedIndex > 0 && ((direction < 0 && scrolls[selectedIndex].value <= .5f) ||
                (direction > 0 && scrolls[selectedIndex].value >= scrolls[selectedIndex].maximum - .5f) ||
                cacheIntersection(scrolls[selectedIndex].clipped, viewport) == null)) {
            blockingChild = scrolls[selectedIndex].full
            selectedIndex--
        }
        val selected = scrolls[selectedIndex]
        val visible = assertNotNull(cacheIntersection(selected.clipped, viewport),
            "The chosen scroll ancestor must actually intersect the window: $target")
        val edge = minOf(4f * density, visible.width / 4f, visible.height / 4f)
        val left = visible.left + edge
        val right = visible.right - edge
        val top = visible.top + edge
        val bottom = visible.bottom - edge
        val child = blockingChild
        val point = when {
            child == null -> Offset(visible.center.x, visible.center.y)
            left < child.left || left > child.right -> Offset(left, visible.center.y)
            right < child.left || right > child.right -> Offset(right, visible.center.y)
            top < child.top || top > child.bottom -> Offset(visible.center.x, top)
            bottom < child.top || bottom > child.bottom -> Offset(visible.center.x, bottom)
            else -> Offset(visible.center.x, visible.center.y)
        }
        if (selectedIndex == lastIndex && lastDirection != 0 && lastDirection != direction)
            step = (step / 2f).coerceAtLeast(.02f)
        else if (selectedIndex != lastIndex) step = 7f * density
        lastIndex = selectedIndex
        lastDirection = direction
        if (recentSteps.size == 16) recentSteps.removeFirst()
        recentSteps.addLast("target=$target; selected=$selectedIndex; point=$point; delta=${direction * step}")
        moveTo(point.x, point.y)
        scrollAt(point.x, point.y, deltaY = direction * step)
        settledFrame()
    }
    error("Actual pointer scrolling must expose the complete current confirmation at ${width}x$height: ${cacheConfirmationControls()}\nRecent actual pointer steps:\n${recentSteps.joinToString("\n")}")
}
