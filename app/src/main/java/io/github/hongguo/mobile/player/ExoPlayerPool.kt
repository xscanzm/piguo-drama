// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Reuses up to [maxPoolSize] warm ExoPlayer instances across VerticalPager pages
 * to reduce hardware decoder allocation churn during rapid episode swiping.
 */
@OptIn(UnstableApi::class)
class ExoPlayerPool(private val maxPoolSize: Int = 3) {
    private val available = ArrayDeque<ExoPlayer>()

    fun acquire(context: Context): ExoPlayer {
        val pooled = synchronized(available) { available.removeFirstOrNull() }
        if (pooled != null) return pooled

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                35_000,
                600,
                1_200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    fun release(player: ExoPlayer) {
        val resetOk = runCatching {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.clearVideoSurface()
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.volume = 1f
            player.repeatMode = Player.REPEAT_MODE_OFF
        }.isSuccess

        if (!resetOk) {
            runCatching { player.release() }
            return
        }

        val shouldRelease = synchronized(available) {
            if (available.size < maxPoolSize) {
                available.addLast(player)
                false
            } else {
                true
            }
        }
        if (shouldRelease) {
            runCatching { player.release() }
        }
    }

    fun clear() {
        val toRelease = synchronized(available) {
            available.toList().also { available.clear() }
        }
        if (toRelease.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            toRelease.forEach { runCatching { it.release() } }
        } else {
            Handler(Looper.getMainLooper()).post {
                toRelease.forEach { runCatching { it.release() } }
            }
        }
    }
}
