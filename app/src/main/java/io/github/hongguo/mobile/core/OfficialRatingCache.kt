// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class OfficialRatingEntry(
    val seriesId: String,
    val rating: Float? = null,
    val platformScore: Float? = null,
    val gender: Int = -1,
    val updatedAt: Long
)

class OfficialRatingCache(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val entries = ConcurrentHashMap<String, OfficialRatingEntry>()
    private val lock = Any()
    @Volatile
    private var dirty = false
    @Volatile
    private var lastSavedAtMs = 0L

    init {
        val sourceFile = file
        if (sourceFile != null && sourceFile.exists()) {
            runCatching {
                val list = json.decodeFromString<List<OfficialRatingEntry>>(
                    sourceFile.readText(StandardCharsets.UTF_8)
                )
                for (item in list) {
                    if (item.seriesId.isNotBlank()) {
                        entries[item.seriesId] = item
                    }
                }
            }
        }
    }

    fun put(seriesId: String, rating: Float?, platformScore: Float?, gender: Int) {
        val cleanId = seriesId.trim()
        if (cleanId.isEmpty()) return
        if (rating == null && platformScore == null && gender < 0) return

        synchronized(lock) {
            val prev = entries[cleanId]
            val merged = OfficialRatingEntry(
                seriesId = cleanId,
                rating = rating ?: prev?.rating,
                platformScore = platformScore ?: prev?.platformScore,
                gender = if (gender >= 0) gender else (prev?.gender ?: -1),
                updatedAt = System.currentTimeMillis()
            )
            entries[cleanId] = merged
            if (entries.size > MAX_ENTRIES) {
                val toRemove = entries.values
                    .sortedBy { it.updatedAt }
                    .take(entries.size - MAX_ENTRIES)
                for (old in toRemove) {
                    entries.remove(old.seriesId)
                }
            }
            dirty = true
        }
    }

    fun get(seriesId: String): OfficialRatingEntry? = entries[seriesId]

    fun saveIfDirty() {
        val target = file ?: return
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (lastSavedAtMs > 0L && now - lastSavedAtMs < 30_000L) return

        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            lastSavedAtMs = now
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
        private const val MAX_ENTRIES = 2000
    }
}
