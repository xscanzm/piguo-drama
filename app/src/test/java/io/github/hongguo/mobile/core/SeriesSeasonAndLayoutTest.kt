// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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

        val yunmiao1 = SeriesSeasonMatcher.parseTitle("云渺1：我修仙多年强亿点怎么了")
        assertEquals("云渺", yunmiao1.baseTitle)
        assertEquals(1, yunmiao1.seasonNumber)
        assertEquals("第1季", yunmiao1.seasonLabel)

        val yunmiao5 = SeriesSeasonMatcher.parseTitle("云渺5：天道终局")
        assertEquals("云渺", yunmiao5.baseTitle)
        assertEquals(5, yunmiao5.seasonNumber)

        val duLiao = SeriesSeasonMatcher.parseTitle("渡辽第一季之山河起甲")
        assertEquals("渡辽", duLiao.baseTitle)
        assertEquals(1, duLiao.seasonNumber)

        val bracketOne = SeriesSeasonMatcher.parseTitle("神秘复苏（一）")
        assertEquals("神秘复苏", bracketOne.baseTitle)
        assertEquals(1, bracketOne.seasonNumber)

        // Years inside titles must NOT be mistaken for a season marker
        val yearTitle = SeriesSeasonMatcher.parseTitle("冰封1976：我带妹妹闯出活路")
        assertNull(yearTitle.seasonNumber)
        assertEquals("", yearTitle.seasonLabel)
        assertEquals("冰封1976：我带妹妹闯出活路", yearTitle.baseTitle)
        assertEquals("冰封1976", SeriesSeasonMatcher.extractShortPrefixKeyword(yearTitle.baseTitle))
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

        // Single drama yields empty season list, but retains similar recommendations
        val soloResult = SeriesSeasonMatcher.classifyRelatedDramas(
            current = DramaSummary(seriesId = "7686", title = "首辅娇娘", episodeCount = 69),
            searchCandidates = listOf(
                DramaSummary(seriesId = "7690", title = "首辅大人的养崽记事！", episodeCount = 91)
            )
        )
        assertTrue(soloResult.first.isEmpty())
        assertEquals(listOf("7690"), soloResult.second.map { it.summary.seriesId })
    }

    @Test
    fun classifyRelatedDramasSeparatesOtherVersionsHandlesFuzzyPronounsAndAllElevenSeasons() {
        val mainSeasons = listOf(
            DramaSummary(seriesId = "101", title = "持械入宋：第一季", episodeCount = 100),
            DramaSummary(seriesId = "102", title = "持械入宋：第二季", episodeCount = 100),
            DramaSummary(seriesId = "103", title = "持械入宋：第三季", episodeCount = 100),
            DramaSummary(seriesId = "104", title = "持械入宋：第四季", episodeCount = 100),
            DramaSummary(seriesId = "105", title = "持械入宋：第五季", episodeCount = 100),
            DramaSummary(seriesId = "106", title = "持械入宋：第六季", episodeCount = 100),
            DramaSummary(seriesId = "107", title = "持械入宋：第七季", episodeCount = 100),
            DramaSummary(seriesId = "108", title = "持械入宋：第八季", episodeCount = 100),
            DramaSummary(seriesId = "109", title = "持械入宋：第九季", episodeCount = 100),
            DramaSummary(seriesId = "110", title = "持械入宋：第十季", episodeCount = 100),
            DramaSummary(seriesId = "111", title = "持械入宋：第十一季", episodeCount = 113)
        )
        val otherVersions = listOf(
            DramaSummary(seriesId = "201", title = "持械入宋", episodeCount = 138),
            DramaSummary(seriesId = "202", title = "持械入宋第二季", episodeCount = 143),
            DramaSummary(seriesId = "203", title = "持械入宋第三季", episodeCount = 341)
        )
        val similar = DramaSummary(seriesId = "301", title = "持械入明：我有无限军饷", episodeCount = 155)
        val promoZero = DramaSummary(seriesId = "999", title = "《持械入宋》第十二季火热预约中", episodeCount = 0)
        val allCandidates = mainSeasons.reversed() + otherVersions + similar + promoZero

        // Entering from Season 1 discovers all 11 seasons and puts other versions at top of related
        val fromS1 = SeriesSeasonMatcher.classifyRelatedDramas(
            current = DramaSummary(seriesId = "101", title = "持械入宋", episodeCount = 100),
            searchCandidates = allCandidates,
            officialSeriesIds = setOf("102", "103", "104"),
            officialOtherVersionIds = setOf("201", "202", "203")
        )
        assertEquals((101..111).map { it.toString() }, fromS1.first.map { it.summary.seriesId })
        assertEquals(listOf("201", "202", "203", "301"), fromS1.second.map { it.summary.seriesId })
        assertEquals(RelatedDramaKind.OTHER_VERSION, fromS1.second[0].kind)
        assertEquals(RelatedDramaKind.SIMILAR, fromS1.second[3].kind)

        // Entering into the other-version production isolates its own 3 seasons cleanly
        val fromOther = SeriesSeasonMatcher.classifyRelatedDramas(
            current = otherVersions[0],
            searchCandidates = allCandidates,
            officialSeriesIds = setOf("102", "103", "104"),
            officialOtherVersionIds = setOf("201", "202", "203")
        )
        assertEquals(listOf("201", "202", "203"), fromOther.first.map { it.summary.seriesId })

        // Fuzzy pronoun equivalence & 1-char edit distance
        val highway = SeriesSeasonMatcher.classifyRelatedDramas(
            current = DramaSummary(seriesId = "h1", title = "公路求生榜一说他要走到公路尽头", episodeCount = 132),
            searchCandidates = listOf(
                DramaSummary(seriesId = "h2", title = "公路求生榜一说他要走到公路尽头第二季", episodeCount = 172),
                DramaSummary(seriesId = "h3", title = "公路求生榜一说她要走到公路尽头第三季", episodeCount = 207),
                DramaSummary(seriesId = "h0", title = "《公路求生榜一说她要走到公路尽头》第二季预约", episodeCount = 0),
                DramaSummary(seriesId = "h4", title = "公路求生：从面包车开始无限升级", episodeCount = 135)
            )
        )
        assertEquals(listOf("h1", "h2", "h3"), highway.first.map { it.summary.seriesId })
        assertEquals(listOf("h4"), highway.second.map { it.summary.seriesId })

        val oneCharDiff = SeriesSeasonMatcher.matchAndSortSeasons(
            current = DramaSummary(seriesId = "x1", title = "修仙满级回现代，我让全家横着走了", episodeCount = 100),
            searchCandidates = listOf(
                DramaSummary(seriesId = "x2", title = "修仙满级回现代，我让全家横着走！第一季", episodeCount = 120)
            )
        )
        assertEquals(listOf("x1", "x2"), oneCharDiff.map { it.summary.seriesId })
    }

    @Test
    fun parseAppSearchTabExtractsLynxSeriesOtherVersionsAndFiltersZeroEpisodePromos() {
        val lynxSeriesJson = JSONObject().apply {
            put("cell_name", "系列剧")
            put("cell_url", "dragon8662://lynxview?from=same_ip_same_series_land_page")
            put("cell_data", JSONArray().apply {
                put(JSONObject().put("video_data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("series_id", "7676706788724460568")
                        put("title", "持械入宋：第二季")
                        put("episode_cnt", 100)
                        put("cover", "https://example.com/s2.jpg")
                    })
                }))
            })
        }.toString()

        val lynxOtherJson = JSONObject().apply {
            put("cell_name", "其他版本")
            put("cell_url", "dragon8662://lynxview?from=same_ip_diff_series_land_page")
            put("cell_data", JSONArray().apply {
                put(JSONObject().put("video_data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("series_id", "7666404568275635224")
                        put("title", "持械入宋")
                        put("episode_cnt", 138)
                        put("cover", "https://example.com/other.jpg")
                    })
                }))
            })
        }.toString()

        val tab = JSONObject().apply {
            put("tab_type", 11)
            put("has_more", true)
            put("search_id", "sid_123")
            put("passback", "pb_123")
            put("next_offset", 6)
            put("data", JSONArray().apply {
                put(JSONObject().put("video_data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("series_id", "7671966197985856536")
                        put("title", "持械入宋")
                        put("episode_cnt", 100)
                        put("cover", "https://example.com/s1.jpg")
                    })
                }))
                put(JSONObject().put("video_data", JSONArray().apply {
                    put(JSONObject().apply {
                        put("series_id", "7698694098143677465")
                        put("title", "《持械入宋》预约中")
                        put("episode_cnt", 0)
                    })
                }))
                put(JSONObject().put("lynx_data", lynxSeriesJson))
                put(JSONObject().put("lynx_data", lynxOtherJson))
            })
        }

        val client = HongguoApiClient(OkHttpClient())
        val parsed = client.parseAppSearchTab(tab, page = 1)
        assertEquals(
            listOf("7671966197985856536", "7676706788724460568", "7666404568275635224"),
            parsed.items.map { it.seriesId }
        )
        assertFalse(parsed.items.any { it.seriesId == "7698694098143677465" })
        assertEquals(setOf("7676706788724460568"), parsed.officialSeriesIds)
        assertEquals(setOf("7666404568275635224"), parsed.officialOtherVersionIds)
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
