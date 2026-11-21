// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hongguo.mobile.core.DramaQualityEvaluator.formatCompactCount
import io.github.hongguo.mobile.core.DramaQualityReport
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt

private val PendingGreen = Color(0xFF4ADE80)
private val TierGreen = Color(0xFF4ADE80)
private val TierAmber = Color(0xFFFFB800)
private val TierRed = Color(0xFFFF6B6B)

private fun gradeAccentColor(grade: String): Color = when (grade) {
    "S" -> Color(0xFFFFB800)
    "A" -> CoralRed
    "B" -> Color(0xFF2F80ED)
    else -> Color(0xFF9AA0A6)
}

private fun tierColor(subScore: Float): Color = when {
    subScore >= 67f -> TierGreen
    subScore >= 34f -> TierAmber
    else -> TierRed
}

private fun formatLaunchDate(createTimeSec: Long): String {
    if (createTimeSec <= 0L) return ""
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
    return fmt.format(Date(createTimeSec * 1000L))
}

@Composable
fun DramaQualityBadge(
    report: DramaQualityReport,
    onClick: () -> Unit,
    prominent: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (report.isPending) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PendingGreen.copy(alpha = 0.18f))
                .border(0.8.dp, PendingGreen.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = if (prominent) 3.dp else 2.dp)
        ) {
            Text(
                text = "新剧 · 待定",
                color = PendingGreen,
                fontSize = if (prominent) 12.sp else 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    if (prominent) {
        val bgModifier = when (report.grade) {
            "S" -> Modifier
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFFFFC933), Color(0xFFFF7A00)))
                )
                .border(1.dp, Color(0xFFFFE58F), RoundedCornerShape(8.dp))
            "A" -> Modifier
                .background(CoralRed)
                .border(1.dp, Color(0xFFFF8CA0), RoundedCornerShape(8.dp))
            "B" -> Modifier
                .background(Color(0xFF2F80ED))
                .border(1.dp, Color(0xFF7AB3FF), RoundedCornerShape(8.dp))
            else -> Modifier
                .background(Color(0xFF4A4F5A))
                .border(1.dp, Color(0xFF747B89), RoundedCornerShape(8.dp))
        }
        val textColor = when (report.grade) {
            "S" -> Color(0xFF1A1000)
            "A", "B" -> Color.White
            else -> Color(0xFFE0E3EA)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .then(bgModifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(
                text = report.grade,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${report.score}分",
                color = textColor.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        val accent = gradeAccentColor(report.grade)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.22f))
                .border(0.8.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${report.grade} · ${report.score}分",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DarkHorseBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PendingGreen.copy(alpha = 0.2f))
            .border(0.8.dp, PendingGreen.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "黑马",
            color = PendingGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CategoryTagChips(
    tags: List<String>,
    gender: Int = -1,
    isNew: Boolean = false,
    small: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shown = tags.take(2)
    if (!isNew && shown.isEmpty()) return

    val hPad = if (small) 5.dp else 6.dp
    val vPad = if (small) 1.dp else 2.dp
    val textSize = if (small) 9.sp else 11.sp
    val chipBg = when (gender) {
        0 -> Color(0xFFFF7EB3).copy(alpha = 0.30f)
        1 -> Color(0xFF5AA9FF).copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.16f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
    ) {
        if (isNew) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(PendingGreen.copy(alpha = 0.25f))
                    .border(0.6.dp, PendingGreen.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .padding(horizontal = hPad, vertical = vPad)
            ) {
                Text(
                    text = "新剧",
                    color = PendingGreen,
                    fontSize = textSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        for (tag in shown) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(chipBg)
                    .padding(horizontal = hPad, vertical = vPad)
            ) {
                Text(
                    text = tag,
                    color = Color.White,
                    fontSize = textSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DramaQualitySheet(
    title: String,
    report: DramaQualityReport,
    currentEpisodeIndex: Int? = null,
    currentEpisodeDigg: Long? = null,
    episodeDiggs: List<Long> = emptyList(),
    tags: List<String> = emptyList(),
    episodeComments: List<Long> = emptyList(),
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = if (report.isPending) PendingGreen else gradeAccentColor(report.grade)

    fun logRatio(x: Double, lo: Double, hi: Double): Float {
        if (x <= lo) return 0f
        if (x >= hi) return 1f
        return ((log10(x) - log10(lo)) / (log10(hi) - log10(lo))).toFloat().coerceIn(0f, 1f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            // 1. Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    val statusPart = when {
                        report.isPending -> "上线${maxOf(report.ageDays, 1)}天 · 数据积累中"
                        report.isCompleted == true -> "已完结 · 全${report.episodeCount}集"
                        report.isCompleted == false -> "连载中 · 已更${report.episodeCount}集"
                        else -> "全剧 ${report.episodeCount} 集"
                    }
                    val launchDate = formatLaunchDate(report.createTimeSec)
                    val subtitle = if (launchDate.isNotEmpty()) "$statusPart · $launchDate 上线" else statusPart
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .border(1.dp, accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (report.isPending) "待定 · ${report.score}" else "${report.grade} 级 · ${report.score} / 100",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Tags row
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (t in tags.take(6)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceElevated)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = t,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 3. Verdict banner
            val bannerColor = when {
                report.tailCollapsed -> Color(0xFFFF8E8E)
                report.isDarkHorse -> PendingGreen
                else -> accent
            }
            val bannerText = buildString {
                append(report.verdict.ifEmpty { "综合口碑评估" })
                if (report.tailCollapsed) {
                    append("（尾段留存过低，已封顶 79 分，不进入 S 级）")
                } else if (report.isDarkHorse) {
                    append("（黑马剧：高留存起量迅速）")
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bannerColor.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(bannerText, color = bannerColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // 4. Score summary cards
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ScoreSummaryCard(
                    label = "口碑",
                    value = "${report.qualityScore}",
                    accent = accent,
                    modifier = Modifier.weight(1f)
                )
                ScoreSummaryCard(
                    label = "热度",
                    value = "${report.heatScore}",
                    accent = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (report.officialRating != null) {
                    ScoreSummaryCard(
                        label = "红果评分",
                        value = "%.1f".format(Locale.US, report.officialRating),
                        accent = Color(0xFFFFB800),
                        modifier = Modifier.weight(1f)
                    )
                } else if (report.platformScore != null) {
                    ScoreSummaryCard(
                        label = "平台参考",
                        value = "%.1f".format(Locale.US, report.platformScore),
                        accent = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 5. Current episode digg
            if (currentEpisodeDigg != null && currentEpisodeDigg > 0L) {
                val epLabel = if (currentEpisodeIndex != null && currentEpisodeIndex > 0) {
                    "当前第 $currentEpisodeIndex 集点赞数：${formatCompactCount(currentEpisodeDigg)}"
                } else {
                    "当前分集点赞数：${formatCompactCount(currentEpisodeDigg)}"
                }
                Text(
                    text = epLabel,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 6. Sparkline
            if (episodeDiggs.size >= 5 && episodeDiggs.any { it > 0L }) {
                EpisodeDiggSparklineCard(
                    diggs = episodeDiggs,
                    currentEpisodeIndex = currentEpisodeIndex,
                    accent = accent,
                    baseDigg = report.baseDigg,
                    cliffEpisode = report.cliffEpisode,
                    cliffDrop = report.cliffDrop,
                    comments = episodeComments
                )
            }

            // 7. Metrics breakdown (no weight percentages, 3-tier bar colors)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .padding(14.dp)
            ) {
                val baseVal = when {
                    report.baseDigg > 0L -> report.baseDigg
                    report.ep2Digg > 0L -> report.ep2Digg
                    else -> report.ep1Digg
                }
                QualityMetricRow(
                    label = "中段留存",
                    valueText = "${(report.storyRetention * 100f).roundToInt()}%（中段 ${formatCompactCount(report.midTailDigg)} ÷ 基准 ${formatCompactCount(baseVal)}）",
                    progress = (report.sRetMid / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sRetMid)
                )
                QualityMetricRow(
                    label = "结局留存",
                    valueText = "${(report.endRetention * 100f).roundToInt()}%",
                    progress = (report.sRetEnd / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sRetEnd)
                )
                val cliffText = if (report.cliffEpisode > 0 && report.cliffDrop >= 0.05f) {
                    "${(report.cliffDrop * 100f).roundToInt()}% (第${report.cliffEpisode}集)"
                } else {
                    "${(report.cliffDrop * 100f).roundToInt()}%"
                }
                QualityMetricRow(
                    label = "断崖跌幅",
                    valueText = cliffText,
                    progress = (report.sCliff / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sCliff)
                )
                QualityMetricRow(
                    label = "互动密度",
                    valueText = "集均赞≈追剧的 ${"%.1f".format(Locale.US, report.diggPerFollow * 100f)}%",
                    progress = (report.sInter / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sInter)
                )
                if (report.commentPerFollow > 0f) {
                    val cmtProg = logRatio(report.commentPerFollow.toDouble(), 0.0002, 0.003)
                    QualityMetricRow(
                        label = "评论热度",
                        valueText = "${"%.2f".format(Locale.US, report.commentPerFollow * 1000f)}‰",
                        progress = cmtProg,
                        barColor = tierColor(cmtProg * 100f)
                    )
                }
                QualityMetricRow(
                    label = "集均点赞",
                    valueText = "${formatCompactCount(report.avgEpisodeDigg)} / 集（总赞 ${formatCompactCount(report.totalDiggCount)}）",
                    progress = (report.sHeatAvg / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sHeatAvg)
                )
                QualityMetricRow(
                    label = "追剧人数",
                    valueText = "${formatCompactCount(report.followedCount)} 人",
                    progress = (report.sHeatFav / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sHeatFav)
                )
                QualityMetricRow(
                    label = "近期播放",
                    valueText = "${formatCompactCount(report.seriesPlayCount)}（近期窗口）",
                    progress = (report.sHeatPlay / 100f).coerceIn(0f, 1f),
                    barColor = tierColor(report.sHeatPlay)
                )
            }

            // 8. Footer legend
            Text(
                text = "评级：S ≥ 82 · A ≥ 65 · B ≥ 46 · C < 46 ｜ 口碑 75% + 热度 25%",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ScoreSummaryCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(text = value, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EpisodeDiggSparklineCard(
    diggs: List<Long>,
    currentEpisodeIndex: Int?,
    accent: Color,
    baseDigg: Long = 0L,
    cliffEpisode: Int = -1,
    cliffDrop: Float = 0f,
    comments: List<Long> = emptyList()
) {
    val peakSlice = if (diggs.size >= 4) diggs.drop(2) else diggs
    val peakOffset = if (diggs.size >= 4) 2 else 0
    val peakRelIdx = peakSlice.indices.maxByOrNull { peakSlice[it] } ?: 0
    val peakEpNum = peakOffset + peakRelIdx + 1
    val peakDigg = diggs.getOrElse(peakEpNum - 1) { 0L }

    // Anchor Y max on ep2..N so massive ep1 passive feed exposure does not flatten later episodes
    val bodyMax = (diggs.drop(1).maxOrNull() ?: diggs.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val scaleMax = bodyMax.toFloat() * 1.15f
    val refVal = when {
        baseDigg > 0L -> baseDigg
        diggs.size > 1 && diggs[1] > 0L -> diggs[1]
        else -> diggs[0]
    }
    val hasComments = comments.size == diggs.size && comments.any { it > 0L }
    val cmtScaleMax = if (hasComments) {
        (comments.drop(1).maxOrNull() ?: comments.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat() * 1.15f
    } else {
        1f
    }
    val showCliff = cliffDrop >= 0.35f && cliffEpisode in 1..diggs.size

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("逐集点赞走势", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val rightText = buildString {
                append("高能峰值：第${peakEpNum}集 (${formatCompactCount(peakDigg)})")
                if (showCliff) {
                    append(" · 断崖：第${cliffEpisode}集")
                }
            }
            Text(
                text = rightText,
                color = if (showCliff) TierRed else accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            val w = size.width
            val h = size.height
            val padTop = 6.dp.toPx()
            val padBottom = 6.dp.toPx()
            val drawH = (h - padTop - padBottom).coerceAtLeast(1f)
            val n = diggs.size

            fun xFor(i: Int): Float = if (n <= 1) 0f else (i.toFloat() / (n - 1).toFloat()) * w
            fun yFor(v: Long): Float {
                val frac = (v.coerceAtLeast(0L).toFloat() / scaleMax).coerceIn(0f, 1f)
                return padTop + drawH * (1f - frac)
            }
            fun yForComment(v: Long): Float {
                val frac = (v.coerceAtLeast(0L).toFloat() / cmtScaleMax).coerceIn(0f, 1f)
                return padTop + drawH * (1f - frac)
            }

            // Ending segment highlight (last 12% of episodes)
            val endCount = maxOf(3, ceil(0.12 * n).toInt()).coerceAtMost(n)
            val endStartIdx = (n - endCount).coerceAtLeast(0)
            val endX = xFor(endStartIdx)
            drawRect(
                color = Color.White.copy(alpha = 0.05f),
                topLeft = Offset(endX, 0f),
                size = Size((w - endX).coerceAtLeast(0f), h)
            )

            // Reference dashed line at ep2-4 median baseline
            val refY = yFor(refVal)
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(0f, refY),
                end = Offset(w, refY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )

            // Cliff vertical dashed marker
            if (showCliff) {
                val cx = xFor(cliffEpisode - 1)
                drawLine(
                    color = TierRed.copy(alpha = 0.8f),
                    start = Offset(cx, padTop),
                    end = Offset(cx, h - padBottom),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            }

            val strokePath = Path()
            val fillPath = Path()
            diggs.forEachIndexed { idx, v ->
                val px = xFor(idx)
                val py = yFor(v)
                if (idx == 0) {
                    strokePath.moveTo(px, py)
                    fillPath.moveTo(px, h - padBottom)
                    fillPath.lineTo(px, py)
                } else {
                    strokePath.lineTo(px, py)
                    fillPath.lineTo(px, py)
                }
            }
            fillPath.lineTo(xFor(n - 1), h - padBottom)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.28f), Color.Transparent)
                )
            )
            drawPath(
                path = strokePath,
                color = accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            if (hasComments) {
                val cmtPath = Path()
                comments.forEachIndexed { idx, v ->
                    val px = xFor(idx)
                    val py = yForComment(v)
                    if (idx == 0) cmtPath.moveTo(px, py) else cmtPath.lineTo(px, py)
                }
                drawPath(
                    path = cmtPath,
                    color = Color.White.copy(alpha = 0.45f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            if (currentEpisodeIndex != null && currentEpisodeIndex in 1..n) {
                val ci = currentEpisodeIndex - 1
                val cx = xFor(ci)
                val cy = yFor(diggs[ci])
                drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = accent, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text("第1集", color = TextSecondary, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (hasComments) "虚线 = 第2-4集基准 · 白线 = 评论" else "虚线 = 第2-4集基准",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text("第${diggs.size}集", color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun QualityMetricRow(
    label: String,
    valueText: String,
    progress: Float,
    barColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        LinearProgressIndicator(
            progress = { progress },
            color = barColor,
            trackColor = Color.White.copy(alpha = 0.1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}
