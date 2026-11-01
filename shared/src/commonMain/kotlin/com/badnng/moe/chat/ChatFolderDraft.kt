package com.badnng.moe.chat

/** Preserve only identities with an unambiguous unchanged location in a minimal text replacement. */
fun ChatFolder.withNameText(value: String): ChatFolder = withNameReplacement(value, null)

/** A verified selection/caret edit distinguishes identical plain and custom emoji glyphs. */
fun ChatFolder.withNameText(value: String, selectionStart: Int, selectionEnd: Int,
    nextSelectionStart: Int, nextSelectionEnd: Int): ChatFolder {
    if (value == name) return this
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    val delta = value.length - name.length
    val candidates = if (start != end) listOf(start to end) else if (delta >= 0) listOf(start to start)
        else listOf(start + delta to start, start to start - delta)
    val actual = candidates.distinct().filter { (left, right) ->
        val inserted = value.length - (name.length - (right - left))
        left >= 0 && right in left..name.length && inserted >= 0 && left + inserted <= value.length &&
            nextSelectionStart == nextSelectionEnd && nextSelectionStart == left + inserted &&
            name.isUtf16Boundary(left) && name.isUtf16Boundary(right) && value.isUtf16Boundary(left) && value.isUtf16Boundary(left + inserted) &&
            name.regionMatches(0, value, 0, left) && name.regionMatches(right, value, left + inserted, name.length - right)
    }.singleOrNull()
    return withNameReplacement(value, actual)
}

private fun ChatFolder.withNameReplacement(value: String, actual: Pair<Int, Int>?): ChatFolder {
    if (value == name) return this
    // Folder names are at most twelve native characters. A temporarily oversized paste
    // stays editable, but never creates an unbounded ambiguity search or grants identities.
    if (name.length > 256 || value.length > 256) return copy(name = value, nameEntities = emptyList())
    var prefix = 0
    while (prefix < minOf(name.length, value.length) && name[prefix] == value[prefix]) prefix++
    while (!name.isUtf16Boundary(prefix) || !value.isUtf16Boundary(prefix)) prefix--
    var suffix = 0
    while (suffix < minOf(name.length, value.length) && name[name.lastIndex - suffix] == value[value.lastIndex - suffix]) suffix++
    while (!name.isUtf16Boundary(name.length - suffix) || !value.isUtf16Boundary(value.length - suffix)) suffix--
    val kept = minOf(name.length, value.length, prefix + suffix)
    val replacements = actual?.let { listOf(it) } ?: (maxOf(0, kept - suffix)..minOf(prefix, kept)).mapNotNull { left ->
        val right = name.length - (kept - left)
        val nextRight = value.length - (kept - left)
        (left to right).takeIf { name.isUtf16Boundary(left) && name.isUtf16Boundary(right) && value.isUtf16Boundary(nextRight) }
    }
    val delta = value.length - name.length
    val retained = RichMessageText(name, nameEntities).validEntities().mapNotNull { entity ->
        if (entity.kind != MessageEntityKind.CUSTOM_EMOJI) return@mapNotNull null
        val mappings = replacements.map { (left, right) ->
            when {
                entity.end <= left -> entity
                entity.offset >= right -> entity.copy(offset = entity.offset + delta)
                else -> null
            }
        }
        mappings.firstOrNull()?.takeIf { mapped -> mappings.all { it == mapped } }
    }
    return copy(name = value, nameEntities = retained)
}
