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
import io.github.hongguo.mobile.core.DramaDetail
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

internal data class RollingPrecacheTarget(
    val summary: DramaSummary,
    val fullLocalDownload: Boolean,
    val aheadIndex: Int
)

internal fun computeRollingPrecacheTargetCount(
    isWifi: Boolean,
    wifiOnlyPreload: Boolean,
    preloadEnabled: Boolean
): Int {
    if (!preloadEnabled) return 0
    if (wifiOnlyPreload && !isWifi) return 0
    return if (isWifi) 15 else 4
}

internal fun computeFeedLowWaterMark(
    isWifi: Boolean,
    wifiOnlyPreload: Boolean,
    preloadEnabled: Boolean
): Int {
    if (!preloadEnabled) return 3
    if (wifiOnlyPreload && !isWifi) return 3
    return if (isWifi) 10 else 4
}

internal fun selectRollingPrecacheTargets(
    upcomingCandidates: List<DramaSummary>,
    excludedSeriesIds: Set<String>,
    failedSeriesIds: Set<String>,
    targetCount: Int,
    fullLocalAheadCount: Int = 2
): List<RollingPrecacheTarget> {
    if (targetCount <= 0) return emptyList()
    val seen = HashSet<String>()
    val result = ArrayList<RollingPrecacheTarget>(targetCount)
    for (cand in upcomingCandidates) {
        val sid = cand.seriesId.trim()
        if (sid.isEmpty() || sid in excludedSeriesIds || sid in failedSeriesIds || !seen.add(sid)) continue
        val idx = result.size
        result.add(
            RollingPrecacheTarget(
                summary = cand,
                fullLocalDownload = idx < fullLocalAheadCount,
                aheadIndex = idx
            )
        )
        if (result.size >= targetCount) break
    }
    return result
}

@OptIn(UnstableApi::class)
class MediaCacheManager(
    context: Context,
    private val api: HongguoApiClient,
    private val httpClient: OkHttpClient,
    private val protectedCacheFilenamesProvider: () -> Set<String> = { emptySet() },
    private val networkMonitor: NetworkMonitor? = null
) {
    private class RollingPrecachePlan(
        val generation: Long,
        val channelKey: String,
        val candidates: List<DramaSummary>,
        val settings: UserSettings,
        val localStore: LocalStore,
        val excludedIds: Set<String>,
        val persistWarmStore: Boolean,
        val enabled: Boolean,
        val onImmediateNextReady: ((seriesId: String, media: ResolvedMediaSource, detail: DramaDetail?, episodeIndex: Int) -> Unit)?
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
    private val foregroundResolveCount = AtomicInteger(0)
    @Volatile
    private var foregroundPlaybackReady: Boolean = true
    @Volatile
    private var foregroundWaitSinceMs: Long = 0L
    private val rollingPlanGeneration = AtomicLong(0L)
    @Volatile
    private var latestRollingPlan: RollingPrecachePlan? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MAX_DECRYPTED_BYTES = 350L * 1024L * 1024L
        private const val MAX_STREAM_CACHE_BYTES = 300L * 1024L * 1024L
        private const val MAX_EPISODE_BYTES = 256L * 1024L * 1024L
        private const val RESOLVED_SOURCE_TTL_MS = 300_000L
        private const val MAX_RESOLVED_CACHE_ENTRIES = 120
        private const val PRELOAD_HEAD_BYTES = 2_560L * 1024L
        private const val PRELOAD_MIN_CACHED_BYTES = 512L * 1024L
        private const val FULL_LOCAL_AHEAD_COUNT = 2
    }

    fun setForegroundPlaybackReady(ready: Boolean) {
        foregroundPlaybackReady = ready
        if (ready) {
            foregroundWaitSinceMs = 0L
        } else if (foregroundWaitSinceMs == 0L) {
            foregroundWaitSinceMs = System.currentTimeMillis()
        }
    }

    private fun shouldYieldBackgroundToForeground(): Boolean {
        if (foregroundResolveCount.get() > 0) return true
        if (!foregroundPlaybackReady) {
            val started = foregroundWaitSinceMs
            if (started > 0L && (System.currentTimeMillis() - started) < 6_000L) {
                return true
            }
        }
        return false
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
        bypassCooldown: Boolean = false,
        isBackground: Boolean = false
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

        if (!isBackground) {
            foregroundResolveCount.incrementAndGet()
        }
        try {
            val result = withContext(Dispatchers.IO) {
                val playResult = api.fetchEpisodePlayInfo(
                    vid = vid,
                    preferredQuality = quality,
                    seriesId = seriesId,
                    bypassCooldown = bypassCooldown,
                    isBackground = isBackground
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
            if (!isBackground) {
                foregroundResolveCount.decrementAndGet()
            }
            inflight.remove(key, deferred)
        }
    }

    fun isStreamHeadCached(source: ResolvedMediaSource): Boolean {
        if (source.isLocalFile) return true
        if (source.cacheKey.isBlank()) return false
        val cached = runCatching {
            streamCache.getCachedBytes(source.cacheKey, 0L, PRELOAD_HEAD_BYTES)
        }.getOrDefault(0L)
        return cached >= PRELOAD_MIN_CACHED_BYTES
    }

    private fun cacheStreamHead(source: ResolvedMediaSource): Boolean {
        if (source.isLocalFile || source.cacheKey.isBlank()) return true
        if (isStreamHeadCached(source)) return true
        val dataSource = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(buildOkHttpDataSourceFactory(source))
            .createDataSourceForDownloading()
        val dataSpec = DataSpec.Builder()
            .setUri(source.uri)
            .setKey(source.cacheKey)
            .setPosition(0L)
            .setLength(PRELOAD_HEAD_BYTES)
            .build()
        return runCatching {
            CacheWriter(dataSource, dataSpec, null, null).cache()
            isStreamHeadCached(source)
        }.getOrDefault(false)
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
                    val resolved = resolvePlayableSource(vid, quality, seriesId = seriesId, isBackground = true)
                    if (!resolved.isLocalFile && resolved.cacheKey.isNotEmpty()) {
                        cacheStreamHead(resolved)
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
        seriesId: String? = null,
        isBackground: Boolean = false
    ): ResolvedMediaSource? {
        peekLocalFileSource(vid, quality.key)?.let { return it }
        val resolved = resolvePlayableSource(vid, quality, seriesId = seriesId, isBackground = isBackground)
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
                                        if (isBackground && foregroundResolveCount.get() > 0) {
                                            Thread.sleep(60L)
                                        }
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

    fun pauseRollingFeedPrecache() {
        val gen = rollingPlanGeneration.incrementAndGet()
        latestRollingPlan = latestRollingPlan?.let { prev ->
            RollingPrecachePlan(
                generation = gen,
                channelKey = prev.channelKey,
                candidates = prev.candidates,
                settings = prev.settings,
                localStore = prev.localStore,
                excludedIds = prev.excludedIds,
                persistWarmStore = prev.persistWarmStore,
                enabled = false,
                onImmediateNextReady = null
            )
        }
    }

    fun scheduleRollingFeedPrecache(
        channelKey: String,
        candidates: List<DramaSummary>,
        settings: UserSettings,
        localStore: LocalStore,
        excludedIds: Set<String>,
        persistWarmStore: Boolean,
        enabled: Boolean = true,
        onImmediateNextReady: ((seriesId: String, media: ResolvedMediaSource, detail: DramaDetail?, episodeIndex: Int) -> Unit)? = null
    ) {
        val gen = rollingPlanGeneration.incrementAndGet()
        latestRollingPlan = RollingPrecachePlan(
            generation = gen,
            channelKey = channelKey,
            candidates = candidates,
            settings = settings,
            localStore = localStore,
            excludedIds = excludedIds,
            persistWarmStore = persistWarmStore,
            enabled = enabled,
            onImmediateNextReady = onImmediateNextReady
        )
        if (!enabled || !settings.preloadNextEpisode || (settings.wifiOnlyPreload && !isOnWifi())) return

        scope.launch {
            if (!warmWorkerMutex.tryLock()) return@launch
            try {
                var prunedGeneration = -1L
                val notifiedImmediateNextKeys = HashSet<String>()
                for (step in 0 until 200) {
                    val plan = latestRollingPlan ?: break
                    if (!plan.enabled || !plan.settings.preloadNextEpisode || (plan.settings.wifiOnlyPreload && !isOnWifi())) {
                        break
                    }

                    var pausedOut = false
                    while (shouldYieldBackgroundToForeground()) {
                        delay(220L)
                        val refreshed = latestRollingPlan
                        if (refreshed == null || !refreshed.enabled || !refreshed.settings.preloadNextEpisode ||
                            (refreshed.settings.wifiOnlyPreload && !isOnWifi())
                        ) {
                            pausedOut = true
                            break
                        }
                    }
                    if (pausedOut) break

                    val activePlan = latestRollingPlan ?: break
                    if (!activePlan.enabled) break

                    val targetCount = computeRollingPrecacheTargetCount(
                        isWifi = isOnWifi(),
                        wifiOnlyPreload = activePlan.settings.wifiOnlyPreload,
                        preloadEnabled = activePlan.settings.preloadNextEpisode
                    )
                    if (targetCount <= 0) break

                    val quality = effectiveQuality(activePlan.settings)
                    val combinedExcluded = buildSet {
                        addAll(activePlan.excludedIds)
                        addAll(activePlan.localStore.seenSeriesIdsSnapshot())
                        addAll(activePlan.localStore.feedPreferences.value.dislikedSeriesIds)
                        for (h in activePlan.localStore.history.value) add(h.seriesId)
                        for (f in activePlan.localStore.favorites.value) add(f.seriesId)
                    }
                    if (activePlan.persistWarmStore && prunedGeneration != activePlan.generation) {
                        prunedGeneration = activePlan.generation
                        activePlan.localStore.pruneWarmFeedEntries(combinedExcluded) { entry ->
                            hasLocalFile(entry.vid, entry.qualityKey)
                        }
                    }

                    val targets = selectRollingPrecacheTargets(
                        upcomingCandidates = activePlan.candidates,
                        excludedSeriesIds = combinedExcluded,
                        failedSeriesIds = warmFailedSeriesIds,
                        targetCount = targetCount,
                        fullLocalAheadCount = FULL_LOCAL_AHEAD_COUNT
                    )
                    if (targets.isEmpty()) break

                    var nextWork: RollingPrecacheTarget? = null
                    for (t in targets) {
                        val sid = t.summary.seriesId
                        val cachedDetail = api.peekSeriesDetail(sid)
                        val knownVid = t.summary.initialVid.ifBlank {
                            cachedDetail?.episodes?.getOrNull((t.summary.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                                ?: cachedDetail?.episodes?.firstOrNull()?.vid
                                ?: ""
                        }
                        if (knownVid.isNotBlank() && hasLocalFile(knownVid, quality.key)) {
                            if (t.aheadIndex == 0) {
                                val notifyKey = "${activePlan.generation}:${sid}:${quality.key}"
                                if (notifiedImmediateNextKeys.add(notifyKey)) {
                                    peekLocalFileSource(knownVid, quality.key)?.let { localSrc ->
                                        val epIdx = cachedDetail?.episodes?.firstOrNull { it.vid == knownVid }?.index
                                            ?: t.summary.initialEpisodeIndex
                                        activePlan.onImmediateNextReady?.invoke(sid, localSrc, cachedDetail, epIdx)
                                    }
                                }
                            }
                            if (activePlan.persistWarmStore &&
                                sid !in activePlan.localStore.seenSeriesIdsSnapshot() &&
                                sid !in activePlan.localStore.feedPreferences.value.dislikedSeriesIds
                            ) {
                                val epIdx = cachedDetail?.episodes?.firstOrNull { it.vid == knownVid }?.index
                                    ?: t.summary.initialEpisodeIndex
                                activePlan.localStore.upsertWarmFeedEntry(
                                    WarmFeedEntry(
                                        summary = t.summary,
                                        detail = cachedDetail,
                                        vid = knownVid,
                                        episodeIndex = epIdx,
                                        qualityKey = quality.key
                                    )
                                )
                            }
                            continue
                        }
                        if (!t.fullLocalDownload && knownVid.isNotBlank()) {
                            val mem = peekResolvedSource(knownVid, quality)
                            if (mem != null && isStreamHeadCached(mem)) {
                                continue
                            }
                        }
                        nextWork = t
                        break
                    }

                    val work = nextWork ?: break
                    val cand = work.summary
                    var detail = api.peekSeriesDetail(cand.seriesId)
                    val vid = cand.initialVid.ifBlank {
                        if (detail == null) {
                            detail = runCatching { api.fetchSeriesDetail(cand.seriesId) }.getOrNull()
                        }
                        detail?.episodes?.getOrNull((cand.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                            ?: detail?.episodes?.firstOrNull()?.vid
                            ?: ""
                    }
                    if (vid.isBlank()) {
                        warmFailedSeriesIds.add(cand.seriesId)
                        continue
                    }

                    val resolved = runCatching {
                        resolvePlayableSource(vid, quality, seriesId = cand.seriesId, isBackground = true)
                    }.getOrNull()
                    if (resolved == null) {
                        warmFailedSeriesIds.add(cand.seriesId)
                        delay(350L)
                        continue
                    }

                    var readyMedia: ResolvedMediaSource = resolved
                    if (!resolved.isLocalFile) {
                        if (work.fullLocalDownload) {
                            val local = runCatching {
                                downloadToLocalCache(vid = vid, quality = quality, seriesId = cand.seriesId, isBackground = true)
                            }.getOrNull()
                            if (local != null) {
                                readyMedia = local
                            } else {
                                cacheStreamHead(resolved)
                            }
                        } else {
                            cacheStreamHead(resolved)
                        }
                    }

                    if (work.aheadIndex == 0) {
                        val notifyKey = "${activePlan.generation}:${cand.seriesId}:${quality.key}"
                        if (notifiedImmediateNextKeys.add(notifyKey)) {
                            val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                                ?: cand.initialEpisodeIndex
                            activePlan.onImmediateNextReady?.invoke(cand.seriesId, readyMedia, detail, epIdx)
                        }
                    }

                    if (activePlan.persistWarmStore &&
                        readyMedia.isLocalFile &&
                        cand.seriesId !in activePlan.localStore.seenSeriesIdsSnapshot() &&
                        cand.seriesId !in activePlan.localStore.feedPreferences.value.dislikedSeriesIds
                    ) {
                        if (detail == null) {
                            detail = runCatching { api.fetchSeriesDetail(cand.seriesId) }.getOrNull()
                        }
                        val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                            ?: cand.initialEpisodeIndex
                        activePlan.localStore.upsertWarmFeedEntry(
                            WarmFeedEntry(
                                summary = cand,
                                detail = detail,
                                vid = vid,
                                episodeIndex = epIdx,
                                qualityKey = quality.key
                            )
                        )
                    }
                    delay(180L)
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
