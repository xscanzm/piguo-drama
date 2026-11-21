// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

class QualityCalibrationProbeTest {

    @Test
    fun printLiveDetailCalibrationTable() {
        val probeDirPath = System.getenv("HG_PROBE_OUT")?.trim().orEmpty()
        val probeDir = if (probeDirPath.isNotEmpty()) File(probeDirPath) else null
        Assume.assumeTrue(
            "Set HG_PROBE_OUT to an existing probe directory to run calibration output",
            probeDir != null && probeDir.isDirectory
        )

        val detailFiles = probeDir!!.listFiles { _, name ->
            name.endsWith("multi_video_detail_v1.json")
        }?.sortedBy { it.name }.orEmpty()
        Assume.assumeTrue("No multi_video_detail_v1.json files found in $probeDirPath", detailFiles.isNotEmpty())

        println("TITLE | GRADE | SCORE | Q | H | rMid | rEnd | cliff | VERDICT")
        for (file in detailFiles) {
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val data = root.optJSONObject("data") ?: continue
            for (sid in data.keys()) {
                val vd = data.optJSONObject(sid)?.optJSONObject("video_data") ?: continue
                val vl = vd.optJSONArray("video_list") ?: continue
                val rows = ArrayList<Triple<Int, Boolean, Pair<Long, Long>>>(vl.length())
                for (i in 0 until vl.length()) {
                    val item = vl.optJSONObject(i) ?: continue
                    val idx = item.optInt("vid_index", i + 1).coerceAtLeast(1)
                    val digg = item.optLong("digged_count", 0L).coerceAtLeast(0L)
                    val cmt = item.optLong("comment_count", 0L).coerceAtLeast(0L)
                    val newly = item.optBoolean("is_newly_update", false)
                    rows.add(Triple(idx, newly, digg to cmt))
                }
                if (rows.isEmpty()) continue
                val sorted = rows.sortedBy { it.first }
                val epLabel = vd.optString("episode_right_text", "").trim()
                val isCompleted = when {
                    epLabel.startsWith("全") -> true
                    epLabel.contains("更新") || epLabel.contains("连载") -> false
                    else -> null
                }
                val report = DramaQualityEvaluator.evaluate(
                    DramaQualityInput(
                        episodeDiggs = sorted.map { it.third.first },
                        episodeComments = sorted.map { it.third.second },
                        followedCount = vd.optLong("followed_cnt", 0L).coerceAtLeast(0L),
                        seriesPlayCount = vd.optLong("series_play_cnt", 0L).coerceAtLeast(0L),
                        createTimeSec = vd.optLong("create_time", 0L).coerceAtLeast(0L),
                        isCompleted = isCompleted,
                        newlyUpdatedCount = sorted.count { it.second },
                        officialRating = null,
                        platformScore = null
                    )
                ) ?: continue

                val title = vd.optString("series_title", sid)
                println(
                    String.format(
                        Locale.US,
                        "%s | %s | %.1f | %.1f | %.1f | %d%% | %d%% | %d%% | %s",
                        title,
                        report.grade,
                        report.score,
                        report.qualityScore,
                        report.heatScore,
                        (report.storyRetention * 100f).roundToInt(),
                        (report.endRetention * 100f).roundToInt(),
                        (report.cliffDrop * 100f).roundToInt(),
                        report.verdict
                    )
                )
            }
        }
    }
}
