// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.FavoriteEntry
import io.github.hongguo.mobile.core.WatchHistoryEntry
import io.github.hongguo.mobile.core.formatRelativeWatchTime
import io.github.hongguo.mobile.core.isHistoryCompleted
import io.github.hongguo.mobile.ui.components.formatDuration
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface LibraryUndoTarget {
    data class History(val entry: WatchHistoryEntry) : LibraryUndoTarget
    data class Favorite(val entry: FavoriteEntry) : LibraryUndoTarget
}

private enum class LibraryFilter(val label: String) {
    ALL("全部"),
    WATCHING("未看完"),
    COMPLETED("已看完")
}

@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenSeriesPlayer: (seriesId: String, episodeIndex: Int, positionMs: Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val history by container.localStore.history.collectAsState()
    val favorites by container.localStore.favorites.collectAsState()
    val authSession by container.localStore.authSession.collectAsState()
    val syncing by container.accountManager.syncing.collectAsState()
    var tabIdx by remember { mutableIntStateOf(0) }
    var statusFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<LibraryUndoTarget?>(null) }
    var undoSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(authSession?.profile?.userId) {
        if (authSession != null) {
            container.accountManager.syncCloudLibrary()
        }
    }

    LaunchedEffect(undoSeq) {
        if (undoSeq > 0) {
            delay(4_000L)
            undoTarget = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    listOf("观看历史 (${history.size})", "我的追剧 (${favorites.size})").forEachIndexed { idx, title ->
                        val selected = idx == tabIdx
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { tabIdx = idx }
                        ) {
                            Text(
                                text = title,
                                color = if (selected) TextPrimary else TextSecondary,
                                fontSize = 17.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(Modifier.height(4.dp))
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(CoralRed)
                                )
                            }
                        }
                    }
                }
                if (tabIdx == 0 && history.isNotEmpty()) {
                    Text(
                        text = "清空",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showClearHistoryDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (authSession != null) {
                    IconButton(
                        enabled = !syncing,
                        onClick = { scope.launch { container.accountManager.syncCloudLibrary() } }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "同步云端",
                            tint = if (syncing) CoralRed else TextSecondary
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 10.dp)
            ) {
                LibraryFilter.entries.forEach { f ->
                    val selected = statusFilter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) CoralRed.copy(alpha = 0.22f) else SurfaceDark)
                            .clickable { statusFilter = f }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = f.label,
                            color = if (selected) CoralRed else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            if (tabIdx == 0) {
                val filteredHistory = remember(history, statusFilter) {
                    when (statusFilter) {
                        LibraryFilter.ALL -> history
                        LibraryFilter.WATCHING -> history.filter { !isHistoryCompleted(it) }
                        LibraryFilter.COMPLETED -> history.filter { isHistoryCompleted(it) }
                    }
                }
                if (filteredHistory.isEmpty()) {
                    val emptyMsg = when {
                        history.isEmpty() -> "还没有观看记录，去首页刷一刷短剧吧"
                        statusFilter == LibraryFilter.COMPLETED -> "暂无已看完的剧集"
                        else -> "暂无未看完的剧集"
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyMsg, color = TextSecondary, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredHistory, key = { it.seriesId }) { entry ->
                            val completed = isHistoryCompleted(entry)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDark)
                                    .clickable {
                                        onOpenSeriesPlayer(entry.seriesId, entry.lastEpisodeIndex, entry.positionMs)
                                    }
                                    .padding(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceElevated)
                                ) {
                                    if (entry.cover.isNotEmpty()) {
                                        AsyncImage(
                                            model = entry.cover,
                                            contentDescription = entry.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = entry.title,
                                            color = TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (completed) {
                                            Text(
                                                text = "已看完",
                                                color = Color(0xFF7AD97A),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF7AD97A).copy(alpha = 0.16f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    val totalText = if (entry.totalEpisodes > 0) " / 共${entry.totalEpisodes}集" else ""
                                    val relTime = formatRelativeWatchTime(entry.updatedAt)
                                    val timeSuffix = if (relTime.isNotEmpty()) " · $relTime" else ""
                                    Text(
                                        text = "看到第${entry.lastEpisodeIndex}集$totalText · ${formatDuration(entry.positionMs)}$timeSuffix",
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entry.durationMs > 0L) {
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f) },
                                            color = CoralRed,
                                            trackColor = SurfaceElevated,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    container.localStore.removeHistory(entry.seriesId)
                                    undoTarget = LibraryUndoTarget.History(entry)
                                    undoSeq++
                                }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除记录", tint = TextSecondary)
                                }
                            }
                        }
                    }
                }
            } else {
                val historyMap = remember(history) { history.associateBy { it.seriesId } }
                val filteredFavorites = remember(favorites, historyMap, statusFilter) {
                    when (statusFilter) {
                        LibraryFilter.ALL -> favorites
                        LibraryFilter.WATCHING -> favorites.filter { fav ->
                            val hist = historyMap[fav.seriesId]
                            hist == null || !isHistoryCompleted(hist)
                        }
                        LibraryFilter.COMPLETED -> favorites.filter { fav ->
                            val hist = historyMap[fav.seriesId]
                            hist != null && isHistoryCompleted(hist)
                        }
                    }
                }
                if (filteredFavorites.isEmpty()) {
                    val emptyMsg = when {
                        favorites.isEmpty() -> "还没有追剧收藏，点击播放页「追剧」即可收藏"
                        statusFilter == LibraryFilter.COMPLETED -> "暂无已看完的追剧"
                        else -> "暂无未看完的追剧"
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyMsg, color = TextSecondary, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredFavorites, key = { it.seriesId }) { fav ->
                            val hist = historyMap[fav.seriesId]
                            val completed = hist != null && isHistoryCompleted(hist)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDark)
                                    .clickable {
                                        onOpenSeriesPlayer(
                                            fav.seriesId,
                                            hist?.lastEpisodeIndex ?: 1,
                                            hist?.positionMs ?: 0L
                                        )
                                    }
                                    .padding(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceElevated)
                                ) {
                                    if (fav.cover.isNotEmpty()) {
                                        AsyncImage(
                                            model = fav.cover,
                                            contentDescription = fav.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = fav.title,
                                            color = TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (completed) {
                                            Text(
                                                text = "已看完",
                                                color = Color(0xFF7AD97A),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF7AD97A).copy(alpha = 0.16f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    val status = when {
                                        completed -> "已全部看完（共${hist?.totalEpisodes ?: fav.totalEpisodes}集）"
                                        hist != null -> "上次看到第${hist.lastEpisodeIndex}集"
                                        fav.totalEpisodes > 0 -> "共${fav.totalEpisodes}集"
                                        else -> "点击立即观看"
                                    }
                                    Text(
                                        text = status,
                                        color = if (completed) Color(0xFF7AD97A) else CoralRed,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton(onClick = {
                                    onOpenSeriesPlayer(fav.seriesId, hist?.lastEpisodeIndex ?: 1, hist?.positionMs ?: 0L)
                                }) {
                                    Icon(
                                        Icons.Default.PlayCircleOutline,
                                        contentDescription = "播放",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    container.accountManager.toggleFavoriteAndSync(
                                        DramaSummary(
                                            seriesId = fav.seriesId,
                                            title = fav.title,
                                            cover = fav.cover,
                                            episodeCount = fav.totalEpisodes,
                                            description = fav.description
                                        )
                                    )
                                    undoTarget = LibraryUndoTarget.Favorite(fav)
                                    undoSeq++
                                }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "取消追剧", tint = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }

        val activeUndo = undoTarget
        AnimatedVisibility(
            visible = activeUndo != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        ) {
            if (activeUndo != null) {
                val msg = when (activeUndo) {
                    is LibraryUndoTarget.History -> "已删除观看记录"
                    is LibraryUndoTarget.Favorite -> "已取消追剧"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF222228).copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text(msg, color = TextPrimary, fontSize = 13.sp)
                    Text(
                        text = "撤销",
                        color = Color(0xFF7AD97A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            when (activeUndo) {
                                is LibraryUndoTarget.History -> container.localStore.restoreHistoryEntry(activeUndo.entry)
                                is LibraryUndoTarget.Favorite -> {
                                    val fav = activeUndo.entry
                                    if (!container.localStore.isFavorite(fav.seriesId)) {
                                        container.accountManager.toggleFavoriteAndSync(
                                            DramaSummary(
                                                seriesId = fav.seriesId,
                                                title = fav.title,
                                                cover = fav.cover,
                                                episodeCount = fav.totalEpisodes,
                                                description = fav.description
                                            )
                                        )
                                    }
                                }
                            }
                            undoTarget = null
                        }
                    )
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("清空观看历史？", fontWeight = FontWeight.Bold) },
            text = { Text("将移除本地全部观看进度记录，已收藏的追剧不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    container.localStore.clearHistory()
                    undoTarget = null
                    showClearHistoryDialog = false
                }) {
                    Text("确认清空", color = CoralRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
    }
}
