/*
 * QR Code generator, adapted from Project Nayuki's QR Code generator.
 * Copyright (c) Project Nayuki. (MIT License)
 * https://www.nayuki.io/page/qr-code-generator-library
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions: The above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of
 * the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
 * OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.badnng.moe

import kotlin.math.abs

/** Bounded byte-mode QR Model 2, Quartile ECC, versions 1..40. No token is retained. */
internal class LoginQrMatrix private constructor(val size: Int, private val modules: Array<BooleanArray>) {
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]
    override fun toString(): String = "LoginQrMatrix(size=$size, <redacted>)"

    companion object {
        fun encode(payload: String): LoginQrMatrix? {
            val bytes = payload.encodeToByteArray()
            if (bytes.isEmpty() || bytes.size > 1663) return null
            val version = (1..40).firstOrNull {
                4 + (if (it < 10) 8 else 16) + bytes.size * 8 <= dataCodewords(it) * 8
            } ?: return null
            val bits = mutableListOf<Boolean>()
            fun append(value: Int, length: Int) {
                for (i in length - 1 downTo 0) bits += value ushr i and 1 != 0
            }
            append(4, 4)
            append(bytes.size, if (version < 10) 8 else 16)
            bytes.forEach { append(it.toInt() and 255, 8) }
            val capacity = dataCodewords(version) * 8
            append(0, minOf(4, capacity - bits.size))
            while (bits.size % 8 != 0) bits += false
            var pad = 0xEC
            while (bits.size < capacity) { append(pad, 8); pad = pad xor 0xFD }
            val data = ByteArray(bits.size / 8)
            bits.forEachIndexed { index, value -> if (value) data[index / 8] = (data[index / 8].toInt() or (1 shl (7 - index % 8))).toByte() }
            val builder = Builder(version)
            builder.functionPatterns()
            builder.codewords(interleave(data, version))
            // A fixed standards-compliant mask keeps encoding bounded and deterministic.
            builder.mask()
            builder.format()
            data.fill(0)
            bytes.fill(0)
            return LoginQrMatrix(builder.size, builder.modules)
        }

        private val ecc = intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30,
            24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
        private val blocks = intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12,
            17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68)
        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }
        private fun dataCodewords(version: Int) = rawModules(version) / 8 - ecc[version] * blocks[version]
        private fun multiply(a: Int, b: Int): Int {
            var result = 0
            for (i in 7 downTo 0) {
                result = (result shl 1) xor ((result ushr 7) * 0x11D)
                result = result xor ((b ushr i and 1) * a)
            }
            return result
        }
        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 2)
            }
            return result
        }
        private fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (value in data) {
                val factor = (value.toInt() and 255) xor result[0]
                result.copyInto(result, 0, 1)
                result[result.lastIndex] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
        private fun interleave(data: ByteArray, version: Int): ByteArray {
            val count = blocks[version]
            val raw = rawModules(version) / 8
            val shortCount = count - raw % count
            val shortLength = raw / count
            val divisor = divisor(ecc[version])
            var offset = 0
            val chunks = (0 until count).map { index ->
                val length = shortLength - ecc[version] + if (index < shortCount) 0 else 1
                val chunk = data.copyOfRange(offset, offset + length)
                offset += length
                val correction = remainder(chunk, divisor)
                val padded = chunk.copyOf(shortLength + 1)
                correction.copyInto(padded, padded.size - correction.size)
                chunk.fill(0)
                correction.fill(0)
                padded
            }
            val result = ByteArray(raw)
            var index = 0
            for (i in 0..shortLength) for (j in 0 until count) {
                if (i != shortLength - ecc[version] || j >= shortCount) result[index++] = chunks[j][i]
            }
            chunks.forEach { it.fill(0) }
            return result
        }
    }

    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val function = Array(size) { BooleanArray(size) }
        private fun set(x: Int, y: Int, value: Boolean) {
            modules[y][x] = value
            function[y][x] = true
        }
        fun functionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            if (version >= 2) {
                val count = version / 7 + 2
                val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
                val positions = IntArray(count)
                positions[0] = 6
                var pos = size - 7
                for (i in count - 1 downTo 1) { positions[i] = pos; pos -= step }
                for (i in positions.indices) for (j in positions.indices) {
                    if ((i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)) continue
                    for (dy in -2..2) for (dx in -2..2) set(positions[i] + dx, positions[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
                }
            }
            format()
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bits ushr i and 1 != 0); set(b, a, bits ushr i and 1 != 0)
                }
            }
        }
        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val distance = maxOf(abs(dx), abs(dy))
                    set(x, y, distance != 2 && distance != 4)
                }
            }
        }
        fun format() {
            // Q = 3, mask = 0.
            val data = 3 shl 3
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = bits ushr i and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }
        fun codewords(data: ByteArray) {
            var index = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until size) {
                    val y = if ((right + 1) and 2 == 0) size - 1 - vertical else vertical
                    for (j in 0..1) {
                        val x = right - j
                        if (!function[y][x] && index < data.size * 8) {
                            modules[y][x] = data[index ushr 3].toInt() ushr (7 - (index and 7)) and 1 != 0
                            index++
                        }
                    }
                }
                right -= 2
            }
            check(index == data.size * 8)
            data.fill(0)
        }
        fun mask() {
            for (y in 0 until size) for (x in 0 until size) if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x]
        }
    }
}
