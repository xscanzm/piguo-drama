// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppUpdateAndWarmCacheTest {

    @Test
    fun semVerCompareHandlesPrefixesMultiDigitSegmentsAndSuffixes() {
        assertEquals("1.1.1", AppUpdateChecker.normalizeVersion("v1.1.1"))
        assertEquals("1.2.0", AppUpdateChecker.normalizeVersion("V1.2.0-beta.1+build7"))

        assertTrue(AppUpdateChecker.isNewerVersion("v1.2.0", "1.1.1"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.10.0", "1.2.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("2.0", "1.9.9"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.1.1", "1.1.1"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.1.0", "1.1.1"))
        assertEquals(0, AppUpdateChecker.compareSemVer("1.2", "1.2.0"))
    }

    @Test
    fun githubReleaseJsonPicksPreferredApkAssetOrDeterministicFallback() {
        val fullJson = """
            {
              "tag_name": "v1.2.0",
              "name": "皮果刷剧 v1.2.0",
              "body": "- 新增应用内更新\n- 冷启动预缓存 10 个视频",
              "html_url": "https://github.com/xscanzm/piguo-drama/releases/tag/v1.2.0",
              "published_at": "2026-11-21T15:00:00Z",
              "assets": [
                {
                  "name": "checksums.txt",
                  "size": 128,
                  "browser_download_url": "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.0/checksums.txt"
                },
                {
                  "name": "other-build.apk",
                  "size": 4100000,
                  "browser_download_url": "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.0/other-build.apk"
                },
                {
                  "name": "app-release.apk",
                  "size": 3890123,
                  "browser_download_url": "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.0/app-release.apk"
                }
              ]
            }
        """.trimIndent()
        val parsed = AppUpdateChecker.parseGithubReleaseJson(fullJson)
        assertNotNull(parsed)
        assertEquals("v1.2.0", parsed!!.tagName)
        assertEquals("1.2.0", parsed.versionName)
        assertEquals(3890123L, parsed.assetSizeBytes)
        assertEquals(
            "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.0/app-release.apk",
            parsed.downloadUrl
        )

        val fallbackApkJson = """
            {
              "tag_name": "v1.2.1",
              "assets": [
                {
                  "name": "piguo-universal.apk",
                  "size": 4000000,
                  "browser_download_url": "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.1/piguo-universal.apk"
                }
              ]
            }
        """.trimIndent()
        val parsedFallback = AppUpdateChecker.parseGithubReleaseJson(fallbackApkJson)
        assertNotNull(parsedFallback)
        assertEquals(
            "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.1/piguo-universal.apk",
            parsedFallback!!.downloadUrl
        )

        val noAssetsJson = """{"tag_name": "v1.3.0", "assets": []}"""
        val parsedNoAssets = AppUpdateChecker.parseGithubReleaseJson(noAssetsJson)
        assertNotNull(parsedNoAssets)
        assertEquals(
            "https://github.com/xscanzm/piguo-drama/releases/download/v1.3.0/app-release.apk",
            parsedNoAssets!!.downloadUrl
        )

        assertNull(AppUpdateChecker.parseGithubReleaseJson("not-json"))
        assertNull(AppUpdateChecker.parseGithubReleaseJson("""{"name":"no tag"}"""))
    }

    @Test
    fun redirectLocationParsesReleaseTagDeterministically() {
        val parsed = AppUpdateChecker.parseRedirectLocationToRelease(
            "https://github.com/xscanzm/piguo-drama/releases/tag/v1.2.5"
        )
        assertNotNull(parsed)
        assertEquals("v1.2.5", parsed!!.tagName)
        assertEquals("1.2.5", parsed.versionName)
        assertEquals(
            "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.5/app-release.apk",
            parsed.downloadUrl
        )
        assertEquals("https://github.com/xscanzm/piguo-drama/releases/tag/v1.2.5", parsed.htmlUrl)

        val relative = AppUpdateChecker.parseRedirectLocationToRelease("/xscanzm/piguo-drama/releases/tag/v1.4.0?from=latest")
        assertNotNull(relative)
        assertEquals("1.4.0", relative!!.versionName)
        assertEquals("https://github.com/xscanzm/piguo-drama/releases/tag/v1.4.0", relative.htmlUrl)

        assertNull(AppUpdateChecker.parseRedirectLocationToRelease("https://github.com/xscanzm/piguo-drama"))

        val htmlMatch = AppUpdateChecker.parseTagFromHtmlOrUrl(
            """<a href="https://github.com/xscanzm/piguo-drama/releases/tag/v1.3.2">v1.3.2</a>"""
        )
        assertNotNull(htmlMatch)
        assertEquals("v1.3.2", htmlMatch!!.tagName)
        assertEquals("1.3.2", htmlMatch.versionName)
    }

    @Test
    fun buildDownloadCandidatesIncludesDirectAndMirrorsForGithubOnly() {
        val ghUrl = "https://github.com/xscanzm/piguo-drama/releases/download/v1.2.1/app-release.apk"
        val candidates = AppUpdateChecker.buildDownloadCandidates(ghUrl)
        assertEquals(1 + GITHUB_MIRROR_CHANNELS.size, candidates.size)
        assertEquals("GitHub 直连" to ghUrl, candidates[0])
        assertEquals("https://gh-proxy.org/$ghUrl", candidates[1].second)

        val cdnUrl = "https://cdn.example.com/app-release.apk"
        assertEquals(listOf("GitHub 直连" to cdnUrl), AppUpdateChecker.buildDownloadCandidates(cdnUrl))
    }

    @Test
    fun feedWarmCacheStorePersistsDedupesEvictsPrunesAndClears() {
        val dir = Files.createTempDirectory("warm_feed_test").toFile()
        try {
            val file = dir.resolve("warm_feed_cache.json")
            val store = FeedWarmCacheStore(file, maxEntries = 3)

            fun entry(sid: String, vid: String, q: String = "auto") = WarmFeedEntry(
                summary = DramaSummary(seriesId = sid, title = "短剧$sid", initialVid = vid),
                vid = vid,
                episodeIndex = 1,
                qualityKey = q,
                cachedAt = 1000L
            )

            store.upsert(entry("s1", "v1"))
            store.upsert(entry("s2", "v2"))
            store.upsert(entry("s2", "v2_new")) // dedup overwrite
            store.upsert(entry("s3", "v3", q = "720p"))
            // Adding s4 exceeds maxEntries=3 -> evicts oldest (s1)
            val count = store.upsert(entry("s4", "v4"))
            assertEquals(3, count)
            assertEquals(setOf("s2", "s3", "s4"), store.seriesIdsSnapshot())
            assertEquals(
                setOf("v2_new_auto.mp4", "v3_720p.mp4", "v4_auto.mp4"),
                store.protectedFilenames()
            )

            // Reload from disk
            val reloaded = FeedWarmCacheStore(file, maxEntries = 3)
            assertEquals(3, reloaded.size())
            assertEquals(setOf("s2", "s3", "s4"), reloaded.seriesIdsSnapshot())

            // Prune excludes s2 (already seen) and invalidates s4 (missing file)
            val remaining = reloaded.prune(excludedSeriesIds = setOf("s2")) { it.summary.seriesId != "s4" }
            assertEquals(listOf("s3"), remaining.map { it.summary.seriesId })
            assertEquals(1, reloaded.size())

            // Remove & clear
            assertEquals(0, reloaded.remove("s3"))
            reloaded.upsert(entry("s5", "v5"))
            reloaded.clear()
            assertEquals(0, reloaded.size())
            assertEquals(0, FeedWarmCacheStore(file, maxEntries = 3).size())
        } finally {
            dir.deleteRecursively()
        }
    }
}
