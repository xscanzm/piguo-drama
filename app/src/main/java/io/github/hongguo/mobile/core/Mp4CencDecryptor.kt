// SPDX-License-Identifier: GPL-3.0-only
// Port of CENC-AES-CTR MP4 sample decryptor & moov box rebuilder from jackotom/hongguo-drama src/native/hongguo.js.
package io.github.hongguo.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Mp4CencDecryptor {
    private val CONTAINER_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta")
    private val PRUNED_TYPES = setOf("senc", "saio", "saiz", "sgpd", "sbgp")

    private class Mp4Box(
        val typ: String,
        val off: Int,
        val size: Int,
        val hdr: Int,
        var children: List<Mp4Box>?
    )

    private fun readU32(data: ByteArray, off: Int): Long =
        ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun readU64(data: ByteArray, off: Int): Long =
        ByteBuffer.wrap(data, off, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun writeU32(data: ByteArray, off: Int, value: Long) {
        ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt())
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val boxes = ArrayList<Mp4Box>()
        var off = start
        while (off + 8 <= end) {
            var size = readU32(data, off)
            val typ = String(data, off + 4, 4, StandardCharsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                if (off + 16 > end) break
                size = readU64(data, off + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (end - off).toLong()
            }
            if (size < hdr || off + size > end) break
            val sz = size.toInt()
            val children = if (typ in CONTAINER_TYPES) parseBoxes(data, off + hdr, off + sz) else null
            boxes.add(Mp4Box(typ, off, sz, hdr, children))
            off += sz
        }
        return boxes
    }

    private fun walkBoxes(boxes: List<Mp4Box>, out: MutableList<Mp4Box>) {
        for (b in boxes) {
            b.children?.let { walkBoxes(it, out) }
            out.add(b)
        }
    }

    private fun walkChildren(box: Mp4Box, out: MutableList<Mp4Box>) {
        for (c in box.children ?: emptyList()) {
            out.add(c)
            walkChildren(c, out)
        }
    }

    private fun clonePruned(box: Mp4Box): Mp4Box {
        val prunedChildren = box.children
            ?.filter { it.typ !in PRUNED_TYPES }
            ?.map { clonePruned(it) }
        return Mp4Box(box.typ, box.off, box.size, box.hdr, prunedChildren)
    }

    private fun rebuildMoov(data: ByteArray, moovOff: Int, moovSize: Int, top: List<Mp4Box>): ByteArray {
        val moovBox = top.firstOrNull { it.typ == "moov" } ?: error("no moov box")
        val tree = clonePruned(moovBox)
        val all = ArrayList<Mp4Box>()
        walkChildren(tree, all)

        val patches = HashMap<Int, ByteArray>()
        for (b in all) {
            if (b.typ == "stsd") {
                val content = b.off + 8
                val count = readU32(data, content + 4).toInt()
                var p = content + 8
                val entries = ArrayList<ByteArray>()
                for (i in 0 until count) {
                    val esize = readU32(data, p).toInt()
                    val etyp = String(data, p + 4, 4, StandardCharsets.ISO_8859_1)
                    if (etyp == "encv" || etyp == "enca") {
                        val hdrSize = if (etyp == "encv") 78 else 28
                        val children = parseBoxes(data, p + 8 + hdrSize, p + esize)
                        var newType = "mp4a"
                        if (etyp == "encv") {
                            val sinf = children.firstOrNull { it.typ == "sinf" }
                            val frma = sinf?.let { parseBoxes(data, it.off + it.hdr, it.off + it.size).firstOrNull { c -> c.typ == "frma" } }
                            val original = if (frma != null && frma.size == frma.hdr + 4) {
                                String(data, frma.off + frma.hdr, 4, StandardCharsets.ISO_8859_1)
                            } else null
                            val hasAvc = children.any { it.typ == "avcC" }
                            val hasHevc = children.any { it.typ == "hvcC" }
                            newType = original ?: when {
                                hasAvc && !hasHevc -> "avc1"
                                hasHevc && !hasAvc -> "hvc1"
                                else -> error("无法确定加密视频的原始编码")
                            }
                            require(newType in setOf("avc1", "avc3", "hvc1", "hev1")) { "不支持的视频编码: $newType" }
                        }
                        val entry = ByteArray(8 + hdrSize)
                        val typeBytes = newType.toByteArray(StandardCharsets.ISO_8859_1)
                        System.arraycopy(typeBytes, 0, entry, 4, 4)
                        System.arraycopy(data, p + 8, entry, 8, hdrSize)
                        val extraBos = ByteArrayOutputStream()
                        for (child in children) {
                            if (child.typ != "sinf") {
                                extraBos.write(data, child.off, child.size)
                            }
                        }
                        val extra = extraBos.toByteArray()
                        val fullEntry = ByteHelpers.concat(entry, extra)
                        writeU32(fullEntry, 0, fullEntry.size.toLong())
                        entries.add(fullEntry)
                    } else {
                        entries.add(data.copyOfRange(p, p + esize))
                    }
                    p += esize
                }
                val stsdHdr = ByteArray(16)
                System.arraycopy(data, b.off, stsdHdr, 0, 16)
                writeU32(stsdHdr, 12, entries.size.toLong())
                val fullStsd = ByteHelpers.concat(stsdHdr, *entries.toTypedArray())
                writeU32(fullStsd, 0, fullStsd.size.toLong())
                patches[b.off] = fullStsd
            }
        }

        fun serialize(box: Mp4Box): ByteArray {
            val ch = box.children ?: return patches[box.off] ?: data.copyOfRange(box.off, box.off + box.size)
            val bos = ByteArrayOutputStream()
            for (c in ch) bos.write(serialize(c))
            val body = bos.toByteArray()
            val hdrb = data.copyOfRange(box.off, box.off + box.hdr)
            if (box.hdr == 8) {
                writeU32(hdrb, 0, (8 + body.size).toLong())
            } else {
                writeU32(hdrb, 0, 1L)
                ByteBuffer.wrap(hdrb, 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(16L + body.size)
            }
            return ByteHelpers.concat(hdrb, body)
        }

        val first = serialize(tree)
        val delta = moovSize - first.size
        for (b in all) {
            if (b.typ == "stco") {
                val nc = readU32(data, b.off + 12).toInt()
                for (i in 0 until nc) {
                    val v = readU32(data, b.off + 16 + i * 4)
                    if (v >= moovOff + moovSize) {
                        writeU32(data, b.off + 16 + i * 4, v - delta)
                    }
                }
            }
        }
        return serialize(tree)
    }

    fun decryptBytes(data: ByteArray, key16: ByteArray): ByteArray {
        val total = data.size
        val top = parseBoxes(data, 0, total)
        val moov = top.firstOrNull { it.typ == "moov" } ?: error("no moov box found in MP4")

        val allBoxes = ArrayList<Mp4Box>()
        walkBoxes(top, allBoxes)
        val stbls = allBoxes.filter { it.typ == "stbl" }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(key16, "AES")
        val ivBuf = ByteArray(16)

        for (stbl in stbls) {
            val children = stbl.children ?: continue
            val stsz = children.firstOrNull { it.typ == "stsz" } ?: continue
            val stco = children.firstOrNull { it.typ == "stco" } ?: continue
            val stsc = children.firstOrNull { it.typ == "stsc" } ?: continue
            val senc = children.firstOrNull { it.typ == "senc" } ?: continue

            val ss = readU32(data, stsz.off + 12).toInt()
            val n = readU32(data, stsz.off + 16).toInt()
            val sizes = IntArray(n) { i ->
                if (ss == 0) readU32(data, stsz.off + 20 + i * 4).toInt() else ss
            }

            val nc = readU32(data, stco.off + 12).toInt()
            val chunkOffs = IntArray(nc) { i -> readU32(data, stco.off + 16 + i * 4).toInt() }

            val ns = readU32(data, stsc.off + 12).toInt()
            val stscFirst = IntArray(ns)
            val stscSpc = IntArray(ns)
            for (i in 0 until ns) {
                stscFirst[i] = readU32(data, stsc.off + 16 + i * 12).toInt()
                stscSpc[i] = readU32(data, stsc.off + 20 + i * 12).toInt()
            }

            val sc = readU32(data, senc.off + 12).toInt()
            val chunkSpc = IntArray(nc) { 1 }
            for (i in 0 until ns) {
                val fc = stscFirst[i]
                val spc = stscSpc[i]
                val nxt = if (i + 1 < ns) stscFirst[i + 1] else (nc + 1)
                for (ci in (fc - 1) until minOf(nxt - 1, nc)) {
                    if (ci >= 0) chunkSpc[ci] = spc
                }
            }

            val sampleOffs = IntArray(n)
            var si = 0
            for (ci in 0 until nc) {
                var off = chunkOffs[ci]
                val spc = chunkSpc[ci]
                for (s in 0 until spc) {
                    if (si >= n) break
                    sampleOffs[si] = off
                    off += sizes[si]
                    si++
                }
            }

            val count = minOf(si, sc)
            for (i in 0 until count) {
                val off = sampleOffs[i]
                val sz = sizes[i]
                if (off < 0 || sz <= 0 || off + sz > total) continue
                System.arraycopy(data, senc.off + 16 + i * 8, ivBuf, 0, 8)
                ivBuf.fill(0, 8, 16)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
                val plain = cipher.doFinal(data, off, sz)
                System.arraycopy(plain, 0, data, off, sz)
            }
        }

        val newMoov = rebuildMoov(data, moov.off, moov.size, top)
        val out = ByteArrayOutputStream(total)
        var off = 0
        while (off < total) {
            var size = readU32(data, off)
            val typ = String(data, off + 4, 4, StandardCharsets.ISO_8859_1)
            if (size == 1L) {
                size = readU64(data, off + 8)
            } else if (size == 0L) {
                size = (total - off).toLong()
            }
            if (size <= 0 || off + size > total) break
            if (typ == "moov") {
                out.write(newMoov)
            } else {
                out.write(data, off, size.toInt())
            }
            off += size.toInt()
        }
        return out.toByteArray()
    }

    fun decryptFile(inputFile: File, outputFile: File, key16: ByteArray) {
        val raw = inputFile.readBytes()
        val decrypted = decryptBytes(raw, key16)
        outputFile.writeBytes(decrypted)
    }
}
