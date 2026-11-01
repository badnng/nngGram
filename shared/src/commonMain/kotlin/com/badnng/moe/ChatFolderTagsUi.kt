package com.badnng.moe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min

/** Positions may exist for chats outside a folder. Only actual native membership authorizes a tag. */
internal fun chatFolderTags(chat: ChatItem, snapshot: ChatSnapshot): List<ChatFolderInfo> {
    val lists = chat.chatLists ?: return emptyList()
    if (!snapshot.folderTagsEnabled) return emptyList()
    return snapshot.folders.filter { it.colorId in 0..6 && it.id != snapshot.activeList.folderId && ChatListRef(it.id) in lists }
}

// Native color_indices.style defines these seven hue families. Miuix contrast adapts their tones.
private val folderTagSeeds = listOf(Color(0xFFC55245), Color(0xFFDF8A49), Color(0xFF52B440),
    Color(0xFF45BCE7), Color(0xFF447BC4), Color(0xFF9978C8), Color(0xFFD46C99))

/** One real measured line, in native folder order, with +N replacing the last tag when needed. */
@Composable
internal fun ChatFolderTagStrip(tags: List<ChatFolderInfo>, modifier: Modifier = Modifier, selected: Boolean = false) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = chatMessageTextStyle().copy(fontSize = 10.sp, lineHeight = TextUnit.Unspecified)
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val onSurface = MiuixTheme.colorScheme.onSurface
    val primary = MiuixTheme.colorScheme.primary
    val onPrimary = MiuixTheme.colorScheme.onPrimary
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val height = measurer.measure("X", style, maxLines = 1).size.height
        val skip = with(density) { 4.dp.roundToPx() }
        val padding = measurer.measure("   ", style, maxLines = 1, softWrap = false).size.width
        val maximum = constraints.maxWidth
        fun width(text: String) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width + padding
        val entries = mutableListOf<Triple<Any, String, Int>>()
        var used = 0
        var more = 0
        for ((index, tag) in tags.withIndex()) {
            val text = tag.name.uppercase()
            val size = width(text)
            if (used + size > maximum) { more = tags.size - index; break }
            entries += Triple(tag.id, text, tag.colorId)
            used += size + skip
        }
        if (more > 0) {
            var text = "+$more"
            var size = width(text)
            if (used + size > maximum && entries.isNotEmpty()) {
                val removed = entries.removeAt(entries.lastIndex)
                used -= width(removed.second) + skip
                more++; text = "+$more"; size = width(text)
            }
            if (used + size <= maximum) entries += Triple("more", text, 4)
        }
        val measured = entries.map { (key, text, colorId) ->
            val seed = folderTagSeeds[colorId]
            val background = if (selected) onPrimary else seed.copy(alpha = .15f).compositeOver(surface)
            var foreground = if (selected) primary else seed
            fun contrast(a: Color, b: Color) = (max(a.luminance(), b.luminance()) + .05f) / (min(a.luminance(), b.luminance()) + .05f)
            var step = 0
            while (!selected && contrast(foreground, background) < 4.5f && step < 10) {
                step++; foreground = lerp(seed, onSurface, step / 10f)
            }
            val size = width(text)
            subcompose(key) {
                Box(Modifier.requiredWidth(with(density) { size.toDp() })
                    .chatSquircleBackground(background, 4.dp).semantics { contentDescription = "文件夹标签 $text" }
                    .padding(start = with(density) { (padding / 2).toDp() },
                        end = with(density) { (padding - padding / 2).toDp() }), contentAlignment = Alignment.Center) {
                    Text(text, style = style, color = foreground, maxLines = 1, softWrap = false)
                }
            }.single().measure(Constraints.fixed(size, height))
        }
        val contentWidth = measured.sumOf { it.width } + skip * (measured.size - 1).coerceAtLeast(0)
        layout(constraints.constrainWidth(contentWidth), constraints.constrainHeight(height)) {
            var x = 0
            measured.forEach { it.placeRelative(x, 0); x += it.width + skip }
        }
    }
}
