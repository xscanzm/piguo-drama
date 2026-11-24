// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolParityTest {

    @Test
    fun sm3MatchesReferenceVectors() {
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            ByteHelpers.bytesToHex(Sm3.digest(ByteArray(0)))
        )
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            ByteHelpers.bytesToHex(Sm3.digest("abc".toByteArray(StandardCharsets.UTF_8)))
        )
    }

    @Test
    fun v5SignerMatchesDesktopReferenceTicketAndStub() {
        val nodeGzipBody = ByteHelpers.hexToBytes("1f8b080000000000000aab562a492d2e51b232ac0500b4747afa0a000000")
        val values = linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74332",
            "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64",
            "device_platform" to "android",
            "device_type" to "PGT-AN10",
            "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to "1234567890",
            "cdid" to "11111111-1111-4111-8111-111111111111",
            "device_id" to "9876543210"
        )
        val signed = PlaybackV5Signer.sign(
            baseUrl = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/player/v1",
            values = values,
            gzipBody = nodeGzipBody,
            nowMs = 1700000000000L
        )
        assertEquals("1700000000", signed.headers["x-khronos"])
        assertNull(signed.headers["x-ss-req-ticket"])
        assertEquals("654AE4E85BFDF40203C28E3971496134", signed.headers["x-ss-stub"])
        assertTrue(signed.url.endsWith("&_rticket=1700000000003"))
        assertEquals(36, ByteHelpers.base64Decode(signed.headers.getValue("x-helios")).size)
        assertEquals(481, ByteHelpers.base64Decode(signed.headers.getValue("x-medusa")).size)
    }

    @Test
    fun spadeUrlDecryptMatchesNodeVector() {
        val decrypted = SpadeDecryptor.decryptSpadeUrl(
            "qAABAPW4u/AiZrjqQ7olmwLbECm5AqNSjJKSq+RWAOeDoqixS4m7nGwJvm0XzxbeQxyaPA==",
            "0123456789abcdef".toByteArray(StandardCharsets.UTF_8)
        )
        assertEquals("https://v1.douyinvod.com/test.mp4", decrypted)
    }

    @Test
    fun metadataAndSearchSigningProduceValidHeaders() {
        val meta = LegacySigner.signedMetadataRequest(
            "/reading/bookapi/bookmall/cell/change/v",
            mapOf("selected_items" to "all"),
            null
        )
        assertTrue(meta.headers["x-gorgon"]?.startsWith("8404") == true)
        assertTrue(!meta.headers["x-medusa"].isNullOrEmpty())

        val search = LegacySigner.signedSearchRequest(
            keyword = "总裁",
            page = 1,
            identity = DeviceIdentity("9876543210", "1234567890", "11111111-1111-4111-8111-111111111111")
        )
        assertTrue(search.url.contains("query=%E6%80%BB%E8%A3%81"))
        assertTrue(search.headers["x-gorgon"]?.startsWith("8404") == true)
    }

    private fun box(typ: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        ByteBuffer.wrap(out, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(out.size)
        System.arraycopy(typ.toByteArray(StandardCharsets.ISO_8859_1), 0, out, 4, 4)
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    @Test
    fun mp4CencDecryptorDecryptsSamplesAndRebuildsMoov() {
        val key = ByteHelpers.hexToBytes("00112233445566778899aabbccddeeff")
        val nonce = ByteHelpers.hexToBytes("1122334455667788")
        val plainSample = "HELLO_CENC_VIDEO_SAMPLE_PAYLOAD!".toByteArray(StandardCharsets.UTF_8)

        val iv = ByteArray(16).also { System.arraycopy(nonce, 0, it, 0, 8) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encSample = cipher.doFinal(plainSample)

        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.ISO_8859_1))

        val avcC = box("avcC", byteArrayOf(1, 66, 0, 30))
        val sinf = box("sinf", box("frma", "avc1".toByteArray(StandardCharsets.ISO_8859_1)))
        val encvHeader = ByteArray(78)
        val encvBody = ByteHelpers.concat(encvHeader, avcC, sinf)
        val encv = box("encv", encvBody)
        val stsd = box("stsd", ByteHelpers.concat(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), encv))

        val stsz = box(
            "stsz",
            ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0) // version/flags
                putInt(0) // sample_size=0
                putInt(1) // count=1
            }.array() + ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(encSample.size).array()
        )
        val stsc = box(
            "stsc",
            ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0); putInt(1); putInt(1); putInt(1); putInt(1)
            }.array()
        )
        val senc = box(
            "senc",
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply { putInt(0); putInt(1) }.array() + nonce
        )

        // Build placeholder stco, then calculate actual mdat payload offset
        fun buildMp4WithStco(chunkOffset: Int): ByteArray {
            val stco = box(
                "stco",
                ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0); putInt(1); putInt(chunkOffset)
                }.array()
            )
            val stbl = box("stbl", ByteHelpers.concat(stsd, stsz, stsc, stco, senc))
            val moov = box("moov", box("trak", box("mdia", box("minf", stbl))))
            val mdat = box("mdat", encSample)
            return ByteHelpers.concat(ftyp, moov, mdat)
        }

        val probe = buildMp4WithStco(0)
        val mdatOffset = probe.size - encSample.size
        val encryptedMp4 = buildMp4WithStco(mdatOffset)

        val decryptedMp4 = Mp4CencDecryptor.decryptBytes(encryptedMp4, key)
        val text = String(decryptedMp4, StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("avc1"))
        assertFalse(text.contains("encv"))
        assertFalse(text.contains("senc"))
        assertFalse(text.contains("sinf"))

        // Verify decrypted sample bytes are at the end of mdat
        val actualSample = decryptedMp4.copyOfRange(decryptedMp4.size - plainSample.size, decryptedMp4.size)
        assertArrayEquals(plainSample, actualSample)
    }

    @Test
    fun qualityEvaluatorGradesHitsTailCollapseDarkHorseAndShortClips() {
        // 1. Calibrated S-grade hit (mirrors live sample ~945k fav, ~30k avg digg, high mid-tail retention)
        val sDiggs = MutableList(90) { i ->
            when {
                i == 0 -> 98_510L
                i == 1 -> 49_611L
                else -> 32_000L
            }
        }
        val sReport = DramaQualityEvaluator.evaluate(
            episodeDiggs = sDiggs,
            followedCount = 945_254L,
            seriesPlayCount = 2_120_105L
        )
        assertNotNull(sReport)
        assertEquals("S", sReport!!.grade)
        assertTrue(sReport.score >= 82f)
        assertFalse(sReport.tailCollapsed)

        // 2. Tail-collapse guardrail: huge ep1/ep2 + fav, but mid-tail plunges below 20% of ep2 -> capped <= 79
        val collapsedDiggs = MutableList(80) { i ->
            when {
                i == 0 -> 260_000L
                i == 1 -> 120_000L
                i < 16 -> 60_000L
                else -> 12_000L
            }
        }
        val collapsed = DramaQualityEvaluator.evaluate(
            episodeDiggs = collapsedDiggs,
            followedCount = 1_500_000L,
            seriesPlayCount = 20_000_000L
        )!!
        assertTrue(collapsed.tailCollapsed)
        assertTrue(collapsed.score <= 79.0f)
        assertTrue(collapsed.grade != "S")

        // 3. Dark-horse badge: fav < 300k, play >= 3M, retention >= 45%
        val darkDiggs = MutableList(60) { i ->
            when (i) {
                0 -> 28_000L
                1 -> 15_000L
                else -> 9_000L
            }
        }
        val darkHorse = DramaQualityEvaluator.evaluate(
            episodeDiggs = darkDiggs,
            followedCount = 150_000L,
            seriesPlayCount = 5_000_000L
        )!!
        assertTrue(darkHorse.isDarkHorse)

        // 4. Too few episodes (< 5) -> null
        assertNull(DramaQualityEvaluator.evaluate(listOf(1000L, 900L, 800L, 700L), 10000L, 50000L))

        // 5. Compact count formatter
        assertEquals("850", DramaQualityEvaluator.formatCompactCount(850L))
        assertEquals("3.2万", DramaQualityEvaluator.formatCompactCount(31_912L))
        assertEquals("1.20亿", DramaQualityEvaluator.formatCompactCount(120_000_000L))
    }

    @Test
    fun v5PlaybackRequestDefaultsToWorkingMultiVideoModelV1Path() {
        val identity = DeviceIdentity("9876543210", "1234567890", "11111111-1111-4111-8111-111111111111")
        val payload = """{"mixed_video_id_map":{"1004":["7673348226409319448"]},"dr_scene":"player"}"""

        val defaultReq = LegacySigner.buildV5PlaybackRequest(payloadJson = payload, identity = identity)
        assertTrue(defaultReq.url.contains("/novel/player/multi_video_model/v1/?"))
        assertTrue(!defaultReq.headers["x-medusa"].isNullOrEmpty())
        assertTrue(!defaultReq.headers["x-helios"].isNullOrEmpty())
        assertTrue(!defaultReq.headers["x-khronos"].isNullOrEmpty())
        assertTrue(!defaultReq.headers["x-ss-stub"].isNullOrEmpty())

        val fallbackReq = LegacySigner.buildV5PlaybackRequest(
            payloadJson = payload,
            identity = identity,
            apiPath = "/novel/player/multi_video_model/player/v1"
        )
        assertTrue(fallbackReq.url.contains("/novel/player/multi_video_model/player/v1?"))
        assertTrue(!fallbackReq.headers["x-medusa"].isNullOrEmpty())
    }

    @Test
    fun v5VideoTabGetOmitsBodyHeadersAndUsesEmptyBodyDigest() {
        val identity = DeviceIdentity("9876543210", "1234567890", "11111111-1111-4111-8111-111111111111")
        val req = LegacySigner.buildV5VideoTabRequest(vid = "7680666554572229656", identity = identity)

        assertTrue(req.url.contains("https://api5-normal-sinfonlinec.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/?"))
        assertTrue(req.url.contains("version_code=74532"))
        assertTrue(req.url.contains("video_id=7680666554572229656"))
        assertNull(req.body)
        assertTrue(!req.headers["x-khronos"].isNullOrEmpty())
        assertTrue(!req.headers["x-helios"].isNullOrEmpty())
        assertTrue(!req.headers["x-medusa"].isNullOrEmpty())
        assertTrue(!req.headers["User-Agent"].isNullOrEmpty())
        assertTrue(!req.headers["Accept"].isNullOrEmpty())
        assertNull(req.headers["x-ss-stub"])
        assertNull(req.headers["x-ss-req-ticket"])
        assertNull(req.headers["Content-Type"])
        assertNull(req.headers["Content-Encoding"])
    }

    @Test
    fun v5PreloadPlaybackUsesField21Of312AndCurrentAppProfile() {
        assertEquals(312L, PlaybackV5Signer.FIELD_21)
        val identity = DeviceIdentity("9876543210", "1234567890", "11111111-1111-4111-8111-111111111111")
        val payload = """{"biz_param":{"device_level":2},"mixed_video_id_map":{"1004":["7671977933363235864"]},"dr_scene":"preload"}"""

        val req = LegacySigner.buildV5PlaybackRequest(
            payloadJson = payload,
            identity = identity,
            hostOverride = LegacySigner.CURRENT_PLAYBACK_HOST,
            cookieHeader = null,
            apiPath = LegacySigner.PRELOAD_PLAYBACK_PATH,
            currentAppProfile = true
        )
        assertTrue(req.url.startsWith("https://reading.snssdk.com/novel/player/multi_video_model/preload/v1?"))
        assertTrue(req.url.contains("version_code=74532"))
        assertTrue(req.url.contains("version_name=7.4.5.32"))
        assertTrue(!req.headers["x-medusa"].isNullOrEmpty())
        assertTrue(!req.headers["x-helios"].isNullOrEmpty())
        assertTrue(!req.headers["x-khronos"].isNullOrEmpty())
        assertTrue(!req.headers["x-ss-stub"].isNullOrEmpty())
        assertNull(req.headers["Cookie"])
    }

    private fun buildSyntheticSpadeA(keyHex: String): String {
        val plain = ByteArray(33)
        plain[0] = 'x'.code.toByte()
        System.arraycopy(keyHex.toByteArray(StandardCharsets.US_ASCII), 0, plain, 1, 32)
        val raw = ByteArray(34)
        var a = 85
        var b = 246
        for (i in 0 until 33) {
            val previous = if ((i and 1) != 0) a else b
            val pop = Integer.bitCount(i)
            val target = plain[i].toInt() and 0xFF
            val old = (previous xor ((target + 21 + pop) and 0xFF)) and 0xFF
            if ((i and 1) != 0) a = old else b = old
            raw[i + 1] = old.toByte()
        }
        raw[0] = ((raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)).toByte()
        return ByteHelpers.base64Encode(raw)
    }

    @Test
    fun v5SingleVideoModelPostAndGetRequestsUse74532ProfileAndStableUa() {
        val identity = DeviceIdentity("9876543210", "1234567890", "11111111-1111-4111-8111-111111111111")
        val vid = "7678630460108327961"
        val payload = """{"biz_param":{"device_level":3,"source":9,"video_platform":3},"content_type":1004,"video_id":"$vid"}"""

        val postReq = LegacySigner.buildV5PlaybackRequest(
            payloadJson = payload,
            identity = identity,
            hostOverride = LegacySigner.CURRENT_PLAYBACK_HOST,
            cookieHeader = null,
            apiPath = LegacySigner.VIDEO_MODEL_PATH,
            currentAppProfile = true
        )
        assertTrue(postReq.url.contains("/novel/player/video_model/v1/?"))
        assertTrue(postReq.url.contains("version_code=74532"))
        assertTrue(postReq.url.contains("version_name=7.4.5.32"))
        assertEquals(LegacySigner.PLAYBACK_UA, postReq.headers["User-Agent"])
        assertTrue(!postReq.headers["x-medusa"].isNullOrEmpty())
        assertTrue(!postReq.headers["x-helios"].isNullOrEmpty())
        assertTrue(!postReq.headers["x-khronos"].isNullOrEmpty())
        assertTrue(!postReq.headers["x-ss-stub"].isNullOrEmpty())
        assertNull(postReq.headers["Cookie"])

        val prevHttpAgent = System.getProperty("http.agent")
        try {
            System.setProperty("http.agent", "Dalvik/2.1.0 (Linux; U; Android 15; Test)")
            val preloadReq = LegacySigner.buildV5PlaybackRequest(
                payloadJson = payload,
                identity = identity,
                hostOverride = LegacySigner.CURRENT_PLAYBACK_HOST,
                cookieHeader = null,
                apiPath = LegacySigner.PRELOAD_PLAYBACK_PATH,
                currentAppProfile = true
            )
            assertEquals("okhttp/4.12.0", preloadReq.headers["User-Agent"])
        } finally {
            if (prevHttpAgent == null) {
                System.clearProperty("http.agent")
            } else {
                System.setProperty("http.agent", prevHttpAgent)
            }
        }

        val getReq = LegacySigner.buildV5SingleVideoModelGetRequest(
            vid = vid,
            identity = identity,
            hostOverride = LegacySigner.CURRENT_PLAYBACK_HOST,
            cookieHeader = null
        )
        assertTrue(getReq.url.contains("/novel/player/video_model/v1/?"))
        assertTrue(getReq.url.contains("video_id=$vid"))
        assertTrue(getReq.url.contains("version_code=74532"))
        assertNull(getReq.body)
        assertEquals(LegacySigner.PLAYBACK_UA, getReq.headers["User-Agent"])
        assertTrue(!getReq.headers["x-medusa"].isNullOrEmpty())
        assertTrue(!getReq.headers["x-helios"].isNullOrEmpty())
        assertTrue(!getReq.headers["x-khronos"].isNullOrEmpty())
        assertNull(getReq.headers["Cookie"])
    }

    @Test
    fun resolvePlayResponseParsesSingleVideoModelAndMultiVideoModelShapes() {
        val apiClient = HongguoApiClient(OkHttpClient())
        val expectedKey = "00112233445566778899aabbccddeeff"
        val spadeA = buildSyntheticSpadeA(expectedKey)
        val vid = "7678630460108327961"

        val singleModelJson = """
            {
              "video_id": "v02Test",
              "video_duration": 100.0,
              "video_list": [
                {
                  "video_meta": {
                    "codec_type": "bytevc2",
                    "definition": "720p",
                    "vwidth": 720,
                    "vheight": 1280
                  },
                  "encrypt_info": {
                    "encrypt": true,
                    "spade_a": "$spadeA"
                  },
                  "main_url": "https://v1.qznovelvod.com/bytevc2.mp4"
                },
                {
                  "video_meta": {
                    "codec_type": "bytevc1",
                    "definition": "1080p",
                    "vwidth": 1080,
                    "vheight": 1920
                  },
                  "encrypt_info": {
                    "encrypt": true,
                    "spade_a": "$spadeA"
                  },
                  "main_url": "https://v2.qznovelvod.com/hevc1080.mp4",
                  "backup_url": "https://v3.fqnovelvod.com/hevc1080_bak.mp4"
                }
              ]
            }
        """.trimIndent()

        val singleResp = JSONObject().apply {
            put("code", 0)
            put("data", JSONObject().apply {
                put("video_model", singleModelJson)
            })
        }
        val singleResult = apiClient.resolvePlayResponse(singleResp, vid, QualityPreference.AUTO)
        assertEquals(vid, singleResult.vid)
        assertEquals(2, singleResult.variants.size)
        assertEquals("hevc", singleResult.variants[0].codec)
        assertEquals("1080p", singleResult.variants[0].quality)
        assertEquals(expectedKey, singleResult.variants[0].contentKeyHex)
        assertEquals("https://v2.qznovelvod.com/hevc1080.mp4", singleResult.variants[0].url)

        val multiResp = JSONObject().apply {
            put("code", 0)
            put("data", JSONObject().apply {
                put("1004", JSONObject().apply {
                    put(vid, JSONObject().apply {
                        put("video_model", JSONObject(singleModelJson))
                    })
                })
            })
        }
        val multiResult = apiClient.resolvePlayResponse(multiResp, vid, QualityPreference.AUTO)
        assertEquals(vid, multiResult.vid)
        assertEquals(2, multiResult.variants.size)
        assertEquals("hevc", multiResult.variants[0].codec)
        assertEquals("1080p", multiResult.variants[0].quality)
        assertEquals(expectedKey, multiResult.variants[0].contentKeyHex)
    }
}
