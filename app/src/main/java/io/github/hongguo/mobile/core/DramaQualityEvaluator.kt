// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DramaQualityInput(
    val episodeDiggs: List<Long>,
    val episodeComments: List<Long> = emptyList(),
    val followedCount: Long,
    val seriesPlayCount: Long,
    val createTimeSec: Long = 0L,
    val nowSec: Long = System.currentTimeMillis() / 1000L,
    val isCompleted: Boolean? = null,
    val newlyUpdatedCount: Int = 0,
    val officialRating: Float? = null,
    val platformScore: Float? = null
)

/**
 * Quality v2 evaluator combining multi-segment retention, interaction density,
 * official user ratings, launch-age velocity normalization, and sample confidence.
 */
object DramaQualityEvaluator {

    private fun logScore(x: Double, lo: Double, hi: Double): Double {
        if (x <= lo) return 0.0
        if (x >= hi) return 100.0
        return 100.0 * (log10(x) - log10(lo)) / (log10(hi) - log10(lo))
    }

    private fun linScore(x: Double, lo: Double, hi: Double): Double =
        ((x - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)

    private fun median(sorted: List<Long>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid].toDouble()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    private fun mean(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum().toDouble() / values.size.toDouble()
    }

    fun evaluate(input: DramaQualityInput): DramaQualityReport? {
        val diggs = input.episodeDiggs.map { it.coerceAtLeast(0L) }
        val n = diggs.size
        if (n < 5) return null
        val totalDigg = diggs.sum()
        if (totalDigg <= 0L) return null

        val ageDaysRaw = if (input.createTimeSec > 0L) {
            maxOf(1.0, (input.nowSec - input.createTimeSec) / 86400.0)
        } else {
            null
        }

        // Exclude freshly updated or immature tail episodes when computing retention curves
        var mature = diggs
        if (input.isCompleted == false) {
            val trim = maxOf(input.newlyUpdatedCount, 3)
            if (mature.size - trim >= 5) {
                mature = mature.subList(0, mature.size - trim)
            }
        }
        if (ageDaysRaw != null && ageDaysRaw < 7.0) {
            val trim = maxOf(1, ceil(0.10 * mature.size).toInt())
            if (mature.size - trim >= 5) {
                mature = mature.subList(0, mature.size - trim)
            }
        }
        val k = mature.size

        val ep1 = diggs[0]
        val ep2 = diggs[1]
        val base = median(mature.subList(1, minOf(6, k)).sorted()).coerceAtLeast(1.0)

        val startIdx = floor(0.20 * k).toInt().coerceIn(0, k - 1)
        val endIdx = floor(0.85 * k).toInt().coerceIn(startIdx, k - 1)
        val midSlice = mature.subList(startIdx, endIdx + 1)
        val midTail = median(midSlice.sorted())
        val rMid = midTail / base

        val endCount = maxOf(3, ceil(0.12 * k).toInt()).coerceAtMost(k)
        val endMean = mean(mature.subList(k - endCount, k))
        val rEnd = endMean / base

        var cliffDrop = 0.0
        var cliffEpisode = -1
        if (k >= 12) {
            val w = DoubleArray(k - 4) { i -> mean(mature.subList(i, i + 5)) }
            for (i in 2..(k - 10)) {
                if (w[i] <= 0.0) continue
                val drop = ((w[i] - w[i + 5]) / w[i]).coerceAtLeast(0.0)
                if (drop > cliffDrop) {
                    cliffDrop = drop
                    cliffEpisode = i + 5 + 1
                }
            }
        }

        val midMean = mean(midSlice)
        val cv = if (midMean > 0.0) {
            val variance = midSlice.sumOf { v ->
                val d = v - midMean
                d * d
            } / midSlice.size.toDouble()
            sqrt(variance) / midMean
        } else {
            0.0
        }

        val sMid = linScore(rMid, 0.12, 0.55)
        val sEnd = linScore(rEnd, 0.10, 0.55)
        val sCliff = 100.0 - linScore(cliffDrop, 0.35, 0.75)
        val sStab = 100.0 - linScore(cv, 0.25, 0.90)
        val sRet = 0.50 * sMid + 0.25 * sEnd + 0.15 * sCliff + 0.10 * sStab

        val followedSafe = input.followedCount.coerceAtLeast(1L).toDouble()
        val avgDigg = totalDigg.toDouble() / n.toDouble()
        val density = avgDigg / followedSafe
        val sDen = linScore(density, 0.008, 0.030)

        val comments = if (input.episodeComments.size == n) {
            input.episodeComments.map { it.coerceAtLeast(0L) }
        } else {
            emptyList()
        }
        val totalCmt = comments.sum()
        val hasComments = comments.size == n && totalCmt > 0L
        val commentPerFollow = if (hasComments) (totalCmt.toDouble() / n.toDouble()) / followedSafe else 0.0
        val sInter = if (hasComments) {
            val sCmt = logScore(commentPerFollow, 0.0002, 0.003)
            0.7 * sDen + 0.3 * sCmt
        } else {
            sDen
        }

        val official = input.officialRating?.takeIf { !it.isNaN() && it in 1.0f..10.0f }
        val platform = input.platformScore?.takeIf { !it.isNaN() && it in 1.0f..10.0f }
        val sOff = official?.let { linScore(it.toDouble(), 7.0, 9.5) }
        val sPlat = if (official == null) platform?.let { linScore(it.toDouble(), 7.6, 8.6) } else null

        var qWeighted = 0.45 * sRet + 0.15 * sInter
        var qWeightSum = 0.45 + 0.15
        if (sOff != null) {
            qWeighted += 0.25 * sOff
            qWeightSum += 0.25
        } else if (sPlat != null) {
            qWeighted += 0.15 * sPlat
            qWeightSum += 0.15
        }
        val qualityScore = qWeighted / qWeightSum

        val f = if (ageDaysRaw != null && ageDaysRaw < 30.0) {
            (30.0 / ageDaysRaw).pow(0.6)
        } else {
            1.0
        }
        val sHeatAvg = logScore(avgDigg * f, 1500.0, 35000.0)
        val sHeatFav = logScore(input.followedCount.coerceAtLeast(0L).toDouble() * f, 30000.0, 1500000.0)
        val sHeatPlay = logScore(input.seriesPlayCount.coerceAtLeast(0L).toDouble(), 100000.0, 30000000.0)
        val heatScore = 0.4 * sHeatAvg + 0.4 * sHeatFav + 0.2 * sHeatPlay

        val raw = 0.75 * qualityScore + 0.25 * heatScore

        val cDigg = linScore(log10(totalDigg.toDouble() + 1.0), log10(2000.0), log10(300000.0)) / 100.0
        val cAge = ageDaysRaw?.let { (it / 7.0).coerceIn(0.3, 1.0) } ?: 1.0
        val confidence = cDigg * cAge
        val isRecent = ageDaysRaw != null && ageDaysRaw <= 30.0
        var finalScore = if (isRecent) {
            confidence * raw + (1.0 - confidence) * 60.0
        } else {
            raw
        }
        val isPending = isRecent && confidence < 0.45

        if (official != null) {
            if (official >= 9.3f && rMid >= 0.22) {
                finalScore = maxOf(finalScore, 65.0)
            }
            if (official <= 7.5f) {
                finalScore = minOf(finalScore, 64.9)
            }
        }

        val tailCollapsed = (rEnd < 0.18 || rMid < 0.22) && cDigg >= 0.6 && input.isCompleted != false
        if (tailCollapsed && finalScore >= 80.0) {
            finalScore = 79.0
        }

        val grade = when {
            finalScore >= 82.0 -> "S"
            finalScore >= 65.0 -> "A"
            finalScore >= 46.0 -> "B"
            else -> "C"
        }

        val isDarkHorse = input.followedCount < 300_000L && rMid >= 0.45 && (
            input.seriesPlayCount >= 3_000_000L ||
                (ageDaysRaw != null && ageDaysRaw <= 14.0 && input.followedCount / ageDaysRaw >= 20_000.0)
            )

        val ageDaysInt = ageDaysRaw?.roundToInt() ?: -1
        val verdict = when {
            isPending -> "上线${ageDaysInt}天，数据积累中，评分仅供参考"
            tailCollapsed -> "前段吸引力强，但后段大量弃剧，谨慎入坑"
            rEnd >= rMid * 1.1 && rEnd >= 0.40 -> "中段稳定、结局回升，适合一口气追完"
            cliffDrop >= 0.5 && cliffEpisode > 0 -> "第${cliffEpisode}集附近明显劝退，前面好看后面一般"
            rMid >= 0.45 -> "留存优秀，追剧黏性强"
            rMid < 0.18 -> "前几集强钩子，后续留存偏弱"
            else -> "表现中规中矩"
        }

        return DramaQualityReport(
            score = ((finalScore * 10.0).roundToInt() / 10.0f),
            grade = grade,
            isDarkHorse = isDarkHorse,
            episodeCount = n,
            followedCount = input.followedCount,
            seriesPlayCount = input.seriesPlayCount,
            totalDiggCount = totalDigg,
            avgEpisodeDigg = avgDigg.roundToInt().toLong(),
            storyRetention = rMid.toFloat(),
            diggPerFollow = density.toFloat(),
            ep1Digg = ep1,
            ep2Digg = ep2,
            midTailDigg = midTail.roundToInt().toLong(),
            tailCollapsed = tailCollapsed,
            qualityScore = ((qualityScore * 10.0).roundToInt() / 10.0f),
            heatScore = ((heatScore * 10.0).roundToInt() / 10.0f),
            confidence = confidence.toFloat(),
            isPending = isPending,
            ageDays = ageDaysInt,
            createTimeSec = input.createTimeSec,
            isCompleted = input.isCompleted,
            endRetention = rEnd.toFloat(),
            cliffDrop = cliffDrop.toFloat(),
            cliffEpisode = cliffEpisode,
            stability = sStab.toFloat(),
            baseDigg = base.roundToInt().toLong(),
            commentPerFollow = commentPerFollow.toFloat(),
            officialRating = official,
            platformScore = platform,
            sRetMid = sMid.toFloat(),
            sRetEnd = sEnd.toFloat(),
            sCliff = sCliff.toFloat(),
            sInter = sInter.toFloat(),
            sHeatAvg = sHeatAvg.toFloat(),
            sHeatFav = sHeatFav.toFloat(),
            sHeatPlay = sHeatPlay.toFloat(),
            verdict = verdict
        )
    }

    fun evaluate(
        episodeDiggs: List<Long>,
        followedCount: Long,
        seriesPlayCount: Long
    ): DramaQualityReport? = evaluate(
        DramaQualityInput(
            episodeDiggs = episodeDiggs,
            followedCount = followedCount,
            seriesPlayCount = seriesPlayCount
        )
    )

    fun formatCompactCount(count: Long): String {
        if (count <= 0L) return "0"
        return when {
            count < 10_000L -> count.toString()
            count < 100_000_000L -> String.format(Locale.US, "%.1f万", count / 10_000.0)
            else -> String.format(Locale.US, "%.2f亿", count / 100_000_000.0)
        }
    }
}
