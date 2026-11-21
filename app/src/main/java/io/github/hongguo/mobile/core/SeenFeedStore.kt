// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

fun shouldRecordWatchHistory(
    episodeIndex: Int,
    positionMs: Long,
    alreadyInHistory: Boolean
): Boolean = alreadyInHistory || (episodeIndex >= 2 && positionMs >= 3_000L)

sealed class HomeFeedEndedAction {
    data class OpenNextEpisode(val nextEpisodeIndex: Int) : HomeFeedEndedAction()
    data class AdvanceFeedPage(val nextPage: Int) : HomeFeedEndedAction()
    data object None : HomeFeedEndedAction()
}

fun resolveHomeFeedEndedAction(
    currentEpisodeIndex: Int,
    totalEpisodes: Int,
    currentPage: Int,
    feedSize: Int
): HomeFeedEndedAction {
    val safeEp = currentEpisodeIndex.coerceAtLeast(1)
    return when {
        totalEpisodes <= 0 || safeEp < totalEpisodes -> HomeFeedEndedAction.OpenNextEpisode(safeEp + 1)
        currentPage + 1 < feedSize -> HomeFeedEndedAction.AdvanceFeedPage(currentPage + 1)
        else -> HomeFeedEndedAction.None
    }
}

class SeenFeedStore(
    private val file: File?,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries = LinkedHashMap<String, SeenFeedEntry>()
    private val lock = Any()
    @Volatile
    private var dirty = false

    init {
        val sourceFile = file
        if (sourceFile != null && sourceFile.exists()) {
            runCatching {
                val list = json.decodeFromString<List<SeenFeedEntry>>(
                    sourceFile.readText(StandardCharsets.UTF_8)
                )
                val limit = maxEntries.coerceAtLeast(1)
                val valid = list
                    .filter { it.seriesId.isNotBlank() }
                    .sortedBy { it.seenAt }
                val startIdx = (valid.size - limit).coerceAtLeast(0)
                for (i in startIdx until valid.size) {
                    val item = valid[i]
                    val cleanId = item.seriesId.trim()
                    entries[cleanId] = item.copy(seriesId = cleanId)
                }
            }
        }
    }

    fun markSeen(
        seriesId: String,
        recFilterInfo: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val cleanId = seriesId.trim()
        if (cleanId.isEmpty()) return size()
        val limit = maxEntries.coerceAtLeast(1)

        synchronized(lock) {
            val prev = entries.remove(cleanId)
            val mergedFilter = recFilterInfo.trim().ifEmpty { prev?.recFilterInfo.orEmpty() }
            entries[cleanId] = SeenFeedEntry(
                seriesId = cleanId,
                recFilterInfo = mergedFilter,
                seenAt = nowMs
            )
            while (entries.size > limit) {
                val it = entries.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
            dirty = true
            return entries.size
        }
    }

    fun seenIdsSnapshot(): Set<String> = synchronized(lock) {
        entries.keys.toHashSet()
    }

    fun recentRecFilters(limit: Int): List<String> {
        val cap = limit.coerceAtLeast(0)
        if (cap == 0) return emptyList()
        return synchronized(lock) {
            val out = ArrayList<String>(cap)
            val seenFilters = HashSet<String>()
            val values = entries.values.toList()
            for (i in values.lastIndex downTo 0) {
                val raw = values[i].recFilterInfo
                if (raw.isNotBlank() && seenFilters.add(raw)) {
                    out.add(raw)
                    if (out.size >= cap) break
                }
            }
            out
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            dirty = true
        }
    }

    fun flush() {
        val target = file ?: return
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            entries.values.toList()
        }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot), StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000
    }
}
