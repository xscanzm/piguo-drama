// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hongguo.mobile.BuildConfig
import io.github.hongguo.mobile.core.AppUpdateInfo
import io.github.hongguo.mobile.core.LATEST_RELEASE_PAGE_URL
import io.github.hongguo.mobile.core.UpdateStatus
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import java.util.Locale

internal fun formatTransferSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        " · " + String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    } else {
        " · ${kb.toInt().coerceAtLeast(1)} KB/s"
    }
}

internal fun formatDownloadProgressText(status: UpdateStatus.Downloading): String {
    val downloaded = status.downloadedBytes
    if (status.connecting || downloaded <= 0L) {
        return status.channelLabel
    }
    val downloadedMb = String.format(Locale.US, "%.2f", downloaded / (1024.0 * 1024.0))
    val speedSuffix = formatTransferSpeed(status.speedBytesPerSec)
    val total = status.totalBytes
    return if (total > 0L) {
        val totalMb = String.format(Locale.US, "%.2f", total / (1024.0 * 1024.0))
        val pct = ((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) * 100f).toInt()
        "${status.channelLabel} · $downloadedMb / $totalMb MB ($pct%)$speedSuffix"
    } else {
        "${status.channelLabel} · 已下载 $downloadedMb MB$speedSuffix"
    }
}

@Composable
fun AppUpdateDialog(
    status: UpdateStatus,
    onDismiss: (skipInSession: Boolean) -> Unit,
    onStartDownload: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onTriggerInstall: (UpdateStatus.ReadyToInstall) -> Unit,
    onOpenBrowser: (String) -> Unit
) {
    val info: AppUpdateInfo? = when (status) {
        is UpdateStatus.Available -> status.info
        is UpdateStatus.Downloading -> status.info
        is UpdateStatus.ReadyToInstall -> status.info
        is UpdateStatus.Error -> status.info
        else -> null
    }
    if (info == null) return

    val isDownloading = status is UpdateStatus.Downloading
    val sizeText = if (info.assetSizeBytes > 0L) {
        "约 " + String.format(Locale.US, "%.2f MB", info.assetSizeBytes / (1024.0 * 1024.0))
    } else ""

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss(true) },
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "发现新版本 v${info.versionName}",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "当前 v${BuildConfig.VERSION_NAME} → v${info.versionName}",
                        color = CoralRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CoralRed.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (sizeText.isNotEmpty()) {
                        Text(text = sizeText, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val notes = info.releaseNotes.ifBlank { "包含体验优化与问题修复。" }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceElevated)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = notes,
                        color = TextPrimary.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }

                when (status) {
                    is UpdateStatus.Downloading -> {
                        val total = status.totalBytes
                        val downloaded = status.downloadedBytes
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (status.connecting || downloaded <= 0L || total <= 0L) {
                                LinearProgressIndicator(
                                    color = CoralRed,
                                    trackColor = SurfaceElevated,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                val pct = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { pct },
                                    color = CoralRed,
                                    trackColor = SurfaceElevated,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                text = formatDownloadProgressText(status),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    is UpdateStatus.ReadyToInstall -> {
                        Text(
                            text = status.hint ?: "安装包已下载完成，点击「立即安装」即可覆盖升级。",
                            color = if (status.hint != null) Color(0xFFFFD54F) else Color(0xFF7AD97A),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }

                    is UpdateStatus.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = status.message,
                                color = CoralRed,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onOpenBrowser(info.htmlUrl.ifBlank { LATEST_RELEASE_PAGE_URL }) }
                                ) {
                                    Text("浏览器备用下载", color = Color(0xFF8EC5FF), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (status) {
                is UpdateStatus.Downloading -> {
                    Button(
                        onClick = { onDismiss(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                    ) {
                        Text("后台下载", color = Color(0xFF8EC5FF), fontWeight = FontWeight.Medium)
                    }
                }

                is UpdateStatus.ReadyToInstall -> {
                    Button(
                        onClick = { onTriggerInstall(status) },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                    ) {
                        Text("立即安装", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                is UpdateStatus.Error -> {
                    Button(
                        onClick = { onStartDownload(info) },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                    ) {
                        Text("重试下载", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                else -> {
                    Button(
                        onClick = { onStartDownload(info) },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                    ) {
                        Text("立即下载更新", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = {
            if (isDownloading) {
                TextButton(onClick = onCancelDownload) {
                    Text("取消下载", color = TextSecondary)
                }
            } else {
                TextButton(onClick = { onDismiss(true) }) {
                    Text("稍后再说", color = TextSecondary)
                }
            }
        }
    )
}
