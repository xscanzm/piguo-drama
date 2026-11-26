// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

private val CJK_REGEX = Regex("[\\u4E00-\\u9FFF]")
private val TECHNICAL_NOISE_REGEX = Regex(
    "(Exception:|Error:|Unable to resolve|Failed to connect|Software caused connection|CLEARTEXT|java\\.|javax\\.|okhttp3\\.)",
    RegexOption.IGNORE_CASE
)

fun friendlyErrorMessage(
    error: Throwable?,
    fallback: String = "加载失败，请稍后重试"
): String {
    if (error == null) return fallback

    val visited = HashSet<Throwable>()
    var cur: Throwable? = error
    while (cur != null && visited.add(cur)) {
        when (cur) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException -> return "网络连接失败，请检查网络后重试"
            is SocketTimeoutException,
            is InterruptedIOException -> return "网络请求超时，请检查网络或点击重试"
            is SSLException -> return "安全连接异常，请检查网络环境或系统时间"
        }
        cur = cur.cause
    }

    val rawMsg = error.message?.trim().orEmpty()
    if (rawMsg.isNotEmpty() &&
        rawMsg.length <= 80 &&
        CJK_REGEX.containsMatchIn(rawMsg) &&
        !TECHNICAL_NOISE_REGEX.containsMatchIn(rawMsg)
    ) {
        return rawMsg
    }
    return fallback
}

fun friendlyPlaybackError(errorCode: Int, errorCodeName: String): String {
    val name = errorCodeName.uppercase(Locale.US)
    return when {
        name.contains("IO_NETWORK_CONNECTION_FAILED") ||
            name.contains("IO_NETWORK_CONNECTION_TIMEOUT") ||
            errorCode == 2001 ||
            errorCode == 2002 -> "网络连接中断或超时，请点击重试"

        name.contains("IO_BAD_HTTP_STATUS") ||
            name.contains("IO_FILE_NOT_FOUND") ||
            errorCode == 2004 ||
            errorCode == 2005 -> "片源地址已失效或暂不可用，请点击重试刷新"

        name.contains("DECODER_INIT_FAILED") ||
            name.contains("DECODER_QUERY_FAILED") ||
            name.contains("DECODING_FAILED") ||
            name.contains("DECODING_FORMAT_UNSUPPORTED") ||
            errorCode in 4001..4005 -> "当前设备解码该画质异常，可尝试切换 720P 或 480P"

        else -> "视频播放异常，请点击重试 ($errorCodeName)"
    }
}

fun formatRelativeWatchTime(
    updatedAtMs: Long,
    nowMs: Long = System.currentTimeMillis()
): String {
    if (updatedAtMs <= 0L) return ""
    val diffMs = (nowMs - updatedAtMs).coerceAtLeast(0L)
    val sec = diffMs / 1000L
    if (sec < 60L) return "刚刚"
    val min = sec / 60L
    if (min < 60L) return "${min}分钟前"
    val hour = min / 60L
    if (hour < 24L) return "${hour}小时前"
    val day = hour / 24L
    if (day == 1L) return "昨天"
    if (day < 7L) return "${day}天前"
    return SimpleDateFormat("MM-dd", Locale.US).format(Date(updatedAtMs))
}
