@file:OptIn(ExperimentalUnsignedTypes::class)

// SPDX-License-Identifier: GPL-3.0-only
// Port of jackotom/hongguo-drama src/native/playback-v5.js
// (originally from N3urda/hongguoTV Signer.kt, AesV3.kt, V5Constants.kt & drpys).
package io.github.hongguo.mobile.core

import java.nio.charset.StandardCharsets

data class SignedRequest(
    val url: String,
    val headers: Map<String, String>
)

object PlaybackV5Signer {
    const val FIELD_21: Long = 312L
    private val PROCESS_NONCE = ByteHelpers.base64UrlNoPadding(ByteHelpers.randomBytes(18))
    private val PROCESS_STARTED = System.currentTimeMillis()
    private val MAGIC = ByteHelpers.hexToBytes("2d4b4fca49750d433fb5ae2c226dcc56")
    private val SIGN_KEY = ByteHelpers.hexToBytes("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
    private val SBOX = ByteHelpers.hexToBytes(
        "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e" +
            "2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d" +
            "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea" +
            "8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a" +
            "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0" +
            "b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d" +
            "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cd" +
            "bc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
            "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55b" +
            "a67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65" +
            "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272c" +
            "e860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe" +
            "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a889136" +
            "2fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8" +
            "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd95" +
            "1d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
            "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952be" +
            "a2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d" +
            "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99ea" +
            "a6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e" +
            "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187" +
            "745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28" +
            "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571" +
            "c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
            "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d" +
            "58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9" +
            "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46d" +
            "a6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f" +
            "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e" +
            "98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168" +
            "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b85" +
            "0b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e"
    )
    private val HASH_TABLE = intArrayOf(
        0xebb64faf.toInt(), 0x7aadcc2, 0xcf3187bf.toInt(), 0xe01138ff.toInt(),
        0x6d0bfcff, 0x5a30a3be, 0xb41ad638.toInt(), 0x34180eb8,
        0xf233eb6f.toInt(), 0xb1a584cc.toInt(), 0xccc30dc7.toInt(), 0x47d1db51,
        0xd55653de.toInt(), 0x70a84fa1, 0x57473c12, 0xf76f0288.toInt(),
        0x2c077f0a, 0xda0dcad0.toInt(), 0xfbb86f6c.toInt(), 0xfdc4cf00.toInt(),
        0x688a020d, 0xe676c6a6.toInt(), 0x8cd6338b.toInt(), 0x1a3c8d0e,
        0xcce8b06b.toInt(), 0x6ad0ed0b, 0xa0522717.toInt(), 0xdc71ac83.toInt(),
        0x2285db71, 0xd5b4dda6.toInt(), 0x736f8650, 0x6560306c,
        0x617ce2a6, 0xe423417e.toInt(), 0xa40e143, 0x544e4032,
        0x88dffb2a.toInt(), 0x716c1ae0, 0x4c467a88, 0x5b23bb3,
        0xe1d0b866.toInt(), 0xbaa3dcb8.toInt(), 0xae3374d3.toInt(), 0xc3381a50.toInt(),
        0x1702f75b, 0xfe6da368.toInt(), 0xf0b4cf48.toInt(), 0x4e0ffbb8,
        0x72aad10d, 0x26c53a3d, 0xf2bce0f6.toInt(), 0xb4557581.toInt(),
        0x4a257fdd, 0x8c3182a2.toInt(), 0xab0b3b86.toInt(), 0x3d5dfb14,
        0x4f103634, 0xd37b52d7.toInt(), 0x444eff16, 0xeb0a33d1.toInt(),
        0x6ca86f6e, 0x284ba7, 0x8387cfa, 0x5fb37586
    )

    private fun getIv(initial: Int, bytes: ByteArray): Int {
        var value = initial
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            value = if ((i and 1) == 0) {
                (value ushr 4) xor value xor (value shl 6) xor b
            } else {
                ((value ushr 7) xor value xor (b or (value shl 12))).inv()
            }
        }
        return value
    }

    private fun branch(query: ByteArray, body: ByteArray, ts: Int): Int =
        getIv(getIv(getIv(0x20230928, query), body), ByteHelpers.le32(ts)) and 15

    private fun sumMd5(bytes: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val b = bytes[i].toInt() and 0xFF
            val temp = (check ushr (if ((i and 1) == 0) 3 else 5)) xor check
            check = if ((i and 1) == 0) {
                (b xor (check shl 7)) xor temp
            } else {
                (b or (check shl 11)).inv() xor temp
            }
        }
        return (check or 4) xor 0x1000000
    }

    private fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        require(branch(query, body, ts) == 12) { "Unsupported v5 hash branch" }
        val seed = 0x9d4a97c2.toInt()
        val count = seed + ts
        val table = IntArray(HASH_TABLE.size) { i -> ByteHelpers.ror32(HASH_TABLE[i], count + 1) }
        val shift = (count + 2) and 7
        val pad = byteArrayOf(0xfa.toByte(), 0x45, 0x61, 0xd7.toByte()).map { v ->
            val u = v.toInt() and 0xFF
            (((u or (u shl 8)) ushr shift) and 0xFF).toByte()
        }.toByteArray()
        val data = ByteHelpers.concat(query, body, ByteHelpers.le32(ts), pad, ByteHelpers.hexToBytes("00000000000001a0"))
        val schedule = IntArray(data.size / 4 + 112)
        for (i in 0 until data.size / 4) {
            schedule[i] = ByteHelpers.readUInt32BE(data, i * 4)
        }
        val baseLen = data.size / 4
        for (i in 0 until 112) {
            val x = schedule[i + 1]
            val y = schedule[i + 14]
            val rx = ByteHelpers.rol32(x, 14) xor ByteHelpers.rol32(x, 25) xor (x ushr 3)
            val ry = ByteHelpers.rol32(y, 13) xor ByteHelpers.rol32(y, 15) xor (y ushr 10)
            schedule[baseLen + i] = schedule[i] + schedule[i + 9] + rx + ry
        }

        val initial = intArrayOf(
            0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
            984304912, 3005047866L.toInt(), 2874125293L.toInt(), 2152413264L.toInt()
        ).map { ByteHelpers.ror32(it, count) }.toIntArray()
        val state = initial.clone()

        for (i in 0 until 96) {
            val base = seed + i
            val n1 = ((state[4] xor state[0]) and state[1]) xor state[4]
            val n2 = ByteHelpers.rol32(state[1], 26) xor ByteHelpers.rol32(state[1], 21) xor ByteHelpers.rol32(state[1], 7)
            val n4 = schedule[base and 127] + n1 + n2 + table[base and 63] + state[6]
            val n5 = ByteHelpers.rol32(state[3], 30) xor ByteHelpers.rol32(state[3], 19) xor ByteHelpers.rol32(state[3], 10)
            val n6 = (state[3] and state[5]) or ((state[3] or state[5]) and state[2])
            val old = state[7]
            for (k in 7 downTo 1) state[k] = state[k - 1]
            state[0] = old + n4
            state[7] = n5 + n6 + n4
        }

        val output = ByteArray(32)
        for (i in 0 until 8) {
            ByteHelpers.writeUInt32BE(output, i * 4, state[i] + initial[i])
        }
        val folded = ByteHelpers.xor(output.copyOfRange(0, 16), output.copyOfRange(16, 32))
        return ByteHelpers.concat(folded, ByteHelpers.le32(sumMd5(folded) or 0x20))
    }

    private class CarrierV5(key: ByteArray, ts: Int) {
        private val box: ByteArray
        private val con: IntArray
        private val order: IntArray
        private val keys: Array<ByteArray>

        init {
            val word = ts and 3
            box = SBOX.copyOfRange(word * 256, (word + 1) * 256)
            con = arrayOf(
                intArrayOf(2, 0, 3, 1),
                intArrayOf(1, 3, 2, 0),
                intArrayOf(3, 2, 1, 0),
                intArrayOf(2, 3, 1, 0)
            )[word]
            order = arrayOf(
                intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
                intArrayOf(0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7),
                intArrayOf(0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7),
                intArrayOf(0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7)
            )[word]
            val initial = intArrayOf(0xbbb5de72.toInt(), 0xd4638d5b.toInt(), 0x92a5f73b.toInt(), 0x89f8a1c9.toInt())[word]
            val initBytes = ByteHelpers.le32(initial)
            val init16 = ByteHelpers.concat(initBytes, initBytes, initBytes, initBytes)
            val expanded = ByteArray(48)
            val mk = ByteHelpers.xor(init16, key)
            System.arraycopy(mk, 0, expanded, 0, 16)
            var rounds = 8
            for (i in 4 until 12) {
                val at = 4 * (i - 1)
                var a = expanded[at].toInt() and 0xFF
                var b = expanded[at + 1].toInt() and 0xFF
                var c = expanded[at + 2].toInt() and 0xFF
                var d = expanded[at + 3].toInt() and 0xFF
                if ((i and 3) == 0) {
                    val temp = ((initial ushr (rounds and 24)) xor (box[b].toInt() and 0xFF)) and 0xFF
                    b = box[c].toInt() and 0xFF
                    c = box[d].toInt() and 0xFF
                    d = box[a].toInt() and 0xFF
                    a = temp
                }
                rounds += 2
                expanded[at + 4] = (a xor (expanded[at - 12].toInt() and 0xFF)).toByte()
                expanded[at + 5] = (b xor (expanded[at - 11].toInt() and 0xFF)).toByte()
                expanded[at + 6] = (c xor (expanded[at - 10].toInt() and 0xFF)).toByte()
                expanded[at + 7] = (d xor (expanded[at - 9].toInt() and 0xFF)).toByte()
            }
            keys = Array(12) { i -> expanded.copyOfRange(i * 4, i * 4 + 4) }
        }

        private fun block(value: ByteArray): ByteArray {
            var rows = Array(4) { i -> IntArray(4) { j -> value[i * 4 + j].toInt() and 0xFF } }
            fun add(offset: Int) {
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        rows[i][j] = rows[i][j] xor (keys[offset + i][con[j]].toInt() and 0xFF)
                    }
                }
            }
            add(0)
            for (round in 1..2) {
                val before = Array(4) { i -> IntArray(4) { j -> box[rows[i][j]].toInt() and 0xFF } }
                val flat = IntArray(16)
                for (i in 0 until 4) {
                    val src = before[con[i]]
                    System.arraycopy(src, 0, flat, i * 4, 4)
                }
                rows = Array(4) { i -> IntArray(4) { j -> flat[order[i * 4 + j]] } }
                if (round == 1) {
                    rows = Array(4) { i -> IntArray(4) { j -> rows[i][con[j]] } }
                    val xt = { x: Int -> ((x shl 1) xor (if ((x and 128) != 0) 0x1b else 0)) and 255 }
                    for (i in 0 until 4) {
                        val t = rows[0][i] xor rows[1][i] xor rows[2][i] xor rows[3][i]
                        val u = rows[0][i]
                        rows[0][i] = rows[0][i] xor (t xor xt(rows[0][i] xor rows[1][i]))
                        rows[1][i] = rows[1][i] xor (t xor xt(rows[1][i] xor rows[2][i]))
                        rows[2][i] = rows[2][i] xor (t xor xt(rows[2][i] xor rows[3][i]))
                        rows[3][i] = rows[3][i] xor (t xor xt(rows[3][i] xor u))
                    }
                }
                add(round * 4)
            }
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    rows[i][j] = rows[i][j] xor (keys[4 + i][j].toInt() and 0xFF)
                }
            }
            val out = ByteArray(16)
            for (i in 0 until 4) {
                for (j in 0 until 4) out[i * 4 + j] = rows[i][j].toByte()
            }
            return out
        }

        fun encrypt(source: ByteArray, iv: ByteArray): ByteArray {
            require(source.size >= 248) { "V5 carrier requires at least 248 bytes" }
            val wire = intArrayOf(6, 5, 3, 4, 2, 7, 1, 0)
            val compact = intArrayOf(1, 7, 3, 6, 2, 0, 4, 5)
            val plaintext = ByteArray(32)
            for (i in 0 until 31) {
                var acc = 0
                for (j in 0 until 8) {
                    val s = source[i * 8 + j].toInt() and 0xFF
                    acc = acc or (((s ushr wire[j]) and 1) shl compact[j])
                }
                plaintext[i] = acc.toByte()
            }
            plaintext[31] = 1
            val first = block(ByteHelpers.xor(plaintext.copyOfRange(0, 16), iv))
            val second = block(ByteHelpers.xor(plaintext.copyOfRange(16, 32), first))
            val key = ByteHelpers.concat(first, second)
            val output = source.clone()
            for (i in 0 until 31) {
                val k = key[i].toInt() and 0xFF
                for (j in 0 until 8) {
                    val at = i * 8 + j
                    val o = output[at].toInt() and 0xFF
                    output[at] = ((o and (1 shl wire[j]).inv()) or (((k ushr compact[j]) and 1) shl wire[j])).toByte()
                }
            }
            return ByteHelpers.concat(key.copyOfRange(31, 32), output)
        }
    }

    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = ByteArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28
            val d0 = key[at].toInt() and 0xFF
            val d1 = key[at + 1].toInt() and 0xFF
            val b = data[i].toInt() and 0xFF
            var value = ((((b shl 4) or (b ushr 4)) and 255) + d0).inv() xor d1
            value = (((value and 255) shl 3) or ((value and 255) ushr 5)) and 255
            encoded[data.size - i - 1] = (((value + d1) and 255) xor d0).inv().toByte()
        }
        val last = (encoded[encoded.size - 1].toInt() and 0xFF) xor (encoded[encoded.size - 2].toInt() and 0xFF)
        val first = encoded[0].toInt() and 0xFF
        encoded[0] = (last.inv() + first).toByte()
        encoded[1] = (((encoded[0].toInt() and 0xFF) xor (encoded[encoded.size - 1].toInt() and 0xFF) xor 254) + (encoded[1].toInt() and 0xFF)).toByte()
        val e1 = encoded[1].toInt() and 0xFF
        encoded[2] = ((encoded[2].toInt() and 0xFF) + ((last - first) xor (((e1 shl 3) or (e1 ushr 5)) and 255) xor 2)).toByte()
        for (i in 0 until encoded.size - 4) {
            val e2 = encoded[i + 2].toInt() and 0xFF
            val temp = (((e2 shl 3) or (e2 ushr 5)) and 255) xor (encoded[i + 1].toInt() and 0xFF) xor (i + 3)
            encoded[i + 3] = (temp.inv() + (encoded[i + 3].toInt() and 0xFF)).toByte()
        }
        encoded[encoded.size - 1] = ((encoded[encoded.size - 1].toInt() and 0xFF) xor (encoded[encoded.size - 2].toInt() and 0xFF)).toByte()
        var sum = 0
        for (i in 1 until encoded.size) sum += (encoded[i].toInt() and 0xFF)
        encoded[0] = (((encoded[0].toInt() and 0xFF) xor (encoded[1].toInt() and 0xFF)) + sum).toByte()
        return encoded
    }

    private fun medusa(query: ByteArray, f13: ByteArray, ts: Int, who: Map<String, String>): String {
        val flags = ByteArray(8)
        val device = ProtoWriter()
            .putSint(1, 1)
            .putSint(2, 2)
            .putString(3, "8662")
            .putString(4, who.getValue("device_id"))
            .putString(5, PROCESS_NONCE)
            .putString(6, "!noperm!")
            .putString(12, "Asia/Shanghai,8")
            .putString(13, "zh_CN")
            .putString(22, who.getValue("os_version"))
            .putSint(25, PROCESS_STARTED)
            .putString(30, who.getValue("device_type"))
            .putString(31, "HONOR")
            .putString(32, who.getValue("device_type"))
            .putString(34, "HONOR")
            .toByteArray()
        val env = ProtoWriter()
            .putString(6, "v04.10.03.04-bugfix")
            .putString(8, who.getValue("channel"))
            .putBytes(12, device)
            .putString(14, who.getValue("version_name"))
            .toByteArray()
        val nested15 = ProtoWriter()
            .putSint(1, 1)
            .putSint(2, 1)
            .putSint(3, 1)
            .putSint(5, 1)
            .toByteArray()
        val message = ProtoWriter()
            .putBytes(1, MAGIC)
            .putSint(2, 5)
            .putSint(3, ByteHelpers.randomUInt32().toLong() and 0xFFFFFFFFL)
            .putString(4, "8662")
            .putString(5, who.getValue("device_id"))
            .putString(6, "646637794")
            .putString(7, who.getValue("version_name"))
            .putString(8, "v04.10.03-ml-android")
            .putSint(9, 0x040a0300L)
            .putBytes(10, flags)
            .putSint(12, ts.toLong())
            .putBytes(13, f13)
            .putBytes(14, query.copyOfRange(0, 6))
            .putBytes(15, nested15)
            .putSint(17, ts.toLong())
            .putString(20, "none")
            .putSint(21, FIELD_21)
            .putBytes(23, env)
            .putString(24, """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}""")
            .toByteArray()

        val nonce = ByteHelpers.randomUInt32()
        val high = (nonce ushr 16) and 255
        val hash = Sm3.digest(ByteHelpers.concat(SIGN_KEY, ByteHelpers.le32(nonce), SIGN_KEY))
        val seed = ByteHelpers.le32((((high shl 11) or (nonce ushr 24)) xor (high ushr 5) xor high).inv())
        val transformed = ByteHelpers.concat(flags, xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) {
            transformed[i] = ((transformed[i].toInt() and 0xFF) xor (seed[i.inv() and 3].toInt() and 0xFF)).toByte()
        }
        val q0 = query[0].toInt() and 63
        val f0 = f13[0].toInt() and 63
        val check = 0x18000001 or (q0 shl 14) or (f0 shl 8)
        val packed = ByteHelpers.concat(
            byteArrayOf(0xca.toByte()),
            ByteHelpers.le32(ByteHelpers.randomUInt32()),
            ByteHelpers.le32(check),
            transformed,
            byteArrayOf(((nonce ushr 16) and 0xFF).toByte(), ((nonce ushr 24) and 0xFF).toByte())
        )
        val encrypted = CarrierV5(ByteHelpers.md5(SIGN_KEY.copyOfRange(0, 16)), ts)
            .encrypt(packed, ByteHelpers.md5(SIGN_KEY.copyOfRange(16, 32)))
        val prefix = ByteHelpers.concat(ByteHelpers.le32(5), MAGIC)
        val time = ByteHelpers.le32(ts)
        for (i in prefix.indices) {
            prefix[i] = ((prefix[i].toInt() and 0xFF) xor (time[i and 3].toInt() and 0xFF)).toByte()
        }
        return ByteHelpers.base64Encode(
            ByteHelpers.concat(
                prefix,
                byteArrayOf((nonce and 0xFF).toByte(), ((nonce ushr 8) and 0xFF).toByte(), 0, 1),
                encrypted
            )
        )
    }

    private fun helios(ts: Int): String {
        val nonce = ByteHelpers.le32(ByteHelpers.randomUInt32())
        val hexDigest = ByteHelpers.bytesToHex(
            ByteHelpers.md5(ByteHelpers.concat(nonce, "8662".toByteArray(StandardCharsets.US_ASCII)))
        )
        val ascii = hexDigest.toByteArray(StandardCharsets.US_ASCII)
        val words = ArrayDeque<ULong>(40)
        for (i in 0 until 4) words.addLast(ByteHelpers.readUInt64LE(ascii, i * 8))
        val table = ULongArray(35)
        table[0] = words[0]
        var b0 = words.removeFirst()
        var b8 = words.removeFirst()
        for (i in 0 until 34) {
            var value = (ByteHelpers.ror64(b8, 8) + b0) xor i.toULong()
            words.addLast(value)
            value = value xor ByteHelpers.ror64(b0, 61)
            table[i + 1] = value
            b0 = value
            b8 = words.removeFirst()
        }
        val text = "$ts-646637794-8662".toByteArray(StandardCharsets.US_ASCII)
        val padding = 16 - (text.size % 16)
        val data = ByteArray(text.size + padding) { idx -> if (idx < text.size) text[idx] else padding.toByte() }
        val output = ByteArray(data.size)
        var at = 0
        while (at < data.size) {
            var a = ByteHelpers.readUInt64LE(data, at)
            var b = ByteHelpers.readUInt64LE(data, at + 8)
            for (i in 0 until 34) {
                b = table[i] xor (a + ByteHelpers.ror64(b, 8))
                a = b xor ByteHelpers.ror64(a, 61)
            }
            ByteHelpers.writeUInt64LE(output, at, a)
            ByteHelpers.writeUInt64LE(output, at + 8, b)
            at += 16
        }
        return ByteHelpers.base64Encode(ByteHelpers.concat(nonce, output))
    }

    fun sign(baseUrl: String, values: Map<String, String>, gzipBody: ByteArray?, nowMs: Long = System.currentTimeMillis()): SignedRequest {
        val ts = (nowMs / 1000L).toInt()
        val hasBody = gzipBody != null
        val bodyHash = ByteHelpers.md5(gzipBody ?: ByteArray(0))
        for (offset in 0 until 1024) {
            val ticket = (nowMs + offset).toString()
            val ordered = values.toMutableMap()
            ordered["_rticket"] = ticket
            val query = ByteHelpers.encodeQueryParams(ordered.entries.map { it.key to it.value })
            val queryHash = Sm3.digest(query.toByteArray(StandardCharsets.UTF_8))
            if (branch(queryHash, bodyHash, ts) != 12) continue
            val f13 = hashF13(queryHash, bodyHash, ts)
            val headers = LinkedHashMap<String, String>(4).apply {
                put("x-khronos", ts.toString())
                put("x-helios", helios(ts))
                put("x-medusa", medusa(queryHash, f13, ts, values))
                if (hasBody) {
                    put("x-ss-stub", ByteHelpers.bytesToHex(bodyHash, uppercase = true))
                }
            }
            return SignedRequest(
                url = "$baseUrl?$query",
                headers = headers
            )
        }
        error("Unable to select V5 playback signature branch within 1024 tickets")
    }
}
