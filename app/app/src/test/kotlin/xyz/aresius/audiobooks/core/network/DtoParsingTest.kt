package xyz.aresius.audiobooks.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.aresius.audiobooks.testutil.Samples

class DtoParsingTest {
    private val json = AudiobooksJson

    @Test fun sessionWithUserAndNullProvider() {
        val s = json.decodeFromString(SessionResponse.serializer(), Samples.session())
        assertEquals("access-new", s.accessToken)
        assertEquals(3600, s.expiresIn)
        assertTrue(s.user.isAdmin)
        assertNull(s.user.settings.preferredProvider)
        assertEquals(SourceMode.AUTO, s.user.settings.sourceMode)
        assertFalse(s.user.settings.p2pEnabled)
    }

    @Test fun bookDetailWithReleasesAndPerUserFields() {
        val d = json.decodeFromString(BookDetail.serializer(), Samples.bookDetail(releases = Samples.release() + "," + Samples.release("b".repeat(40), usable = """{"debrid":[],"p2p":true}""", cached = "{}")))
        assertEquals("Pride and Prejudice", d.book.title)
        assertEquals(2, d.releases.size)
        assertEquals(mapOf("torbox" to true), d.releases[0].cached)
        assertEquals(listOf("torbox"), d.releases[0].usable.debrid)
        assertTrue(d.releases[1].usable.p2p)
        assertEquals(64, d.releases[0].bitrateKbps)
        assertNull(d.releases[0].narrator)
    }

    @Test fun optionalFieldsAndUnknownKeysAreTolerated() {
        val b = json.decodeFromString(Book.serializer(), """{"asin":"B000000001","title":"T","brand_new_field":1}""")
        assertEquals(emptyList(), b.authors)
        assertEquals(0, b.runtimeMin)
        val r = json.decodeFromString(ReleaseView.serializer(), """{"info_hash":"${"a".repeat(40)}","future":true}""")
        assertEquals(emptyMap(), r.cached)
        assertEquals(Usable(), r.usable)
    }

    @Test fun deviceDebridAdminInviteChaptersPrepareLibrary() {
        val d = json.decodeFromString(DevicesResponse.serializer(), """{"devices":[{"id":"d1","name":"Pixel","created_at":"2026-11-25T10:00:00Z","last_seen_at":null,"current":true}]}""")
        assertTrue(d.devices.single().current)
        assertNull(d.devices.single().lastSeenAt)
        val debrid = json.decodeFromString(DebridListResponse.serializer(), """{"providers":[${Samples.debrid("torbox")},${Samples.debrid("realdebrid", configured = false)}]}""")
        assertTrue(debrid.providers[0].isOk)
        assertFalse(debrid.providers[1].configured)
        val u = json.decodeFromString(AdminUsersResponse.serializer(), """{"users":[{"id":"u1","display_name":"Ada","role":"admin","disabled":false,"created_at":"2026-11-25T10:00:00Z","device_count":2,"debrid":{"torbox":true,"realdebrid":false}}]}""")
        assertEquals(true, u.users.single().debrid["torbox"])
        val created = json.decodeFromString(CreatedInvite.serializer(), """{"id":"i1","note":"for Bob","created_at":"2026-11-25T10:00:00Z","expires_at":"2026-11-28T10:00:00Z","code":"SECRET-CODE"}""")
        assertEquals("SECRET-CODE", created.code)
        assertFalse(created.toString().contains("SECRET-CODE"))
        val c = json.decodeFromString(Chapters.serializer(), """{"asin":"B000000001","runtime_ms":1000,"chapters":[{"title":"One","start_ms":0,"length_ms":500}]}""")
        assertEquals(500, c.chapters.single().lengthMs)
        val p = json.decodeFromString(Prepare.serializer(), """{"state":"failed","reason":"no_seeders","retry_after_s":30}""")
        assertEquals(Prepare.FAILED, p.state)
        val l = json.decodeFromString(LibraryResponse.serializer(), """{"entries":[${Samples.libraryEntry("B000000001")}]}""")
        assertEquals(600000, l.entries.single().positionMs)
        assertEquals(1.0, l.entries.single().speed)
    }

    @Test fun requestsOmitNullsAndUseTheWireNames() {
        val patch = json.encodeToString(UpdateMeRequest.serializer(), UpdateMeRequest(settings = SettingsPatch(p2pEnabled = true, sourceMode = "auto")))
        assertEquals("""{"settings":{"source_mode":"auto","p2p_enabled":true}}""", patch)
        assertEquals("""{"api_key":"abcdefgh"}""", json.encodeToString(PutDebridKeyRequest.serializer(), PutDebridKeyRequest("abcdefgh")))
        assertEquals("""{"provider":"torbox"}""", json.encodeToString(PrepareRequest.serializer(), PrepareRequest("torbox")))
        assertEquals("""{"status":"saved","updated_at":5}""", json.encodeToString(LibraryEntryWrite.serializer(), LibraryEntryWrite(status = "saved", updatedAt = 5)))
        assertEquals("""{"note":"hi","expires_in_hours":24}""", json.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest("hi", 24)))
        assertEquals(Provider.RealDebrid, Provider.fromWire("realdebrid"))
        assertNull(Provider.fromWire("alldebrid"))
    }

    @Test fun contractV11FieldsAreReadAndTheirAbsenceIsTolerated() {
        val c = json.decodeFromString(Chapters.serializer(), """{"asin":"B1","runtime_ms":1000,"intro_ms":2000,"outro_ms":5000,"chapters":[]}""")
        assertEquals(2000L, c.introMs)
        assertEquals(5000L, c.outroMs)
        val old = json.decodeFromString(Chapters.serializer(), """{"asin":"B1","runtime_ms":1000,"chapters":[]}""")
        assertNull(old.introMs)
        assertNull(old.outroMs)
        val files = json.decodeFromString(
            ReleaseView.serializer(),
            """{"info_hash":"${"a".repeat(40)}","files":[{"index":0,"path":"a.mp3","size":10,"duration_ms":61000},{"index":1,"path":"b.mp3","size":10}],"torrent_files":[{"index":0,"path":"a.mp3","size":10,"duration_ms":null}]}""",
        )
        assertEquals(61000L, files.files[0].durationMs)
        assertNull(files.files[1].durationMs)
        assertNull(files.torrentFiles.single().durationMs)
        val entry = json.decodeFromString(LibraryEntry.serializer(), Samples.libraryEntry("B000000001", "finished", finishedAt = "2026-11-25T10:00:00Z"))
        assertEquals("2026-11-25T10:00:00Z", entry.finishedAt)
        assertNull(json.decodeFromString(LibraryEntry.serializer(), Samples.libraryEntry("B000000001")).finishedAt)
        assertNull(json.decodeFromString(LibraryEntry.serializer(), """{"book":${Samples.book()},"status":"saved"}""").finishedAt)
        val bm = json.decodeFromString(BookmarksResponse.serializer(), """{"bookmarks":[{"id":"b1","position_ms":5,"created_at":"2026-11-25T10:00:00Z","client_id":"abc-1"},{"id":"b2","position_ms":6,"created_at":"x"}]}""")
        assertEquals("abc-1", bm.bookmarks[0].clientId)
        assertNull(bm.bookmarks[1].clientId)
    }

    @Test fun durationsAreSentAsContractedAndTheAnswerIsTolerated() {
        val body = json.encodeToString(DurationsRequest.serializer(), DurationsRequest(listOf(DurationEntry("A/1.mp3", 61000))))
        assertEquals("""{"durations":[{"path":"A/1.mp3","duration_ms":61000}]}""", body)
        val r = json.decodeFromString(DurationsResponse.serializer(), """{"durations":[{"path":"A/1.mp3","duration_ms":60500,"extra":1}],"ignored":2,"future":true}""")
        assertEquals(60500L, r.durations.single().durationMs)
        assertEquals(2, r.ignored)
        assertEquals(DurationsResponse(), json.decodeFromString(DurationsResponse.serializer(), "{}"))
    }

    @Test fun requestsOmitWhatWasNotSet() {
        assertEquals("""{"position_ms":5}""", json.encodeToString(CreateBookmarkRequest.serializer(), CreateBookmarkRequest(5)))
        assertEquals("""{"position_ms":5,"note":"n","client_id":"c-1"}""", json.encodeToString(CreateBookmarkRequest.serializer(), CreateBookmarkRequest(5, "n", "c-1")))
        assertEquals("""{"files":[{"index":0,"path":"a","size":1}]}""", json.encodeToString(TorrentFilesRequest.serializer(), TorrentFilesRequest(listOf(TorrentFile(0, "a", 1)))))
    }
}
