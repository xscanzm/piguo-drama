// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ByteHelpers {
    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun randomUInt32(): Int = random.nextInt()

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray, uppercase: Boolean = false): String {
        val chars = if (uppercase) "0123456789ABCDEF" else "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(chars[v ushr 4])
            sb.append(chars[v and 0x0F])
        }
        return sb.toString()
    }

    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun base64Decode(str: String): ByteArray = Base64.getDecoder().decode(str)

    fun base64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    fun sha512(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(bytes)

    fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun gunzip(data: ByteArray, maxOutputBytes: Int = 2 * 1024 * 1024): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { gis ->
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val read = gis.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxOutputBytes) throw IllegalArgumentException("Gzip output exceeds limit")
                bos.write(buf, 0, read)
            }
        }
        return bos.toByteArray()
    }

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = true): ByteArray {
        val mode = if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding"
        val cipher = Cipher.getInstance(mode)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = false): ByteArray {
        val mode = if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding"
        val cipher = Cipher.getInstance(mode)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun rol32(value: Int, count: Int): Int {
        val n = count and 31
        return if (n == 0) value else (value shl n) or (value ushr (32 - n))
    }

    fun ror32(value: Int, count: Int): Int = rol32(value, -count)

    fun rol64(value: ULong, count: Int): ULong {
        val n = count and 63
        return if (n == 0) value else (value shl n) or (value shr (64 - n))
    }

    fun ror64(value: ULong, count: Int): ULong {
        val n = count and 63
        return if (n == 0) value else (value shr n) or (value shl (64 - n))
    }

    fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun be32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    fun le64(value: ULong): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()

    fun readUInt32LE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun readUInt32BE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    fun readUInt64LE(bytes: ByteArray, offset: Int): ULong =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()

    fun writeUInt32LE(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    fun writeUInt32BE(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }

    fun writeUInt64LE(bytes: ByteArray, offset: Int, value: ULong) {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong())
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val len = minOf(a.size, b.size)
        return ByteArray(len) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, out, pos, arr.size)
            pos += arr.size
        }
        return out
    }

    fun varint(value: Long): ByteArray {
        var n = value.toULong()
        val out = ByteArrayOutputStream()
        while (n > 127uL) {
            out.write((n.toInt() and 127) or 128)
            n = n shr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }

    fun zigzag(value: Long): Long =
        if (value < 0L) ((-value) * 2L - 1L) else (value * 2L)

    /**
     * Matches JS encodeURIComponent(value).replace(/[!'()]/g, ...) used in playback-v5.js.
     */
    fun encodeUrlComponent(value: String): String {
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("%2A", "*")
        val sb = StringBuilder(encoded.length)
        for (ch in encoded) {
            when (ch) {
                '!' -> sb.append("%21")
                '\'' -> sb.append("%27")
                '(' -> sb.append("%28")
                ')' -> sb.append("%29")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun encodeQueryParams(entries: List<Pair<String, String>>): String =
        entries.joinToString("&") { (k, v) -> "${encodeUrlComponent(k)}=${encodeUrlComponent(v)}" }
}

internal class ProtoWriter {
    private val bos = ByteArrayOutputStream()

    fun putVarint(tag: Int, value: Long): ProtoWriter {
        bos.write(ByteHelpers.varint(tag.toLong() shl 3))
        bos.write(ByteHelpers.varint(value))
        return this
    }

    fun putSint(tag: Int, value: Long): ProtoWriter {
        bos.write(ByteHelpers.varint(tag.toLong() shl 3))
        bos.write(ByteHelpers.varint(ByteHelpers.zigzag(value)))
        return this
    }

    fun putBytes(tag: Int, bytes: ByteArray): ProtoWriter {
        if (bytes.isEmpty()) return this
        bos.write(ByteHelpers.varint((tag.toLong() shl 3) or 2L))
        bos.write(ByteHelpers.varint(bytes.size.toLong()))
        bos.write(bytes)
        return this
    }

    fun putString(tag: Int, value: String): ProtoWriter =
        putBytes(tag, value.toByteArray(StandardCharsets.UTF_8))

    fun putFloat(tag: Int, value: Float): ProtoWriter {
        bos.write(ByteHelpers.varint((tag.toLong() shl 3) or 5L))
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        return this
    }

    fun toByteArray(): ByteArray = bos.toByteArray()
}
