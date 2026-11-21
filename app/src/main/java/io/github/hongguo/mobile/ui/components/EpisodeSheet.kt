// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hongguo.mobile.core.DramaDetail
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.player.SleepTimerManager
import io.github.hongguo.mobile.player.SleepTimerMode
import io.github.hongguo.mobile.player.SleepTimerState
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodePickerSheet(
    detail: DramaDetail,
    currentEpisodeIndex: Int,
    watchedUpToEpisode: Int = 0,
    onSelectEpisode: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pageSize = 20
    val total = detail.episodes.size
    val totalRanges = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
    val initialRange = ((currentEpisodeIndex - 1) / pageSize).coerceIn(0, totalRanges - 1)
    var selectedRange by remember(detail.seriesId, currentEpisodeIndex) { mutableIntStateOf(initialRange) }

    val hotEpisodes = remember(detail.seriesId, detail.episodes) {
        if (detail.episodes.size < 10) {
            emptyList()
        } else {
            val bodyEps = detail.episodes.filter { it.index >= 3 && it.diggCount > 0L }
            if (bodyEps.size < 5) {
                emptyList()
            } else {
                val sortedCounts = bodyEps.map { it.diggCount }.sorted()
                val med = sortedCounts[sortedCounts.size / 2]
                val threshold = (med * 1.25).toLong().coerceAtLeast(1L)
                bodyEps.filter { it.diggCount >= threshold }
                    .sortedByDescending { it.diggCount }
                    .take(5)
                    .sortedBy { it.index }
            }
        }
    }
    val hotEpisodeIndices = remember(hotEpisodes) { hotEpisodes.mapTo(HashSet()) { it.index } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = detail.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "共 $total 集",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            if (detail.description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail.description,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hotEpisodes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Text(
                            text = "高能集",
                            color = Color(0xFFFFB800),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(hotEpisodes.size) { idx ->
                        val ep = hotEpisodes[idx]
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFB800).copy(alpha = 0.16f))
                                .border(0.8.dp, Color(0xFFFFB800).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectEpisode(ep.index)
                                    onDismiss()
                                }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "第${ep.index}集 · ${formatCompactCount(ep.diggCount)}",
                                color = Color(0xFFFFD54F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (totalRanges > 1) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(totalRanges) { rIdx ->
                        val start = rIdx * pageSize + 1
                        val end = minOf((rIdx + 1) * pageSize, total)
                        val selected = rIdx == selectedRange
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
                                .border(
                                    1.dp,
                                    if (selected) CoralRed else Color.Transparent,
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { selectedRange = rIdx }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "$start-$end",
                                color = if (selected) CoralRed else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            val startIdx = selectedRange * pageSize
            val endIdx = minOf(startIdx + pageSize, total)
            val slice = detail.episodes.subList(startIdx, endIdx)

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(slice, key = { it.vid }) { ep ->
                    val isCurrent = ep.index == currentEpisodeIndex
                    val isHot = ep.index in hotEpisodeIndices
                    val isWatched = watchedUpToEpisode > 0 && ep.index < watchedUpToEpisode && !isCurrent
                    val tileBg = when {
                        isCurrent -> CoralRed
                        isWatched -> SurfaceElevated.copy(alpha = 0.5f)
                        else -> SurfaceElevated
                    }
                    val numColor = when {
                        isCurrent -> Color.White
                        isWatched -> TextSecondary.copy(alpha = 0.65f)
                        else -> Color.White
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tileBg)
                            .then(
                                if (isHot && !isCurrent) {
                                    Modifier.border(1.dp, Color(0xFFFFB800).copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                                } else Modifier
                            )
                            .clickable {
                                onSelectEpisode(ep.index)
                                onDismiss()
                            }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Equalizer,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Text(
                                    text = "${ep.index}",
                                    color = numColor,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                            if (ep.diggCount > 0L) {
                                Text(
                                    text = if (isHot && !isCurrent) "★ ${formatCompactCount(ep.diggCount)}" else formatCompactCount(ep.diggCount),
                                    color = if (isHot && !isCurrent) Color(0xFFFFD54F) else numColor.copy(alpha = 0.68f),
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedQualitySheet(
    currentSpeed: Float,
    currentQuality: QualityPreference,
    fitModeFill: Boolean = false,
    onSelectFitMode: (Boolean) -> Unit = {},
    sleepTimerState: SleepTimerState = SleepTimerState(),
    onSelectSleepTimer: (SleepTimerMode) -> Unit = {},
    backgroundAudioPlay: Boolean = false,
    onToggleBackgroundAudio: (Boolean) -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
    onSelectSpeed: (Float) -> Unit,
    onSelectQuality: (QualityPreference) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                .padding(bottom = 28.dp)
        ) {
            Text("播放倍速", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                speeds.forEach { spd ->
                    val selected = kotlin.math.abs(spd - currentSpeed) < 0.01f
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CoralRed else SurfaceElevated)
                            .clickable {
                                onSelectSpeed(spd)
                                onDismiss()
                            }
                    ) {
                        Text(
                            text = "${spd}x",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("清晰度偏好", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreference.entries.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { q ->
                            val selected = q == currentQuality
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
                                    .border(1.dp, if (selected) CoralRed else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelectQuality(q)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = q.label.substringBefore(" "),
                                    color = if (selected) CoralRed else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("画面比例（竖屏模式）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(false to "原比例（不裁边）", true to "等比铺满（裁左右）").forEach { (fillVal, title) ->
                    val selected = fitModeFill == fillVal
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
                            .border(1.dp, if (selected) CoralRed else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelectFitMode(fillVal) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (selected) CoralRed else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            val remainText = SleepTimerManager.formatRemaining(sleepTimerState)
            Text(
                text = if (remainText.isNotEmpty()) "睡眠定时 · $remainText" else "睡眠定时",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                SleepTimerMode.entries.forEach { m ->
                    val selected = sleepTimerState.mode == m
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
                            .border(1.dp, if (selected) CoralRed else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelectSleepTimer(m) }
                            .padding(vertical = 9.dp, horizontal = 2.dp)
                    ) {
                        Text(
                            text = m.label,
                            color = if (selected) CoralRed else TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("小窗与后台听剧", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (onEnterPip != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceElevated)
                            .clickable {
                                onDismiss()
                                onEnterPip()
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Text("小窗播放 (PiP)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (backgroundAudioPlay) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
                        .border(1.dp, if (backgroundAudioPlay) CoralRed else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onToggleBackgroundAudio(!backgroundAudioPlay) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = if (backgroundAudioPlay) "息屏听剧：开" else "息屏听剧：关",
                        color = if (backgroundAudioPlay) CoralRed else TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (backgroundAudioPlay) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
