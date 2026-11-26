// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class LocalStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileWriteLock = Any()
    private val pendingFileWrites = ConcurrentHashMap<String, Job>()
    @Volatile
    private var historyWriteJob: Job? = null
    @Volatile
    private var seenWriteJob: Job? = null
    @Volatile
    private var warmWriteJob: Job? = null
    @Volatile
    private var resumeWriteJob: Job? = null

    private val dir = File(context.filesDir, "hongguo_store").apply { mkdirs() }
    private val historyFile = File(dir, "watch_history.json")
    private val favoritesFile = File(dir, "favorites.json")
    private val settingsFile = File(dir, "settings.json")
    private val authFile = File(dir, "auth_session.json")
    private val feedPrefsFile = File(dir, "feed_preferences.json")
    private val searchHistoryFile = File(dir, "search_history.json")
    private val resumeFile = File(dir, "resume_playback.json")
    private val seenFeedStore = SeenFeedStore(File(dir, "seen_feed.json"))
    private val warmFeedStore = FeedWarmCacheStore(File(dir, "warm_feed_cache.json"), persistOnWrite = false)

    private val _seenFeedCount = MutableStateFlow(seenFeedStore.size())
    val seenFeedCount: StateFlow<Int> = _seenFeedCount.asStateFlow()

    private val _warmFeedCount = MutableStateFlow(warmFeedStore.size())
    val warmFeedCount: StateFlow<Int> = _warmFeedCount.asStateFlow()

    private val _history = MutableStateFlow(loadList<WatchHistoryEntry>(historyFile))
    val history: StateFlow<List<WatchHistoryEntry>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow(loadList<FavoriteEntry>(favoritesFile))
    val favorites: StateFlow<List<FavoriteEntry>> = _favorites.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _authSession = MutableStateFlow(loadAuthSession())
    val authSession: StateFlow<AuthSession?> = _authSession.asStateFlow()

    private val _feedPreferences = MutableStateFlow(loadFeedPreferences())
    val feedPreferences: StateFlow<FeedPreferences> = _feedPreferences.asStateFlow()

    private val _searchHistory = MutableStateFlow(loadList<String>(searchHistoryFile))
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _resumePlayback = MutableStateFlow(loadResumePlayback())
    val resumePlayback: StateFlow<ResumePlaybackState?> = _resumePlayback.asStateFlow()

    private inline fun <reified T> loadList(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<T>>(file.readText(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun loadSettings(): UserSettings {
        if (!settingsFile.exists()) return UserSettings()
        return runCatching {
            val loaded = json.decodeFromString<UserSettings>(settingsFile.readText(StandardCharsets.UTF_8))
            if (!loaded.fitModeUserChosen && loaded.fitModeFill) {
                loaded.copy(fitModeFill = false)
            } else {
                loaded
            }
        }.getOrDefault(UserSettings())
    }

    private fun loadAuthSession(): AuthSession? {
        if (!authFile.exists()) return null
        return runCatching {
            json.decodeFromString<AuthSession>(authFile.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun loadFeedPreferences(): FeedPreferences {
        if (!feedPrefsFile.exists()) return FeedPreferences()
        return runCatching {
            json.decodeFromString<FeedPreferences>(feedPrefsFile.readText(StandardCharsets.UTF_8))
        }.getOrDefault(FeedPreferences())
    }

    private fun loadResumePlayback(): ResumePlaybackState? {
        if (!resumeFile.exists()) return null
        return runCatching {
            json.decodeFromString<ResumePlaybackState>(resumeFile.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun writeAtomicLocked(file: File, content: String) {
        synchronized(fileWriteLock) {
            val atomic = AtomicFile(file)
            val fos = atomic.startWrite()
            try {
                fos.write(content.toByteArray(StandardCharsets.UTF_8))
                atomic.finishWrite(fos)
            } catch (e: Throwable) {
                atomic.failWrite(fos)
                throw e
            }
        }
    }

    private fun persistLatestAsync(file: File, snapshotProvider: () -> String) {
        val key = file.name
        pendingFileWrites[key]?.cancel()
        val job = ioScope.launch {
            runCatching {
                synchronized(fileWriteLock) {
                    val content = snapshotProvider()
                    val atomic = AtomicFile(file)
                    val fos = atomic.startWrite()
                    try {
                        fos.write(content.toByteArray(StandardCharsets.UTF_8))
                        atomic.finishWrite(fos)
                    } catch (e: Throwable) {
                        atomic.failWrite(fos)
                        throw e
                    }
                }
            }
        }
        pendingFileWrites[key] = job
    }

    @Synchronized
    fun recordWatchProgress(
        seriesId: String,
        title: String,
        cover: String,
        totalEpisodes: Int,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long
    ) {
        val entry = WatchHistoryEntry(
            seriesId = seriesId,
            title = title,
            cover = cover,
            totalEpisodes = totalEpisodes,
            lastEpisodeIndex = episodeIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis()
        )
        val next = (listOf(entry) + _history.value.filter { it.seriesId != seriesId }).take(150)
        _history.value = next
        historyWriteJob?.cancel()
        historyWriteJob = ioScope.launch {
            delay(5000L)
            persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
        }
    }

    fun flushWatchHistory() {
        historyWriteJob?.cancel()
        historyWriteJob = null
        persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
    }

    fun getLastWatchEntry(seriesId: String): WatchHistoryEntry? =
        _history.value.firstOrNull { it.seriesId == seriesId }

    @Synchronized
    fun removeHistory(seriesId: String) {
        val next = _history.value.filter { it.seriesId != seriesId }
        _history.value = next
        historyWriteJob?.cancel()
        persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
    }

    @Synchronized
    fun clearHistory() {
        if (_history.value.isEmpty()) return
        _history.value = emptyList()
        historyWriteJob?.cancel()
        persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
    }

    @Synchronized
    fun restoreHistoryEntry(entry: WatchHistoryEntry) {
        val existing = _history.value.firstOrNull { it.seriesId == entry.seriesId }
        if (existing != null && existing.updatedAt >= entry.updatedAt) return
        val next = (listOf(entry) + _history.value.filter { it.seriesId != entry.seriesId })
            .sortedByDescending { it.updatedAt }
            .take(150)
        _history.value = next
        historyWriteJob?.cancel()
        persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
    }

    @Synchronized
    fun toggleFavorite(detail: DramaSummary): Boolean {
        val exists = _favorites.value.any { it.seriesId == detail.seriesId }
        val next = if (exists) {
            _favorites.value.filter { it.seriesId != detail.seriesId }
        } else {
            listOf(
                FavoriteEntry(
                    seriesId = detail.seriesId,
                    title = detail.title,
                    cover = detail.cover,
                    totalEpisodes = detail.episodeCount,
                    description = detail.description
                )
            ) + _favorites.value
        }
        _favorites.value = next
        persistLatestAsync(favoritesFile) { json.encodeToString(_favorites.value) }
        return !exists
    }

    fun isFavorite(seriesId: String): Boolean =
        _favorites.value.any { it.seriesId == seriesId }

    @Synchronized
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persistLatestAsync(settingsFile) { json.encodeToString(_settings.value) }
    }

    @Synchronized
    fun saveAuthSession(session: AuthSession) {
        _authSession.value = session
        writeAtomicLocked(authFile, json.encodeToString(session))
    }

    @Synchronized
    fun clearAuthSession() {
        _authSession.value = null
        synchronized(fileWriteLock) {
            if (authFile.exists()) authFile.delete()
        }
    }

    @Synchronized
    fun dislikeSeries(seriesId: String, recFilterInfo: String = "") {
        val cur = _feedPreferences.value
        val nextIds = (listOf(seriesId) + cur.dislikedSeriesIds.filter { it != seriesId }).take(500)
        val cleanFilter = recFilterInfo.trim()
        val nextFilters = if (cleanFilter.isNotEmpty()) {
            (listOf(cleanFilter) + cur.dislikedRecFilters.filter { it != cleanFilter }).take(100)
        } else {
            cur.dislikedRecFilters
        }
        val next = cur.copy(dislikedSeriesIds = nextIds, dislikedRecFilters = nextFilters)
        _feedPreferences.value = next
        persistLatestAsync(feedPrefsFile) { json.encodeToString(_feedPreferences.value) }
        removeWarmFeedEntry(seriesId)
    }

    @Synchronized
    fun undislikeSeries(seriesId: String, recFilterInfo: String = "") {
        val cur = _feedPreferences.value
        val nextIds = cur.dislikedSeriesIds.filter { it != seriesId }
        val cleanFilter = recFilterInfo.trim()
        val nextFilters = if (cleanFilter.isNotEmpty()) {
            cur.dislikedRecFilters.filter { it != cleanFilter }
        } else {
            cur.dislikedRecFilters
        }
        if (nextIds.size == cur.dislikedSeriesIds.size && nextFilters.size == cur.dislikedRecFilters.size) return
        val next = cur.copy(dislikedSeriesIds = nextIds, dislikedRecFilters = nextFilters)
        _feedPreferences.value = next
        persistLatestAsync(feedPrefsFile) { json.encodeToString(_feedPreferences.value) }
    }

    fun markFeedSeen(seriesId: String, recFilterInfo: String = "") {
        val count = seenFeedStore.markSeen(seriesId, recFilterInfo)
        _seenFeedCount.value = count
        seenWriteJob?.cancel()
        seenWriteJob = ioScope.launch {
            delay(5_000L)
            seenFeedStore.flush()
        }
        removeWarmFeedEntry(seriesId)
    }

    private fun scheduleWarmFeedFlush() {
        warmWriteJob?.cancel()
        warmWriteJob = ioScope.launch {
            delay(500L)
            warmFeedStore.flush()
        }
    }

    fun flushWarmFeed() {
        warmWriteJob?.cancel()
        warmWriteJob = null
        ioScope.launch {
            warmFeedStore.flush()
        }
    }

    fun warmFeedEntriesSnapshot(): List<WarmFeedEntry> = warmFeedStore.entriesSnapshot()

    fun warmFeedSeriesIds(): Set<String> = warmFeedStore.seriesIdsSnapshot()

    fun warmProtectedCacheFilenames(): Set<String> {
        val base = warmFeedStore.protectedFilenames()
        val resume = _resumePlayback.value ?: return base
        if (resume.vid.isBlank()) return base
        return base + "${resume.vid}_${resume.qualityKey}.mp4"
    }

    @Synchronized
    fun saveResumePlayback(state: ResumePlaybackState) {
        if (state.seriesId.isBlank() ||
            state.episodeIndex < 2 ||
            isSeriesCompleted(state.episodeIndex, state.totalEpisodes, state.positionMs, state.durationMs)
        ) {
            clearResumePlayback()
            return
        }
        _resumePlayback.value = state
        resumeWriteJob?.cancel()
        resumeWriteJob = ioScope.launch {
            delay(2_000L)
            persistLatestAsync(resumeFile) {
                val cur = _resumePlayback.value
                if (cur != null) json.encodeToString(cur) else ""
            }
        }
    }

    @Synchronized
    fun clearResumePlayback() {
        resumeWriteJob?.cancel()
        resumeWriteJob = null
        _resumePlayback.value = null
        pendingFileWrites.remove(resumeFile.name)?.cancel()
        ioScope.launch {
            synchronized(fileWriteLock) {
                if (resumeFile.exists()) resumeFile.delete()
            }
        }
    }

    fun flushResumePlayback() {
        resumeWriteJob?.cancel()
        resumeWriteJob = null
        val snapshot = _resumePlayback.value
        if (snapshot != null) {
            persistLatestAsync(resumeFile) {
                val cur = _resumePlayback.value
                if (cur != null) json.encodeToString(cur) else ""
            }
        } else {
            clearResumePlayback()
        }
    }

    fun upsertWarmFeedEntry(entry: WarmFeedEntry) {
        val n = warmFeedStore.upsert(entry)
        _warmFeedCount.value = n
        scheduleWarmFeedFlush()
    }

    fun removeWarmFeedEntry(seriesId: String) {
        val n = warmFeedStore.remove(seriesId)
        _warmFeedCount.value = n
        scheduleWarmFeedFlush()
    }

    fun pruneWarmFeedEntries(
        excludedIds: Set<String>,
        isValid: (WarmFeedEntry) -> Boolean
    ): List<WarmFeedEntry> {
        val remaining = warmFeedStore.prune(excludedIds, isValid)
        _warmFeedCount.value = remaining.size
        scheduleWarmFeedFlush()
        return remaining
    }

    fun clearWarmFeedCache() {
        warmFeedStore.clear()
        _warmFeedCount.value = 0
        scheduleWarmFeedFlush()
    }

    fun flushSeenFeed() {
        seenWriteJob?.cancel()
        seenWriteJob = null
        ioScope.launch {
            seenFeedStore.flush()
        }
    }

    fun seenSeriesIdsSnapshot(): Set<String> = seenFeedStore.seenIdsSnapshot()

    fun recentSeenRecFilters(limit: Int = 30): List<String> = seenFeedStore.recentRecFilters(limit)

    fun clearSeenFeed() {
        seenFeedStore.clear()
        _seenFeedCount.value = 0
        seenWriteJob?.cancel()
        seenWriteJob = null
        ioScope.launch {
            seenFeedStore.flush()
        }
    }

    @Synchronized
    fun dislikeTag(tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        val cur = _feedPreferences.value
        val nextTags = (listOf(clean) + cur.dislikedTags.filter { it != clean }).take(50)
        val next = cur.copy(dislikedTags = nextTags)
        _feedPreferences.value = next
        persistLatestAsync(feedPrefsFile) { json.encodeToString(_feedPreferences.value) }
    }

    @Synchronized
    fun removeDislikedTag(tag: String) {
        val cur = _feedPreferences.value
        val nextTags = cur.dislikedTags.filter { it != tag }
        if (nextTags.size == cur.dislikedTags.size) return
        val next = cur.copy(dislikedTags = nextTags)
        _feedPreferences.value = next
        persistLatestAsync(feedPrefsFile) { json.encodeToString(_feedPreferences.value) }
    }

    @Synchronized
    fun clearDislikes() {
        val next = FeedPreferences()
        _feedPreferences.value = next
        persistLatestAsync(feedPrefsFile) { json.encodeToString(_feedPreferences.value) }
    }

    @Synchronized
    fun addSearchHistory(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + _searchHistory.value.filter { it != clean }).take(15)
        _searchHistory.value = next
        persistLatestAsync(searchHistoryFile) { json.encodeToString(_searchHistory.value) }
    }

    @Synchronized
    fun removeSearchHistory(keyword: String) {
        val next = _searchHistory.value.filter { it != keyword }
        _searchHistory.value = next
        persistLatestAsync(searchHistoryFile) { json.encodeToString(_searchHistory.value) }
    }

    @Synchronized
    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        persistLatestAsync(searchHistoryFile) { json.encodeToString(_searchHistory.value) }
    }

    @Synchronized
    fun mergeRemoteHistory(remote: List<WatchHistoryEntry>) {
        if (remote.isEmpty()) return
        val merged = HashMap<String, WatchHistoryEntry>()
        for (item in _history.value) merged[item.seriesId] = item
        for (item in remote) {
            val existing = merged[item.seriesId]
            if (existing == null || item.updatedAt > existing.updatedAt) {
                merged[item.seriesId] = item
            }
        }
        val sorted = merged.values.sortedByDescending { it.updatedAt }.take(150)
        _history.value = sorted
        historyWriteJob?.cancel()
        persistLatestAsync(historyFile) { json.encodeToString(_history.value) }
    }

    @Synchronized
    fun mergeRemoteFavorites(remote: List<FavoriteEntry>) {
        if (remote.isEmpty()) return
        val existingIds = _favorites.value.mapTo(HashSet()) { it.seriesId }
        val additions = remote.filter { existingIds.add(it.seriesId) }
        if (additions.isEmpty()) return
        val next = (_favorites.value + additions).sortedByDescending { it.addedAt }
        _favorites.value = next
        persistLatestAsync(favoritesFile) { json.encodeToString(_favorites.value) }
    }
}
