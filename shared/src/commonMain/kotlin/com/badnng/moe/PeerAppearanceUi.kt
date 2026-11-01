package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun Int.peerAccentColor(): Color = Color(0xff000000L or toLong())

/** The existing sender-name slot follows the actual sender, independently of outgoing/self ownership. */
internal fun ChatSnapshot.messageSenderNamePalette(message: ChatMessage, dark: Boolean): PeerNameAccentPalette? =
    peerAppearance(message.senderId, message.senderIsChat)?.let {
        resolvePeerNamePalette(it.name, appearanceCatalog, dark, it.gift)
    }

/** HistoryItem::contentColorsFrom: a forward uses its original author; a hidden author has no peer. */
internal fun ChatSnapshot.messageContentNamePalette(message: ChatMessage, dark: Boolean): PeerNameAccentPalette? {
    val forward = message.forwardInfo
    if (forward == null) return messageSenderNamePalette(message, dark)
    val identity = forward.origin.sourceIdentity() ?: return null
    val appearance = when (identity) {
        is ForwardSourceIdentity.User -> peerAppearance(identity.userId, false)
        is ForwardSourceIdentity.Chat -> peerAppearance(identity.chatId, true)
    } ?: return null
    return resolvePeerNamePalette(appearance.name, appearanceCatalog, dark, appearance.gift)
}

internal fun ChatSnapshot.replyNamePalette(reply: MessageReply, original: ChatMessage?, dark: Boolean): PeerNameAccentPalette? {
    if (original != null) return messageContentNamePalette(original, dark)
    val appearance = if (reply.contentAuthorKnown) when (val identity = reply.contentAuthor) {
        is ForwardSourceIdentity.User -> peerAppearance(identity.userId, false)
        is ForwardSourceIdentity.Chat -> peerAppearance(identity.chatId, true)
        null -> null
    } else peerAppearance(reply.senderId, reply.senderIsChat)
    return appearance?.let {
        resolvePeerNamePalette(it.name, appearanceCatalog, dark, it.gift)
    }
}

@Composable
internal fun peerSenderNameColor(message: ChatMessage, snapshot: ChatSnapshot): Color =
    snapshot.messageSenderNamePalette(message, MiuixTheme.colorScheme.surface.luminance() < .5f)?.nameRgb?.peerAccentColor()
        ?: MiuixTheme.colorScheme.onSurfaceVariantSummary

/** lib_ui text.cpp quote-outline tile: two/three real colors form the same diagonal stripe repeat. */
@Composable
internal fun PeerReplyOutline(palette: PeerNameAccentPalette?, fallback: Color, modifier: Modifier) {
    Canvas(modifier) {
        val colors = palette?.replyColors.orEmpty().map { it.peerAccentColor() }
        val first = colors.firstOrNull()?.let { if (palette?.selectedNameRgb != null) it.copy(alpha=.9f) else it } ?: fallback
        drawRect(first)
        if (colors.size > 1 && size.width > 0) {
            val third = colors.size == 3
            val w = size.width
            val period = w * if (third) 6 else 4
            var y = -period
            while (y < size.height) {
                fun stripe(color: Color, rightTop: Float, rightBottom: Float, leftBottom: Float, leftTop: Float) {
                    val path = Path().apply {
                        moveTo(w, y+rightTop*w); lineTo(w,y+rightBottom*w)
                        lineTo(0f,y+leftBottom*w); lineTo(0f,y+leftTop*w); close()
                    }
                    drawPath(path,color)
                }
                stripe(colors[if (third) 2 else 1],1f,if(third)4f else 3f,if(third)5f else 4f,2f)
                if (third) stripe(colors[1],3f,5f,6f,4f)
                y += period
            }
        }
    }
}
