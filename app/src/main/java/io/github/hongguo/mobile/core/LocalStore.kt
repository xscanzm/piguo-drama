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

class LocalStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileWriteLock = Any()
    @Volatile
    private var historyWriteJob: Job? = null

    private val dir = File(context.filesDir, "hongguo_store").apply { mkdirs() }
    private val historyFile = File(dir, "watch_history.json")
    private val favoritesFile = File(dir, "favorites.json")
    private val settingsFile = File(dir, "settings.json")
    private val authFile = File(dir, "auth_session.json")
    private val feedPrefsFile = File(dir, "feed_preferences.json")
    private val searchHistoryFile = File(dir, "search_history.json")

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

    private inline fun <reified T> loadList(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<T>>(file.readText(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun loadSettings(): UserSettings {
        if (!settingsFile.exists()) return UserSettings()
        return runCatching {
            json.decodeFromString<UserSettings>(settingsFile.readText(StandardCharsets.UTF_8))
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

    private fun persistAsync(file: File, content: String) {
        ioScope.launch {
            runCatching { writeAtomicLocked(file, content) }
        }
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
            runCatching { writeAtomicLocked(historyFile, json.encodeToString(_history.value)) }
        }
    }

    fun flushWatchHistory() {
        historyWriteJob?.cancel()
        historyWriteJob = null
        val snapshot = _history.value
        persistAsync(historyFile, json.encodeToString(snapshot))
    }

    fun getLastWatchEntry(seriesId: String): WatchHistoryEntry? =
        _history.value.firstOrNull { it.seriesId == seriesId }

    @Synchronized
    fun removeHistory(seriesId: String) {
        val next = _history.value.filter { it.seriesId != seriesId }
        _history.value = next
        historyWriteJob?.cancel()
        persistAsync(historyFile, json.encodeToString(next))
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
        persistAsync(favoritesFile, json.encodeToString(next))
        return !exists
    }

    fun isFavorite(seriesId: String): Boolean =
        _favorites.value.any { it.seriesId == seriesId }

    @Synchronized
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persistAsync(settingsFile, json.encodeToString(next))
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
    fun dislikeSeries(seriesId: String) {
        val cur = _feedPreferences.value
        val nextIds = (listOf(seriesId) + cur.dislikedSeriesIds.filter { it != seriesId }).take(500)
        val next = cur.copy(dislikedSeriesIds = nextIds)
        _feedPreferences.value = next
        persistAsync(feedPrefsFile, json.encodeToString(next))
    }

    @Synchronized
    fun undislikeSeries(seriesId: String) {
        val cur = _feedPreferences.value
        val nextIds = cur.dislikedSeriesIds.filter { it != seriesId }
        if (nextIds.size == cur.dislikedSeriesIds.size) return
        val next = cur.copy(dislikedSeriesIds = nextIds)
        _feedPreferences.value = next
        persistAsync(feedPrefsFile, json.encodeToString(next))
    }

    @Synchronized
    fun dislikeTag(tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        val cur = _feedPreferences.value
        val nextTags = (listOf(clean) + cur.dislikedTags.filter { it != clean }).take(50)
        val next = cur.copy(dislikedTags = nextTags)
        _feedPreferences.value = next
        persistAsync(feedPrefsFile, json.encodeToString(next))
    }

    @Synchronized
    fun removeDislikedTag(tag: String) {
        val cur = _feedPreferences.value
        val nextTags = cur.dislikedTags.filter { it != tag }
        if (nextTags.size == cur.dislikedTags.size) return
        val next = cur.copy(dislikedTags = nextTags)
        _feedPreferences.value = next
        persistAsync(feedPrefsFile, json.encodeToString(next))
    }

    @Synchronized
    fun clearDislikes() {
        val next = FeedPreferences()
        _feedPreferences.value = next
        persistAsync(feedPrefsFile, json.encodeToString(next))
    }

    @Synchronized
    fun addSearchHistory(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val next = (listOf(clean) + _searchHistory.value.filter { it != clean }).take(15)
        _searchHistory.value = next
        persistAsync(searchHistoryFile, json.encodeToString(next))
    }

    @Synchronized
    fun removeSearchHistory(keyword: String) {
        val next = _searchHistory.value.filter { it != keyword }
        _searchHistory.value = next
        persistAsync(searchHistoryFile, json.encodeToString(next))
    }

    @Synchronized
    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        persistAsync(searchHistoryFile, json.encodeToString(emptyList<String>()))
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
        persistAsync(historyFile, json.encodeToString(sorted))
    }

    @Synchronized
    fun mergeRemoteFavorites(remote: List<FavoriteEntry>) {
        if (remote.isEmpty()) return
        val existingIds = _favorites.value.mapTo(HashSet()) { it.seriesId }
        val additions = remote.filter { existingIds.add(it.seriesId) }
        if (additions.isEmpty()) return
        val next = (_favorites.value + additions).sortedByDescending { it.addedAt }
        _favorites.value = next
        persistAsync(favoritesFile, json.encodeToString(next))
    }
}
