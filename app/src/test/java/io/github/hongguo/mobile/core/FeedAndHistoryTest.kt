// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FeedAndHistoryTest {

    @Test
    fun watchHistoryRequiresEpisode2OrExistingHistory() {
        assertFalse(shouldRecordWatchHistory(episodeIndex = 1, positionMs = 0L, alreadyInHistory = false))
        assertFalse(shouldRecordWatchHistory(episodeIndex = 1, positionMs = 45_000L, alreadyInHistory = false))
        assertTrue(shouldRecordWatchHistory(episodeIndex = 1, positionMs = 500L, alreadyInHistory = true))

        assertFalse(shouldRecordWatchHistory(episodeIndex = 2, positionMs = 2_999L, alreadyInHistory = false))
        assertTrue(shouldRecordWatchHistory(episodeIndex = 2, positionMs = 3_000L, alreadyInHistory = false))
        assertTrue(shouldRecordWatchHistory(episodeIndex = 5, positionMs = 4_000L, alreadyInHistory = false))
    }

    @Test
    fun homeFeedEndedActionAdvancesFromHighlightOrLastEpisodeCorrectly() {
        assertEquals(
            HomeFeedEndedAction.OpenNextEpisode(2),
            resolveHomeFeedEndedAction(currentEpisodeIndex = 1, totalEpisodes = 60, currentPage = 0, feedSize = 10)
        )
        assertEquals(
            HomeFeedEndedAction.OpenNextEpisode(5),
            resolveHomeFeedEndedAction(currentEpisodeIndex = 4, totalEpisodes = 60, currentPage = 1, feedSize = 10)
        )
        assertEquals(
            HomeFeedEndedAction.OpenNextEpisode(4),
            resolveHomeFeedEndedAction(currentEpisodeIndex = 3, totalEpisodes = 0, currentPage = 0, feedSize = 10)
        )
        assertEquals(
            HomeFeedEndedAction.AdvanceFeedPage(3),
            resolveHomeFeedEndedAction(currentEpisodeIndex = 60, totalEpisodes = 60, currentPage = 2, feedSize = 10)
        )
        assertEquals(
            HomeFeedEndedAction.None,
            resolveHomeFeedEndedAction(currentEpisodeIndex = 60, totalEpisodes = 60, currentPage = 9, feedSize = 10)
        )
    }

    @Test
    fun seenFeedStorePersistsLruEvictsAndClears() {
        val dir = Files.createTempDirectory("seen_feed_test").toFile()
        try {
            val file = dir.resolve("seen_feed.json")
            val store = SeenFeedStore(file, maxEntries = 3)

            store.markSeen("1001", """{"series_id":"1001"}""", nowMs = 100L)
            store.markSeen("1002", """{"series_id":"1002"}""", nowMs = 200L)
            store.markSeen("1003", "", nowMs = 300L)
            // Promote 1001 and keep its non-blank recFilterInfo
            store.markSeen("1001", "", nowMs = 400L)
            // Adding 1004 should evict 1002 (oldest remaining)
            val count = store.markSeen("1004", """{"series_id":"1004"}""", nowMs = 500L)
            assertEquals(3, count)

            val ids = store.seenIdsSnapshot()
            assertEquals(setOf("1001", "1003", "1004"), ids)
            assertEquals(
                listOf("""{"series_id":"1004"}""", """{"series_id":"1001"}"""),
                store.recentRecFilters(10)
            )

            store.flush()
            val reloaded = SeenFeedStore(file, maxEntries = 3)
            assertEquals(setOf("1001", "1003", "1004"), reloaded.seenIdsSnapshot())
            assertEquals(
                listOf("""{"series_id":"1004"}""", """{"series_id":"1001"}"""),
                reloaded.recentRecFilters(10)
            )

            reloaded.clear()
            assertEquals(0, reloaded.size())
            reloaded.flush()

            val afterClear = SeenFeedStore(file, maxEntries = 3)
            assertEquals(0, afterClear.size())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deviceRegistrarLoadsPersistedIdentityWithoutNetworkAndIgnoresCorruptFile() {
        val dir = Files.createTempDirectory("device_id_test").toFile()
        try {
            val validFile = dir.resolve("device_identity.json")
            validFile.writeText(
                """{"deviceId":"1234567890123","iid":"9876543210987","cdid":"cdid-abc-123"}""",
                StandardCharsets.UTF_8
            )
            var networkCalled = false
            val offlineClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor {
                    networkCalled = true
                    throw IOException("Network disabled in unit test")
                })
                .build()

            val registrar = DeviceRegistrar(offlineClient, validFile)
            val loaded = runBlocking { registrar.getOrRegister() }
            assertFalse("Expected persisted identity to avoid network registration", networkCalled)
            assertEquals("1234567890123", loaded.deviceId)
            assertEquals("9876543210987", loaded.iid)
            assertEquals("cdid-abc-123", loaded.cdid)

            // Corrupted or zeroed file must not crash construction and must fail cleanly on offline register
            val corruptFile = dir.resolve("corrupt_identity.json")
            corruptFile.writeText("""{"deviceId":"0","iid":"0","cdid":""}""", StandardCharsets.UTF_8)
            val corruptRegistrar = DeviceRegistrar(offlineClient, corruptFile)
            var threw = false
            try {
                runBlocking { corruptRegistrar.getOrRegister(bypassCooldown = true) }
            } catch (_: Exception) {
                threw = true
            }
            assertTrue("Expected corrupt identity not to be accepted as cached", threw)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recFilterListJsonMergesDeduplicatesCapsAndClears() {
        val apiClient = HongguoApiClient(OkHttpClient())
        val dislike1 = """{"series_id":"9001","tag":"dislike"}"""
        val dislike2 = """{"series_id":"9002","tag":"dislike"}"""
        val seenChannel = """{"series_id":"8001","tag":"channel"}"""
        val seenPersisted1 = """{"series_id":"7001","tag":"persisted"}"""
        val seenPersistedDuplicate = seenChannel

        apiClient.seedRecFilters(
            seen = listOf("bad-json", seenPersisted1, seenPersistedDuplicate),
            disliked = listOf(dislike1, dislike2)
        )
        apiClient.recordSeenRecFilter("hot-drama", seenChannel)

        val jsonStr = apiClient.buildRecFilterListJson("hot-drama", maxItems = 30, maxRawChars = 3500)
        val arr = JSONArray(jsonStr)
        assertEquals(4, arr.length())
        val seriesIds = (0 until arr.length()).map { arr.getJSONObject(it).getString("series_id") }
        // Disliked first, then channel seen, then persisted seen without duplicates
        assertEquals(setOf("9001", "9002", "8001", "7001"), seriesIds.toSet())

        // Test maxItems & maxRawChars bounds
        val cappedCount = JSONArray(apiClient.buildRecFilterListJson("hot-drama", maxItems = 2, maxRawChars = 3500))
        assertEquals(2, cappedCount.length())

        val cappedCharsStr = apiClient.buildRecFilterListJson("hot-drama", maxItems = 30, maxRawChars = 90)
        assertTrue("Expected <= 90 chars, got ${cappedCharsStr.length}", cappedCharsStr.length <= 90)

        // Test individual remove & clear operations
        apiClient.removeDislikedRecFilter(dislike2)
        apiClient.clearSeenRecFilters()
        val onlyDislike1 = JSONArray(apiClient.buildRecFilterListJson("hot-drama"))
        assertEquals(1, onlyDislike1.length())
        assertEquals("9001", onlyDislike1.getJSONObject(0).getString("series_id"))

        apiClient.clearDislikedRecFilters()
        assertEquals("", apiClient.buildRecFilterListJson("hot-drama"))
    }

    @Test
    fun videoViewportPreservesAspectRatioInFitAndCropModes() {
        // 9:16 portrait video (1080x1920) inside 20:9 phone container (1440x2950)
        val fitPortrait = computeVideoViewportSize(
            containerW = 1440f,
            containerH = 2950f,
            videoW = 1080,
            videoH = 1920,
            pixelRatio = 1f,
            cropToFill = false
        )
        assertEquals(1440f, fitPortrait.widthPx, 0.5f)
        assertEquals(2560f, fitPortrait.heightPx, 0.5f)

        val cropPortrait = computeVideoViewportSize(
            containerW = 1440f,
            containerH = 2950f,
            videoW = 1080,
            videoH = 1920,
            pixelRatio = 1f,
            cropToFill = true
        )
        assertEquals(2950f, cropPortrait.heightPx, 0.5f)
        assertEquals(2950f * (1080f / 1920f), cropPortrait.widthPx, 0.5f)

        // 16:9 landscape video (1920x1080) inside 20:9 phone container (1440x2950)
        val fitLandscape = computeVideoViewportSize(
            containerW = 1440f,
            containerH = 2950f,
            videoW = 1920,
            videoH = 1080,
            pixelRatio = 1f,
            cropToFill = false
        )
        assertEquals(1440f, fitLandscape.widthPx, 0.5f)
        assertEquals(810f, fitLandscape.heightPx, 0.5f)
    }
}
