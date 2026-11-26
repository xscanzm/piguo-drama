// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.hongguo.mobile.core.ByteHelpers
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.HongguoApiClient
import io.github.hongguo.mobile.core.LocalStore
import io.github.hongguo.mobile.core.Mp4CencDecryptor
import io.github.hongguo.mobile.core.NetworkMonitor
import io.github.hongguo.mobile.core.PlayVariant
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.core.UserSettings
import io.github.hongguo.mobile.core.WarmFeedEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResolvedMediaSource(
    val uri: Uri,
    val userAgent: String,
    val qualityLabel: String,
    val codec: String,
    val isLocalFile: Boolean,
    val referer: String = "",
    val cacheKey: String = "",
    val fallbackSources: List<ResolvedMediaSource> = emptyList()
)

@OptIn(UnstableApi::class)
class MediaCacheManager(
    context: Context,
    private val api: HongguoApiClient,
    private val httpClient: OkHttpClient,
    private val protectedCacheFilenamesProvider: () -> Set<String> = { emptySet() },
    private val networkMonitor: NetworkMonitor? = null
) {
    private data class WarmPrecachePlan(
        val candidates: List<DramaSummary>,
        val settings: UserSettings,
        val excludedIds: Set<String>
    )

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "episodes_mp4").apply { mkdirs() }
    private val streamCacheDir = File(appContext.cacheDir, "media_stream_cache").apply { mkdirs() }
    private val streamCache: SimpleCache by lazy {
        SimpleCache(
            streamCacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_STREAM_CACHE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ResolvedMediaSource>>()
    private val resolvedSourceCache = ConcurrentHashMap<String, Pair<Long, ResolvedMediaSource>>()
    private val activePreloads = ConcurrentHashMap.newKeySet<String>()
    private val warmWorkerMutex = Mutex()
    private val warmFailedSeriesIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var latestWarmPlan: WarmPrecachePlan? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MAX_DECRYPTED_BYTES = 300L * 1024L * 1024L
        private const val MAX_STREAM_CACHE_BYTES = 200L * 1024L * 1024L
        private const val MAX_EPISODE_BYTES = 256L * 1024L * 1024L
        private const val RESOLVED_SOURCE_TTL_MS = 300_000L
        private const val MAX_RESOLVED_CACHE_ENTRIES = 120
        private const val PRELOAD_HEAD_BYTES = 1_536L * 1024L
        private const val PRELOAD_MIN_CACHED_BYTES = 256L * 1024L
    }

    private fun cacheFile(vid: String, quality: QualityPreference): File =
        cacheFileByKey(vid, quality.key)

    private fun cacheFileByKey(vid: String, qualityKey: String): File =
        File(cacheDir, "${vid}_${qualityKey}.mp4")

    fun hasLocalFile(vid: String, qualityKey: String): Boolean {
        if (vid.isBlank()) return false
        val cached = cacheFileByKey(vid, qualityKey)
        return cached.exists() && cached.length() > 1024L
    }

    fun peekLocalFileSource(vid: String, qualityKey: String): ResolvedMediaSource? {
        if (vid.isBlank()) return null
        val cached = cacheFileByKey(vid, qualityKey)
        if (!cached.exists() || cached.length() <= 1024L) return null
        cached.setLastModified(System.currentTimeMillis())
        val label = QualityPreference.entries.firstOrNull { it.key == qualityKey }?.label ?: qualityKey
        return ResolvedMediaSource(
            uri = Uri.fromFile(cached),
            userAgent = "",
            qualityLabel = label,
            codec = "cached",
            isLocalFile = true,
            cacheKey = "${vid}_${qualityKey}"
        )
    }

    fun peekResolvedSource(vid: String, quality: QualityPreference): ResolvedMediaSource? {
        val cached = cacheFile(vid, quality)
        if (cached.exists() && cached.length() > 1024L) {
            return ResolvedMediaSource(
                uri = Uri.fromFile(cached),
                userAgent = "",
                qualityLabel = quality.label,
                codec = "cached",
                isLocalFile = true,
                cacheKey = "${vid}_${quality.key}"
            )
        }
        val key = "${vid}_${quality.key}"
        val mem = resolvedSourceCache[key] ?: return null
        return if (mem.first > System.currentTimeMillis()) mem.second else null
    }

    fun isOnWifi(): Boolean {
        networkMonitor?.status?.value?.let { return it.isWifi }
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }.getOrDefault(true)
    }

    fun effectiveQuality(settings: UserSettings, isWifi: Boolean = isOnWifi()): QualityPreference {
        val chosen = QualityPreference.entries.firstOrNull { it.key == settings.quality } ?: QualityPreference.AUTO
        if (settings.cellularAutoLowQuality && !isWifi) {
            return when (chosen) {
                QualityPreference.P480, QualityPreference.P360 -> chosen
                else -> QualityPreference.P480
            }
        }
        return chosen
    }

    fun invalidateSource(vid: String, quality: QualityPreference) {
        if (vid.isBlank()) return
        val key = "${vid}_${quality.key}"
        resolvedSourceCache.remove(key)
        runCatching {
            val f = cacheFile(vid, quality)
            if (f.exists()) f.delete()
        }
        runCatching {
            streamCache.removeResource(key)
            for (idx in 1..4) {
                streamCache.removeResource("${key}_s$idx")
            }
        }
    }

    private fun pruneResolvedCacheIfNeeded() {
        if (resolvedSourceCache.size <= MAX_RESOLVED_CACHE_ENTRIES) return
        val now = System.currentTimeMillis()
        val it = resolvedSourceCache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.first <= now) it.remove()
        }
        if (resolvedSourceCache.size > MAX_RESOLVED_CACHE_ENTRIES) {
            val surplus = resolvedSourceCache.size - MAX_RESOLVED_CACHE_ENTRIES
            resolvedSourceCache.entries
                .sortedBy { it.value.first }
                .take(surplus)
                .forEach { resolvedSourceCache.remove(it.key) }
        }
    }

    private fun buildOkHttpDataSourceFactory(source: ResolvedMediaSource): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(httpClient).apply {
            if (source.userAgent.isNotEmpty()) setUserAgent(source.userAgent)
            if (source.referer.isNotEmpty()) {
                setDefaultRequestProperties(mapOf("Referer" to source.referer))
            }
        }

    fun createPlaybackDataSourceFactory(source: ResolvedMediaSource): DataSource.Factory {
        if (source.isLocalFile) {
            return DefaultDataSource.Factory(appContext)
        }
        val okHttpFactory = buildOkHttpDataSourceFactory(source)
        return CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(okHttpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun resolvePlayableSource(
        vid: String,
        quality: QualityPreference,
        seriesId: String? = null,
        bypassCooldown: Boolean = false
    ): ResolvedMediaSource {
        val cached = cacheFile(vid, quality)
        if (cached.exists() && cached.length() > 1024L) {
            cached.setLastModified(System.currentTimeMillis())
            return ResolvedMediaSource(
                uri = Uri.fromFile(cached),
                userAgent = "",
                qualityLabel = quality.label,
                codec = "cached",
                isLocalFile = true,
                cacheKey = "${vid}_${quality.key}"
            )
        }

        val key = "${vid}_${quality.key}"
        if (!bypassCooldown) {
            val mem = resolvedSourceCache[key]
            if (mem != null && mem.first > System.currentTimeMillis()) {
                return mem.second
            }
        }

        val existing = inflight[key]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<ResolvedMediaSource>()
        val prev = inflight.putIfAbsent(key, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                val playResult = api.fetchEpisodePlayInfo(
                    vid = vid,
                    preferredQuality = quality,
                    seriesId = seriesId,
                    bypassCooldown = bypassCooldown
                )
                val plainVariants = playResult.variants.filter { it.contentKeyHex.isNullOrEmpty() }
                val plainSources = plainVariants.mapIndexed { idx, v ->
                    ResolvedMediaSource(
                        uri = Uri.parse(v.url),
                        userAgent = v.userAgent,
                        qualityLabel = v.quality,
                        codec = v.codec,
                        isLocalFile = false,
                        referer = v.referer,
                        cacheKey = if (idx == 0) key else "${key}_s$idx"
                    )
                }

                var lastErr: Throwable? = null
                for (variant in playResult.variants) {
                    if (variant.contentKeyHex.isNullOrEmpty()) {
                        val idx = plainVariants.indexOf(variant)
                        val primary = if (idx >= 0) plainSources[idx] else ResolvedMediaSource(
                            uri = Uri.parse(variant.url),
                            userAgent = variant.userAgent,
                            qualityLabel = variant.quality,
                            codec = variant.codec,
                            isLocalFile = false,
                            referer = variant.referer,
                            cacheKey = key
                        )
                        val fallbacks = if (idx >= 0) plainSources.drop(idx + 1) else emptyList()
                        return@withContext primary.copy(fallbackSources = fallbacks)
                    }
                    try {
                        val resolved = prepareEncryptedVariant(vid, quality, variant)
                        return@withContext resolved
                    } catch (e: Throwable) {
                        lastErr = e
                    }
                }
                if (plainSources.isNotEmpty()) {
                    return@withContext plainSources.first().copy(fallbackSources = plainSources.drop(1))
                }
                throw (lastErr ?: IllegalStateException("所有片源候选均加载失败"))
            }
            if (!result.isLocalFile) {
                resolvedSourceCache[key] = (System.currentTimeMillis() + RESOLVED_SOURCE_TTL_MS) to result
                pruneResolvedCacheIfNeeded()
            }
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inflight.remove(key, deferred)
        }
    }

    fun preloadInBackground(
        vids: List<String>,
        quality: QualityPreference,
        seriesId: String? = null,
        wifiOnly: Boolean = false
    ) {
        if (wifiOnly && !isOnWifi()) return
        for (vid in vids.distinct().take(2)) {
            val cached = cacheFile(vid, quality)
            val key = "${vid}_${quality.key}"
            if (cached.exists() && cached.length() > 1024L) continue
            if (inflight.containsKey(key) || activePreloads.contains(key)) continue
            activePreloads.add(key)
            scope.launch {
                try {
                    val resolved = resolvePlayableSource(vid, quality, seriesId = seriesId)
                    if (!resolved.isLocalFile && resolved.cacheKey.isNotEmpty()) {
                        val alreadyCached = runCatching {
                            streamCache.getCachedBytes(resolved.cacheKey, 0L, PRELOAD_HEAD_BYTES)
                        }.getOrDefault(0L)
                        if (alreadyCached < PRELOAD_MIN_CACHED_BYTES) {
                            runCatching {
                                val dataSource = CacheDataSource.Factory()
                                    .setCache(streamCache)
                                    .setUpstreamDataSourceFactory(buildOkHttpDataSourceFactory(resolved))
                                    .createDataSourceForDownloading()
                                val dataSpec = DataSpec.Builder()
                                    .setUri(resolved.uri)
                                    .setKey(resolved.cacheKey)
                                    .setPosition(0L)
                                    .setLength(PRELOAD_HEAD_BYTES)
                                    .build()
                                CacheWriter(dataSource, dataSpec, null, null).cache()
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    activePreloads.remove(key)
                }
            }
        }
    }

    private fun prepareEncryptedVariant(
        vid: String,
        quality: QualityPreference,
        variant: PlayVariant
    ): ResolvedMediaSource {
        val keyHex = variant.contentKeyHex ?: error("缺少解密密钥")
        val outFile = cacheFile(vid, quality)
        val tmpEncrypted = File(cacheDir, "${vid}_${quality.key}.enc.tmp")
        val tmpDecrypted = File(cacheDir, "${vid}_${quality.key}.dec.tmp")
        try {
            val dlClient = httpClient.newBuilder()
                .callTimeout(45, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            fun executeDownload(refererToSend: String): Int {
                val req = Request.Builder()
                    .url(variant.url)
                    .apply {
                        if (variant.userAgent.isNotEmpty()) header("User-Agent", variant.userAgent)
                        if (refererToSend.isNotEmpty()) header("Referer", refererToSend)
                    }
                    .get()
                    .build()
                dlClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return resp.code
                    val body = resp.body ?: error("媒体响应为空")
                    val len = body.contentLength()
                    if (len > MAX_EPISODE_BYTES) error("单集文件过大")
                    body.byteStream().use { input ->
                        tmpEncrypted.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > MAX_EPISODE_BYTES) error("单集文件超过大小限制")
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    return resp.code
                }
            }

            var code = executeDownload(variant.referer)
            if (code == 403) {
                val altReferer = if (variant.referer.isEmpty()) "https://novelquickapp.com/" else ""
                code = executeDownload(altReferer)
            }
            if (code !in 200..299) error("媒体下载失败 (HTTP $code)")
            val keyBytes = ByteHelpers.hexToBytes(keyHex)
            Mp4CencDecryptor.decryptFile(tmpEncrypted, tmpDecrypted, keyBytes)
            if (outFile.exists()) outFile.delete()
            if (!tmpDecrypted.renameTo(outFile)) {
                tmpDecrypted.copyTo(outFile, overwrite = true)
                tmpDecrypted.delete()
            }
            evictIfNeeded()
            return ResolvedMediaSource(
                uri = Uri.fromFile(outFile),
                userAgent = variant.userAgent,
                qualityLabel = variant.quality,
                codec = variant.codec,
                isLocalFile = true
            )
        } finally {
            if (tmpEncrypted.exists()) tmpEncrypted.delete()
            if (tmpDecrypted.exists()) tmpDecrypted.delete()
        }
    }

    suspend fun downloadToLocalCache(
        vid: String,
        quality: QualityPreference,
        seriesId: String? = null
    ): ResolvedMediaSource? {
        peekLocalFileSource(vid, quality.key)?.let { return it }
        val resolved = resolvePlayableSource(vid, quality, seriesId = seriesId)
        if (resolved.isLocalFile) return resolved

        val target = cacheFile(vid, quality)
        val tmp = File(cacheDir, "${vid}_${quality.key}.dl.tmp")
        val candidates = listOf(resolved) + resolved.fallbackSources
        return withContext(Dispatchers.IO) {
            try {
                val dlClient = httpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .build()
                for (cand in candidates) {
                    if (cand.isLocalFile) return@withContext cand
                    val ok = runCatching {
                        val req = Request.Builder()
                            .url(cand.uri.toString())
                            .apply {
                                if (cand.userAgent.isNotEmpty()) header("User-Agent", cand.userAgent)
                                if (cand.referer.isNotEmpty()) header("Referer", cand.referer)
                            }
                            .get()
                            .build()
                        dlClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("HTTP ${resp.code}")
                            val body = resp.body ?: error("响应为空")
                            val len = body.contentLength()
                            if (len > MAX_EPISODE_BYTES) error("单集文件过大")
                            var total = 0L
                            body.byteStream().use { input ->
                                tmp.outputStream().use { output ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        total += n
                                        if (total > MAX_EPISODE_BYTES) error("单集文件超过大小限制")
                                        output.write(buf, 0, n)
                                    }
                                }
                            }
                            if (total <= 8 * 1024L) error("缓存体积异常")
                        }
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        if (target.exists()) target.delete()
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                        target.setLastModified(System.currentTimeMillis())
                        evictIfNeeded()
                        return@withContext peekLocalFileSource(vid, quality.key)
                    } else if (tmp.exists()) {
                        tmp.delete()
                    }
                }
                null
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    fun scheduleWarmFeedPrecache(
        candidates: List<DramaSummary>,
        settings: UserSettings,
        localStore: LocalStore,
        excludedIds: Set<String>
    ) {
        latestWarmPlan = WarmPrecachePlan(
            candidates = candidates,
            settings = settings,
            excludedIds = excludedIds
        )
        if (!settings.preloadNextEpisode || (settings.wifiOnlyPreload && !isOnWifi())) return

        scope.launch {
            if (!warmWorkerMutex.tryLock()) return@launch
            try {
                val extraFetched = ArrayList<DramaSummary>()
                var extraFetchAttempts = 0
                for (step in 0 until 16) {
                    val plan = latestWarmPlan ?: break
                    if (!plan.settings.preloadNextEpisode || (plan.settings.wifiOnlyPreload && !isOnWifi())) {
                        break
                    }
                    val targetCount = if (isOnWifi()) 10 else 3
                    val combinedExcluded = buildSet {
                        addAll(plan.excludedIds)
                        addAll(localStore.seenSeriesIdsSnapshot())
                        addAll(localStore.feedPreferences.value.dislikedSeriesIds)
                        for (h in localStore.history.value) add(h.seriesId)
                        for (f in localStore.favorites.value) add(f.seriesId)
                    }
                    val validWarm = localStore.pruneWarmFeedEntries(combinedExcluded) { entry ->
                        hasLocalFile(entry.vid, entry.qualityKey)
                    }
                    if (validWarm.size >= targetCount) break

                    val warmIds = validWarm.mapTo(HashSet()) { it.summary.seriesId }
                    val pool = plan.candidates + extraFetched
                    var nextCandidate = pool.firstOrNull { cand ->
                        cand.seriesId !in combinedExcluded &&
                            cand.seriesId !in warmIds &&
                            cand.seriesId !in warmFailedSeriesIds
                    }
                    if (nextCandidate == null && extraFetchAttempts < 2) {
                        val pageToFetch = 2 + extraFetchAttempts
                        extraFetchAttempts++
                        val fetched = runCatching {
                            api.fetchRecommendFeed("hot-drama", page = pageToFetch, forceRefresh = false)
                        }.getOrNull()?.items.orEmpty()
                        val existing = pool.mapTo(HashSet()) { it.seriesId }
                        for (item in fetched) {
                            if (existing.add(item.seriesId)) extraFetched.add(item)
                        }
                        nextCandidate = extraFetched.firstOrNull { cand ->
                            cand.seriesId !in combinedExcluded &&
                                cand.seriesId !in warmIds &&
                                cand.seriesId !in warmFailedSeriesIds
                        }
                    }
                    val candidate = nextCandidate ?: break

                    val quality = effectiveQuality(plan.settings)
                    var detail = api.peekSeriesDetail(candidate.seriesId)
                    val vid = candidate.initialVid.ifBlank {
                        if (detail == null) {
                            detail = runCatching { api.fetchSeriesDetail(candidate.seriesId) }.getOrNull()
                        }
                        detail?.episodes?.getOrNull((candidate.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                            ?: detail?.episodes?.firstOrNull()?.vid
                            ?: ""
                    }
                    if (vid.isBlank()) {
                        warmFailedSeriesIds.add(candidate.seriesId)
                        continue
                    }

                    val cachedMedia = runCatching {
                        downloadToLocalCache(vid = vid, quality = quality, seriesId = candidate.seriesId)
                    }.getOrNull()
                    if (cachedMedia == null) {
                        warmFailedSeriesIds.add(candidate.seriesId)
                        delay(500L)
                        continue
                    }

                    if (detail == null) {
                        detail = runCatching { api.fetchSeriesDetail(candidate.seriesId) }.getOrNull()
                    }
                    val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                        ?: candidate.initialEpisodeIndex

                    if (candidate.seriesId !in localStore.seenSeriesIdsSnapshot() &&
                        candidate.seriesId !in localStore.feedPreferences.value.dislikedSeriesIds
                    ) {
                        localStore.upsertWarmFeedEntry(
                            WarmFeedEntry(
                                summary = candidate,
                                detail = detail,
                                vid = vid,
                                episodeIndex = epIdx,
                                qualityKey = quality.key
                            )
                        )
                    }
                    delay(800L)
                }
            } finally {
                warmWorkerMutex.unlock()
            }
        }
    }

    fun getCacheSizeBytes(): Long {
        val decryptedBytes = cacheDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        val streamBytes = runCatching { streamCache.cacheSpace }.getOrDefault(0L)
        return decryptedBytes + streamBytes
    }

    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        resolvedSourceCache.clear()
        warmFailedSeriesIds.clear()
        runCatching {
            for (k in streamCache.keys.toList()) {
                runCatching { streamCache.removeResource(k) }
            }
        }
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DECRYPTED_BYTES) return

        val protectedNames = protectedCacheFilenamesProvider()
        val (protectedFiles, regularFiles) = files.partition { it.name in protectedNames }
        for (f in regularFiles + protectedFiles) {
            if (total <= MAX_DECRYPTED_BYTES) break
            val sz = f.length()
            if (f.delete()) total -= sz
        }
    }
}
