// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SleepTimerMode(
    val label: String,
    val durationSec: Long,
    val episodeCount: Int = 0
) {
    OFF("关闭", 0L, 0),
    AFTER_EPISODE("播完本集", 0L, 1),
    AFTER_3_EPISODES("播完3集", 0L, 3),
    AFTER_5_EPISODES("播完5集", 0L, 5),
    MIN_15("15分钟", 15L * 60L, 0),
    MIN_30("30分钟", 30L * 60L, 0),
    MIN_60("60分钟", 60L * 60L, 0)
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingSec: Long = 0L,
    val remainingEpisodes: Int = 0,
    val triggerCount: Int = 0
)

class SleepTimerManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()
    private var timerJob: Job? = null

    fun setMode(mode: SleepTimerMode) {
        timerJob?.cancel()
        timerJob = null
        val prevTriggers = _state.value.triggerCount
        when {
            mode == SleepTimerMode.OFF -> {
                _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, 0, prevTriggers)
            }
            mode.episodeCount > 0 -> {
                _state.value = SleepTimerState(mode, 0L, mode.episodeCount, prevTriggers)
            }
            else -> {
                _state.value = SleepTimerState(mode, mode.durationSec, 0, prevTriggers)
                timerJob = scope.launch {
                    var left = mode.durationSec
                    while (left > 0L) {
                        delay(1000L)
                        left -= 1L
                        val cur = _state.value
                        if (cur.mode != mode) return@launch
                        if (left <= 0L) {
                            _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, 0, cur.triggerCount + 1)
                            return@launch
                        } else {
                            _state.value = cur.copy(remainingSec = left)
                        }
                    }
                }
            }
        }
    }

    fun consumeEpisodeEndTrigger(): Boolean {
        val cur = _state.value
        if (cur.mode.episodeCount <= 0) return false
        val nextLeft = (cur.remainingEpisodes - 1).coerceAtLeast(0)
        if (nextLeft <= 0) {
            timerJob?.cancel()
            timerJob = null
            _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, 0, cur.triggerCount + 1)
            return true
        }
        _state.value = cur.copy(remainingEpisodes = nextLeft)
        return false
    }

    companion object {
        fun formatRemaining(state: SleepTimerState): String = when {
            state.mode == SleepTimerMode.OFF -> ""
            state.mode.episodeCount > 0 -> if (state.remainingEpisodes <= 1) "播完本集" else "余${state.remainingEpisodes}集"
            else -> {
                val sec = state.remainingSec.coerceAtLeast(0L)
                val m = sec / 60L
                val s = sec % 60L
                "%02d:%02d".format(m, s)
            }
        }
    }
}
