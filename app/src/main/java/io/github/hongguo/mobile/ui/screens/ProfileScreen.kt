// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.BuildConfig
import io.github.hongguo.mobile.R
import io.github.hongguo.mobile.core.LATEST_RELEASE_PAGE_URL
import io.github.hongguo.mobile.core.QualityPreference
import io.github.hongguo.mobile.core.UpdateStatus
import io.github.hongguo.mobile.ui.components.PhoneLoginSheet
import io.github.hongguo.mobile.ui.components.QrLoginSheet
import io.github.hongguo.mobile.ui.components.WebLoginDialog
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.localStore.settings.collectAsState()
    val authSession by container.localStore.authSession.collectAsState()
    val feedPrefs by container.localStore.feedPreferences.collectAsState()
    val seenFeedCount by container.localStore.seenFeedCount.collectAsState()
    val warmFeedCount by container.localStore.warmFeedCount.collectAsState()
    val updateStatus by container.updateManager.status.collectAsState()
    val syncing by container.accountManager.syncing.collectAsState()
    val sessionExpired by container.accountManager.sessionExpired.collectAsState()
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showPhoneLogin by remember { mutableStateOf(false) }
    var showWebLogin by remember { mutableStateOf(false) }
    var syncToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { container.mediaCache.getCacheSizeBytes() }
    }

    val qualityPref = QualityPreference.entries.firstOrNull { it.key == settings.quality } ?: QualityPreference.AUTO
    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("我的", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // Account & Official Recommendation Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                ) {
                    val avatar = authSession?.profile?.avatarUrl.orEmpty()
                    if (avatar.isNotEmpty()) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = if (authSession != null) CoralRed else TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (authSession != null) {
                        val p = authSession!!.profile
                        Text(
                            text = p.nickname,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "已登录 · 官方推荐流与云端追剧同步中",
                            color = CoralRed,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            text = "未登录（游客模式）",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "登录后开启官方千人千面推荐与云端历史同步",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (authSession == null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { showQrLogin = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("红果 / 抖音扫码登录（推荐）", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { showPhoneLogin = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("手机号登录", color = TextPrimary, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { showWebLogin = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("官网页面登录", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        enabled = !syncing,
                        onClick = {
                            scope.launch {
                                val ok = container.accountManager.syncCloudLibrary()
                                syncToast = if (ok) "云端追剧与历史已同步" else "同步暂未成功，请稍后重试"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (syncing) "同步中..." else "同步云端记录", color = TextPrimary)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                container.accountManager.logout()
                                syncToast = "已退出登录，切换回游客模式"
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("退出登录", color = TextSecondary)
                    }
                }
                if (sessionExpired) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CoralRed.copy(alpha = 0.16f))
                            .clickable { showQrLogin = true }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "账号登录态可能已过期，点击一键重新扫码续期",
                            color = CoralRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (!syncToast.isNullOrBlank()) {
                Text(syncToast.orEmpty(), color = TextSecondary, fontSize = 12.sp)
            }
        }

        // Quality selector (2 rows x 3 columns)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Text("默认画质", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreference.entries.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { q ->
                            val selected = q == qualityPref
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) CoralRed else SurfaceElevated)
                                    .clickable { container.localStore.updateSettings { it.copy(quality = q.key) } }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    text = q.label.substringBefore(" "),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // Default speed selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Text("默认倍速（播放画面长按可临时 2.0x）", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                speeds.forEach { spd ->
                    val selected = kotlin.math.abs(spd - settings.playbackSpeed) < 0.01f
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CoralRed else SurfaceElevated)
                            .clickable { container.localStore.updateSettings { it.copy(playbackSpeed = spd) } }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = "${spd}x",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Switches
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动连播下一集", color = TextPrimary, fontSize = 15.sp)
                    Text("当前集播完自动上滑切换到下一集", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.autoPlayNext,
                    onCheckedChange = { checked -> container.localStore.updateSettings { it.copy(autoPlayNext = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CoralRed)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("后台预加载后续分集", color = TextPrimary, fontSize = 15.sp)
                    Text("提前缓冲下 1-2 集首段数据，滑屏切集零等待", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.preloadNextEpisode,
                    onCheckedChange = { checked -> container.localStore.updateSettings { it.copy(preloadNextEpisode = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CoralRed)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("仅 Wi-Fi 预加载下一集", color = TextPrimary, fontSize = 15.sp)
                    Text("使用移动流量时暂不后台预缓冲后续分集", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.wifiOnlyPreload,
                    onCheckedChange = { checked -> container.localStore.updateSettings { it.copy(wifiOnlyPreload = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CoralRed)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("移动网络自动 480P 省流", color = TextPrimary, fontSize = 15.sp)
                    Text("非 Wi-Fi 环境播放自动降档至 480P，切回 Wi-Fi 自动恢复", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.cellularAutoLowQuality,
                    onCheckedChange = { checked -> container.localStore.updateSettings { it.copy(cellularAutoLowQuality = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CoralRed)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("息屏 / 后台继续听剧", color = TextPrimary, fontSize = 15.sp)
                    Text("锁屏或切到后台时保持声音播放不自动暂停", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.backgroundAudioPlay,
                    onCheckedChange = { checked -> container.localStore.updateSettings { it.copy(backgroundAudioPlay = checked) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CoralRed)
                )
            }
        }

        // Cache & feed preferences card
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("视频缓冲缓存", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val mb = cacheBytes / (1024.0 * 1024.0)
                Text(
                    "当前占用 ${"%.1f".format(mb)} MB · 已预缓 $warmFeedCount 部冷启动秒开短剧",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val refreshed = withContext(Dispatchers.IO) {
                            container.mediaCache.clearAllCache()
                            container.localStore.clearWarmFeedCache()
                            container.mediaCache.getCacheSizeBytes()
                        }
                        cacheBytes = refreshed
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
            ) {
                Text("清理", color = TextPrimary)
            }
        }

        val hasDislikes = feedPrefs.dislikedSeriesIds.isNotEmpty() || feedPrefs.dislikedTags.isNotEmpty()
        if (hasDislikes || seenFeedCount > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(14.dp)
            ) {
                Text("推荐过滤记录", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (hasDislikes) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "已屏蔽 ${feedPrefs.dislikedSeriesIds.size} 部剧集 · ${feedPrefs.dislikedTags.size} 个题材",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                container.localStore.clearDislikes()
                                container.apiClient.clearDislikedRecFilters()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Text("重置", color = TextPrimary)
                        }
                    }
                }
                if (seenFeedCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "已刷去重 $seenFeedCount 部剧集",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                container.localStore.clearSeenFeed()
                                container.apiClient.clearSeenRecFilters()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Text("清空已刷", color = TextPrimary)
                        }
                    }
                }
                if (feedPrefs.dislikedTags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(feedPrefs.dislikedTags, key = { it }) { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SurfaceElevated)
                                    .clickable { container.localStore.removeDislikedTag(tag) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(tag, color = TextPrimary, fontSize = 12.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除屏蔽标签",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // About & developer credits
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_brand_logo),
                    contentDescription = "皮果刷剧 Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "皮果刷剧 v${BuildConfig.VERSION_NAME}",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("开源无广告短剧客户端 · GPL-3.0", color = TextSecondary, fontSize = 12.sp)
                }
                val hasNewUpdate = updateStatus is UpdateStatus.Available ||
                    updateStatus is UpdateStatus.ReadyToInstall
                Button(
                    onClick = {
                        when (updateStatus) {
                            is UpdateStatus.Available,
                            is UpdateStatus.ReadyToInstall,
                            is UpdateStatus.Downloading -> container.updateManager.openUpdateDialog()
                            else -> container.updateManager.checkForUpdates(silent = false)
                        }
                    },
                    enabled = updateStatus !is UpdateStatus.Checking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasNewUpdate) CoralRed else SurfaceElevated
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when (updateStatus) {
                            is UpdateStatus.Checking -> "检查中..."
                            is UpdateStatus.Available -> "发现新版"
                            is UpdateStatus.ReadyToInstall -> "安装新版"
                            is UpdateStatus.Downloading -> "下载中"
                            else -> "检查更新"
                        },
                        color = if (hasNewUpdate) Color.White else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (hasNewUpdate) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            when (val u = updateStatus) {
                is UpdateStatus.Checking -> {
                    Text("正在检查更新...", color = TextSecondary, fontSize = 12.sp)
                }
                is UpdateStatus.UpToDate -> {
                    Text("当前已是最新版本 (v${u.currentVersion})", color = Color(0xFF7AD97A), fontSize = 12.sp)
                }
                is UpdateStatus.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = u.message,
                            color = CoralRed,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "前往 Releases",
                            color = Color(0xFF8EC5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                container.updateManager.openReleasePageInBrowser(
                                    context,
                                    u.info?.htmlUrl ?: LATEST_RELEASE_PAGE_URL
                                )
                            }
                        )
                    }
                }
                else -> Unit
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = "作者：皮皮未来 · 周一 · xscanzm",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "功臣：Gemini",
                    color = CoralRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "基于 Jetpack Compose + Media3 原生构建，支持官方扫码登录、智能首段缓冲预加载、横屏沉浸手势、追剧双向同步与 S/A/B/C 剧集口碑评分。协议签名与 CENC MP4 解密参考移植自 jackotom/hongguo-drama (v7.5.2) 及上游开源项目 N3urda/hongguoTV、woshishiq1/drpys，所有剧集内容与版权归原平台所有。",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }

    if (showQrLogin) {
        QrLoginSheet(
            accountManager = container.accountManager,
            onDismiss = { showQrLogin = false },
            onLoginSuccess = {
                showQrLogin = false
                syncToast = "扫码登录成功，已同步账号推荐与追剧记录"
            }
        )
    }

    if (showPhoneLogin) {
        PhoneLoginSheet(
            accountManager = container.accountManager,
            onDismiss = { showPhoneLogin = false },
            onSwitchToQrLogin = {
                showPhoneLogin = false
                showQrLogin = true
            },
            onSwitchToWebLogin = {
                showPhoneLogin = false
                showWebLogin = true
            },
            onLoginSuccess = {
                showPhoneLogin = false
                syncToast = "登录成功，已同步账号推荐与追剧记录"
            }
        )
    }

    if (showWebLogin) {
        WebLoginDialog(
            accountManager = container.accountManager,
            onDismiss = { showWebLogin = false },
            onLoginSuccess = {
                showWebLogin = false
                syncToast = "官网登录成功，已开启账号推荐流"
            }
        )
    }
}
