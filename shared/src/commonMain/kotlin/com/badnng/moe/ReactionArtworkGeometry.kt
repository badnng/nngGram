package com.badnng.moe

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ReactionKey

// Telegram Desktop a7f4536: chat.style distinguishes the 18 dp layout slot
// from the 32 dp reaction artwork canvas. Transparent native margins are part
// of the latter; fitting that whole canvas into the slot shrinks the glyph.
internal val reactionInlineSlotEdge = 18.dp
internal val reactionInlineImageEdge = 32.dp
internal val reactionInlineAroundEdge = reactionInlineImageEdge * 2

/** ReactionFlyAnimation::paintCenterFrame, including the native select fallback. */
internal fun reactionAdditionArtworkEdge(key: ReactionKey, hasCenterAnimation: Boolean, around: Boolean): Dp = when {
    around -> reactionInlineAroundEdge
    // FrameSizeFromTag(Normal) -> AdjustCustomEmojiSize(18) = round(18 * 1.12).
    key.customEmojiId != 0L -> 20.dp
    hasCenterAnimation -> reactionInlineImageEdge
    else -> reactionInlineImageEdge / 2
}
