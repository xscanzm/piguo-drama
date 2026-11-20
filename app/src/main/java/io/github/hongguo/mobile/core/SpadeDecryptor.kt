// SPDX-License-Identifier: GPL-3.0-only
// Port of spade_a / spadeUrl decryption from jackotom/hongguo-drama src/native/app-source.js.
package io.github.hongguo.mobile.core

import java.nio.charset.StandardCharsets
import java.util.Base64

object SpadeDecryptor {
    private val KDF_SALT = ByteHelpers.hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    fun deriveContentKey(spadeA: String): ByteArray {
        val normalized = spadeA.trim().replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        val raw = Base64.getDecoder().decode(padded)
        require(raw.size >= 3) { "spade_a too short" }
        var v8 = raw.size - ((raw[0].toInt() and 0xFF) xor (raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)) + 47
        v8 = minOf(v8, raw.size - 1)
        require(v8 >= 33) { "spade_a key length invalid" }
        val work = raw.copyOfRange(1, 1 + v8)
        var a = 85
        var b = 246
        for (i in 0 until v8) {
            val old = work[i].toInt() and 0xFF
            val previous = if ((i and 1) != 0) a else b
            if ((i and 1) != 0) a = old else b = old
            val pop = Integer.bitCount(i)
            work[i] = ((-21 - pop + (previous xor old)) and 0xFF).toByte()
        }
        val hex = String(work, 1, 32, StandardCharsets.US_ASCII)
        require(Regex("^[0-9a-fA-F]{32}$").matches(hex)) { "App 视频密钥格式无效" }
        return ByteHelpers.hexToBytes(hex)
    }

    fun decryptSpadeUrl(encoded: String, seed: ByteArray): String {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty()) return ""
        val raw = ByteHelpers.base64Decode(trimmed)
        require(raw.size >= 5) { "spade URL ciphertext too short" }
        require((raw[0].toInt() and 0xFF) == 0xa8 && raw[2].toInt() == 0x01 && raw[3].toInt() == 0x00) {
            "spade URL header format error"
        }
        val cipherLength = ((raw.size - 4) / 16) * 16
        require(cipherLength > 0) { "spade URL ciphertext has no complete block" }
        val h1 = ByteHelpers.sha512(seed)
        val h2 = ByteHelpers.sha512(ByteHelpers.concat(h1, KDF_SALT))
        var plaintext = ByteHelpers.aesCbcDecrypt(
            key = h2.copyOfRange(0, 16),
            iv = h2.copyOfRange(16, 32),
            data = raw.copyOfRange(4, 4 + cipherLength),
            padding = false
        )
        var end = plaintext.size
        if (end > 0) {
            val padding = plaintext[end - 1].toInt() and 0xFF
            if (padding in 1..16 && padding <= end) {
                end -= padding
            }
        }
        while (end > 0 && plaintext[end - 1].toInt() == 0) {
            end--
        }
        return String(plaintext, 0, end, StandardCharsets.UTF_8)
    }
}
