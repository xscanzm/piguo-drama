// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.hongguo.mobile.ui.components.AppUpdateDialog
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(BgBlack.toArgb())
        )
        super.onCreate(savedInstanceState)
        val container = (application as HongguoApp).container
        setContent {
            HongguoTheme {
                HongguoRootApp(container)
            }
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
    val updateStatus by container.updateManager.status.collectAsState()
    val showUpdateDialog by container.updateManager.dialogVisible.collectAsState()
    var feedFullscreen by remember { mutableStateOf(false) }
    val showBottomBar = !inPip && MainTab.entries.any { it.route == currentRoute } && !(currentRoute == MainTab.HOME.route && feedFullscreen)
    var searchFocusTrigger by remember { mutableIntStateOf(0) }
    var homeRefreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_500L)
        container.updateManager.checkForUpdates(silent = true)
    }

    fun openSeriesPlayer(seriesId: String, episodeIndex: Int = 1, positionMs: Long = 0L) {
        val hist = if (episodeIndex == 1 && positionMs == 0L) {
            container.localStore.getLastWatchEntry(seriesId)
        } else null
        val ep = hist?.lastEpisodeIndex ?: episodeIndex
        val pos = hist?.positionMs ?: positionMs
        navController.navigate("player/$seriesId?episode=$ep&pos=$pos")
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
                        isRouteVisible = currentRoute == MainTab.HOME.route,
                        refreshTrigger = homeRefreshTrigger,
                        onFullscreenChange = { feedFullscreen = it },
                        onOpenSeriesPlayer = { id, ep, pos ->
                            navController.navigate("player/$id?episode=$ep&pos=$pos")
                        },
                        onOpenSearch = {
                            searchFocusTrigger++
                            navController.navigate(MainTab.THEATER.route) {
                                popUpTo(MainTab.HOME.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(MainTab.THEATER.route) {
                    TheaterScreen(
                        container = container,
                        focusSearchTrigger = searchFocusTrigger,
                        onOpenSeries = { id -> openSeriesPlayer(id) }
                    )
                }
                composable(MainTab.LIBRARY.route) {
                    LibraryScreen(
                        container = container,
                        onOpenSeriesPlayer = { id, ep, pos ->
                            navController.navigate("player/$id?episode=$ep&pos=$pos")
                        }
                    )
                }
                composable(MainTab.PROFILE.route) {
                    ProfileScreen(container = container)
                }
                composable(
                    route = "player/{seriesId}?episode={episode}&pos={pos}",
                    arguments = listOf(
                        navArgument("seriesId") { type = NavType.StringType },
                        navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                        navArgument("pos") { type = NavType.LongType; defaultValue = 0L }
                    )
                ) { entry ->
                    val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                    val ep = entry.arguments?.getInt("episode") ?: 1
                    val pos = entry.arguments?.getLong("pos") ?: 0L
                    DramaSeriesPlayerScreen(
                        container = container,
                        seriesId = seriesId,
                        initialEpisodeIndex = ep,
                        initialPositionMs = pos,
                        onBack = { navController.popBackStack() }
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
            onTriggerInstall = { ready -> container.updateManager.triggerInstall(context, ready.apkFile, ready.info) },
            onOpenBrowser = { url -> container.updateManager.openReleasePageInBrowser(context, url) }
        )
    }
}
