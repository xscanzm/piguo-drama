// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackRemoteCommand {
    TOGGLE_PLAY_PAUSE,
    PLAY,
    PAUSE,
    NEXT_EPISODE,
    STOP
}

data class ActivePlaybackMeta(
    val ownerId: String = "",
    val seriesId: String = "",
    val title: String = "",
    val episodeIndex: Int = 1,
    val totalEpisodes: Int = 0,
    val isPlaying: Boolean = false,
    val canSkipNext: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val active: Boolean = false
)

class PlaybackControlBus {
    companion object {
        const val ACTION_PIP_TOGGLE_PLAY = "io.github.hongguo.mobile.action.PIP_TOGGLE_PLAY"
        const val ACTION_PIP_NEXT = "io.github.hongguo.mobile.action.PIP_NEXT"
    }

    private val _meta = MutableStateFlow(ActivePlaybackMeta())
    val meta: StateFlow<ActivePlaybackMeta> = _meta.asStateFlow()

    private val _commands = MutableSharedFlow<PlaybackRemoteCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<PlaybackRemoteCommand> = _commands.asSharedFlow()

    fun updateMeta(next: ActivePlaybackMeta) {
        _meta.value = next
    }

    fun clearIfOwner(ownerId: String) {
        if (_meta.value.ownerId == ownerId) {
            _meta.value = ActivePlaybackMeta()
        }
    }

    fun emitCommand(command: PlaybackRemoteCommand) {
        _commands.tryEmit(command)
    }
}
