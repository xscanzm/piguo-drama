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
import androidx.media3.datasource.cache.ContentMetadata
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

internal fun computeFullLocalAheadCount(
    isWifi: Boolean,
    wifiOnlyPreload: Boolean,
    preloadEnabled: Boolean
): Int {
    if (!preloadEnabled) return 0
    if (wifiOnlyPreload && !isWifi) return 0
    return if (isWifi) 8 else 1
}

internal fun isStreamHeadSufficient(
    cachedBytes: Long,
    contentLength: Long,
    minCachedBytes: Long = 512L * 1024L
): Boolean {
    if (cachedBytes >= minCachedBytes) return true
    return contentLength > 0L && cachedBytes >= contentLength && cachedBytes > 8L * 1024L
}

internal class FailureCooldownTracker(
    private val defaultCooldownMs: Long = 90_000L
) {
    private val untilById = ConcurrentHashMap<String, Long>()

    fun markFailed(id: String, cooldownMs: Long = defaultCooldownMs, nowMs: Long = System.currentTimeMillis()) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        untilById[clean] = nowMs + cooldownMs.coerceAtLeast(1_000L)
    }

    fun isInCooldown(id: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        val until = untilById[clean] ?: return false
        if (until > nowMs) return true
        untilById.remove(clean, until)
        return false
    }

    fun activeFailedIds(nowMs: Long = System.currentTimeMillis()): Set<String> {
        if (untilById.isEmpty()) return emptySet()
        val out = HashSet<String>()
        val it = untilById.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value > nowMs) {
                out.add(e.key)
            } else {
                it.remove()
            }
        }
        return out
    }

    fun clear(id: String) {
        val clean = id.trim()
        if (clean.isNotEmpty()) untilById.remove(clean)
    }

    fun clearAll() {
        untilById.clear()
    }
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

    private data class InflightResolve(
        val deferred: CompletableDeferred<ResolvedMediaSource>,
        val allowEncryptedDownload: Boolean,
        val bypassCooldown: Boolean
    )

    private class EncryptedOnlyDeferredException : RuntimeException("encrypted-only deferred")
    private class DownloadAbortedException : RuntimeException("download aborted")

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "episodes_mp4").apply { mkdirs() }
    private val streamCacheDir = File(appContext.cacheDir, "media_stream_cache").apply { mkdirs() }
    private val bgHeadHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
    }
    private val streamCache: SimpleCache by lazy {
        SimpleCache(
            streamCacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_STREAM_CACHE_BYTES),
            StandaloneDatabaseProvider(appContext)
        )
    }

    private val inflight = ConcurrentHashMap<String, InflightResolve>()
    private val resolvedSourceCache = ConcurrentHashMap<String, Pair<Long, ResolvedMediaSource>>()
    private val verifiedStreamHeadKeys = ConcurrentHashMap<String, Long>()
    private val activePreloads = ConcurrentHashMap.newKeySet<String>()
    private val warmFailedCooldown = FailureCooldownTracker(90_000L)
    private val fullDownloadFailedCooldown = FailureCooldownTracker(180_000L)
    private val foregroundResolveCount = AtomicInteger(0)
    @Volatile
    private var foregroundPlaybackReady: Boolean = true
    @Volatile
    private var foregroundWaitSinceMs: Long = 0L
    @Volatile
    private var activeForegroundSeriesId: String? = null
    private val rollingPlanGeneration = AtomicLong(0L)
    @Volatile
    private var latestRollingPlan: RollingPrecachePlan? = null
    private val rollingWorkerRunning = AtomicBoolean(false)
    private val rollingWorkerRescanRequested = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val MAX_DECRYPTED_BYTES = 500L * 1024L * 1024L
        private const val MAX_STREAM_CACHE_BYTES = 300L * 1024L * 1024L
        private const val MAX_EPISODE_BYTES = 256L * 1024L * 1024L
        private const val RESOLVED_SOURCE_TTL_MS = 12L * 60_000L
        private const val MAX_RESOLVED_CACHE_ENTRIES = 120
        private const val PRELOAD_HEAD_BYTES = 2_048L * 1024L
        private const val PRELOAD_MIN_CACHED_BYTES = 512L * 1024L
        private const val FOREGROUND_YIELD_MAX_MS = 4_000L
    }

    fun setForegroundPlaybackReady(ready: Boolean) {
        foregroundPlaybackReady = ready
        if (ready) {
            foregroundWaitSinceMs = 0L
        } else if (foregroundWaitSinceMs == 0L) {
            foregroundWaitSinceMs = System.currentTimeMillis()
        }
    }

    fun setActiveForegroundSeries(seriesId: String?) {
        activeForegroundSeriesId = seriesId?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearFailedCooldown(seriesId: String) {
        warmFailedCooldown.clear(seriesId)
        fullDownloadFailedCooldown.clear(seriesId)
    }

    private fun shouldYieldBackgroundToForeground(): Boolean {
        if (foregroundResolveCount.get() > 0) return true
        if (!foregroundPlaybackReady) {
            val started = foregroundWaitSinceMs
            if (started > 0L && (System.currentTimeMillis() - started) < FOREGROUND_YIELD_MAX_MS) {
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
        verifiedStreamHeadKeys.remove(key)
        for (idx in 1..4) {
            verifiedStreamHeadKeys.remove("${key}_s$idx")
        }
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

    private fun buildOkHttpDataSourceFactory(
        source: ResolvedMediaSource,
        client: OkHttpClient = httpClient
    ): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).apply {
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
        isBackground: Boolean = false,
        allowEncryptedDownload: Boolean = true
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
        if (existing != null &&
            (!bypassCooldown || existing.bypassCooldown) &&
            (!allowEncryptedDownload || existing.allowEncryptedDownload)
        ) {
            if (!isBackground) foregroundResolveCount.incrementAndGet()
            return try {
                existing.deferred.await()
            } finally {
                if (!isBackground) foregroundResolveCount.decrementAndGet()
            }
        }

        val entry = InflightResolve(
            deferred = CompletableDeferred(),
            allowEncryptedDownload = allowEncryptedDownload,
            bypassCooldown = bypassCooldown
        )
        val prev = if (!bypassCooldown && allowEncryptedDownload) {
            inflight.putIfAbsent(key, entry)
        } else {
            null
        }
        if (prev != null && (!allowEncryptedDownload || prev.allowEncryptedDownload)) {
            if (!isBackground) foregroundResolveCount.incrementAndGet()
            return try {
                prev.deferred.await()
            } finally {
                if (!isBackground) foregroundResolveCount.decrementAndGet()
            }
        }
        if (prev == null && (!bypassCooldown && allowEncryptedDownload).not()) {
            inflight[key] = entry
        }

        // Decouple network/decryption execution from caller coroutine cancellation so an aborted UI effect
        // never cancels or poisons shared inflight requests.
        scope.launch {
            try {
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

                var result: ResolvedMediaSource? = null
                var lastErr: Throwable? = null
                var sawEncryptedVariant = false
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
                        result = primary.copy(fallbackSources = fallbacks)
                        break
                    }
                    sawEncryptedVariant = true
                    if (!allowEncryptedDownload) continue
                    try {
                        result = prepareEncryptedVariant(vid, quality, variant)
                        break
                    } catch (e: Throwable) {
                        lastErr = e
                    }
                }
                if (result == null && plainSources.isNotEmpty()) {
                    result = plainSources.first().copy(fallbackSources = plainSources.drop(1))
                }
                if (result == null) {
                    if (!allowEncryptedDownload && sawEncryptedVariant) {
                        throw EncryptedOnlyDeferredException()
                    }
                    throw (lastErr ?: IllegalStateException("所有片源候选均加载失败"))
                }
                if (!result.isLocalFile) {
                    resolvedSourceCache[key] = (System.currentTimeMillis() + RESOLVED_SOURCE_TTL_MS) to result
                    pruneResolvedCacheIfNeeded()
                }
                entry.deferred.complete(result)
            } catch (t: Throwable) {
                entry.deferred.completeExceptionally(t)
            } finally {
                inflight.remove(key, entry)
            }
        }

        if (!isBackground) foregroundResolveCount.incrementAndGet()
        return try {
            entry.deferred.await()
        } finally {
            if (!isBackground) foregroundResolveCount.decrementAndGet()
        }
    }

    fun isStreamHeadCached(source: ResolvedMediaSource): Boolean {
        if (source.isLocalFile) return true
        if (source.cacheKey.isBlank()) return false
        val cached = runCatching {
            streamCache.getCachedBytes(source.cacheKey, 0L, PRELOAD_HEAD_BYTES)
        }.getOrDefault(0L)
        val contentLen = runCatching {
            ContentMetadata.getContentLength(streamCache.getContentMetadata(source.cacheKey))
        }.getOrDefault(-1L)
        if (isStreamHeadSufficient(cached, contentLen, PRELOAD_MIN_CACHED_BYTES)) return true
        val verifiedUntil = verifiedStreamHeadKeys[source.cacheKey] ?: 0L
        return verifiedUntil > System.currentTimeMillis() && cached > 8L * 1024L
    }

    private fun cacheStreamHead(source: ResolvedMediaSource): Boolean =
        cacheStreamHeadWithFallback(source, cacheKeyToUpdate = null) != null

    private fun cacheStreamHeadWithFallback(
        source: ResolvedMediaSource,
        cacheKeyToUpdate: String?
    ): ResolvedMediaSource? {
        if (source.isLocalFile || source.cacheKey.isBlank()) return source
        if (isStreamHeadCached(source)) return source

        val candidates = listOf(source) + source.fallbackSources
        for ((idx, cand) in candidates.withIndex()) {
            if (cand.isLocalFile) return cand
            if (cand.cacheKey.isBlank()) continue
            if (isStreamHeadCached(cand)) {
                val reordered = if (idx == 0) cand else cand.copy(
                    fallbackSources = candidates.filterIndexed { i, _ -> i != idx }
                )
                if (!cacheKeyToUpdate.isNullOrBlank()) {
                    resolvedSourceCache[cacheKeyToUpdate] = (System.currentTimeMillis() + RESOLVED_SOURCE_TTL_MS) to reordered
                }
                return reordered
            }
            val dataSource = CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(buildOkHttpDataSourceFactory(cand, bgHeadHttpClient))
                .createDataSourceForDownloading()
            val dataSpec = DataSpec.Builder()
                .setUri(cand.uri)
                .setKey(cand.cacheKey)
                .setPosition(0L)
                .setLength(PRELOAD_HEAD_BYTES)
                .build()
            val ok = runCatching {
                CacheWriter(dataSource, dataSpec, null, null).cache()
                val cached = streamCache.getCachedBytes(cand.cacheKey, 0L, PRELOAD_HEAD_BYTES)
                if (cached > 8L * 1024L) {
                    verifiedStreamHeadKeys[cand.cacheKey] = System.currentTimeMillis() + RESOLVED_SOURCE_TTL_MS
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (ok) {
                val reordered = if (idx == 0) cand else cand.copy(
                    fallbackSources = candidates.filterIndexed { i, _ -> i != idx }
                )
                if (!cacheKeyToUpdate.isNullOrBlank()) {
                    resolvedSourceCache[cacheKeyToUpdate] = (System.currentTimeMillis() + RESOLVED_SOURCE_TTL_MS) to reordered
                }
                return reordered
            }
        }
        return null
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
        isBackground: Boolean = false,
        preResolved: ResolvedMediaSource? = null,
        shouldAbort: () -> Boolean = { false }
    ): ResolvedMediaSource? {
        peekLocalFileSource(vid, quality.key)?.let { return it }
        if (shouldAbort()) throw DownloadAbortedException()
        val resolved = preResolved ?: resolvePlayableSource(vid, quality, seriesId = seriesId, isBackground = isBackground)
        if (resolved.isLocalFile) return resolved
        if (shouldAbort()) throw DownloadAbortedException()

        val target = cacheFile(vid, quality)
        val tmp = File(cacheDir, "${vid}_${quality.key}.dl.tmp")
        val candidates = listOf(resolved) + resolved.fallbackSources
        return withContext(Dispatchers.IO) {
            try {
                val dlClient = httpClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build()
                for (cand in candidates) {
                    if (shouldAbort()) throw DownloadAbortedException()
                    if (cand.isLocalFile) return@withContext cand
                    val req = Request.Builder()
                        .url(cand.uri.toString())
                        .apply {
                            if (cand.userAgent.isNotEmpty()) header("User-Agent", cand.userAgent)
                            if (cand.referer.isNotEmpty()) header("Referer", cand.referer)
                        }
                        .get()
                        .build()
                    val call = dlClient.newCall(req)
                    var aborted = false
                    val ok = try {
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) error("HTTP ${resp.code}")
                            val body = resp.body ?: error("响应为空")
                            val len = body.contentLength()
                            if (len > MAX_EPISODE_BYTES) error("单集文件过大")
                            var total = 0L
                            body.byteStream().use { input ->
                                tmp.outputStream().use { output ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        if (shouldAbort()) {
                                            aborted = true
                                            call.cancel()
                                            break
                                        }
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
                            if (aborted) false else total > 8 * 1024L
                        }
                    } catch (ce: CancellationException) {
                        call.cancel()
                        throw ce
                    } catch (_: Throwable) {
                        if (aborted || shouldAbort()) {
                            aborted = true
                        }
                        false
                    }
                    if (aborted) {
                        if (tmp.exists()) tmp.delete()
                        throw DownloadAbortedException()
                    }
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

    private fun isPlanActive(plan: RollingPrecachePlan?, requiredGeneration: Long? = null): Boolean {
        if (plan == null || !plan.enabled || !plan.settings.preloadNextEpisode) return false
        if (plan.settings.wifiOnlyPreload && !isOnWifi()) return false
        if (requiredGeneration != null && plan.generation != requiredGeneration) return false
        return true
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
        launchRollingPrecacheWorker()
    }

    private fun launchRollingPrecacheWorker() {
        rollingWorkerRescanRequested.set(true)
        if (!rollingWorkerRunning.compareAndSet(false, true)) return

        scope.launch {
            try {
                runRollingPrecacheLoop()
            } finally {
                rollingWorkerRunning.set(false)
                if (rollingWorkerRescanRequested.getAndSet(false) && isPlanActive(latestRollingPlan)) {
                    launchRollingPrecacheWorker()
                }
            }
        }
    }

    private suspend fun runRollingPrecacheLoop() {
        var prunedGeneration = -1L
        val notifiedImmediateNextKeys = HashSet<String>()

        while (true) {
            rollingWorkerRescanRequested.set(false)
            val plan = latestRollingPlan
            if (!isPlanActive(plan)) {
                if (rollingWorkerRescanRequested.get() && isPlanActive(latestRollingPlan)) continue
                break
            }
            val startGen = plan!!.generation

            while (shouldYieldBackgroundToForeground()) {
                delay(180L)
                if (!isPlanActive(latestRollingPlan, startGen)) break
            }
            if (!isPlanActive(latestRollingPlan, startGen)) continue

            val isWifi = isOnWifi()
            val targetCount = computeRollingPrecacheTargetCount(
                isWifi = isWifi,
                wifiOnlyPreload = plan.settings.wifiOnlyPreload,
                preloadEnabled = plan.settings.preloadNextEpisode
            )
            if (targetCount <= 0) break

            val fullLocalCount = computeFullLocalAheadCount(
                isWifi = isWifi,
                wifiOnlyPreload = plan.settings.wifiOnlyPreload,
                preloadEnabled = plan.settings.preloadNextEpisode
            )
            val quality = effectiveQuality(plan.settings, isWifi)
            val combinedExcluded = buildSet {
                addAll(plan.excludedIds)
                addAll(plan.localStore.seenSeriesIdsSnapshot())
                addAll(plan.localStore.feedPreferences.value.dislikedSeriesIds)
                for (h in plan.localStore.history.value) add(h.seriesId)
                for (f in plan.localStore.favorites.value) add(f.seriesId)
            }
            if (plan.persistWarmStore && prunedGeneration != startGen) {
                prunedGeneration = startGen
                plan.localStore.pruneWarmFeedEntries(combinedExcluded) { entry ->
                    hasLocalFile(entry.vid, entry.qualityKey)
                }
            }

            val stage1Targets = selectRollingPrecacheTargets(
                upcomingCandidates = plan.candidates,
                excludedSeriesIds = combinedExcluded,
                failedSeriesIds = warmFailedCooldown.activeFailedIds(),
                targetCount = targetCount,
                fullLocalAheadCount = fullLocalCount
            )
            if (stage1Targets.isEmpty()) {
                if (rollingWorkerRescanRequested.get() || latestRollingPlan?.generation != startGen) continue
                break
            }

            val deferredEncryptedIds = ConcurrentHashMap.newKeySet<String>()

            // Stage 1A: Immediate next item gets first-byte stream head cached and promoted to UI ASAP
            val immediateTarget = stage1Targets.firstOrNull { it.aheadIndex == 0 }
            if (immediateTarget != null && isPlanActive(latestRollingPlan, startGen)) {
                val cand = immediateTarget.summary
                val sid = cand.seriesId
                var detail = api.peekSeriesDetail(sid)
                val vid = cand.initialVid.ifBlank {
                    if (detail == null) {
                        detail = runCatching { api.fetchSeriesDetail(sid) }.getOrNull()
                    }
                    detail?.episodes?.getOrNull((cand.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                        ?: detail?.episodes?.firstOrNull()?.vid
                        ?: ""
                }
                if (vid.isBlank()) {
                    warmFailedCooldown.markFailed(sid)
                } else {
                    val localImmediate = peekLocalFileSource(vid, quality.key)
                    if (localImmediate != null) {
                        val notifyKey = "$startGen:$sid:${quality.key}"
                        if (notifiedImmediateNextKeys.add(notifyKey)) {
                            val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                                ?: cand.initialEpisodeIndex
                            plan.onImmediateNextReady?.invoke(sid, localImmediate, detail, epIdx)
                        }
                    } else {
                        val mem = peekResolvedSource(vid, quality)
                        val resolvedImmediate = if (mem != null && !mem.isLocalFile && isStreamHeadCached(mem)) {
                            mem
                        } else {
                            runCatching {
                                resolvePlayableSource(
                                    vid = vid,
                                    quality = quality,
                                    seriesId = sid,
                                    isBackground = true,
                                    allowEncryptedDownload = true
                                )
                            }.getOrNull()
                        }
                        if (resolvedImmediate == null) {
                            warmFailedCooldown.markFailed(sid)
                        } else {
                            val readyMedia = if (resolvedImmediate.isLocalFile) {
                                resolvedImmediate
                            } else {
                                cacheStreamHeadWithFallback(resolvedImmediate, "${vid}_${quality.key}")
                            }
                            if (readyMedia == null) {
                                warmFailedCooldown.markFailed(sid)
                            } else {
                                val notifyKey = "$startGen:$sid:${quality.key}"
                                if (notifiedImmediateNextKeys.add(notifyKey)) {
                                    val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                                        ?: cand.initialEpisodeIndex
                                    plan.onImmediateNextReady?.invoke(sid, readyMedia, detail, epIdx)
                                }
                            }
                        }
                    }
                }
            }

            if (!isPlanActive(latestRollingPlan, startGen)) continue

            // Stage 1B: 2-way concurrent lightweight stream-head precache across remaining window (up to 15 items)
            val restTargets = stage1Targets.drop(1)
            if (restTargets.isNotEmpty()) {
                val stage1Sem = Semaphore(2)
                coroutineScope {
                    restTargets.map { target ->
                        async {
                            stage1Sem.withPermit {
                                if (!isPlanActive(latestRollingPlan, startGen)) return@withPermit
                                while (shouldYieldBackgroundToForeground()) {
                                    delay(180L)
                                    if (!isPlanActive(latestRollingPlan, startGen)) return@withPermit
                                }
                                val cand = target.summary
                                val sid = cand.seriesId
                                if (sid == activeForegroundSeriesId) return@withPermit
                                var detail = api.peekSeriesDetail(sid)
                                val vid = cand.initialVid.ifBlank {
                                    if (detail == null) {
                                        detail = runCatching { api.fetchSeriesDetail(sid) }.getOrNull()
                                    }
                                    detail?.episodes?.getOrNull((cand.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                                        ?: detail?.episodes?.firstOrNull()?.vid
                                        ?: ""
                                }
                                if (vid.isBlank()) {
                                    warmFailedCooldown.markFailed(sid)
                                    return@withPermit
                                }
                                if (hasLocalFile(vid, quality.key)) return@withPermit
                                val mem = peekResolvedSource(vid, quality)
                                if (mem != null && !mem.isLocalFile && isStreamHeadCached(mem)) return@withPermit

                                val allowEncrypted = target.aheadIndex <= 1
                                val resolved = try {
                                    resolvePlayableSource(
                                        vid = vid,
                                        quality = quality,
                                        seriesId = sid,
                                        isBackground = true,
                                        allowEncryptedDownload = allowEncrypted
                                    )
                                } catch (_: EncryptedOnlyDeferredException) {
                                    deferredEncryptedIds.add(sid)
                                    return@withPermit
                                } catch (_: Throwable) {
                                    null
                                }
                                if (resolved == null) {
                                    warmFailedCooldown.markFailed(sid)
                                    return@withPermit
                                }
                                if (!resolved.isLocalFile) {
                                    val cachedHead = cacheStreamHeadWithFallback(resolved, "${vid}_${quality.key}")
                                    if (cachedHead == null) {
                                        warmFailedCooldown.markFailed(sid)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            if (!isPlanActive(latestRollingPlan, startGen)) continue

            // Stage 2: Low-priority full local MP4 download for cold-start warm cache & offline resilience
            val fullWindowTargets = stage1Targets.take(fullLocalCount)
            val fullWindowIds = fullWindowTargets.mapTo(HashSet()) { it.summary.seriesId }
            for (target in fullWindowTargets) {
                if (!isPlanActive(latestRollingPlan, startGen)) break
                while (shouldYieldBackgroundToForeground()) {
                    delay(200L)
                    if (!isPlanActive(latestRollingPlan, startGen)) break
                }
                if (!isPlanActive(latestRollingPlan, startGen)) break

                val cand = target.summary
                val sid = cand.seriesId
                if (sid == activeForegroundSeriesId) continue
                if (fullDownloadFailedCooldown.isInCooldown(sid)) continue

                var detail = api.peekSeriesDetail(sid)
                val vid = cand.initialVid.ifBlank {
                    if (detail == null) {
                        detail = runCatching { api.fetchSeriesDetail(sid) }.getOrNull()
                    }
                    detail?.episodes?.getOrNull((cand.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                        ?: detail?.episodes?.firstOrNull()?.vid
                        ?: ""
                }
                if (vid.isBlank()) {
                    fullDownloadFailedCooldown.markFailed(sid)
                    continue
                }

                if (hasLocalFile(vid, quality.key)) {
                    if (plan.persistWarmStore &&
                        sid !in plan.localStore.seenSeriesIdsSnapshot() &&
                        sid !in plan.localStore.feedPreferences.value.dislikedSeriesIds
                    ) {
                        if (detail == null) {
                            detail = runCatching { api.fetchSeriesDetail(sid) }.getOrNull()
                        }
                        val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                            ?: cand.initialEpisodeIndex
                        plan.localStore.upsertWarmFeedEntry(
                            WarmFeedEntry(
                                summary = cand,
                                detail = detail,
                                vid = vid,
                                episodeIndex = epIdx,
                                qualityKey = quality.key
                            )
                        )
                    }
                    continue
                }

                val mem = peekResolvedSource(vid, quality)
                var aborted = false
                val localResult = try {
                    downloadToLocalCache(
                        vid = vid,
                        quality = quality,
                        seriesId = sid,
                        isBackground = true,
                        preResolved = mem,
                        shouldAbort = {
                            !isPlanActive(latestRollingPlan, startGen) ||
                                activeForegroundSeriesId == sid ||
                                sid !in fullWindowIds
                        }
                    )
                } catch (_: DownloadAbortedException) {
                    aborted = true
                    null
                } catch (_: Throwable) {
                    null
                }
                if (aborted || !isPlanActive(latestRollingPlan, startGen)) break
                if (localResult == null) {
                    if (activeForegroundSeriesId != sid) {
                        fullDownloadFailedCooldown.markFailed(sid)
                    }
                    continue
                }
                if (target.aheadIndex == 0 && localResult.isLocalFile) {
                    val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                        ?: cand.initialEpisodeIndex
                    plan.onImmediateNextReady?.invoke(sid, localResult, detail, epIdx)
                }
                if (plan.persistWarmStore &&
                    localResult.isLocalFile &&
                    sid !in plan.localStore.seenSeriesIdsSnapshot() &&
                    sid !in plan.localStore.feedPreferences.value.dislikedSeriesIds
                ) {
                    if (detail == null) {
                        detail = runCatching { api.fetchSeriesDetail(sid) }.getOrNull()
                    }
                    val epIdx = detail?.episodes?.firstOrNull { it.vid == vid }?.index
                        ?: cand.initialEpisodeIndex
                    plan.localStore.upsertWarmFeedEntry(
                        WarmFeedEntry(
                            summary = cand,
                            detail = detail,
                            vid = vid,
                            episodeIndex = epIdx,
                            qualityKey = quality.key
                        )
                    )
                }
                delay(120L)
            }

            if (rollingWorkerRescanRequested.get() || latestRollingPlan?.generation != startGen) {
                continue
            }
            break
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
        verifiedStreamHeadKeys.clear()
        warmFailedCooldown.clearAll()
        fullDownloadFailedCooldown.clearAll()
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
