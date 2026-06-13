package com.arbaktos.babywatch.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arbaktos.babywatch.data.SleepApi
import com.arbaktos.babywatch.data.SleepStatus
import kotlinx.coroutines.delay
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
 * Actions are OPTIMISTIC: the predicted next state is applied immediately on
 * tap (so the palette/motion crossfade starts with the squish, not after the
 * network round trip); the server response reconciles it, and a failure snaps
 * back to the pre-tap state.
 *
 * NOTE pause semantics (matches Huckleberry, NOT the design handoff's
 * baseElapsed model): pausing freezes the timer; resuming jumps it forward to
 * wall-clock elapsed including the pause gap.
 */
data class SleepUiState(
    val phase: Phase = Phase.LOADING,
    /** Server-computed elapsed at fetch time (sleeping/paused). */
    val sessionElapsedSec: Double? = null,
    /** Server-computed "time awake" at fetch time (awake, needs lastSleepEnd). */
    val awakeElapsedSec: Double? = null,
    /** Session start (epoch sec) — needed to predict the resume jump. */
    val sessionStartedAt: Double? = null,
    /** Server clock at fetch (epoch sec) — for epoch-based predictions. */
    val serverTimeAtFetch: Double? = null,
    /** SystemClock.elapsedRealtime() when the snapshot was fetched. */
    val fetchedAtRealtime: Long = 0L,
    /**
     * A user action (start/pause/resume/stop) is awaiting the server. The blob
     * is non-interactive and shows a spinner while this is true. Deliberately
     * NOT set by a background poll — a passive refresh must never block a tap
     * (that was the "tap squishes but nothing happens" bug).
     */
    val actionInFlight: Boolean = false,
    val errorMessage: String? = null,
)

class SleepViewModel(
    private val api: SleepApi,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val _state = MutableStateFlow(SleepUiState())
    val state: StateFlow<SleepUiState> = _state.asStateFlow()

    /** A status poll is in flight. Separate from [actionInFlight] so a poll
     *  never blocks an action and never disables the blob. */
    private var isRefreshing = false

    /** Bumped by every action. A poll started before an action captures this
     *  token and discards its (now stale) result if the token has moved — so a
     *  late refresh can't clobber a fresher optimistic/confirmed state. */
    private var actionGen = 0L

    fun refresh() {
        // An action already covers us (its response is fresher than any poll),
        // and we never stack polls.
        if (isRefreshing || _state.value.actionInFlight) return
        isRefreshing = true
        val gen = actionGen
        viewModelScope.launch {
            try {
                val status = api.status()
                if (gen == actionGen && !_state.value.actionInFlight) apply(status)
            } catch (e: Exception) {
                if (gen == actionGen && !_state.value.actionInFlight) {
                    _state.update {
                        it.copy(
                            phase = if (it.phase == Phase.LOADING) Phase.ERROR else it.phase,
                            errorMessage = e.message ?: "request failed",
                        )
                    }
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    fun startSleep() = act("start") { api.start() }
    fun pauseSleep() = act("pause") { api.pause() }
    fun resumeSleep() = act("resume") { api.resume() }
    fun stopSleep() = act("stop") { api.stop() }

    /** Optimistic action: predict -> request -> reconcile (or snap back). */
    private fun act(kind: String, call: suspend () -> SleepStatus) {
        // One action at a time; the UI also disables the blob while in flight,
        // so this guard is just defence in depth, not the primary gate.
        if (_state.value.actionInFlight) return
        val before = _state.value
        actionGen++ // supersede any in-flight refresh
        _state.value = predict(kind, before).copy(actionInFlight = true, errorMessage = null)
        viewModelScope.launch {
            try {
                apply(call())
            } catch (e: Exception) {
                // Snap back; the visible revert is the failure signal, plus a
                // transient message.
                _state.value = before.copy(
                    actionInFlight = false,
                    errorMessage = e.message ?: "request failed",
                )
                clearErrorLater()
            }
        }
    }

    /** Local guess at what the server will say; corrected on reconcile. */
    private fun predict(kind: String, s: SleepUiState): SleepUiState {
        val nowRt = now()
        return when (kind) {
            "start" -> s.copy(
                phase = Phase.SLEEPING,
                sessionElapsedSec = 0.0,
                fetchedAtRealtime = nowRt,
            )
            "pause" -> s.copy(
                phase = Phase.PAUSED,
                sessionElapsedSec = s.displayElapsedSec(nowRt) ?: s.sessionElapsedSec,
                fetchedAtRealtime = nowRt,
            )
            "resume" -> {
                // Server semantics: elapsed jumps to wall-clock since start.
                val epochNow = s.serverTimeAtFetch?.plus((nowRt - s.fetchedAtRealtime) / 1000.0)
                val jumped = if (epochNow != null && s.sessionStartedAt != null) {
                    epochNow - s.sessionStartedAt
                } else {
                    s.sessionElapsedSec
                }
                s.copy(
                    phase = Phase.SLEEPING,
                    sessionElapsedSec = jumped,
                    fetchedAtRealtime = nowRt,
                )
            }
            "stop" -> s.copy(
                phase = Phase.AWAKE,
                sessionElapsedSec = null,
                awakeElapsedSec = 0.0, // "just now"
                fetchedAtRealtime = nowRt,
            )
            else -> s
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
            sessionStartedAt = status.sessionStartedAt,
            serverTimeAtFetch = status.serverTime,
            fetchedAtRealtime = now(),
            actionInFlight = false,
            errorMessage = null,
        )
    }

    private fun clearErrorLater() {
        viewModelScope.launch {
            delay(3000)
            _state.update { if (it.actionInFlight) it else it.copy(errorMessage = null) }
        }
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
