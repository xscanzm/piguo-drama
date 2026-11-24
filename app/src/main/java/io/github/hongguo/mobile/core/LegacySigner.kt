@file:OptIn(ExperimentalUnsignedTypes::class)

// SPDX-License-Identifier: GPL-3.0-only
// Port of jackotom/hongguo-drama src/native/app-source.js (metadata/search/legacy signing)
// and src/native/playback-signature.js (Argus/Ladon/Simon).
package io.github.hongguo.mobile.core

import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val iid: String,
    val cdid: String
)

data class MetadataSignedRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?
)

object LegacySigner {
    const val VIDEO_API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
    const val PLAYER_API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1"
    const val CURRENT_PLAYBACK_HOST = "reading.snssdk.com"
    const val PRELOAD_PLAYBACK_PATH = "/novel/player/multi_video_model/preload/v1"
    const val VIDEO_MODEL_PATH = "/novel/player/video_model/v1/"
    const val SEARCH_API = "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v"

    val PRELOAD_PLAYBACK_HOSTS = listOf(
        "reading.snssdk.com",
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lq.fqnovel.com",
        "api3-normal-lf.fqnovel.com"
    )

    val PLAYBACK_HOSTS = listOf(
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinea.fqnovel.com",
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lf.fqnovel.com",
        "api5-normal-hl.fqnovel.com",
        "api5-normal-lq.fqnovel.com"
    )

    val VIDEO_TAB_HOSTS = listOf(
        "api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-lq.fqnovel.com",
        "api3-normal-lf.fqnovel.com",
        "api3-normal-lq.fqnovel.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api5-normal-sinfonlinea.fqnovel.com"
    )

    val PASSPORT_HOSTS = listOf(
        "security.snssdk.com",
        "is.snssdk.com",
        "api5-normal-sinfonlineb.fqnovel.com",
        "api3-normal-lf.fqnovel.com",
        "api5-normal-lf.fqnovel.com"
    )

    const val VIDEO_UA =
        "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    const val SEARCH_UA =
        "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
    const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    private val PROCESS_NONCE = ByteHelpers.base64UrlNoPadding(ByteHelpers.randomBytes(18))
    private val PROCESS_STARTED = System.currentTimeMillis()

    private fun localId(): String {
        val hex = ByteHelpers.bytesToHex(ByteHelpers.randomBytes(6))
        val v = BigInteger("1000000000000000").add(BigInteger(hex, 16))
        return v.toString()
    }

    private val BASE_DEVICE = linkedMapOf(
        "ac" to "wifi",
        "channel" to "update_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "71332",
        "version_name" to "7.1.3.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "manifest_version_code" to "71332",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "update_version_code" to "71332",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "71332",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0"
    )

    private val METADATA_DEVICE: Map<String, String> by lazy {
        LinkedHashMap(BASE_DEVICE).apply {
            this["version_code"] = "73532"
            this["version_name"] = "7.3.5.32"
            this["manifest_version_code"] = "73532"
            this["update_version_code"] = "73532"
            this["pv_player"] = "73532"
            this["iid"] = localId()
            this["device_id"] = localId()
        }
    }

    // ---- app-source.js Medusa / Helios / Gorgon ----

    private val BRANCH2_SV = intArrayOf(
        0xa7aefe20.toInt(), 0x7149f1d6, 0x47e4ca07, 0xe9b58f67.toInt(),
        0x93b924de.toInt(), 0xc614d0f5.toInt(), 0x38afe0ef, 0xb2bbad73.toInt(),
        0xe24444c3.toInt(), 0x9d3aec9b.toInt(), 0xdf7b37e4.toInt(), 0xd8b16d40.toInt(),
        0xf8ac31b8.toInt(), 0x76b9a90b, 0x31d833ee, 0x953fce64.toInt(),
        0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925.toInt(),
        0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0.toInt(), 0x114ca35e,
        0x8193f984.toInt(), 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9,
        0xa6d84453.toInt(), 0x3a18500c, 0x798ec47a, 0x97a76b28.toInt(),
        0x66c4ff96, 0x51716443, 0xdd2fc3b, 0xb5696da7.toInt(),
        0xbbeb3ac5.toInt(), 0x5c53d204, 0xd32608ce.toInt(), 0x7279b9ec,
        0xf4188ecf.toInt(), 0xf7d793db.toInt(), 0x332cc491, 0xab76ae15.toInt(),
        0x9bebe727.toInt(), 0x18a01384, 0x5be9f8a7, 0x5f90a754,
        0x39b663c0, 0x36673c83, 0x7c92f514, 0x9d7d94d7.toInt(),
        0xe2e8d9aa.toInt(), 0x5f7e9ea9, 0x7abd4551, 0x569e05da,
        0x40a25632, 0x3df5a9a5, 0xbab37d80.toInt(), 0x454286dc,
        0x3f5d4e78, 0x3d7b75d, 0xb1fe4af7.toInt(), 0xa5ab26a3.toInt()
    )

    private val BRANCH2_ORDERS = ByteHelpers.hexToBytes(
        "0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04" +
            "060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e" +
            "0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e" +
            "0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b00" +
            "0b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d000104" +
            "0906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e" +
            "01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d" +
            "04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c0903" +
            "0a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c060904" +
            "0d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003"
    )

    private val MEDUSA_SBOX = ByteHelpers.base64Decode(
        "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83kl" +
            "Tni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpi" +
            "Aj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RM" +
            "WKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQx" +
            "LJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoG" +
            "EIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVM" +
            "rLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45" +
            "JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwA" +
            "c2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KE" +
            "j2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HL" +
            "npIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0Ynh" +
            "fS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+E" +
            "CMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2Zc" +
            "dIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocm" +
            "nR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8" +
            "JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf" +
            "9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOg" +
            "DJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwi" +
            "O/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssb" +
            "CQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQ" +
            "WZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C" +
            "7Hd+5CvbkMEFnnrUUmskEw=="
    )

    private fun getIv(iv: Int, data: ByteArray): Int {
        var value = iv
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            value = if ((i and 1) == 0) {
                (value ushr 4) xor value xor (value shl 6) xor b
            } else {
                ((value ushr 7) xor value xor (b or (value shl 12))).inv()
            }
        }
        return value
    }

    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val b = data[i].toInt() and 0xFF
            val temp = if ((i and 1) == 0) (check ushr 3) xor check else (check ushr 5) xor check
            check = if ((i and 1) == 0) (b xor (check shl 7)) else (b or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }

    private fun md5V3Step(kind: Int, a: Int, b: Int, c: Int, d: Int, m: Int, shift: Int, constant: Int): Int {
        val f = when (kind) {
            0 -> (b and c) or (b.inv() and d)
            1 -> (b and d) or (c and d.inv())
            2 -> b xor c xor d
            else -> c xor (b or d.inv())
        }
        return b + ByteHelpers.rol32(a + f + m + constant, shift)
    }

    private fun md5SumV3(message: ByteArray, countV2: Int, orders: ByteArray, countV1: Int): ByteArray {
        val sv = IntArray(BRANCH2_SV.size) { i -> ByteHelpers.ror32(BRANCH2_SV[i], countV1) }
        val start = intArrayOf(
            ByteHelpers.ror32(0x79e0f2fb, countV2),
            ByteHelpers.ror32(0xc8b52570.toInt(), countV2),
            ByteHelpers.ror32(0xebc2f8cd.toInt(), countV2),
            ByteHelpers.ror32(0x7c104d93, countV2)
        )
        val endCount = (countV2 + 6) and 255
        val end = intArrayOf(
            ByteHelpers.ror32(0x19be4866, endCount),
            ByteHelpers.ror32(0xe85986b4.toInt(), endCount),
            ByteHelpers.ror32(0xe19b326e.toInt(), endCount),
            ByteHelpers.ror32(0x71d1d7d4, endCount)
        )
        val m = IntArray(16) { i -> ByteHelpers.readUInt32LE(message, i * 4) }
        var a = start[0]
        var b = start[1]
        var c = start[2]
        var d = start[3]

        fun run(kind: Int, shifts: IntArray, offset: Int) {
            for (i in 0 until 16) {
                val ord = orders[offset + i].toInt() and 0xFF
                val phase = i and 3
                val next = when (phase) {
                    0 -> md5V3Step(kind, a, b, c, d, m[ord], shifts[i], sv[offset + i])
                    1 -> md5V3Step(kind, d, a, b, c, m[ord], shifts[i], sv[offset + i])
                    2 -> md5V3Step(kind, c, d, a, b, m[ord], shifts[i], sv[offset + i])
                    else -> md5V3Step(kind, b, c, d, a, m[ord], shifts[i], sv[offset + i])
                }
                when (phase) {
                    0 -> a = next
                    1 -> d = next
                    2 -> c = next
                    else -> b = next
                }
            }
        }

        run(0, intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22), 0)
        run(1, intArrayOf(5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20), 16)
        run(2, intArrayOf(4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23), 32)
        run(3, intArrayOf(6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21), 48)

        val ret = ByteArray(16)
        ByteHelpers.writeUInt32LE(ret, 0, (start[0] + a) xor end[0])
        ByteHelpers.writeUInt32LE(ret, 4, (start[1] + b) xor end[1])
        ByteHelpers.writeUInt32LE(ret, 8, (start[2] + c) xor end[2])
        ByteHelpers.writeUInt32LE(ret, 12, (start[3] + d) xor end[3])
        return ByteHelpers.concat(ret, ByteHelpers.le32(sumMd5(ret)))
    }

    private fun branch2F13(iv: Int, querySm3: ByteArray, bodyMd5: ByteArray, tsBytes: ByteArray, khronos: Int): ByteArray {
        val v = (iv and 13) * 86
        val ivV0 = ((v ushr 15) and 255) + ((v ushr 8) and 255)
        val n1 = intArrayOf(0x8980f29b.toInt(), 0xeb549c7f.toInt(), 0xb08726db.toInt(), 0xd40cb5e6.toInt(), 0xe8f559e4.toInt())[ivV0]
        val countV1 = (n1 + khronos + 1) and 255
        val countV2 = n1 + khronos
        val shift = (countV2 + 5) and 7
        val seed = intArrayOf(0x84, 0x96, 0x77, 0x9d, 0xd4, 0x15, 0x0b, 0xf8)
        val pad = ByteArray(seed.size) { idx -> (((seed[idx] or (seed[idx] shl 8)) ushr shift) and 255).toByte() }
        val input = ByteHelpers.concat(querySm3, bodyMd5, tsBytes, pad, ByteHelpers.hexToBytes("a0010000"))
        val orders = BRANCH2_ORDERS.copyOfRange(ivV0 shl 6, (ivV0 + 1) shl 6)
        return md5SumV3(input, countV2, orders, countV1)
    }

    private fun hashF13Legacy(querySm3: ByteArray, bodyMd5: ByteArray, tsBytes: ByteArray, khronos: Int): ByteArray {
        val iv = getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), tsBytes)
        val low = iv and 15
        val ivV0 = (low * 171) ushr 9
        val branch = low - ivV0 * 3
        if (branch == 2) return branch2F13(iv, querySm3, bodyMd5, tsBytes, khronos)
        if (branch != 0) {
            return ByteHelpers.concat(
                querySm3.copyOfRange(0, 16),
                bodyMd5,
                ByteHelpers.le32(sumMd5(ByteHelpers.concat(querySm3, bodyMd5)))
            )
        }
        val ivV1List = intArrayOf(0xc4a78580.toInt(), 0xb3c0fd39.toInt(), 0xc58c5686.toInt(), 0xc9aa3ba7.toInt(), 0xf5a7adf2.toInt(), 0x963c2ed1.toInt())
        val ivV1 = ivV1List[ivV0]
        val countV1 = (ivV1 + khronos + 1) and 255
        val countV2 = ivV1 + khronos
        val tt02 = intArrayOf(
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
        val tt = IntArray(tt02.size) { i -> ByteHelpers.ror32(tt02[i], countV1) }
        val n0 = (countV2 + 2) and 7
        val seed = intArrayOf(0xfa, 0x45, 0x61, 0xd7)
        val pad = ByteArray(seed.size) { idx -> (((seed[idx] or (seed[idx] shl 8)) ushr n0) and 255).toByte() }
        val data = ByteHelpers.concat(querySm3, bodyMd5, tsBytes, pad, ByteHelpers.hexToBytes("00000000000001a0"))
        val di = IntArray(data.size / 4 + 112)
        for (i in 0 until data.size / 4) di[i] = ByteHelpers.readUInt32BE(data, i * 4)
        var di0 = di[0]
        val baseLen = data.size / 4
        for (i in 0 until 112) {
            val di1 = di[i + 1]
            val di14 = di[i + 14]
            val r1 = ByteHelpers.rol32(di1, 14) xor ByteHelpers.rol32(di1, 25) xor (di1 ushr 3)
            val r2 = ByteHelpers.rol32(di14, 13) xor ByteHelpers.rol32(di14, 15) xor (di14 ushr 10)
            di[baseLen + i] = di0 + di[i + 9] + r1 + r2
            di0 = di1
        }
        val init = intArrayOf(
            0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
            984304912, 3005047866L.toInt(), 2874125293L.toInt(), 2152413264L.toInt()
        ).map { ByteHelpers.ror32(it, countV2 and 31) }.toIntArray()
        val chosen = arrayOf(
            intArrayOf(101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3),
            intArrayOf(96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4),
            intArrayOf(96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5),
            intArrayOf(99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6),
            intArrayOf(96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4),
            intArrayOf(100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5)
        )[ivV0]
        val d = init.clone()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n0v = di[base and 127]
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = ByteHelpers.rol32(d[chosen[1]], 26) xor ByteHelpers.rol32(d[chosen[1]], 21) xor ByteHelpers.rol32(d[chosen[1]], 7)
            val n4 = n0v + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = ByteHelpers.rol32(d[chosen[2]], 30) xor ByteHelpers.rol32(d[chosen[2]], 19) xor ByteHelpers.rol32(d[chosen[2]], 10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val n7 = n5 + n6
            val old = d[chosen[9]]
            for (k in 7 downTo 1) d[k] = d[k - 1]
            d[0] = old // note: d.unshift(d.pop())
            d[chosen[10]] = n7 + n4
            d[chosen[8]] = old + n4
        }
        val ret = ByteArray(32)
        for (i in 0 until 8) ByteHelpers.writeUInt32BE(ret, i * 4, d[i] + init[i])
        val folded = ByteHelpers.xor(ret.copyOfRange(0, 16), ret.copyOfRange(16, 32))
        return ByteHelpers.concat(folded, ByteHelpers.le32(sumMd5(folded)))
    }

    private fun reverseBits(value: Int): Int {
        var result = 0
        for (i in 0 until 8) {
            result = (result shl 1) or ((value ushr i) and 1)
        }
        return result
    }

    private fun rc4Gorgon(data: ByteArray, key: IntArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) and 255
            s[i] = s[j]
        }
        var i = 0
        j = 0
        return ByteArray(data.size) { idx ->
            i += 1
            val x = s[i]
            j += x
            val y = s[j and 255]
            s[i] = y
            ((data[idx].toInt() and 0xFF) xor s[(y + y) and 255]).toByte()
        }
    }

    private fun xGorgon(query: String, body: ByteArray?, timestamp: Int, random: Int): String {
        val sdk = ByteHelpers.le32(67503104)
        val qMd5 = ByteHelpers.md5(query.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 4)
        val bMd5 = if (body != null && body.isNotEmpty()) ByteHelpers.md5(body).copyOfRange(0, 4) else ByteArray(4)
        val input = ByteHelpers.concat(qMd5, bMd5, ByteArray(4), sdk, ByteHelpers.be32(timestamp))
        val key = intArrayOf(0x4a, 0x40, 0x16, (random ushr 8) and 255, 0x47, 0x6c, 0x01, random and 255)
        val out = rc4Gorgon(input, key)
        for (i in out.indices) {
            var value = out[i].toInt() and 0xFF
            value = ((value ushr 4) or (value shl 4)) and 255
            val next = out[if (i + 1 < out.size) i + 1 else 0].toInt() and 0xFF
            out[i] = ((reverseBits((next xor value) and 255) xor 20).inv() and 255).toByte()
        }
        return ByteHelpers.bytesToHex(
            ByteHelpers.concat(
                byteArrayOf(0x84.toByte(), 0x04),
                byteArrayOf((random and 255).toByte(), ((random ushr 8) and 255).toByte(), 0x40, 0x01),
                out
            )
        )
    }

    private class AesV3(key: ByteArray, khronos: Int) {
        private val wordSize = khronos and 3
        private val box = MEDUSA_SBOX.copyOfRange(wordSize * 256, (wordSize + 1) * 256)
        private val con2 = arrayOf(
            intArrayOf(1, 0, 2, 3),
            intArrayOf(2, 0, 3, 1),
            intArrayOf(0, 1, 3, 2),
            intArrayOf(1, 0, 2, 3)
        )[wordSize]
        private val order = arrayOf(
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
            intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
            intArrayOf(0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15),
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3)
        )[wordSize]
        private val keys: Array<IntArray>

        init {
            val init = intArrayOf(0xca025ddc.toInt(), 0x823dc546.toInt(), 0xc9420583.toInt(), 0xc298225f.toInt())[wordSize]
            val initBytes = ByteHelpers.le32(init)
            val initial = ByteHelpers.concat(initBytes, initBytes, initBytes, initBytes)
            val mk = ByteArray(initial.size) { i -> ((initial[i].toInt() and 0xFF) xor (key[i % key.size].toInt() and 0xFF)).toByte() }
            val expanded = ByteArray(48)
            System.arraycopy(mk, 0, expanded, 0, 16)
            var rounds = 8
            for (i in 4 until 12) {
                val at = 4 * (i - 1)
                var a = expanded[at].toInt() and 0xFF
                var b = expanded[at + 1].toInt() and 0xFF
                var c = expanded[at + 2].toInt() and 0xFF
                var d = expanded[at + 3].toInt() and 0xFF
                if ((i and 3) == 0) {
                    val t = ((init ushr (rounds and 24)) xor (box[b].toInt() and 0xFF)) and 255
                    b = box[c].toInt() and 0xFF
                    c = box[d].toInt() and 0xFF
                    d = box[a].toInt() and 0xFF
                    a = t
                }
                rounds += 2
                expanded[at + 4] = (a xor (expanded[at - 12].toInt() and 0xFF)).toByte()
                expanded[at + 5] = (b xor (expanded[at - 11].toInt() and 0xFF)).toByte()
                expanded[at + 6] = (c xor (expanded[at - 10].toInt() and 0xFF)).toByte()
                expanded[at + 7] = (d xor (expanded[at - 9].toInt() and 0xFF)).toByte()
            }
            keys = Array(12) { i -> IntArray(4) { j -> expanded[i * 4 + j].toInt() and 0xFF } }
        }

        private fun block(value: ByteArray): ByteArray {
            val rows = Array(4) { i -> IntArray(4) { j -> value[i * 4 + j].toInt() and 0xFF } }
            fun addCon(offset: Int) {
                for (i in 0 until 4) for (j in 0 until 4) rows[i][j] = rows[i][j] xor keys[offset + i][con2[j]]
            }
            addCon(0)
            for (round in 1..2) {
                for (i in 0 until 4) for (j in 0 until 4) rows[i][j] = box[rows[i][j]].toInt() and 0xFF
                val old = Array(4) { i -> rows[i].clone() }
                for (i in 0 until 4) rows[i] = old[con2[i]]
                val b = IntArray(16) { idx -> rows[idx / 4][idx % 4] }
                for (idx in 0 until 16) rows[idx / 4][idx % 4] = b[order[idx]]
                if (round == 1) {
                    val beforeMix = Array(4) { i -> rows[i].clone() }
                    for (i in 0 until 4) rows[i] = IntArray(4) { j -> beforeMix[i][con2[j]] }
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
                addCon(round * 4)
            }
            for (i in 0 until 4) for (j in 0 until 4) rows[i][j] = rows[i][j] xor keys[4 + i][j]
            val out = ByteArray(16)
            for (i in 0 until 4) for (j in 0 until 4) out[i * 4 + j] = rows[i][j].toByte()
            return out
        }

        fun encrypt(data: ByteArray, iv: ByteArray): ByteArray {
            val plaintext = ByteArray(32)
            for (i in 0 until 31) {
                val at = i * 8
                val n0 = ((data[at].toInt() and 0xFF) ushr 4) and 2
                val n1 = n0 or ((data[at + 1].toInt() and 0xFF) and 64)
                val n2 = n1 or (((data[at + 2].toInt() and 0xFF) ushr 2) and 1)
                val n3 = n2 or (((data[at + 3].toInt() and 0xFF) shl 3) and 128)
                val n4 = n3 or (((data[at + 4].toInt() and 0xFF) ushr 1) and 4)
                val n5 = n4 or (((data[at + 5].toInt() and 0xFF) shl 3) and 16)
                val n6 = n5 or (((data[at + 6].toInt() and 0xFF) shl 5) and 32)
                plaintext[i] = (n6 or (((data[at + 7].toInt() and 0xFF) ushr 4) and 8)).toByte()
            }
            plaintext[31] = 1
            val first = block(ByteHelpers.xor(plaintext.copyOfRange(0, 16), iv))
            val second = block(ByteHelpers.xor(plaintext.copyOfRange(16, 32), first))
            val key = ByteHelpers.concat(first, second)
            val out = data.clone()
            for (i in 0 until 31) {
                val at = i * 8
                val k = key[i].toInt() and 0xFF
                out[at] = (((out[at].toInt() and 0xdf) or ((k shl 4) and 32))).toByte()
                out[at + 1] = (((out[at + 1].toInt() and 0xbf) or (k and 64))).toByte()
                out[at + 2] = (((out[at + 2].toInt() and 0xfb) or ((k shl 2) and 4))).toByte()
                out[at + 3] = (((out[at + 3].toInt() and 0xef) or ((k ushr 3) and 16))).toByte()
                out[at + 4] = (((out[at + 4].toInt() and 0xf7) or ((k + k) and 8))).toByte()
                out[at + 5] = (((out[at + 5].toInt() and 0xfd) or ((k ushr 3) and 2))).toByte()
                out[at + 6] = (((out[at + 6].toInt() and 0xfe) or ((k ushr 5) and 1))).toByte()
                out[at + 7] = (((out[at + 7].toInt() and 0x7f) or ((k shl 4) and 128))).toByte()
            }
            return ByteHelpers.concat(key.copyOfRange(31, 32), out)
        }
    }

    private fun xmxorLegacy(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = ByteArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28
            val d0 = key[at].toInt() and 0xFF
            val d1 = key[at + 1].toInt() and 0xFF
            val b = data[i].toInt() and 0xFF
            var d2 = (((b shl 4) or (b ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1
            d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255
            d2 = (d2 xor d0) and 255
            encoded[data.size - i - 1] = (d2.inv() and 255).toByte()
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
        for (i in 0 until encoded.size - 1) sum += (encoded[i + 1].toInt() and 0xFF)
        encoded[0] = (((encoded[0].toInt() and 0xFF) xor (encoded[1].toInt() and 0xFF)) + sum).toByte()
        return encoded
    }

    private fun deviceProto(deviceId: String): ByteArray = ProtoWriter()
        .putSint(1, 1).putSint(2, 2).putString(3, "8662").putString(4, deviceId)
        .putString(5, PROCESS_NONCE).putString(6, "!noperm!")
        .putSint(7, -888888).putSint(8, -888888).putSint(9, 3).putSint(10, -888888)
        .putString(11, "!notset!").putString(12, "Asia/Shanghai,8").putString(13, "zh_CN")
        .putSint(14, 4)
        .putFloat(16, 0f).putFloat(17, 0f).putFloat(18, 0f).putFloat(19, 0f).putFloat(20, 0f).putFloat(21, 0f)
        .putString(22, "16").putSint(23, 41).putSint(24, 36)
        .putSint(25, PROCESS_STARTED).putSint(26, PROCESS_STARTED).putSint(27, PROCESS_STARTED).putSint(28, PROCESS_STARTED)
        .putSint(29, -1).putString(30, "25053RT47C").putString(31, "Redmi")
        .putString(32, "25053RT47C").putString(33, "25053RT47C").putString(34, "Xiaomi")
        .putString(35, "Redmi").putString(36, "Redmi").putSint(38, 31)
        .toByteArray()

    private fun buildMedusaLegacy(url: String, body: ByteArray?, khronos: Int, deviceId: String, versionName: String): String {
        val bodyMd5 = if (body != null && body.isNotEmpty()) ByteHelpers.md5(body) else ByteArray(16)
        val queryPart = url.substringAfter('?')
        val queryBytes = queryPart.toByteArray(StandardCharsets.UTF_8)
        val querySm3 = Sm3.digest(queryBytes)
        val tsBytes = ByteHelpers.le32(khronos)
        val queryBodyTs = hashF13Legacy(querySm3, bodyMd5, tsBytes, khronos)
        val nested = ProtoWriter().putSint(1, 111).putSint(2, 10).putSint(3, 694367).putSint(5, 586952199).toByteArray()
        val messageRand = ByteHelpers.randomUInt32().toLong() and 0xFFFFFFFFL
        val envLaunch = (ByteHelpers.random.nextInt(21) + 100).toLong()
        val envPid = (ByteHelpers.random.nextInt(2000) + 10001).toLong()
        val innerEnv = ProtoWriter()
            .putSint(1, System.currentTimeMillis() / 1000L)
            .putSint(2, -2)
            .putSint(4, 200)
            .toByteArray()
        val env = ProtoWriter()
            .putSint(1, envLaunch).putSint(2, 146331399).putSint(3, 146331396).putSint(5, 7)
            .putString(6, "v04.06.04.03-bugfix").putSint(7, envPid)
            .putBytes(12, deviceProto(deviceId)).putBytes(13, innerEnv).putString(14, versionName)
            .toByteArray()
        val queryHash = Sm3.digest(ByteHelpers.concat(queryBytes, bodyMd5, "none".toByteArray(StandardCharsets.US_ASCII)))
        val message = ProtoWriter()
            .putBytes(1, ByteHelpers.hexToBytes("f7e85ffad7d7dc3bd62ac87057cf6118"))
            .putSint(2, 3).putSint(3, messageRand).putString(4, "8662").putString(5, deviceId)
            .putString(6, "1588093228").putString(7, versionName).putString(8, "v04.06.04-ml-android")
            .putSint(9, 67503104).putBytes(10, ByteHelpers.hexToBytes("4001000000000000"))
            .putSint(12, khronos.toLong()).putBytes(13, queryBodyTs).putBytes(14, querySm3.copyOfRange(0, 6))
            .putBytes(15, nested).putString(16, PROCESS_NONCE).putSint(17, khronos.toLong())
            .putBytes(19, queryHash).putString(20, "none").putSint(21, 312).putBytes(23, env)
            .putString(24, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""")
            .toByteArray()

        val random = ByteHelpers.randomUInt32()
        val signKey = ByteHelpers.hexToBytes("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val hash = Sm3.digest(ByteHelpers.concat(signKey, ByteHelpers.le32(random), signKey))
        val d1 = (random ushr 16) and 255
        val d2 = (((d1 shl 11) or (random ushr 24)) xor (d1 ushr 5) xor d1).inv()
        val seed = ByteHelpers.le32(d2)
        val transformed = ByteHelpers.concat(ByteHelpers.hexToBytes("4001000000000000"), xmxorLegacy(message, hash)).reversedArray()
        for (i in transformed.indices) {
            transformed[i] = ((transformed[i].toInt() and 0xFF) xor (seed[i.inv() and 3].toInt() and 0xFF)).toByte()
        }
        val check = ((querySm3[0].toInt() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].toInt() and 63) shl 8)
        val xmRand = ByteHelpers.randomUInt32()
        val packed = ByteHelpers.concat(
            byteArrayOf(0x35),
            ByteHelpers.le32(xmRand),
            ByteHelpers.le32(check),
            transformed,
            byteArrayOf(((random ushr 16) and 255).toByte(), ((random ushr 24) and 255).toByte())
        )
        val encrypted = AesV3(ByteHelpers.hexToBytes("f1593376766ea98d34f31b057a9d5be4"), khronos)
            .encrypt(packed, ByteHelpers.hexToBytes("1fe109a4125283f418de9e051a969e12"))
        val version = ByteHelpers.hexToBytes("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        val prefix = ByteArray(20)
        for (i in 0 until 20 step 4) {
            ByteHelpers.writeUInt32LE(prefix, i, ByteHelpers.readUInt32LE(version, i) xor khronos)
        }
        return ByteHelpers.base64Encode(
            ByteHelpers.concat(
                prefix,
                byteArrayOf((random and 255).toByte(), ((random ushr 8) and 255).toByte(), 0, 1),
                encrypted
            )
        )
    }

    private fun heliosLegacy(khronos: Int): String {
        val random = ByteHelpers.randomUInt32()
        val data = ByteHelpers.concat(ByteHelpers.le32(random), "8662".toByteArray(StandardCharsets.US_ASCII))
        val ascii = ByteHelpers.bytesToHex(ByteHelpers.md5(data)).toByteArray(StandardCharsets.US_ASCII)
        val words = ArrayDeque<ULong>(40)
        for (i in 0 until 4) words.addLast(ByteHelpers.readUInt64LE(ascii, i * 8))
        val table = ULongArray(35)
        table[0] = words[0]
        var b0 = words.removeFirst()
        var b8 = words.removeFirst()
        for (i in 0 until 34) {
            var x8 = ByteHelpers.ror64(b8, 8) + b0
            x8 = x8 xor i.toULong()
            words.addLast(x8)
            x8 = x8 xor ByteHelpers.ror64(b0, 61)
            table[i + 1] = x8
            b0 = x8
            b8 = words.removeFirst()
        }
        val text = "$khronos-1588093228-8662".toByteArray(StandardCharsets.US_ASCII)
        val padLen = 16 - (text.size % 16)
        val pad = ByteArray(text.size + padLen) { i -> if (i < text.size) text[i] else padLen.toByte() }
        val output = ByteArray(pad.size)
        var at = 0
        while (at < pad.size) {
            var a = ByteHelpers.readUInt64LE(pad, at)
            var b = ByteHelpers.readUInt64LE(pad, at + 8)
            for (i in 0 until 34) {
                b = table[i] xor (a + ByteHelpers.ror64(b, 8))
                a = b xor ByteHelpers.ror64(a, 61)
            }
            ByteHelpers.writeUInt64LE(output, at, a)
            ByteHelpers.writeUInt64LE(output, at + 8, b)
            at += 16
        }
        return ByteHelpers.base64Encode(ByteHelpers.concat(ByteHelpers.le32(random), output))
    }

    private fun branchOf(url: String, body: ByteArray?, khronos: Int): Int {
        val bodyMd5 = if (body != null && body.isNotEmpty()) ByteHelpers.md5(body) else ByteArray(16)
        val q = Sm3.digest(url.substringAfter('?').toByteArray(StandardCharsets.UTF_8))
        val iv = getIv(getIv(getIv(0x20230928, q), bodyMd5), ByteHelpers.le32(khronos))
        val low = iv and 15
        return low - (((low * 171) ushr 9) * 3)
    }

    fun signedMetadataRequest(
        apiPath: String,
        values: Map<String, String>,
        payloadJson: String? = null,
        host: String = "api5-normal-sinfonlineb.fqnovel.com",
        cookieHeader: String? = null,
        identity: DeviceIdentity? = null
    ): MetadataSignedRequest {
        val body = payloadJson?.toByteArray(StandardCharsets.UTF_8)
        val deviceMap = if (identity != null) {
            LinkedHashMap(METADATA_DEVICE).apply {
                this["iid"] = identity.iid
                this["device_id"] = identity.deviceId
                this["cdid"] = identity.cdid
            }
        } else {
            METADATA_DEVICE
        }
        val activeDeviceId = deviceMap.getValue("device_id")
        val nowMs = System.currentTimeMillis()
        val khronos = (nowMs / 1000L).toInt()
        var query = ""
        var url = ""
        var ticket = nowMs
        var found = false
        for (n in 0 until 128) {
            ticket = nowMs + n
            val params = LinkedHashMap<String, String>()
            params.putAll(values)
            params.putAll(deviceMap)
            params["ts"] = khronos.toString()
            params["_rticket"] = ticket.toString()
            query = ByteHelpers.encodeQueryParams(params.entries.map { it.key to it.value })
            url = "https://$host$apiPath?$query"
            if (branchOf(url, body, khronos) == 0) {
                found = true
                break
            }
        }
        check(found) { "无法生成 App 目录请求签名" }
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = VIDEO_UA.replace("71332", "73532")
        headers["Accept"] = "application/json"
        headers["Accept-Encoding"] = "identity"
        headers["x-xs-from-web"] = "0"
        headers["sdk-version"] = "2"
        headers["x-ss-dp"] = "8662"
        headers["x-tt-store-region"] = "cn"
        headers["x-tt-store-region-src"] = "did"
        headers["x-ss-req-ticket"] = ticket.toString()
        headers["x-khronos"] = khronos.toString()
        headers["x-gorgon"] = xGorgon(query, body, khronos, ByteHelpers.random.nextInt(65536))
        headers["x-ladon"] = ByteHelpers.base64Encode(ByteHelpers.be32(khronos))
        headers["x-argus"] = ByteHelpers.base64Encode(ByteHelpers.le32(khronos))
        headers["x-helios"] = heliosLegacy(khronos)
        headers["x-medusa"] = buildMedusaLegacy(url, body, khronos, activeDeviceId, "7.3.5.32")
        headers["x-tt-dt"] = ""
        if (!cookieHeader.isNullOrBlank()) {
            headers["Cookie"] = cookieHeader
        }
        if (body != null) {
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["x-ss-stub"] = ByteHelpers.bytesToHex(ByteHelpers.md5(body), uppercase = true)
        }
        return MetadataSignedRequest(url, headers, body)
    }

    fun mixModeXor5Hex(plain: String): String {
        val bytes = plain.toByteArray(StandardCharsets.UTF_8)
        val xored = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 0x05).toByte() }
        return ByteHelpers.bytesToHex(xored, uppercase = false)
    }

    fun signedAppRequest(
        apiPath: String,
        values: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        identity: DeviceIdentity,
        host: String = "api5-normal-sinfonlineb.fqnovel.com",
        cookieHeader: String? = null,
        csrfToken: String? = null
    ): MetadataSignedRequest {
        val device = LinkedHashMap(BASE_DEVICE).apply {
            this["version_code"] = "73932"
            this["version_name"] = "7.3.9.32"
            this["manifest_version_code"] = "73932"
            this["update_version_code"] = "73932"
            this["pv_player"] = "73932"
            this["iid"] = identity.iid
            this["device_id"] = identity.deviceId
            this["cdid"] = identity.cdid
        }
        val nowMs = System.currentTimeMillis()
        val khronos = (nowMs / 1000L).toInt()
        var query = ""
        var url = ""
        var ticket = nowMs
        var found = false
        for (n in 0 until 128) {
            ticket = nowMs + n
            val params = LinkedHashMap<String, String>()
            params.putAll(values)
            params.putAll(device)
            params["ts"] = khronos.toString()
            params["_rticket"] = ticket.toString()
            query = ByteHelpers.encodeQueryParams(params.entries.map { it.key to it.value })
            url = "https://$host$apiPath?$query"
            if (branchOf(url, bodyBytes, khronos) == 0) {
                found = true
                break
            }
        }
        check(found) { "无法生成 App 请求签名" }
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = VIDEO_UA.replace("71332", "73932")
        headers["Accept"] = "application/json"
        headers["Accept-Encoding"] = "identity"
        headers["x-xs-from-web"] = "0"
        headers["sdk-version"] = "2"
        headers["passport-sdk-version"] = "5051452"
        headers["x-ss-dp"] = "8662"
        headers["x-tt-store-region"] = "cn"
        headers["x-tt-store-region-src"] = "did"
        headers["x-ss-req-ticket"] = ticket.toString()
        headers["x-khronos"] = khronos.toString()
        headers["x-gorgon"] = xGorgon(query, bodyBytes, khronos, ByteHelpers.random.nextInt(65536))
        headers["x-ladon"] = ByteHelpers.base64Encode(ByteHelpers.be32(khronos))
        headers["x-argus"] = ByteHelpers.base64Encode(ByteHelpers.le32(khronos))
        headers["x-helios"] = heliosLegacy(khronos)
        headers["x-medusa"] = buildMedusaLegacy(url, bodyBytes, khronos, identity.deviceId, "7.3.9.32")
        headers["x-tt-dt"] = ""
        if (!cookieHeader.isNullOrBlank()) {
            headers["Cookie"] = cookieHeader
        }
        if (!csrfToken.isNullOrBlank()) {
            headers["x-tt-passport-csrf-token"] = csrfToken
        }
        if (bodyBytes != null) {
            headers["Content-Type"] = contentType
            headers["x-ss-stub"] = ByteHelpers.bytesToHex(ByteHelpers.md5(bodyBytes), uppercase = true)
        }
        return MetadataSignedRequest(url, headers, bodyBytes)
    }

    fun signedSearchRequest(
        keyword: String,
        page: Int,
        searchId: String? = null,
        passback: String? = null,
        nextOffset: Int? = null,
        identity: DeviceIdentity
    ): MetadataSignedRequest {
        val device = LinkedHashMap(BASE_DEVICE).apply {
            this["version_code"] = "72232"
            this["version_name"] = "7.2.2.32"
            this["manifest_version_code"] = "72232"
            this["update_version_code"] = "72232"
            this["pv_player"] = "72232"
            this["iid"] = identity.iid
            this["device_id"] = identity.deviceId
            this["cdid"] = identity.cdid
        }
        val nowMs = System.currentTimeMillis()
        val khronos = (nowMs / 1000L).toInt()
        val offset = nextOffset ?: ((page - 1) * 20)
        var query = ""
        var url = ""
        var ticket = nowMs
        var found = false
        for (n in 0 until 128) {
            ticket = nowMs + n
            val params = LinkedHashMap<String, String>()
            params.putAll(device)
            params["query"] = keyword.take(80)
            params["count"] = "20"
            params["tab_type"] = "11"
            params["bookshelf_search_plan"] = "4"
            params["use_correct"] = "true"
            params["user_is_login"] = "0"
            if (!searchId.isNullOrEmpty()) params["search_id"] = searchId
            if (!passback.isNullOrEmpty()) params["passback"] = passback
            params["offset"] = offset.toString()
            params["ts"] = khronos.toString()
            params["_rticket"] = ticket.toString()
            query = ByteHelpers.encodeQueryParams(params.entries.map { it.key to it.value })
            url = "$SEARCH_API?$query"
            if (branchOf(url, null, khronos) == 0) {
                found = true
                break
            }
        }
        check(found) { "无法生成搜索请求签名" }
        val headers = linkedMapOf(
            "User-Agent" to SEARCH_UA,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket.toString(),
            "x-tt-request-tag" to "t=0;n=0",
            "sdk-version" to "2",
            "passport-sdk-version" to "50561",
            "x-xs-from-web" to "0",
            "x-khronos" to khronos.toString(),
            "x-ladon" to ByteHelpers.base64Encode(ByteHelpers.be32(khronos)),
            "x-argus" to ByteHelpers.base64Encode(ByteHelpers.le32(khronos)),
            "x-gorgon" to xGorgon(query, null, khronos, ByteHelpers.random.nextInt(65536)),
            "x-helios" to heliosLegacy(khronos),
            "x-medusa" to buildMedusaLegacy(url, null, khronos, identity.deviceId, "7.2.2.32"),
            "x-tt-dt" to ""
        )
        return MetadataSignedRequest(url, headers, null)
    }

    // ---- playback-signature.js (551 Argus/Ladon fallback for VIDEO_API) ----

    private const val LICENSE_ID = 1611921764L
    private val ARGUS_SIGN_KEY = ByteHelpers.hexToBytes("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val ARGUS_AES_KEY = ByteHelpers.hexToBytes("d31e3718288a1027baab59f146a09a9c")
    private val ARGUS_AES_IV = ByteHelpers.hexToBytes("ea180a0336ed352fcd24e4d50018ae54")
    private val ARGUS_MAGIC8 = ByteHelpers.hexToBytes("f2f7fcfff2f7fcff")
    private val ARGUS_TAIL9 = ByteHelpers.hexToBytes("eafb2cfe8568519154")

    private fun pad16(data: ByteArray): ByteArray {
        val pad = 16 - (data.size % 16)
        return ByteArray(data.size + pad) { i -> if (i < data.size) data[i] else pad.toByte() }
    }

    private fun simonKeyExpansion(key: ULongArray): ULongArray {
        val expanded = ULongArray(72)
        for (i in 0 until 4) expanded[i] = key[i]
        for (i in 4 until 72) {
            var tmp = ByteHelpers.ror64(expanded[i - 1], 3) xor expanded[i - 3]
            tmp = tmp xor ByteHelpers.ror64(tmp, 1)
            val bit = (0x3dc94c3a046d678bUL shr ((i - 4) % 62)) and 1UL
            expanded[i] = expanded[i - 4].inv() xor tmp xor bit xor 3UL
        }
        return expanded
    }

    private fun simonEncrypt(left: ULong, right: ULong, key: ULongArray): Pair<ULong, ULong> {
        var x = left
        var y = right
        for (rk in simonKeyExpansion(key)) {
            val prev = y
            y = x xor (ByteHelpers.rol64(y, 1) and ByteHelpers.rol64(y, 8)) xor ByteHelpers.rol64(y, 2) xor rk
            x = prev
        }
        return x to y
    }

    private fun queryParam(query: String, name: String): String? {
        for (part in query.split('&')) {
            val idx = part.indexOf('=')
            val k = if (idx < 0) part else part.substring(0, idx)
            val dk = try { URLDecoder.decode(k.replace("+", " "), StandardCharsets.UTF_8.name()) } catch (_: Exception) { k }
            if (dk == name) {
                val v = if (idx < 0) "" else part.substring(idx + 1)
                return try { URLDecoder.decode(v.replace("+", " "), StandardCharsets.UTF_8.name()) } catch (_: Exception) { v }
            }
        }
        return null
    }

    private fun argusSign(query: String, stubHex: String, ts: Long, aid: Long, deviceId: String, randField: Int): String {
        val bodyHash = if (stubHex.isEmpty()) Sm3.digest(ByteArray(16)).copyOfRange(0, 6)
        else if (Regex("^(?:[a-fA-F\\d]{2})+$").matches(stubHex)) Sm3.digest(ByteHelpers.hexToBytes(stubHex)).copyOfRange(0, 6)
        else Sm3.digest(ByteArray(0)).copyOfRange(0, 6)
        val queryHash = Sm3.digest(if (query.isEmpty()) ByteArray(16) else query.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 6)

        val p15 = ProtoWriter().putVarint(1, 1).putVarint(2, 1).putVarint(3, 1).putVarint(7, 0xc792ecccL).toByteArray()
        val p23 = ProtoWriter().putString(1, "NX551J").putVarint(2, 8196).putVarint(4, 0x80e0d800L).toByteArray()
        val bean = ProtoWriter()
            .putVarint(1, 1077940818L).putVarint(2, 2).putVarint(3, randField.toLong() and 0xFFFFFFFFL)
            .putString(4, (if (aid == 0L) 1967L else aid).toString()).putString(5, deviceId)
            .putString(6, LICENSE_ID.toString())
            .putString(7, queryParam(query, "version_name") ?: "")
            .putString(8, "v04.04.05-ov-android")
            .putVarint(9, 134744640L).putBytes(10, ByteArray(8)).putVarint(11, 0).putVarint(12, ts shl 1)
            .putBytes(13, bodyHash).putBytes(14, queryHash)
            .putBytes(15, p15).putString(16, "").putString(20, "none").putVarint(21, 738)
            .putBytes(23, p23).putVarint(25, 2)
            .toByteArray()

        val protobuf = pad16(bean)
        val key = ULongArray(4) { i -> ByteHelpers.readUInt64LE(ARGUS_SIGN_KEY, i * 8) }
        val blocks = ArrayList<ByteArray>()
        var at = 0
        while (at < protobuf.size) {
            val (l, r) = simonEncrypt(
                ByteHelpers.readUInt64LE(protobuf, at),
                ByteHelpers.readUInt64LE(protobuf, at + 8),
                key
            )
            blocks.add(ByteHelpers.le64(l))
            blocks.add(ByteHelpers.le64(r))
            at += 16
        }
        val framed = ByteHelpers.concat(ARGUS_MAGIC8, *blocks.toTypedArray(), ARGUS_TAIL9)
        for (i in 8 until framed.size) {
            framed[i] = ((framed[i].toInt() and 0xFF) xor (ARGUS_MAGIC8[i % 8].toInt() and 0xFF)).toByte()
        }
        val padded = pad16(ByteHelpers.concat(framed.reversedArray(), "ao".toByteArray(StandardCharsets.US_ASCII)))
        val enc = ByteHelpers.aesCbcEncrypt(ARGUS_AES_KEY, ARGUS_AES_IV, padded, padding = false)
        return ByteHelpers.base64Encode(ByteHelpers.concat(ByteHelpers.hexToBytes("f281"), enc))
    }

    private fun ladonEncrypt(ts: Long, aid: Long, randomBytes: ByteArray): String {
        val aidText = aid.toString()
        val digestHex = ByteHelpers.bytesToHex(ByteHelpers.md5(ByteHelpers.concat(randomBytes, aidText.toByteArray(StandardCharsets.UTF_8))))
        val ascii = digestHex.toByteArray(StandardCharsets.US_ASCII)
        val words = Array(4) { i -> ByteHelpers.readUInt64LE(ascii, i * 8) }
        val table = ULongArray(35)
        table[0] = words[0]
        val queue = ArrayDeque<ULong>().apply { addLast(words[2]); addLast(words[3]) }
        var b0 = words[0]
        var b8 = words[1]
        for (i in 0 until 34) {
            var x8 = (ByteHelpers.ror64(b8, 8) + b0) xor i.toULong()
            queue.addLast(x8)
            x8 = x8 xor ByteHelpers.rol64(b0, 3)
            table[i + 1] = x8
            b0 = x8
            b8 = queue.removeFirst()
        }
        val input = pad16("$ts-$LICENSE_ID-$aidText".toByteArray(StandardCharsets.US_ASCII))
        val out = ByteArray(input.size)
        var at = 0
        while (at < input.size) {
            var a = ByteHelpers.readUInt64LE(input, at)
            var b = ByteHelpers.readUInt64LE(input, at + 8)
            for (i in 0 until 34) {
                b = table[i] xor (a + ByteHelpers.ror64(b, 8))
                a = b xor ByteHelpers.rol64(a, 3)
            }
            ByteHelpers.writeUInt64LE(out, at, a)
            ByteHelpers.writeUInt64LE(out, at + 8, b)
            at += 16
        }
        return ByteHelpers.base64Encode(ByteHelpers.concat(randomBytes, out))
    }

    private fun v5ProfileParams(
        identity: DeviceIdentity,
        currentAppProfile: Boolean
    ): LinkedHashMap<String, String> = if (currentAppProfile) {
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74532",
            "version_name" to "7.4.5.32",
            "update_version_code" to "74532",
            "manifest_version_code" to "74532",
            "os" to "android",
            "ssmix" to "a",
            "channel" to "update_64",
            "device_platform" to "android",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "os_version" to "16",
            "os_api" to "36",
            "ac" to "wifi",
            "language" to "zh",
            "host_abi" to "arm64-v8a",
            "iid" to identity.iid,
            "cdid" to identity.cdid,
            "device_id" to identity.deviceId
        )
    } else {
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74332",
            "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64",
            "device_platform" to "android",
            "device_type" to "PGT-AN10",
            "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to identity.iid,
            "cdid" to identity.cdid,
            "device_id" to identity.deviceId
        )
    }

    fun buildV5PlaybackRequest(
        payloadJson: String,
        identity: DeviceIdentity,
        alternateHost: Boolean = false,
        hostOverride: String? = null,
        cookieHeader: String? = null,
        apiPath: String = "/novel/player/multi_video_model/v1/",
        currentAppProfile: Boolean = true
    ): MetadataSignedRequest {
        val gzipBody = ByteHelpers.gzip(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val targetHost = hostOverride ?: if (alternateHost) "api5-normal-sinfonlinea.fqnovel.com" else "api5-normal-sinfonlineb.fqnovel.com"
        val base = "https://$targetHost$apiPath"
        val signed = PlaybackV5Signer.sign(
            baseUrl = base,
            values = v5ProfileParams(identity, currentAppProfile),
            gzipBody = gzipBody
        )
        val userAgent = if (currentAppProfile && apiPath == PRELOAD_PLAYBACK_PATH) {
            "okhttp/4.12.0"
        } else {
            PLAYBACK_UA
        }
        val headers = LinkedHashMap(signed.headers).apply {
            this["User-Agent"] = userAgent
            this["Accept"] = "application/json"
            this["Content-Type"] = "application/json; charset=utf-8"
            this["Content-Encoding"] = "gzip"
            if (!cookieHeader.isNullOrBlank()) {
                this["Cookie"] = cookieHeader
            }
        }
        return MetadataSignedRequest(signed.url, headers, gzipBody)
    }

    fun buildV5SingleVideoModelGetRequest(
        vid: String,
        identity: DeviceIdentity,
        hostOverride: String = CURRENT_PLAYBACK_HOST,
        cookieHeader: String? = null
    ): MetadataSignedRequest {
        val base = "https://$hostOverride$VIDEO_MODEL_PATH"
        val values = v5ProfileParams(identity, currentAppProfile = true).apply {
            this["video_id"] = vid
            this["video_id_type"] = "0"
            this["device_level"] = "1"
            this["need_all_video_definition"] = "1"
            this["need_mp4_align"] = "0"
            this["use_os_player"] = "0"
            this["video_platform"] = "1024"
            this["source"] = "0"
        }
        val signed = PlaybackV5Signer.sign(
            baseUrl = base,
            values = values,
            gzipBody = null
        )
        val headers = LinkedHashMap(signed.headers).apply {
            this["User-Agent"] = PLAYBACK_UA
            this["Accept"] = "application/json"
            if (!cookieHeader.isNullOrBlank()) {
                this["Cookie"] = cookieHeader
            }
        }
        return MetadataSignedRequest(signed.url, headers, null)
    }

    fun buildV5VideoTabRequest(
        vid: String,
        identity: DeviceIdentity,
        hostOverride: String = VIDEO_TAB_HOSTS.first(),
        cookieHeader: String? = null
    ): MetadataSignedRequest {
        val base = "https://$hostOverride/reading/bookapi/video_tab/multi_video_model/v1/"
        val values = v5ProfileParams(identity, currentAppProfile = true).apply {
            this["video_id"] = vid
            this["video_id_type"] = "0"
            this["device_level"] = "1"
            this["need_all_video_definition"] = "1"
            this["need_mp4_align"] = "0"
            this["use_os_player"] = "0"
            this["video_platform"] = "1024"
            this["source"] = "0"
            this["from_video_id"] = ""
        }
        val signed = PlaybackV5Signer.sign(
            baseUrl = base,
            values = values,
            gzipBody = null
        )
        val headers = LinkedHashMap(signed.headers).apply {
            this["User-Agent"] = VIDEO_UA.replace("71332", "73932")
            this["Accept"] = "application/json; charset=utf-8"
            this["Accept-Language"] = "zh-CN,zh;q=0.9"
            if (!cookieHeader.isNullOrBlank()) {
                this["Cookie"] = cookieHeader
            }
        }
        return MetadataSignedRequest(signed.url, headers, null)
    }

    fun buildLegacyPlaybackRequest(
        payloadJson: String,
        identity: DeviceIdentity,
        hostOverride: String = "api5-normal-sinfonlineb.fqnovel.com",
        cookieHeader: String? = null
    ): MetadataSignedRequest {
        val gzipBody = ByteHelpers.gzip(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val nowMs = System.currentTimeMillis()
        val khronos = nowMs / 1000L
        val params = LinkedHashMap(BASE_DEVICE).apply {
            this["version_code"] = "74332"
            this["version_name"] = "7.4.3.32"
            this["manifest_version_code"] = "74332"
            this["update_version_code"] = "74332"
            this["pv_player"] = "74332"
            this["device_id"] = identity.deviceId
            this["iid"] = identity.iid
            this["cdid"] = identity.cdid
            this["ts"] = khronos.toString()
            this["_rticket"] = nowMs.toString()
        }
        val query = ByteHelpers.encodeQueryParams(params.entries.map { it.key to it.value })
        val url = "https://$hostOverride/novel/player/multi_video_model/v1/?$query"
        val stub = ByteHelpers.bytesToHex(ByteHelpers.md5(gzipBody), uppercase = false)
        val ladon = ladonEncrypt(khronos, 8662L, ByteHelpers.randomBytes(4))
        val argus = argusSign(query, stub, khronos, 8662L, identity.deviceId, ByteHelpers.randomUInt32())
        val headers = linkedMapOf(
            "User-Agent" to VIDEO_UA.replace("71332", "74332"),
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to (khronos * 1000L).toString(),
            "sdk-version" to "2",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to "5051452",
            "x-ss-dp" to "8662",
            "x-khronos" to khronos.toString(),
            "x-ladon" to ladon,
            "x-argus" to argus,
            "x-tt-dt" to "",
            "x-tt-store-region" to "cn",
            "x-tt-store-region-src" to "did",
            "Content-Type" to "application/json; charset=UTF-8",
            "Content-Encoding" to "gzip",
            "x-ss-stub" to stub,
            "Connection" to "close"
        )
        if (!cookieHeader.isNullOrBlank()) {
            headers["Cookie"] = cookieHeader
        }
        return MetadataSignedRequest(url, headers, gzipBody)
    }
}
