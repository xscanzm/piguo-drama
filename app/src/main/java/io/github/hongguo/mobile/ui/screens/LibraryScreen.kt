// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import io.github.hongguo.mobile.ui.components.formatDuration
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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

    LaunchedEffect(authSession?.profile?.userId) {
        if (authSession != null) {
            container.accountManager.syncCloudLibrary()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
    ) {
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

        if (tabIdx == 0) {
            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有观看记录，去首页刷一刷短剧吧", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(history, key = { it.seriesId }) { entry ->
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
                                Text(
                                    text = entry.title,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                val totalText = if (entry.totalEpisodes > 0) " / 共${entry.totalEpisodes}集" else ""
                                Text(
                                    text = "看到第${entry.lastEpisodeIndex}集$totalText · ${formatDuration(entry.positionMs)}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
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
                            IconButton(onClick = { container.localStore.removeHistory(entry.seriesId) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "删除记录", tint = TextSecondary)
                            }
                        }
                    }
                }
            }
        } else {
            if (favorites.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有追剧收藏，点击播放页「追剧」即可收藏", color = TextSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(favorites, key = { it.seriesId }) { fav ->
                        val hist = history.firstOrNull { it.seriesId == fav.seriesId }
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
                                Text(
                                    text = fav.title,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                val status = if (hist != null) "上次看到第${hist.lastEpisodeIndex}集"
                                else if (fav.totalEpisodes > 0) "共${fav.totalEpisodes}集"
                                else "点击立即观看"
                                Text(text = status, color = CoralRed, fontSize = 12.sp)
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
                                    DramaSummary(seriesId = fav.seriesId, title = fav.title)
                                )
                            }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "取消追剧", tint = TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
