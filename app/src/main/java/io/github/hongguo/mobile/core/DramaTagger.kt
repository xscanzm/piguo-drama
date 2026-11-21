// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.json.JSONArray
import org.json.JSONObject

object DramaTagger {
    private val GENERIC = setOf("现代", "古代", "都市", "剧情", "架空", "未来")
    private val GENDER_REGEX = Regex("[?&]gender=(\\d)")
    private val RATING_REGEX = Regex("^评分\\s*(\\d+(?:\\.\\d)?)$")
    private val NEW_TAG_TEXTS = setOf("新剧", "红果首发")

    private fun toCategoryArray(raw: Any?): JSONArray? {
        return when (raw) {
            is JSONArray -> raw
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.startsWith("[")) runCatching { JSONArray(trimmed) }.getOrNull() else null
            }
            else -> null
        }
    }

    fun parseCategoryNames(raw: Any?): List<String> {
        val arr = toCategoryArray(raw) ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name").orEmpty().trim()
            if (name.isNotEmpty() && seen.add(name)) {
                out.add(name)
            }
        }
        return out
    }

    fun parseGender(raw: Any?): Int {
        val arr = toCategoryArray(raw) ?: return -1
        for (i in 0 until arr.length()) {
            val schema = arr.optJSONObject(i)?.optString("schema").orEmpty()
            val match = GENDER_REGEX.find(schema) ?: continue
            val value = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (value != null && value in 0..2) return value
        }
        return -1
    }

    fun pickDisplayTags(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val firstNonGenericIdx = names.indexOfFirst { it !in GENERIC }
        val firstIdx = if (firstNonGenericIdx >= 0) firstNonGenericIdx else 0
        val tag1 = names[firstIdx]

        var tag2: String? = null
        for (i in (firstIdx + 1) until names.size) {
            val cand = names[i]
            if (cand in GENERIC) continue
            if (cand == tag1 || cand.contains(tag1) || tag1.contains(cand)) continue
            tag2 = cand
            break
        }

        if (tag2 == null) {
            for (cand in names) {
                if (cand !in GENERIC || cand == "现代" || cand == "古代") continue
                if (cand == tag1 || cand.contains(tag1) || tag1.contains(cand)) continue
                tag2 = cand
                break
            }
        }

        return if (tag2 != null) listOf(tag1, tag2) else listOf(tag1)
    }

    private fun extractReasonObject(v: JSONObject): JSONObject? {
        val raw = v.opt("recommend_reason_list") ?: return null
        return when (raw) {
            is JSONObject -> raw
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.startsWith("{")) runCatching { JSONObject(trimmed) }.getOrNull() else null
            }
            else -> null
        }
    }

    fun parseOfficialRating(v: JSONObject): Float? {
        fun findInArray(arr: JSONArray?): Float? {
            if (arr == null) return null
            for (i in 0 until arr.length()) {
                val content = arr.optJSONObject(i)?.optString("content").orEmpty().trim()
                val match = RATING_REGEX.matchEntire(content) ?: continue
                val rating = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: continue
                if (rating in 1.0f..10.0f) return rating
            }
            return null
        }
        findInArray(v.optJSONArray("secondary_info_list"))?.let { return it }
        val rr = extractReasonObject(v) ?: return null
        return findInArray(rr.optJSONArray("secondary_info_list"))
    }

    fun parsePlatformScore(v: JSONObject): Float? {
        val score = v.optDouble("score", Double.NaN)
        if (score.isNaN() || score < 1.0 || score > 10.0) return null
        return score.toFloat()
    }

    fun parseNewTag(v: JSONObject): Boolean {
        val direct = v.optJSONObject("tag_info")?.optString("text").orEmpty().trim()
        if (direct in NEW_TAG_TEXTS) return true
        val nested = extractReasonObject(v)?.optJSONObject("tag_info")?.optString("text").orEmpty().trim()
        return nested in NEW_TAG_TEXTS
    }
}
