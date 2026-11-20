// SPDX-License-Identifier: GPL-3.0-only
// Ported from jackotom/hongguo-drama src/native/sm3.js (GB/T 32905 SM3).
package io.github.hongguo.mobile.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Sm3 {
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )

    private fun rotateLeft(x: Int, n: Int): Int {
        val k = n and 31
        return if (k == 0) x else (x shl k) or (x ushr (32 - k))
    }

    fun digest(input: ByteArray): ByteArray {
        val paddedLen = ((input.size + 9 + 63) / 64) * 64
        val data = ByteArray(paddedLen)
        System.arraycopy(input, 0, data, 0, input.size)
        data[input.size] = 0x80.toByte()
        val bitLen = input.size.toLong() * 8L
        ByteBuffer.wrap(data, paddedLen - 8, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(bitLen)

        val state = IV.clone()
        val words = IntArray(68)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        var offset = 0
        while (offset < paddedLen) {
            for (i in 0 until 16) {
                words[i] = buf.getInt(offset + i * 4)
            }
            for (i in 16 until 68) {
                val x = words[i - 16] xor words[i - 9] xor rotateLeft(words[i - 3], 15)
                words[i] = x xor rotateLeft(x, 15) xor rotateLeft(x, 23) xor
                    rotateLeft(words[i - 13], 7) xor words[i - 6]
            }

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]

            for (i in 0 until 64) {
                val tj = if (i < 16) 0x79cc4519 else 0x7a879d8a
                val a12 = rotateLeft(a, 12)
                val s1 = rotateLeft(a12 + e + rotateLeft(tj, i), 7)
                val s2 = s1 xor a12
                val ff = if (i < 16) (a xor b xor c) else ((a and b) or (a and c) or (b and c))
                val gg = if (i < 16) (e xor f xor g) else ((e and f) or (e.inv() and g))
                val t1 = ff + d + s2 + (words[i] xor words[i + 4])
                val t2 = gg + h + s1 + words[i]
                d = c
                c = rotateLeft(b, 9)
                b = a
                a = t1
                h = g
                g = rotateLeft(f, 19)
                f = e
                e = t2 xor rotateLeft(t2, 9) xor rotateLeft(t2, 17)
            }

            state[0] = state[0] xor a
            state[1] = state[1] xor b
            state[2] = state[2] xor c
            state[3] = state[3] xor d
            state[4] = state[4] xor e
            state[5] = state[5] xor f
            state[6] = state[6] xor g
            state[7] = state[7] xor h
            offset += 64
        }

        val out = ByteArray(32)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until 8) {
            outBuf.putInt(i * 4, state[i])
        }
        return out
    }
}
