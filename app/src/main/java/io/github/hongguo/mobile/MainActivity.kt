// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.hongguo.mobile.core.isEligibleForAutoResume
import io.github.hongguo.mobile.player.PlaybackControlBus
import io.github.hongguo.mobile.player.PlaybackForegroundService
import io.github.hongguo.mobile.player.PlaybackRemoteCommand
import io.github.hongguo.mobile.ui.components.AppUpdateDialog
import io.github.hongguo.mobile.ui.components.findActivity
import io.github.hongguo.mobile.ui.components.updatePipParams
import io.github.hongguo.mobile.ui.screens.DramaSeriesPlayerScreen
import io.github.hongguo.mobile.ui.screens.HomeFeedScreen
import io.github.hongguo.mobile.ui.screens.LibraryScreen
import io.github.hongguo.mobile.ui.screens.ProfileScreen
import io.github.hongguo.mobile.ui.screens.TheaterScreen
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.HongguoTheme
import io.github.hongguo.mobile.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    private var pipReceiverRegistered = false
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bus = (application as? HongguoApp)?.container?.playbackControlBus ?: return
            when (intent?.action) {
                PlaybackControlBus.ACTION_PIP_TOGGLE_PLAY -> bus.emitCommand(PlaybackRemoteCommand.TOGGLE_PLAY_PAUSE)
                PlaybackControlBus.ACTION_PIP_NEXT -> bus.emitCommand(PlaybackRemoteCommand.NEXT_EPISODE)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(BgBlack.toArgb())
        )
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(PlaybackControlBus.ACTION_PIP_TOGGLE_PLAY)
            addAction(PlaybackControlBus.ACTION_PIP_NEXT)
        }
        ContextCompat.registerReceiver(this, pipActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        pipReceiverRegistered = true
        val container = (application as HongguoApp).container
        setContent {
            HongguoTheme {
                HongguoRootApp(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as HongguoApp).container.updateManager.resumePendingInstallIfAllowed(this)
    }

    override fun onStop() {
        super.onStop()
        val store = (application as HongguoApp).container.localStore
        store.flushWatchHistory()
        store.flushSeenFeed()
        store.flushWarmFeed()
        store.flushResumePlayback()
    }

    override fun onDestroy() {
        if (pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipActionReceiver) }
            pipReceiverRegistered = false
        }
        super.onDestroy()
        if (!isChangingConfigurations) {
            (application as HongguoApp).container.playerPool.clear()
            PlaybackForegroundService.stop(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        (application as HongguoApp).container.isInPipMode.value = isInPictureInPictureMode
    }
}

private enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "首页", Icons.Default.Home),
    THEATER("theater", "剧场", Icons.Default.Explore),
    LIBRARY("library", "追剧", Icons.Default.Favorite),
    PROFILE("profile", "我的", Icons.Default.Person)
}

@Composable
private fun HongguoRootApp(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: MainTab.HOME.route
    val inPip by container.isInPipMode.collectAsState()
    val networkStatus by container.networkMonitor.status.collectAsState()
    val settings by container.localStore.settings.collectAsState()
    val playbackMeta by container.playbackControlBus.meta.collectAsState()
    val updateStatus by container.updateManager.status.collectAsState()
    val showUpdateDialog by container.updateManager.dialogVisible.collectAsState()
    val initialResume = remember {
        val saved = container.localStore.resumePlayback.value
        val enabled = container.localStore.settings.value.autoResumeOnLaunch
        if (isEligibleForAutoResume(saved, enabled)) saved else null
    }
    var pendingAutoResume by remember { mutableStateOf(initialResume) }
    var feedFullscreen by remember { mutableStateOf(false) }
    val showBottomBar = !inPip && MainTab.entries.any { it.route == currentRoute } && !(currentRoute == MainTab.HOME.route && feedFullscreen)
    var searchFocusTrigger by remember { mutableIntStateOf(0) }
    var pendingSearchKeyword by remember { mutableStateOf<String?>(null) }
    var homeRefreshTrigger by remember { mutableIntStateOf(0) }
    var lastHomeBackMs by remember { mutableLongStateOf(0L) }
    var showExitHint by remember { mutableStateOf(false) }

    LaunchedEffect(settings.backgroundAudioPlay, playbackMeta) {
        PlaybackForegroundService.sync(context, settings.backgroundAudioPlay, playbackMeta)
    }

    LaunchedEffect(inPip, playbackMeta.isPlaying, playbackMeta.canSkipNext, playbackMeta.videoWidth, playbackMeta.videoHeight) {
        if (inPip) {
            context.updatePipParams(
                videoWidth = playbackMeta.videoWidth,
                videoHeight = playbackMeta.videoHeight,
                isPlaying = playbackMeta.isPlaying,
                canSkipNext = playbackMeta.canSkipNext
            )
        }
    }

    LaunchedEffect(showExitHint) {
        if (showExitHint) {
            kotlinx.coroutines.delay(2_000L)
            showExitHint = false
        }
    }

    BackHandler(enabled = currentRoute == MainTab.HOME.route && !feedFullscreen && !inPip) {
        val now = System.currentTimeMillis()
        if (now - lastHomeBackMs <= 2_000L) {
            context.findActivity()?.finish()
        } else {
            lastHomeBackMs = now
            showExitHint = true
        }
    }

    fun openTheaterSearch(keyword: String? = null) {
        pendingSearchKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        searchFocusTrigger++
        navController.navigate(MainTab.THEATER.route) {
            popUpTo(MainTab.HOME.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(Unit) {
        val resume = pendingAutoResume
        if (resume != null) {
            navController.navigate(
                "player/${resume.seriesId}?episode=${resume.episodeIndex}&pos=${resume.positionMs}&resumed=true"
            )
            pendingAutoResume = null
        }
        kotlinx.coroutines.delay(2_500L)
        container.updateManager.checkForUpdates(silent = true)
    }

    fun openSeriesPlayer(
        seriesId: String,
        episodeIndex: Int = 1,
        positionMs: Long = 0L,
        replaceCurrent: Boolean = false
    ) {
        val hist = if (episodeIndex == 1 && positionMs == 0L) {
            container.localStore.getLastWatchEntry(seriesId)
        } else null
        val ep = hist?.lastEpisodeIndex ?: episodeIndex
        val pos = hist?.positionMs ?: positionMs
        val route = "player/$seriesId?episode=$ep&pos=$pos&resumed=false"
        if (replaceCurrent) {
            navController.navigate(route) {
                popUpTo("player/{seriesId}?episode={episode}&pos={pos}&resumed={resumed}") { inclusive = true }
            }
        } else {
            navController.navigate(route)
        }
    }

    Scaffold(
        containerColor = BgBlack,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = Color(0xFF101014),
                    contentColor = Color.White
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(MainTab.HOME.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else if (tab == MainTab.HOME) {
                                    homeRefreshTrigger++
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CoralRed,
                                selectedTextColor = CoralRed,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp)
        ) {
            NavHost(
                navController = navController,
                startDestination = MainTab.HOME.route
            ) {
                composable(MainTab.HOME.route) {
                    HomeFeedScreen(
                        container = container,
                        isRouteVisible = currentRoute == MainTab.HOME.route && pendingAutoResume == null,
                        refreshTrigger = homeRefreshTrigger,
                        onFullscreenChange = { feedFullscreen = it },
                        onOpenSeriesPlayer = { id, ep, pos ->
                            navController.navigate("player/$id?episode=$ep&pos=$pos&resumed=false")
                        },
                        onOpenSearch = { kw -> openTheaterSearch(kw) }
                    )
                }
                composable(MainTab.THEATER.route) {
                    TheaterScreen(
                        container = container,
                        focusSearchTrigger = searchFocusTrigger,
                        initialSearchKeyword = pendingSearchKeyword,
                        onConsumeInitialSearchKeyword = { pendingSearchKeyword = null },
                        onOpenSeries = { id -> openSeriesPlayer(id) }
                    )
                }
                composable(MainTab.LIBRARY.route) {
                    LibraryScreen(
                        container = container,
                        onOpenSeriesPlayer = { id, ep, pos ->
                            navController.navigate("player/$id?episode=$ep&pos=$pos&resumed=false")
                        }
                    )
                }
                composable(MainTab.PROFILE.route) {
                    ProfileScreen(container = container)
                }
                composable(
                    route = "player/{seriesId}?episode={episode}&pos={pos}&resumed={resumed}",
                    arguments = listOf(
                        navArgument("seriesId") { type = NavType.StringType },
                        navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                        navArgument("pos") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("resumed") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { entry ->
                    val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                    val ep = entry.arguments?.getInt("episode") ?: 1
                    val pos = entry.arguments?.getLong("pos") ?: 0L
                    val resumed = entry.arguments?.getBoolean("resumed") ?: false
                    DramaSeriesPlayerScreen(
                        container = container,
                        seriesId = seriesId,
                        initialEpisodeIndex = ep,
                        initialPositionMs = pos,
                        autoResumed = resumed,
                        onBack = { navController.popBackStack() },
                        onSwitchSeries = { targetId -> openSeriesPlayer(targetId, replaceCurrent = true) },
                        onSearchAll = { kw -> openTheaterSearch(kw) }
                    )
                }
            }

            AnimatedVisibility(
                visible = !networkStatus.isConnected && !inPip,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 54.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = CoralRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "当前无网络连接 · 已缓存剧集可继续观看",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AnimatedVisibility(
                visible = showExitHint && !inPip,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF222228).copy(alpha = 0.94f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "再按一次退出皮果刷剧",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (showUpdateDialog) {
        AppUpdateDialog(
            status = updateStatus,
            onDismiss = { skip -> container.updateManager.dismissUpdateDialog(skipInSession = skip) },
            onStartDownload = { info -> container.updateManager.startDownloadAndInstall(info, context) },
            onCancelDownload = { container.updateManager.cancelDownload() },
            onTriggerInstall = { ready -> container.updateManager.triggerInstall(context, ready.apkFile, ready.info) },
            onOpenBrowser = { url -> container.updateManager.openReleasePageInBrowser(context, url) }
        )
    }
}
