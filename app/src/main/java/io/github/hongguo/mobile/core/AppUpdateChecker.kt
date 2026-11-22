// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

const val GITHUB_REPO = "xscanzm/piguo-drama"
const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
const val LATEST_RELEASE_PAGE_URL = "https://github.com/$GITHUB_REPO/releases/latest"

val GITHUB_MIRROR_CHANNELS: List<Pair<String, String>> = listOf(
    "加速线路 1" to "https://gh-proxy.org/",
    "加速线路 2" to "https://ghfast.top/",
    "加速线路 3" to "https://v6.gh-proxy.com/"
)

@Serializable
data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val htmlUrl: String,
    val assetSizeBytes: Long = 0L,
    val publishedAt: String = ""
)

object AppUpdateChecker {
    private val TAG_PATH_REGEX = Regex("/releases/tag/([^/?#\"'\\s<>]+)")

    fun buildDownloadCandidates(downloadUrl: String): List<Pair<String, String>> {
        val clean = downloadUrl.trim()
        if (clean.isEmpty()) return emptyList()
        val list = ArrayList<Pair<String, String>>(1 + GITHUB_MIRROR_CHANNELS.size)
        list.add("GitHub 直连" to clean)
        if (clean.startsWith("https://github.com/")) {
            for ((label, prefix) in GITHUB_MIRROR_CHANNELS) {
                list.add(label to "$prefix$clean")
            }
        }
        return list
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
    }

    fun compareSemVer(a: String, b: String): Int {
        val partsA = normalizeVersion(a)
            .split('.')
            .map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
        val partsB = normalizeVersion(b)
            .split('.')
            .map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun isNewerVersion(latest: String, current: String): Boolean =
        compareSemVer(latest, current) > 0

    fun parseGithubReleaseJson(rawJson: String): AppUpdateInfo? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val obj = JSONObject(rawJson)
            val tag = obj.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            val version = normalizeVersion(tag)
            if (version.isEmpty()) return null

            var preferredUrl = ""
            var preferredSize = 0L
            var fallbackApkUrl = ""
            var fallbackApkSize = 0L
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val url = asset.optString("browser_download_url", "").trim()
                    val size = asset.optLong("size", 0L).coerceAtLeast(0L)
                    if (!url.startsWith("https://")) continue
                    if (name.equals("app-release.apk", ignoreCase = true)) {
                        preferredUrl = url
                        preferredSize = size
                        break
                    }
                    if (fallbackApkUrl.isEmpty() && name.endsWith(".apk", ignoreCase = true)) {
                        fallbackApkUrl = url
                        fallbackApkSize = size
                    }
                }
            }

            val deterministicUrl = "https://github.com/$GITHUB_REPO/releases/download/$tag/app-release.apk"
            val finalDownloadUrl = when {
                preferredUrl.isNotEmpty() -> preferredUrl
                fallbackApkUrl.isNotEmpty() -> fallbackApkUrl
                else -> deterministicUrl
            }
            if (!finalDownloadUrl.startsWith("https://")) return null

            val finalSize = when {
                preferredUrl.isNotEmpty() -> preferredSize
                fallbackApkUrl.isNotEmpty() -> fallbackApkSize
                else -> 0L
            }
            val htmlUrl = obj.optString("html_url", "").trim()
                .takeIf { it.startsWith("https://") }
                ?: "https://github.com/$GITHUB_REPO/releases/tag/$tag"
            val title = obj.optString("name", "").trim().ifEmpty { "皮果刷剧 $tag" }
            val notes = obj.optString("body", "").trim()
            val published = obj.optString("published_at", "").trim()

            AppUpdateInfo(
                tagName = tag,
                versionName = version,
                title = title,
                releaseNotes = notes,
                downloadUrl = finalDownloadUrl,
                htmlUrl = htmlUrl,
                assetSizeBytes = finalSize,
                publishedAt = published
            )
        }.getOrNull()
    }

    fun parseRedirectLocationToRelease(locationUrl: String): AppUpdateInfo? {
        val raw = locationUrl.trim()
        if (raw.isEmpty()) return null
        val match = TAG_PATH_REGEX.find(raw) ?: return null
        val encodedTag = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (encodedTag.isEmpty()) return null
        val tag = runCatching { URLDecoder.decode(encodedTag, StandardCharsets.UTF_8.name()) }
            .getOrDefault(encodedTag)
            .trim()
        val version = normalizeVersion(tag)
        if (tag.isEmpty() || version.isEmpty()) return null

        val htmlUrl = if (raw.startsWith("https://")) {
            raw
        } else {
            "https://github.com/$GITHUB_REPO/releases/tag/$tag"
        }
        return AppUpdateInfo(
            tagName = tag,
            versionName = version,
            title = "皮果刷剧 $tag",
            releaseNotes = "",
            downloadUrl = "https://github.com/$GITHUB_REPO/releases/download/$tag/app-release.apk",
            htmlUrl = htmlUrl
        )
    }

    fun parseTagFromHtmlOrUrl(text: String): AppUpdateInfo? =
        parseRedirectLocationToRelease(text)

    suspend fun fetchLatestRelease(httpClient: OkHttpClient): AppUpdateInfo = withContext(Dispatchers.IO) {
        val apiClient = httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        var lastError: Exception? = null

        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PiguoDrama-Android")
                .get()
                .build()
            apiClient.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseGithubReleaseJson(body)
                    if (parsed != null) return@withContext parsed
                    error("版本信息解析失败")
                } else {
                    error("GitHub 接口返回 HTTP ${resp.code}")
                }
            }
        }.onFailure { if (it is Exception) lastError = it }

        // Fallback via /releases/latest HTTP 302 redirect Location header (no API rate limit)
        runCatching {
            val redirectClient = httpClient.newBuilder()
                .followRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(LATEST_RELEASE_PAGE_URL)
                .header("User-Agent", "PiguoDrama-Android")
                .get()
                .build()
            redirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location").orEmpty()
                val parsed = parseRedirectLocationToRelease(loc)
                if (parsed != null) return@withContext parsed
                error("跳转定位最新版本失败 (HTTP ${resp.code})")
            }
        }.onFailure { if (it is Exception) lastError = it }

        // Mirror fallbacks for restricted networks where GitHub direct times out
        for ((_, prefix) in GITHUB_MIRROR_CHANNELS) {
            runCatching {
                val mirrorClient = httpClient.newBuilder()
                    .followRedirects(false)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(7, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$prefix$LATEST_RELEASE_PAGE_URL")
                    .header("User-Agent", "PiguoDrama-Android")
                    .get()
                    .build()
                mirrorClient.newCall(req).execute().use { resp ->
                    val loc = resp.header("Location").orEmpty()
                    parseTagFromHtmlOrUrl(loc)?.let { return@withContext it }
                    if (resp.code in 200..299) {
                        val html = resp.body?.string().orEmpty().take(128_000)
                        parseTagFromHtmlOrUrl(html)?.let { return@withContext it }
                    }
                    error("镜像检查版本失败 (HTTP ${resp.code})")
                }
            }.onFailure { if (it is Exception) lastError = it }
        }

        throw (lastError ?: IllegalStateException("检查更新失败，请检查网络连接"))
    }
}
