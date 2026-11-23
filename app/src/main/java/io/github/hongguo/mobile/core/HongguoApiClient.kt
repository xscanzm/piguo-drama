// SPDX-License-Identifier: GPL-3.0-only
// Port of jackotom/hongguo-drama App/Web catalog, search, detail & playback APIs.
package io.github.hongguo.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HongguoApiClient(
    val httpClient: OkHttpClient,
    private val ratingCacheFile: java.io.File? = null,
    private val deviceIdentityFile: java.io.File? = null,
    private val authSessionProvider: () -> AuthSession? = { null }
) {
    companion object {
        private const val SITE = "https://hongguoduanju.com"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val CATEGORY_API = "/reading/distribution/category/landpage/v/"
        private const val RANK_API = "/reading/bookapi/bookmall/cell/change/v"
        private const val FEED_RECOMMEND_API = "/reading/bookapi/bookmall/cell/change/v1/"
        private const val MALL_PLAYLET_FEED_API = "/reading/playlet/shortbuy_api/get_mall_playlet_feed/v1/"
        private const val FEEDBACK_API = "/reading/playlet/shortbuy_api/feedback/v1/"
        private const val FEED_RECOMMEND_CELL_ID = "6952850996422770718"

        private val CATEGORIES = mapOf(
            "real-drama" to ("default" to "short_play"),
            "comic-drama" to ("comic_series" to "comic_series"),
            "ai-drama" to ("ai_series" to "ai_series")
        )
        private val RANKS = mapOf(
            "hot-drama" to ("all" to "ranklist_hot_play_sc"),
            "hot-real-drama" to ("human" to "human_hot_play"),
            "hot-comic-drama" to ("comic_series_rank" to "comic_series_hot_play"),
            "hot-ai-drama" to ("ai_playlet" to "ai_playlet_hot_play")
        )
        private val FEED_SELECTORS = mapOf(
            "hot-drama" to "all",
            "real-drama" to "playlet",
            "comic-drama" to "motion_comic",
            "ai-drama" to "ai_playlet"
        )
        private val API_DOMAINS = listOf("fqnovel.com", "snssdk.com")
        private val MEDIA_DOMAINS = listOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
        private val WEB_MEDIA_DOMAINS = listOf("hongguoduanju.com", "qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
        private val VOD_FALLBACK_DOMAINS = listOf(
            "fqnovel.com",
            "snssdk.com",
            "bytedanceapi.com",
            "bytevod.com",
            "douyinvod.com",
            "qznovelvod.com",
            "fqnovelvod.com"
        )
    }

    val registrar = DeviceRegistrar(httpClient, deviceIdentityFile)
    val ratingCache = OfficialRatingCache(ratingCacheFile)
    private val playbackMutex = Mutex()
    private var playbackReadyAtMs = 0L
    private val detailThrottleMutex = Mutex()
    @Volatile
    private var detailReadyAtMs = 0L

    private data class CursorState(val offset: Int, val sessionId: String)
    private val catalogCursors = ConcurrentHashMap<String, MutableMap<Int, CursorState>>()
    private val recommendCursors = ConcurrentHashMap<String, MutableMap<Int, CursorState>>()
    private val mallFeedCursors = ConcurrentHashMap<String, MutableMap<Int, CursorState>>()
    private val seenRecFilters = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val persistedSeenRecFilters = java.util.Collections.synchronizedList(ArrayList<String>())
    private val dislikedRecFilters = java.util.Collections.synchronizedList(ArrayList<String>())
    private val prefetchedVariantsCache = ConcurrentHashMap<String, Pair<Long, List<PlayVariant>>>()
    private val catalogGenres = ConcurrentHashMap<String, List<GenreItem>>()
    private val detailCache = ConcurrentHashMap<String, Pair<Long, DramaDetail>>()

    private fun currentCookieHeader(): String? =
        authSessionProvider()?.cookieHeader?.takeIf { it.isNotBlank() }

    suspend fun activeIdentity(forceFreshAnonymous: Boolean = false, bypassCooldown: Boolean = false): DeviceIdentity {
        val session = authSessionProvider()
        if (!forceFreshAnonymous && session != null &&
            session.deviceId.isNotBlank() && session.iid.isNotBlank() && session.cdid.isNotBlank()
        ) {
            return DeviceIdentity(session.deviceId, session.iid, session.cdid)
        }
        return registrar.getOrRegister(forceFresh = forceFreshAnonymous, bypassCooldown = bypassCooldown)
    }

    // ---- Helpers ----

    private fun cleanTitle(value: String?): String = (value ?: "")
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#039;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanUrl(value: String?): String = (value ?: "")
        .trim()
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun compatibleCover(raw: String?): String {
        val url = cleanUrl(raw)
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return ""
        val parsed = url.toHttpUrlOrNull() ?: return ""
        if (parsed.encodedPath.endsWith(".heic", ignoreCase = true)) {
            val host = parsed.host.lowercase()
            if ((host == "fqnovelpic.com" || host.endsWith(".fqnovelpic.com") ||
                    host == "byteimg.com" || host.endsWith(".byteimg.com")) &&
                parsed.encodedPath.startsWith("/novel-pic/") &&
                parsed.encodedPath.contains("~")
            ) {
                val basePath = parsed.encodedPath.substringBefore("~")
                return "https://p3-novel.byteimg.com$basePath~tplv-shrink:640:0.image"
            }
            return ""
        }
        return url
    }

    private fun checkDomain(rawUrl: String, allowedDomains: List<String>): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: error("地址格式无效")
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "不受支持的协议或地址"
        }
        val host = parsed.host.lowercase()
        val ok = allowedDomains.any { d -> host == d || host.endsWith(".$d") }
        require(ok) { "返回了不受支持的服务域名: $host" }
        return parsed.toString()
    }

    private fun executeSignedJson(
        signed: MetadataSignedRequest,
        method: String = "GET",
        timeoutMs: Long = 10_000L,
        preserveLargeFields: List<String> = emptyList()
    ): JSONObject {
        val url = checkDomain(signed.url, API_DOMAINS)
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in signed.headers) {
            reqBuilder.header(k, v)
        }
        if (method == "POST") {
            val ct = signed.headers["Content-Type"] ?: "application/json; charset=utf-8"
            val bodyBytes = signed.body ?: ByteArray(0)
            reqBuilder.post(bodyBytes.toRequestBody(ct.toMediaType()))
        } else {
            reqBuilder.get()
        }
        val client = httpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code in listOf(301, 302, 303, 307, 308)) {
                error("App 接口发生不支持的跳转")
            }
            if (resp.code != 200) {
                error("App 接口请求失败 (HTTP ${resp.code})")
            }
            var raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) {
                throw EmptyResponseException("App 接口返回空响应 (HTTP 200)")
            }
            if (preserveLargeFields.isNotEmpty()) {
                val names = preserveLargeFields.joinToString("|") { Regex.escape(it) }
                val pattern = Regex("(\"(?:$names)\"\\s*:\\s*)(-?\\d{16,})(?=\\s*[,}\\]])")
                raw = pattern.replace(raw, "$1\"$2\"")
            }
            return JSONObject(raw)
        }
    }

    private fun executePlainGetText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 10_000L): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        val client = httpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun extractRouterData(html: String): JSONObject {
        val marker = Regex("(?:window\\.)?_ROUTER_DATA\\s*=\\s*\\{").find(html)
            ?: error("来源页面未返回目录数据")
        val start = marker.range.last
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else {
                when (ch) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return JSONObject(html.substring(start, i + 1))
                }
            }
        }
        error("来源页面数据不完整")
    }

    suspend fun fetchRecommendFeed(
        channelKey: String,
        page: Int = 1,
        forceRefresh: Boolean = false
    ): CatalogPageResult = withContext(Dispatchers.IO) {
        if (channelKey == "hot-drama") {
            if (forceRefresh && page == 1) {
                mallFeedCursors.remove(channelKey)
            }
            val mallResult = runCatching {
                fetchMallPlayletFeed(channelKey, page, forceRefresh)
            }.getOrNull()
            if (mallResult != null && mallResult.items.isNotEmpty()) {
                return@withContext mallResult
            }
        }
        val selected = FEED_SELECTORS[channelKey]
        if (selected != null) {
            if (forceRefresh && page == 1) {
                recommendCursors.remove(channelKey)
            }
            val recResult = runCatching {
                fetchAppRecommendFeed(channelKey, selected, page)
            }.getOrNull()
            if (recResult != null && recResult.items.isNotEmpty()) {
                return@withContext recResult
            }
        }
        val fallbackCat = CatalogCategory.entries.firstOrNull { it.key == channelKey } ?: CatalogCategory.HOT_DRAMA
        val startPage = if (page == 1 && forceRefresh) (1..3).random() else page
        val catResult = fetchCatalog(fallbackCat, "", startPage, forceRefresh = forceRefresh)
        if (forceRefresh && page == 1 && catResult.items.size > 1) {
            catResult.copy(items = catResult.items.shuffled())
        } else {
            catResult
        }
    }

    suspend fun submitFeedDislike(summary: DramaSummary, reason: String = "不感兴趣") = withContext(Dispatchers.IO) {
        if (summary.recFilterInfo.isNotBlank()) {
            synchronized(dislikedRecFilters) {
                if (!dislikedRecFilters.contains(summary.recFilterInfo)) {
                    dislikedRecFilters.add(summary.recFilterInfo)
                    while (dislikedRecFilters.size > 80) {
                        dislikedRecFilters.removeAt(0)
                    }
                }
            }
        }
        runCatching {
            val identity = activeIdentity()
            val cookie = currentCookieHeader()
            val payload = JSONObject().apply {
                put("scene", "VideoClips")
                put("id", summary.seriesId)
                put("reason", reason)
            }.toString().toByteArray(StandardCharsets.UTF_8)
            val signed = LegacySigner.signedAppRequest(
                apiPath = FEEDBACK_API,
                values = emptyMap(),
                bodyBytes = payload,
                identity = identity,
                host = LegacySigner.PLAYBACK_HOSTS.first(),
                cookieHeader = cookie
            )
            executeSignedJson(signed, method = "POST", timeoutMs = 5_000L)
        }
    }

    fun seedRecFilters(seen: List<String>, disliked: List<String>) {
        synchronized(persistedSeenRecFilters) {
            persistedSeenRecFilters.clear()
            val dedup = HashSet<String>()
            for (raw in seen) {
                val clean = raw.trim()
                if (clean.isNotEmpty() && dedup.add(clean)) {
                    persistedSeenRecFilters.add(clean)
                    if (persistedSeenRecFilters.size >= 60) break
                }
            }
        }
        synchronized(dislikedRecFilters) {
            for (raw in disliked.asReversed()) {
                val clean = raw.trim()
                if (clean.isNotEmpty()) {
                    dislikedRecFilters.remove(clean)
                    dislikedRecFilters.add(clean)
                    while (dislikedRecFilters.size > 80) {
                        dislikedRecFilters.removeAt(0)
                    }
                }
            }
        }
    }

    fun recordSeenRecFilter(channelKey: String, recFilterInfo: String) {
        val clean = recFilterInfo.trim()
        if (clean.isEmpty()) return
        val channelSet = seenRecFilters.getOrPut(channelKey) { LinkedHashSet() }
        synchronized(channelSet) {
            channelSet.remove(clean)
            channelSet.add(clean)
            while (channelSet.size > 60) {
                channelSet.iterator().let { it.next(); it.remove() }
            }
        }
        synchronized(persistedSeenRecFilters) {
            persistedSeenRecFilters.remove(clean)
            persistedSeenRecFilters.add(0, clean)
            while (persistedSeenRecFilters.size > 60) {
                persistedSeenRecFilters.removeAt(persistedSeenRecFilters.lastIndex)
            }
        }
    }

    fun removeDislikedRecFilter(recFilterInfo: String) {
        val clean = recFilterInfo.trim()
        if (clean.isEmpty()) return
        synchronized(dislikedRecFilters) {
            dislikedRecFilters.remove(clean)
        }
    }

    fun clearSeenRecFilters() {
        seenRecFilters.clear()
        synchronized(persistedSeenRecFilters) {
            persistedSeenRecFilters.clear()
        }
    }

    fun clearDislikedRecFilters() {
        synchronized(dislikedRecFilters) {
            dislikedRecFilters.clear()
        }
    }

    internal fun buildRecFilterListJson(
        channelKey: String,
        maxItems: Int = 30,
        maxRawChars: Int = 3500
    ): String {
        if (maxItems <= 0 || maxRawChars < 2) return ""
        val list = JSONArray()
        val dedup = LinkedHashSet<String>()
        var charCount = 2

        fun tryAdd(raw: String): Boolean {
            if (list.length() >= maxItems) return false
            val clean = raw.trim()
            if (clean.isEmpty() || !dedup.add(clean)) return true
            val obj = runCatching { JSONObject(clean) }.getOrNull() ?: return true
            val serialized = obj.toString()
            val addedLen = serialized.length + if (list.length() > 0) 1 else 0
            if (charCount + addedLen > maxRawChars) return false
            list.put(obj)
            charCount += addedLen
            return list.length() < maxItems
        }

        val dislikedSnapshot = synchronized(dislikedRecFilters) {
            dislikedRecFilters.asReversed().take(12)
        }
        for (raw in dislikedSnapshot) {
            if (!tryAdd(raw)) break
        }

        val channelSnapshot = seenRecFilters[channelKey]?.let { seen ->
            synchronized(seen) { seen.toList().asReversed() }
        }.orEmpty()
        for (raw in channelSnapshot) {
            if (!tryAdd(raw)) break
        }

        val persistedSnapshot = synchronized(persistedSeenRecFilters) {
            persistedSeenRecFilters.toList()
        }
        for (raw in persistedSnapshot) {
            if (!tryAdd(raw)) break
        }

        return if (list.length() > 0) list.toString() else ""
    }

    private suspend fun fetchMallPlayletFeed(
        channelKey: String,
        page: Int,
        forceRefresh: Boolean
    ): CatalogPageResult {
        val cursors = mallFeedCursors.getOrPut(channelKey) {
            mutableMapOf(1 to CursorState(0, ""))
        }
        val cursor = if (page == 1) CursorState(0, "") else cursors[page] ?: error("推荐游标失效")
        var identity = activeIdentity()
        val cookie = currentCookieHeader()
        val recFilterJson = buildRecFilterListJson(channelKey)

        var payload: JSONObject? = null
        var lastErr: Exception? = null
        var refreshedDevice = false
        for (host in LegacySigner.PLAYBACK_HOSTS.take(4)) {
            val values = linkedMapOf(
                "enter_from" to "shop_mall_video_feed",
                "offset" to cursor.offset.toString()
            )
            if (cursor.sessionId.isNotBlank() && !forceRefresh) {
                values["session_id"] = cursor.sessionId
            }
            if (recFilterJson.isNotEmpty()) {
                values["rec_filter_info_list"] = recFilterJson
            }
            val signed = LegacySigner.signedAppRequest(
                apiPath = MALL_PLAYLET_FEED_API,
                values = values,
                identity = identity,
                host = host,
                cookieHeader = cookie
            )
            try {
                val res = executeSignedJson(
                    signed = signed,
                    method = "GET",
                    timeoutMs = 8_500L,
                    preserveLargeFields = listOf("series_id", "vid", "item_id", "rec_group_id")
                )
                if (res.optInt("code", -1) == 0) {
                    payload = res
                    break
                }
            } catch (e: Exception) {
                lastErr = e
                if (e is EmptyResponseException && !refreshedDevice && authSessionProvider() == null) {
                    refreshedDevice = true
                    runCatching {
                        identity = registrar.getOrRegister(forceFresh = true, bypassCooldown = true)
                    }
                }
            }
        }
        val root = payload ?: throw (lastErr ?: IllegalStateException("官方推荐流暂不可用"))
        val data = root.optJSONObject("data") ?: error("推荐流缺少 data")
        val view = data.optJSONObject("cell_view") ?: error("推荐流缺少 cell_view")
        val cellData = view.optJSONArray("cell_data") ?: JSONArray()

        val seen = HashSet<String>()
        val items = ArrayList<DramaSummary>()
        val channelFilterSet = seenRecFilters.getOrPut(channelKey) { LinkedHashSet() }

        for (i in 0 until cellData.length()) {
            val cell = cellData.optJSONObject(i) ?: continue
            val recFilterRaw = cell.optJSONObject("cell_extra_data")
                ?.optJSONObject("rec_filter_info")
                ?.toString()
                .orEmpty()
            val arr = cell.optJSONArray("video_data")
            val rows = ArrayList<JSONObject>()
            if (arr != null) {
                for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { rows.add(it) }
            } else {
                cell.optJSONObject("video_data")?.let { rows.add(it) }
            }
            for (v in rows) {
                parseFeedCellRow(v, recFilterRaw, seen)?.let { summary ->
                    items.add(summary)
                    if (recFilterRaw.isNotEmpty()) {
                        synchronized(channelFilterSet) {
                            channelFilterSet.add(recFilterRaw)
                            while (channelFilterSet.size > 60) {
                                channelFilterSet.iterator().let { it.next(); it.remove() }
                            }
                        }
                    }
                }
            }
        }

        val hasMore = data.optBoolean("has_more", view.optBoolean("has_more", false))
        val nextOffset = data.optInt("next_offset", view.optInt("next_offset", -1))
        val nextSessionId = data.optString("session_id", view.optString("session_id", ""))
        if (hasMore && nextOffset > cursor.offset) {
            cursors[page + 1] = CursorState(nextOffset, nextSessionId)
        } else {
            cursors.remove(page + 1)
        }
        ratingCache.saveIfDirty()

        return CatalogPageResult(
            items = items,
            genres = emptyList(),
            page = page,
            hasMore = hasMore && page < 50 && cursors.containsKey(page + 1)
        )
    }

    private fun parseFeedCellRow(
        v: JSONObject,
        recFilterRaw: String,
        seen: MutableSet<String>
    ): DramaSummary? {
        if (v.optBoolean("disable_play", false)) return null
        val det = v.optJSONObject("video_detail")
        val sid = v.optString("series_id", "")
            .ifEmpty { det?.optString("series_id_str").orEmpty() }
            .ifEmpty { det?.optString("series_id").orEmpty() }
            .trim()
        val title = cleanTitle(
            v.optString("title")
                .ifEmpty { v.optString("series_title") }
                .ifEmpty { v.optString("series_name") }
                .ifEmpty { det?.optString("series_title").orEmpty() }
        )
        if (!Regex("^\\d{1,30}$").matches(sid) || title.isEmpty() || !seen.add(sid)) return null

        val vid = v.optString("vid", "")
            .ifEmpty { v.optJSONObject("highlight")?.optString("vid").orEmpty() }
            .ifEmpty { det?.optString("first_vid").orEmpty() }
            .trim()
            .takeIf { Regex("^\\d{1,30}$").matches(it) } ?: ""

        val cover = compatibleCover(
            v.optString("cover")
                .ifEmpty { v.optString("series_cover") }
                .ifEmpty { det?.optString("series_cover").orEmpty() }
        )
        val desc = cleanTitle(
            v.optString("video_desc")
                .ifEmpty { v.optString("series_intro") }
                .ifEmpty { det?.optString("series_intro").orEmpty() }
        )
        val epCnt = v.optInt("episode_cnt", det?.optInt("episode_cnt", 0) ?: 0)
        val epLabel = cleanTitle(det?.optString("episode_right_text"))

        val tags = ArrayList<String>()
        val subList = v.optJSONArray("sub_title_list")
        if (subList != null) {
            for (j in 0 until minOf(subList.length(), 4)) {
                val c = cleanTitle(subList.optJSONObject(j)?.optString("content"))
                if (c.isNotEmpty() && c !in tags) tags.add(c)
            }
        }
        val catSchemaRaw = det?.optString("category_schema").orEmpty()
        if (catSchemaRaw.startsWith("[")) {
            runCatching {
                val arr = JSONArray(catSchemaRaw)
                for (j in 0 until minOf(arr.length(), 4)) {
                    val name = cleanTitle(arr.optJSONObject(j)?.optString("name"))
                    if (name.isNotEmpty() && name !in tags) tags.add(name)
                }
            }
        }
        var heatText = ""
        val seriesSubs = det?.optJSONArray("series_sub_title_list")
        if (seriesSubs != null) {
            for (j in 0 until seriesSubs.length()) {
                val s = cleanTitle(seriesSubs.optString(j))
                if (s.contains("热度")) {
                    heatText = s
                } else if (s.isNotEmpty() && s !in tags && tags.size < 4) {
                    tags.add(s)
                }
            }
        }

        val followedCnt = det?.optLong("followed_cnt", 0L)?.coerceAtLeast(0L) ?: 0L
        val seriesPlayCnt = det?.optLong("series_play_cnt", 0L)?.coerceAtLeast(0L) ?: 0L
        val vCatRaw = v.opt("category_schema")?.takeIf { it != JSONObject.NULL && it.toString().trim().startsWith("[") }
        val detCatRaw = det?.opt("category_schema")?.takeIf { it != JSONObject.NULL && it.toString().trim().startsWith("[") }
        val categoryNames = DramaTagger.parseCategoryNames(vCatRaw ?: detCatRaw)
        val displayTags = DramaTagger.pickDisplayTags(categoryNames)
        val parsedGender = DramaTagger.parseGender(vCatRaw).let { g ->
            if (g >= 0) g else DramaTagger.parseGender(detCatRaw)
        }
        val officialRating = DramaTagger.parseOfficialRating(v)
        val platformScore = DramaTagger.parsePlatformScore(v)
        if (officialRating != null || platformScore != null || parsedGender >= 0) {
            ratingCache.put(sid, officialRating, platformScore, parsedGender)
        }
        val cachedRating = ratingCache.get(sid)
        val gender = if (parsedGender >= 0) parsedGender else (cachedRating?.gender ?: -1)
        val createTimeSec = v.optLong("create_time", 0L).let {
            if (it > 0L) it else (det?.optLong("create_time", 0L) ?: 0L)
        }.coerceAtLeast(0L)
        val isCompleted = parseCompletedStatus(epLabel)
        val nowSec = System.currentTimeMillis() / 1000L
        val isNewTag = DramaTagger.parseNewTag(v) || (createTimeSec > 0L && (nowSec - createTimeSec) in 0L..(7L * 86400L))
        var parsedQuality: DramaQualityReport? = null
        var initialEpisodeIndex = 1

        // Warm detail cache immediately when video_detail.video_list is embedded
        val detVideos = det?.optJSONArray("video_list")
        if (detVideos != null && detVideos.length() > 0) {
            val rawEps = ArrayList<Triple<Int, Boolean, EpisodeItem>>(detVideos.length())
            for (k in 0 until detVideos.length()) {
                val epObj = detVideos.optJSONObject(k) ?: continue
                val ev = epObj.optString("vid", "").trim()
                if (!Regex("^\\d{1,30}$").matches(ev)) continue
                val eIdx = epObj.optInt("vid_index", k + 1).coerceAtLeast(1)
                val digg = epObj.optLong("digged_count", 0L).coerceAtLeast(0L)
                val cmt = epObj.optLong("comment_count", 0L).coerceAtLeast(0L)
                val isNewly = epObj.optBoolean("is_newly_update", false)
                rawEps.add(
                    Triple(
                        eIdx,
                        isNewly,
                        EpisodeItem(vid = ev, index = eIdx, title = "第${eIdx}集", diggCount = digg, commentCount = cmt)
                    )
                )
            }
            if (rawEps.isNotEmpty()) {
                val sortedRows = rawEps.sortedBy { it.first }
                val sortedEps = sortedRows.mapIndexed { idx, p ->
                    p.third.copy(index = idx + 1, title = "第${idx + 1}集")
                }
                if (vid.isNotEmpty()) {
                    sortedEps.firstOrNull { it.vid == vid }?.index?.let { initialEpisodeIndex = it }
                }
                val newlyCount = sortedRows.count { it.second }
                val resolvedEpLabel = epLabel.ifEmpty { "全${sortedEps.size}集" }
                val resolvedCompleted = isCompleted ?: parseCompletedStatus(resolvedEpLabel)
                parsedQuality = buildQuality(
                    seriesId = sid,
                    episodeDiggs = sortedEps.map { it.diggCount },
                    episodeComments = sortedEps.map { it.commentCount },
                    followedCount = followedCnt,
                    seriesPlayCount = seriesPlayCnt,
                    createTimeSec = createTimeSec,
                    isCompleted = resolvedCompleted,
                    newlyUpdatedCount = newlyCount,
                    officialRating = officialRating,
                    platformScore = platformScore
                )
                detailCache.putIfAbsent(
                    sid,
                    (System.currentTimeMillis() + 300_000L) to DramaDetail(
                        seriesId = sid,
                        title = title,
                        cover = cover,
                        description = desc,
                        episodeLabel = resolvedEpLabel,
                        episodes = sortedEps,
                        followedCount = followedCnt,
                        seriesPlayCount = seriesPlayCnt,
                        quality = parsedQuality,
                        categoryNames = categoryNames,
                        displayTags = displayTags,
                        gender = gender,
                        createTimeSec = createTimeSec,
                        isCompleted = resolvedCompleted
                    )
                )
            }
        }

        // Pre-populate embedded video_model variant for instant feed playback / fallback
        val modelRaw = v.opt("video_model")
        if (vid.isNotEmpty() && modelRaw != null && modelRaw != JSONObject.NULL) {
            runCatching {
                val modelObj = when (modelRaw) {
                    is JSONObject -> modelRaw
                    is String -> if (modelRaw.trim().startsWith("{")) JSONObject(modelRaw) else null
                    else -> null
                }
                if (modelObj != null) {
                    val parsed = parseModel(
                        info = modelObj,
                        vid = vid,
                        preferredQuality = QualityPreference.AUTO,
                        inline = true,
                        requireEncryptionMetadata = true
                    )
                    if (parsed.variants.isNotEmpty()) {
                        prefetchedVariantsCache[vid] = System.currentTimeMillis() to parsed.variants
                    }
                }
            }
        }

        val mergedTags = (categoryNames + tags).distinct()
        return DramaSummary(
            seriesId = sid,
            title = title,
            cover = cover,
            episodeCount = epCnt,
            description = desc,
            tags = mergedTags,
            heat = heatText,
            episodeLabel = epLabel,
            initialVid = vid,
            initialEpisodeIndex = initialEpisodeIndex,
            recommendInfo = v.optString("recommend_info", ""),
            recFilterInfo = recFilterRaw,
            followedCount = followedCnt,
            seriesPlayCount = seriesPlayCnt,
            quality = parsedQuality,
            categoryNames = categoryNames,
            displayTags = displayTags,
            gender = gender,
            createTimeSec = createTimeSec,
            isNewTag = isNewTag,
            officialRating = officialRating ?: cachedRating?.rating,
            platformScore = platformScore ?: cachedRating?.platformScore
        )
    }

    private suspend fun fetchAppRecommendFeed(
        channelKey: String,
        selectedItem: String,
        page: Int
    ): CatalogPageResult {
        val cursors = recommendCursors.getOrPut(channelKey) {
            mutableMapOf(1 to CursorState(0, ""))
        }
        val cursor = if (page == 1) CursorState(0, "") else cursors[page] ?: error("推荐游标失效")
        var identity = activeIdentity()
        val cookie = currentCookieHeader()

        var payload: JSONObject? = null
        var lastErr: Exception? = null
        var refreshedDevice = false
        for (host in LegacySigner.PLAYBACK_HOSTS.take(4)) {
            val values = linkedMapOf(
                "tab_type" to "8",
                "cell_id" to FEED_RECOMMEND_CELL_ID,
                "client_req_type" to "2",
                "selected_items" to selectedItem,
                "offset" to cursor.offset.toString(),
                "limit" to "12",
                "session_id" to cursor.sessionId
            )
            val signed = LegacySigner.signedAppRequest(
                apiPath = FEED_RECOMMEND_API,
                values = values,
                identity = identity,
                host = host,
                cookieHeader = cookie
            )
            try {
                val res = executeSignedJson(
                    signed = signed,
                    method = "GET",
                    timeoutMs = 7_500L,
                    preserveLargeFields = listOf("series_id", "vid")
                )
                if (res.optInt("code", -1) == 0) {
                    payload = res
                    break
                }
            } catch (e: Exception) {
                lastErr = e
                if (e is EmptyResponseException && !refreshedDevice && authSessionProvider() == null) {
                    refreshedDevice = true
                    runCatching {
                        identity = registrar.getOrRegister(forceFresh = true, bypassCooldown = true)
                    }
                }
            }
        }
        val root = payload ?: throw (lastErr ?: IllegalStateException("推荐流暂不可用"))
        val data = root.optJSONObject("data") ?: error("推荐流缺少 data")
        val view = data.optJSONObject("cell_view") ?: error("推荐流缺少 cell_view")
        val cellData = view.optJSONArray("cell_data") ?: JSONArray()

        val seen = HashSet<String>()
        val items = ArrayList<DramaSummary>()
        for (i in 0 until cellData.length()) {
            val cell = cellData.optJSONObject(i) ?: continue
            val recFilterRaw = cell.optJSONObject("cell_extra_data")
                ?.optJSONObject("rec_filter_info")
                ?.toString()
                .orEmpty()
            val arr = cell.optJSONArray("video_data")
            if (arr != null) {
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.let { v -> parseFeedCellRow(v, recFilterRaw, seen)?.let(items::add) }
                }
            } else {
                cell.optJSONObject("video_data")?.let { v -> parseFeedCellRow(v, recFilterRaw, seen)?.let(items::add) }
            }
        }

        val hasMore = data.optBoolean("has_more", view.optBoolean("has_more", false))
        val nextOffset = data.optInt("next_offset", view.optInt("next_offset", -1))
        val nextSessionId = data.optString("session_id", view.optString("session_id", ""))
        if (hasMore && nextOffset > cursor.offset) {
            cursors[page + 1] = CursorState(nextOffset, nextSessionId)
        } else {
            cursors.remove(page + 1)
        }
        ratingCache.saveIfDirty()

        return CatalogPageResult(
            items = items,
            genres = emptyList(),
            page = page,
            hasMore = hasMore && page < 50 && cursors.containsKey(page + 1)
        )
    }

    private fun parseCompletedStatus(epLabel: String): Boolean? {
        val trimmed = epLabel.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("全")) return true
        if (trimmed.contains("更新") || trimmed.contains("连载")) return false
        return null
    }

    private fun buildQuality(
        seriesId: String,
        episodeDiggs: List<Long>,
        episodeComments: List<Long>,
        followedCount: Long,
        seriesPlayCount: Long,
        createTimeSec: Long,
        isCompleted: Boolean?,
        newlyUpdatedCount: Int,
        officialRating: Float? = null,
        platformScore: Float? = null
    ): DramaQualityReport? {
        val cached = ratingCache.get(seriesId)
        return DramaQualityEvaluator.evaluate(
            DramaQualityInput(
                episodeDiggs = episodeDiggs,
                episodeComments = episodeComments,
                followedCount = followedCount,
                seriesPlayCount = seriesPlayCount,
                createTimeSec = createTimeSec,
                isCompleted = isCompleted,
                newlyUpdatedCount = newlyUpdatedCount,
                officialRating = officialRating ?: cached?.rating,
                platformScore = platformScore ?: cached?.platformScore
            )
        )
    }

    // ---- Catalog & Ranking ----

    suspend fun fetchCatalog(
        category: CatalogCategory,
        genre: String = "",
        page: Int = 1,
        forceRefresh: Boolean = false
    ): CatalogPageResult = withContext(Dispatchers.IO) {
        val scopeKey = "${category.key}:$genre"
        if (forceRefresh && page == 1) {
            catalogCursors.remove(scopeKey)
        }
        try {
            fetchAppCatalog(category, genre, page, scopeKey)
        } catch (e: Exception) {
            if (page == 1 && !category.isRanking) {
                fetchWebCatalog(category, genre, page)
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchAppCatalog(
        category: CatalogCategory,
        genre: String,
        page: Int,
        scopeKey: String
    ): CatalogPageResult {
        val cursors = catalogCursors.getOrPut(scopeKey) {
            mutableMapOf(1 to CursorState(0, ""))
        }
        val cursor = if (page == 1) CursorState(0, "") else cursors[page] ?: error("分页游标失效，请下拉刷新")
        val identity = activeIdentity()
        val cookie = currentCookieHeader()

        val ranking = RANKS[category.key]
        fun buildCatalogSigned(id: DeviceIdentity): MetadataSignedRequest {
            return if (ranking != null) {
                val values = linkedMapOf(
                    "tab_type" to if (page == 1) "26" else "18",
                    "client_req_type" to "2",
                    "client_template" to "2",
                    "background_selected_items" to "",
                    "category_id" to "0",
                    "limit" to "20",
                    "cell_sub_id" to "0",
                    "gender" to "2",
                    "cell_id" to "7470092475068071998",
                    "selected_items" to ranking.first,
                    "sub_selected_items" to ranking.second,
                    "panel_selected_items" to "",
                    "offset" to cursor.offset.toString(),
                    "session_id" to cursor.sessionId,
                    "filter_ids" to "",
                    "unlimited_selector_change_type" to "0"
                )
                LegacySigner.signedMetadataRequest(RANK_API, values, null, cookieHeader = cookie, identity = id)
            } else {
                val catPair = CATEGORIES.getValue(category.key)
                val bodyJson = JSONObject().apply {
                    put("filter_ids", "")
                    put("req_scene", catPair.first)
                    put("offset", cursor.offset)
                    put("need_selector_panel", page == 1)
                    put("limit", 15)
                    put("select_items", JSONObject().apply {
                        put("genre", JSONArray(listOf(catPair.second)))
                        put("sort", JSONArray(listOf("hot_score")))
                        put("category_dim_theme", if (genre.isNotEmpty()) JSONArray(listOf(genre)) else JSONArray())
                        put("category_dim_role", JSONArray())
                        put("category_dim_epoch", JSONArray())
                        put("gender", JSONArray())
                        put("online_time", JSONArray())
                        put("creation_status", JSONArray())
                    })
                    put("session_id", cursor.sessionId)
                    put("req_type", if (page == 1) "default" else "only_content")
                    put("client_req_type", 3)
                }.toString()
                LegacySigner.signedMetadataRequest(CATEGORY_API, emptyMap(), bodyJson, cookieHeader = cookie, identity = id)
            }
        }

        val payload = try {
            executeSignedJson(
                signed = buildCatalogSigned(identity),
                method = if (ranking != null) "GET" else "POST",
                timeoutMs = 7000L,
                preserveLargeFields = listOf("series_id", "vid")
            )
        } catch (e: EmptyResponseException) {
            if (authSessionProvider() == null) {
                val freshId = runCatching { registrar.getOrRegister(forceFresh = true, bypassCooldown = true) }.getOrNull()
                    ?: throw e
                executeSignedJson(
                    signed = buildCatalogSigned(freshId),
                    method = if (ranking != null) "GET" else "POST",
                    timeoutMs = 7000L,
                    preserveLargeFields = listOf("series_id", "vid")
                )
            } else {
                throw e
            }
        }
        if (payload.optInt("code", -1) != 0) error("App 目录服务暂不可用")
        val data = payload.optJSONObject("data") ?: error("App 目录缺少数据")

        val allowedTypes = when (category) {
            CatalogCategory.HOT_DRAMA -> setOf(1, 1004)
            CatalogCategory.REAL_DRAMA, CatalogCategory.HOT_REAL -> setOf(1)
            else -> setOf(1004)
        }

        val rows = ArrayList<JSONObject>()
        var genres = catalogGenres[category.key].orEmpty()
        val hasMore: Boolean
        val nextOffset: Int
        val nextSessionId: String

        if (ranking != null) {
            val view = data.optJSONObject("cell_view") ?: error("榜单数据异常")
            val cellData = view.optJSONArray("cell_data") ?: JSONArray()
            for (i in 0 until cellData.length()) {
                val cell = cellData.optJSONObject(i) ?: continue
                val arr = cell.optJSONArray("video_data")
                if (arr != null) {
                    for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { rows.add(it) }
                } else {
                    cell.optJSONObject("video_data")?.let { rows.add(it) }
                }
            }
            hasMore = view.optBoolean("has_more", false)
            nextOffset = view.optInt("next_offset", -1)
            nextSessionId = view.optString("session_id", "")
        } else {
            val arr = data.optJSONArray("video_data") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rows.add(it) }
            val selectorRows = data.optJSONArray("selector_rows")
            if (selectorRows != null && (page == 1 || selectorRows.length() > 0)) {
                val extractedGenres = ArrayList<GenreItem>()
                val seenSlugs = HashSet<String>()
                for (i in 0 until selectorRows.length()) {
                    val row = selectorRows.optJSONObject(i) ?: continue
                    if (row.optString("type") == "category_dim_theme") {
                        val items = row.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j) ?: continue
                            val slug = item.optString("selector_item_id")
                            val label = cleanTitle(item.optString("show_name"))
                            if (Regex("^cate_\\d{1,8}$").matches(slug) && label.isNotEmpty() && seenSlugs.add(slug)) {
                                extractedGenres.add(GenreItem(slug, label))
                            }
                        }
                    }
                }
                if (extractedGenres.isNotEmpty()) {
                    genres = extractedGenres
                    catalogGenres[category.key] = genres
                }
            }
            hasMore = data.optBoolean("has_more", false)
            nextOffset = data.optInt("next_offset", -1)
            nextSessionId = data.optString("session_id", "")
        }

        val seen = HashSet<String>()
        val summaries = ArrayList<DramaSummary>()
        val nowSec = System.currentTimeMillis() / 1000L
        for ((idx, v) in rows.withIndex()) {
            val id = v.optString("series_id", "").trim()
            val title = cleanTitle(
                v.optString("title").ifEmpty { v.optString("series_title").ifEmpty { v.optString("series_name") } }
            )
            val contentType = v.optInt("content_type", -1)
            if (!Regex("^\\d{1,30}$").matches(id) || title.isEmpty() || !seen.add(id) || contentType !in allowedTypes) continue
            val tags = ArrayList<String>()
            val subList = v.optJSONArray("sub_title_list")
            if (subList != null) {
                for (j in 0 until minOf(subList.length(), 4)) {
                    val c = cleanTitle(subList.optJSONObject(j)?.optString("content"))
                    if (c.isNotEmpty()) tags.add(c)
                }
            }
            val rawCat = v.opt("category_schema")
            val categoryNames = DramaTagger.parseCategoryNames(rawCat)
            val displayTags = DramaTagger.pickDisplayTags(categoryNames)
            val parsedGender = DramaTagger.parseGender(rawCat)
            val officialRating = DramaTagger.parseOfficialRating(v)
            val platformScore = DramaTagger.parsePlatformScore(v)
            ratingCache.put(id, officialRating, platformScore, parsedGender)
            val cachedRating = ratingCache.get(id)
            val gender = if (parsedGender >= 0) parsedGender else (cachedRating?.gender ?: -1)
            val createTimeSec = v.optLong("create_time", 0L).coerceAtLeast(0L)
            val isNewTag = DramaTagger.parseNewTag(v) || (createTimeSec > 0L && (nowSec - createTimeSec) in 0L..(7L * 86400L))
            val mergedTags = (categoryNames + tags).distinct()
            summaries.add(
                DramaSummary(
                    seriesId = id,
                    title = title,
                    cover = compatibleCover(v.optString("cover")),
                    episodeCount = v.optInt("episode_cnt", 0),
                    description = cleanTitle(v.optString("video_desc").ifEmpty { v.optString("series_intro") }),
                    tags = mergedTags,
                    rank = if (ranking != null) ((page - 1) * 20 + idx + 1) else null,
                    categoryNames = categoryNames,
                    displayTags = displayTags,
                    gender = gender,
                    createTimeSec = createTimeSec,
                    isNewTag = isNewTag,
                    officialRating = officialRating ?: cachedRating?.rating,
                    platformScore = platformScore ?: cachedRating?.platformScore
                )
            )
        }

        if (hasMore && nextOffset > cursor.offset && nextSessionId.isNotEmpty()) {
            cursors[page + 1] = CursorState(nextOffset, nextSessionId)
        } else {
            cursors.remove(page + 1)
        }
        ratingCache.saveIfDirty()

        return CatalogPageResult(
            items = summaries,
            genres = genres,
            page = page,
            hasMore = hasMore && page < 50 && cursors.containsKey(page + 1)
        )
    }

    private fun fetchWebCatalog(category: CatalogCategory, genre: String, page: Int): CatalogPageResult {
        val url = if (genre.isNotEmpty()) {
            "$SITE/category/${category.key}/$genre${if (page > 1) "?page=$page" else ""}"
        } else {
            "$SITE/category/${category.key}${if (page > 1) "?page=$page" else ""}"
        }
        val html = executePlainGetText(url, mapOf("User-Agent" to WEB_UA, "Referer" to "$SITE/"))
        val router = extractRouterData(html)
        val loader = router.optJSONObject("loaderData") ?: error("网站目录数据缺失")
        var data: JSONObject? = null
        val keys = loader.keys()
        while (keys.hasNext()) {
            val valObj = loader.optJSONObject(keys.next()) ?: continue
            if (valObj.optJSONObject("categoryRoute")?.optString("contentType") == category.key) {
                data = valObj
                break
            }
        }
        val pageData = data ?: error("网站目录解析失败")
        val list = pageData.optJSONArray("recommendList") ?: JSONArray()
        val seen = HashSet<String>()
        val items = ArrayList<DramaSummary>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("series_id")
            val title = cleanTitle(item.optString("series_name"))
            if (!Regex("^\\d{6,}$").matches(id) || title.isEmpty() || !seen.add(id)) continue
            val tagsArr = item.optJSONArray("tags")
            val tags = ArrayList<String>()
            if (tagsArr != null) {
                for (j in 0 until minOf(tagsArr.length(), 4)) {
                    val t = cleanTitle(tagsArr.optString(j))
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            items.add(
                DramaSummary(
                    seriesId = id,
                    title = title,
                    cover = compatibleCover(item.optString("series_cover")),
                    episodeCount = item.optInt("episode_cnt", 0),
                    tags = tags,
                    episodeLabel = cleanTitle(item.optString("episode_right_text"))
                )
            )
        }
        val pagination = pageData.optJSONObject("pagination")
        val totalPages = pagination?.optInt("totalPages", 1) ?: 1
        return CatalogPageResult(items = items, genres = emptyList(), page = page, hasMore = page < totalPages)
    }

    // ---- Search ----

    fun parseSeriesIdFromInput(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (Regex("^\\d{6,30}$").matches(trimmed)) return trimmed
        val httpMatch = Regex("https?://[^\\s]+").find(trimmed)?.value ?: return null
        val url = httpMatch.toHttpUrlOrNull() ?: return null
        if (!url.host.endsWith("hongguoduanju.com", ignoreCase = true)) return null
        url.queryParameter("series_id")?.takeIf { Regex("^\\d{6,30}$").matches(it) }?.let { return it }
        Regex("^/player/(\\d{6,30})").find(url.encodedPath)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun parseSearchVideoItem(
        video: JSONObject,
        fallbackTitle: String = "",
        fallbackDesc: String = ""
    ): DramaSummary? {
        val sid = video.optString("series_id").trim()
        if (!Regex("^\\d{6,30}$").matches(sid)) return null
        if (video.optBoolean("disable_play", false)) return null
        val epCnt = video.optInt("episode_cnt", video.optInt("episode_count", 0))
        if (epCnt <= 0) return null
        val title = cleanTitle(
            video.optString("title")
                .ifBlank { video.optString("series_name") }
                .ifBlank { fallbackTitle }
        )
        if (title.isEmpty()) return null
        val cover = compatibleCover(video.optString("cover").ifBlank { video.optString("series_cover") })
        val desc = cleanTitle(
            video.optString("series_intro")
                .ifBlank { video.optString("video_desc") }
                .ifBlank { fallbackDesc }
        )
        val detailObj = video.optJSONObject("video_detail")
        val followedCnt = video.optLong("followed_cnt", 0L)
            .takeIf { it > 0L }
            ?: detailObj?.optLong("followed_cnt", 0L)
            ?: 0L
        val playCnt = video.optLong("play_cnt", 0L)
            .takeIf { it > 0L }
            ?: detailObj?.optLong("series_play_cnt", 0L)
            ?: 0L
        return DramaSummary(
            seriesId = sid,
            title = title,
            cover = cover,
            episodeCount = epCnt,
            description = desc,
            followedCount = followedCnt,
            seriesPlayCount = playCnt
        )
    }

    internal fun parseAppSearchTab(tab: JSONObject, page: Int): SearchPageResult {
        val cells = tab.optJSONArray("data") ?: JSONArray()
        val seen = HashSet<String>()
        val items = ArrayList<DramaSummary>()
        val officialSeriesIds = LinkedHashSet<String>()
        val officialOtherVersionIds = LinkedHashSet<String>()

        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val hlTitle = cell.optJSONObject("search_high_light")?.optJSONObject("title")?.optString("text")
            val cellFallbackTitle = hlTitle?.takeIf { it.isNotBlank() } ?: cell.optString("cell_name")
            val cellFallbackDesc = cell.optString("series_intro")

            val vArr = cell.optJSONArray("video_data")
            if (vArr != null) {
                for (j in 0 until vArr.length()) {
                    val candidate = vArr.optJSONObject(j) ?: continue
                    val parsed = parseSearchVideoItem(
                        video = candidate,
                        fallbackTitle = if (j == 0) cellFallbackTitle else "",
                        fallbackDesc = if (j == 0) cellFallbackDesc else ""
                    ) ?: continue
                    if (seen.add(parsed.seriesId)) {
                        items.add(parsed)
                    }
                }
            }

            val lynxRaw = cell.optString("lynx_data", "")
            if (lynxRaw.isNotBlank()) {
                val preservedLynx = Regex("(\"(?:series_id|vid)\"\\s*:\\s*)(-?\\d{6,})(?=\\s*[,}\\]])")
                    .replace(lynxRaw, "$1\"$2\"")
                val lynxObj = runCatching { JSONObject(preservedLynx) }.getOrNull() ?: continue
                val lynxName = lynxObj.optString("cell_name", cell.optString("cell_name"))
                val lynxUrl = lynxObj.optString("cell_url", cell.optString("cell_url"))
                val isOfficialSeries = lynxName.contains("系列") || lynxUrl.contains("same_ip_same_series")
                val isOfficialOtherVersion = lynxName.contains("其他版本") || lynxUrl.contains("same_ip_diff_series")
                val cellDataArr = lynxObj.optJSONArray("cell_data") ?: continue
                for (k in 0 until cellDataArr.length()) {
                    val sub = cellDataArr.optJSONObject(k) ?: continue
                    val subVideos = sub.optJSONArray("video_data") ?: continue
                    for (m in 0 until subVideos.length()) {
                        val candidate = subVideos.optJSONObject(m) ?: continue
                        val parsed = parseSearchVideoItem(candidate) ?: continue
                        if (isOfficialSeries) {
                            officialSeriesIds.add(parsed.seriesId)
                        } else if (isOfficialOtherVersion) {
                            officialOtherVersionIds.add(parsed.seriesId)
                        }
                        if (seen.add(parsed.seriesId)) {
                            items.add(parsed)
                        }
                    }
                }
            }
        }

        val hasMore = tab.optBoolean("has_more", false) && page < 50
        val nextCursor = SearchCursor(
            searchId = tab.optString("search_id", ""),
            passback = tab.optString("passback", ""),
            nextOffset = if (tab.has("next_offset")) tab.optInt("next_offset") else null
        )
        return SearchPageResult(
            items = items,
            page = page,
            hasMore = hasMore,
            cursor = nextCursor,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )
    }

    suspend fun searchDramas(
        keyword: String,
        page: Int = 1,
        cursor: SearchCursor? = null
    ): SearchPageResult = withContext(Dispatchers.IO) {
        val cleanKw = cleanTitle(keyword).take(80)
        require(cleanKw.isNotEmpty()) { "请输入搜索关键词" }
        try {
            val identity = registrar.getOrRegister()
            val signed = LegacySigner.signedSearchRequest(
                keyword = cleanKw,
                page = page,
                searchId = cursor?.searchId,
                passback = cursor?.passback,
                nextOffset = cursor?.nextOffset,
                identity = identity
            )
            val payload = executeSignedJson(
                signed = signed,
                method = "GET",
                timeoutMs = 8000L,
                preserveLargeFields = listOf("series_id", "vid")
            )
            if (payload.optInt("code", 0) != 0) error("App 搜索错误")
            val tabs = payload.optJSONArray("search_tabs")
                ?: payload.optJSONObject("data")?.optJSONArray("search_tabs")
                ?: error("App 搜索结构异常")
            var targetTab: JSONObject? = null
            for (i in 0 until tabs.length()) {
                val t = tabs.optJSONObject(i) ?: continue
                if (t.optInt("tab_type", -1) == 11) {
                    targetTab = t
                    break
                }
            }
            val tab = targetTab ?: error("未找到短剧搜索结果")
            parseAppSearchTab(tab, page)
        } catch (_: Exception) {
            searchWebsite(cleanKw, page)
        }
    }

    private fun searchWebsite(keyword: String, page: Int): SearchPageResult {
        val encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val url = "$SITE/api/search/result?query=$encoded&page=$page"
        val raw = executePlainGetText(url, mapOf("User-Agent" to WEB_UA, "Referer" to "$SITE/"))
        val json = JSONObject(raw)
        val list = json.optJSONArray("searchList") ?: JSONArray()
        val seen = HashSet<String>()
        val items = ArrayList<DramaSummary>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val videoData = item.optJSONObject("video_data")?.takeIf { it.length() > 0 } ?: item
            if (videoData.optBoolean("disable_play", false)) continue
            val hasEpField = videoData.has("episode_cnt") || videoData.has("episode_count")
            val epCnt = videoData.optInt("episode_cnt", videoData.optInt("episode_count", 0))
            if (hasEpField && epCnt <= 0) continue
            val sid = videoData.optString("series_id").ifEmpty { item.optString("series_id") }.trim()
            val title = cleanTitle(
                videoData.optString("series_name").ifEmpty { videoData.optString("series_title").ifEmpty { item.optString("name") } }
            )
            if (!Regex("^\\d{6,}$").matches(sid) || title.isEmpty() || !seen.add(sid)) continue
            items.add(
                DramaSummary(
                    seriesId = sid,
                    title = title,
                    cover = compatibleCover(videoData.optString("series_cover").ifEmpty { videoData.optString("cover") }),
                    episodeCount = epCnt.coerceAtLeast(0),
                    description = cleanTitle(videoData.optString("series_intro"))
                )
            )
        }
        return SearchPageResult(items = items, page = page, hasMore = items.size >= 8 && page < 10, cursor = null)
    }

    // ---- Series Detail & Live Zero-Cache Related Dramas ----

    fun peekSeriesDetail(seriesId: String): DramaDetail? {
        val cached = detailCache[seriesId] ?: return null
        return if (cached.first > System.currentTimeMillis()) cached.second else null
    }

    fun warmDetailCache(detail: DramaDetail) {
        if (detail.seriesId.isNotBlank() && detail.episodes.isNotEmpty()) {
            detailCache.putIfAbsent(detail.seriesId, (System.currentTimeMillis() + 300_000L) to detail)
        }
    }

    private fun mergeCandidates(
        base: List<DramaSummary>,
        incoming: List<DramaSummary>
    ): List<DramaSummary> {
        val map = LinkedHashMap<String, DramaSummary>()
        for (item in base) {
            if (item.seriesId.isNotBlank()) {
                val prev = map[item.seriesId]
                map[item.seriesId] = if (prev == null) item else SeriesSeasonMatcher.preferBetterSummary(prev, item)
            }
        }
        for (item in incoming) {
            if (item.seriesId.isNotBlank()) {
                val prev = map[item.seriesId]
                map[item.seriesId] = if (prev == null) item else SeriesSeasonMatcher.preferBetterSummary(prev, item)
            }
        }
        return map.values.toList()
    }

    suspend fun fetchRelatedDramas(current: DramaSummary): RelatedDramaBundle = withContext(Dispatchers.IO) {
        val keyword = SeriesSeasonMatcher.extractSearchKeyword(current.title)
        if (keyword.isBlank() || SeriesSeasonMatcher.normalizeForMatch(keyword).length < 2) {
            return@withContext RelatedDramaBundle(
                keyword = current.title,
                seasons = emptyList(),
                related = emptyList()
            )
        }

        val appP1Job = async { runCatching { searchDramas(keyword = keyword, page = 1) } }
        val webP1Job = async { runCatching { searchWebsite(keyword = keyword, page = 1) } }
        val appP1Res = appP1Job.await()
        val webP1Res = webP1Job.await()
        val appP1 = appP1Res.getOrNull()
        val webP1 = webP1Res.getOrNull()
        if (appP1 == null && webP1 == null) {
            throw (appP1Res.exceptionOrNull() ?: webP1Res.exceptionOrNull() ?: IllegalStateException("相关作品搜索失败"))
        }

        var merged = mergeCandidates(appP1?.items.orEmpty(), webP1?.items.orEmpty())
        val officialSeriesIds = LinkedHashSet<String>().apply {
            appP1?.officialSeriesIds?.let { addAll(it) }
        }
        val officialOtherVersionIds = LinkedHashSet<String>().apply {
            appP1?.officialOtherVersionIds?.let { addAll(it) }
        }

        val initialClassified = SeriesSeasonMatcher.classifyRelatedDramas(
            current = current,
            searchCandidates = merged,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )

        var nextPage = 2
        var hasMore = (appP1?.hasMore == true) || (webP1?.hasMore == true)
        var cursor: SearchCursor? = appP1?.cursor

        if (appP1 != null && appP1.hasMore && initialClassified.second.size < 6) {
            val appP2 = runCatching {
                searchDramas(keyword = keyword, page = 2, cursor = appP1.cursor)
            }.getOrNull()
            if (appP2 != null) {
                merged = mergeCandidates(merged, appP2.items)
                officialSeriesIds.addAll(appP2.officialSeriesIds)
                officialOtherVersionIds.addAll(appP2.officialOtherVersionIds)
                nextPage = 3
                hasMore = appP2.hasMore
                cursor = appP2.cursor
            }
        }

        var finalClassified = SeriesSeasonMatcher.classifyRelatedDramas(
            current = current,
            searchCandidates = merged,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )

        if (finalClassified.first.size < 2) {
            val shortPrefix = SeriesSeasonMatcher.extractShortPrefixKeyword(keyword)
            if (shortPrefix != null) {
                val extraWeb = runCatching { searchWebsite(keyword = shortPrefix, page = 1) }.getOrNull()
                if (extraWeb != null && extraWeb.items.isNotEmpty()) {
                    merged = mergeCandidates(merged, extraWeb.items)
                    finalClassified = SeriesSeasonMatcher.classifyRelatedDramas(
                        current = current,
                        searchCandidates = merged,
                        officialSeriesIds = officialSeriesIds,
                        officialOtherVersionIds = officialOtherVersionIds
                    )
                }
            }
        }

        RelatedDramaBundle(
            keyword = keyword,
            seasons = finalClassified.first,
            related = finalClassified.second,
            nextPage = nextPage,
            hasMore = hasMore,
            cursor = cursor,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )
    }

    suspend fun fetchMoreRelatedDramas(
        current: DramaSummary,
        bundle: RelatedDramaBundle
    ): RelatedDramaBundle = withContext(Dispatchers.IO) {
        if (!bundle.hasMore || bundle.keyword.isBlank()) return@withContext bundle

        val pageRes = if (bundle.cursor != null) {
            searchDramas(keyword = bundle.keyword, page = bundle.nextPage, cursor = bundle.cursor)
        } else {
            searchWebsite(keyword = bundle.keyword, page = bundle.nextPage)
        }

        val existing = bundle.seasons.map { it.summary } + bundle.related.map { it.summary }
        val existingIds = existing.mapTo(HashSet()) { it.seriesId }
        val newUniqueCount = pageRes.items.count { it.seriesId !in existingIds }
        val merged = mergeCandidates(existing, pageRes.items)
        val officialSeriesIds = (bundle.officialSeriesIds + pageRes.officialSeriesIds)
        val officialOtherVersionIds = (bundle.officialOtherVersionIds + pageRes.officialOtherVersionIds)

        val classified = SeriesSeasonMatcher.classifyRelatedDramas(
            current = current,
            searchCandidates = merged,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )
        val moreAvailable = if (pageRes.cursor != null) {
            pageRes.hasMore
        } else {
            pageRes.hasMore && newUniqueCount > 0
        }

        bundle.copy(
            seasons = classified.first,
            related = classified.second,
            nextPage = bundle.nextPage + 1,
            hasMore = moreAvailable,
            cursor = pageRes.cursor,
            officialSeriesIds = officialSeriesIds,
            officialOtherVersionIds = officialOtherVersionIds
        )
    }

    suspend fun fetchSeriesDetail(seriesId: String, forceRefresh: Boolean = false): DramaDetail = withContext(Dispatchers.IO) {
        require(Regex("^\\d{6,30}$").matches(seriesId)) { "无效剧集编号" }
        val cached = detailCache[seriesId]
        if (!forceRefresh && cached != null && cached.first > System.currentTimeMillis()) {
            return@withContext cached.second
        }

        val appResult = runCatching { fetchSeriesDetailFromApp(seriesId) }.getOrNull()
        val result = appResult ?: fetchSeriesDetailFromWeb(seriesId)
        detailCache[seriesId] = (System.currentTimeMillis() + 300_000L) to result
        result
    }

    private suspend fun fetchSeriesDetailFromWeb(seriesId: String): DramaDetail = coroutineScope {
        val headers = mapOf("User-Agent" to WEB_UA, "Referer" to "$SITE/")
        val playerJob = async {
            parseWebSeriesDetail(
                executePlainGetText("$SITE/player/$seriesId", headers, timeoutMs = 4_500L),
                seriesId
            )
        }
        val detailJob = async {
            delay(500L)
            parseWebSeriesDetail(
                executePlainGetText("$SITE/detail?series_id=$seriesId", headers, timeoutMs = 4_500L),
                seriesId
            )
        }
        try {
            select<DramaDetail> {
                playerJob.onAwait { d -> detailJob.cancel(); d }
                detailJob.onAwait { d -> playerJob.cancel(); d }
            }
        } catch (firstErr: Exception) {
            // If the earlier route failed fast, give the remaining route a chance
            val second = runCatching {
                if (playerJob.isActive) playerJob.await() else if (detailJob.isActive) detailJob.await() else null
            }.getOrNull()
            playerJob.cancel()
            detailJob.cancel()
            second ?: throw firstErr
        }
    }

    private fun parseWebSeriesDetail(html: String, seriesId: String): DramaDetail {
        val loader = extractRouterData(html).optJSONObject("loaderData") ?: error("剧集详情数据缺失")
        val data = loader.optJSONObject("player_(series_id)/(vid)/page")
            ?: loader.optJSONObject("player_(series_id)/page")
            ?: loader.optJSONObject("detail_page")
            ?: error("未能解析剧集信息")
        if (data.has("isSuccess") && !data.optBoolean("isSuccess", true)) {
            error("官网剧集暂不可用")
        }
        val detail = data.optJSONObject("seriesDetail") ?: error("剧集详情为空")
        val actualSid = detail.optString("series_id", "").trim()
        if (actualSid.isNotEmpty() && actualSid != seriesId) {
            error("官网返回剧集标识不匹配")
        }
        val vids = detail.optJSONArray("vid_list") ?: error("分集列表为空")
        val episodes = ArrayList<EpisodeItem>(vids.length())
        for (i in 0 until vids.length()) {
            val vid = vids.optString(i, "").trim()
            if (Regex("^\\d{1,30}$").matches(vid)) {
                episodes.add(EpisodeItem(vid = vid, index = i + 1, title = "第${i + 1}集"))
            }
        }
        require(episodes.isNotEmpty()) { "暂无可播分集" }
        return DramaDetail(
            seriesId = seriesId,
            title = cleanTitle(detail.optString("series_name")).ifEmpty { "未命名短剧" },
            cover = compatibleCover(detail.optString("series_cover")),
            description = cleanTitle(detail.optString("series_intro")),
            episodeLabel = cleanTitle(detail.optString("episode_right_text")),
            episodes = episodes
        )
    }

    private suspend fun fetchSeriesDetailFromApp(seriesId: String): DramaDetail {
        detailThrottleMutex.withLock {
            val wait = detailReadyAtMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            detailReadyAtMs = System.currentTimeMillis() + 650L
        }
        var identity = activeIdentity()
        var activeCookie = currentCookieHeader()
        val bodyJson = JSONObject().apply {
            put("biz_param", JSONObject().apply {
                put("detail_page_version", 0)
                put("disable_digg_stat", false)
                put("need_all_video_definition", false)
                put("need_mp4_align", false)
                put("source", 7)
                put("use_os_player", false)
                put("use_server_dns", false)
            })
            put("dr_scene", "preload")
            put("series_id", seriesId)
        }.toString()

        var lastErr: Exception? = null
        var refreshedDevice = false
        for (host in LegacySigner.PRELOAD_PLAYBACK_HOSTS.take(4)) {
            val signed = LegacySigner.buildV5PlaybackRequest(
                payloadJson = bodyJson,
                identity = identity,
                hostOverride = host,
                cookieHeader = activeCookie,
                apiPath = "/novel/player/multi_video_detail/v1/",
                currentAppProfile = true
            )
            try {
                val res = executeSignedJson(
                    signed = signed,
                    method = "POST",
                    timeoutMs = 10_000L,
                    preserveLargeFields = listOf("series_id", "vid")
                )
                if (res.optInt("code", -1) != 0) continue
                val vd = res.optJSONObject("data")?.optJSONObject(seriesId)?.optJSONObject("video_data") ?: continue
                val vl = vd.optJSONArray("video_list") ?: continue
                val rawEps = ArrayList<Triple<Int, Boolean, EpisodeItem>>(vl.length())
                for (i in 0 until vl.length()) {
                    val item = vl.optJSONObject(i) ?: continue
                    val vid = item.optString("vid", "").trim()
                    if (!Regex("^\\d{1,30}$").matches(vid)) continue
                    val idx = item.optInt("vid_index", i + 1).coerceAtLeast(1)
                    val digg = item.optLong("digged_count", 0L).coerceAtLeast(0L)
                    val cmt = item.optLong("comment_count", 0L).coerceAtLeast(0L)
                    val isNewly = item.optBoolean("is_newly_update", false)
                    rawEps.add(
                        Triple(
                            idx,
                            isNewly,
                            EpisodeItem(vid = vid, index = idx, title = "第${idx}集", diggCount = digg, commentCount = cmt)
                        )
                    )
                }
                if (rawEps.isEmpty()) continue
                val sortedRows = rawEps.sortedBy { it.first }
                val sortedEps = sortedRows.mapIndexed { idx, pair ->
                    pair.third.copy(index = idx + 1, title = "第${idx + 1}集")
                }
                val newlyCount = sortedRows.count { it.second }
                val followedCnt = vd.optLong("followed_cnt", 0L).coerceAtLeast(0L)
                val seriesPlayCnt = vd.optLong("series_play_cnt", 0L).coerceAtLeast(0L)
                val epLabel = cleanTitle(vd.optString("episode_right_text")).ifEmpty { "全${sortedEps.size}集" }
                val isCompleted = parseCompletedStatus(epLabel)
                val createTimeSec = vd.optLong("create_time", 0L).coerceAtLeast(0L)
                val rawCat = vd.opt("category_schema")
                val categoryNames = DramaTagger.parseCategoryNames(rawCat)
                val displayTags = DramaTagger.pickDisplayTags(categoryNames)
                val parsedGender = DramaTagger.parseGender(rawCat)
                val gender = if (parsedGender >= 0) parsedGender else (ratingCache.get(seriesId)?.gender ?: -1)
                val quality = buildQuality(
                    seriesId = seriesId,
                    episodeDiggs = sortedEps.map { it.diggCount },
                    episodeComments = sortedEps.map { it.commentCount },
                    followedCount = followedCnt,
                    seriesPlayCount = seriesPlayCnt,
                    createTimeSec = createTimeSec,
                    isCompleted = isCompleted,
                    newlyUpdatedCount = newlyCount
                )
                return DramaDetail(
                    seriesId = seriesId,
                    title = cleanTitle(vd.optString("series_title")).ifEmpty { "未命名短剧" },
                    cover = compatibleCover(vd.optString("series_cover").ifEmpty { vd.optString("cover_url") }),
                    description = cleanTitle(vd.optString("series_intro")),
                    episodeLabel = epLabel,
                    episodes = sortedEps,
                    followedCount = followedCnt,
                    seriesPlayCount = seriesPlayCnt,
                    quality = quality,
                    categoryNames = categoryNames,
                    displayTags = displayTags,
                    gender = gender,
                    createTimeSec = createTimeSec,
                    isCompleted = isCompleted
                )
            } catch (e: Exception) {
                lastErr = e
                if (e is EmptyResponseException && !refreshedDevice) {
                    refreshedDevice = true
                    activeCookie = null
                    runCatching {
                        identity = registrar.getOrRegister(forceFresh = true, bypassCooldown = true)
                    }
                }
            }
        }
        throw (lastErr ?: IllegalStateException("剧集分集列表加载失败"))
    }

    // ---- Playback URL Resolution (Multi-Host V5/Legacy + Web Direct MP4 + Preload Fallback) ----

    suspend fun fetchEpisodePlayInfo(
        vid: String,
        preferredQuality: QualityPreference = QualityPreference.AUTO,
        seriesId: String? = null,
        bypassCooldown: Boolean = false
    ): EpisodePlayResult = withContext(Dispatchers.IO) {
        require(Regex("^\\d{1,30}$").matches(vid)) { "分集编号无效" }
        val prefetched = prefetchedVariantsCache[vid]?.takeIf {
            System.currentTimeMillis() - it.first < 4 * 60_000L
        }?.second
        if (preferredQuality == QualityPreference.AUTO && !bypassCooldown && !prefetched.isNullOrEmpty()) {
            return@withContext EpisodePlayResult(vid = vid, variants = prefetched)
        }

        playbackMutex.withLock {
            val wait = playbackReadyAtMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            playbackReadyAtMs = System.currentTimeMillis() + 450L
        }

        fun buildPlayPayload(deviceLevel: Int, drScene: String): String = JSONObject().apply {
            put("biz_param", JSONObject().apply {
                put("detail_page_version", 0)
                put("device_level", deviceLevel)
                put("disable_digg_stat", false)
                put("disable_video_relate_book", false)
                put("need_all_video_definition", true)
                put("need_mp4_align", false)
                put("use_os_player", false)
                put("use_server_dns", false)
                put("video_platform", 1024)
            })
            put("mixed_video_id_map", JSONObject().apply {
                put("1004", JSONArray(listOf(vid)))
            })
            put("dr_scene", drScene)
        }.toString()

        val preloadPayload = buildPlayPayload(deviceLevel = 2, drScene = "preload")
        val playerPayload = buildPlayPayload(deviceLevel = 1, drScene = "player")

        // Model endpoints require anonymous identity with NO account cookies
        var identity = registrar.getOrRegister(forceFresh = false, bypassCooldown = bypassCooldown)
        var lastErr: Exception? = null
        var v5ReturnedDataButUnusable = false
        var refreshedDevice = false

        // Tier 1: Primary V5 POST preload/v1 with current 7.4.5.32 profile (reading.snssdk.com + fallbacks)
        for (host in LegacySigner.PRELOAD_PLAYBACK_HOSTS.take(4)) {
            try {
                val signed = LegacySigner.buildV5PlaybackRequest(
                    payloadJson = preloadPayload,
                    identity = identity,
                    hostOverride = host,
                    cookieHeader = null,
                    apiPath = LegacySigner.PRELOAD_PLAYBACK_PATH,
                    currentAppProfile = true
                )
                val resp = executeSignedJson(signed, method = "POST", timeoutMs = 10_000L)
                return@withContext resolvePlayResponse(resp, vid, preferredQuality)
            } catch (e: Exception) {
                lastErr = e
                if (e !is EmptyResponseException && e !is java.io.IOException &&
                    e.message?.contains("(110001)") != true
                ) {
                    v5ReturnedDataButUnusable = true
                }
                if (!refreshedDevice &&
                    (e is EmptyResponseException || e.message?.contains("接口暂未提供") == true)
                ) {
                    refreshedDevice = true
                    runCatching {
                        identity = registrar.getOrRegister(forceFresh = true, bypassCooldown = true)
                    }
                    delay(300L)
                }
            }
        }

        // Tier 2: Bodyless V5 GET on video_tab across healthy hosts
        for (host in LegacySigner.VIDEO_TAB_HOSTS.take(4)) {
            try {
                val signed = LegacySigner.buildV5VideoTabRequest(
                    vid = vid,
                    identity = identity,
                    hostOverride = host,
                    cookieHeader = null
                )
                val resp = executeSignedJson(signed, method = "GET", timeoutMs = 10_000L)
                return@withContext resolvePlayResponse(resp, vid, preferredQuality)
            } catch (e: Exception) {
                lastErr = e
                if (e !is EmptyResponseException && e !is java.io.IOException &&
                    e.message?.contains("(110001)") != true
                ) {
                    v5ReturnedDataButUnusable = true
                }
                if (!refreshedDevice &&
                    (e is EmptyResponseException || e.message?.contains("接口暂未提供") == true)
                ) {
                    refreshedDevice = true
                    runCatching {
                        identity = registrar.getOrRegister(forceFresh = true, bypassCooldown = true)
                    }
                    delay(300L)
                }
            }
        }

        // Tier 3: V5 POST player/v1 and multi_video_model/v1 fallback (only when Tier 1 & 2 suffered empty/transient errors)
        if (!v5ReturnedDataButUnusable) {
            val postFallbacks = listOf(
                "/novel/player/multi_video_model/player/v1" to LegacySigner.CURRENT_PLAYBACK_HOST,
                "/novel/player/multi_video_model/v1/" to LegacySigner.PLAYBACK_HOSTS[0],
                "/novel/player/multi_video_model/v1/" to LegacySigner.PLAYBACK_HOSTS[1]
            )
            for ((path, host) in postFallbacks) {
                try {
                    val signed = LegacySigner.buildV5PlaybackRequest(
                        payloadJson = playerPayload,
                        identity = identity,
                        hostOverride = host,
                        cookieHeader = null,
                        apiPath = path,
                        currentAppProfile = true
                    )
                    val resp = executeSignedJson(signed, method = "POST", timeoutMs = 10_000L)
                    return@withContext resolvePlayResponse(resp, vid, preferredQuality)
                } catch (e: Exception) {
                    if (e !is EmptyResponseException && e !is java.io.IOException &&
                        e.message?.contains("(110001)") != true
                    ) {
                        v5ReturnedDataButUnusable = true
                    }
                    if (e !is EmptyResponseException || lastErr == null) {
                        lastErr = e
                    }
                }
            }
        }

        // Tier 4: Official Web Direct MP4 (rescues ByteVC2-only / App rate-limit / missing metadata)
        val webVariant = runCatching { fetchPlayUrlFromWeb(vid, seriesId) }.getOrNull()
        if (webVariant != null) {
            return@withContext EpisodePlayResult(vid = vid, variants = listOf(webVariant))
        }

        // Tier 5: App Legacy API only if V5 suffered transport/empty failures (avoid masking real business/codec errors)
        if (!v5ReturnedDataButUnusable) {
            for (host in LegacySigner.PLAYBACK_HOSTS.take(2)) {
                try {
                    val signed = LegacySigner.buildLegacyPlaybackRequest(
                        payloadJson = playerPayload,
                        identity = identity,
                        hostOverride = host,
                        cookieHeader = null
                    )
                    val resp = executeSignedJson(signed, method = "POST", timeoutMs = 10_000L)
                    return@withContext resolvePlayResponse(resp, vid, preferredQuality)
                } catch (e: Exception) {
                    if (e !is EmptyResponseException) lastErr = e
                }
            }
        }

        if (!prefetched.isNullOrEmpty()) {
            return@withContext EpisodePlayResult(vid = vid, variants = prefetched)
        }

        throw (lastErr ?: IllegalStateException("本集暂无可用片源"))
    }

    private fun resolvePlayResponse(
        resp: JSONObject,
        vid: String,
        preferredQuality: QualityPreference
    ): EpisodePlayResult {
        val bizCode = resp.optInt("Code", resp.optInt("code", 0))
        if (bizCode != 0) {
            error("接口暂未提供本集播放信息 ($bizCode)")
        }
        val dataObj = resp.optJSONObject("data") ?: error("播放响应缺少 data")
        val entries = dataObj.optJSONObject("1004") ?: dataObj.optJSONObject("video_model_data") ?: dataObj
        val entry = entries.optJSONObject(vid) ?: error("未返回所选分集信息")
        val modelRaw = entry.opt("video_model")
        val model = when (modelRaw) {
            is JSONObject -> modelRaw
            is String -> JSONObject(modelRaw)
            else -> error("分集 video_model 格式异常")
        }

        val videoListAny = model.opt("video_list")
        val hasVideoList = when (videoListAny) {
            is JSONObject -> videoListAny.length() > 0
            is JSONArray -> videoListAny.length() > 0
            else -> false
        }
        if (hasVideoList) {
            try {
                return parseModel(model, vid, preferredQuality, inline = true, requireEncryptionMetadata = true)
            } catch (e: IncompleteEncryptionException) {
                return fetchFallbackPlayModel(model, vid, preferredQuality, cause = e)
            } catch (e: ByteVc2OnlyException) {
                return fetchFallbackPlayModel(model, vid, preferredQuality, cause = e)
            }
        }

        return fetchFallbackPlayModel(model, vid, preferredQuality, cause = null)
    }

    private fun fetchFallbackPlayModel(
        model: JSONObject,
        vid: String,
        preferredQuality: QualityPreference,
        cause: Exception?
    ): EpisodePlayResult {
        val rawFallback = parseFallbackApi(model.opt("fallback_api"))
            ?: if (cause != null) throw cause else error("未返回本集片源地址")
        val checkedUrl = checkDomain(rawFallback, VOD_FALLBACK_DOMAINS)
        val parsedUrl = checkedUrl.toHttpUrlOrNull() ?: error("备用片源地址无效")
        val candidates = listOf(
            parsedUrl.newBuilder().setQueryParameter("codec_type", "1").build().toString(),
            parsedUrl.newBuilder().setQueryParameter("codec_type", "3").build().toString(),
            parsedUrl.newBuilder().setQueryParameter("codec_type", "0").setQueryParameter("force_codec", "h264").build().toString(),
            checkedUrl
        ).distinct()

        var lastErr: Exception? = cause
        for (url in candidates) {
            try {
                val rawText = executePlainGetText(url, mapOf("Accept" to "application/json"), timeoutMs = 10_000L)
                if (rawText.isBlank()) continue
                val outer = JSONObject(rawText)
                val info = outer.optJSONObject("video_info")?.optJSONObject("data") ?: continue
                return parseModel(info, vid, preferredQuality, inline = false, requireEncryptionMetadata = false)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: IllegalStateException("备用片源数据为空"))
    }

    private fun fetchPlayUrlFromWeb(vid: String, seriesId: String?): PlayVariant? {
        val candidates = ArrayList<String>()
        if (!seriesId.isNullOrBlank() && Regex("^\\d{1,30}$").matches(seriesId)) {
            candidates.add("$SITE/player/$seriesId/$vid")
        }
        candidates.add("$SITE/player/_/$vid")
        val headers = mapOf("User-Agent" to WEB_UA, "Referer" to "$SITE/")

        for (pageUrl in candidates) {
            val html = runCatching { executePlainGetText(pageUrl, headers, timeoutMs = 4_500L) }.getOrNull() ?: continue
            val router = runCatching { extractRouterData(html) }.getOrNull() ?: continue
            val data = router.optJSONObject("loaderData")?.optJSONObject("player_(series_id)/(vid)/page") ?: continue
            if (data.has("isSuccess") && !data.optBoolean("isSuccess", true)) continue
            if (data.optBoolean("protectedPlayback", false)) continue
            val seo = data.optJSONObject("publicVideoSeo") ?: continue
            if (!seo.optBoolean("available", false)) continue
            if (seo.optString("itemId") != vid) continue

            val ldScripts = Regex("<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE).findAll(html)
            for (match in ldScripts) {
                val attrs = match.groupValues[1]
                if (!attrs.contains("application/ld+json", ignoreCase = true)) continue
                val videoObj = runCatching { JSONObject(match.groupValues[2]) }.getOrNull() ?: continue
                if (videoObj.optString("@type") != "VideoObject") continue
                val rawContent = cleanUrl(videoObj.optString("contentUrl"))
                    .replace(Regex("([?&])Range=[^&]*", RegexOption.IGNORE_CASE), "$1")
                    .replace(Regex("[?&]$"), "")
                val checked = runCatching { checkDomain(rawContent, WEB_MEDIA_DOMAINS) }.getOrNull() ?: continue
                return PlayVariant(
                    url = checked,
                    contentKeyHex = null,
                    codec = "h264",
                    quality = "官网直链",
                    width = 0,
                    height = 0,
                    userAgent = WEB_UA,
                    referer = "$SITE/"
                )
            }
        }
        return null
    }

    private fun parseFallbackApi(value: Any?): String? {
        return when (value) {
            is String -> {
                val trimmed = value.trim()
                if (trimmed.startsWith("{")) JSONObject(trimmed).optString("fallback_api").takeIf { it.isNotEmpty() }
                else trimmed.takeIf { it.isNotEmpty() }
            }
            is JSONArray -> value.optString(0).takeIf { it.isNotEmpty() }
            is JSONObject -> value.optString("fallback_api").takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private data class CandidateRow(
        val item: JSONObject,
        val codec: String,
        val codecRank: Int,
        val quality: String,
        val pixels: Int,
        val width: Int,
        val height: Int
    )

    private fun extractVideoListItems(videoListAny: Any?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        when (videoListAny) {
            is JSONObject -> {
                val keys = videoListAny.keys()
                while (keys.hasNext()) {
                    videoListAny.optJSONObject(keys.next())?.let { out.add(it) }
                }
            }
            is JSONArray -> {
                for (i in 0 until videoListAny.length()) {
                    videoListAny.optJSONObject(i)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    private fun parseModel(
        info: JSONObject,
        vid: String,
        preferredQuality: QualityPreference,
        inline: Boolean,
        requireEncryptionMetadata: Boolean
    ): EpisodePlayResult {
        val rawItems = extractVideoListItems(info.opt("video_list"))
        if (rawItems.isEmpty()) error("片源列表为空")
        val rows = ArrayList<CandidateRow>()
        var sawByteVc2 = false
        for (item in rawItems) {
            val hasUrl = item.optString("main_url").isNotEmpty() ||
                item.optString("backup_url").isNotEmpty() ||
                (item.optJSONArray("backup_url_list")?.length() ?: 0) > 0
            if (!hasUrl) continue
            val meta = item.optJSONObject("video_meta")
            val codec = (item.optString("codec_type").ifEmpty { meta?.optString("codec_type").orEmpty() }).lowercase().trim()
            if (codec == "bytevc2") {
                sawByteVc2 = true
                continue // Android MediaCodec generally lacks ByteVC2 decoder
            }
            val codecRank = when (codec) {
                "h264", "avc", "avc1" -> 0
                "hevc", "h265", "h265_hvc1", "hvc1", "hev1", "bytevc1" -> 1
                else -> continue
            }
            val w = item.optInt("vwidth", item.optInt("width", meta?.optInt("vwidth", meta.optInt("width", 0)) ?: 0))
            val h = item.optInt("vheight", item.optInt("height", meta?.optInt("vheight", meta.optInt("height", 0)) ?: 0))
            val quality = item.optString("quality_desc")
                .ifEmpty { item.optString("definition") }
                .ifEmpty { meta?.optString("definition").orEmpty() }
                .ifEmpty { if (h > 0) "${minOf(w, h)}p" else "" }
            val pixels = if (w > 0 && h > 0) minOf(w, h) else Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: 0
            rows.add(CandidateRow(item, if (codec == "bytevc1") "hevc" else codec, codecRank, quality, pixels, w, h))
        }

        if (rows.isEmpty()) {
            if (sawByteVc2) throw ByteVc2OnlyException("本集仅提供 ByteVC2 编码片源，暂不支持播放")
            error("本集暂未提供兼容的 H.264/HEVC 片源")
        }

        val targetMinPx = preferredQuality.targetPixels

        val sorted = if (preferredQuality == QualityPreference.AUTO || targetMinPx <= 0) {
            rows.sortedWith(compareBy<CandidateRow> { it.codecRank }.thenByDescending { it.pixels })
        } else {
            rows.sortedWith(
                compareBy<CandidateRow> { it.pixels <= 0 }
                    .thenBy { kotlin.math.abs(it.pixels - targetMinPx) }
                    .thenByDescending { it.pixels >= targetMinPx }
                    .thenBy { it.codecRank }
            )
        }

        val seedBase64 = info.optString("key_seed", "")
        val seedBytes = if (seedBase64.isNotEmpty()) ByteHelpers.base64Decode(seedBase64) else null
        val variants = ArrayList<PlayVariant>()
        val seen = HashSet<String>()
        var incompleteEncryption = false

        for (row in sorted) {
            val item = row.item
            val spade = item.optString("spade_a").ifEmpty { item.optJSONObject("encrypt_info")?.optString("spade_a").orEmpty() }
            val encryptFlag = item.optJSONObject("encrypt_info")?.opt("encrypt") ?: item.opt("encrypt")
            val explicitlyUnencrypted = encryptFlag == false || encryptFlag == 0 || encryptFlag == "0" || encryptFlag == "false"
            if (requireEncryptionMetadata && spade.isEmpty() && !explicitlyUnencrypted) {
                if (encryptFlag == null || encryptFlag == JSONObject.NULL) incompleteEncryption = true
                continue
            }
            val keyHex = if (spade.isNotEmpty()) {
                runCatching { ByteHelpers.bytesToHex(SpadeDecryptor.deriveContentKey(spade)) }.getOrNull() ?: continue
            } else null

            val candidates = ArrayList<String>()
            item.optString("main_url").takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            item.optString("backup_url").takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            val blist = item.optJSONArray("backup_url_list")
            if (blist != null) {
                for (i in 0 until blist.length()) blist.optString(i).takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
            }

            for (rawUrl in candidates) {
                runCatching {
                    val decoded = if (seedBytes != null) SpadeDecryptor.decryptSpadeUrl(rawUrl, seedBytes) else cleanUrl(rawUrl)
                    val checked = checkDomain(decoded, MEDIA_DOMAINS)
                    val id = "$checked:${keyHex.orEmpty()}"
                    if (seen.add(id)) {
                        variants.add(
                            PlayVariant(
                                url = checked,
                                contentKeyHex = keyHex,
                                codec = row.codec,
                                quality = row.quality.ifEmpty { if (row.pixels > 0) "${row.pixels}p" else "自动" },
                                width = row.width,
                                height = row.height,
                                userAgent = if (inline) LegacySigner.PLAYBACK_UA else LegacySigner.VIDEO_UA
                            )
                        )
                    }
                }
            }
        }

        if (variants.isEmpty()) {
            if (incompleteEncryption) throw IncompleteEncryptionException("本集缺少解密元数据")
            error("本集片源地址或密钥无法解析")
        }
        return EpisodePlayResult(vid = vid, variants = variants)
    }

    private class EmptyResponseException(message: String) : RuntimeException(message)
    private class IncompleteEncryptionException(message: String) : RuntimeException(message)
    private class ByteVc2OnlyException(message: String) : RuntimeException(message)
}
