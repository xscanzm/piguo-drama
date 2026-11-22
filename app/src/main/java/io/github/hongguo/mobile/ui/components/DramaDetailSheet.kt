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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.DramaSummary
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
    seasonsLoading: Boolean,
    historyBySeriesId: Map<String, WatchHistoryEntry>,
    primaryActionLabel: String,
    onToggleFavorite: () -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenQualitySheet: (() -> Unit)?,
    onSwitchSeason: (seriesId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top poster + title + stats + quality badge
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

            // Genre tags
            if (allTags.isNotEmpty() || isNewDrama) {
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
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(tag, color = TextPrimary.copy(alpha = 0.88f), fontSize = 11.sp)
                        }
                    }
                }
            }

            // Action row
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

            // Multi-season bidirectional switcher (shown whenever >= 2 seasons or loading)
            if (seasonsLoading || seasons.size >= 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (seasons.size >= 2) "系列剧 · 共 ${seasons.size} 季" else "系列上下季",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (seasonsLoading && seasons.isEmpty()) {
                            CircularProgressIndicator(
                                color = CoralRed,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("正在查找...", color = TextSecondary, fontSize = 12.sp)
                        } else if (seasons.size >= 2) {
                            Text("点击切换任意季", color = TextSecondary, fontSize = 11.sp)
                        }
                    }

                    if (seasons.size >= 2) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            onSwitchSeason(item.summary.seriesId)
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

            // Full synopsis
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
                    lineHeight = 21.sp
                )
            }
        }
    }
}
