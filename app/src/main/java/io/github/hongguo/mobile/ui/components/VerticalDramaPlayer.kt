// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.hongguo.mobile.R
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.github.hongguo.mobile.player.ExoPlayerPool
import io.github.hongguo.mobile.player.MediaCacheManager
import io.github.hongguo.mobile.player.ResolvedMediaSource
import io.github.hongguo.mobile.ui.theme.CoralRed
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class FullscreenGestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class)
@Composable
fun VerticalDramaPlayer(
    mediaSource: ResolvedMediaSource?,
    isLoading: Boolean,
    errorMessage: String?,
    coverUrl: String,
    isActive: Boolean,
    initialPositionMs: Long,
    playbackSpeed: Float,
    mediaCache: MediaCacheManager,
    playerPool: ExoPlayerPool,
    fitModeFill: Boolean = true,
    isInPipMode: Boolean = false,
    backgroundAudioPlay: Boolean = false,
    sleepTriggerCount: Int = 0,
    onRetry: () -> Unit,
    onEnded: () -> Unit,
    onProgressUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    onLongPressSpeedChange: (Boolean) -> Unit = {},
    onTapScreen: () -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: (Boolean) -> Unit = {},
    onControlsVisibilityChange: (Boolean) -> Unit = {},
    onVideoAspectKnown: (width: Int, height: Int) -> Unit = { _, _ -> },
    onFlushProgress: () -> Unit = {},
    showSeekBar: Boolean = true,
    showFullscreenHint: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isUserPaused by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isLongPressing by remember { mutableStateOf(false) }
    var isScreenLocked by remember(isFullscreen) { mutableStateOf(false) }
    var lockHintVisible by remember(isFullscreen) { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var firstFrameRendered by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var videoPixelRatio by remember { mutableFloatStateOf(1f) }
    var fallbackIndex by remember(mediaSource) { mutableIntStateOf(0) }
    var gestureHudText by remember { mutableStateOf<String?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var lastHandledSleepTrigger by remember { mutableIntStateOf(sleepTriggerCount) }

    val latestMediaSource by rememberUpdatedState(mediaSource)
    val latestFlushProgress by rememberUpdatedState(onFlushProgress)
    val latestBackgroundAudio by rememberUpdatedState(backgroundAudioPlay)
    val latestInPip by rememberUpdatedState(isInPipMode)
    val latestOnVideoAspect by rememberUpdatedState(onVideoAspectKnown)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnProgressUpdate by rememberUpdatedState(onProgressUpdate)

    LaunchedEffect(lockHintVisible, isScreenLocked) {
        if (lockHintVisible && isScreenLocked) {
            delay(3000L)
            lockHintVisible = false
        }
    }

    val activeSource = remember(mediaSource, fallbackIndex) {
        if (mediaSource == null) null
        else if (fallbackIndex == 0) mediaSource
        else mediaSource.fallbackSources.getOrNull(fallbackIndex - 1)
    }

    val isLandscapeVideo = (videoWidth * videoPixelRatio) > videoHeight && videoHeight > 0

    val exoPlayer = remember(context) { playerPool.acquire(context) }

    LaunchedEffect(sleepTriggerCount) {
        if (sleepTriggerCount > lastHandledSleepTrigger) {
            lastHandledSleepTrigger = sleepTriggerCount
            if (isActive && mediaSource != null) {
                isUserPaused = true
                exoPlayer.playWhenReady = false
                latestFlushProgress()
            }
        }
    }

    LaunchedEffect(activeSource) {
        if (activeSource == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            firstFrameRendered = false
            videoWidth = 0
            videoHeight = 0
            videoPixelRatio = 1f
            playerError = null
            return@LaunchedEffect
        }
        playerError = null
        firstFrameRendered = false
        videoWidth = 0
        videoHeight = 0
        videoPixelRatio = 1f
        isUserPaused = false

        val dataSourceFactory = mediaCache.createPlaybackDataSourceFactory(activeSource)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val item = MediaItem.Builder()
            .setUri(activeSource.uri)
            .apply {
                if (activeSource.cacheKey.isNotEmpty()) {
                    setCustomCacheKey(activeSource.cacheKey)
                }
            }
            .build()
        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(item))
        if (initialPositionMs > 1000L) {
            exoPlayer.seekTo(initialPositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = isActive
    }

    LaunchedEffect(isActive, isUserPaused, activeSource) {
        if (activeSource != null) {
            exoPlayer.playWhenReady = isActive && !isUserPaused
        }
        if (!isActive) {
            isLongPressing = false
            onLongPressSpeedChange(false)
            latestFlushProgress()
        }
    }

    LaunchedEffect(playbackSpeed, isLongPressing) {
        val targetSpeed = if (isLongPressing) 2.0f else playbackSpeed
        exoPlayer.playbackParameters = PlaybackParameters(targetSpeed)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    isUserPaused = true
                }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoWidth = size.width
                    videoHeight = size.height
                    videoPixelRatio = if (size.pixelWidthHeightRatio > 0f) size.pixelWidthHeightRatio else 1f
                    latestOnVideoAspect(size.width, size.height)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    val endedDur = exoPlayer.duration.coerceAtLeast(0L)
                    latestOnProgressUpdate(endedDur, endedDur)
                    latestFlushProgress()
                    latestOnEnded()
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlayerError(error: PlaybackException) {
                val maxFallbacks = latestMediaSource?.fallbackSources?.size ?: 0
                if (fallbackIndex < maxFallbacks) {
                    fallbackIndex += 1
                } else {
                    playerError = "视频解码或播放异常 (${error.errorCodeName})"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            latestFlushProgress()
            playerViewRef?.player = null
            exoPlayer.removeListener(listener)
            playerPool.release(exoPlayer)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    latestFlushProgress()
                }
                Lifecycle.Event.ON_STOP -> {
                    latestFlushProgress()
                    if (!latestBackgroundAudio && !latestInPip) {
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    if (isActive && !isUserPaused && activeSource != null) {
                        exoPlayer.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive, exoPlayer, activeSource) {
        var ticks = 0
        while (isActive && activeSource != null) {
            if (!isScrubbing) {
                currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration
                if (dur > 0L) durationMs = dur
                if (++ticks % 4 == 0 && currentPosMs > 0L) {
                    latestOnProgressUpdate(currentPosMs, durationMs)
                }
            }
            delay(250L)
        }
    }

    val controlsVisibleInFullscreen = !isInPipMode &&
        (!isFullscreen || (!isScreenLocked && (isUserPaused || (!isPlaying && !isBuffering) || isScrubbing)))
    LaunchedEffect(controlsVisibleInFullscreen, isFullscreen) {
        onControlsVisibilityChange(controlsVisibleInFullscreen)
    }

    val desiredResizeMode = if (isLandscapeVideo || isFullscreen || !fitModeFill) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT
    } else {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
    val shouldKeepScreenOn = isActive && (isPlaying || (isBuffering && !isUserPaused)) && playerError == null

    val latestControlsVisible by rememberUpdatedState(controlsVisibleInFullscreen)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestCurrentPosMs by rememberUpdatedState(currentPosMs)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(mediaSource, isActive, isFullscreen, isScreenLocked, isInPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isInPipMode) return@detectTapGestures
                        if (isFullscreen && isScreenLocked) {
                            lockHintVisible = !lockHintVisible
                            return@detectTapGestures
                        }
                        if (mediaSource != null && playerError == null) {
                            isUserPaused = !isUserPaused
                            exoPlayer.playWhenReady = !isUserPaused
                            if (isUserPaused) latestFlushProgress()
                        }
                        onTapScreen()
                    },
                    onLongPress = {
                        if (isFullscreen && isScreenLocked) return@detectTapGestures
                        if (mediaSource != null && isActive) {
                            isLongPressing = true
                            onLongPressSpeedChange(true)
                        }
                    },
                    onPress = {
                        try {
                            awaitRelease()
                        } finally {
                            if (isLongPressing) {
                                isLongPressing = false
                                onLongPressSpeedChange(false)
                            }
                        }
                    }
                )
            }
    ) {
        val containerW = maxWidth
        val containerH = maxHeight
        val density = LocalDensity.current
        val boxWpx = with(density) { containerW.toPx() }.coerceAtLeast(1f)
        val boxHpx = with(density) { containerH.toPx() }.coerceAtLeast(1f)

        val playerViewModifier = if ((isLandscapeVideo || isFullscreen || !fitModeFill) && videoWidth > 0 && videoHeight > 0) {
            val aspect = ((videoWidth * videoPixelRatio) / videoHeight.toFloat()).coerceAtLeast(0.1f)
            val (viewWpx, viewHpx) = if (boxWpx / boxHpx > aspect) {
                (boxHpx * aspect) to boxHpx
            } else {
                boxWpx to (boxWpx / aspect)
            }
            val boxW = with(density) { viewWpx.toDp() }
            val boxH = with(density) { viewHpx.toDp() }
            Modifier
                .align(Alignment.Center)
                .size(boxW, boxH)
        } else {
            Modifier.fillMaxSize()
        }

        if (mediaSource != null) {
            AndroidView(
                factory = { ctx ->
                    val pv = LayoutInflater.from(ctx).inflate(R.layout.player_texture_view, null, false) as PlayerView
                    pv.apply {
                        playerViewRef = this
                        useController = false
                        resizeMode = desiredResizeMode
                        keepScreenOn = shouldKeepScreenOn
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                    }
                },
                update = { view ->
                    playerViewRef = view
                    if (view.player !== exoPlayer) view.player = exoPlayer
                    if (view.resizeMode != desiredResizeMode) {
                        view.resizeMode = desiredResizeMode
                        view.requestLayout()
                    }
                    if (view.keepScreenOn != shouldKeepScreenOn) view.keepScreenOn = shouldKeepScreenOn
                },
                modifier = playerViewModifier
            )
        }

        // Fullscreen drag gesture overlay: horizontal seek / left brightness / right volume
        if (isFullscreen && !isScreenLocked && !isInPipMode && mediaSource != null && playerError == null) {
            val bottomSeekZonePx = with(density) { 56.dp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isFullscreen, mediaSource, boxWpx, boxHpx) {
                        var mode = FullscreenGestureMode.NONE
                        var totalDx = 0f
                        var seekBaseMs = 0L
                        var seekTargetMs = 0L
                        var brightnessVal = 0.5f
                        var volumeFloat = 0f
                        var maxVol = 15
                        var ignoreGesture = false

                        detectDragGestures(
                            onDragStart = { startOffset ->
                                mode = FullscreenGestureMode.NONE
                                totalDx = 0f
                                ignoreGesture = latestControlsVisible && startOffset.y > (boxHpx - bottomSeekZonePx)
                                if (!ignoreGesture) {
                                    seekBaseMs = latestCurrentPosMs
                                    seekTargetMs = seekBaseMs
                                    val act = context.findActivity()
                                    val curB = act?.window?.attributes?.screenBrightness ?: -1f
                                    brightnessVal = if (curB >= 0f) {
                                        curB.coerceIn(0.05f, 1.0f)
                                    } else {
                                        val sysB = runCatching {
                                            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                                        }.getOrDefault(128)
                                        (sysB / 255f).coerceIn(0.05f, 1.0f)
                                    }
                                    maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                                    volumeFloat = (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat()
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (ignoreGesture) return@detectDragGestures
                                change.consume()
                                if (mode == FullscreenGestureMode.NONE) {
                                    if (abs(dragAmount.x) + abs(dragAmount.y) < 3f) return@detectDragGestures
                                    mode = when {
                                        abs(dragAmount.x) > abs(dragAmount.y) -> FullscreenGestureMode.SEEK
                                        change.position.x < boxWpx * 0.5f -> FullscreenGestureMode.BRIGHTNESS
                                        else -> FullscreenGestureMode.VOLUME
                                    }
                                }
                                when (mode) {
                                    FullscreenGestureMode.SEEK -> {
                                        totalDx += dragAmount.x
                                        val dur = latestDurationMs.coerceAtLeast(1L)
                                        val spanMs = minOf(dur.coerceAtLeast(15_000L), 90_000L)
                                        val deltaMs = ((totalDx / boxWpx) * spanMs).toLong()
                                        seekTargetMs = (seekBaseMs + deltaMs).coerceIn(0L, dur)
                                        isScrubbing = true
                                        scrubFraction = (seekTargetMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                                        val sign = if (seekTargetMs >= seekBaseMs) "+" else "-"
                                        val diffSec = abs(seekTargetMs - seekBaseMs) / 1000L
                                        gestureHudText = "${formatDuration(seekTargetMs)} / ${formatDuration(dur)} ($sign${diffSec}s)"
                                    }
                                    FullscreenGestureMode.BRIGHTNESS -> {
                                        brightnessVal = (brightnessVal - (dragAmount.y / boxHpx)).coerceIn(0.05f, 1.0f)
                                        context.findActivity()?.window?.let { win ->
                                            val attrs = win.attributes
                                            attrs.screenBrightness = brightnessVal
                                            win.attributes = attrs
                                        }
                                        gestureHudText = "亮度 ${(brightnessVal * 100f).roundToInt()}%"
                                    }
                                    FullscreenGestureMode.VOLUME -> {
                                        volumeFloat = (volumeFloat - (dragAmount.y / boxHpx) * maxVol).coerceIn(0f, maxVol.toFloat())
                                        val targetInt = volumeFloat.roundToInt()
                                        val curInt = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: targetInt
                                        if (targetInt != curInt) {
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetInt, 0)
                                        }
                                        gestureHudText = "音量 ${((volumeFloat / maxVol.toFloat()) * 100f).roundToInt()}%"
                                    }
                                    FullscreenGestureMode.NONE -> {}
                                }
                            },
                            onDragEnd = {
                                if (!ignoreGesture && mode == FullscreenGestureMode.SEEK) {
                                    exoPlayer.seekTo(seekTargetMs)
                                    currentPosMs = seekTargetMs
                                    isScrubbing = false
                                }
                                mode = FullscreenGestureMode.NONE
                                gestureHudText = null
                            },
                            onDragCancel = {
                                if (mode == FullscreenGestureMode.SEEK) {
                                    isScrubbing = false
                                }
                                mode = FullscreenGestureMode.NONE
                                gestureHudText = null
                            }
                        )
                    }
            )
        }

        if (!firstFrameRendered && coverUrl.isNotEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = if (isFullscreen) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        }

        if (isLoading || (mediaSource != null && isBuffering && !firstFrameRendered && playerError == null)) {
            CircularProgressIndicator(
                color = CoralRed,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = !isInPipMode && isUserPaused && !isLoading && playerError == null && mediaSource != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        // "全屏观看" button placed right below centered landscape video frame
        if (!isInPipMode && isLandscapeVideo && !isFullscreen && showFullscreenHint && mediaSource != null && playerError == null) {
            val videoRenderedH = (containerW * videoHeight / videoWidth).coerceAtMost(containerH)
            val buttonTop = ((containerH + videoRenderedH) / 2) + 14.dp
            if (buttonTop + 40.dp < containerH) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = buttonTop)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1F1F24).copy(alpha = 0.88f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                        .clickable { onToggleFullscreen(true) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "全屏观看",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "全屏观看",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Exit fullscreen button in landscape fullscreen mode
        if (!isInPipMode && isFullscreen && showFullscreenHint && controlsVisibleInFullscreen && !isScreenLocked) {
            IconButton(
                onClick = { onToggleFullscreen(false) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 26.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
            }
        }

        // Fullscreen anti-misoperation lock / unlock button
        val showLockBtn = !isInPipMode && isFullscreen && (
            (isScreenLocked && lockHintVisible) || (!isScreenLocked && controlsVisibleInFullscreen)
            )
        if (showLockBtn) {
            IconButton(
                onClick = {
                    if (isScreenLocked) {
                        isScreenLocked = false
                        lockHintVisible = false
                    } else {
                        isScreenLocked = true
                        lockHintVisible = true
                        isUserPaused = false
                        exoPlayer.playWhenReady = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp)
                    .size(42.dp)
                    .background(
                        if (isScreenLocked) CoralRed.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isScreenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isScreenLocked) "解锁屏幕" else "锁定屏幕防误触",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (!isInPipMode && !gestureHudText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = gestureHudText.orEmpty(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(
            visible = !isInPipMode && isLongPressing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isFullscreen) 24.dp else 84.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = CoralRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "倍速播放中 2.0x",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        val displayErr = playerError ?: errorMessage
        if (displayErr != null && !isLoading) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = displayErr,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("重新加载")
                }
            }
        }

        val shouldShowSeekBar = !isInPipMode && showSeekBar && durationMs > 0L && controlsVisibleInFullscreen
        if (shouldShowSeekBar) {
            val fraction = if (isScrubbing) scrubFraction else (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            ThinVideoProgressBar(
                fraction = fraction,
                isScrubbing = isScrubbing,
                currentMs = if (isScrubbing) (scrubFraction * durationMs).toLong() else currentPosMs,
                durationMs = durationMs,
                onScrubStart = { f ->
                    isScrubbing = true
                    scrubFraction = f
                },
                onScrubChange = { f ->
                    scrubFraction = f
                },
                onScrubEnd = { f ->
                    val targetMs = (f * durationMs).toLong()
                    exoPlayer.seekTo(targetMs)
                    currentPosMs = targetMs
                    isScrubbing = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = if (isFullscreen) 24.dp else 12.dp, vertical = if (isFullscreen) 10.dp else 4.dp)
            )
        }
    }
}

@Composable
private fun ThinVideoProgressBar(
    fraction: Float,
    isScrubbing: Boolean,
    currentMs: Long,
    durationMs: Long,
    onScrubStart: (Float) -> Unit,
    onScrubChange: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    val density = LocalDensity.current

    Column(modifier = modifier) {
        if (isScrubbing) {
            Text(
                text = "${formatDuration(currentMs)} / ${formatDuration(durationMs)}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        val trackHeight = if (isScrubbing) 4.dp else 2.dp
        val thumbSize = if (isScrubbing) 12.dp else 6.dp
        val thumbHalfPx = with(density) { (thumbSize / 2).toPx() }

        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val f = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                        onScrubEnd(f)
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / widthPx.toFloat()).coerceIn(0f, 1f)
                            onScrubStart(dragFraction)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / widthPx.toFloat()).coerceIn(0f, 1f)
                            onScrubChange(dragFraction)
                        },
                        onDragEnd = { onScrubEnd(dragFraction) },
                        onDragCancel = { onScrubEnd(dragFraction) }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(trackHeight))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(trackHeight)
                        .background(CoralRed)
                )
            }
            val thumbOffsetX = ((widthPx * fraction.coerceIn(0f, 1f)) - thumbHalfPx)
                .coerceIn(0f, (widthPx - thumbHalfPx * 2).coerceAtLeast(0f))
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetX.roundToInt(), 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%02d:%02d".format(min, sec)
}
