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

object SeriesSeasonMatcher {
    private val STRIP_NOISE_REGEX = Regex("[\\s：:·\\-—_~，,。.！!？?、（）()【】\\[\\]《》<>“”\"']+")
    private val TRAILING_SEP_REGEX = Regex("[\\s：:·\\-—_、]+$")

    private val PATTERN_DI_SEASON = Regex(
        "^(.*?)([\\s：:·\\-—_]*(?:[（(\\[【]\\s*)?第([一二三四五六七八九十两\\d]+)([季部篇辑])(?:[：:·\\-—\\s][^）)\\]】]*)?(?:\\s*[）)\\]】])?)$"
    )
    private val PATTERN_BRACKET_PART = Regex(
        "^(.*?)([\\s：:·\\-—_]*[（(\\[【]\\s*([上中下终])([季部篇集]?)\\s*[）)\\]】])$"
    )
    private val PATTERN_SUFFIX_PART = Regex(
        "^(.*?)([\\s：:·\\-—_]*([上中下终])([季部篇集]))$"
    )
    private val PATTERN_SEQUEL_SPINOFF = Regex(
        "^(.*?)([\\s：:·\\-—_]*(?:[（(\\[【]\\s*)?(续集|番外篇?)(?:\\s*[）)\\]】])?)$"
    )
    private val PATTERN_TRAILING_NUM_OR_ROMAN = Regex(
        "^(.*?)([\\s：:·\\-—_]*(?:[（(\\[【]\\s*)?(?:(?<!\\d)(1[0-9]|20|[2-9])|(?<![A-Za-z])(II|III|IV|V|Ⅱ|Ⅲ|Ⅳ|Ⅴ))(?:\\s*[）)\\]】])?)$"
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

        PATTERN_SEQUEL_SPINOFF.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val marker = m.groupValues[3]
            val num = if (marker == "续集") 2 else 98
            makeResult(rawBase, num, marker)?.let { return it }
        }

        PATTERN_TRAILING_NUM_OR_ROMAN.matchEntire(trimmed)?.let { m ->
            val rawBase = m.groupValues[1]
            val numToken = m.groupValues[3].ifEmpty { m.groupValues[4] }
            val num = parseChineseOrArabicInt(numToken)
            if (num != null && !rawBase.trimEnd().endsWith("第")) {
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

    fun matchAndSortSeasons(
        current: DramaSummary,
        searchCandidates: List<DramaSummary>
    ): List<SeriesSeasonItem> {
        val curParsed = parseTitle(current.title)
        if (curParsed.normalizedBase.length < 2) return emptyList()

        val deduped = LinkedHashMap<String, DramaSummary>()
        if (current.seriesId.isNotBlank()) {
            deduped[current.seriesId] = current
        }
        for (cand in searchCandidates) {
            if (cand.seriesId.isNotBlank() && !deduped.containsKey(cand.seriesId)) {
                deduped[cand.seriesId] = cand
            }
        }

        val matched = deduped.values.mapNotNull { item ->
            val parsed = parseTitle(item.title)
            if (parsed.normalizedBase == curParsed.normalizedBase) item to parsed else null
        }
        if (matched.size < 2) return emptyList()

        val hasExplicitFirst = matched.any { it.second.seasonNumber == 1 }
        return matched.map { (item, parsed) ->
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
                    seasonNumber = if (parsed.seasonNumber != null) parsed.seasonNumber else sortOrder,
                    displayLabel = label,
                    isCurrent = item.seriesId == current.seriesId
                )
            )
        }.sortedWith(
            compareBy<Pair<Int, SeriesSeasonItem>>(
                { it.first },
                { it.second.summary.title.length },
                { it.second.summary.seriesId }
            )
        ).map { it.second }
    }
}
