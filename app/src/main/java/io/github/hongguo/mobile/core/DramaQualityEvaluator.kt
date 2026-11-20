// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Unified S/A/B/C quality rating model calibrated against live Hongguo per-episode like,
 * collection, and recent play metrics.
 */
object DramaQualityEvaluator {

    private fun logScore(x: Double, lo: Double, hi: Double): Double {
        if (x <= lo) return 0.0
        if (x >= hi) return 100.0
        return 100.0 * (log10(x) - log10(lo)) / (log10(hi) - log10(lo))
    }

    private fun linScore(x: Double, lo: Double, hi: Double): Double =
        ((x - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)

    private fun medianOf(sorted: List<Long>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toDouble()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    fun evaluate(
        episodeDiggs: List<Long>,
        followedCount: Long,
        seriesPlayCount: Long
    ): DramaQualityReport? {
        if (episodeDiggs.size < 5) return null
        val diggs = episodeDiggs.map { it.coerceAtLeast(0L) }
        val totalDigg = diggs.sum()
        if (totalDigg <= 0L) return null

        val n = diggs.size
        val avgDigg = totalDigg.toDouble() / n.toDouble()
        val ep1 = diggs[0]
        val ep2 = diggs[1]
        val base = (if (ep2 > 0L) ep2 else ep1).coerceAtLeast(1L).toDouble()

        val startIdx = floor(0.20 * n).toInt().coerceIn(0, n - 1)
        val endIdx = floor(0.90 * n).toInt().coerceIn(startIdx, n - 1)
        val midTailSorted = diggs.subList(startIdx, endIdx + 1).sorted()
        val midTail = medianOf(midTailSorted)

        val rStory = midTail / base
        val density = totalDigg.toDouble() / followedCount.coerceAtLeast(1L).toDouble()

        val sAvg = logScore(avgDigg, 1500.0, 35000.0)
        val sFav = logScore(followedCount.toDouble(), 30000.0, 1500000.0)
        val sRet = linScore(rStory, 0.15, 0.60)
        val sDen = linScore(density, 1.2, 3.2)
        val sPlay = logScore(seriesPlayCount.toDouble(), 100000.0, 30000000.0)

        val rawTotal = 0.32 * sAvg + 0.28 * sFav + 0.25 * sRet + 0.10 * sDen + 0.05 * sPlay
        val tailCollapsed = rStory < 0.20
        val finalScore = if (tailCollapsed && rawTotal >= 80.0) 79.0 else rawTotal

        val grade = when {
            finalScore >= 82.0 -> "S"
            finalScore >= 65.0 -> "A"
            finalScore >= 46.0 -> "B"
            else -> "C"
        }
        val isDarkHorse = followedCount < 300_000L && seriesPlayCount >= 3_000_000L && rStory >= 0.45

        return DramaQualityReport(
            score = ((finalScore * 10.0).roundToInt() / 10.0f),
            grade = grade,
            isDarkHorse = isDarkHorse,
            episodeCount = n,
            followedCount = followedCount,
            seriesPlayCount = seriesPlayCount,
            totalDiggCount = totalDigg,
            avgEpisodeDigg = avgDigg.roundToInt().toLong(),
            storyRetention = rStory.toFloat(),
            diggPerFollow = density.toFloat(),
            ep1Digg = ep1,
            ep2Digg = ep2,
            midTailDigg = midTail.roundToInt().toLong(),
            tailCollapsed = tailCollapsed
        )
    }

    fun formatCompactCount(count: Long): String {
        if (count <= 0L) return "0"
        return when {
            count < 10_000L -> count.toString()
            count < 100_000_000L -> String.format(Locale.US, "%.1f万", count / 10_000.0)
            else -> String.format(Locale.US, "%.2f亿", count / 100_000_000.0)
        }
    }
}
