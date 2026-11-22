// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSeasonAndLayoutTest {

    @Test
    fun parseTitleHandlesSeasonFormatsAndPreservesYears() {
        val s1 = SeriesSeasonMatcher.parseTitle("兽语启星光第一季")
        assertEquals("兽语启星光", s1.baseTitle)
        assertEquals(1, s1.seasonNumber)
        assertEquals("第1季", s1.seasonLabel)

        val s2 = SeriesSeasonMatcher.parseTitle("清冷侍郎被奸臣之女霍霍了第1季")
        assertEquals("清冷侍郎被奸臣之女霍霍了", s2.baseTitle)
        assertEquals(1, s2.seasonNumber)
        assertEquals("第1季", s2.seasonLabel)

        val sub = SeriesSeasonMatcher.parseTitle("大奉打更人第二季：风云再起")
        assertEquals("大奉打更人", sub.baseTitle)
        assertEquals(2, sub.seasonNumber)
        assertEquals("第2季", sub.seasonLabel)

        val partUp = SeriesSeasonMatcher.parseTitle("重生七零（上）")
        val partDown = SeriesSeasonMatcher.parseTitle("重生七零（下）")
        assertEquals(partUp.normalizedBase, partDown.normalizedBase)
        assertEquals(1, partUp.seasonNumber)
        assertEquals(3, partDown.seasonNumber)

        val bu2 = SeriesSeasonMatcher.parseTitle("某某大剧第二部")
        assertEquals("某某大剧", bu2.baseTitle)
        assertEquals(2, bu2.seasonNumber)
        assertEquals("第2部", bu2.seasonLabel)

        // Years inside titles must NOT be mistaken for a season marker
        val yearTitle = SeriesSeasonMatcher.parseTitle("冰封1976：我带妹妹闯出活路")
        assertNull(yearTitle.seasonNumber)
        assertEquals("", yearTitle.seasonLabel)
        assertEquals("冰封1976：我带妹妹闯出活路", yearTitle.baseTitle)
    }

    @Test
    fun matchAndSortSeasonsFiltersNoiseAndSupportsReverseNavigation() {
        val season3 = DramaSummary(seriesId = "7706", title = "兽语启星光第三季", episodeCount = 156)
        val candidates = listOf(
            DramaSummary(seriesId = "7706", title = "兽语启星光第三季", episodeCount = 156),
            DramaSummary(seriesId = "7695", title = "兽语启星光第二季", episodeCount = 171),
            DramaSummary(seriesId = "7671", title = "兽语启星光第一季", episodeCount = 147),
            DramaSummary(seriesId = "7683", title = "蛇宠心尖", episodeCount = 34),
            DramaSummary(seriesId = "7703", title = "雪落苍苏", episodeCount = 113)
        )

        // Starting from Season 3 still lists Season 1 -> 2 -> 3 for jumping back
        val sorted = SeriesSeasonMatcher.matchAndSortSeasons(season3, candidates)
        assertEquals(listOf("7671", "7695", "7706"), sorted.map { it.summary.seriesId })
        assertEquals(listOf("第1季", "第2季", "第3季"), sorted.map { it.displayLabel })
        assertFalse(sorted[0].isCurrent)
        assertFalse(sorted[1].isCurrent)
        assertTrue(sorted[2].isCurrent)

        // Base title + 第2季 without explicit 第1季 labels the unnumbered one as 第1季
        val baseCurrent = DramaSummary(seriesId = "100", title = "天才萌宝归来", episodeCount = 80)
        val paired = SeriesSeasonMatcher.matchAndSortSeasons(
            current = baseCurrent,
            searchCandidates = listOf(
                DramaSummary(seriesId = "200", title = "天才萌宝归来第二季", episodeCount = 90),
                DramaSummary(seriesId = "999", title = "天才萌宝归来第三季番外之别剧", episodeCount = 50),
                DramaSummary(seriesId = "888", title = "重生囤百亿物资，突击小队通关末世第十季", episodeCount = 184)
            )
        )
        assertEquals(listOf("100", "200"), paired.map { it.summary.seriesId })
        assertEquals(listOf("第1季", "第2季"), paired.map { it.displayLabel })

        // Single drama yields empty list
        val solo = SeriesSeasonMatcher.matchAndSortSeasons(
            current = DramaSummary(seriesId = "7686", title = "首辅娇娘", episodeCount = 69),
            searchCandidates = listOf(
                DramaSummary(seriesId = "7690", title = "首辅大人的养崽记事！", episodeCount = 91)
            )
        )
        assertTrue(solo.isEmpty())
    }

    @Test
    fun fullscreenButtonPlacementNeverOverlapsTopOrBottomOverlay() {
        // Case 1: 16:9 video in tall screen with plenty of space below video
        val tallPhone = computeFullscreenButtonPlacement(
            containerHDp = 880f,
            videoRenderedHDp = 225f,
            topBarClearanceDp = 80f,
            bottomOverlayClearanceDp = 220f
        )
        assertEquals(FullscreenButtonAnchor.BELOW_VIDEO, tallPhone.anchor)
        assertTrue(tallPhone.topOffsetDp >= 552.5f + 12f)
        assertTrue(tallPhone.topOffsetDp + 36f <= 880f - 220f)

        // Case 2: 4:3/wide aspect on compact screen where below-video collides with bottom overlay -> move above video
        val compactOverlap = computeFullscreenButtonPlacement(
            containerHDp = 720f,
            videoRenderedHDp = 300f,
            topBarClearanceDp = 80f,
            bottomOverlayClearanceDp = 250f
        )
        assertEquals(FullscreenButtonAnchor.ABOVE_VIDEO, compactOverlap.anchor)
        assertTrue(compactOverlap.topOffsetDp >= 80f)
        assertTrue(compactOverlap.topOffsetDp + 36f <= 210f - 12f)

        // Case 3: Extremely tight screen where neither below nor above fits cleanly -> top-right inside video
        val ultraTight = computeFullscreenButtonPlacement(
            containerHDp = 540f,
            videoRenderedHDp = 260f,
            topBarClearanceDp = 120f,
            bottomOverlayClearanceDp = 260f
        )
        assertEquals(FullscreenButtonAnchor.INSIDE_VIDEO_TOP_END, ultraTight.anchor)
        assertTrue(ultraTight.topOffsetDp >= 120f)
    }
}
