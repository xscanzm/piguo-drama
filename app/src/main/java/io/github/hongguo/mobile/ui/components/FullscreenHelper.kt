// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.hongguo.mobile.R
import io.github.hongguo.mobile.player.PlaybackControlBus

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun Context.buildPipParams(
    videoWidth: Int,
    videoHeight: Int,
    isPlaying: Boolean,
    canSkipNext: Boolean
): PictureInPictureParams {
    val ratio = if (videoWidth > 0 && videoHeight > 0) {
        val raw = (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.45f, 2.35f)
        Rational((raw * 1000f).toInt().coerceAtLeast(450), 1000)
    } else {
        Rational(9, 16)
    }
    val actions = ArrayList<RemoteAction>(2)
    val toggleIntent = PendingIntent.getBroadcast(
        this,
        201,
        Intent(PlaybackControlBus.ACTION_PIP_TOGGLE_PLAY).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val toggleTitle = if (isPlaying) "暂停" else "播放"
    actions.add(
        RemoteAction(
            Icon.createWithResource(this, if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play),
            toggleTitle,
            toggleTitle,
            toggleIntent
        )
    )
    if (canSkipNext) {
        val nextIntent = PendingIntent.getBroadcast(
            this,
            202,
            Intent(PlaybackControlBus.ACTION_PIP_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_notif_next),
                "下一集",
                "下一集",
                nextIntent
            )
        )
    }
    return PictureInPictureParams.Builder()
        .setAspectRatio(ratio)
        .setActions(actions)
        .build()
}

internal fun Context.enterPipMode(
    videoWidth: Int,
    videoHeight: Int,
    isPlaying: Boolean = true,
    canSkipNext: Boolean = false
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val activity = findActivity() ?: return false
    return runCatching {
        activity.enterPictureInPictureMode(
            buildPipParams(videoWidth, videoHeight, isPlaying, canSkipNext)
        )
    }.getOrDefault(false)
}

internal fun Context.updatePipParams(
    videoWidth: Int,
    videoHeight: Int,
    isPlaying: Boolean,
    canSkipNext: Boolean
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val activity = findActivity() ?: return
    if (!activity.isInPictureInPictureMode) return
    runCatching {
        activity.setPictureInPictureParams(
            buildPipParams(videoWidth, videoHeight, isPlaying, canSkipNext)
        )
    }
}

@Composable
fun LandscapeFullscreenEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            controller.show(WindowInsetsCompat.Type.systemBars())
            val attrs = activity.window.attributes
            if (attrs.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = attrs
            }
        }
        onDispose {
            if (enabled) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                controller.show(WindowInsetsCompat.Type.systemBars())
                val attrs = activity.window.attributes
                if (attrs.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    activity.window.attributes = attrs
                }
            }
        }
    }
}
