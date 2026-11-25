package xyz.aresius.audiobooks.feature.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.player.BookmarkRepository
import xyz.aresius.audiobooks.core.player.FakePlayerDao
import xyz.aresius.audiobooks.core.player.PlayerPhase
import xyz.aresius.audiobooks.core.player.PlayerState
import xyz.aresius.audiobooks.core.player.testBook
import xyz.aresius.audiobooks.testutil.FakePlayerControls
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class PlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)
    private val controls = FakePlayerControls()
    private val dao = FakePlayerDao()
    private val bookmarks = BookmarkRepository(dao, { server.api }, app.sessions) { 1L }
    private val vm = PlayerViewModel(controls, bookmarks)

    @AfterTest fun tearDown() = server.close()

    @Test fun `controls are passed to the player, speed snapped to the contract grid`() {
        vm.togglePlay(); vm.skipBack(); vm.skipForward(); vm.nextChapter(); vm.previousChapter(); vm.seekTo(5_000)
        vm.setSpeed(1.2499f); vm.setSpeed(9f); vm.startSleep(15); vm.extendSleep(); vm.cancelSleep(); vm.retry(); vm.close()
        assertEquals(
            listOf("toggle", "skipBack", "skipForward", "next", "previous", "seek:5000", "speed:1.25", "speed:3.0", "sleep:15", "sleepExtend", "sleepCancel", "retry", "close"),
            controls.calls,
        )
    }

    @Test fun `a bookmark is added at the current position of the open book and listed`() = runBlocking<Unit> {
        server.on("POST", "/v1/me/library/B001/bookmarks", FakeServer.error(503, "upstream_failed"))
        server.onJson("GET", "/v1/me/library/B001/bookmarks", """{"bookmarks":[]}""")
        controls.mutableState.value = PlayerState(phase = PlayerPhase.Ready, asin = "B001", book = testBook("B001"), positionMs = 123_000)
        vm.addBookmark("note")
        val list = vm.bookmarks.awaitState { it.isNotEmpty() }
        assertEquals(123_000L, list.single().positionMs)
        assertEquals("note", list.single().note)
        vm.deleteBookmark(list.single().id)
        vm.bookmarks.awaitState { it.isEmpty() }
    }

    @Test fun `no book open, no bookmark`() = runBlocking<Unit> {
        vm.addBookmark("x")
        assertEquals(0, dao.unsyncedBookmarks("u1").size)
    }
}
