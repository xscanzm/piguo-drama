// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

data class VideoViewportSize(
    val widthPx: Float,
    val heightPx: Float
)

fun computeVideoViewportSize(
    containerW: Float,
    containerH: Float,
    videoW: Int,
    videoH: Int,
    pixelRatio: Float = 1f,
    cropToFill: Boolean = false
): VideoViewportSize {
    val safeCW = containerW.coerceAtLeast(1f)
    val safeCH = containerH.coerceAtLeast(1f)
    if (videoW <= 0 || videoH <= 0) {
        return VideoViewportSize(safeCW, safeCH)
    }
    val pr = if (pixelRatio > 0f) pixelRatio else 1f
    val videoAspect = ((videoW * pr) / videoH.toFloat()).coerceAtLeast(0.1f)
    val containerAspect = safeCW / safeCH
    return if (cropToFill) {
        // Equal-ratio center-crop (fills container without stretching)
        if (containerAspect > videoAspect) {
            VideoViewportSize(safeCW, safeCW / videoAspect)
        } else {
            VideoViewportSize(safeCH * videoAspect, safeCH)
        }
    } else {
        // Equal-ratio fit (preserves entire frame with letterbox/pillarbox bars)
        if (containerAspect > videoAspect) {
            VideoViewportSize(safeCH * videoAspect, safeCH)
        } else {
            VideoViewportSize(safeCW, safeCW / videoAspect)
        }
    }
}
