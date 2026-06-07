package com.arbaktos.babywatch.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaktos.babywatch.data.BabyApi
import com.arbaktos.babywatch.data.SleepStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { LOADING, AWAKE, SLEEPING, PAUSED, ERROR }

/**
 * UI state. The server is the source of truth; between polls the UI ticks
 * locally from [fetchedAtRealtime] (monotonic clock — immune to wall-clock
 * skew between watch and server).
 *
 * NOTE pause semantics (matches Huckleberry, NOT the design handoff's
 * baseElapsed model): pausing freezes the timer; resuming jumps it forward to
 * wall-clock elapsed including the pause gap. We just display what /status
 * returns and tick from there.
 */
data class SleepUiState(
    val phase: Phase = Phase.LOADING,
    /** Server-computed elapsed at fetch time (sleeping/paused). */
    val sessionElapsedSec: Double? = null,
    /** Server-computed "time awake" at fetch time (awake, needs lastSleepEnd). */
    val awakeElapsedSec: Double? = null,
    /** SystemClock.elapsedRealtime() when the snapshot was fetched. */
    val fetchedAtRealtime: Long = 0L,
    /** An action or refresh is in flight — disable buttons. */
    val inFlight: Boolean = false,
    val errorMessage: String? = null,
)

class SleepViewModel(application: Application) : AndroidViewModel(application) {

    private val api = BabyApi(application)

    private val _state = MutableStateFlow(SleepUiState())
    val state: StateFlow<SleepUiState> = _state.asStateFlow()

    fun refresh() = launchCall { api.status() }
    fun startSleep() = launchCall { api.start() }
    fun pauseSleep() = launchCall { api.pause() }
    fun resumeSleep() = launchCall { api.resume() }
    fun stopSleep() = launchCall { api.stop() }

    private fun launchCall(block: suspend () -> SleepStatus) {
        if (_state.value.inFlight) return
        _state.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            try {
                apply(block())
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = if (it.phase == Phase.LOADING) Phase.ERROR else it.phase,
                        inFlight = false,
                        errorMessage = e.message ?: "request failed",
                    )
                }
            }
        }
    }

    private fun apply(status: SleepStatus) {
        val phase = when (status.state) {
            "sleeping" -> Phase.SLEEPING
            "paused" -> Phase.PAUSED
            else -> Phase.AWAKE
        }
        _state.value = SleepUiState(
            phase = phase,
            sessionElapsedSec = status.sessionElapsedSec,
            awakeElapsedSec = status.lastSleepEnd?.let { status.serverTime - it },
            fetchedAtRealtime = SystemClock.elapsedRealtime(),
            inFlight = false,
            errorMessage = null,
        )
    }
}

/** Elapsed seconds to display *now*, ticking locally from the last snapshot. */
fun SleepUiState.displayElapsedSec(nowRealtime: Long): Double? {
    val localDelta = (nowRealtime - fetchedAtRealtime) / 1000.0
    return when (phase) {
        Phase.SLEEPING -> sessionElapsedSec?.plus(localDelta)
        Phase.PAUSED -> sessionElapsedSec // frozen
        Phase.AWAKE -> awakeElapsedSec?.plus(localDelta)
        else -> null
    }
}
