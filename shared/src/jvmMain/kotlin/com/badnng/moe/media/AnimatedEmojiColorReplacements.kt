package com.badnng.moe.media

import kotlin.math.roundToInt

private const val ANIMATED_EMOJI_JSON_LIMIT = 2 * 1024 * 1024
private const val ANIMATED_EMOJI_JSON_NODE_LIMIT = 120_000
private const val ANIMATED_EMOJI_JSON_DEPTH_LIMIT = 128

/**
 * Applies the animation's own Fitzpatrick table to already unpacked TGS JSON on a worker.
 *
 * Telegram Desktop a7f453612f4896ac04d0007a36ab0d377e5bcbcf selects SkinModifier in
 * chat_helpers/stickers_emoji_pack.cpp::ColorReplacements. Its lib_lottie submodule
 * fbb922c30b7c30ba17ad5fd4fcb989eadd620062 maps that modifier to tlottie, pinned by
 * prepare.py to 92df98dc20. This follows tlottie's composition/parse.rs::apply_fitz:
 * first matching table entry, rounded clamped float RGB, nonzero target, fills,
 * strokes and solid layers only. Gradients and the authored alpha stay unchanged.
 *
 * Native TDLib types 0/1 have no modifier; 2..6 select f12/f3/f4/f5/f6. Only changed
 * RGB tokens are rewritten so entity names, geometry, opacity and metadata survive.
 */
internal fun animatedEmojiLottieBytes(bytes: ByteArray, fitzpatrickType: Int): ByteArray {
    require(bytes.size <= ANIMATED_EMOJI_JSON_LIMIT) { "Unsupported animation" }
    require(fitzpatrickType in 0..6) { "Unsupported emoji modifier" }
    if (fitzpatrickType <= 1) return bytes

    val source = bytes.decodeToString(throwOnInvalidSequence = true)
    val root = EmojiJsonParser(source).parse() as? EmojiJsonObject
        ?: throw IllegalArgumentException("Unsupported animation")
    val rawEntries = root.fields["fitz"] ?: return bytes
    val entries = rawEntries as? EmojiJsonArray ?: throw IllegalArgumentException("Unsupported animation")
    val targetKey = if (fitzpatrickType == 2) "f12" else "f$fitzpatrickType"
    val replacements = mutableMapOf<Long, Long>()
    entries.values.forEach { value ->
        val entry = value as? EmojiJsonObject ?: throw IllegalArgumentException("Unsupported animation")
        val original = entry.lastUnsignedColor("o")
        val replacement = entry.lastUnsignedColor(targetKey)
        replacements.putIfAbsent(original, replacement)
    }
    if (replacements.values.none { it != 0L }) return bytes

    val edits = mutableListOf<EmojiJsonEdit>()
    fun replaceColor(value: EmojiJsonValue?) {
        val color = value as? EmojiJsonArray ?: return
        if (color.values.size < 3) return
        val channels = color.values.take(3).map { it as? EmojiJsonNumber ?: return }
        val key = channels.fold(0L) { rgb, channel ->
            (rgb shl 8) or (channel.value.toFloat().coerceIn(0f, 1f) * 255f).roundToInt().toLong()
        }
        val replacement = replacements[key]?.takeIf { it != 0L } ?: return
        channels.forEachIndexed { index, channel ->
            val component = ((replacement shr (16 - index * 8)) and 0xff).toFloat() / 255f
            edits += EmojiJsonEdit(channel.start, channel.end, component.toString())
        }
    }
    fun replaceProperty(value: EmojiJsonValue?) {
        val property = value as? EmojiJsonObject ?: return
        val keyframes = property.array("k") ?: return
        if (keyframes.values.firstOrNull() is EmojiJsonObject) {
            keyframes.values.forEach { frame ->
                (frame as? EmojiJsonObject)?.let {
                    replaceColor(it.fields["s"])
                    replaceColor(it.fields["e"])
                }
            }
        } else {
            replaceColor(keyframes)
        }
    }
    fun replaceShapes(shapes: EmojiJsonArray) {
        shapes.values.forEach { value ->
            val shape = value as? EmojiJsonObject ?: return@forEach
            when (shape.string("ty")?.value) {
                "gr" -> shape.array("it")?.let { replaceShapes(it) }
                "fl", "st" -> replaceProperty(shape.fields["c"])
            }
        }
    }
    fun replaceLayers(layers: EmojiJsonArray) {
        layers.values.forEach { value ->
            val layer = value as? EmojiJsonObject ?: return@forEach
            when (layer.number("ty")?.value?.toInt()) {
                4 -> layer.array("shapes")?.let { replaceShapes(it) }
                1 -> {
                    val color = layer.string("sc") ?: return@forEach
                    val prefix = if (color.value.startsWith('#')) "#" else ""
                    val hex = color.value.removePrefix("#")
                    if (hex.length != 6 && hex.length != 8) return@forEach
                    val key = hex.take(6).toLongOrNull(16) ?: return@forEach
                    val replacement = replacements[key]?.takeIf { it != 0L } ?: return@forEach
                    val rgb = (replacement and 0xffffff).toString(16).padStart(6, '0')
                    edits += EmojiJsonEdit(color.start, color.end, "\"$prefix$rgb${hex.drop(6)}\"")
                }
            }
        }
    }
    root.array("layers")?.let { replaceLayers(it) }
    root.array("assets")?.values?.forEach { asset ->
        (asset as? EmojiJsonObject)?.array("layers")?.let { replaceLayers(it) }
    }
    if (edits.isEmpty()) return bytes

    edits.sortBy(EmojiJsonEdit::start)
    val length = source.length.toLong() + edits.sumOf { (it.text.length - (it.end - it.start)).toLong() }
    require(length <= ANIMATED_EMOJI_JSON_LIMIT) { "Unsupported animation" }
    val result = buildString(length.toInt()) {
        var position = 0
        edits.forEach { edit ->
            append(source, position, edit.start)
            append(edit.text)
            position = edit.end
        }
        append(source, position, source.length)
    }.encodeToByteArray()
    require(result.size <= ANIMATED_EMOJI_JSON_LIMIT) { "Unsupported animation" }
    return result
}

private fun EmojiJsonNumber.unsignedColor(): Long = value.coerceIn(0.0, 4_294_967_295.0).toLong()

private sealed interface EmojiJsonValue
private data class EmojiJsonObject(
    val fields: Map<String, EmojiJsonValue>,
    val repeatedFields: Map<String, EmojiJsonValue>?,
) : EmojiJsonValue {
    fun array(name: String) = fields[name] as? EmojiJsonArray
    fun string(name: String) = fields[name] as? EmojiJsonString
    fun number(name: String) = fields[name] as? EmojiJsonNumber
    fun lastUnsignedColor(name: String): Long {
        val field = repeatedFields?.get(name) ?: fields[name] ?: return 0L
        require(field is EmojiJsonNumber) { "Unsupported animation" }
        return field.unsignedColor()
    }
}
private data class EmojiJsonArray(val values: List<EmojiJsonValue>) : EmojiJsonValue
private data class EmojiJsonString(val start: Int, val end: Int, val value: String) : EmojiJsonValue
private data class EmojiJsonNumber(val start: Int, val end: Int, val value: Double) : EmojiJsonValue
private data object EmojiJsonLiteral : EmojiJsonValue
private data class EmojiJsonEdit(val start: Int, val end: Int, val text: String)

/** Small bounded JSON reader retaining source spans; it never performs a global number replacement. */
private class EmojiJsonParser(private val source: String) {
    private var position = if (source.startsWith('\uFEFF')) 1 else 0
    private var nodes = 0

    fun parse(): EmojiJsonValue {
        val result = value(0)
        whitespace()
        require(position == source.length) { "Unsupported animation" }
        return result
    }

    private fun value(depth: Int): EmojiJsonValue {
        require(depth <= ANIMATED_EMOJI_JSON_DEPTH_LIMIT && ++nodes <= ANIMATED_EMOJI_JSON_NODE_LIMIT) {
            "Unsupported animation"
        }
        whitespace()
        return when (source.getOrNull(position)) {
            '{' -> {
                position++
                val fields = mutableMapOf<String, EmojiJsonValue>()
                var repeatedFields: MutableMap<String, EmojiJsonValue>? = null
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string().value
                        whitespace()
                        expect(':')
                        val item = value(depth + 1)
                        if (fields.putIfAbsent(key, item) != null) {
                            val repeated = repeatedFields ?: mutableMapOf<String, EmojiJsonValue>().also { repeatedFields = it }
                            repeated[key] = item
                        }
                        whitespace()
                    } while (take(','))
                    expect('}')
                }
                EmojiJsonObject(fields, repeatedFields)
            }
            '[' -> {
                position++
                val values = mutableListOf<EmojiJsonValue>()
                whitespace()
                if (!take(']')) {
                    do {
                        values += value(depth + 1)
                        whitespace()
                    } while (take(','))
                    expect(']')
                }
                EmojiJsonArray(values)
            }
            '"' -> string()
            '-', in '0'..'9' -> number()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> throw IllegalArgumentException("Unsupported animation")
        }
    }

    private fun string(): EmojiJsonString {
        val start = position
        expect('"')
        while (position < source.length) {
            val char = source[position++]
            when {
                // tlottie Cursor::read_string_bytes validates escapes but deliberately
                // compares raw keys/enum strings, so an escaped key is never a known key.
                char == '"' -> return EmojiJsonString(start, position, source.substring(start + 1, position - 1))
                char == '\\' -> {
                    when (source.getOrNull(position++)) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            require(position + 4 <= source.length) { "Unsupported animation" }
                            val digits = source.substring(position, position + 4)
                            require(digits.all { it.digitToIntOrNull(16) != null }) { "Unsupported animation" }
                            position += 4
                        }
                        else -> throw IllegalArgumentException("Unsupported animation")
                    }
                }
                char < ' ' -> throw IllegalArgumentException("Unsupported animation")
            }
        }
        throw IllegalArgumentException("Unsupported animation")
    }

    private fun number(): EmojiJsonNumber {
        val start = position
        take('-')
        if (!take('0')) {
            require(source.getOrNull(position) in '1'..'9') { "Unsupported animation" }
            while (source.getOrNull(position) in '0'..'9') position++
        }
        if (take('.')) {
            require(source.getOrNull(position) in '0'..'9') { "Unsupported animation" }
            while (source.getOrNull(position) in '0'..'9') position++
        }
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            require(source.getOrNull(position) in '0'..'9') { "Unsupported animation" }
            while (source.getOrNull(position) in '0'..'9') position++
        }
        val number = source.substring(start, position).toDoubleOrNull()
        require(number != null && number.isFinite()) { "Unsupported animation" }
        return EmojiJsonNumber(start, position, number)
    }

    private fun literal(text: String): EmojiJsonValue {
        require(source.startsWith(text, position)) { "Unsupported animation" }
        position += text.length
        return EmojiJsonLiteral
    }

    private fun whitespace() {
        while (position < source.length && when (source[position]) {
                ' ', '\t', '\n', '\r' -> true
                else -> false
            }
        ) position++
    }

    private fun take(char: Char): Boolean = (source.getOrNull(position) == char).also { if (it) position++ }

    private fun expect(char: Char) {
        require(take(char)) { "Unsupported animation" }
    }
}
