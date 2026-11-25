package xyz.aresius.audiobooks.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.HttpException
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.CreateBookmarkRequest
import xyz.aresius.audiobooks.core.network.DurationEntry
import xyz.aresius.audiobooks.core.network.DurationsRequest
import xyz.aresius.audiobooks.core.network.LibraryEntryWrite
import xyz.aresius.audiobooks.core.network.LibraryStatus

/** The demo backend speaks contract v1.1 through the app's own Retrofit interface, so the demo keeps working with the new code. */
class FakeBackendContractV11Test {
    private val api: AudiobooksApi = Retrofit.Builder()
        .baseUrl("http://${FakeBackend.HOST}/")
        .client(OkHttpClient.Builder().addInterceptor(FakeBackend(latencyMs = 0)).build())
        .addConverterFactory(AudiobooksJson.asConverterFactory("application/json".toMediaType()))
        .build().create(AudiobooksApi::class.java)

    private fun status(block: suspend () -> Unit): Int = runBlocking { try { block(); 200 } catch (e: HttpException) { e.code() } }

    private fun releaseOf(asin: String, format: String) = runBlocking { api.getBook(asin).releases.first { it.format == format } }

    @Test fun theBookPageCarriesDurationsOfAReleaseAPhoneAlreadyReported() = runBlocking<Unit> {
        val known = releaseOf(FakeBackend.DEMO_ASIN, "m4b")
        assertEquals(listOf(FakeBackend.FIXTURE_PART_MS), known.files.map { it.durationMs }.distinct())
        val unknown = releaseOf(FakeBackend.DEMO_ASIN, "mp3").files
        assertTrue(unknown.all { it.durationMs == null })
    }

    @Test fun durationsAreStoredFirstWriterWinsAndShownOnTheBookPage() = runBlocking<Unit> {
        val release = releaseOf("B0DEMO0003", "mp3")
        val p1 = release.files[0].path
        val r = api.reportDurations(release.infoHash, DurationsRequest(listOf(DurationEntry(p1, 61_000), DurationEntry("not/in/the/release.mp3", 5_000))))
        assertEquals(listOf(DurationEntry(p1, 61_000)), r.durations)
        assertEquals(1, r.ignored)
        val again = api.reportDurations(release.infoHash, DurationsRequest(listOf(DurationEntry(p1, 99_000))))
        assertEquals(61_000L, again.durations.single().durationMs, "a later report never replaces the stored value")
        assertEquals(61_000L, releaseOf("B0DEMO0003", "mp3").files[0].durationMs)
        assertNull(releaseOf("B0DEMO0003", "mp3").files[1].durationMs)
    }

    @Test fun durationsOutOfTheContractsBoundsAre400AndAnUnknownReleaseIs404() {
        val hash = releaseOf("B0DEMO0003", "mp3").infoHash
        val path = runBlocking { api.getBook("B0DEMO0003").releases.first { it.infoHash == hash }.files[0].path }
        assertEquals(400, status { api.reportDurations(hash, DurationsRequest(listOf(DurationEntry(path, 999)))) })
        assertEquals(400, status { api.reportDurations(hash, DurationsRequest(listOf(DurationEntry(path, 172_800_001)))) })
        assertEquals(400, status { api.reportDurations(hash, DurationsRequest(listOf(DurationEntry(path, 5_000), DurationEntry(path, 6_000)))) })
        assertEquals(400, status { api.reportDurations(hash, DurationsRequest(emptyList())) })
        assertEquals(404, status { api.reportDurations("f".repeat(40), DurationsRequest(listOf(DurationEntry(path, 5_000)))) })
    }

    @Test fun chaptersCarryTheAudibleBranding() = runBlocking<Unit> {
        val c = api.getChapters("B0DEMO0003")
        assertNotNull(c.introMs)
        assertNotNull(c.outroMs)
        assertNull(api.getChapters(FakeBackend.DEMO_ASIN).introMs)
    }

    @Test fun aBookmarkWithTheSameClientIdIsTheSameBookmarkAndTheBookIsAddedToTheLibrary() = runBlocking<Unit> {
        assertEquals(404, status { api.getLibraryEntry("B0DEMO0010") })
        val first = api.createBookmark("B0DEMO0010", CreateBookmarkRequest(5_000, "one", "uuid-1"))
        val again = api.createBookmark("B0DEMO0010", CreateBookmarkRequest(9_000, "two", "uuid-1"))
        assertEquals(first, again)
        assertEquals("uuid-1", first.clientId)
        assertEquals(5_000L, again.positionMs, "the new body is ignored")
        assertEquals(2, run { api.createBookmark("B0DEMO0010", CreateBookmarkRequest(1)); api.listBookmarks("B0DEMO0010").bookmarks.size })
        val entry = api.getLibraryEntry("B0DEMO0010")
        assertEquals(LibraryStatus.SAVED, entry.status)
        assertEquals(0L, entry.updatedAt)
        assertEquals(404, status { api.createBookmark("B_UNKNOWN", CreateBookmarkRequest(1)) })
        assertEquals(400, status { api.createBookmark("B0DEMO0010", CreateBookmarkRequest(1, null, "bad id!")) })
    }

    @Test fun finishedAtIsSetByTheWriteThatFinishesAndClearedWhenItGoesBack() = runBlocking<Unit> {
        val asin = "B0DEMO0011"
        val write = { status: String, at: Long -> LibraryEntryWrite(status, positionMs = 10, updatedAt = at) }
        assertNull(api.putLibraryEntry(asin, write(LibraryStatus.LISTENING, 100)).finishedAt)
        val finished = api.putLibraryEntry(asin, write(LibraryStatus.FINISHED, 200)).finishedAt
        assertNotNull(finished)
        assertEquals(finished, api.putLibraryEntry(asin, write(LibraryStatus.FINISHED, 300)).finishedAt, "kept by a later write that keeps it finished")
        assertNull(api.putLibraryEntry(asin, write(LibraryStatus.LISTENING, 400)).finishedAt)
        assertNotNull(api.listLibrary(LibraryStatus.FINISHED).entries.firstOrNull { it.finishedAt != null }, "the seeded finished book has one")
    }
}
