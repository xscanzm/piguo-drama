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

enum class SleepTimerMode(val label: String, val durationSec: Long) {
    OFF("关闭", 0L),
    AFTER_EPISODE("播完本集", 0L),
    MIN_15("15分钟", 15L * 60L),
    MIN_30("30分钟", 30L * 60L),
    MIN_60("60分钟", 60L * 60L)
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingSec: Long = 0L,
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
        when (mode) {
            SleepTimerMode.OFF -> {
                _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, prevTriggers)
            }
            SleepTimerMode.AFTER_EPISODE -> {
                _state.value = SleepTimerState(SleepTimerMode.AFTER_EPISODE, 0L, prevTriggers)
            }
            SleepTimerMode.MIN_15, SleepTimerMode.MIN_30, SleepTimerMode.MIN_60 -> {
                _state.value = SleepTimerState(mode, mode.durationSec, prevTriggers)
                timerJob = scope.launch {
                    var left = mode.durationSec
                    while (left > 0L) {
                        delay(1000L)
                        left -= 1L
                        val cur = _state.value
                        if (cur.mode != mode) return@launch
                        if (left <= 0L) {
                            _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, cur.triggerCount + 1)
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
        if (cur.mode != SleepTimerMode.AFTER_EPISODE) return false
        timerJob?.cancel()
        timerJob = null
        _state.value = SleepTimerState(SleepTimerMode.OFF, 0L, cur.triggerCount + 1)
        return true
    }

    companion object {
        fun formatRemaining(state: SleepTimerState): String = when (state.mode) {
            SleepTimerMode.OFF -> ""
            SleepTimerMode.AFTER_EPISODE -> "播完本集"
            else -> {
                val sec = state.remainingSec.coerceAtLeast(0L)
                val m = sec / 60L
                val s = sec % 60L
                "%02d:%02d".format(m, s)
            }
        }
    }
}
