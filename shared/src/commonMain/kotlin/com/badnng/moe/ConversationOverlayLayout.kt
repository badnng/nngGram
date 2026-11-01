package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

// User-tuned vertical placement; these change measured layout, never only its drawing.
internal val ConversationMessageVerticalOffset = 6.dp
internal val ConversationLatestButtonVerticalOffset = 6.dp

/**
 * Measure the animated editor before the timeline so their occlusion agrees in every frame.
 * [floating] draws above the editor's progressive blur, so actions resting on that blur keep
 * their complete downward shadow; its full-size container itself does not consume input.
 */
@Composable
internal fun ConversationOverlayLayout(
    content: @Composable BoxScope.(composerHeight: Int) -> Unit,
    composer: @Composable BoxScope.() -> Unit,
    floating: @Composable BoxScope.(composerHeight: Int) -> Unit = {},
) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val overlay = subcompose("composer") {
            Box(Modifier.fillMaxWidth(), content = composer)
        }.single().measure(Constraints(maxWidth = width, maxHeight = height))
        val body = subcompose("timeline") {
            Box(Modifier.fillMaxSize()) { content(overlay.height) }
        }.single().measure(Constraints.fixed(width, height))
        val top = subcompose("floating") {
            Box(Modifier.fillMaxSize()) { floating(overlay.height) }
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            body.place(0, 0)
            overlay.place(0, height - overlay.height)
            top.place(0, 0)
        }
    }
}
