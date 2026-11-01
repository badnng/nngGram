package com.badnng.moe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MessageForwardInfo
import com.badnng.moe.chat.forwardedHeaderText
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Matches the official service-colour header, rather than rendering a forward as a reply card. */
@Composable
internal fun ForwardedMessageHeader(
    info: MessageForwardInfo,
    snapshot: ChatSnapshot,
    modifier: Modifier = Modifier,
) {
    Text(
        text = snapshot.forwardedHeaderText(info),
        modifier = modifier,
        color = MiuixTheme.colorScheme.primary,
        style = chatMessageTextStyle().copy(fontWeight = FontWeight.SemiBold),
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
