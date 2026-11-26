// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.serialization.Serializable

enum class CatalogCategory(val key: String, val label: String, val isRanking: Boolean) {
    HOT_DRAMA("hot-drama", "热播总榜", true),
    REAL_DRAMA("real-drama", "短剧", false),
    COMIC_DRAMA("comic-drama", "漫剧", false),
    AI_DRAMA("ai-drama", "AI剧", false),
    HOT_REAL("hot-real-drama", "真人热榜", true),
    HOT_COMIC("hot-comic-drama", "漫剧热榜", true),
    HOT_AI("hot-ai-drama", "AI热榜", true)
}

enum class QualityPreference(val key: String, val label: String, val targetPixels: Int) {
    AUTO("auto", "自动 (1080P优先)", 0),
    P1080("1080p", "1080P 超清", 1080),
    P720("720p", "720P 高清", 720),
    P540("540p", "540P 标清", 540),
    P480("480p", "480P 流畅", 480),
    P360("360p", "360P 省流", 360)
}

@Serializable
data class DramaQualityReport(
    val score: Float,
    val grade: String,
    val isDarkHorse: Boolean = false,
    val episodeCount: Int,
    val followedCount: Long,
    val seriesPlayCount: Long,
    val totalDiggCount: Long,
    val avgEpisodeDigg: Long,
    val storyRetention: Float,
    val diggPerFollow: Float,
    val ep1Digg: Long = 0L,
    val ep2Digg: Long = 0L,
    val midTailDigg: Long = 0L,
    val tailCollapsed: Boolean = false,
    val qualityScore: Float = 0f,
    val heatScore: Float = 0f,
    val confidence: Float = 1f,
    val isPending: Boolean = false,
    val ageDays: Int = -1,
    val createTimeSec: Long = 0L,
    val isCompleted: Boolean? = null,
    val endRetention: Float = 0f,
    val cliffDrop: Float = 0f,
    val cliffEpisode: Int = -1,
    val stability: Float = 0f,
    val baseDigg: Long = 0L,
    val commentPerFollow: Float = 0f,
    val officialRating: Float? = null,
    val platformScore: Float? = null,
    val sRetMid: Float = 0f,
    val sRetEnd: Float = 0f,
    val sCliff: Float = 0f,
    val sInter: Float = 0f,
    val sHeatAvg: Float = 0f,
    val sHeatFav: Float = 0f,
    val sHeatPlay: Float = 0f,
    val verdict: String = ""
)

@Serializable
data class DramaSummary(
    val seriesId: String,
    val title: String,
    val cover: String = "",
    val episodeCount: Int = 0,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val rank: Int? = null,
    val heat: String = "",
    val episodeLabel: String = "",
    val initialVid: String = "",
    val initialEpisodeIndex: Int = 1,
    val recommendInfo: String = "",
    val recFilterInfo: String = "",
    val followedCount: Long = 0L,
    val seriesPlayCount: Long = 0L,
    val quality: DramaQualityReport? = null,
    val categoryNames: List<String> = emptyList(),
    val displayTags: List<String> = emptyList(),
    val gender: Int = -1,
    val createTimeSec: Long = 0L,
    val isNewTag: Boolean = false,
    val officialRating: Float? = null,
    val platformScore: Float? = null
)

data class GenreItem(
    val slug: String,
    val label: String
)

data class CatalogPageResult(
    val items: List<DramaSummary>,
    val genres: List<GenreItem>,
    val page: Int,
    val hasMore: Boolean
)

data class SearchCursor(
    val searchId: String = "",
    val passback: String = "",
    val nextOffset: Int? = null
)

data class SearchPageResult(
    val items: List<DramaSummary>,
    val page: Int,
    val hasMore: Boolean,
    val cursor: SearchCursor? = null,
    val officialSeriesIds: Set<String> = emptySet(),
    val officialOtherVersionIds: Set<String> = emptySet()
)

@Serializable
data class EpisodeItem(
    val vid: String,
    val index: Int, // 1-based
    val title: String,
    val diggCount: Long = 0L,
    val commentCount: Long = 0L
)

@Serializable
data class DramaDetail(
    val seriesId: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val episodeLabel: String = "",
    val episodes: List<EpisodeItem> = emptyList(),
    val followedCount: Long = 0L,
    val seriesPlayCount: Long = 0L,
    val quality: DramaQualityReport? = null,
    val categoryNames: List<String> = emptyList(),
    val displayTags: List<String> = emptyList(),
    val gender: Int = -1,
    val createTimeSec: Long = 0L,
    val isCompleted: Boolean? = null
)

data class PlayVariant(
    val url: String,
    val contentKeyHex: String?,
    val codec: String,
    val quality: String,
    val width: Int,
    val height: Int,
    val userAgent: String,
    val referer: String = ""
)

data class EpisodePlayResult(
    val vid: String,
    val variants: List<PlayVariant>
)

@Serializable
data class WatchHistoryEntry(
    val seriesId: String,
    val title: String,
    val cover: String = "",
    val totalEpisodes: Int = 0,
    val lastEpisodeIndex: Int = 1, // 1-based
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class FavoriteEntry(
    val seriesId: String,
    val title: String,
    val cover: String = "",
    val totalEpisodes: Int = 0,
    val description: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
data class UserSettings(
    val quality: String = QualityPreference.AUTO.key,
    val playbackSpeed: Float = 1.0f,
    val autoPlayNext: Boolean = true,
    val preloadNextEpisode: Boolean = true,
    val wifiOnlyPreload: Boolean = false,
    val cellularAutoLowQuality: Boolean = false,
    val feedQualityFilter: String = "all",
    val fitModeFill: Boolean = false,
    val fitModeUserChosen: Boolean = false,
    val backgroundAudioPlay: Boolean = false,
    val autoResumeOnLaunch: Boolean = true
)

@Serializable
data class ResumePlaybackState(
    val seriesId: String,
    val title: String,
    val cover: String = "",
    val vid: String = "",
    val qualityKey: String = QualityPreference.AUTO.key,
    val episodeIndex: Int,
    val totalEpisodes: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class AccountProfile(
    val userId: String,
    val nickname: String = "红果用户",
    val avatarUrl: String = "",
    val phoneMasked: String = ""
)

@Serializable
data class AuthSession(
    val cookieHeader: String,
    val csrfToken: String = "",
    val profile: AccountProfile,
    val deviceId: String = "",
    val iid: String = "",
    val cdid: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class FeedPreferences(
    val dislikedSeriesIds: List<String> = emptyList(),
    val dislikedTags: List<String> = emptyList(),
    val dislikedRecFilters: List<String> = emptyList()
)

@Serializable
data class SeenFeedEntry(
    val seriesId: String,
    val recFilterInfo: String = "",
    val seenAt: Long = System.currentTimeMillis()
)

@Serializable
data class WarmFeedEntry(
    val summary: DramaSummary,
    val detail: DramaDetail? = null,
    val vid: String,
    val episodeIndex: Int = 1,
    val qualityKey: String = QualityPreference.AUTO.key,
    val cachedAt: Long = System.currentTimeMillis()
)

sealed class SmsSendResult {
    data class Sent(val message: String = "验证码已发送") : SmsSendResult()
    data class NeedsWebVerify(val reason: String) : SmsSendResult()
    data class Error(val message: String) : SmsSendResult()
}

sealed class LoginResult {
    data class Success(val profile: AccountProfile) : LoginResult()
    data class NeedsWebVerify(val reason: String) : LoginResult()
    data class Error(val message: String) : LoginResult()
}
