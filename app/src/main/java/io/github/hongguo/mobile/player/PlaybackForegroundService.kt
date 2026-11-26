// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import io.github.hongguo.mobile.HongguoApp
import io.github.hongguo.mobile.MainActivity
import io.github.hongguo.mobile.R

class PlaybackForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "piguo_background_playback"
        private const val NOTIF_ID = 7001
        private const val ACTION_SYNC = "io.github.hongguo.mobile.service.SYNC"
        private const val ACTION_TOGGLE = "io.github.hongguo.mobile.service.TOGGLE"
        private const val ACTION_NEXT = "io.github.hongguo.mobile.service.NEXT"
        private const val ACTION_STOP = "io.github.hongguo.mobile.service.STOP"

        @Volatile
        private var isRunning = false

        fun sync(context: Context, enabled: Boolean, meta: ActivePlaybackMeta) {
            val shouldRun = enabled && meta.active && meta.title.isNotBlank()
            if (shouldRun) {
                val intent = Intent(context, PlaybackForegroundService::class.java).apply {
                    action = ACTION_SYNC
                }
                runCatching {
                    if (isRunning) {
                        context.startService(intent)
                    } else {
                        context.startForegroundService(intent)
                    }
                    isRunning = true
                }
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            isRunning = false
            runCatching {
                context.stopService(Intent(context, PlaybackForegroundService::class.java))
            }
        }
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val bus = (application as? HongguoApp)?.container?.playbackControlBus
        mediaSession = MediaSession(this, "PiguoPlaybackSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    bus?.emitCommand(PlaybackRemoteCommand.PLAY)
                }

                override fun onPause() {
                    bus?.emitCommand(PlaybackRemoteCommand.PAUSE)
                }

                override fun onSkipToNext() {
                    bus?.emitCommand(PlaybackRemoteCommand.NEXT_EPISODE)
                }

                override fun onStop() {
                    bus?.emitCommand(PlaybackRemoteCommand.STOP)
                    stopForegroundAndSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bus = (application as? HongguoApp)?.container?.playbackControlBus
        when (intent?.action) {
            ACTION_TOGGLE -> bus?.emitCommand(PlaybackRemoteCommand.TOGGLE_PLAY_PAUSE)
            ACTION_NEXT -> bus?.emitCommand(PlaybackRemoteCommand.NEXT_EPISODE)
            ACTION_STOP -> {
                bus?.emitCommand(PlaybackRemoteCommand.STOP)
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        val meta = bus?.meta?.value ?: ActivePlaybackMeta()
        if (!meta.active || meta.title.isBlank()) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        updateMediaSession(meta)
        val notification = buildNotification(meta)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isRunning = true
        }.onFailure {
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        isRunning = false
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "后台听剧播控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于息屏或后台听剧时保持播放与锁屏播控"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun updateMediaSession(meta: ActivePlaybackMeta) {
        val session = mediaSession ?: return
        val subtitle = if (meta.totalEpisodes > 0) {
            "第${meta.episodeIndex}/${meta.totalEpisodes}集"
        } else {
            "第${meta.episodeIndex}集"
        }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .build()
        session.setMetadata(metadata)

        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        if (meta.canSkipNext) {
            actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        val state = if (meta.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val pbState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(pbState)
    }

    private fun serviceActionPendingIntent(actionName: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackForegroundService::class.java).apply {
            action = actionName
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(meta: ActivePlaybackMeta): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val subtitle = if (meta.totalEpisodes > 0) {
            "第${meta.episodeIndex}/${meta.totalEpisodes}集 · 后台听剧中"
        } else {
            "第${meta.episodeIndex}集 · 后台听剧中"
        }

        val toggleIcon = if (meta.isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val toggleLabel = if (meta.isPlaying) "暂停" else "播放"
        val toggleAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, toggleIcon),
            toggleLabel,
            serviceActionPendingIntent(ACTION_TOGGLE, 11)
        ).build()
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_close),
            "关闭",
            serviceActionPendingIntent(ACTION_STOP, 13)
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_drama)
            .setContentTitle(meta.title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(meta.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggleAction)

        val compactIndices = if (meta.canSkipNext) {
            val nextAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif_next),
                "下一集",
                serviceActionPendingIntent(ACTION_NEXT, 12)
            ).build()
            builder.addAction(nextAction)
            intArrayOf(0, 1)
        } else {
            intArrayOf(0)
        }
        builder.addAction(stopAction)

        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(*compactIndices)
        mediaSession?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)
        return builder.build()
    }
}
