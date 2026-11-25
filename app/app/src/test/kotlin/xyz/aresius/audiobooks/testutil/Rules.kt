package xyz.aresius.audiobooks.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * ViewModels launch on Dispatchers.Main: run it unconfined so their work starts at once; the HTTP answers arrive on OkHttp threads, so tests
 * wait for states in real time with [awaitState] (a virtual-time scheduler would fire the 45 s API timeout instantly).
 *
 * Never read `vm.state.value` right after a write when the state is a `combine().stateIn()` pipeline: with Unconfined, whichever thread
 * completes a write (the test thread, an OkHttp thread answering the first requests) runs the pipeline, and a write made while another thread is
 * inside it is only picked up when that thread is done. The read then sees the old value now and then (SettingsViewModelTest, UsersViewModelTest
 * failed one run in a few under load). Wait for the value you expect with [awaitState] instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

suspend fun <T> StateFlow<T>.awaitState(timeoutMs: Long = 5000, predicate: (T) -> Boolean): T = withTimeout(timeoutMs) { first(predicate) }
