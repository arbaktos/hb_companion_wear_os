package com.arbaktos.babywatch.ui

import com.arbaktos.babywatch.data.SleepApi
import com.arbaktos.babywatch.data.SleepStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A fake SleepApi whose status() can be held open via [statusGate], so a test
 * can keep a poll "in flight" while it taps an action. Actions resolve
 * immediately (or throw, when an error is staged).
 */
private class FakeApi : SleepApi {
    val statusGate = CompletableDeferred<Unit>()
    var statusResult = awake
    var startError: Exception? = null

    override suspend fun status(): SleepStatus {
        statusGate.await()
        return statusResult
    }

    override suspend fun start(): SleepStatus {
        startError?.let { throw it }
        return sleeping
    }

    override suspend fun pause() = paused
    override suspend fun resume() = sleeping
    override suspend fun stop() = awake

    companion object {
        val awake = SleepStatus(state = "awake", serverTime = 0.0)
        val sleeping = SleepStatus(
            state = "sleeping", sessionStartedAt = 0.0, sessionElapsedSec = 0.0, serverTime = 0.0,
        )
        val paused = SleepStatus(state = "paused", sessionElapsedSec = 0.0, serverTime = 0.0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(api: SleepApi) = SleepViewModel(api, now = { 0L })

    @Test
    fun `action during in-flight refresh is not blocked`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)

        vm.refresh()            // poll launches; status() parks on the gate
        advanceUntilIdle()      // ...and is now genuinely in flight

        vm.startSleep()         // tap while the poll is still pending

        // The tap was accepted (optimistic flip), not swallowed by the poll —
        // this is the regression the bug report was about.
        assertEquals(Phase.SLEEPING, vm.state.value.phase)
        assertTrue(vm.state.value.actionInFlight)
    }

    @Test
    fun `stale refresh result does not clobber a newer action`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)

        vm.refresh()
        advanceUntilIdle()
        vm.startSleep()
        advanceUntilIdle()      // start() resolves -> SLEEPING confirmed

        api.statusGate.complete(Unit)  // the old poll finally returns "awake"
        advanceUntilIdle()

        // The superseded poll must be discarded, not applied over the action.
        assertEquals(Phase.SLEEPING, vm.state.value.phase)
        assertFalse(vm.state.value.actionInFlight)
    }

    @Test
    fun `failed action snaps back to the pre-tap state`() = runTest(dispatcher) {
        val api = FakeApi().apply { statusGate.complete(Unit) }
        val vm = viewModel(api)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(Phase.AWAKE, vm.state.value.phase)

        api.startError = IOException("phone not reachable")
        vm.startSleep()
        assertEquals(Phase.SLEEPING, vm.state.value.phase)  // optimistic

        // runCurrent (not advanceUntilIdle): run the action's coroutine so it
        // throws and snaps back, but DON'T advance the 3s clock that would fire
        // clearErrorLater and wipe the message we're asserting.
        runCurrent()
        assertEquals(Phase.AWAKE, vm.state.value.phase)     // reverted
        assertFalse(vm.state.value.actionInFlight)
        assertNotNull(vm.state.value.errorMessage)
    }

    @Test
    fun `second action is ignored while one is in flight`() = runTest(dispatcher) {
        val api = FakeApi().apply { statusGate.complete(Unit) }
        val vm = viewModel(api)
        vm.refresh()
        advanceUntilIdle()

        vm.startSleep()                 // in flight (not yet advanced)
        vm.pauseSleep()                 // should be dropped by the guard

        // Still the start prediction, not pause.
        assertEquals(Phase.SLEEPING, vm.state.value.phase)
    }
}
