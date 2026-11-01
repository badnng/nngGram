package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MessageEntity
import com.badnng.moe.chat.MessageEntityKind
import com.badnng.moe.chat.ReactionKey
import com.badnng.moe.chat.RichMessageText
import com.badnng.moe.media.ReactionMediaPreview
import top.yukonga.miuix.kmp.basic.Text

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** The message host owns visibility and its current native selection token. */
private class InlineMessageEmojiContext(
    val snapshot: ChatSnapshot,
    val features: ChatFeatureActions,
    val visible: Boolean,
    val active: Boolean,
)

private val LocalInlineMessageEmojiContext = staticCompositionLocalOf<InlineMessageEmojiContext?> { null }

@Composable
internal fun InlineMessageEmojiProvider(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    visible: Boolean,
    active: Boolean = visible,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalInlineMessageEmojiContext provides InlineMessageEmojiContext(snapshot, features, visible, active),
        content = content)
}

/**
 * lib_ui BlockParser::createBlock keeps custom emoji at their entity's UTF-16 location.
 * Resource candidates exclude hidden spoilers, while layout always reserves their native width.
 */
internal fun RichMessageText.inlineMessageEmojiEntities(revealSpoilers: Boolean = false): List<MessageEntity> {
    val entities = validEntities()
    val hidden = if (revealSpoilers) emptyList() else entities.filter { it.kind == MessageEntityKind.SPOILER }
    return inlineMessageEmojiLayoutEntities().filter { entity ->
        hidden.none { it.offset < entity.end && entity.offset < it.end }
    }
}

/** Spoiler reveal changes pixels only; it must never insert a wider emoji into the paragraph. */
private fun RichMessageText.inlineMessageEmojiLayoutEntities(): List<MessageEntity> {
    var previousEnd = -1
    return validEntities().filter { entity ->
        entity.kind == MessageEntityKind.CUSTOM_EMOJI && entity.id != 0L
    }.distinct().sortedBy { it.offset }.filter { entity ->
        // Native emoji ranges cannot overlap. Be conservative with malformed fixtures.
        (entity.offset >= previousEnd).also { if (it) previousEnd = entity.end }
    }
}

private fun MessageEntity.inlineMessageEmojiId(): String = "inline-message-emoji:$offset:$id"

/** Preserve the original text, styles and source-selection offsets, including fallback emoji text. */
internal fun AnnotatedString.withInlineMessageEmoji(
    content: RichMessageText,
    @Suppress("UNUSED_PARAMETER")
    revealSpoilers: Boolean = false,
): AnnotatedString {
    if (text != content.text) return this
    val emoji = content.inlineMessageEmojiLayoutEntities()
    if (emoji.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        emoji.forEach { addStringAnnotation(INLINE_CONTENT_TAG, it.inlineMessageEmojiId(), it.offset, it.end) }
    }.toAnnotatedString()
}

/**
 * The existing native artwork resolver validates IDs learned from current message entities.
 * Only its returned files may download. Every entity keeps the same inline geometry before
 * and after loading, visibility or focus changes. Unavailable artwork paints its source glyph.
 * The existing bounded media renderer animates only an active, visible, focused conversation.
 */
@Composable
internal fun rememberInlineMessageEmojiContent(
    content: RichMessageText,
    revealSpoilers: Boolean = false,
    fontSize: TextUnit,
    foreground: Color,
): Map<String, InlineTextContent> {
    val context = LocalInlineMessageEmojiContext.current
    val snapshot = context?.snapshot
    val emoji = content.inlineMessageEmojiEntities(revealSpoilers)
    val drawableEmoji = emoji.toSet()
    val keys = emoji.map { ReactionKey(customEmojiId = it.id) }.distinct()
    val focused = LocalWindowInfo.current.isWindowFocused
    val animate = context != null && context.visible && context.active && focused
    if (context != null && snapshot != null) {
        LaunchedEffect(snapshot.token, keys, context.visible, animate, snapshot.reactionVisuals, snapshot.reactionVisualLoading,
            snapshot.reactionVisualErrors, snapshot.mediaFiles, snapshot.downloadingFiles, snapshot.fileErrors) {
            if (!context.visible) return@LaunchedEffect
            keys.forEach { key ->
                val visual = snapshot.reactionVisuals[key]
                if (visual == null) {
                    if (key !in snapshot.reactionVisualLoading && key !in snapshot.reactionVisualErrors)
                        context.features.loadReactionVisual(snapshot.token, key)
                }
            }
        }
        keys.forEach { reaction ->
            val visual = snapshot.reactionVisuals[reaction]?.takeIf { it.key == reaction }
            listOfNotNull(visual?.staticIcon ?: visual?.selectAnimation,
                visual?.selectAnimation?.takeIf { animate }).distinctBy { it.file.id }.forEach { resource ->
                key(reaction, resource.file.id) {
                    MediaArtworkVisibility(snapshot, context.features, snapshot.mediaFiles[resource.file.id] ?: resource.file, context.visible)
                }
            }
        }
    }
    return buildMap {
        content.inlineMessageEmojiLayoutEntities().forEach { entity ->
            val canDraw = entity in drawableEmoji
            val key = ReactionKey(customEmojiId = entity.id)
            val visual = if (canDraw) snapshot?.reactionVisuals?.get(key)?.takeIf { it.key == key } else null
            val still = visual?.staticIcon ?: visual?.selectAnimation
            val stillFile = still?.file?.let { snapshot?.mediaFiles?.get(it.id) ?: it }
            val stillPath = stillFile?.localPath?.takeIf { stillFile.downloaded }
            val animation = visual?.selectAnimation
            val animationFile = animation?.file?.let { snapshot?.mediaFiles?.get(it.id) ?: it }
            val animationPath = animationFile?.localPath?.takeIf { animationFile.downloaded }
            put(entity.inlineMessageEmojiId(), InlineTextContent(
                Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter),
            ) { alternateText ->
                if (!canDraw) Box(Modifier.fillMaxSize())
                else ReactionMediaPreview(stillPath ?: animationPath, if (stillPath != null) still?.format else animation?.format,
                    animationPath, animation?.format, animate = animate, staticFirstFrame = true,
                    modifier = Modifier.fillMaxSize(), placeholder = {
                        Text(alternateText, fontSize = fontSize, color = foreground, softWrap = false, maxLines = 1)
                    })
            })
        }
    }
}
