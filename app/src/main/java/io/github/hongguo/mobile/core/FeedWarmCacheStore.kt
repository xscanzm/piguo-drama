// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class FeedWarmCacheStore(
    private val file: File?,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val persistOnWrite: Boolean = true
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries = LinkedHashMap<String, WarmFeedEntry>()
    private val lock = Any()
    @Volatile
    private var dirty = false

    init {
        val sourceFile = file
        if (sourceFile != null && sourceFile.exists()) {
            runCatching {
                val list = json.decodeFromString<List<WarmFeedEntry>>(
                    sourceFile.readText(StandardCharsets.UTF_8)
                )
                val limit = maxEntries.coerceAtLeast(1)
                for (item in list) {
                    val cleanId = item.summary.seriesId.trim()
                    if (cleanId.isNotEmpty() && item.vid.isNotBlank()) {
                        entries[cleanId] = item.copy(
                            summary = item.summary.copy(seriesId = cleanId),
                            vid = item.vid.trim()
                        )
                        if (entries.size >= limit) break
                    }
                }
            }
        }
    }

    fun entriesSnapshot(): List<WarmFeedEntry> = synchronized(lock) {
        entries.values.toList()
    }

    fun seriesIdsSnapshot(): Set<String> = synchronized(lock) {
        entries.keys.toHashSet()
    }

    fun protectedFilenames(): Set<String> = synchronized(lock) {
        entries.values.mapTo(HashSet()) { "${it.vid}_${it.qualityKey}.mp4" }
    }

    fun upsert(entry: WarmFeedEntry): Int {
        val cleanId = entry.summary.seriesId.trim()
        val cleanVid = entry.vid.trim()
        if (cleanId.isEmpty() || cleanVid.isEmpty()) return size()
        val limit = maxEntries.coerceAtLeast(1)

        val snapshot = synchronized(lock) {
            val sanitized = entry.copy(
                summary = entry.summary.copy(seriesId = cleanId),
                vid = cleanVid
            )
            entries[cleanId] = sanitized
            while (entries.size > limit) {
                val it = entries.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
            dirty = true
            entries.values.toList()
        }
        if (persistOnWrite) {
            dirty = false
            persistSnapshot(snapshot)
        }
        return snapshot.size
    }

    fun remove(seriesId: String): Int {
        val cleanId = seriesId.trim()
        val snapshot = synchronized(lock) {
            if (cleanId.isEmpty() || entries.remove(cleanId) == null) {
                return entries.size
            }
            dirty = true
            entries.values.toList()
        }
        if (persistOnWrite) {
            dirty = false
            persistSnapshot(snapshot)
        }
        return snapshot.size
    }

    fun prune(
        excludedSeriesIds: Set<String>,
        isValid: (WarmFeedEntry) -> Boolean
    ): List<WarmFeedEntry> {
        var changed = false
        val snapshot = synchronized(lock) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val item = it.next().value
                if (item.summary.seriesId in excludedSeriesIds || !isValid(item)) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) dirty = true
            entries.values.toList()
        }
        if (changed && persistOnWrite) {
            dirty = false
            persistSnapshot(snapshot)
        }
        return snapshot
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        val hadAny = synchronized(lock) {
            val wasNonEmpty = entries.isNotEmpty()
            entries.clear()
            if (wasNonEmpty || file?.exists() == true) dirty = true
            wasNonEmpty
        }
        if ((hadAny || file?.exists() == true) && persistOnWrite) {
            dirty = false
            persistSnapshot(emptyList())
        }
    }

    fun flush() {
        val target = file ?: return
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            entries.values.toList()
        }
        persistSnapshotToFile(target, snapshot)
    }

    private fun persistSnapshot(snapshot: List<WarmFeedEntry>) {
        val target = file ?: return
        persistSnapshotToFile(target, snapshot)
    }

    private fun persistSnapshotToFile(target: File, snapshot: List<WarmFeedEntry>) {
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
        const val DEFAULT_MAX_ENTRIES = 16
    }
}
