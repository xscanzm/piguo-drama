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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.core.CatalogCategory
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.DramaQualityReport
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.player.ResolvedMediaSource
import io.github.hongguo.mobile.player.SleepTimerManager
import io.github.hongguo.mobile.player.SleepTimerMode
import io.github.hongguo.mobile.ui.components.DarkHorseBadge
import io.github.hongguo.mobile.ui.components.DramaQualityBadge
import io.github.hongguo.mobile.ui.components.DramaQualitySheet
import io.github.hongguo.mobile.ui.components.EpisodePickerSheet
import io.github.hongguo.mobile.ui.components.LandscapeFullscreenEffect
import io.github.hongguo.mobile.ui.components.SpeedQualitySheet
import io.github.hongguo.mobile.ui.components.VerticalDramaPlayer
import io.github.hongguo.mobile.ui.components.enterPipMode
import io.github.hongguo.mobile.ui.theme.CoralRed
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
    val episodeDiggs: List<Long>
)

@Composable
fun HomeFeedScreen(
    container: AppContainer,
    isRouteVisible: Boolean,
    refreshTrigger: Int = 0,
    onFullscreenChange: (Boolean) -> Unit,
    onOpenSeriesPlayer: (seriesId: String, episodeIndex: Int, positionMs: Long) -> Unit,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.localStore.settings.collectAsState()
    val favorites by container.localStore.favorites.collectAsState()
    val history by container.localStore.history.collectAsState()
    val authSession by container.localStore.authSession.collectAsState()
    val feedPrefs by container.localStore.feedPreferences.collectAsState()
    val inPip by container.isInPipMode.collectAsState()
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
    var rawFeedItems by remember { mutableStateOf<List<DramaSummary>>(emptyList()) }
    var feedItems by remember { mutableStateOf<List<DramaSummary>>(emptyList()) }
    var currentPageNum by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var feedLoading by remember { mutableStateOf(false) }
    var feedError by remember { mutableStateOf<String?>(null) }

    val episodeStates = remember { mutableStateMapOf<String, FeedEpisodeState>() }
    val livePositions = remember { mutableStateMapOf<String, Long>() }
    var qualityResumeBySeries by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var curVideoW by remember { mutableIntStateOf(0) }
    var curVideoH by remember { mutableIntStateOf(0) }
    var cleanMode by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenControlsVisible by remember { mutableStateOf(true) }
    var longPressActive by remember { mutableStateOf(false) }
    var showEpisodeSheetFor by remember { mutableStateOf<DramaDetail?>(null) }
    var showQualitySheetFor by remember { mutableStateOf<HomeQualitySheetTarget?>(null) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    var lastDisliked by remember { mutableStateOf<Pair<Int, DramaSummary>?>(null) }
    var dislikeBannerSeq by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { container.localStore.flushWatchHistory() }
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
    val effectiveQuality = remember(settings.quality, settings.cellularAutoLowQuality) {
        container.mediaCache.effectiveQuality(settings)
    }

    fun filterFeed(list: List<DramaSummary>, qualityFilter: String): List<DramaSummary> {
        val blockedIds = feedPrefs.dislikedSeriesIds.toHashSet()
        val blockedTags = feedPrefs.dislikedTags.toHashSet()
        return list.filter { item ->
            val notBlocked = item.seriesId !in blockedIds &&
                (blockedTags.isEmpty() || item.tags.none { it in blockedTags })
            val matchesQuality = when (qualityFilter) {
                "no_c" -> item.quality == null || item.quality.grade != "C"
                "as_only" -> item.quality?.grade == "S" || item.quality?.grade == "A"
                else -> true
            }
            notBlocked && matchesQuality
        }
    }

    fun loadFeed(reset: Boolean) {
        val cat = channels[selectedChannelIdx].first
        scope.launch {
            feedLoading = true
            feedError = null
            try {
                var targetPage = if (reset) 1 else currentPageNum + 1
                var accumulatedRaw = if (reset) emptyList() else rawFeedItems
                var localHasMore = true
                var filteredNow = emptyList<DramaSummary>()
                val maxFetches = if (reset) 3 else 2
                for (attempt in 0 until maxFetches) {
                    val res = container.apiClient.fetchRecommendFeed(cat.key, targetPage, forceRefresh = reset && attempt == 0)
                    val existingIds = accumulatedRaw.mapTo(HashSet()) { it.seriesId }
                    accumulatedRaw = accumulatedRaw + res.items.filter { existingIds.add(it.seriesId) }
                    currentPageNum = res.page
                    localHasMore = res.hasMore
                    filteredNow = filterFeed(accumulatedRaw, settings.feedQualityFilter)
                    if (!localHasMore || filteredNow.size >= (if (reset) 2 else feedItems.size + 1)) {
                        break
                    }
                    targetPage = res.page + 1
                }
                rawFeedItems = accumulatedRaw
                feedItems = filteredNow
                hasMore = localHasMore
            } catch (e: Exception) {
                feedError = e.message ?: "推荐流加载失败"
            } finally {
                feedLoading = false
            }
        }
    }

    LaunchedEffect(selectedChannelIdx, authSession?.profile?.userId) {
        rawFeedItems = emptyList()
        feedItems = emptyList()
        episodeStates.clear()
        loadFeed(reset = true)
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

    val pagerState = rememberPagerState(pageCount = { feedItems.size })

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            episodeStates.clear()
            loadFeed(reset = true)
            pagerState.scrollToPage(0)
        }
    }

    // Load more items as user approaches the end of the feed
    LaunchedEffect(pagerState.currentPage, feedItems.size) {
        if (feedItems.isNotEmpty() && pagerState.currentPage >= feedItems.size - 3 && hasMore && !feedLoading) {
            loadFeed(reset = false)
        }
    }

    // Resolve current page's detail & playable video, plus preload next drama's episode
    LaunchedEffect(pagerState.currentPage, feedItems, effectiveQuality, retryTick) {
        if (feedItems.isEmpty()) return@LaunchedEffect
        val curItem = feedItems.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val existing = episodeStates[curItem.seriesId]
        if (existing?.media == null && existing?.loading != true) {
            episodeStates[curItem.seriesId] = (existing ?: FeedEpisodeState()).copy(loading = true, error = null)
            try {
                val lastHist = history.firstOrNull { it.seriesId == curItem.seriesId }
                if (lastHist == null && curItem.initialVid.isNotEmpty() && existing?.detail == null) {
                    // Fast path: resolve initialVid directly while loading full detail in background
                    scope.launch {
                        runCatching {
                            val d = container.apiClient.fetchSeriesDetail(curItem.seriesId)
                            episodeStates[curItem.seriesId]?.let { cur ->
                                episodeStates[curItem.seriesId] = cur.copy(detail = d)
                            }
                        }
                    }
                    val media = container.mediaCache.resolvePlayableSource(
                        vid = curItem.initialVid,
                        quality = effectiveQuality,
                        seriesId = curItem.seriesId,
                        bypassCooldown = retryTick > 0
                    )
                    val curDetail = episodeStates[curItem.seriesId]?.detail
                    episodeStates[curItem.seriesId] = FeedEpisodeState(
                        detail = curDetail,
                        episodeIndex = 1,
                        media = media,
                        loading = false,
                        error = null
                    )
                } else {
                    val detail = existing?.detail ?: container.apiClient.fetchSeriesDetail(curItem.seriesId)
                    val epIdx = (lastHist?.lastEpisodeIndex ?: existing?.episodeIndex ?: 1)
                        .coerceIn(1, detail.episodes.size)
                    val vid = detail.episodes[epIdx - 1].vid
                    val media = container.mediaCache.resolvePlayableSource(
                        vid = vid,
                        quality = effectiveQuality,
                        seriesId = curItem.seriesId,
                        bypassCooldown = retryTick > 0
                    )
                    episodeStates[curItem.seriesId] = FeedEpisodeState(
                        detail = detail,
                        episodeIndex = epIdx,
                        media = media,
                        loading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                episodeStates[curItem.seriesId] = (episodeStates[curItem.seriesId] ?: FeedEpisodeState())
                    .copy(loading = false, error = e.message ?: "片源准备失败")
            }
        }

        // Preload next drama in the feed (respecting watch history episode when present)
        if (settings.preloadNextEpisode) {
            val nextItem = feedItems.getOrNull(pagerState.currentPage + 1)
            if (nextItem != null && !episodeStates.containsKey(nextItem.seriesId)) {
                scope.launch {
                    runCatching {
                        val nextHist = history.firstOrNull { it.seriesId == nextItem.seriesId }
                        val targetVid = if (nextHist == null && nextItem.initialVid.isNotEmpty()) {
                            nextItem.initialVid
                        } else {
                            val d = container.apiClient.fetchSeriesDetail(nextItem.seriesId)
                            val idx = (nextHist?.lastEpisodeIndex ?: 1).coerceIn(1, d.episodes.size)
                            d.episodes[idx - 1].vid
                        }
                        container.mediaCache.preloadInBackground(
                            vids = listOf(targetVid),
                            quality = effectiveQuality,
                            seriesId = nextItem.seriesId,
                            wifiOnly = settings.wifiOnlyPreload
                        )
                    }
                }
            }
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
                val epState = episodeStates[drama.seriesId] ?: FeedEpisodeState()
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
                        onRetry = {
                            episodeStates.remove(drama.seriesId)
                            retryTick++
                        },
                        onEnded = {
                            if (container.sleepTimer.consumeEpisodeEndTrigger()) return@VerticalDramaPlayer
                            if (settings.autoPlayNext && page + 1 < feedItems.size) {
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        },
                        onProgressUpdate = { pos, dur ->
                            if (qualityResumeBySeries?.first == drama.seriesId) {
                                qualityResumeBySeries = null
                            }
                            livePositions[drama.seriesId] = pos
                            container.localStore.recordWatchProgress(
                                seriesId = drama.seriesId,
                                title = epState.detail?.title ?: drama.title,
                                cover = epState.detail?.cover?.ifEmpty { drama.cover } ?: drama.cover,
                                totalEpisodes = totalEps,
                                episodeIndex = epState.episodeIndex,
                                positionMs = pos,
                                durationMs = dur
                            )
                            val activeVid = epState.detail?.episodes?.getOrNull(epState.episodeIndex - 1)?.vid
                                ?: drama.initialVid
                            container.accountManager.reportWatchProgressAsync(
                                seriesId = drama.seriesId,
                                vid = activeVid,
                                episodeIndex = epState.episodeIndex,
                                positionMs = pos
                            )
                        },
                        onLongPressSpeedChange = { longPressActive = it },
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = { fs -> isFullscreen = fs },
                        onControlsVisibilityChange = { vis ->
                            if (isPageActive) fullscreenControlsVisible = vis
                        },
                        onVideoAspectKnown = { w, h ->
                            if (isPageActive) {
                                curVideoW = w
                                curVideoH = h
                            }
                        },
                        onFlushProgress = { container.localStore.flushWatchHistory() },
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
                                        showEpisodeSheetFor = d
                                    } else {
                                        onOpenSeriesPlayer(drama.seriesId, 1, 0L)
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
                                    container.localStore.dislikeSeries(drama.seriesId)
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
                            .fillMaxWidth(0.78f)
                            .padding(start = 14.dp, bottom = 34.dp)
                    ) {
                        val displayTitle = epState.detail?.title ?: drama.title
                        val qualityReport = epState.detail?.quality ?: drama.quality
                        val epDiggList = epState.detail?.episodes?.map { it.diggCount }.orEmpty()
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
                                    episodeDiggs = epDiggList
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
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                            if (drama.tags.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = drama.tags.joinToString(" · "),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(CoralRed.copy(alpha = 0.92f))
                                    .clickable {
                                        onOpenSeriesPlayer(
                                            drama.seriesId,
                                            epState.episodeIndex,
                                            livePositions[drama.seriesId] ?: resumePos
                                        )
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (totalEps > 0) {
                                        "观看全集 · 第${epState.episodeIndex}集 / 共${totalEps}集"
                                    } else {
                                        "进入全集连播"
                                    },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
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
                                    context.enterPipMode(curVideoW, curVideoH)
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
                                            showEpisodeSheetFor = d
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
                            scope.launch {
                                episodeStates.clear()
                                loadFeed(reset = true)
                                pagerState.scrollToPage(0)
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "换一批", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onOpenSearch,
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
                val firstTag = removed.tags.firstOrNull()
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
                            container.localStore.undislikeSeries(removed.seriesId)
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
    }

    showEpisodeSheetFor?.let { detail ->
        val epState = episodeStates[detail.seriesId]
        val watchedEp = history.firstOrNull { it.seriesId == detail.seriesId }?.lastEpisodeIndex ?: 0
        EpisodePickerSheet(
            detail = detail,
            currentEpisodeIndex = epState?.episodeIndex ?: 1,
            watchedUpToEpisode = watchedEp,
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
            onDismiss = { showQualitySheetFor = null }
        )
    }

    if (showSpeedSheet) {
        SpeedQualitySheet(
            currentSpeed = settings.playbackSpeed,
            currentQuality = qualityPref,
            fitModeFill = settings.fitModeFill,
            onSelectFitMode = { fill -> container.localStore.updateSettings { it.copy(fitModeFill = fill) } },
            sleepTimerState = sleepState,
            onSelectSleepTimer = { mode -> container.sleepTimer.setMode(mode) },
            backgroundAudioPlay = settings.backgroundAudioPlay,
            onToggleBackgroundAudio = { on -> container.localStore.updateSettings { it.copy(backgroundAudioPlay = on) } },
            onEnterPip = { context.enterPipMode(curVideoW, curVideoH) },
            onSelectSpeed = { spd -> container.localStore.updateSettings { it.copy(playbackSpeed = spd) } },
            onSelectQuality = { q ->
                val curDrama = feedItems.getOrNull(pagerState.currentPage)
                if (curDrama != null) {
                    val pos = livePositions[curDrama.seriesId] ?: 0L
                    qualityResumeBySeries = curDrama.seriesId to pos
                }
                container.localStore.updateSettings { it.copy(quality = q.key) }
                episodeStates.clear()
                retryTick++
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
