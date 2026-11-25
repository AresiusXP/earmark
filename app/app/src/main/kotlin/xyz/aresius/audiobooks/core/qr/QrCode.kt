package xyz.aresius.audiobooks.core.qr

/**
 * A QR code (ISO 18004), small on purpose: byte mode, error correction level M (15%), versions 1-10 (up to 213 bytes), which is far more
 * than the 26-character codes of this app need (version 2). Written from the standard's algorithms (finder, timing and alignment
 * patterns, Reed-Solomon over GF(256), the 8 masks with the penalty rules); QrCodeTest decodes the output with ZXing.
 */
class QrCode private constructor(val version: Int, val size: Int, private val dark: BooleanArray) {
    /** True for a dark module; false outside the symbol (the quiet zone is the caller's business: at least 4 modules). */
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    companion object {
        const val MAX_VERSION = 10
        private const val FORMAT_M = 0
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        /** Largest text (UTF-8 bytes) one symbol of [MAX_VERSION] holds. */
        val MAX_BYTES: Int = (dataCodewords(MAX_VERSION) * 8 - 4 - 16) / 8

        fun encode(text: String): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (version in 1..MAX_VERSION) {
                val countBits = if (version < 10) 8 else 16
                if (4 + countBits + bytes.size * 8 <= dataCodewords(version) * 8) return Builder(version, bytes, countBits).build()
            }
            throw IllegalArgumentException("Text too long for a QR code: ${bytes.size} bytes, at most $MAX_BYTES")
        }

        private fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = version * 4 + 10
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val count = version / 7 + 2
                result -= (25 * count - 10) * count - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int): Int = rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * BLOCKS[version - 1]

        private fun bit(value: Int, i: Int) = (value ushr i) and 1 != 0
    }

    private class Builder(val version: Int, bytes: ByteArray, countBits: Int) {
        val size = version * 4 + 17
        val dark = BooleanArray(size * size)
        val function = BooleanArray(size * size)
        val codewords: ByteArray

        init {
            val bits = ArrayList<Boolean>()
            fun append(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add(bit(value, i)) }
            append(0b0100, 4)
            append(bytes.size, countBits)
            bytes.forEach { append(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            repeat(minOf(4, capacity - bits.size)) { bits.add(false) }
            while (bits.size % 8 != 0) bits.add(false)
            val data = ByteArray(capacity / 8)
            for (i in bits.indices) if (bits[i]) data[i / 8] = (data[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            var pad = 0xEC
            for (i in bits.size / 8 until data.size) {
                data[i] = pad.toByte()
                pad = pad xor (0xEC xor 0x11)
            }
            codewords = addEccAndInterleave(data)
        }

        fun build(): QrCode {
            drawFunctionPatterns()
            drawCodewords()
            var best = 0
            var bestScore = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormat(mask)
                val score = penalty()
                if (score < bestScore) {
                    bestScore = score
                    best = mask
                }
                applyMask(mask) // XOR again: undo
            }
            applyMask(best)
            drawFormat(best)
            return QrCode(version, size, dark)
        }

        private fun set(x: Int, y: Int, value: Boolean) {
            dark[y * size + x] = value
            function[y * size + x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val pos = alignmentPositions(version)
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.lastIndex) || (i == pos.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(0) // reserves the area; the real bits follow once the mask is known
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit(bits, i))
                    set(b, a, bit(bits, i))
                }
            }
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val dist = maxOf(Math.abs(dx), Math.abs(dy))
                    set(x, y, dist != 2 && dist != 4)
                }
            }
        }

        private fun drawFormat(mask: Int) {
            val data = (FORMAT_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6))
            set(8, 8, bit(bits, 7))
            set(7, 8, bit(bits, 8))
            for (i in 9..14) set(14 - i, 8, bit(bits, i))
            for (i in 0..7) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        private fun drawCodewords() {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y * size + x] && i < codewords.size * 8) {
                        dark[y * size + x] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !function[y * size + x]) dark[y * size + x] = !dark[y * size + x]
            }
        }

        private fun penalty(): Int {
            var score = 0
            fun at(x: Int, y: Int) = dark[y * size + x]
            for (line in 0 until size) for (horizontal in listOf(true, false)) {
                // outside the symbol counts as light (the quiet zone), so a finder-like pattern at an edge is seen too
                fun cell(i: Int) = i in 0 until size && (if (horizontal) at(i, line) else at(line, i))
                var run = 1
                for (i in 1 until size) {
                    if (cell(i) == cell(i - 1)) {
                        run++
                        if (run == 5) score += 3 else if (run > 5) score++
                    } else {
                        run = 1
                    }
                }
                for (i in -4..size - 7) {
                    val a = (0..10).all { cell(i + it) == FINDER_LIKE_A[it] }
                    val b = (0..10).all { cell(i + it) == FINDER_LIKE_B[it] }
                    if (a) score += 40
                    if (b) score += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = at(x, y)
                if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) score += 3
            }
            val total = size * size
            val darkCount = dark.count { it }
            val k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            return score + maxOf(k, 0) * 10
        }

        private fun addEccAndInterleave(data: ByteArray): ByteArray {
            val blocks = BLOCKS[version - 1]
            val eccLen = ECC_PER_BLOCK[version - 1]
            val raw = rawModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = reedSolomonDivisor(eccLen)
            val all = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until blocks) {
                val dataLen = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + dataLen)
                k += dataLen
                val block = dat.copyOf(shortLen + 1)
                reedSolomonRemainder(dat, divisor).copyInto(block, block.size - eccLen)
                all.add(block)
            }
            val result = ByteArray(raw)
            var n = 0
            for (i in 0 until all[0].size) for (j in all.indices) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = all[j][i]
            }
            return result
        }

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1, result.size)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        companion object {
            private val FINDER_LIKE_A = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            private val FINDER_LIKE_B = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
        }
    }
}
