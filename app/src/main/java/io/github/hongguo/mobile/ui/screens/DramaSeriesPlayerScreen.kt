// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.core.RelatedDramaBundle
import io.github.hongguo.mobile.core.SeriesSeasonMatcher
import io.github.hongguo.mobile.core.shouldRecordWatchHistory
import io.github.hongguo.mobile.player.ResolvedMediaSource
import io.github.hongguo.mobile.ui.components.DramaDetailSheet
import io.github.hongguo.mobile.ui.components.DramaQualitySheet
import io.github.hongguo.mobile.ui.components.EpisodePickerSheet
import io.github.hongguo.mobile.ui.components.LandscapeFullscreenEffect
import io.github.hongguo.mobile.ui.components.SpeedQualitySheet
import io.github.hongguo.mobile.ui.components.VerticalDramaPlayer
import io.github.hongguo.mobile.ui.components.enterPipMode
import io.github.hongguo.mobile.ui.theme.CoralRed
import kotlinx.coroutines.launch

private data class EpisodeLoadState(
    val media: ResolvedMediaSource? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@Composable
fun DramaSeriesPlayerScreen(
    container: AppContainer,
    seriesId: String,
    initialEpisodeIndex: Int,
    initialPositionMs: Long,
    onBack: () -> Unit,
    onSwitchSeries: (seriesId: String) -> Unit = {},
    onSearchAll: (keyword: String) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings by container.localStore.settings.collectAsState()
    val favorites by container.localStore.favorites.collectAsState()
    val history by container.localStore.history.collectAsState()
    val inPip by container.isInPipMode.collectAsState()
    val sleepState by container.sleepTimer.state.collectAsState()

    var detail by remember(seriesId) { mutableStateOf<DramaDetail?>(container.apiClient.peekSeriesDetail(seriesId)) }
    var detailLoading by remember(seriesId) { mutableStateOf(detail == null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailRetryTick by remember { mutableIntStateOf(0) }

    val qualityPref = remember(settings.quality) {
        QualityPreference.entries.firstOrNull { it.key == settings.quality } ?: QualityPreference.AUTO
    }
    val effectiveQuality = remember(settings.quality, settings.cellularAutoLowQuality) {
        container.mediaCache.effectiveQuality(settings)
    }

    LaunchedEffect(seriesId, detailRetryTick) {
        if (detail == null || detailRetryTick > 0) {
            detailLoading = true
            detailError = null
            try {
                detail = container.apiClient.fetchSeriesDetail(seriesId)
            } catch (e: Exception) {
                detailError = e.message ?: "剧集信息加载失败"
            } finally {
                detailLoading = false
            }
        }
    }

    val loadedDetail = detail
    if (loadedDetail == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (detailLoading) {
                    CircularProgressIndicator(color = CoralRed)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(detailError ?: "加载失败", color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { detailRetryTick++ },
                            colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                        ) { Text("重试") }
                    }
                }
            }
        }
        return
    }

    val startPage = (initialEpisodeIndex - 1).coerceIn(0, (loadedDetail.episodes.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { loadedDetail.episodes.size })
    val episodeStates = remember(loadedDetail.seriesId) { mutableStateMapOf<String, EpisodeLoadState>() }
    val livePositions = remember(loadedDetail.seriesId) { mutableStateMapOf<Int, Long>() }
    var qualityResumePos by remember(loadedDetail.seriesId) { mutableStateOf<Pair<Int, Long>?>(null) }
    var mediaRetryTick by remember { mutableIntStateOf(0) }
    var cleanMode by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenControlsVisible by remember { mutableStateOf(true) }
    var longPressActive by remember { mutableStateOf(false) }
    var curVideoW by remember { mutableIntStateOf(0) }
    var curVideoH by remember { mutableIntStateOf(0) }
    var showEpisodeSheet by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var usedInitialPos by remember { mutableStateOf(false) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var relatedBundle by remember(loadedDetail.seriesId) { mutableStateOf<RelatedDramaBundle?>(null) }
    var relatedLoading by remember(loadedDetail.seriesId) { mutableStateOf(false) }
    var relatedLoadingMore by remember(loadedDetail.seriesId) { mutableStateOf(false) }
    var relatedError by remember(loadedDetail.seriesId) { mutableStateOf<String?>(null) }
    var relatedReloadTick by remember(loadedDetail.seriesId) { mutableIntStateOf(0) }

    fun openDetailSheet() {
        relatedReloadTick++
        showDetailSheet = true
    }

    fun openEpisodeSheet() {
        relatedReloadTick++
        showEpisodeSheet = true
    }

    val currentSummary = remember(loadedDetail) {
        DramaSummary(
            seriesId = loadedDetail.seriesId,
            title = loadedDetail.title,
            cover = loadedDetail.cover,
            episodeCount = loadedDetail.episodes.size,
            description = loadedDetail.description,
            tags = loadedDetail.categoryNames,
            episodeLabel = loadedDetail.episodeLabel,
            followedCount = loadedDetail.followedCount,
            seriesPlayCount = loadedDetail.seriesPlayCount,
            quality = loadedDetail.quality,
            categoryNames = loadedDetail.categoryNames,
            displayTags = loadedDetail.displayTags,
            gender = loadedDetail.gender,
            createTimeSec = loadedDetail.createTimeSec
        )
    }

    LaunchedEffect(loadedDetail.seriesId, relatedReloadTick) {
        if (relatedReloadTick > 0) {
            relatedLoading = true
            relatedError = null
            try {
                relatedBundle = container.apiClient.fetchRelatedDramas(currentSummary)
            } catch (e: Exception) {
                relatedError = e.message ?: "相关推荐加载失败"
            } finally {
                relatedLoading = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { container.localStore.flushWatchHistory() }
    }

    LandscapeFullscreenEffect(isFullscreen)
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // Resolve current episode & preload next 1-2 episodes
    LaunchedEffect(pagerState.currentPage, loadedDetail, effectiveQuality, mediaRetryTick) {
        val ep = loadedDetail.episodes.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val curState = episodeStates[ep.vid]
        if (curState?.media == null && curState?.loading != true) {
            val quickPeek = if (mediaRetryTick == 0) container.mediaCache.peekResolvedSource(ep.vid, effectiveQuality) else null
            if (quickPeek != null) {
                episodeStates[ep.vid] = EpisodeLoadState(media = quickPeek, loading = false)
            } else {
                episodeStates[ep.vid] = EpisodeLoadState(loading = true)
                try {
                    val resolved = container.mediaCache.resolvePlayableSource(
                        vid = ep.vid,
                        quality = effectiveQuality,
                        seriesId = loadedDetail.seriesId,
                        bypassCooldown = mediaRetryTick > 0
                    )
                    episodeStates[ep.vid] = EpisodeLoadState(media = resolved, loading = false)
                } catch (e: Exception) {
                    episodeStates[ep.vid] = EpisodeLoadState(loading = false, error = e.message ?: "片源加载失败")
                }
            }
        }
        if (settings.preloadNextEpisode) {
            val nextVids = listOfNotNull(
                loadedDetail.episodes.getOrNull(pagerState.currentPage + 1)?.vid,
                loadedDetail.episodes.getOrNull(pagerState.currentPage + 2)?.vid
            )
            if (nextVids.isNotEmpty()) {
                container.mediaCache.preloadInBackground(
                    vids = nextVids,
                    quality = effectiveQuality,
                    seriesId = loadedDetail.seriesId,
                    wifiOnly = settings.wifiOnlyPreload
                )
            }
        }
    }

    val isFav = favorites.any { it.seriesId == loadedDetail.seriesId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !isFullscreen && !inPip,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val ep = loadedDetail.episodes[page]
            val st = episodeStates[ep.vid] ?: EpisodeLoadState()
            val isActive = page == pagerState.currentPage
            val qResume = qualityResumePos
            val startPos = when {
                qResume != null && qResume.first == page -> qResume.second
                !usedInitialPos && ep.index == initialEpisodeIndex -> initialPositionMs
                else -> 0L
            }
            var bottomContentHeightPx by remember { mutableIntStateOf(0) }
            val measuredTopClearanceDp = with(density) { topBarHeightPx.toDp() }
            val effectiveTopClearanceDp = if (!cleanMode && measuredTopClearanceDp > 0.dp) {
                measuredTopClearanceDp + 6.dp
            } else 44.dp
            val measuredBottomClearanceDp = with(density) { bottomContentHeightPx.toDp() }
            val effectiveBottomClearanceDp = if (!cleanMode && measuredBottomClearanceDp > 0.dp) {
                measuredBottomClearanceDp + 8.dp
            } else 44.dp

            Box(modifier = Modifier.fillMaxSize()) {
                VerticalDramaPlayer(
                    mediaSource = st.media,
                    isLoading = st.loading,
                    errorMessage = st.error,
                    coverUrl = loadedDetail.cover,
                    isActive = isActive,
                    initialPositionMs = startPos,
                    playbackSpeed = settings.playbackSpeed,
                    mediaCache = container.mediaCache,
                    playerPool = container.playerPool,
                    fitModeFill = settings.fitModeFill,
                    isInPipMode = inPip,
                    backgroundAudioPlay = settings.backgroundAudioPlay,
                    sleepTriggerCount = sleepState.triggerCount,
                    topSafeClearanceDp = effectiveTopClearanceDp,
                    bottomOverlayClearanceDp = effectiveBottomClearanceDp,
                    onRetry = {
                        episodeStates.remove(ep.vid)
                        mediaRetryTick++
                    },
                    onEnded = {
                        if (container.sleepTimer.consumeEpisodeEndTrigger()) return@VerticalDramaPlayer
                        if (settings.autoPlayNext && page + 1 < loadedDetail.episodes.size) {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                    onProgressUpdate = { pos, dur ->
                        if (ep.index == initialEpisodeIndex) usedInitialPos = true
                        if (qualityResumePos?.first == page) qualityResumePos = null
                        livePositions[ep.index] = pos
                        val alreadyInHistory = container.localStore.getLastWatchEntry(loadedDetail.seriesId) != null
                        if (shouldRecordWatchHistory(ep.index, pos, alreadyInHistory)) {
                            container.localStore.recordWatchProgress(
                                seriesId = loadedDetail.seriesId,
                                title = loadedDetail.title,
                                cover = loadedDetail.cover,
                                totalEpisodes = loadedDetail.episodes.size,
                                episodeIndex = ep.index,
                                positionMs = pos,
                                durationMs = dur
                            )
                            container.accountManager.reportWatchProgressAsync(
                                seriesId = loadedDetail.seriesId,
                                vid = ep.vid,
                                episodeIndex = ep.index,
                                positionMs = pos
                            )
                        }
                    },
                    onLongPressSpeedChange = { longPressActive = it },
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { fs -> isFullscreen = fs },
                    onControlsVisibilityChange = { vis ->
                        if (isActive) fullscreenControlsVisible = vis
                    },
                    onVideoAspectKnown = { w, h ->
                        if (isActive) {
                            curVideoW = w
                            curVideoH = h
                        }
                    },
                    onFlushProgress = { container.localStore.flushWatchHistory() },
                    showSeekBar = !cleanMode,
                    showFullscreenHint = !cleanMode,
                    modifier = if (isFullscreen || inPip) Modifier else Modifier.navigationBarsPadding()
                )

                if (!cleanMode && !isFullscreen && !inPip) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                                )
                            )
                    )
                }

                // Right action rail
                AnimatedVisibility(
                    visible = !cleanMode && !longPressActive && !isFullscreen && !inPip,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 12.dp, bottom = 74.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        FeedActionIcon(
                            icon = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            label = if (isFav) "已追剧" else "追剧",
                            tint = if (isFav) CoralRed else Color.White,
                            onClick = {
                                container.accountManager.toggleFavoriteAndSync(
                                    DramaSummary(
                                        seriesId = loadedDetail.seriesId,
                                        title = loadedDetail.title,
                                        cover = loadedDetail.cover,
                                        episodeCount = loadedDetail.episodes.size,
                                        description = loadedDetail.description
                                    )
                                )
                            }
                        )
                        FeedActionIcon(
                            icon = Icons.AutoMirrored.Filled.List,
                            label = "选集",
                            onClick = { openEpisodeSheet() }
                        )
                        FeedActionIcon(
                            icon = Icons.Default.Speed,
                            label = "${settings.playbackSpeed}x",
                            onClick = { showSpeedSheet = true }
                        )
                        FeedActionIcon(
                            icon = Icons.Default.VisibilityOff,
                            label = "清屏",
                            onClick = { cleanMode = true }
                        )
                    }
                }

                if (cleanMode && !inPip) {
                    IconButton(
                        onClick = { cleanMode = false },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 14.dp, bottom = 36.dp)
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = "恢复界面", tint = Color.White)
                    }
                }

                // Bottom-left compact drama title (tap to view details)
                AnimatedVisibility(
                    visible = !cleanMode && !longPressActive && !isFullscreen && !inPip,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .onSizeChanged { bottomContentHeightPx = it.height }
                        .fillMaxWidth(0.76f)
                        .padding(start = 14.dp, bottom = 36.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openDetailSheet() }
                    ) {
                        Text(
                            text = loadedDetail.title,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "查看短剧详情",
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Top bar (auto-hides during active fullscreen playback)
        AnimatedVisibility(
            visible = !cleanMode && !longPressActive && !inPip && (!isFullscreen || fullscreenControlsVisible),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeightPx = it.height }
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { if (isFullscreen) isFullscreen = false else onBack() },
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (isFullscreen) "退出全屏" else "返回", tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${loadedDetail.title} · 第${pagerState.currentPage + 1}/${loadedDetail.episodes.size}集",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { openDetailSheet() }
                )
                if (isFullscreen) {
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
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.48f))
                                .clickable { openEpisodeSheet() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("选集", color = Color.White, fontSize = 12.sp)
                        }
                        if (pagerState.currentPage + 1 < loadedDetail.episodes.size) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(CoralRed.copy(alpha = 0.88f))
                                    .clickable {
                                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("下一集", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDetailSheet) {
        val historyMap = remember(history) { history.associateBy { it.seriesId } }
        val activeBundle = relatedBundle
        DramaDetailSheet(
            summary = currentSummary,
            detail = loadedDetail,
            detailLoading = false,
            isFavorite = isFav,
            seasons = activeBundle?.seasons.orEmpty(),
            related = activeBundle?.related.orEmpty(),
            relatedKeyword = activeBundle?.keyword ?: SeriesSeasonMatcher.extractSearchKeyword(currentSummary.title),
            relatedLoading = relatedLoading,
            relatedLoadingMore = relatedLoadingMore,
            hasMoreRelated = activeBundle?.hasMore == true,
            relatedError = relatedError,
            historyBySeriesId = historyMap,
            primaryActionLabel = "选集列表",
            onToggleFavorite = { container.accountManager.toggleFavoriteAndSync(currentSummary) },
            onPrimaryAction = {
                showDetailSheet = false
                showEpisodeSheet = true
            },
            onOpenQualitySheet = loadedDetail.quality?.let {
                {
                    showDetailSheet = false
                    showQualitySheet = true
                }
            },
            onSelectDrama = { targetId ->
                showDetailSheet = false
                onSwitchSeries(targetId)
            },
            onRetryRelated = { relatedReloadTick++ },
            onLoadMoreRelated = {
                val curBundle = relatedBundle
                if (curBundle != null && curBundle.hasMore && !relatedLoadingMore) {
                    scope.launch {
                        relatedLoadingMore = true
                        runCatching {
                            relatedBundle = container.apiClient.fetchMoreRelatedDramas(currentSummary, curBundle)
                        }
                        relatedLoadingMore = false
                    }
                }
            },
            onSearchAll = { kw ->
                showDetailSheet = false
                onSearchAll(kw)
            },
            onDismiss = { showDetailSheet = false }
        )
    }

    if (showEpisodeSheet) {
        val watchedEp = container.localStore.getLastWatchEntry(loadedDetail.seriesId)?.lastEpisodeIndex ?: 0
        EpisodePickerSheet(
            detail = loadedDetail,
            currentEpisodeIndex = pagerState.currentPage + 1,
            watchedUpToEpisode = watchedEp,
            seasons = relatedBundle?.seasons.orEmpty(),
            onSwitchSeason = { targetId ->
                showEpisodeSheet = false
                onSwitchSeries(targetId)
            },
            onSelectEpisode = { chosen ->
                val target = (chosen - 1).coerceIn(0, loadedDetail.episodes.size - 1)
                scope.launch { pagerState.scrollToPage(target) }
            },
            onDismiss = { showEpisodeSheet = false }
        )
    }

    if (showQualitySheet && loadedDetail.quality != null) {
        val curEp = loadedDetail.episodes.getOrNull(pagerState.currentPage)
        DramaQualitySheet(
            title = loadedDetail.title,
            report = loadedDetail.quality!!,
            currentEpisodeIndex = curEp?.index,
            currentEpisodeDigg = curEp?.diggCount,
            episodeDiggs = loadedDetail.episodes.map { it.diggCount },
            tags = loadedDetail.categoryNames,
            episodeComments = loadedDetail.episodes.map { it.commentCount },
            onDismiss = { showQualitySheet = false }
        )
    }

    if (showSpeedSheet) {
        SpeedQualitySheet(
            currentSpeed = settings.playbackSpeed,
            currentQuality = qualityPref,
            fitModeFill = settings.fitModeFill,
            onSelectFitMode = { fill ->
                container.localStore.updateSettings { it.copy(fitModeFill = fill, fitModeUserChosen = true) }
            },
            sleepTimerState = sleepState,
            onSelectSleepTimer = { mode -> container.sleepTimer.setMode(mode) },
            backgroundAudioPlay = settings.backgroundAudioPlay,
            onToggleBackgroundAudio = { on -> container.localStore.updateSettings { it.copy(backgroundAudioPlay = on) } },
            onEnterPip = { context.enterPipMode(curVideoW, curVideoH) },
            onSelectSpeed = { spd -> container.localStore.updateSettings { it.copy(playbackSpeed = spd) } },
            onSelectQuality = { q ->
                val curEp = loadedDetail.episodes.getOrNull(pagerState.currentPage)
                val savedPos = curEp?.let { livePositions[it.index] } ?: 0L
                qualityResumePos = pagerState.currentPage to savedPos
                container.localStore.updateSettings { it.copy(quality = q.key) }
                episodeStates.clear()
                mediaRetryTick++
            },
            onDismiss = { showSpeedSheet = false }
        )
    }
}
