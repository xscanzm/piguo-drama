// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.RelatedDramaItem
import io.github.hongguo.mobile.core.RelatedDramaKind
import io.github.hongguo.mobile.core.SeriesSeasonItem
import io.github.hongguo.mobile.core.WatchHistoryEntry
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DramaDetailSheet(
    summary: DramaSummary,
    detail: DramaDetail?,
    detailLoading: Boolean,
    isFavorite: Boolean,
    seasons: List<SeriesSeasonItem>,
    related: List<RelatedDramaItem>,
    relatedKeyword: String,
    relatedLoading: Boolean,
    relatedLoadingMore: Boolean,
    hasMoreRelated: Boolean,
    relatedError: String?,
    historyBySeriesId: Map<String, WatchHistoryEntry>,
    primaryActionLabel: String,
    onToggleFavorite: () -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenQualitySheet: (() -> Unit)?,
    onSelectDrama: (seriesId: String) -> Unit,
    onRetryRelated: () -> Unit,
    onLoadMoreRelated: () -> Unit,
    onSearchAll: ((keyword: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).coerceAtLeast(440f).dp
    val title = detail?.title?.ifBlank { summary.title } ?: summary.title
    val cover = detail?.cover?.ifBlank { summary.cover } ?: summary.cover
    val description = detail?.description?.ifBlank { summary.description } ?: summary.description
    val totalEps = detail?.episodes?.size?.takeIf { it > 0 } ?: summary.episodeCount
    val epLabel = detail?.episodeLabel?.ifBlank { summary.episodeLabel }
        ?: summary.episodeLabel.ifBlank { if (totalEps > 0) "共${totalEps}集" else "" }
    val quality = detail?.quality ?: summary.quality
    val createTimeSec = detail?.createTimeSec?.takeIf { it > 0L }
        ?: quality?.createTimeSec?.takeIf { it > 0L }
        ?: summary.createTimeSec
    val releaseDate = remember(createTimeSec) {
        if (createTimeSec > 0L) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(createTimeSec * 1000L))
        } else ""
    }
    val followedCount = detail?.followedCount?.takeIf { it > 0L } ?: summary.followedCount
    val playCount = detail?.seriesPlayCount?.takeIf { it > 0L } ?: summary.seriesPlayCount
    val allTags = remember(detail, summary) {
        (detail?.categoryNames.orEmpty() + summary.categoryNames + detail?.displayTags.orEmpty() + summary.displayTags + summary.tags)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
    }
    val isNewDrama = summary.isNewTag || ((quality?.ageDays ?: -1) in 0..7)
    var synopsisExpanded by remember(summary.seriesId) { mutableStateOf(false) }
    var synopsisHasOverflow by remember(summary.seriesId, description) { mutableStateOf(false) }
    val seasonRowState = rememberLazyListState()

    LaunchedEffect(seasons) {
        val curIdx = seasons.indexOfFirst { it.isCurrent }
        if (curIdx >= 0) {
            seasonRowState.animateScrollToItem(curIdx)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
        ) {
            // Top poster + title + stats + quality badge
            item(key = "header") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .width(86.dp)
                            .height(116.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                    ) {
                        if (cover.isNotEmpty()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        val metaParts = buildList {
                            if (epLabel.isNotEmpty()) add(epLabel)
                            if (releaseDate.isNotEmpty()) add("$releaseDate 上线")
                            if (followedCount > 0L) add("${formatCompactCount(followedCount)}追剧")
                            if (playCount > 0L) add("${formatCompactCount(playCount)}播放")
                        }
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = metaParts.joinToString(" · "),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                        if (quality != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let {
                                        if (onOpenQualitySheet != null) {
                                            it.clickable { onOpenQualitySheet() }
                                        } else it
                                    }
                            ) {
                                DramaQualityBadge(
                                    report = quality,
                                    prominent = true,
                                    onClick = { onOpenQualitySheet?.invoke() }
                                )
                                if (quality.isDarkHorse) {
                                    DarkHorseBadge(onClick = { onOpenQualitySheet?.invoke() })
                                }
                                if (onOpenQualitySheet != null) {
                                    Text(
                                        text = "口碑详情 >",
                                        color = Color(0xFF8EC5FF),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Genre tags
            if (allTags.isNotEmpty() || isNewDrama) {
                item(key = "tags") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isNewDrama) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CoralRed.copy(alpha = 0.2f))
                                    .border(0.8.dp, CoralRed.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("新剧", color = CoralRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        for (tag in allTags) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceElevated)
                                    .then(
                                        if (onSearchAll != null) {
                                            Modifier
                                                .border(0.6.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                                .clickable { onSearchAll(tag) }
                                        } else Modifier
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(tag, color = TextPrimary.copy(alpha = 0.88f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Action row
            item(key = "actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) CoralRed else TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isFavorite) "已追剧" else "加入追剧",
                            color = if (isFavorite) CoralRed else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = onPrimaryAction,
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed),
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = primaryActionLabel,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Multi-season horizontal switcher (bidirectional across all matched/official seasons)
            if (seasons.size >= 2) {
                item(key = "seasons") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "系列剧 · 共 ${seasons.size} 季",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text("点击切换任意季", color = TextSecondary, fontSize = 11.sp)
                        }

                        LazyRow(
                            state = seasonRowState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons, key = { it.summary.seriesId }) { item ->
                                val hist = historyBySeriesId[item.summary.seriesId]
                                val bg = if (item.isCurrent) CoralRed.copy(alpha = 0.18f) else SurfaceElevated
                                val borderColor = if (item.isCurrent) CoralRed else Color.White.copy(alpha = 0.08f)
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier
                                        .widthIn(min = 104.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                        .clickable(enabled = !item.isCurrent) {
                                            onSelectDrama(item.summary.seriesId)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            text = item.displayLabel,
                                            color = if (item.isCurrent) CoralRed else TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (item.isCurrent) {
                                            Text(
                                                text = "在看",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(CoralRed)
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    val subText = buildList {
                                        if (item.summary.episodeCount > 0) add("${item.summary.episodeCount}集")
                                        if (!item.isCurrent && hist != null) add("看到第${hist.lastEpisodeIndex}集")
                                    }.joinToString(" · ")
                                    if (subText.isNotEmpty()) {
                                        Text(
                                            text = subText,
                                            color = if (item.isCurrent) CoralRed.copy(alpha = 0.85f) else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Synopsis (collapsible to 4 lines so related works appear clearly in 3/4 sheet)
            item(key = "synopsis") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("剧情简介", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (detailLoading && description.isEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(
                                color = CoralRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        text = when {
                            description.isNotEmpty() -> description
                            detailLoading -> "正在加载完整简介..."
                            else -> "暂无剧情简介"
                        },
                        color = TextPrimary.copy(alpha = 0.86f),
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (!synopsisExpanded) {
                                synopsisHasOverflow = result.hasVisualOverflow
                            }
                        }
                    )
                    if (synopsisHasOverflow || synopsisExpanded) {
                        Text(
                            text = if (synopsisExpanded) "收起简介" else "展开全部",
                            color = Color(0xFF8EC5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { synopsisExpanded = !synopsisExpanded }
                        )
                    }
                }
            }

            // Same-IP & related dramas header
            item(key = "related_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val countSuffix = if (related.isNotEmpty()) {
                        " (${related.size}${if (hasMoreRelated) "+" else ""})"
                    } else ""
                    Text(
                        text = "同 IP · 相关短剧$countSuffix",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onSearchAll != null && relatedKeyword.isNotBlank()) {
                        Text(
                            text = "搜全部 >",
                            color = Color(0xFF8EC5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onSearchAll(relatedKeyword)
                                    onDismiss()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (relatedLoading && related.isEmpty()) {
                item(key = "related_loading") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    ) {
                        CircularProgressIndicator(
                            color = CoralRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在实时搜索系列与相关短剧...", color = TextSecondary, fontSize = 12.5.sp)
                    }
                }
            } else if (relatedError != null && related.isEmpty()) {
                item(key = "related_error") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = relatedError,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "重试",
                            color = CoralRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onRetryRelated() }
                        )
                    }
                }
            } else if (related.isEmpty()) {
                item(key = "related_empty") {
                    Text(
                        text = "暂无其他相近推荐",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(related, key = { it.summary.seriesId }) { item ->
                    val hist = historyBySeriesId[item.summary.seriesId]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                            .clickable {
                                onSelectDrama(item.summary.seriesId)
                                onDismiss()
                            }
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(76.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            if (item.summary.cover.isNotEmpty()) {
                                AsyncImage(
                                    model = item.summary.cover,
                                    contentDescription = item.summary.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = item.summary.title,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (item.badge.isNotEmpty()) {
                                    val isOtherVer = item.kind == RelatedDramaKind.OTHER_VERSION
                                    val badgeBg = if (isOtherVer) {
                                        Color(0xFFFFB800).copy(alpha = 0.18f)
                                    } else {
                                        CoralRed.copy(alpha = 0.16f)
                                    }
                                    val badgeTextColor = if (isOtherVer) Color(0xFFFFD54F) else CoralRed
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(badgeBg)
                                            .let { m ->
                                                if (isOtherVer) {
                                                    m.border(0.6.dp, Color(0xFFFFB800).copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                                                } else m
                                            }
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = item.badge,
                                            color = badgeTextColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            val meta = buildList {
                                if (item.summary.episodeCount > 0) add("共${item.summary.episodeCount}集")
                                if (hist != null) add("看到第${hist.lastEpisodeIndex}集")
                            }.joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(
                                    text = meta,
                                    color = if (hist != null) CoralRed.copy(alpha = 0.9f) else TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                            if (item.summary.description.isNotEmpty()) {
                                Text(
                                    text = item.summary.description,
                                    color = TextSecondary,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (relatedLoadingMore) {
                item(key = "related_loading_more") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = CoralRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载更多...", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else if (hasMoreRelated) {
                item(key = "related_load_more") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                            .clickable { onLoadMoreRelated() }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = "加载更多相关短剧",
                            color = TextPrimary.copy(alpha = 0.9f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
