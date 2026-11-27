// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile

import android.app.Application
import android.content.ComponentCallbacks2
import io.github.hongguo.mobile.core.AccountManager
import io.github.hongguo.mobile.core.AppUpdateManager
import io.github.hongguo.mobile.core.HongguoApiClient
import io.github.hongguo.mobile.core.LocalStore
import io.github.hongguo.mobile.core.NetworkMonitor
import io.github.hongguo.mobile.player.ExoPlayerPool
import io.github.hongguo.mobile.player.MediaCacheManager
import io.github.hongguo.mobile.player.PlaybackControlBus
import io.github.hongguo.mobile.player.SleepTimerManager
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            }
        )
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val localStore = LocalStore(app)
    val networkMonitor = NetworkMonitor(app)
    val apiClient = HongguoApiClient(
        httpClient = httpClient,
        ratingCacheFile = File(app.filesDir, "hongguo_store/official_ratings.json"),
        deviceIdentityFile = File(app.filesDir, "hongguo_store/device_identity.json")
    ) { localStore.authSession.value }
    val accountManager = AccountManager(httpClient, localStore, apiClient)
    val mediaCache = MediaCacheManager(
        context = app,
        api = apiClient,
        httpClient = httpClient,
        protectedCacheFilenamesProvider = { localStore.warmProtectedCacheFilenames() },
        networkMonitor = networkMonitor
    )
    val updateManager = AppUpdateManager(app, httpClient)
    val playerPool = ExoPlayerPool()
    val sleepTimer = SleepTimerManager()
    val playbackControlBus = PlaybackControlBus()
    val isInPipMode = MutableStateFlow(false)

    init {
        apiClient.seedRecFilters(
            seen = localStore.recentSeenRecFilters(30),
            disliked = localStore.feedPreferences.value.dislikedRecFilters
        )
        accountManager.verifySessionHealthAsync()
    }
}

class HongguoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            container.playerPool.clear()
            container.apiClient.trimMemoryCaches()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!::container.isInitialized) return
        container.playerPool.clear()
        container.apiClient.trimMemoryCaches()
    }
}
