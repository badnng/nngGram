package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** TextField does not add trailing padding when a trailing icon is supplied. */
@Composable
internal fun SearchFieldClearButton(label: String, onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        IconButton(onClick, Modifier.size(30.dp).clip(CircleShape), minWidth = 30.dp, minHeight = 30.dp,
            cornerRadius = 15.dp, backgroundColor = Color.Transparent) {
            Icon(MiuixIcons.Regular.Close, label, Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
