package com.badnng.moe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The same centered number pill is used by chat rows, folders and the latest-message control. */
@Composable
internal fun UnreadBadge(
    count: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = LocalDesktopChatLayout.current.compact,
) {
    val colors = MiuixTheme.colorScheme
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = count?.let { "$it 条未读" } ?: "未读"
    }
    if (count == null) {
        Box(modifier.size(8.dp).background(colors.primary, CircleShape).then(semantics))
    } else {
        val height = if (compact) 18.dp else 22.dp
        val fontSize = if (compact) 10.sp else 11.sp
        Box(modifier.height(height).widthIn(min = height).background(colors.primary, CircleShape)
            .then(semantics).padding(horizontal = if (compact) 5.dp else 6.dp), contentAlignment = Alignment.Center) {
            // Animate actual labels, never invented intermediate unread counts. Keeping the capped
            // label as the target also makes native updates above 999 completely stationary.
            AnimatedContent(if (count > 999) "999+" else count.toString(),
                modifier = Modifier.height(height), contentAlignment = Alignment.Center,
                label = "unread count", transitionSpec = {
                    val direction = if ((targetState.toIntOrNull() ?: 1_000) >
                        (initialState.toIntOrNull() ?: 1_000)) 1 else -1
                    (fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(180, easing = FastOutSlowInEasing)) { direction * it / 4 }) togetherWith
                        (fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                            slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { -direction * it / 4 }) using
                        SizeTransform { _, _ -> tween(180, easing = FastOutSlowInEasing) }
                }) { label ->
                // Each AnimatedVisibility child fills the stable badge height. AnimatedContent's
                // outer alignment alone does not center text inside that constrained child.
                Box(Modifier.height(height), contentAlignment = Alignment.Center) {
                    Text(label,
                        style = MiuixTheme.textStyles.main.copy(fontSize = fontSize, lineHeight = fontSize,
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                            fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                        fontWeight = FontWeight.SemiBold, color = colors.onPrimary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
