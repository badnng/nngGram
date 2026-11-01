package com.badnng.moe.media

import com.badnng.moe.chat.MediaFormat
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnimatedEmojiColorReplacementsTest {
    @Test
    fun nativeNoModifierTypesKeepTheOriginalBytes() {
        val bytes = " {\"nm\":\"原始 😭\",\"layers\":[]} \n".encodeToByteArray()
        assertSame(bytes, animatedEmojiLottieBytes(bytes, 0))
        assertSame(bytes, animatedEmojiLottieBytes(bytes, 1))
    }

    @Test
    fun eachNativeModifierSelectsItsOwnAnimationTableColumn() {
        val table = "[{\"o\":16711680,\"f12\":255,\"f3\":65280,\"f4\":65535,\"f5\":16711935,\"f6\":16776960}]"
        val source = animation(table, fill("[1,0,0,0.37]"))
        val colors = listOf(
            "[0.0,0.0,1.0,0.37]",
            "[0.0,1.0,0.0,0.37]",
            "[0.0,1.0,1.0,0.37]",
            "[1.0,0.0,1.0,0.37]",
            "[1.0,1.0,0.0,0.37]",
        )
        colors.forEachIndexed { index, color ->
            assertEquals(source.replace("[1,0,0,0.37]", color), transformed(source, index + 2))
        }
    }

    @Test
    fun staticFillKeepsAlphaOpacityNamesGeometryAndTableVerbatim() {
        val shape = """{"ty":"fl","nm":"肤色 \"a\"","c":{"a":0,"k":[1e0,0.0,0,0.52]},"o":{"a":0,"k":75},"p":[1,0,0,0.52]}"""
        val source = animation("[{\"o\":16711680,\"f3\":4227327}]", shape)
        assertEquals(source.replace("[1e0,0.0,0,0.52]", "[0.2509804,0.5019608,1.0,0.52]"), transformed(source, 3))
    }

    @Test
    fun animatedStrokeReplacesStartAndEndValuesWithoutChangingEasing() {
        val shape = """{"ty":"st","c":{"a":1,"k":[{"t":0,"s":[1,0,0,0.4],"e":[0,1,0,0.6],"i":{"x":[1,0,0,0.4]}},{"t":30,"s":[0,1,0,0.6]}]},"w":{"a":0,"k":2}}"""
        val source = animation("[{\"o\":16711680,\"f3\":255},{\"o\":65280,\"f3\":16711935}]", shape)
        val expected = source
            .replace("\"s\":[1,0,0,0.4]", "\"s\":[0.0,0.0,1.0,0.4]")
            .replace("\"e\":[0,1,0,0.6]", "\"e\":[1.0,0.0,1.0,0.6]")
            .replace("\"s\":[0,1,0,0.6]", "\"s\":[1.0,0.0,1.0,0.6]")
        assertEquals(expected, transformed(source, 3))
    }

    @Test
    fun roundedClampedFloatColorsMatchTheOfficialRgbKey() {
        val source = animation("[{\"o\":16711680,\"f3\":255}]", fill("[1.0001,-0.1,0.001,1]"))
        assertEquals(source.replace("[1.0001,-0.1,0.001,1]", "[0.0,0.0,1.0,1]"), transformed(source, 3))
    }

    @Test
    fun halfChannelRoundingUsesPositiveHalfUp() {
        // float(0.5 / 255) * 255 = 0.5: Rust rounds to 1 while banker's rounding yields 0.
        val source = animation("[{\"o\":65536,\"f3\":255}]", fill("[0.00196078431372549,0,0,1]"))
        assertEquals(source.replace("[0.00196078431372549,0,0,1]", "[0.0,0.0,1.0,1]"), transformed(source, 3))
    }

    @Test
    fun missingAndZeroTargetsDoNotFallThroughToLaterDuplicateEntries() {
        val sources = listOf(
            animation("[{\"o\":16711680},{\"o\":16711680,\"f3\":255}]", fill("[1,0,0,1]")),
            animation("[{\"o\":16711680,\"f3\":0},{\"o\":16711680,\"f3\":255}]", fill("[1,0,0,1]")),
        )
        sources.forEach { source ->
            val bytes = source.encodeToByteArray()
            assertSame(bytes, animatedEmojiLottieBytes(bytes, 3))
        }
    }

    @Test
    fun absentTablesAndUnmatchedSourceColorsKeepTheOriginalBytes() {
        val sources = listOf(
            "{\"layers\":[{\"ty\":4,\"shapes\":[${fill("[1,0,0,1]")}]}]}",
            animation("[{\"o\":65280,\"f3\":255}]", fill("[1,0,0,1]")),
        )
        sources.forEach { source ->
            val bytes = source.encodeToByteArray()
            assertSame(bytes, animatedEmojiLottieBytes(bytes, 3))
        }
    }

    @Test
    fun nonzeroEncodedBlackTargetKeepsTheAuthoredAlpha() {
        val source = animation("[{\"o\":16711680,\"f3\":4278190080}]", fill("[1,0,0,0.42]"))
        assertEquals(source.replace("[1,0,0,0.42]", "[0.0,0.0,0.0,0.42]"), transformed(source, 3))
    }

    @Test
    fun tableFieldsUseLastValueButRepeatedSourceEntriesUseFirstValue() {
        val table = "[{\"o\":65280,\"o\":16711680,\"f3\":65280,\"f3\":255},{\"o\":16711680,\"f3\":65280}]"
        val source = animation(table, fill("[1,0,0,1]"))
        assertEquals(source.replace("[1,0,0,1]", "[0.0,0.0,1.0,1]"), transformed(source, 3))
    }

    @Test
    fun replacementsAreNotCascaded() {
        val source = animation("[{\"o\":16711680,\"f3\":65280},{\"o\":65280,\"f3\":255}]", fill("[1,0,0,1]"))
        assertEquals(source.replace("[1,0,0,1]", "[0.0,1.0,0.0,1]"), transformed(source, 3))
    }

    @Test
    fun nestedGroupsAndAssetSolidLayersUseTheSameTable() {
        val group = "{\"ty\":\"gr\",\"it\":[${fill("[1,0,0,1]")}]}"
        val source = animation("[{\"o\":16711680,\"f3\":255}]", group)
            .dropLast(1) + ",\"assets\":[{\"id\":\"precomp\",\"layers\":[{\"ty\":1,\"sc\":\"#FF000080\",\"sw\":32,\"sh\":32},{\"ty\":4,\"shapes\":[${fill("[1,0,0,1]")}]}]}]}"
        val expected = source.replace("[1,0,0,1]", "[0.0,0.0,1.0,1]").replace("#FF000080", "#0000ff80")
        assertEquals(expected, transformed(source, 3))
    }

    @Test
    fun gradientsAndObjectsOutsideShapeLayersAreUntouched() {
        val gradient = """{"ty":"gf","g":{"p":1,"k":{"a":0,"k":[0,1,0,0,0.5,0.2]}},"c":{"a":0,"k":[1,0,0,1]}}"""
        val source = animation("[{\"o\":16711680,\"f3\":255}]", gradient)
            .dropLast(1) + ",\"extra\":${fill("[1,0,0,1]")}}"
        val bytes = source.encodeToByteArray()
        assertSame(bytes, animatedEmojiLottieBytes(bytes, 3))
    }

    @Test
    fun escapedJsonKeysStayUnmatchedAndUtf8BomSurvives() {
        val source = "\uFEFF" + animation("[{\"o\":16711680,\"f3\":255}]", fill("[1,0,0,1]"))
        assertEquals(source.replace("[1,0,0,1]", "[0.0,0.0,1.0,1]"), transformed(source, 3))
        val escaped = source.replace("\"c\"", "\"\\u0063\"").encodeToByteArray()
        assertSame(escaped, animatedEmojiLottieBytes(escaped, 3), "The official parser compares raw keys without decoding escapes.")
    }

    @Test
    fun syntheticTgsDecoderRendersTheSelectedSkinColorInActualPixels() {
        val file = Files.createTempFile("synthetic-emoji-fitz-", ".tgs")
        try {
            val json = """{"v":"5.5.2","fr":30,"ip":0,"op":30,"w":16,"h":16,"ddd":0,"fitz":[{"o":16711680,"f12":255}],"assets":[],"layers":[{"ddd":0,"ind":1,"ty":4,"nm":"synthetic","sr":1,"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},"ao":0,"shapes":[{"ty":"rc","d":1,"s":{"a":0,"k":[16,16]},"p":{"a":0,"k":[8,8]},"r":{"a":0,"k":0}},${fill("[1,0,0,1]")}],"ip":0,"op":30,"st":0,"bm":0}]}"""
            GZIPOutputStream(Files.newOutputStream(file)).use { it.write(json.encodeToByteArray()) }
            fun renderedCenter(type: Int): Int = MediaDecoder.decode(file.toString(), MediaFormat.TGS, fitzpatrickType = type).use { media ->
                Surface.makeRasterN32Premul(16, 16).use { surface ->
                    surface.canvas.clear(0)
                    media.draw(surface.canvas, Rect.makeWH(16f, 16f), 0)
                    surface.makeImageSnapshot().use { image ->
                        Bitmap.makeFromImage(image).use { bitmap -> bitmap.getColor(8, 8) }
                    }
                }
            }
            assertEquals(0xffff0000.toInt(), renderedCenter(0), "The native base asset should retain red without a modifier.")
            assertEquals(0xff0000ff.toInt(), renderedCenter(2), "The decoder must apply f12 before Skottie renders the real fill.")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun malformedAndOversizedInputsFailWithinTheDecoderBoundary() {
        listOf("{\"fitz\":[}", "{\"fitz\":[],}", "{\"fitz\":{}}", "{\"fitz\":[{\"o\":null}]}", "{\"a\":01}", "{\"a\":\"\\u+123\"}", "{} true").forEach { source ->
            assertFailsWith<IllegalArgumentException> { transformed(source, 3) }
        }
        assertFailsWith<IllegalArgumentException> { animatedEmojiLottieBytes(ByteArray(2 * 1024 * 1024 + 1), 3) }
        assertFailsWith<IllegalArgumentException> { transformed("{}", 7) }
        assertFailsWith<IllegalArgumentException> { transformed("[".repeat(130) + "0" + "]".repeat(130), 3) }
        assertFailsWith<IllegalArgumentException> { transformed("{\"ignored\":[" + "0,".repeat(120_000) + "0]}", 3) }
        assertFailsWith<Exception> { animatedEmojiLottieBytes(byteArrayOf(0xc3.toByte(), 0x28), 3) }
        assertTrue(transformed("{\"fitz\":[],\"layers\":[]}", 3).contains("\"layers\":[]"))
    }

    private fun fill(color: String) = "{\"ty\":\"fl\",\"c\":{\"a\":0,\"k\":$color},\"o\":{\"a\":0,\"k\":100}}"

    private fun animation(table: String, shapes: String) =
        "{\"fr\":30,\"ip\":0,\"op\":30,\"w\":16,\"h\":16,\"fitz\":$table,\"layers\":[{\"ty\":4,\"shapes\":[$shapes]}]}"

    private fun transformed(source: String, type: Int) =
        animatedEmojiLottieBytes(source.encodeToByteArray(), type).decodeToString()
}
