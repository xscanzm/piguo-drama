// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import io.github.hongguo.mobile.player.FailureCooldownTracker
import io.github.hongguo.mobile.player.SleepTimerManager
import io.github.hongguo.mobile.player.SleepTimerMode
import io.github.hongguo.mobile.player.computeFeedLowWaterMark
import io.github.hongguo.mobile.player.computeFullLocalAheadCount
import io.github.hongguo.mobile.player.computeRollingPrecacheTargetCount
import io.github.hongguo.mobile.player.isStreamHeadSufficient
import io.github.hongguo.mobile.player.selectRollingPrecacheTargets
import io.github.hongguo.mobile.ui.components.shouldShowFullscreenControls
import io.github.hongguo.mobile.ui.screens.computeFeedRetryBackoffMs
import io.github.hongguo.mobile.ui.screens.shouldBypassCacheForRetry
import io.github.hongguo.mobile.ui.screens.shouldHoldFullSeriesControlsVisible
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test
    fun resolveSeriesEndSuggestionPicksNextSeasonOrUnseenRecommended() {
        val s1 = SeriesSeasonItem(DramaSummary(seriesId = "s1", title = "传世 第1季"), 1, "第1季", false)
        val s2 = SeriesSeasonItem(DramaSummary(seriesId = "s2", title = "传世 第2季"), 2, "第2季", true)
        val s3 = SeriesSeasonItem(DramaSummary(seriesId = "s3", title = "传世 第3季"), 3, "第3季", false)
        val rSeen = RelatedDramaItem(DramaSummary(seriesId = "r1", title = "已看剧"), RelatedDramaKind.SIMILAR, "相似")
        val rFresh = RelatedDramaItem(DramaSummary(seriesId = "r2", title = "未看剧"), RelatedDramaKind.OTHER_VERSION, "同IP")

        val midBundle = RelatedDramaBundle(
            keyword = "传世",
            seasons = listOf(s1, s2, s3),
            related = listOf(rSeen, rFresh)
        )
        val midSuggestion = resolveSeriesEndSuggestion(midBundle, "s2", excludedSeriesIds = setOf("r1"))
        assertEquals("s3", midSuggestion.nextSeason?.summary?.seriesId)
        assertEquals("r2", midSuggestion.recommended?.summary?.seriesId)

        val lastBundle = RelatedDramaBundle(
            keyword = "传世",
            seasons = listOf(s1, s2.copy(isCurrent = false), s3.copy(isCurrent = true)),
            related = listOf(rSeen, rFresh)
        )
        val lastSuggestion = resolveSeriesEndSuggestion(lastBundle, "s3", excludedSeriesIds = setOf("r1"))
        assertEquals(null, lastSuggestion.nextSeason)
        assertEquals("r2", lastSuggestion.recommended?.summary?.seriesId)

        val emptySuggestion = resolveSeriesEndSuggestion(null, "s3")
        assertEquals(null, emptySuggestion.nextSeason)
        assertEquals(null, emptySuggestion.recommended)
    }

    @Test
    fun sleepTimerCountsDownEpisodesBeforeTriggering() {
        val mgr = SleepTimerManager()
        mgr.setMode(SleepTimerMode.AFTER_3_EPISODES)
        assertEquals("余3集", SleepTimerManager.formatRemaining(mgr.state.value))

        assertFalse(mgr.consumeEpisodeEndTrigger())
        assertEquals("余2集", SleepTimerManager.formatRemaining(mgr.state.value))
        assertEquals(0, mgr.state.value.triggerCount)

        assertFalse(mgr.consumeEpisodeEndTrigger())
        assertEquals("播完本集", SleepTimerManager.formatRemaining(mgr.state.value))
        assertEquals(0, mgr.state.value.triggerCount)

        assertTrue(mgr.consumeEpisodeEndTrigger())
        assertEquals(SleepTimerMode.OFF, mgr.state.value.mode)
        assertEquals("", SleepTimerManager.formatRemaining(mgr.state.value))
        assertEquals(1, mgr.state.value.triggerCount)
    }

    @Test
    fun fullscreenControlsStayHiddenAcrossEpisodeTransitionWhenHudHidden() {
        // Hidden HUD stays hidden even while the next episode is still loading/buffering
        assertFalse(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = false,
                fullscreenHudVisible = false,
                isUserPaused = false,
                isScrubbing = false,
                hasError = false
            )
        )
        // Explicit triggers reveal controls
        assertTrue(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = false,
                fullscreenHudVisible = true,
                isUserPaused = false,
                isScrubbing = false,
                hasError = false
            )
        )
        assertTrue(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = false,
                fullscreenHudVisible = false,
                isUserPaused = true,
                isScrubbing = false,
                hasError = false
            )
        )
        assertTrue(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = false,
                fullscreenHudVisible = false,
                isUserPaused = false,
                isScrubbing = true,
                hasError = false
            )
        )
        assertTrue(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = false,
                fullscreenHudVisible = false,
                isUserPaused = false,
                isScrubbing = false,
                hasError = true
            )
        )
        // Locked fullscreen or PiP hides controls regardless
        assertFalse(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = true,
                isScreenLocked = true,
                fullscreenHudVisible = true,
                isUserPaused = true,
                isScrubbing = true,
                hasError = true
            )
        )
        assertFalse(
            shouldShowFullscreenControls(
                isInPipMode = true,
                isFullscreen = false,
                isScreenLocked = false,
                fullscreenHudVisible = true,
                isUserPaused = true,
                isScrubbing = false,
                hasError = false
            )
        )
        // Normal portrait playback allows controls
        assertTrue(
            shouldShowFullscreenControls(
                isInPipMode = false,
                isFullscreen = false,
                isScreenLocked = false,
                fullscreenHudVisible = false,
                isUserPaused = false,
                isScrubbing = false,
                hasError = false
            )
        )
    }

    @Test
    fun portraitAutoCleanStaysHiddenAcrossEpisodeLoadingTransition() {
        assertFalse(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = true,
                isUserPaused = false,
                anySheetOpen = false,
                isFullscreen = false,
                hasLoadError = false
            )
        )
        assertTrue(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = false,
                isUserPaused = false,
                anySheetOpen = false,
                isFullscreen = false,
                hasLoadError = false
            )
        )
        assertTrue(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = true,
                isUserPaused = true,
                anySheetOpen = false,
                isFullscreen = false,
                hasLoadError = false
            )
        )
        assertTrue(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = true,
                isUserPaused = false,
                anySheetOpen = true,
                isFullscreen = false,
                hasLoadError = false
            )
        )
        assertTrue(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = true,
                isUserPaused = false,
                anySheetOpen = false,
                isFullscreen = true,
                hasLoadError = false
            )
        )
        assertTrue(
            shouldHoldFullSeriesControlsVisible(
                autoCleanEnabled = true,
                isUserPaused = false,
                anySheetOpen = false,
                isFullscreen = false,
                hasLoadError = true
            )
        )
    }

    @Test
    fun rollingFeedPrecachePolicyCoversTargetsTiersAndReplenishment() {
        assertEquals(15, computeRollingPrecacheTargetCount(isWifi = true, wifiOnlyPreload = true, preloadEnabled = true))
        assertEquals(4, computeRollingPrecacheTargetCount(isWifi = false, wifiOnlyPreload = false, preloadEnabled = true))
        assertEquals(0, computeRollingPrecacheTargetCount(isWifi = false, wifiOnlyPreload = true, preloadEnabled = true))
        assertEquals(0, computeRollingPrecacheTargetCount(isWifi = true, wifiOnlyPreload = false, preloadEnabled = false))

        assertEquals(10, computeFeedLowWaterMark(isWifi = true, wifiOnlyPreload = true, preloadEnabled = true))
        assertEquals(4, computeFeedLowWaterMark(isWifi = false, wifiOnlyPreload = false, preloadEnabled = true))
        assertEquals(3, computeFeedLowWaterMark(isWifi = false, wifiOnlyPreload = true, preloadEnabled = true))

        val candidates = (1..22).map { i ->
            DramaSummary(seriesId = "s$i", title = "剧$i", initialVid = "v$i")
        } + listOf(DramaSummary(seriesId = "s5", title = "重复s5", initialVid = "v5"))

        val initialWindow = selectRollingPrecacheTargets(
            upcomingCandidates = candidates,
            excludedSeriesIds = setOf("s2"),
            failedSeriesIds = setOf("s4"),
            targetCount = 15,
            fullLocalAheadCount = 2
        )
        assertEquals(15, initialWindow.size)
        // s1 and s3 become aheadIndex 0 & 1 -> full local download
        assertEquals(listOf("s1", "s3"), initialWindow.filter { it.fullLocalDownload }.map { it.summary.seriesId })
        assertEquals(listOf("s5", "s6", "s7"), initialWindow.drop(2).take(3).map { it.summary.seriesId })
        assertTrue(initialWindow.drop(2).none { it.fullLocalDownload })
        assertEquals("s17", initialWindow.last().summary.seriesId)

        // Simulate watching s1 and s3: window slides forward, promoting s5 & s6 to full download and adding s18 & s19
        val rolledWindow = selectRollingPrecacheTargets(
            upcomingCandidates = candidates.drop(3),
            excludedSeriesIds = setOf("s2", "s4"),
            failedSeriesIds = emptySet(),
            targetCount = 15,
            fullLocalAheadCount = 2
        )
        assertEquals(15, rolledWindow.size)
        assertEquals(listOf("s5", "s6"), rolledWindow.filter { it.fullLocalDownload }.map { it.summary.seriesId })
        assertEquals("s19", rolledWindow.last().summary.seriesId)
    }

    @Test
    fun rankPlayVariantsPrefersPlainStreamsAndMatchesRequestedQuality() {
        val enc720 = PlayVariant(url = "https://v.example/e720", contentKeyHex = "abcd", codec = "h264", quality = "720p", width = 720, height = 1280, userAgent = "ua")
        val plain720 = PlayVariant(url = "https://v.example/p720", contentKeyHex = null, codec = "h264", quality = "720p", width = 720, height = 1280, userAgent = "ua")
        val plain480 = PlayVariant(url = "https://v.example/p480", contentKeyHex = null, codec = "h264", quality = "480p", width = 480, height = 854, userAgent = "ua")
        val enc480 = PlayVariant(url = "https://v.example/e480", contentKeyHex = "ef01", codec = "h264", quality = "480p", width = 480, height = 854, userAgent = "ua")
        val plain360 = PlayVariant(url = "https://v.example/p360", contentKeyHex = null, codec = "h264", quality = "360p", width = 360, height = 640, userAgent = "ua")

        // AUTO prefers plain watchable (>= 480p) ahead of encrypted, and keeps high-res encrypted above 360p plain
        val autoRanked = rankPlayVariants(listOf(plain360, enc720, plain720, enc480), QualityPreference.AUTO)
        assertEquals(listOf(plain720, enc720, enc480, plain360), autoRanked)

        // 480p prefers 480p plain ahead of 480p encrypted
        val p480Ranked = rankPlayVariants(listOf(enc720, enc480, plain480), QualityPreference.P480)
        assertEquals(plain480, p480Ranked.first())
    }

    @Test
    fun canReusePrefetchedVariantsWorksForNonAutoQualities() {
        val single360 = listOf(PlayVariant(url = "https://v/360", contentKeyHex = null, codec = "h264", quality = "360p", width = 360, height = 640, userAgent = "ua"))
        val multi = listOf(
            PlayVariant(url = "https://v/720", contentKeyHex = null, codec = "h264", quality = "720p", width = 720, height = 1280, userAgent = "ua"),
            PlayVariant(url = "https://v/480", contentKeyHex = null, codec = "h264", quality = "480p", width = 480, height = 854, userAgent = "ua")
        )
        assertTrue(canReusePrefetchedVariants(single360, QualityPreference.AUTO))
        assertFalse(canReusePrefetchedVariants(single360, QualityPreference.P1080))
        assertTrue(canReusePrefetchedVariants(multi, QualityPreference.P480))
        assertTrue(canReusePrefetchedVariants(multi, QualityPreference.P1080))
    }

    @Test
    fun playbackHostSelectorPromotesFastHealthyHostAndDemotesFailedHost() {
        val selector = PlaybackHostSelector()
        val base = listOf("hostA", "hostB", "hostC", "hostD")
        assertEquals(listOf("hostA", "hostB", "hostC"), selector.orderedHosts(base, 3, nowMs = 1_000L))

        selector.recordFailure("hostA", nowMs = 1_000L)
        selector.recordSuccess("hostC", latencyMs = 80L, nowMs = 1_100L)
        selector.recordSuccess("hostB", latencyMs = 260L, nowMs = 1_200L)

        // hostC (80ms) first, hostB (260ms) second, hostD (untested) third, hostA (cooldown) last/excluded
        assertEquals(listOf("hostC", "hostB", "hostD"), selector.orderedHosts(base, 3, nowMs = 5_000L))
        assertEquals("hostA", selector.orderedHosts(base, 4, nowMs = 5_000L).last())

        // After cooldown expires (> 30s), hostA recovers behind tested healthy hosts or in base order among untested
        assertEquals(listOf("hostC", "hostB", "hostA"), selector.orderedHosts(base, 3, nowMs = 35_000L))
    }

    @Test
    fun failureCooldownTrackerExpiresAndClearsCleanly() {
        val tracker = FailureCooldownTracker(defaultCooldownMs = 90_000L)
        tracker.markFailed("s1", nowMs = 1_000L)
        tracker.markFailed("s2", cooldownMs = 5_000L, nowMs = 1_000L)

        assertTrue(tracker.isInCooldown("s1", nowMs = 50_000L))
        assertTrue(tracker.isInCooldown("s2", nowMs = 5_500L))
        assertEquals(setOf("s1", "s2"), tracker.activeFailedIds(nowMs = 5_500L))

        assertFalse(tracker.isInCooldown("s2", nowMs = 6_000L))
        assertEquals(setOf("s1"), tracker.activeFailedIds(nowMs = 6_000L))

        tracker.clear("s1")
        assertFalse(tracker.isInCooldown("s1", nowMs = 6_000L))
        assertTrue(tracker.activeFailedIds(nowMs = 6_000L).isEmpty())

        tracker.markFailed("s3", nowMs = 10_000L)
        tracker.clearAll()
        assertFalse(tracker.isInCooldown("s3", nowMs = 10_500L))
    }

    @Test
    fun streamHeadSufficiencyAcceptsShortCompleteClipsAndRejectsPartialSmallClips() {
        assertTrue(isStreamHeadSufficient(cachedBytes = 512L * 1024L, contentLength = -1L))
        assertTrue(isStreamHeadSufficient(cachedBytes = 320L * 1024L, contentLength = 320L * 1024L))
        assertFalse(isStreamHeadSufficient(cachedBytes = 320L * 1024L, contentLength = 2L * 1024L * 1024L))
        assertFalse(isStreamHeadSufficient(cachedBytes = 320L * 1024L, contentLength = -1L))
        assertFalse(isStreamHeadSufficient(cachedBytes = 4L * 1024L, contentLength = 4L * 1024L))
    }

    @Test
    fun fullLocalAheadCountAndRetryPoliciesBehaveDeterministically() {
        assertEquals(8, computeFullLocalAheadCount(isWifi = true, wifiOnlyPreload = true, preloadEnabled = true))
        assertEquals(1, computeFullLocalAheadCount(isWifi = false, wifiOnlyPreload = false, preloadEnabled = true))
        assertEquals(0, computeFullLocalAheadCount(isWifi = false, wifiOnlyPreload = true, preloadEnabled = true))
        assertEquals(0, computeFullLocalAheadCount(isWifi = true, wifiOnlyPreload = false, preloadEnabled = false))

        assertEquals(0L, computeFeedRetryBackoffMs(0))
        assertEquals(2_000L, computeFeedRetryBackoffMs(1))
        assertEquals(4_000L, computeFeedRetryBackoffMs(2))
        assertEquals(8_000L, computeFeedRetryBackoffMs(3))
        assertEquals(16_000L, computeFeedRetryBackoffMs(4))
        assertEquals(30_000L, computeFeedRetryBackoffMs(5))
        assertEquals(30_000L, computeFeedRetryBackoffMs(10))

        val consumed = mutableMapOf<String, Int>()
        assertFalse(shouldBypassCacheForRetry("s1", 0, consumed))
        assertTrue(shouldBypassCacheForRetry("s1", 1, consumed))
        consumed["s1"] = 1
        assertFalse(shouldBypassCacheForRetry("s1", 1, consumed))
        assertFalse(shouldBypassCacheForRetry("s2", 0, consumed))
        assertTrue(shouldBypassCacheForRetry("s1", 2, consumed))
    }

    @Test
    fun recommendFeedFallbackSucceedsOnPage2WhenPrimaryFails() {
        val dir = Files.createTempDirectory("feed_fallback_test").toFile()
        try {
            val identityFile = dir.resolve("device_identity.json")
            identityFile.writeText(
                """{"deviceId":"1234567890123","iid":"9876543210987","cdid":"cdid-fallback-test"}""",
                StandardCharsets.UTF_8
            )
            val appRecOffsets = ArrayList<String>()
            var mallPageCalls = 0
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val path = req.url.encodedPath
                    val jsonBody = when {
                        path.contains("get_mall_playlet_feed") -> {
                            mallPageCalls++
                            if (req.url.queryParameter("offset") == "0") {
                                """{"code":0,"data":{"has_more":true,"next_offset":10,"session_id":"sess-mall-1","cell_view":{"cell_data":[{"video_data":[{"series_id":"100001","title":"剧集一","vid":"200001"}]}]}}}"""
                            } else {
                                return@Interceptor Response.Builder()
                                    .request(req)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(500)
                                    .message("Server Error")
                                    .body("{}".toResponseBody("application/json".toMediaType()))
                                    .build()
                            }
                        }
                        path.contains("bookmall/cell/change/v1") -> {
                            appRecOffsets.add(req.url.queryParameter("offset").orEmpty())
                            """{"code":0,"data":{"has_more":true,"next_offset":12,"session_id":"sess-rec-1","cell_view":{"cell_data":[{"video_data":[{"series_id":"100002","title":"剧集二","vid":"200002"}]}]}}}"""
                        }
                        else -> """{"code":0,"data":{}}"""
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jsonBody.toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build()

            val apiClient = HongguoApiClient(mockClient, deviceIdentityFile = identityFile)
            runBlocking {
                val page1 = apiClient.fetchRecommendFeed("hot-drama", page = 1, forceRefresh = true)
                assertEquals(1, page1.page)
                assertEquals(listOf("100001"), page1.items.map { it.seriesId })
                assertTrue(page1.hasMore)

                val page2 = apiClient.fetchRecommendFeed("hot-drama", page = 2, forceRefresh = false)
                assertEquals(2, page2.page)
                assertEquals(listOf("100002"), page2.items.map { it.seriesId })
                assertEquals(listOf("0"), appRecOffsets)
            }
            assertTrue(mallPageCalls >= 2)
        } finally {
            dir.deleteRecursively()
        }
    }
}
