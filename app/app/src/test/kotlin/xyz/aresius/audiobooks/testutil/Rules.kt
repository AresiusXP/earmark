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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

suspend fun <T> StateFlow<T>.awaitState(timeoutMs: Long = 5000, predicate: (T) -> Boolean): T = withTimeout(timeoutMs) { first(predicate) }
