// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

const val RESUME_PLAYBACK_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

fun isSeriesCompleted(
    episodeIndex: Int,
    totalEpisodes: Int,
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (totalEpisodes <= 0 || episodeIndex < totalEpisodes) return false
    if (durationMs <= 0L) return false
    return positionMs * 10L >= durationMs * 9L
}

fun isEligibleForAutoResume(
    state: ResumePlaybackState?,
    autoResumeEnabled: Boolean,
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    if (!autoResumeEnabled || state == null) return false
    if (state.seriesId.isBlank() || state.episodeIndex < 2) return false
    val ageMs = nowMs - state.updatedAt
    if (ageMs < 0L || ageMs > RESUME_PLAYBACK_MAX_AGE_MS) return false
    return !isSeriesCompleted(
        episodeIndex = state.episodeIndex,
        totalEpisodes = state.totalEpisodes,
        positionMs = state.positionMs,
        durationMs = state.durationMs
    )
}

fun isHistoryCompleted(entry: WatchHistoryEntry): Boolean = isSeriesCompleted(
    episodeIndex = entry.lastEpisodeIndex,
    totalEpisodes = entry.totalEpisodes,
    positionMs = entry.positionMs,
    durationMs = entry.durationMs
)
