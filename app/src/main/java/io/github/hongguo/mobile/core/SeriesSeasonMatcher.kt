// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

data class ParsedSeasonTitle(
    val rawTitle: String,
    val baseTitle: String,
    val normalizedBase: String,
    val seasonNumber: Int?,
    val seasonLabel: String
)

data class SeriesSeasonItem(
    val summary: DramaSummary,
    val seasonNumber: Int?,
    val displayLabel: String,
    val isCurrent: Boolean
)

enum class RelatedDramaKind {
    OTHER_VERSION,
    SIMILAR
}

data class RelatedDramaItem(
    val summary: DramaSummary,
    val kind: RelatedDramaKind,
    val badge: String = ""
)

data class RelatedDramaBundle(
    val keyword: String,
    val seasons: List<SeriesSeasonItem>,
    val related: List<RelatedDramaItem>,
    val nextPage: Int = 2,
    val hasMore: Boolean = false,
    val cursor: SearchCursor? = null,
    val officialSeriesIds: Set<String> = emptySet(),
    val officialOtherVersionIds: Set<String> = emptySet()
)

object SeriesSeasonMatcher {
    private val STRIP_NOISE_REGEX = Regex("[\\s：:·\\-—_~，,。.！!？?、（）()【】\\[\\]《》<>“”\"']+")
    private val TRAILING_SEP_REGEX = Regex("[\\s：:·\\-—_、，,]+$")
    private val SHORT_PREFIX_SPLIT_REGEX = Regex("\\s*(?:[：:，,—－]|\\s+-\\s+)\\s*")

    private val PATTERN_DI_SEASON = Regex(
        "^(.*?)([\\s：:·\\-—_,，]*(?:[（(\\[【]\\s*)?第([一二三四五六七八九十两\\d]+)([季部篇辑])(?:(?:[：:·\\-—\\s]|之)[^）)\\]】]*)?(?:\\s*[）)\\]】])?)$"
    )
    private val PATTERN_BRACKET_PART = Regex(
        "^(.*?)([\\s：:·\\-—_]*[（(\\[【]\\s*([上中下终])([季部篇集]?)\\s*[）)\\]】])$"
    )
    private val PATTERN_SUFFIX_PART = Regex(
        "^(.*?)([\\s：:·\\-—_]*([上中下终])([季部篇集]))$"
    )
    private val PATTERN_BRACKET_NUM = Regex(
        "^(.*?)([\\s：:·\\-—_]*[（(\\[【]\\s*([一二三四五六七八九十两\\d]+)\\s*[）)\\]】])$"
    )
    private val PATTERN_SEQUEL_SPINOFF = Regex(
        "^(.*?)([\\s：:·\\-—_]*(?:[（(\\[【]\\s*)?(续集|番外篇?)(?:\\s*[）)\\]】])?)$"
    )
    private val PATTERN_NUM_WITH_SUBTITLE = Regex(
        "^(.*?)([\\s·\\-—_]*(?<!\\d)(1[0-9]|20|[1-9])\\s*[：:·\\-—]\\s*(.+))$"
    )
    private val PATTERN_TRAILING_NUM_OR_ROMAN = Regex(
        "^(.*?)([\\s：:·\\-—_]*(?:[（(\\[【]\\s*)?(?:(?<!\\d)(1[0-9]|20|[1-9])|(?<![A-Za-z\\u2160-\\u216F])(III|II|IV|IX|VIII|VII|VI|V|X|I|Ⅲ|Ⅱ|Ⅳ|Ⅴ|Ⅰ))(?:\\s*[）)\\]】])?)$"
    )

    private val ZH_DIGITS = mapOf(
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9
    )

    private val ROMAN_MAP = mapOf(
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5,
        "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
        "Ⅰ" to 1, "Ⅱ" to 2, "Ⅲ" to 3, "Ⅳ" to 4, "Ⅴ" to 5
    )

    fun parseChineseOrArabicInt(token: String): Int? {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        clean.toIntOrNull()?.takeIf { it in 1..999 }?.let { return it }
        ROMAN_MAP[clean.uppercase()]?.let { return it }
        if (clean == "十") return 10
        if ('十' in clean) {
            val idx = clean.indexOf('十')
            val left = clean.substring(0, idx)
            val right = clean.substring(idx + 1)
            val tens = when {
                left.isEmpty() -> 1
                left.length == 1 -> ZH_DIGITS[left[0]] ?: return null
                else -> return null
            }
            val ones = when {
                right.isEmpty() -> 0
                right.length == 1 -> ZH_DIGITS[right[0]] ?: return null
                else -> return null
            }
            return tens * 10 + ones
        }
        if (clean.length == 1) {
            return ZH_DIGITS[clean[0]]
        }
        return null
    }

    fun normalizeForMatch(text: String): String =
        STRIP_NOISE_REGEX.replace(text, "").lowercase()

    fun normalizeForFuzzyMatch(text: String): String =
        normalizeForMatch(text)
            .replace('她', '他')
            .replace('它', '他')
            .replace('妳', '你')

    internal fun levenshteinDistanceAtMost1(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        if (la == lb) {
            var diffs = 0
            for (i in 0 until la) {
                if (a[i] != b[i] && ++diffs > 1) return false
            }
            return true
        }
        val shorter = if (la < lb) a else b
        val longer = if (la < lb) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else if (skipped) {
                return false
            } else {
                skipped = true
                j++
            }
        }
        return true
    }

    private fun isFuzzyBaseEquivalent(fuzzyA: String, fuzzyB: String): Boolean {
        if (fuzzyA.isEmpty() || fuzzyB.isEmpty()) return false
        if (fuzzyA == fuzzyB) return true
        if (fuzzyA.length >= 6 && fuzzyB.length >= 6 && levenshteinDistanceAtMost1(fuzzyA, fuzzyB)) {
            return true
        }
        return false
    }

    fun parseTitle(rawTitle: String): ParsedSeasonTitle {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) {
            return ParsedSeasonTitle(rawTitle, "", "", null, "")
        }

        fun makeResult(rawBase: String, seasonNumber: Int?, seasonLabel: String): ParsedSeasonTitle? {
            val base = TRAILING_SEP_REGEX.replace(rawBase.trim(), "")
            val norm = normalizeForMatch(base)
            if (norm.length < 2) return null
            return ParsedSeasonTitle(
                rawTitle = rawTitle,
                baseTitle = base,
                normalizedBase = norm,
                seasonNumber = seasonNumber,
                seasonLabel = seasonLabel
            )
        }

        PATTERN_DI_SEASON.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val numToken = m.groupValues[3]
            val unit = m.groupValues[4]
            val num = parseChineseOrArabicInt(numToken)
            if (num != null) {
                makeResult(rawBase, num, "第${num}${unit}")?.let { return it }
            }
        }

        PATTERN_BRACKET_PART.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val part = m.groupValues[3]
            val unit = m.groupValues[4]
            val num = when (part) {
                "上" -> 1
                "中" -> 2
                "下" -> 3
                "终" -> 99
                else -> null
            }
            val label = if (unit.isNotEmpty()) "$part$unit" else "${part}部"
            makeResult(rawBase, num, label)?.let { return it }
        }

        PATTERN_SUFFIX_PART.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val part = m.groupValues[3]
            val unit = m.groupValues[4]
            val num = when (part) {
                "上" -> 1
                "中" -> 2
                "下" -> 3
                "终" -> 99
                else -> null
            }
            makeResult(rawBase, num, "$part$unit")?.let { return it }
        }

        PATTERN_BRACKET_NUM.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val num = parseChineseOrArabicInt(m.groupValues[3])?.takeIf { it in 1..30 }
            val trailingChar = rawBase.trimEnd().lastOrNull()
            if (num != null && trailingChar != '第' && trailingChar != '榜') {
                makeResult(rawBase, num, "第${num}季")?.let { return it }
            }
        }

        PATTERN_SEQUEL_SPINOFF.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val marker = m.groupValues[3]
            val num = if (marker == "续集") 2 else 98
            makeResult(rawBase, num, marker)?.let { return it }
        }

        PATTERN_NUM_WITH_SUBTITLE.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val num = parseChineseOrArabicInt(m.groupValues[3])?.takeIf { it in 1..20 }
            val trailingChar = rawBase.trimEnd().lastOrNull()
            if (num != null && trailingChar != '第' && trailingChar != '榜') {
                makeResult(rawBase, num, "第${num}季")?.let { return it }
            }
        }

        PATTERN_TRAILING_NUM_OR_ROMAN.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val numToken = m.groupValues[3].ifEmpty { m.groupValues[4] }
            val num = parseChineseOrArabicInt(numToken)?.takeIf { it in 1..20 }
            val trailingChar = rawBase.trimEnd().lastOrNull()
            if (num != null && trailingChar != '第' && trailingChar != '榜') {
                makeResult(rawBase, num, "第${num}季")?.let { return it }
            }
        }

        val cleanBase = TRAILING_SEP_REGEX.replace(trimmed, "")
        return ParsedSeasonTitle(
            rawTitle = rawTitle,
            baseTitle = cleanBase,
            normalizedBase = normalizeForMatch(cleanBase),
            seasonNumber = null,
            seasonLabel = ""
        )
    }

    fun extractSearchKeyword(rawTitle: String): String {
        val parsed = parseTitle(rawTitle)
        return parsed.baseTitle.ifBlank { rawTitle.trim() }
    }

    fun extractShortPrefixKeyword(baseTitle: String): String? {
        val clean = baseTitle.trim()
        if (clean.isEmpty()) return null
        val match = SHORT_PREFIX_SPLIT_REGEX.find(clean) ?: return null
        val prefix = TRAILING_SEP_REGEX.replace(clean.substring(0, match.range.first).trim(), "")
        if (prefix.length < 4 || normalizeForMatch(prefix).length < 4) return null
        if (prefix == clean) return null
        return prefix
    }

    internal fun preferBetterSummary(a: DramaSummary, b: DramaSummary): DramaSummary {
        val pa = parseTitle(a.title)
        val pb = parseTitle(b.title)
        val betterTitle = when {
            pb.seasonNumber != null && pa.seasonNumber == null -> b.title
            pa.seasonNumber != null && pb.seasonNumber == null -> a.title
            b.title.length > a.title.length && a.title.isNotBlank() -> b.title
            else -> a.title.ifBlank { b.title }
        }
        return a.copy(
            title = betterTitle,
            cover = a.cover.ifBlank { b.cover },
            episodeCount = maxOf(a.episodeCount, b.episodeCount),
            description = if (b.description.length > a.description.length) b.description else a.description.ifBlank { b.description },
            tags = a.tags.ifEmpty { b.tags },
            episodeLabel = a.episodeLabel.ifBlank { b.episodeLabel },
            followedCount = maxOf(a.followedCount, b.followedCount),
            seriesPlayCount = maxOf(a.seriesPlayCount, b.seriesPlayCount),
            quality = a.quality ?: b.quality,
            categoryNames = a.categoryNames.ifEmpty { b.categoryNames },
            displayTags = a.displayTags.ifEmpty { b.displayTags },
            gender = if (a.gender >= 0) a.gender else b.gender,
            createTimeSec = if (a.createTimeSec > 0L) a.createTimeSec else b.createTimeSec
        )
    }

    fun classifyRelatedDramas(
        current: DramaSummary,
        searchCandidates: List<DramaSummary>,
        officialSeriesIds: Set<String> = emptySet(),
        officialOtherVersionIds: Set<String> = emptySet()
    ): Pair<List<SeriesSeasonItem>, List<RelatedDramaItem>> {
        val curParsed = parseTitle(current.title)
        if (curParsed.normalizedBase.length < 2) return emptyList<SeriesSeasonItem>() to emptyList()
        val curFuzzy = normalizeForFuzzyMatch(curParsed.baseTitle)

        val currentInOtherVersion = current.seriesId.isNotBlank() && current.seriesId in officialOtherVersionIds
        val effectiveSeriesIds: Set<String>
        val effectiveOtherVersionIds: Set<String>
        if (currentInOtherVersion) {
            effectiveSeriesIds = officialOtherVersionIds
            effectiveOtherVersionIds = officialSeriesIds
        } else {
            effectiveSeriesIds = officialSeriesIds
            effectiveOtherVersionIds = officialOtherVersionIds
        }

        val deduped = LinkedHashMap<String, DramaSummary>()
        if (current.seriesId.isNotBlank()) {
            deduped[current.seriesId] = current
        }
        for (cand in searchCandidates) {
            if (cand.seriesId.isBlank()) continue
            if (cand.episodeCount <= 0 && cand.seriesId != current.seriesId) continue
            val existing = deduped[cand.seriesId]
            deduped[cand.seriesId] = if (existing == null) cand else preferBetterSummary(existing, cand)
        }

        val effectiveCurrent = deduped[current.seriesId] ?: current
        val effectiveCurParsed = parseTitle(effectiveCurrent.title)
        val effectiveCurFuzzy = normalizeForFuzzyMatch(effectiveCurParsed.baseTitle).ifEmpty { curFuzzy }

        val restrictOtherVersionToOfficialGroup = currentInOtherVersion && officialOtherVersionIds.size >= 2
        val sameSeriesRaw = ArrayList<Pair<DramaSummary, ParsedSeasonTitle>>()
        val remaining = ArrayList<Pair<DramaSummary, ParsedSeasonTitle>>()

        for (item in deduped.values) {
            val parsed = parseTitle(item.title)
            val candFuzzy = normalizeForFuzzyMatch(parsed.baseTitle)
            val isSameSeries = when {
                item.seriesId == current.seriesId -> true
                item.seriesId in effectiveOtherVersionIds -> false
                item.seriesId in effectiveSeriesIds -> true
                restrictOtherVersionToOfficialGroup -> false
                else -> isFuzzyBaseEquivalent(effectiveCurFuzzy, candFuzzy)
            }
            if (isSameSeries) {
                sameSeriesRaw.add(item to parsed)
            } else {
                remaining.add(item to parsed)
            }
        }

        val seasons: List<SeriesSeasonItem>
        val relatedPool: List<Pair<DramaSummary, ParsedSeasonTitle>>
        if (sameSeriesRaw.size >= 2) {
            val hasExplicitFirst = sameSeriesRaw.any { it.second.seasonNumber == 1 }
            seasons = sameSeriesRaw.map { (item, parsed) ->
                val sortOrder: Int
                val label: String
                if (parsed.seasonLabel.isNotEmpty()) {
                    sortOrder = parsed.seasonNumber ?: Int.MAX_VALUE
                    label = parsed.seasonLabel
                } else if (!hasExplicitFirst) {
                    sortOrder = 1
                    label = "第1季"
                } else {
                    sortOrder = 0
                    label = "同系列"
                }
                Pair(
                    sortOrder,
                    SeriesSeasonItem(
                        summary = item,
                        seasonNumber = parsed.seasonNumber ?: sortOrder,
                        displayLabel = label,
                        isCurrent = item.seriesId == current.seriesId
                    )
                )
            }.sortedWith(
                compareBy<Pair<Int, SeriesSeasonItem>>(
                    { it.first },
                    { !it.second.isCurrent },
                    { it.second.summary.title.length },
                    { it.second.summary.seriesId }
                )
            ).map { it.second }
            relatedPool = remaining
        } else {
            seasons = emptyList()
            relatedPool = deduped.values
                .filter { it.seriesId != current.seriesId }
                .map { it to parseTitle(it.title) }
        }

        val otherVersionItems = ArrayList<RelatedDramaItem>()
        val similarItems = ArrayList<RelatedDramaItem>()
        for ((item, parsed) in relatedPool) {
            if (item.seriesId == current.seriesId) continue
            if (item.seriesId in effectiveOtherVersionIds) {
                otherVersionItems.add(
                    RelatedDramaItem(
                        summary = item,
                        kind = RelatedDramaKind.OTHER_VERSION,
                        badge = if (parsed.seasonLabel.isNotEmpty()) "其他版本 · ${parsed.seasonLabel}" else "其他版本"
                    )
                )
            } else {
                similarItems.add(
                    RelatedDramaItem(
                        summary = item,
                        kind = RelatedDramaKind.SIMILAR,
                        badge = parsed.seasonLabel
                    )
                )
            }
        }
        return seasons to (otherVersionItems + similarItems)
    }

    fun matchAndSortSeasons(
        current: DramaSummary,
        searchCandidates: List<DramaSummary>
    ): List<SeriesSeasonItem> =
        classifyRelatedDramas(current, searchCandidates).first
}
