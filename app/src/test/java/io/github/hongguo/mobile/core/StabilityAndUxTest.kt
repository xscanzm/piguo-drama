// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.net.ssl.SSLException

class StabilityAndUxTest {

    @Test
    fun friendlyErrorMessageMapsNetworkErrorsAndPreservesCleanChinese() {
        assertEquals(
            "网络连接失败，请检查网络后重试",
            friendlyErrorMessage(UnknownHostException("api.fqnovel.com"))
        )
        assertEquals(
            "网络连接失败，请检查网络后重试",
            friendlyErrorMessage(RuntimeException("outer", ConnectException("refused")))
        )
        assertEquals(
            "网络请求超时，请检查网络或点击重试",
            friendlyErrorMessage(SocketTimeoutException("timeout"))
        )
        assertEquals(
            "网络请求超时，请检查网络或点击重试",
            friendlyErrorMessage(InterruptedIOException("canceled"))
        )
        assertEquals(
            "安全连接异常，请检查网络环境或系统时间",
            friendlyErrorMessage(SSLException("handshake failed"))
        )
        assertEquals(
            "暂无可播分集",
            friendlyErrorMessage(IllegalStateException("暂无可播分集"))
        )
        assertEquals(
            "加载失败，请稍后重试",
            friendlyErrorMessage(IllegalStateException("Unable to resolve host xyz"), fallback = "加载失败，请稍后重试")
        )
        assertEquals(
            "默认文案",
            friendlyErrorMessage(null, fallback = "默认文案")
        )
    }

    @Test
    fun friendlyPlaybackErrorMapsMedia3ErrorCodes() {
        assertTrue(friendlyPlaybackError(2001, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED").contains("网络"))
        assertTrue(friendlyPlaybackError(2002, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT").contains("网络"))
        assertTrue(friendlyPlaybackError(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS").contains("片源"))
        assertTrue(friendlyPlaybackError(4001, "ERROR_CODE_DECODER_INIT_FAILED").contains("解码"))
        assertTrue(friendlyPlaybackError(1000, "ERROR_CODE_UNSPECIFIED").contains("ERROR_CODE_UNSPECIFIED"))
    }

    @Test
    fun formatRelativeWatchTimeCoversAllBuckets() {
        val now = 1_700_000_000_000L
        assertEquals("", formatRelativeWatchTime(0L, now))
        assertEquals("刚刚", formatRelativeWatchTime(now - 25_000L, now))
        assertEquals("5分钟前", formatRelativeWatchTime(now - 5 * 60_000L, now))
        assertEquals("3小时前", formatRelativeWatchTime(now - 3 * 3_600_000L, now))
        assertEquals("昨天", formatRelativeWatchTime(now - 26 * 3_600_000L, now))
        assertEquals("4天前", formatRelativeWatchTime(now - 4 * 24 * 3_600_000L, now))
        val old = formatRelativeWatchTime(now - 15 * 24 * 3_600_000L, now)
        assertTrue(Regex("^\\d{2}-\\d{2}$").matches(old))
    }

    @Test
    fun feedWarmCacheStoreDeferredFlushPersistsOnlyOnFlush() {
        val dir = Files.createTempDirectory("warm_deferred_test").toFile()
        try {
            val file = dir.resolve("warm_feed_cache.json")
            val store = FeedWarmCacheStore(file, maxEntries = 5, persistOnWrite = false)
            val entry = WarmFeedEntry(
                summary = DramaSummary(seriesId = "s10", title = "测试短剧", initialVid = "v10"),
                vid = "v10",
                episodeIndex = 1,
                qualityKey = "auto"
            )
            store.upsert(entry)
            // Deferred mode does not write synchronously on upsert
            assertFalse(file.exists())
            assertEquals(1, store.size())

            store.flush()
            assertTrue(file.exists())

            val reloaded = FeedWarmCacheStore(file, maxEntries = 5, persistOnWrite = false)
            assertEquals(setOf("s10"), reloaded.seriesIdsSnapshot())

            reloaded.clear()
            // Deferred clear also waits for flush
            assertEquals(1, FeedWarmCacheStore(file, maxEntries = 5).size())
            reloaded.flush()
            assertEquals(0, FeedWarmCacheStore(file, maxEntries = 5).size())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val out = ByteArray(total)
        ByteBuffer.wrap(out, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(total)
        System.arraycopy(type.toByteArray(StandardCharsets.ISO_8859_1), 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    @Test
    fun mp4DecryptFileMatchesDecryptBytesStreamingOutput() {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.ISO_8859_1))
        val mvhd = box("mvhd", ByteArray(16))
        val sbgp = box("sbgp", ByteArray(8))
        val moovPayload = ByteArrayOutputStream().apply {
            write(mvhd)
            write(sbgp)
        }.toByteArray()
        val moov = box("moov", moovPayload)
        val mdat = box("mdat", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val inputBytes = ByteArrayOutputStream().apply {
            write(ftyp)
            write(moov)
            write(mdat)
        }.toByteArray()

        val key = ByteArray(16) { it.toByte() }
        val expectedBytes = Mp4CencDecryptor.decryptBytes(inputBytes.copyOf(), key)

        val dir = Files.createTempDirectory("cenc_stream_test").toFile()
        try {
            val inFile = dir.resolve("in.mp4")
            val outFile = dir.resolve("out.mp4")
            inFile.writeBytes(inputBytes)
            Mp4CencDecryptor.decryptFile(inFile, outFile, key)
            assertArrayEquals(expectedBytes, outFile.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resumePlaybackPolicyRejectsEpisode1AndExpiredAndCompleted() {
        val now = 1_700_000_000_000L
        val base = ResumePlaybackState(
            seriesId = "7001",
            title = "霸总追妻",
            episodeIndex = 5,
            totalEpisodes = 60,
            positionMs = 30_000L,
            durationMs = 120_000L,
            updatedAt = now - 60_000L
        )
        assertTrue(isEligibleForAutoResume(base, autoResumeEnabled = true, nowMs = now))
        assertFalse(isEligibleForAutoResume(base, autoResumeEnabled = false, nowMs = now))
        assertFalse(isEligibleForAutoResume(null, autoResumeEnabled = true, nowMs = now))

        // Episode 1 must not trigger auto-resume
        assertFalse(isEligibleForAutoResume(base.copy(episodeIndex = 1), autoResumeEnabled = true, nowMs = now))

        // 7-day expiry boundary
        assertTrue(
            isEligibleForAutoResume(
                base.copy(updatedAt = now - RESUME_PLAYBACK_MAX_AGE_MS),
                autoResumeEnabled = true,
                nowMs = now
            )
        )
        assertFalse(
            isEligibleForAutoResume(
                base.copy(updatedAt = now - RESUME_PLAYBACK_MAX_AGE_MS - 1L),
                autoResumeEnabled = true,
                nowMs = now
            )
        )

        // Last episode < 90% is eligible, >= 90% is completed
        assertTrue(
            isEligibleForAutoResume(
                base.copy(episodeIndex = 60, positionMs = 89_000L, durationMs = 100_000L),
                autoResumeEnabled = true,
                nowMs = now
            )
        )
        assertFalse(
            isEligibleForAutoResume(
                base.copy(episodeIndex = 60, positionMs = 90_000L, durationMs = 100_000L),
                autoResumeEnabled = true,
                nowMs = now
            )
        )
    }

    @Test
    fun isHistoryCompletedMatchesLastEpisodeThreshold() {
        val mid = WatchHistoryEntry(
            seriesId = "1",
            title = "测试剧",
            totalEpisodes = 40,
            lastEpisodeIndex = 39,
            positionMs = 95_000L,
            durationMs = 100_000L
        )
        assertFalse(isHistoryCompleted(mid))
        assertFalse(isHistoryCompleted(mid.copy(lastEpisodeIndex = 40, positionMs = 85_000L)))
        assertTrue(isHistoryCompleted(mid.copy(lastEpisodeIndex = 40, positionMs = 90_000L)))
    }
}
