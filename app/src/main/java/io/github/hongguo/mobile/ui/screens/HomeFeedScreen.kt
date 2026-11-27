// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.core.CatalogCategory
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.DramaQualityReport
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.HomeFeedEndedAction
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.core.RelatedDramaBundle
import io.github.hongguo.mobile.core.SeriesSeasonMatcher
import io.github.hongguo.mobile.core.friendlyErrorMessage
import io.github.hongguo.mobile.core.resolveHomeFeedEndedAction
import io.github.hongguo.mobile.player.ActivePlaybackMeta
import io.github.hongguo.mobile.player.PlaybackRemoteCommand
import io.github.hongguo.mobile.player.ResolvedMediaSource
import io.github.hongguo.mobile.player.SleepTimerManager
import io.github.hongguo.mobile.player.SleepTimerMode
import io.github.hongguo.mobile.player.computeFeedLowWaterMark
import io.github.hongguo.mobile.ui.components.CategoryTagChips
import io.github.hongguo.mobile.ui.components.DarkHorseBadge
import io.github.hongguo.mobile.ui.components.DramaDetailSheet
import io.github.hongguo.mobile.ui.components.DramaQualityBadge
import io.github.hongguo.mobile.ui.components.DramaQualitySheet
import io.github.hongguo.mobile.ui.components.EpisodePickerSheet
import io.github.hongguo.mobile.ui.components.LandscapeFullscreenEffect
import io.github.hongguo.mobile.ui.components.SpeedQualitySheet
import io.github.hongguo.mobile.ui.components.VerticalDramaPlayer
import io.github.hongguo.mobile.ui.components.enterPipMode
import io.github.hongguo.mobile.ui.theme.CoralRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class FeedEpisodeState(
    val detail: DramaDetail? = null,
    val episodeIndex: Int = 1,
    val media: ResolvedMediaSource? = null,
    val loading: Boolean = false,
    val error: String? = null
)

private data class HomeQualitySheetTarget(
    val title: String,
    val report: DramaQualityReport,
    val episodeIndex: Int,
    val episodeDigg: Long,
    val episodeDiggs: List<Long>,
    val tags: List<String> = emptyList(),
    val episodeComments: List<Long> = emptyList()
)

internal fun computeFeedRetryBackoffMs(consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return 0L
    val exp = (consecutiveFailures - 1).coerceIn(0, 4)
    return (2_000L shl exp).coerceAtMost(30_000L)
}

internal fun shouldBypassCacheForRetry(
    seriesId: String,
    currentTick: Int,
    consumedTicks: Map<String, Int>
): Boolean = currentTick > 0 && consumedTicks[seriesId] != currentTick

@Composable
fun HomeFeedScreen(
    container: AppContainer,
    isRouteVisible: Boolean,
    refreshTrigger: Int = 0,
    onFullscreenChange: (Boolean) -> Unit,
    onOpenSeriesPlayer: (seriesId: String, episodeIndex: Int, positionMs: Long) -> Unit,
    onOpenSearch: (keyword: String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by container.localStore.settings.collectAsState()
    val favorites by container.localStore.favorites.collectAsState()
    val history by container.localStore.history.collectAsState()
    val authSession by container.localStore.authSession.collectAsState()
    val feedPrefs by container.localStore.feedPreferences.collectAsState()
    val inPip by container.isInPipMode.collectAsState()
    val networkStatus by container.networkMonitor.status.collectAsState()
    val sleepState by container.sleepTimer.state.collectAsState()

    val channels = remember {
        listOf(
            CatalogCategory.HOT_DRAMA to "推荐",
            CatalogCategory.REAL_DRAMA to "短剧",
            CatalogCategory.COMIC_DRAMA to "漫剧",
            CatalogCategory.AI_DRAMA to "AI剧"
        )
    }

    var selectedChannelIdx by remember { mutableIntStateOf(0) }
    val initialBootState = remember {
        val prefs = container.localStore.feedPreferences.value
        val curSettings = container.localStore.settings.value
        val excluded = buildSet {
            addAll(container.localStore.seenSeriesIdsSnapshot())
            for (h in container.localStore.history.value) add(h.seriesId)
            for (f in container.localStore.favorites.value) add(f.seriesId)
        }
        val blockedIds = prefs.dislikedSeriesIds.toHashSet()
        val blockedTags = prefs.dislikedTags.toHashSet()
        val warmEntries = container.localStore.pruneWarmFeedEntries(excluded + blockedIds) { entry ->
            container.mediaCache.hasLocalFile(entry.vid, entry.qualityKey)
        }.filter { entry ->
            val summary = entry.summary
            val allTags = summary.tags + summary.categoryNames
            val tagOk = blockedTags.isEmpty() || allTags.none { it in blockedTags }
            val qualityOk = when (curSettings.feedQualityFilter) {
                "no_c" -> summary.quality == null || summary.quality.isPending || summary.quality.grade != "C"
                "as_only" -> summary.quality?.let { !it.isPending && (it.grade == "S" || it.grade == "A") } == true
                else -> true
            }
            tagOk && qualityOk
        }
        Triple(excluded, warmEntries.map { it.summary }, warmEntries)
    }

    var rawFeedItems by remember { mutableStateOf(initialBootState.second) }
    var feedItems by remember { mutableStateOf(initialBootState.second) }
    var currentPageNum by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(pageCount = { feedItems.size })

    val episodeStates = remember {
        mutableStateMapOf<String, FeedEpisodeState>().apply {
            for (entry in initialBootState.third) {
                entry.detail?.let { container.apiClient.warmDetailCache(it) }
                val localMedia = container.mediaCache.peekLocalFileSource(entry.vid, entry.qualityKey)
                if (localMedia != null) {
                    put(
                        entry.summary.seriesId,
                        FeedEpisodeState(
                            detail = entry.detail,
                            episodeIndex = entry.episodeIndex,
                            media = localMedia,
                            loading = false,
                            error = null
                        )
                    )
                }
            }
        }
    }
    val livePositions = remember { mutableStateMapOf<String, Long>() }
    var qualityResumeBySeries by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var curVideoW by remember { mutableIntStateOf(0) }
    var curVideoH by remember { mutableIntStateOf(0) }
    var cleanMode by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenControlsVisible by remember(isFullscreen) { mutableStateOf(true) }
    var fullscreenHudVisible by remember(isFullscreen) { mutableStateOf(true) }
    var isFullscreenLocked by remember(isFullscreen) { mutableStateOf(false) }
    var longPressActive by remember { mutableStateOf(false) }
    var showEpisodeSheetFor by remember { mutableStateOf<DramaDetail?>(null) }
    var showDetailSheetFor by remember { mutableStateOf<DramaSummary?>(null) }
    var detailSheetDetailLoading by remember { mutableStateOf(false) }
    var detailSheetRelated by remember { mutableStateOf<RelatedDramaBundle?>(null) }
    var detailSheetRelatedLoading by remember { mutableStateOf(false) }
    var detailSheetRelatedLoadingMore by remember { mutableStateOf(false) }
    var detailSheetRelatedError by remember { mutableStateOf<String?>(null) }
    var detailSheetOpenSeq by remember { mutableIntStateOf(0) }

    fun openHomeDetailSheet(drama: DramaSummary) {
        detailSheetOpenSeq++
        showDetailSheetFor = drama
    }

    fun openHomeEpisodeSheet(detail: DramaDetail) {
        detailSheetOpenSeq++
        showEpisodeSheetFor = detail
    }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var showQualitySheetFor by remember { mutableStateOf<HomeQualitySheetTarget?>(null) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    val seriesRetryTicks = remember { mutableStateMapOf<String, Int>() }
    val consumedRetryBypassTicks = remember { mutableMapOf<String, Int>() }
    var feedGeneration by remember { mutableIntStateOf(0) }
    var feedConsecutiveFailures by remember { mutableIntStateOf(0) }
    var feedNextRetryAtMs by remember { mutableLongStateOf(0L) }
    var feedSessionCycle by remember { mutableIntStateOf(0) }
    var feedExhausted by remember { mutableStateOf(false) }
    var lastDisliked by remember { mutableStateOf<Pair<Int, DramaSummary>?>(null) }
    var dislikeBannerSeq by remember { mutableIntStateOf(0) }
    var sessionExcludedIds by remember { mutableStateOf(initialBootState.first) }
    var handledInitialBoot by remember { mutableStateOf(false) }
    val preloadedNextEpisodeSeries = remember { mutableSetOf<String>() }
    val autoRecoveredSeries = remember { mutableStateMapOf<String, Int>() }
    var feedLoadJob by remember { mutableStateOf<Job?>(null) }
    var feedLoadSeq by remember { mutableIntStateOf(0) }
    val busOwnerId = remember { "home_feed_${System.nanoTime()}" }
    var isCurFeedPlaying by remember { mutableStateOf(false) }
    var isCurFeedUserPaused by remember { mutableStateOf(false) }
    var remoteCmdSeq by remember { mutableIntStateOf(0) }
    var remoteCommandTrigger by remember { mutableStateOf<Pair<Int, PlaybackRemoteCommand>?>(null) }
    val initialSleepTrigger = remember { sleepState.triggerCount }
    var showSleepEndedHint by remember { mutableStateOf(false) }

    LaunchedEffect(sleepState.triggerCount) {
        if (sleepState.triggerCount > initialSleepTrigger) {
            showSleepEndedHint = true
            delay(3_000L)
            showSleepEndedHint = false
        }
    }

    DisposableEffect(busOwnerId) {
        onDispose {
            container.mediaCache.pauseRollingFeedPrecache()
            container.mediaCache.setActiveForegroundSeries(null)
            container.mediaCache.setForegroundPlaybackReady(true)
            container.playbackControlBus.clearIfOwner(busOwnerId)
            container.localStore.flushSeenFeed()
            container.localStore.flushWatchHistory()
        }
    }

    LaunchedEffect(dislikeBannerSeq) {
        if (dislikeBannerSeq > 0) {
            delay(5000L)
            lastDisliked = null
        }
    }

    LandscapeFullscreenEffect(isFullscreen && isRouteVisible)
    LaunchedEffect(isFullscreen, isRouteVisible) {
        onFullscreenChange(isFullscreen && isRouteVisible)
    }
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    val qualityPref = remember(settings.quality) {
        QualityPreference.entries.firstOrNull { it.key == settings.quality } ?: QualityPreference.AUTO
    }
    val effectiveQuality = remember(settings.quality, settings.cellularAutoLowQuality, networkStatus.isWifi) {
        container.mediaCache.effectiveQuality(settings, networkStatus.isWifi)
    }

    fun filterFeed(
        list: List<DramaSummary>,
        qualityFilter: String,
        excludedIds: Set<String> = sessionExcludedIds
    ): List<DramaSummary> {
        val blockedIds = feedPrefs.dislikedSeriesIds.toHashSet()
        val blockedTags = feedPrefs.dislikedTags.toHashSet()
        return list.filter { item ->
            val allItemTags = item.tags + item.categoryNames
            val notBlocked = item.seriesId !in blockedIds &&
                item.seriesId !in excludedIds &&
                (blockedTags.isEmpty() || allItemTags.none { it in blockedTags })
            val matchesQuality = when (qualityFilter) {
                "no_c" -> item.quality == null || item.quality.isPending || item.quality.grade != "C"
                "as_only" -> item.quality?.let { !it.isPending && (it.grade == "S" || it.grade == "A") } == true
                else -> true
            }
            notBlocked && matchesQuality
        }
    }

    fun loadFeed(
        reset: Boolean,
        appendFreshFirstPage: Boolean = false,
        scrollToTopAfter: Boolean = false
    ) {
        feedLoadJob?.cancel()
        val mySeq = ++feedLoadSeq
        val cat = channels[selectedChannelIdx].first
        if (reset) {
            feedGeneration++
            feedConsecutiveFailures = 0
            feedNextRetryAtMs = 0L
            feedSessionCycle = 0
            feedExhausted = false
        }
        feedLoadJob = scope.launch {
            feedLoading = true
            feedError = null
            try {
                val activeExcluded = if (reset) {
                    val snapshot = buildSet {
                        addAll(container.localStore.seenSeriesIdsSnapshot())
                        for (h in container.localStore.history.value) add(h.seriesId)
                        for (f in container.localStore.favorites.value) add(f.seriesId)
                    }
                    sessionExcludedIds = snapshot
                    preloadedNextEpisodeSeries.clear()
                    snapshot
                } else {
                    sessionExcludedIds
                }
                var targetPage = if (reset || appendFreshFirstPage) 1 else currentPageNum + 1
                var accumulatedRaw = if (reset) emptyList() else rawFeedItems
                var localHasMore = true
                var localCycle = if (reset) 0 else feedSessionCycle
                var nextCycleForceRefresh = false
                var filteredNow = emptyList<DramaSummary>()
                val prevFilteredSize = if (reset) 0 else feedItems.size
                val maxFetches = if (reset) 8 else 6
                val targetSize = when {
                    reset -> 10
                    appendFreshFirstPage -> feedItems.size + 8
                    else -> feedItems.size + 8
                }
                for (attempt in 0 until maxFetches) {
                    val res = container.apiClient.fetchRecommendFeed(
                        cat.key,
                        targetPage,
                        forceRefresh = ((reset || appendFreshFirstPage) && attempt == 0) || nextCycleForceRefresh
                    )
                    nextCycleForceRefresh = false
                    val existingIds = accumulatedRaw.mapTo(HashSet()) { it.seriesId }
                    accumulatedRaw = accumulatedRaw + res.items.filter { existingIds.add(it.seriesId) }
                    currentPageNum = res.page
                    localHasMore = res.hasMore
                    filteredNow = filterFeed(accumulatedRaw, settings.feedQualityFilter, activeExcluded)
                    if (filteredNow.size >= targetSize) {
                        break
                    }
                    if (!localHasMore) {
                        if (localCycle < 3) {
                            localCycle++
                            targetPage = 1
                            nextCycleForceRefresh = true
                            localHasMore = true
                            continue
                        }
                        break
                    }
                    targetPage = res.page + 1
                }
                if (mySeq != feedLoadSeq) return@launch
                if (reset && filteredNow.isEmpty() && accumulatedRaw.isNotEmpty()) {
                    sessionExcludedIds = emptySet()
                    filteredNow = filterFeed(accumulatedRaw, settings.feedQualityFilter, emptySet())
                }
                feedSessionCycle = localCycle
                val addedCount = filteredNow.size - prevFilteredSize
                if (!reset && addedCount <= 0) {
                    if (!localHasMore && localCycle >= 3) {
                        feedExhausted = true
                        hasMore = false
                    } else {
                        feedConsecutiveFailures++
                        feedNextRetryAtMs = System.currentTimeMillis() + computeFeedRetryBackoffMs(feedConsecutiveFailures)
                        hasMore = localHasMore
                    }
                } else {
                    feedConsecutiveFailures = 0
                    feedNextRetryAtMs = 0L
                    feedError = null
                    feedExhausted = !localHasMore && localCycle >= 3
                    hasMore = localHasMore || localCycle < 3
                }
                rawFeedItems = accumulatedRaw
                feedItems = filteredNow
                if (scrollToTopAfter && filteredNow.isNotEmpty()) {
                    pagerState.scrollToPage(0)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (mySeq == feedLoadSeq) {
                    feedConsecutiveFailures++
                    feedNextRetryAtMs = System.currentTimeMillis() + computeFeedRetryBackoffMs(feedConsecutiveFailures)
                    if (!appendFreshFirstPage || feedItems.isEmpty()) {
                        feedError = friendlyErrorMessage(e, "推荐流加载失败，请稍后重试")
                    }
                }
            } finally {
                if (mySeq == feedLoadSeq) {
                    feedLoading = false
                }
            }
        }
    }

    LaunchedEffect(selectedChannelIdx, authSession?.profile?.userId) {
        if (!handledInitialBoot) {
            handledInitialBoot = true
            if (selectedChannelIdx == 0 && feedItems.isNotEmpty()) {
                loadFeed(reset = false, appendFreshFirstPage = true)
            } else {
                loadFeed(reset = true)
            }
        } else {
            rawFeedItems = emptyList()
            feedItems = emptyList()
            episodeStates.clear()
            loadFeed(reset = true)
        }
    }

    LaunchedEffect(settings.feedQualityFilter, feedPrefs) {
        if (rawFeedItems.isNotEmpty()) {
            val nextFiltered = filterFeed(rawFeedItems, settings.feedQualityFilter)
            feedItems = nextFiltered
            if (nextFiltered.size < 2 && hasMore && !feedLoading) {
                loadFeed(reset = false)
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            episodeStates.clear()
            loadFeed(reset = true, scrollToTopAfter = true)
        }
    }

    LaunchedEffect(networkStatus.isConnected) {
        if (networkStatus.isConnected && feedError != null) {
            feedConsecutiveFailures = 0
            feedNextRetryAtMs = 0L
        }
    }

    // Proactively keep a deep feed buffer ahead so rolling 15-item precache always has candidates
    LaunchedEffect(
        pagerState.currentPage,
        feedItems.size,
        hasMore,
        feedLoading,
        isRouteVisible,
        networkStatus.isConnected,
        networkStatus.isWifi,
        settings.preloadNextEpisode,
        settings.wifiOnlyPreload,
        feedConsecutiveFailures,
        feedNextRetryAtMs,
        feedExhausted
    ) {
        if (!isRouteVisible || feedItems.isEmpty() || !hasMore || feedLoading || !networkStatus.isConnected || feedExhausted) {
            return@LaunchedEffect
        }
        val remaining = (feedItems.size - 1 - pagerState.currentPage).coerceAtLeast(0)
        val lowWaterMark = computeFeedLowWaterMark(
            isWifi = networkStatus.isWifi,
            wifiOnlyPreload = settings.wifiOnlyPreload,
            preloadEnabled = settings.preloadNextEpisode
        )
        if (remaining >= lowWaterMark) return@LaunchedEffect
        if (feedConsecutiveFailures >= 3 && remaining > 1) return@LaunchedEffect
        val waitMs = feedNextRetryAtMs - System.currentTimeMillis()
        if (waitMs > 0L) {
            delay(waitMs)
        }
        if (!feedLoading && isRouteVisible && networkStatus.isConnected && hasMore && !feedExhausted) {
            loadFeed(reset = false)
        }
    }

    // Record a series as seen only after genuine dwell (>= 1.5s) on a playable feed page
    val curFeedItem = feedItems.getOrNull(pagerState.currentPage)
    val curFeedState = curFeedItem?.let { episodeStates[it.seriesId] }
    val curFeedPlayableReady = curFeedState?.media != null && curFeedState.error == null
    LaunchedEffect(
        isRouteVisible,
        inPip,
        settings.backgroundAudioPlay,
        curFeedItem?.seriesId,
        curFeedState?.detail?.title,
        curFeedState?.episodeIndex,
        isCurFeedPlaying,
        pagerState.currentPage,
        feedItems.size,
        curVideoW,
        curVideoH
    ) {
        val shouldPublish = curFeedItem != null &&
            (isRouteVisible || inPip || (settings.backgroundAudioPlay && isCurFeedPlaying))
        if (shouldPublish) {
            val totalEps = curFeedState?.detail?.episodes?.size ?: curFeedItem!!.episodeCount
            container.playbackControlBus.updateMeta(
                ActivePlaybackMeta(
                    ownerId = busOwnerId,
                    seriesId = curFeedItem.seriesId,
                    title = curFeedState?.detail?.title?.ifBlank { curFeedItem.title } ?: curFeedItem.title,
                    episodeIndex = curFeedState?.episodeIndex ?: curFeedItem.initialEpisodeIndex,
                    totalEpisodes = totalEps,
                    isPlaying = isCurFeedPlaying,
                    canSkipNext = pagerState.currentPage + 1 < feedItems.size,
                    videoWidth = curVideoW,
                    videoHeight = curVideoH,
                    active = true
                )
            )
        } else {
            container.playbackControlBus.clearIfOwner(busOwnerId)
        }
    }

    LaunchedEffect(busOwnerId) {
        container.playbackControlBus.commands.collect { cmd ->
            if (container.playbackControlBus.meta.value.ownerId != busOwnerId) return@collect
            when (cmd) {
                PlaybackRemoteCommand.NEXT_EPISODE -> {
                    val nextPage = pagerState.currentPage + 1
                    if (nextPage < feedItems.size) {
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            pagerState.animateScrollToPage(nextPage)
                        } else {
                            pagerState.scrollToPage(nextPage)
                        }
                    }
                }
                else -> {
                    remoteCommandTrigger = (++remoteCmdSeq) to cmd
                }
            }
        }
    }

    LaunchedEffect(curFeedItem?.seriesId, curFeedPlayableReady, isRouteVisible, selectedChannelIdx) {
        if (!isRouteVisible || !curFeedPlayableReady) return@LaunchedEffect
        val cur = curFeedItem ?: return@LaunchedEffect
        val channelKey = channels[selectedChannelIdx].first.key
        delay(1500L)
        container.localStore.markFeedSeen(cur.seriesId, cur.recFilterInfo)
        container.apiClient.recordSeenRecFilter(channelKey, cur.recFilterInfo)
    }

    val curPageSeriesId = feedItems.getOrNull(pagerState.currentPage)?.seriesId
    val curPageRetryTick = curPageSeriesId?.let { seriesRetryTicks[it] } ?: 0
    val nextPageSeriesId = feedItems.getOrNull(pagerState.currentPage + 1)?.seriesId

    LaunchedEffect(curPageSeriesId, isRouteVisible) {
        isCurFeedUserPaused = false
        isCurFeedPlaying = false
        container.mediaCache.setActiveForegroundSeries(if (isRouteVisible) curPageSeriesId else null)
    }

    // Pause background precache bandwidth competition while foreground playback is starting/buffering
    LaunchedEffect(
        isRouteVisible,
        curPageSeriesId,
        isCurFeedPlaying,
        isCurFeedUserPaused,
        curFeedState?.media?.isLocalFile,
        curFeedState?.error
    ) {
        val foregroundReady = !isRouteVisible ||
            curPageSeriesId == null ||
            isCurFeedPlaying ||
            isCurFeedUserPaused ||
            curFeedState?.error != null ||
            curFeedState?.media?.isLocalFile == true
        container.mediaCache.setForegroundPlaybackReady(foregroundReady)
    }

    // Unified rolling precache across all channels (15 items on Wi-Fi / 4 on cellular, tiered full + head)
    LaunchedEffect(
        isRouteVisible,
        selectedChannelIdx,
        pagerState.currentPage,
        feedItems,
        settings.preloadNextEpisode,
        settings.wifiOnlyPreload,
        settings.quality,
        settings.cellularAutoLowQuality,
        networkStatus.isWifi
    ) {
        if (!isRouteVisible || feedItems.isEmpty()) {
            container.mediaCache.pauseRollingFeedPrecache()
        } else {
            val upcomingCandidates = feedItems.drop((pagerState.currentPage + 1).coerceAtMost(feedItems.size))
            container.mediaCache.scheduleRollingFeedPrecache(
                channelKey = channels[selectedChannelIdx].first.key,
                candidates = upcomingCandidates,
                settings = settings,
                localStore = container.localStore,
                excludedIds = sessionExcludedIds,
                persistWarmStore = (selectedChannelIdx == 0),
                enabled = true,
                onImmediateNextReady = { readySeriesId, readyMedia, readyDetail, readyEpIdx ->
                    scope.launch {
                        val immediateNext = feedItems.getOrNull(pagerState.currentPage + 1)
                        if (immediateNext?.seriesId == readySeriesId) {
                            val prev = episodeStates[readySeriesId]
                            if (prev?.media == null) {
                                episodeStates[readySeriesId] = FeedEpisodeState(
                                    detail = prev?.detail ?: readyDetail,
                                    episodeIndex = readyEpIdx,
                                    media = readyMedia,
                                    loading = false,
                                    error = null
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    // Resolve current page's detail & playable video without keying on the entire feedItems list
    LaunchedEffect(curPageSeriesId, effectiveQuality, curPageRetryTick, feedGeneration) {
        val targetSid = curPageSeriesId ?: return@LaunchedEffect
        val curItem = feedItems.firstOrNull { it.seriesId == targetSid }
            ?: feedItems.getOrNull(pagerState.currentPage)
            ?: return@LaunchedEffect
        if (episodeStates.size > 40) {
            val keepStart = (pagerState.currentPage - 15).coerceAtLeast(0)
            val keepEnd = (pagerState.currentPage + 15).coerceAtMost(feedItems.lastIndex)
            val keepIds = (keepStart..keepEnd).mapTo(HashSet()) { feedItems[it].seriesId }
            val toRemove = episodeStates.keys.filter { it !in keepIds }
            toRemove.forEach { episodeStates.remove(it) }
        }
        val existing = episodeStates[curItem.seriesId]
        val bypassForRetry = shouldBypassCacheForRetry(curItem.seriesId, curPageRetryTick, consumedRetryBypassTicks)
        if (bypassForRetry) {
            consumedRetryBypassTicks[curItem.seriesId] = curPageRetryTick
        }
        if (existing?.media == null && existing?.loading != true) {
            val lastHist = history.firstOrNull { it.seriesId == curItem.seriesId }
            val cachedDetail = existing?.detail ?: container.apiClient.peekSeriesDetail(curItem.seriesId)
            val knownVid = curItem.initialVid.ifBlank {
                cachedDetail?.episodes?.getOrNull((curItem.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                    ?: cachedDetail?.episodes?.firstOrNull()?.vid
                    ?: ""
            }
            val quickPeek = if (!bypassForRetry && lastHist == null && knownVid.isNotEmpty()) {
                container.mediaCache.peekResolvedSource(knownVid, effectiveQuality)
            } else null
            if (quickPeek != null) {
                val resolvedEpIdx = cachedDetail?.episodes?.firstOrNull { it.vid == knownVid }?.index
                    ?: existing?.episodeIndex
                    ?: curItem.initialEpisodeIndex
                episodeStates[curItem.seriesId] = FeedEpisodeState(
                    detail = cachedDetail,
                    episodeIndex = resolvedEpIdx,
                    media = quickPeek,
                    loading = false,
                    error = null
                )
                if (cachedDetail == null) {
                    scope.launch {
                        runCatching {
                            val d = container.apiClient.fetchSeriesDetail(curItem.seriesId)
                            val epIdx = d.episodes.firstOrNull { it.vid == knownVid }?.index
                                ?: curItem.initialEpisodeIndex.coerceIn(1, d.episodes.size.coerceAtLeast(1))
                            episodeStates[curItem.seriesId]?.let { cur ->
                                episodeStates[curItem.seriesId] = cur.copy(detail = d, episodeIndex = epIdx)
                            }
                        }
                    }
                }
            } else {
                var markedLoading = true
                episodeStates[curItem.seriesId] = (existing ?: FeedEpisodeState(episodeIndex = curItem.initialEpisodeIndex))
                    .copy(loading = true, error = null)
                try {
                    if (lastHist == null && knownVid.isNotEmpty() && cachedDetail == null) {
                        // Fast path: resolve knownVid directly while loading full detail in background
                        scope.launch {
                            runCatching {
                                val d = container.apiClient.fetchSeriesDetail(curItem.seriesId)
                                val resolvedEpIdx = d.episodes.firstOrNull { it.vid == knownVid }?.index
                                    ?: curItem.initialEpisodeIndex.coerceIn(1, d.episodes.size.coerceAtLeast(1))
                                episodeStates[curItem.seriesId]?.let { cur ->
                                    episodeStates[curItem.seriesId] = cur.copy(detail = d, episodeIndex = resolvedEpIdx)
                                }
                            }
                        }
                        val media = container.mediaCache.resolvePlayableSource(
                            vid = knownVid,
                            quality = effectiveQuality,
                            seriesId = curItem.seriesId,
                            bypassCooldown = bypassForRetry
                        )
                        markedLoading = false
                        val latestState = episodeStates[curItem.seriesId]
                        val curDetail = latestState?.detail
                        val resolvedEpIdx = curDetail?.episodes?.firstOrNull { it.vid == knownVid }?.index
                            ?: latestState?.episodeIndex
                            ?: curItem.initialEpisodeIndex
                        episodeStates[curItem.seriesId] = FeedEpisodeState(
                            detail = curDetail,
                            episodeIndex = resolvedEpIdx,
                            media = media,
                            loading = false,
                            error = null
                        )
                    } else {
                        val detail = cachedDetail ?: container.apiClient.fetchSeriesDetail(curItem.seriesId)
                        val epIdx = (
                            lastHist?.lastEpisodeIndex
                                ?: detail.episodes.firstOrNull { it.vid == curItem.initialVid }?.index
                                ?: existing?.episodeIndex
                                ?: curItem.initialEpisodeIndex
                            ).coerceIn(1, detail.episodes.size.coerceAtLeast(1))
                        val vid = detail.episodes[epIdx - 1].vid
                        val media = container.mediaCache.resolvePlayableSource(
                            vid = vid,
                            quality = effectiveQuality,
                            seriesId = curItem.seriesId,
                            bypassCooldown = bypassForRetry
                        )
                        markedLoading = false
                        episodeStates[curItem.seriesId] = FeedEpisodeState(
                            detail = detail,
                            episodeIndex = epIdx,
                            media = media,
                            loading = false,
                            error = null
                        )
                    }
                } catch (ce: CancellationException) {
                    if (markedLoading) {
                        episodeStates[curItem.seriesId]?.let { cur ->
                            if (cur.loading && cur.media == null) {
                                episodeStates[curItem.seriesId] = cur.copy(loading = false)
                            }
                        }
                    }
                    throw ce
                } catch (e: Exception) {
                    markedLoading = false
                    episodeStates[curItem.seriesId] = (
                        episodeStates[curItem.seriesId] ?: FeedEpisodeState(episodeIndex = curItem.initialEpisodeIndex)
                        ).copy(loading = false, error = friendlyErrorMessage(e, "片源准备失败，请点击重试"))
                }
            }
        }
    }

    // Pre-prepare adjacent next page synchronously if cached, or preload resumed episode
    LaunchedEffect(nextPageSeriesId, effectiveQuality, feedGeneration) {
        val nextSid = nextPageSeriesId ?: return@LaunchedEffect
        val nextItem = feedItems.firstOrNull { it.seriesId == nextSid } ?: return@LaunchedEffect
        if (episodeStates.containsKey(nextItem.seriesId)) return@LaunchedEffect
        val nextHist = history.firstOrNull { it.seriesId == nextItem.seriesId }
        val nextDetail = container.apiClient.peekSeriesDetail(nextItem.seriesId)
        val nextVid = nextItem.initialVid.ifBlank {
            nextDetail?.episodes?.getOrNull((nextItem.initialEpisodeIndex - 1).coerceAtLeast(0))?.vid
                ?: nextDetail?.episodes?.firstOrNull()?.vid
                ?: ""
        }
        if (nextHist == null && nextVid.isNotEmpty()) {
            val nextPeek = container.mediaCache.peekResolvedSource(nextVid, effectiveQuality)
            if (nextPeek != null) {
                val nextEpIdx = nextDetail?.episodes?.firstOrNull { it.vid == nextVid }?.index
                    ?: nextItem.initialEpisodeIndex
                episodeStates[nextItem.seriesId] = FeedEpisodeState(
                    detail = nextDetail,
                    episodeIndex = nextEpIdx,
                    media = nextPeek,
                    loading = false,
                    error = null
                )
            }
        } else if (nextHist != null && settings.preloadNextEpisode) {
            scope.launch {
                runCatching {
                    val d = nextDetail ?: container.apiClient.fetchSeriesDetail(nextItem.seriesId)
                    val idx = nextHist.lastEpisodeIndex.coerceIn(1, d.episodes.size)
                    container.mediaCache.preloadInBackground(
                        vids = listOf(d.episodes[idx - 1].vid),
                        quality = effectiveQuality,
                        seriesId = nextItem.seriesId,
                        wifiOnly = settings.wifiOnlyPreload
                    )
                }
            }
        }
    }

    val density = LocalDensity.current

    LaunchedEffect(showDetailSheetFor?.seriesId, showEpisodeSheetFor?.seriesId, detailSheetOpenSeq) {
        val target = showDetailSheetFor ?: showEpisodeSheetFor?.let { epDet ->
            rawFeedItems.firstOrNull { it.seriesId == epDet.seriesId }
                ?: DramaSummary(
                    seriesId = epDet.seriesId,
                    title = epDet.title,
                    cover = epDet.cover,
                    episodeCount = epDet.episodes.size,
                    description = epDet.description
                )
        } ?: return@LaunchedEffect
        val existingDetail = episodeStates[target.seriesId]?.detail ?: container.apiClient.peekSeriesDetail(target.seriesId)
        val effectiveTarget = if (existingDetail != null && existingDetail.title.isNotBlank()) {
            target.copy(
                title = existingDetail.title,
                episodeCount = existingDetail.episodes.size.coerceAtLeast(target.episodeCount)
            )
        } else target

        detailSheetRelated = null
        detailSheetRelatedError = null
        detailSheetRelatedLoading = true

        val detailDeferred = if (showDetailSheetFor != null && existingDetail == null) {
            detailSheetDetailLoading = true
            async { runCatching { container.apiClient.fetchSeriesDetail(target.seriesId) }.getOrNull() }
        } else {
            detailSheetDetailLoading = false
            null
        }

        val initialRes = runCatching { container.apiClient.fetchRelatedDramas(effectiveTarget) }
        initialRes.onSuccess {
            detailSheetRelated = it
        }.onFailure {
            detailSheetRelatedError = it.message ?: "相关作品加载失败"
        }
        detailSheetRelatedLoading = false

        if (detailDeferred != null) {
            val fetched = detailDeferred.await()
            if (fetched != null) {
                val curState = episodeStates[target.seriesId]
                val resolvedEpIdx = if (target.initialVid.isNotEmpty()) {
                    fetched.episodes.firstOrNull { it.vid == target.initialVid }?.index
                        ?: curState?.episodeIndex
                        ?: target.initialEpisodeIndex
                } else {
                    curState?.episodeIndex ?: target.initialEpisodeIndex
                }
                episodeStates[target.seriesId] = (curState ?: FeedEpisodeState()).copy(
                    detail = fetched,
                    episodeIndex = resolvedEpIdx
                )
                if (fetched.title.isNotBlank() && fetched.title != effectiveTarget.title) {
                    val refinedTarget = effectiveTarget.copy(
                        title = fetched.title,
                        episodeCount = fetched.episodes.size.coerceAtLeast(effectiveTarget.episodeCount)
                    )
                    detailSheetRelatedLoading = true
                    detailSheetRelatedError = null
                    runCatching { container.apiClient.fetchRelatedDramas(refinedTarget) }
                        .onSuccess { detailSheetRelated = it }
                        .onFailure { err ->
                            if (detailSheetRelated == null) {
                                detailSheetRelatedError = err.message ?: "相关作品加载失败"
                            }
                        }
                    detailSheetRelatedLoading = false
                }
            }
            detailSheetDetailLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (feedItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (feedLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CoralRed)
                        Spacer(Modifier.height(12.dp))
                        Text("正在加载热门短剧...", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(feedError ?: "暂无推荐短剧", color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { loadFeed(reset = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                        ) {
                            Text("点击重试")
                        }
                    }
                }
            }
        } else {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !isFullscreen && !inPip,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val drama = feedItems[page]
                val epState = episodeStates[drama.seriesId] ?: FeedEpisodeState(episodeIndex = drama.initialEpisodeIndex)
                val isPageActive = isRouteVisible && page == pagerState.currentPage
                val isFav = favorites.any { it.seriesId == drama.seriesId }
                val totalEps = epState.detail?.episodes?.size ?: drama.episodeCount
                val lastHist = history.firstOrNull { it.seriesId == drama.seriesId }
                val resumePos = when {
                    qualityResumeBySeries?.first == drama.seriesId -> qualityResumeBySeries!!.second
                    lastHist != null && lastHist.lastEpisodeIndex == epState.episodeIndex -> lastHist.positionMs
                    else -> 0L
                }

                var descExpanded by remember(drama.seriesId) { mutableStateOf(false) }
                var bottomOverlayHeightPx by remember(drama.seriesId) { mutableIntStateOf(0) }
                val measuredTopClearanceDp = with(density) { topBarHeightPx.toDp() }
                val effectiveTopClearanceDp = if (!cleanMode && measuredTopClearanceDp > 0.dp) {
                    measuredTopClearanceDp + 6.dp
                } else 44.dp
                val measuredBottomClearanceDp = with(density) { bottomOverlayHeightPx.toDp() }
                val effectiveBottomClearanceDp = if (!cleanMode && measuredBottomClearanceDp > 0.dp) {
                    measuredBottomClearanceDp + 8.dp
                } else 44.dp

                Box(modifier = Modifier.fillMaxSize()) {
                    VerticalDramaPlayer(
                        mediaSource = epState.media,
                        isLoading = epState.loading,
                        errorMessage = epState.error,
                        coverUrl = epState.detail?.cover?.ifEmpty { drama.cover } ?: drama.cover,
                        isActive = isPageActive,
                        initialPositionMs = resumePos,
                        playbackSpeed = settings.playbackSpeed,
                        mediaCache = container.mediaCache,
                        playerPool = container.playerPool,
                        fitModeFill = settings.fitModeFill,
                        isInPipMode = inPip,
                        backgroundAudioPlay = settings.backgroundAudioPlay,
                        sleepTriggerCount = sleepState.triggerCount,
                        topSafeClearanceDp = effectiveTopClearanceDp,
                        bottomOverlayClearanceDp = effectiveBottomClearanceDp,
                        canAutoRecover = (autoRecoveredSeries[drama.seriesId] ?: 0) < 1,
                        onAutoRecover = {
                            val count = autoRecoveredSeries[drama.seriesId] ?: 0
                            if (count < 1) {
                                autoRecoveredSeries[drama.seriesId] = count + 1
                                val targetVid = epState.detail?.episodes?.getOrNull(epState.episodeIndex - 1)?.vid
                                    ?: drama.initialVid
                                if (targetVid.isNotBlank()) {
                                    container.mediaCache.invalidateSource(targetVid, effectiveQuality)
                                }
                                container.mediaCache.clearFailedCooldown(drama.seriesId)
                                episodeStates[drama.seriesId] = epState.copy(media = null, loading = false, error = null)
                                seriesRetryTicks[drama.seriesId] = (seriesRetryTicks[drama.seriesId] ?: 0) + 1
                            }
                        },
                        onRetry = {
                            autoRecoveredSeries.remove(drama.seriesId)
                            val targetVid = epState.detail?.episodes?.getOrNull(epState.episodeIndex - 1)?.vid
                                ?: drama.initialVid
                            if (targetVid.isNotBlank()) {
                                container.mediaCache.invalidateSource(targetVid, effectiveQuality)
                            }
                            container.mediaCache.clearFailedCooldown(drama.seriesId)
                            episodeStates[drama.seriesId] = epState.copy(media = null, loading = false, error = null)
                            seriesRetryTicks[drama.seriesId] = (seriesRetryTicks[drama.seriesId] ?: 0) + 1
                        },
                        onEnded = {
                            if (container.sleepTimer.consumeEpisodeEndTrigger()) return@VerticalDramaPlayer
                            if (!settings.autoPlayNext || !isPageActive) return@VerticalDramaPlayer
                            when (val action = resolveHomeFeedEndedAction(
                                currentEpisodeIndex = epState.episodeIndex,
                                totalEpisodes = totalEps,
                                currentPage = page,
                                feedSize = feedItems.size
                            )) {
                                is HomeFeedEndedAction.OpenNextEpisode -> {
                                    onOpenSeriesPlayer(drama.seriesId, action.nextEpisodeIndex, 0L)
                                }
                                is HomeFeedEndedAction.AdvanceFeedPage -> {
                                    scope.launch { pagerState.animateScrollToPage(action.nextPage) }
                                }
                                HomeFeedEndedAction.None -> Unit
                            }
                        },
                        onProgressUpdate = { pos, dur ->
                            if (qualityResumeBySeries?.first == drama.seriesId) {
                                qualityResumeBySeries = null
                            }
                            livePositions[drama.seriesId] = pos
                            if (settings.preloadNextEpisode && dur > 0L && pos * 2L >= dur) {
                                val nextEp = epState.detail?.episodes?.firstOrNull {
                                    it.index == epState.episodeIndex + 1
                                } ?: epState.detail?.episodes?.getOrNull(epState.episodeIndex)
                                if (nextEp != null && preloadedNextEpisodeSeries.add(drama.seriesId)) {
                                    scope.launch {
                                        runCatching {
                                            container.mediaCache.preloadInBackground(
                                                vids = listOf(nextEp.vid),
                                                quality = effectiveQuality,
                                                seriesId = drama.seriesId,
                                                wifiOnly = settings.wifiOnlyPreload
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onLongPressSpeedChange = { longPressActive = it },
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = { fs -> isFullscreen = fs },
                        onControlsVisibilityChange = { vis ->
                            if (isPageActive) fullscreenControlsVisible = vis
                        },
                        fullscreenHudVisible = fullscreenHudVisible,
                        onFullscreenHudVisibleChange = { fullscreenHudVisible = it },
                        isFullscreenLocked = isFullscreenLocked,
                        onFullscreenLockedChange = { isFullscreenLocked = it },
                        onVideoAspectKnown = { w, h ->
                            if (isPageActive) {
                                curVideoW = w
                                curVideoH = h
                            }
                        },
                        onFlushProgress = { container.localStore.flushSeenFeed() },
                        onPlaybackPlayingChange = { playing ->
                            if (isPageActive) isCurFeedPlaying = playing
                        },
                        remoteCommandTrigger = if (isPageActive) remoteCommandTrigger else null,
                        onUserPausedChange = { paused ->
                            if (isPageActive) isCurFeedUserPaused = paused
                        },
                        canDowngradeQuality = effectiveQuality != QualityPreference.P480 && effectiveQuality != QualityPreference.P360,
                        onQuickDowngradeQuality = {
                            val pos = livePositions[drama.seriesId] ?: 0L
                            qualityResumeBySeries = drama.seriesId to pos
                            container.localStore.updateSettings { it.copy(quality = QualityPreference.P480.key) }
                            val keys = episodeStates.keys.toList()
                            for (k in keys) {
                                episodeStates[k]?.let { st ->
                                    episodeStates[k] = st.copy(media = null, loading = false, error = null)
                                }
                            }
                        },
                        showSeekBar = !cleanMode,
                        showFullscreenHint = !cleanMode
                    )

                    // Bottom gradient scrim for readable overlay text
                    if (!cleanMode && !isFullscreen && !inPip) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(250.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                    )
                                )
                        )
                    }

                    // Right-side action rail
                    AnimatedVisibility(
                        visible = !cleanMode && !longPressActive && !isFullscreen && !inPip,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 78.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            FeedActionIcon(
                                icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                label = if (isFav) "已追剧" else "追剧",
                                tint = if (isFav) CoralRed else Color.White,
                                onClick = { container.accountManager.toggleFavoriteAndSync(drama) }
                            )
                            FeedActionIcon(
                                icon = Icons.AutoMirrored.Filled.List,
                                label = if (totalEps > 0) "${totalEps}集" else "选集",
                                onClick = {
                                    val d = epState.detail
                                    if (d != null) {
                                        openHomeEpisodeSheet(d)
                                    } else {
                                        onOpenSeriesPlayer(
                                            drama.seriesId,
                                            epState.episodeIndex,
                                            livePositions[drama.seriesId] ?: resumePos
                                        )
                                    }
                                }
                            )
                            FeedActionIcon(
                                icon = Icons.Default.Speed,
                                label = "${settings.playbackSpeed}x",
                                onClick = { showSpeedSheet = true }
                            )
                            FeedActionIcon(
                                icon = Icons.Default.ThumbDownOffAlt,
                                label = "不看",
                                onClick = {
                                    val removedIdx = feedItems.indexOfFirst { it.seriesId == drama.seriesId }.coerceAtLeast(0)
                                    container.localStore.dislikeSeries(drama.seriesId, drama.recFilterInfo)
                                    scope.launch { container.apiClient.submitFeedDislike(drama) }
                                    episodeStates.remove(drama.seriesId)
                                    rawFeedItems = rawFeedItems.filter { it.seriesId != drama.seriesId }
                                    val nextList = feedItems.filter { it.seriesId != drama.seriesId }
                                    feedItems = nextList
                                    lastDisliked = removedIdx to drama
                                    dislikeBannerSeq++
                                    if (nextList.size <= 2 && hasMore && !feedLoading) {
                                        loadFeed(reset = false)
                                    }
                                }
                            )
                            FeedActionIcon(
                                icon = Icons.Default.VisibilityOff,
                                label = "清屏",
                                onClick = { cleanMode = true }
                            )
                        }
                    }

                    // Clean mode restore button
                    if (cleanMode && !inPip) {
                        IconButton(
                            onClick = { cleanMode = false },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 36.dp)
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = "恢复界面", tint = Color.White)
                        }
                    }

                    // Bottom-left drama info + leading S/A/B/C badge + "Watch Full Series" pill
                    AnimatedVisibility(
                        visible = !cleanMode && !longPressActive && !isFullscreen && !inPip,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .onSizeChanged { bottomOverlayHeightPx = it.height }
                            .fillMaxWidth(0.78f)
                            .padding(start = 14.dp, bottom = 34.dp)
                    ) {
                        val displayTitle = epState.detail?.title ?: drama.title
                        val qualityReport = epState.detail?.quality ?: drama.quality
                        val epDiggList = epState.detail?.episodes?.map { it.diggCount }.orEmpty()
                        val epCommentList = epState.detail?.episodes?.map { it.commentCount }.orEmpty()
                        val sheetTags = epState.detail?.categoryNames?.ifEmpty { null }
                            ?: drama.categoryNames.ifEmpty { drama.tags }
                        val curEpDigg = epState.detail?.episodes?.getOrNull(epState.episodeIndex - 1)?.diggCount
                            ?: qualityReport?.ep1Digg
                            ?: 0L
                        val openQualitySheet = {
                            if (qualityReport != null) {
                                showQualitySheetFor = HomeQualitySheetTarget(
                                    title = displayTitle,
                                    report = qualityReport,
                                    episodeIndex = epState.episodeIndex,
                                    episodeDigg = curEpDigg,
                                    episodeDiggs = epDiggList,
                                    tags = sheetTags,
                                    episodeComments = epCommentList
                                )
                            }
                        }
                        val statParts = buildList {
                            if (curEpDigg > 0L) add("本集赞 ${formatCompactCount(curEpDigg)}")
                            if (qualityReport != null && qualityReport.followedCount > 0L) {
                                add("${formatCompactCount(qualityReport.followedCount)}追剧")
                            }
                            if (qualityReport != null && qualityReport.storyRetention > 0f) {
                                add("留存 ${(qualityReport.storyRetention * 100f).roundToInt()}%")
                            }
                        }
                        Column {
                            if (qualityReport != null || statParts.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { if (qualityReport != null) it.clickable(onClick = openQualitySheet) else it }
                                ) {
                                    if (qualityReport != null) {
                                        DramaQualityBadge(
                                            report = qualityReport,
                                            prominent = true,
                                            onClick = openQualitySheet
                                        )
                                    }
                                    if (qualityReport?.isDarkHorse == true) {
                                        DarkHorseBadge(onClick = openQualitySheet)
                                    }
                                    if (statParts.isNotEmpty()) {
                                        Text(
                                            text = statParts.joinToString(" · "),
                                            color = Color.White.copy(alpha = 0.86f),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            val displayTags = epState.detail?.displayTags?.ifEmpty { null }
                                ?: drama.displayTags.ifEmpty { drama.tags.take(2) }
                            val tagGender = epState.detail?.gender?.takeIf { it >= 0 } ?: drama.gender
                            val tagIsNew = drama.isNewTag || ((qualityReport?.ageDays ?: -1) in 0..7)
                            if (displayTags.isNotEmpty() || tagIsNew) {
                                CategoryTagChips(
                                    tags = displayTags,
                                    gender = tagGender,
                                    isNew = tagIsNew
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openHomeDetailSheet(drama) }
                            ) {
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "查看短剧详情",
                                    tint = Color.White.copy(alpha = 0.72f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            val desc = epState.detail?.description?.ifEmpty { drama.description } ?: drama.description
                            if (desc.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    maxLines = if (descExpanded) 5 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded }
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF1A1A20).copy(alpha = 0.72f))
                                    .border(0.8.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                                    .clickable {
                                        onOpenSeriesPlayer(
                                            drama.seriesId,
                                            epState.episodeIndex,
                                            livePositions[drama.seriesId] ?: resumePos
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (totalEps > 0) {
                                        "观看全集 · 第${epState.episodeIndex}集 / 共${totalEps}集"
                                    } else {
                                        "进入全集连播"
                                    },
                                    color = Color.White.copy(alpha = 0.94f),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.78f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top floating channel tabs & filter / search buttons (auto-hides in fullscreen unless paused/scrubbing)
        AnimatedVisibility(
            visible = !cleanMode && !longPressActive && !inPip && (!isFullscreen || fullscreenControlsVisible),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeightPx = it.height }
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (isFullscreen) {
                val curDrama = feedItems.getOrNull(pagerState.currentPage)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { isFullscreen = false },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = curDrama?.title.orEmpty(),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.48f))
                                .clickable { showSpeedSheet = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("${settings.playbackSpeed}x / 画质", color = Color.White, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.48f))
                                .clickable {
                                    isFullscreen = false
                                    context.enterPipMode(
                                        videoWidth = curVideoW,
                                        videoHeight = curVideoH,
                                        isPlaying = isCurFeedPlaying,
                                        canSkipNext = pagerState.currentPage + 1 < feedItems.size
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("小窗", color = Color.White, fontSize = 12.sp)
                        }
                        if (curDrama != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CoralRed.copy(alpha = 0.88f))
                                    .clickable {
                                        val d = episodeStates[curDrama.seriesId]?.detail
                                        if (d != null) {
                                            openHomeEpisodeSheet(d)
                                        } else {
                                            val epIdx = episodeStates[curDrama.seriesId]?.episodeIndex ?: 1
                                            val pos = livePositions[curDrama.seriesId] ?: 0L
                                            isFullscreen = false
                                            onOpenSeriesPlayer(curDrama.seriesId, epIdx, pos)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("看全集", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        channels.forEachIndexed { idx, (_, label) ->
                            val active = idx == selectedChannelIdx
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { selectedChannelIdx = idx }
                            ) {
                                Text(
                                    text = label,
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                                    fontSize = if (active) 16.sp else 14.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                )
                                Spacer(Modifier.height(3.dp))
                                if (active) {
                                    Box(
                                        modifier = Modifier
                                            .width(16.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(CoralRed)
                                    )
                                }
                            }
                        }
                    }

                    val (filterLabel, filterBg, filterText) = when (settings.feedQualityFilter) {
                        "no_c" -> Triple("去C级", Color(0xFF2F80ED).copy(alpha = 0.32f), Color(0xFF8EC5FF))
                        "as_only" -> Triple("仅A/S", Color(0xFFFFB800).copy(alpha = 0.28f), Color(0xFFFFD54F))
                        else -> Triple("全部", Color.Black.copy(alpha = 0.38f), Color.White.copy(alpha = 0.85f))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(filterBg)
                            .border(0.8.dp, filterText.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .clickable {
                                val next = when (settings.feedQualityFilter) {
                                    "all" -> "no_c"
                                    "no_c" -> "as_only"
                                    else -> "all"
                                }
                                container.localStore.updateSettings { it.copy(feedQualityFilter = next) }
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(filterLabel, color = filterText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (sleepState.mode != SleepTimerMode.OFF) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(CoralRed.copy(alpha = 0.28f))
                                .clickable { showSpeedSheet = true }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = SleepTimerManager.formatRemaining(sleepState),
                                color = Color(0xFFFFD0D8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            episodeStates.clear()
                            loadFeed(reset = true, scrollToTopAfter = true)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "换一批", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = { onOpenSearch(null) },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "搜索", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        val dislikedPair = lastDisliked
        AnimatedVisibility(
            visible = dislikedPair != null && !isFullscreen && !cleanMode && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp)
        ) {
            if (dislikedPair != null) {
                val (origIdx, removed) = dislikedPair
                val firstTag = removed.displayTags.firstOrNull() ?: removed.tags.firstOrNull()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text("已屏蔽此剧", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    Text(
                        text = "撤销",
                        color = Color(0xFF7AD97A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            container.localStore.undislikeSeries(removed.seriesId, removed.recFilterInfo)
                            container.apiClient.removeDislikedRecFilter(removed.recFilterInfo)
                            if (rawFeedItems.none { it.seriesId == removed.seriesId }) {
                                val rMutable = rawFeedItems.toMutableList()
                                rMutable.add(origIdx.coerceIn(0, rMutable.size), removed)
                                rawFeedItems = rMutable
                            }
                            if (feedItems.none { it.seriesId == removed.seriesId }) {
                                val mutable = feedItems.toMutableList()
                                val insertAt = origIdx.coerceIn(0, mutable.size)
                                mutable.add(insertAt, removed)
                                feedItems = mutable
                                scope.launch { pagerState.scrollToPage(insertAt) }
                            }
                            lastDisliked = null
                        }
                    )
                    if (!firstTag.isNullOrBlank()) {
                        Text(
                            text = "屏蔽「$firstTag」",
                            color = CoralRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                container.localStore.dislikeTag(firstTag)
                                feedItems = filterFeed(feedItems, settings.feedQualityFilter)
                                lastDisliked = null
                            }
                        )
                    }
                }
            }
        }
        val atFeedTail = feedItems.isNotEmpty() && pagerState.currentPage >= feedItems.lastIndex
        val tailBannerVisible = atFeedTail &&
            dislikedPair == null &&
            !showSleepEndedHint &&
            !isFullscreen &&
            !cleanMode &&
            !inPip &&
            (feedLoading || feedError != null || feedExhausted)
        AnimatedVisibility(
            visible = tailBannerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable(enabled = !feedLoading) {
                        if (feedExhausted) {
                            episodeStates.clear()
                            loadFeed(reset = true, scrollToTopAfter = true)
                        } else if (feedError != null) {
                            feedConsecutiveFailures = 0
                            feedNextRetryAtMs = 0L
                            loadFeed(reset = false)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                if (feedLoading) {
                    CircularProgressIndicator(
                        color = CoralRed,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(13.dp)
                    )
                    Text("正在加载更多推荐...", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                } else if (feedExhausted) {
                    Text(
                        text = "已刷完本轮推荐 · 点击换一批",
                        color = CoralRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "更多推荐加载失败 · 点击重试",
                        color = Color(0xFFFFB4AB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showSleepEndedHint && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "睡眠定时已到，已自动暂停播放",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    showDetailSheetFor?.let { target ->
        val targetState = episodeStates[target.seriesId]
        val targetDetail = targetState?.detail
        val targetQuality = targetDetail?.quality ?: target.quality
        val targetTitle = targetDetail?.title ?: target.title
        val targetEpIdx = targetState?.episodeIndex ?: target.initialEpisodeIndex
        val historyMap = remember(history) { history.associateBy { it.seriesId } }
        val activeBundle = detailSheetRelated
        val effectiveTargetForRelated = if (targetDetail != null && targetDetail.title.isNotBlank()) {
            target.copy(
                title = targetDetail.title,
                episodeCount = targetDetail.episodes.size.coerceAtLeast(target.episodeCount)
            )
        } else target
        DramaDetailSheet(
            summary = target,
            detail = targetDetail,
            detailLoading = detailSheetDetailLoading,
            isFavorite = favorites.any { it.seriesId == target.seriesId },
            seasons = activeBundle?.seasons.orEmpty(),
            related = activeBundle?.related.orEmpty(),
            relatedKeyword = activeBundle?.keyword ?: SeriesSeasonMatcher.extractSearchKeyword(targetTitle),
            relatedLoading = detailSheetRelatedLoading,
            relatedLoadingMore = detailSheetRelatedLoadingMore,
            hasMoreRelated = activeBundle?.hasMore == true,
            relatedError = detailSheetRelatedError,
            historyBySeriesId = historyMap,
            primaryActionLabel = "观看全集",
            onToggleFavorite = { container.accountManager.toggleFavoriteAndSync(target) },
            onPrimaryAction = {
                showDetailSheetFor = null
                onOpenSeriesPlayer(
                    target.seriesId,
                    targetEpIdx,
                    livePositions[target.seriesId] ?: 0L
                )
            },
            onOpenQualitySheet = targetQuality?.let { q ->
                {
                    showDetailSheetFor = null
                    showQualitySheetFor = HomeQualitySheetTarget(
                        title = targetTitle,
                        report = q,
                        episodeIndex = targetEpIdx,
                        episodeDigg = targetDetail?.episodes?.getOrNull(targetEpIdx - 1)?.diggCount ?: q.ep1Digg,
                        episodeDiggs = targetDetail?.episodes?.map { it.diggCount }.orEmpty(),
                        tags = targetDetail?.categoryNames?.ifEmpty { null } ?: target.categoryNames.ifEmpty { target.tags },
                        episodeComments = targetDetail?.episodes?.map { it.commentCount }.orEmpty()
                    )
                }
            },
            onSelectDrama = { targetSeriesId ->
                showDetailSheetFor = null
                val hist = container.localStore.getLastWatchEntry(targetSeriesId)
                onOpenSeriesPlayer(
                    targetSeriesId,
                    hist?.lastEpisodeIndex ?: 1,
                    hist?.positionMs ?: 0L
                )
            },
            onRetryRelated = { detailSheetOpenSeq++ },
            onLoadMoreRelated = {
                val curBundle = detailSheetRelated
                if (curBundle != null && curBundle.hasMore && !detailSheetRelatedLoadingMore) {
                    scope.launch {
                        detailSheetRelatedLoadingMore = true
                        runCatching {
                            detailSheetRelated = container.apiClient.fetchMoreRelatedDramas(effectiveTargetForRelated, curBundle)
                        }
                        detailSheetRelatedLoadingMore = false
                    }
                }
            },
            onSearchAll = { kw ->
                showDetailSheetFor = null
                onOpenSearch(kw)
            },
            onDismiss = { showDetailSheetFor = null }
        )
    }

    showEpisodeSheetFor?.let { detail ->
        val epState = episodeStates[detail.seriesId]
        val watchedEp = history.firstOrNull { it.seriesId == detail.seriesId }?.lastEpisodeIndex ?: 0
        EpisodePickerSheet(
            detail = detail,
            currentEpisodeIndex = epState?.episodeIndex ?: 1,
            watchedUpToEpisode = watchedEp,
            seasons = detailSheetRelated?.seasons.orEmpty(),
            onSwitchSeason = { targetSeriesId ->
                showEpisodeSheetFor = null
                val hist = container.localStore.getLastWatchEntry(targetSeriesId)
                onOpenSeriesPlayer(
                    targetSeriesId,
                    hist?.lastEpisodeIndex ?: 1,
                    hist?.positionMs ?: 0L
                )
            },
            onSelectEpisode = { chosenIdx ->
                onOpenSeriesPlayer(detail.seriesId, chosenIdx, 0L)
            },
            onDismiss = { showEpisodeSheetFor = null }
        )
    }

    showQualitySheetFor?.let { target ->
        DramaQualitySheet(
            title = target.title,
            report = target.report,
            currentEpisodeIndex = target.episodeIndex,
            currentEpisodeDigg = target.episodeDigg,
            episodeDiggs = target.episodeDiggs,
            tags = target.tags,
            episodeComments = target.episodeComments,
            onDismiss = { showQualitySheetFor = null }
        )
    }

    if (showSpeedSheet) {
        val curDramaForSheet = feedItems.getOrNull(pagerState.currentPage)
        val curMediaForSheet = curDramaForSheet?.let { episodeStates[it.seriesId]?.media }
        val activeQualityText = curMediaForSheet?.let { m ->
            listOf(m.qualityLabel, m.codec.uppercase())
                .filter { it.isNotBlank() && !it.equals("CACHED", ignoreCase = true) }
                .joinToString(" · ")
        }?.ifBlank { effectiveQuality.label.substringBefore(" ") }
            ?: effectiveQuality.label.substringBefore(" ")
        val isDowngraded = settings.cellularAutoLowQuality && !networkStatus.isWifi && qualityPref != effectiveQuality

        SpeedQualitySheet(
            currentSpeed = settings.playbackSpeed,
            currentQuality = qualityPref,
            activeQualityInfo = activeQualityText,
            isCellularDowngraded = isDowngraded,
            fitModeFill = settings.fitModeFill,
            onSelectFitMode = { fill ->
                container.localStore.updateSettings { it.copy(fitModeFill = fill, fitModeUserChosen = true) }
            },
            sleepTimerState = sleepState,
            onSelectSleepTimer = { mode -> container.sleepTimer.setMode(mode) },
            backgroundAudioPlay = settings.backgroundAudioPlay,
            onToggleBackgroundAudio = { on -> container.localStore.updateSettings { it.copy(backgroundAudioPlay = on) } },
            onEnterPip = {
                context.enterPipMode(
                    videoWidth = curVideoW,
                    videoHeight = curVideoH,
                    isPlaying = isCurFeedPlaying,
                    canSkipNext = pagerState.currentPage + 1 < feedItems.size
                )
            },
            onSelectSpeed = { spd -> container.localStore.updateSettings { it.copy(playbackSpeed = spd) } },
            onSelectQuality = { q ->
                if (q.key != settings.quality) {
                    val curDrama = feedItems.getOrNull(pagerState.currentPage)
                    if (curDrama != null) {
                        val pos = livePositions[curDrama.seriesId] ?: 0L
                        qualityResumeBySeries = curDrama.seriesId to pos
                    }
                    container.localStore.updateSettings { it.copy(quality = q.key) }
                    val keys = episodeStates.keys.toList()
                    for (k in keys) {
                        episodeStates[k]?.let { st ->
                            episodeStates[k] = st.copy(media = null, loading = false, error = null)
                        }
                    }
                }
            },
            onDismiss = { showSpeedSheet = false }
        )
    }
}

@Composable
fun FeedActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
