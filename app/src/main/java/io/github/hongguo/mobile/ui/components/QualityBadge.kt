// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlin.math.log10
import kotlin.math.roundToInt

private fun gradeAccentColor(grade: String): Color = when (grade) {
    "S" -> Color(0xFFFFB800)
    "A" -> CoralRed
    "B" -> Color(0xFF2F80ED)
    else -> Color(0xFF9AA0A6)
}

@Composable
fun DramaQualityBadge(
    report: DramaQualityReport,
    onClick: () -> Unit,
    prominent: Boolean = true,
    modifier: Modifier = Modifier
) {
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
            .background(Color(0xFF4ADE80).copy(alpha = 0.2f))
            .border(0.8.dp, Color(0xFF4ADE80).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "黑马",
            color = Color(0xFF4ADE80),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DramaQualitySheet(
    title: String,
    report: DramaQualityReport,
    currentEpisodeIndex: Int? = null,
    currentEpisodeDigg: Long? = null,
    episodeDiggs: List<Long> = emptyList(),
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent = gradeAccentColor(report.grade)

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
                    Text(
                        text = "全剧 ${report.episodeCount} 集综合口碑评估",
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
                        text = "${report.grade} 级 · ${report.score} / 100",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (report.isDarkHorse || report.tailCollapsed) {
                val bannerText = when {
                    report.tailCollapsed -> "尾段留存低于 20%，已触发防烂尾熔断（封顶 79 分，不进入 S 级）"
                    else -> "黑马潜力剧：前期热度增长迅猛且正片留存 ≥ 45%"
                }
                val bannerColor = if (report.tailCollapsed) Color(0xFFFF8E8E) else Color(0xFF4ADE80)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bannerColor.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(bannerText, color = bannerColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

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

            if (episodeDiggs.size >= 5 && episodeDiggs.any { it > 0L }) {
                EpisodeDiggSparklineCard(
                    diggs = episodeDiggs,
                    currentEpisodeIndex = currentEpisodeIndex,
                    accent = accent
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .padding(14.dp)
            ) {
                QualityMetricRow(
                    label = "集均点赞 (32%)",
                    valueText = "${formatCompactCount(report.avgEpisodeDigg)} / 集（总赞 ${formatCompactCount(report.totalDiggCount)}）",
                    progress = logRatio(report.avgEpisodeDigg.toDouble(), 1500.0, 35000.0),
                    barColor = accent
                )
                QualityMetricRow(
                    label = "累计收藏 (28%)",
                    valueText = "${formatCompactCount(report.followedCount)} 人追剧",
                    progress = logRatio(report.followedCount.toDouble(), 30000.0, 1500000.0),
                    barColor = accent
                )
                val baseEp = if (report.ep2Digg > 0L) report.ep2Digg else report.ep1Digg
                QualityMetricRow(
                    label = "正片留存 (25%)",
                    valueText = "${(report.storyRetention * 100f).roundToInt()}%（中后段 ${formatCompactCount(report.midTailDigg)} ÷ 第2集 ${formatCompactCount(baseEp)}）",
                    progress = ((report.storyRetention - 0.15f) / (0.60f - 0.15f)).coerceIn(0f, 1f),
                    barColor = accent
                )
                QualityMetricRow(
                    label = "互动密度 (10%)",
                    valueText = "${"%.2f".format(report.diggPerFollow)} 赞 / 收藏",
                    progress = ((report.diggPerFollow - 1.2f) / (3.2f - 1.2f)).coerceIn(0f, 1f),
                    barColor = accent
                )
                QualityMetricRow(
                    label = "近期活跃 (5%)",
                    valueText = "${formatCompactCount(report.seriesPlayCount)} 播放活跃度",
                    progress = logRatio(report.seriesPlayCount.toDouble(), 100000.0, 30000000.0),
                    barColor = accent
                )
            }

            Text(
                text = "评级标准：S ≥ 82 · A ≥ 65 · B ≥ 46 · C < 46",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun EpisodeDiggSparklineCard(
    diggs: List<Long>,
    currentEpisodeIndex: Int?,
    accent: Color
) {
    val peakSlice = if (diggs.size >= 4) diggs.drop(2) else diggs
    val peakOffset = if (diggs.size >= 4) 2 else 0
    val peakRelIdx = peakSlice.indices.maxByOrNull { peakSlice[it] } ?: 0
    val peakEpNum = peakOffset + peakRelIdx + 1
    val peakDigg = diggs.getOrElse(peakEpNum - 1) { 0L }

    // Anchor Y max on ep2..N so massive ep1 passive feed exposure does not flatten later episodes
    val bodyMax = (diggs.drop(1).maxOrNull() ?: diggs.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val scaleMax = bodyMax.toFloat() * 1.15f
    val baseEp2 = (if (diggs.size > 1 && diggs[1] > 0L) diggs[1] else diggs[0]).toFloat()

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
            Text(
                text = "高能峰值：第${peakEpNum}集 (${formatCompactCount(peakDigg)})",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
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

            // Reference dashed line at ep2 level
            val refY = yFor(baseEp2.toLong())
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(0f, refY),
                end = Offset(w, refY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )

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
            Text("虚线 = 第2集正片基准", color = TextSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
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
