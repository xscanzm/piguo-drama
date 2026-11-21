// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs

class QualityV2Test {

    @Test
    fun newDramaShowsPendingAndAvoidsLowScorePenalty() {
        val now = 1_795_252_151L
        val diggs = List(60) { idx ->
            when (idx) {
                0 -> 8_000L
                1 -> 3_000L
                else -> 2_800L
            }
        }
        val report = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = diggs,
                followedCount = 20_000L,
                seriesPlayCount = 300_000L,
                createTimeSec = now - 2L * 86400L,
                nowSec = now
            )
        )
        assertNotNull(report)
        assertTrue(report!!.isPending)
        assertTrue("Expected pending new drama score >= 46, got ${report.score}", report.score >= 46f)
    }

    @Test
    fun scoreIsInvariantToEpisodeCount() {
        fun makeDiggs(epCount: Int): List<Long> = List(epCount) { idx ->
            if (idx == 0) 10_000L else 4_000L
        }
        val r30 = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = makeDiggs(30),
                followedCount = 200_000L,
                seriesPlayCount = 2_000_000L
            )
        )!!
        val r90 = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = makeDiggs(90),
                followedCount = 200_000L,
                seriesPlayCount = 2_000_000L
            )
        )!!
        val diff = abs(r30.score - r90.score)
        assertTrue("Expected episode-count diff < 3, got $diff (30=${r30.score}, 90=${r90.score})", diff < 3f)
    }

    @Test
    fun officialRatingGuardrailsAdjustTier() {
        // Medium drama with ~0.35 mid-tail retention
        val diggs = List(60) { idx ->
            when {
                idx == 0 -> 40_000L
                idx in 1..3 -> 20_000L
                else -> 7_000L
            }
        }
        val highOfficial = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = diggs,
                followedCount = 150_000L,
                seriesPlayCount = 1_500_000L,
                officialRating = 9.5f
            )
        )!!
        assertTrue("Expected official 9.5 >= 65, got ${highOfficial.score}", highOfficial.score >= 65f)

        val lowOfficial = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = diggs,
                followedCount = 150_000L,
                seriesPlayCount = 1_500_000L,
                officialRating = 7.2f
            )
        )!!
        assertTrue("Expected official 7.2 < 65, got ${lowOfficial.score}", lowOfficial.score < 65f)
    }

    @Test
    fun ongoingDramaExcludesFreshEpisodesFromTailCollapse() {
        val diggs = MutableList(50) { idx ->
            when {
                idx == 0 -> 25_000L
                idx < 47 -> 10_000L
                else -> 20L
            }
        }
        val report = DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = diggs,
                followedCount = 300_000L,
                seriesPlayCount = 3_000_000L,
                isCompleted = false,
                newlyUpdatedCount = 2
            )
        )!!
        assertFalse(report.tailCollapsed)
    }

    @Test
    fun dramaTaggerExtractsDisplayTagsGenderAndRatings() {
        assertEquals(
            listOf("家庭", "业界精英"),
            DramaTagger.pickDisplayTags(listOf("家庭", "家庭伦理", "业界精英", "现代", "亲情"))
        )
        assertEquals(
            listOf("末世", "重生"),
            DramaTagger.pickDisplayTags(listOf("末世", "重生", "异能", "求生", "都市"))
        )
        assertEquals(
            listOf("脑洞", "重生"),
            DramaTagger.pickDisplayTags(listOf("脑洞", "重生", "系统", "逆袭", "都市", "豪门"))
        )
        assertEquals(
            listOf("爱情", "先婚后爱"),
            DramaTagger.pickDisplayTags(listOf("爱情", "古风爱情", "先婚后爱", "空间", "古代"))
        )
        assertEquals(
            listOf("犯罪", "逆风翻盘"),
            DramaTagger.pickDisplayTags(listOf("剧情", "犯罪", "逆风翻盘", "大男主", "现代", "绝地逃生"))
        )
        assertEquals(
            listOf("豪门", "剧情"),
            DramaTagger.pickDisplayTags(listOf("剧情", "都市", "豪门"))
        )

        val schemaArr = JSONArray().apply {
            put(JSONObject().apply {
                put("category_id", 5030)
                put("name", "剧情")
                put("schema", "dragon8662://categorySearch?categoryId=5030&gender=1&source=detail")
            })
        }
        assertEquals(1, DramaTagger.parseGender(schemaArr))

        val ratingJson = JSONObject("""{"secondary_info_list":[{"content":"评分9.6"},{"content":"100万收藏"}]}""")
        assertEquals(9.6f, DramaTagger.parseOfficialRating(ratingJson)!!, 0.001f)

        val scoreJson = JSONObject("""{"score":8.2}""")
        assertEquals(8.2f, DramaTagger.parsePlatformScore(scoreJson)!!, 0.001f)
    }

    @Test
    fun officialRatingCachePersistsAndMergesNonNullFields() {
        val tempDir = Files.createTempDirectory("rating_cache_test").toFile()
        try {
            val cacheFile = java.io.File(tempDir, "official_ratings.json")
            val cache = OfficialRatingCache(cacheFile)
            cache.put("123456", rating = 9.4f, platformScore = 8.2f, gender = 0)
            cache.put("123456", rating = null, platformScore = null, gender = -1)
            cache.saveIfDirty()

            assertTrue(cacheFile.exists())

            val reloaded = OfficialRatingCache(cacheFile)
            val entry = reloaded.get("123456")
            assertNotNull(entry)
            assertEquals(9.4f, entry!!.rating!!, 0.001f)
            assertEquals(8.2f, entry.platformScore!!, 0.001f)
            assertEquals(0, entry.gender)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
