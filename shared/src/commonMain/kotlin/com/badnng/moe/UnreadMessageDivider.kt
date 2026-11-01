package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Belongs to the first unread message's item, preserving stable lazy-list message indices. */
@Composable
internal fun UnreadMessageDivider(horizontalInset: Dp) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).layout { measurable, constraints ->
        val inset = horizontalInset.roundToPx()
        val width = constraints.maxWidth + inset * 2
        val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, child.height) { child.placeRelative(-inset, 0) }
    }.background(MiuixTheme.colorScheme.secondaryContainer)
        .semantics { contentDescription = "从这里开始是未读消息" }.padding(vertical = 7.dp),
        contentAlignment = Alignment.Center) {
        Text("未读消息", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
    }
}
