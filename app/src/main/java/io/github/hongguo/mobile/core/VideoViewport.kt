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

enum class FullscreenButtonAnchor {
    BELOW_VIDEO,
    ABOVE_VIDEO,
    INSIDE_VIDEO_TOP_END
}

data class FullscreenButtonPlacement(
    val anchor: FullscreenButtonAnchor,
    val topOffsetDp: Float
)

fun computeFullscreenButtonPlacement(
    containerHDp: Float,
    videoRenderedHDp: Float,
    topBarClearanceDp: Float,
    bottomOverlayClearanceDp: Float,
    buttonHeightDp: Float = 36f,
    gapDp: Float = 12f
): FullscreenButtonPlacement {
    val safeContainerH = containerHDp.coerceAtLeast(1f)
    val safeVideoH = videoRenderedHDp.coerceIn(0f, safeContainerH)
    val videoTop = (safeContainerH - safeVideoH) / 2f
    val videoBottom = (safeContainerH + safeVideoH) / 2f
    val maxBottom = (safeContainerH - bottomOverlayClearanceDp).coerceAtLeast(0f)

    val belowTop = videoBottom + gapDp
    if (belowTop + buttonHeightDp <= maxBottom) {
        return FullscreenButtonPlacement(FullscreenButtonAnchor.BELOW_VIDEO, belowTop)
    }

    val aboveTop = videoTop - gapDp - buttonHeightDp
    if (aboveTop >= topBarClearanceDp) {
        return FullscreenButtonPlacement(FullscreenButtonAnchor.ABOVE_VIDEO, aboveTop)
    }

    val insideTop = maxOf(videoTop + 10f, topBarClearanceDp)
    return FullscreenButtonPlacement(FullscreenButtonAnchor.INSIDE_VIDEO_TOP_END, insideTop)
}
