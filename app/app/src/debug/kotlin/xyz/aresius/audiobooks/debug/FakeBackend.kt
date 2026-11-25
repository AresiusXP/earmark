package xyz.aresius.audiobooks.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import xyz.aresius.audiobooks.core.auth.CodeFormat
import xyz.aresius.audiobooks.core.network.AdminUpdateUserRequest
import xyz.aresius.audiobooks.core.network.AdminUser
import xyz.aresius.audiobooks.core.network.AdminUsersResponse
import xyz.aresius.audiobooks.core.network.ApiErrorBody
import xyz.aresius.audiobooks.core.network.ApiErrorDetail
import xyz.aresius.audiobooks.core.network.AppUpdateDto
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.BootstrapRequest
import xyz.aresius.audiobooks.core.network.Book
import xyz.aresius.audiobooks.core.network.BookDetail
import xyz.aresius.audiobooks.core.network.BookListResponse
import xyz.aresius.audiobooks.core.network.Bookmark
import xyz.aresius.audiobooks.core.network.BookmarksResponse
import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.CreateBookmarkRequest
import xyz.aresius.audiobooks.core.network.CreateInviteRequest
import xyz.aresius.audiobooks.core.network.CreatedInvite
import xyz.aresius.audiobooks.core.network.DebridListResponse
import xyz.aresius.audiobooks.core.network.DebridStatus
import xyz.aresius.audiobooks.core.network.Device
import xyz.aresius.audiobooks.core.network.DurationEntry
import xyz.aresius.audiobooks.core.network.DurationsRequest
import xyz.aresius.audiobooks.core.network.DurationsResponse
import xyz.aresius.audiobooks.core.network.DevicesResponse
import xyz.aresius.audiobooks.core.network.GoogleJoinRequest
import xyz.aresius.audiobooks.core.network.GoogleLink
import xyz.aresius.audiobooks.core.network.GoogleLinkRequest
import xyz.aresius.audiobooks.core.network.GoogleNonceResponse
import xyz.aresius.audiobooks.core.network.GoogleSignInRequest
import xyz.aresius.audiobooks.core.network.Invite
import xyz.aresius.audiobooks.core.network.InvitesResponse
import xyz.aresius.audiobooks.core.network.JoinRequest
import xyz.aresius.audiobooks.core.network.LibraryEntry
import xyz.aresius.audiobooks.core.network.LibraryEntryWrite
import xyz.aresius.audiobooks.core.network.LibraryResponse
import xyz.aresius.audiobooks.core.network.LibraryStatus
import xyz.aresius.audiobooks.core.network.LinkCodeResponse
import xyz.aresius.audiobooks.core.network.LinkRequest
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.core.network.Prepare
import xyz.aresius.audiobooks.core.network.PutDebridKeyRequest
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.Roles
import xyz.aresius.audiobooks.core.network.SessionResponse
import xyz.aresius.audiobooks.core.network.Settings
import xyz.aresius.audiobooks.core.network.SettingsPatch
import xyz.aresius.audiobooks.core.network.TorrentFile
import xyz.aresius.audiobooks.core.network.UpdateMeRequest
import xyz.aresius.audiobooks.core.network.Usable
import xyz.aresius.audiobooks.core.network.VersionInfo

/**
 * Debug-only stand-in for the server, answering every route of api/openapi.yaml for the host [HOST] in process (no sockets), so the
 * app can be run and demoed on an emulator before the server implements catalog, releases and library. State lives in memory.
 * See app/README.md ("Demo backend") for the codes it accepts.
 */
class FakeBackend(private val latencyMs: Long = 250) : Interceptor {
    private val lock = Any()
    private var bootstrapped = false
    private val me = HashMap<String, Me>()
    private var currentUserId = ""
    private val disabled = HashSet<String>()
    private val devices = ArrayList<Device>()
    private val invites = LinkedHashMap<String, Pair<Invite, String>>()
    private val linkCodes = HashSet<String>()
    private val googleNonces = HashSet<String>()
    private val googleIdentities = HashMap<String, String>() // Google sub -> user id
    private val debridKeys = HashMap<String, DebridStatus>()
    private val library = LinkedHashMap<String, LibraryEntry>()
    private val bookmarks = HashMap<String, MutableList<Bookmark>>()
    private val reportedDurations = HashMap<String, MutableMap<String, Long>>() // release hash -> path -> duration_ms (first writer wins)
    private val prepareCalls = HashMap<String, Int>()
    private val books = sampleBooks()
    private val downloads = DemoDownloads()

    init {
        invites[CodeFormat.normalize(DEMO_INVITE)] = Invite("inv-demo", "Demo invite", Instant.now().toString(), Instant.now().plusSeconds(72 * 3600).toString()) to DEMO_INVITE
        resetLibrary()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        if (latencyMs > 0) Thread.sleep(latencyMs)
        return synchronized(lock) { handle(request) }
    }

    // --- routing -------------------------------------------------------------------------------------------------

    private fun handle(request: Request): Response {
        val seg = request.url.pathSegments.filter { it.isNotEmpty() }
        val m = request.method
        if (seg.firstOrNull() == "cdn") return downloads.cdn(request)
        if (seg.firstOrNull() == "covers") return cover(request, seg.getOrNull(1)?.substringBefore('.').orEmpty())
        if (seg == listOf("healthz")) return text(request, "ok")
        if (seg.size < 2 || seg[0] != "v1") return error(request, 404, "not_found", "no such route")
        val path = seg.drop(1)
        if (path == listOf("version")) return json(request, VersionInfo("0.0.0-demo", 1, bootstrapped, googleEnabled = true))
        if (path == listOf("app", "update")) return json(request, AppUpdateDto(available = false))
        if (path.firstOrNull() == "auth") return auth(request, path)
        ensureUser()
        return when (path[0]) {
            "me" -> meRoutes(request, m, path)
            "admin" -> adminRoutes(request, m, path)
            "search" -> search(request)
            "discover" -> discover(request)
            "books" -> bookRoutes(request, path)
            "releases" -> releaseRoutes(request, m, path)
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    private fun auth(request: Request, path: List<String>): Response {
        if (path.getOrNull(1) == "google") return googleAuth(request, path.drop(2))
        if (request.method != "POST") return error(request, 404, "not_found", "no such route")
        return when (path.getOrNull(1)) {
            "bootstrap" -> {
                val b = body(request, BootstrapRequest.serializer())
                when {
                    bootstrapped -> error(request, 409, "conflict", "the instance is already set up")
                    b.instancePassword != DEMO_PASSWORD -> error(request, 401, "unauthorized", "wrong instance password")
                    else -> signIn(request, b.displayName, b.deviceName, Roles.ADMIN).also { bootstrapped = true }
                }
            }
            "join" -> {
                val b = body(request, JoinRequest.serializer())
                if (invites.remove(CodeFormat.normalize(b.inviteCode)) == null) error(request, 401, "unauthorized", "unknown, used or expired invite")
                else signIn(request, b.displayName, b.deviceName, Roles.USER)
            }
            "link" -> {
                val b = body(request, LinkRequest.serializer())
                if (!linkCodes.remove(CodeFormat.normalize(b.linkCode))) error(request, 401, "unauthorized", "unknown, used or expired link code")
                else {
                    ensureUser()
                    signIn(request, me[currentUserId]!!.displayName, b.deviceName, me[currentUserId]!!.role, existing = currentUserId)
                }
            }
            "refresh" -> json(request, session(me[currentUserId] ?: defaultUser()))
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    // --- Sign in with Google (fake ID tokens, see FakeGoogleTokenSource) ---------------------------------------------

    private fun googleAuth(request: Request, path: List<String>): Response = when {
        path == listOf("nonce") && request.method == "GET" -> {
            val nonce = "demo-nonce-${UUID.randomUUID()}"
            googleNonces.add(nonce)
            json(request, GoogleNonceResponse(nonce, Instant.now().plusSeconds(300).toString()))
        }
        path.isEmpty() && request.method == "POST" -> {
            val b = body(request, GoogleSignInRequest.serializer())
            val token = spendToken(b.idToken)
            when {
                token == null -> error(request, 401, "unauthorized", "invalid ID token")
                googleIdentities[token.sub] == null -> error(request, 401, "google_not_linked", "this Google account is not linked to a user")
                else -> {
                    val id = googleIdentities.getValue(token.sub)
                    ensureUser()
                    val u = me[id] ?: user()
                    signIn(request, u.displayName, b.deviceName, u.role, existing = u.id)
                }
            }
        }
        path == listOf("join") && request.method == "POST" -> {
            val b = body(request, GoogleJoinRequest.serializer())
            val token = spendToken(b.idToken)
            when {
                token == null -> error(request, 401, "unauthorized", "invalid ID token")
                googleIdentities[token.sub] != null -> error(request, 409, "conflict", "this Google account is already linked to a user")
                invites[CodeFormat.normalize(b.inviteCode)] == null -> error(request, 401, "unauthorized", "unknown, used or expired invite")
                else -> {
                    invites.remove(CodeFormat.normalize(b.inviteCode))
                    val name = b.displayName?.takeIf { it.isNotBlank() } ?: token.givenName
                    signIn(request, name, b.deviceName, Roles.USER).also {
                        googleIdentities[token.sub] = currentUserId
                        me[currentUserId] = user().copy(google = GoogleLink(true, token.email))
                    }
                }
            }
        }
        else -> error(request, 404, "not_found", "no such route")
    }

    private fun linkGoogle(request: Request): Response {
        val token = spendToken(body(request, GoogleLinkRequest.serializer()).idToken) ?: return error(request, 401, "unauthorized", "invalid ID token")
        val owner = googleIdentities[token.sub]
        val u = user()
        return when {
            owner != null && owner != u.id -> error(request, 409, "conflict", "this Google account is linked to another user")
            owner == null && u.google.linked -> error(request, 409, "conflict", "another Google account is already linked: unlink it first")
            else -> {
                googleIdentities[token.sub] = u.id
                val linked = u.copy(google = GoogleLink(true, token.email))
                me[u.id] = linked
                json(request, linked)
            }
        }
    }

    private fun unlinkGoogle(request: Request): Response {
        googleIdentities.entries.removeAll { it.value == currentUserId }
        me[currentUserId] = user().copy(google = GoogleLink())
        return noContent(request)
    }

    /** Like the real server: a token must carry a nonce minted here, and every nonce works once. */
    private fun spendToken(idToken: String): FakeGoogleToken? {
        val token = FakeGoogleToken.parse(idToken) ?: return null
        return if (googleNonces.remove(token.nonce)) token else null
    }

    /** What the server mints: 26 Crockford base32 characters in groups of four ("K7QD-9XMP-..."). */
    private fun newCode(): String {
        val random = java.security.SecureRandom()
        return buildString {
            repeat(CodeFormat.LENGTH) { i ->
                if (i > 0 && i % 4 == 0) append('-')
                append(CodeFormat.ALPHABET[random.nextInt(CodeFormat.ALPHABET.length)])
            }
        }
    }

    private fun signIn(request: Request, displayName: String, deviceName: String, role: String, existing: String? = null): Response {
        val id = existing ?: "u-${UUID.randomUUID().toString().take(8)}"
        val user = me[id] ?: Me(id, displayName.trim(), role, Settings())
        me[id] = user
        currentUserId = id
        devices.add(Device("d-${UUID.randomUUID().toString().take(8)}", deviceName.trim(), Instant.now().toString(), Instant.now().toString(), current = true))
        // The new device is the current one; earlier ones are not.
        for (i in 0 until devices.lastIndex) devices[i] = devices[i].copy(current = false)
        return json(request, session(user))
    }

    private fun session(user: Me) = SessionResponse("demo-access-${UUID.randomUUID()}", "demo-refresh-${UUID.randomUUID()}", 3600, "Bearer", user)

    /** The user a restarted process continues as. It already has a working TorBox key so a reinstall during development can stream at once. */
    private fun defaultUser(): Me = Me("u-demo", "Demo Admin", Roles.ADMIN, Settings()).also {
        me[it.id] = it
        currentUserId = it.id
        bootstrapped = true
        debridKeys["torbox"] = DebridStatus("torbox", configured = true, status = "ok", validatedAt = Instant.now().toString())
    }

    /** The app may outlive the fake's memory (a process restart keeps the stored session): start from a default admin then. */
    private fun ensureUser() {
        if (me[currentUserId] == null) defaultUser()
        if (devices.isEmpty()) devices.add(Device("d-demo", "Emulator", Instant.now().minusSeconds(3600).toString(), Instant.now().toString(), current = true))
    }

    private fun user(): Me = me[currentUserId]!!

    // --- /v1/me ------------------------------------------------------------------------------------------------

    private fun meRoutes(request: Request, m: String, path: List<String>): Response = when {
        path == listOf("me") && m == "GET" -> json(request, user())
        path == listOf("me") && m == "PATCH" -> {
            val b = body(request, UpdateMeRequest.serializer())
            val s = b.settings
            if (s?.languages?.isEmpty() == true) error(request, 400, "invalid_request", "languages needs at least one entry")
            else {
                var u = user()
                b.displayName?.let { u = u.copy(displayName = it) }
                if (s != null) u = u.copy(settings = applyPatch(u.settings, s))
                me[u.id] = u
                json(request, u)
            }
        }
        path == listOf("me", "google") && m == "POST" -> linkGoogle(request)
        path == listOf("me", "google") && m == "DELETE" -> unlinkGoogle(request)
        path == listOf("me", "devices") && m == "GET" -> json(request, DevicesResponse(devices.toList()))
        path == listOf("me", "devices", "link-code") && m == "POST" -> {
            val code = newCode()
            linkCodes.add(CodeFormat.normalize(code))
            json(request, LinkCodeResponse(code, Instant.now().plusSeconds(900).toString()))
        }
        path.size == 3 && path[1] == "devices" && m == "DELETE" -> {
            devices.removeAll { it.id == path[2] }
            noContent(request)
        }
        path == listOf("me", "debrid") && m == "GET" ->
            json(request, DebridListResponse(listOf("torbox", "realdebrid").map { debridKeys[it] ?: DebridStatus(it, configured = false) }))
        path.size == 3 && path[1] == "debrid" -> debrid(request, m, path[2])
        path.getOrNull(1) == "library" -> libraryRoutes(request, m, path)
        else -> error(request, 404, "not_found", "no such route")
    }

    private fun applyPatch(s: Settings, p: SettingsPatch) = s.copy(
        region = p.region ?: s.region,
        languages = p.languages ?: s.languages,
        preferFormat = p.preferFormat ?: s.preferFormat,
        sourceMode = p.sourceMode ?: s.sourceMode,
        p2pEnabled = p.p2pEnabled ?: s.p2pEnabled,
    )

    private fun debrid(request: Request, m: String, provider: String): Response {
        if (provider != "torbox" && provider != "realdebrid") return error(request, 404, "not_found", "unknown provider")
        return when (m) {
            "PUT" -> {
                val key = body(request, PutDebridKeyRequest.serializer()).apiKey
                when {
                    key.length < 8 -> error(request, 400, "invalid_request", "api_key must be at least 8 characters")
                    key == BAD_KEY -> error(request, 400, "bad_debrid_key", "$provider rejected this key")
                    else -> DebridStatus(provider, true, "ok", Instant.now().toString(), null, sharedWithOthers = false).also { debridKeys[provider] = it }.let { json(request, it) }
                }
            }
            "POST" -> debridKeys[provider]?.let { json(request, it.copy(validatedAt = Instant.now().toString()).also { s -> debridKeys[provider] = s }) }
                ?: error(request, 404, "not_found", "no key stored")
            "DELETE" -> {
                debridKeys.remove(provider)
                noContent(request)
            }
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    // --- admin ------------------------------------------------------------------------------------------------

    private fun adminRoutes(request: Request, m: String, path: List<String>): Response {
        if (user().role != Roles.ADMIN) return error(request, 403, "forbidden", "admin only")
        return when {
            path == listOf("admin", "users") && m == "GET" -> json(request, AdminUsersResponse(adminUsers()))
            path.size == 3 && path[1] == "users" && m == "PATCH" -> {
                val id = path[2]
                val wanted = body(request, AdminUpdateUserRequest.serializer()).disabled
                if (id == currentUserId) {
                    error(request, 409, "conflict", "you cannot disable yourself")
                } else {
                    if (wanted == true) disabled.add(id) else if (wanted == false) disabled.remove(id)
                    adminUsers().firstOrNull { it.id == id }?.let { json(request, it) } ?: error(request, 404, "not_found", "no such user")
                }
            }
            path == listOf("admin", "invites") && m == "GET" -> json(request, InvitesResponse(invites.values.map { it.first }))
            path == listOf("admin", "invites") && m == "POST" -> {
                val b = body(request, CreateInviteRequest.serializer())
                val code = newCode()
                val invite = Invite("inv-${UUID.randomUUID().toString().take(6)}", b.note, Instant.now().toString(), Instant.now().plusSeconds((b.expiresInHours ?: 72) * 3600L).toString())
                invites[CodeFormat.normalize(code)] = invite to code
                json(request, CreatedInvite(invite.id, invite.note, invite.createdAt, invite.expiresAt, code))
            }
            path.size == 3 && path[1] == "invites" && m == "DELETE" -> {
                invites.entries.removeAll { it.value.first.id == path[2] }
                noContent(request)
            }
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    private fun adminUsers(): List<AdminUser> {
        val own = user()
        val real = me.values.filter { it.id != own.id }.map { AdminUser(it.id, it.displayName, it.role, it.id in disabled, Instant.now().toString(), Instant.now().toString(), 1, mapOf("torbox" to false, "realdebrid" to false)) }
        val seeded = listOf(
            AdminUser("u-anna", "Anna", Roles.USER, "u-anna" in disabled, Instant.now().minusSeconds(86400 * 9).toString(), Instant.now().minusSeconds(7200).toString(), 2, mapOf("torbox" to true, "realdebrid" to false)),
            AdminUser("u-bram", "Bram", Roles.USER, "u-bram" in disabled, Instant.now().minusSeconds(86400 * 3).toString(), null, 1, mapOf("torbox" to false, "realdebrid" to true)),
        )
        val self = AdminUser(own.id, own.displayName, own.role, false, Instant.now().minusSeconds(86400 * 20).toString(), Instant.now().toString(), devices.size, debridKeys.mapValues { it.value.configured })
        return listOf(self) + real + seeded
    }

    // --- catalog ----------------------------------------------------------------------------------------------

    private fun search(request: Request): Response {
        val q = request.url.queryParameter("q").orEmpty().trim()
        if (q.length < 2) return error(request, 400, "invalid_request", "q must have at least 2 characters")
        if (q.equals("error", ignoreCase = true)) return error(request, 503, "upstream_failed", "demo failure: search is down", retryAfter = 0)
        val hits = visibleBooks().filter { b -> listOf(b.title, b.authors.joinToString(), b.narrators.joinToString(), b.series.orEmpty()).any { it.contains(q, ignoreCase = true) } }
        return json(request, BookListResponse(hits))
    }

    private fun discover(request: Request): Response {
        val page = request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val from = page * PAGE_SIZE
        val shelf = visibleBooks()
        return json(request, BookListResponse(if (from >= shelf.size) emptyList() else shelf.subList(from, minOf(shelf.size, from + PAGE_SIZE))))
    }

    /** The catalog as the user's content languages see it: the Spanish demo books show only with "es" selected, right after the demo book (the others are always listed, as before). */
    private fun visibleBooks(): List<Book> {
        val (spanish, rest) = books.partition { it.language == "es" }
        return if ("es" in user().settings.languages) rest.take(1) + spanish + rest.drop(1) else rest
    }

    private fun bookRoutes(request: Request, path: List<String>): Response {
        val book = books.firstOrNull { it.asin == path.getOrNull(1) } ?: return error(request, 404, "not_found", "unknown book")
        return when (path.getOrNull(2)) {
            // Per-user fields (`cached`, `usable`) are mixed in: the real server sends this page `private, no-store`.
            null -> json(request, BookDetail(book, Instant.now().minusSeconds(600).toString(), releasesFor(book)), cacheControl = "private, no-store")
            "chapters" ->
                if (book.asin == DEMO_ASIN) json(request, Chapters(book.asin, DEMO_PARTS * FIXTURE_PART_MS, (0 until DEMO_PARTS).map { Chapter("Part ${it + 1}: ${listOf("Opening", "Middle", "Ending")[it]}", it * FIXTURE_PART_MS, FIXTURE_PART_MS) }))
                // The Audible edition carries branding at both ends (`intro_ms`/`outro_ms`); the marks are measured with it included.
                else json(request, Chapters(book.asin, book.runtimeMin * 60_000L, (0 until 8).map { Chapter("Chapter ${it + 1}", it * book.runtimeMin * 7_500L, book.runtimeMin * 7_500L) }, AUDIBLE_INTRO_MS, AUDIBLE_OUTRO_MS), cacheControl = "private, max-age=300")
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    private fun releasesFor(book: Book): List<ReleaseView> {
        val seed = book.asin.last().code
        val ok = debridKeys.filterValues { it.status == "ok" }.keys.toList()
        val usable = Usable(debrid = ok, p2p = user().settings.p2pEnabled)
        return listOf(
            release(book, "m4b", 64, 420L * 1024 * 1024 + seed * 1_000_000L, 88 + seed % 20, mapOf("torbox" to true), usable),
            release(book, "mp3", 128, 910L * 1024 * 1024 + seed * 1_000_000L, 31 + seed % 9, mapOf("torbox" to true, "realdebrid" to false), usable),
            release(book, "mp3", 64, 380L * 1024 * 1024 + seed * 1_000_000L, 4, emptyMap(), usable),
        )
    }

    private fun release(book: Book, format: String, kbps: Int, size: Long, seeders: Int, cached: Map<String, Boolean>, usable: Usable): ReleaseView {
        val hash = (book.asin + format + kbps).hashCode().toUInt().toString(16).padStart(8, '0').repeat(5)
        val parts = if (book.asin == DEMO_ASIN) DEMO_PARTS else 6
        val files = (1..parts).map { n ->
            val path = "${book.title}/Part $n.$format"
            // The demo book's m4b release is one a phone reported already (its parts are 60 s); everything else is learned by playing (POST durations).
            val known = reportedDurations[hash]?.get(path) ?: FIXTURE_PART_MS.takeIf { book.asin == DEMO_ASIN && format == "m4b" }
            TorrentFile(n - 1, path, if (book.asin == DEMO_ASIN) 0L else DemoDownloads.FILE_BYTES, known)
        }
        return ReleaseView(
            infoHash = hash, title = "${book.authors.firstOrNull().orEmpty()} - ${book.title} [${format.uppercase()} ${kbps}kbps]", source = "demo", size = size, seeders = seeders,
            format = format, bitrateKbps = kbps, narrator = book.narrators.firstOrNull(), language = book.language, score = 0.9 - kbps / 1000.0, collection = false,
            magnet = "magnet:?xt=urn:btih:$hash", files = files, torrentFiles = emptyList(), cached = cached, usable = usable,
        )
    }

    private fun demoFiles(hash: String): List<Pair<Int, String>> =
        books.flatMap { releasesFor(it) }.firstOrNull { it.infoHash == hash }?.files?.map { it.index to it.path }.orEmpty()

    /**
     * The demo book ([DEMO_ASIN]) streams AND downloads from the local audio fixture ([FIXTURE_BASE], see app/scripts/audio_fixture.py): a cached
     * release answers `prepare` with `ready` and its download route with a 302 to the fixture; an uncached one is `preparing` (25 % more per
     * poll) for its first four polls, so the "getting the book ready" state shows. Its files list no size (the fixture's real sizes are not
     * known here), so the download's size check is skipped and the player shares the runtime equally among the parts. Every other book goes through [DemoDownloads]: `prepare`
     * after a few polls, then a 302 to this host's own `/cdn/` that serves a silent WAV (playable too). Every route needs a working debrid key
     * (`403 no_debrid_key`).
     */
    private fun releaseRoutes(request: Request, m: String, path: List<String>): Response {
        val hash = path.getOrNull(1).orEmpty()
        val release = books.flatMap { releasesFor(it) }.firstOrNull { it.infoHash == hash } ?: return error(request, 404, "not_found", "unknown release")
        if (path.getOrNull(2) == "durations" && m == "POST") return reportDurations(request, release)
        if (debridKeys.values.none { it.status == "ok" }) return error(request, 403, "no_debrid_key", "add a debrid key first")
        val fixture = books.any { it.asin == DEMO_ASIN && releasesFor(it).any { r -> r.infoHash == hash } }
        if (!fixture) {
            return when {
                path.getOrNull(2) == "prepare" && m == "POST" -> downloads.prepare(request, hash, demoFiles(hash), true)
                path.getOrNull(2) == "files" && path.getOrNull(4) == "download" && m == "GET" -> downloads.download(request, hash, path[3].toIntOrNull() ?: 0, true)
                else -> error(request, 404, "not_found", "not implemented by the demo backend")
            }
        }
        val cached = release.cached["torbox"] == true || release.cached["realdebrid"] == true
        return when {
            path.getOrNull(2) == "prepare" && m == "POST" -> {
                val n = prepareCalls.merge(hash, 1, Int::plus) ?: 1
                json(request, if (cached || n > PREPARE_POLLS) Prepare(Prepare.READY, 100, files = release.files) else Prepare(Prepare.PREPARING, n * 100 / (PREPARE_POLLS + 1), retryAfterS = 2))
            }
            path.getOrNull(2) == "files" && path.getOrNull(4) == "download" && (m == "GET" || m == "HEAD") -> {
                val index = path[3].toIntOrNull()?.takeIf { it in release.files.indices } ?: return error(request, 404, "not_found", "no such file")
                if (!cached && (prepareCalls[hash] ?: 0) <= PREPARE_POLLS) {
                    json(request, Prepare(Prepare.PREPARING, 30, retryAfterS = 2), 202)
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Found").header("Location", "$FIXTURE_BASE/part${index % FIXTURE_PARTS + 1}.mp3")
                        .header("Cache-Control", "no-store").body("".toResponseBody(JSON)).build()
                }
            }
            else -> error(request, 404, "not_found", "not implemented by the demo backend")
        }
    }

    /** `POST /v1/releases/{hash}/durations`: paths of the release's audio files only, 1 s..48 h each, no duplicates, first writer wins. */
    private fun reportDurations(request: Request, release: ReleaseView): Response {
        val entries = body(request, DurationsRequest.serializer()).durations
        val known = release.files.map { it.path }.toSet()
        val paths = entries.map { it.path.replace('\\', '/') }
        if (entries.isEmpty() || entries.size > 2000 || paths.toSet().size != paths.size || entries.any { it.durationMs !in 1_000L..172_800_000L }) {
            return error(request, 400, "invalid_request", "durations must be 1-2000 distinct paths of 1 s to 48 h")
        }
        val stored = reportedDurations.getOrPut(release.infoHash) { HashMap() }
        val accepted = entries.indices.filter { paths[it] in known }.map { i ->
            DurationEntry(paths[i], stored.getOrPut(paths[i]) { entries[i].durationMs })
        }
        return json(request, DurationsResponse(accepted, entries.size - accepted.size))
    }

    // --- library ----------------------------------------------------------------------------------------------

    private fun libraryRoutes(request: Request, m: String, path: List<String>): Response {
        if (path.size == 2 && m == "GET") {
            val status = request.url.queryParameter("status")
            return json(request, LibraryResponse(library.values.filter { status == null || it.status == status }.sortedByDescending { it.updatedAt }))
        }
        val asin = path.getOrNull(2) ?: return error(request, 404, "not_found", "no such route")
        if (path.size > 3) return bookmarkRoutes(request, m, asin, path)
        return when (m) {
            "GET" -> library[asin]?.let { json(request, it) } ?: error(request, 404, "not_found", "not in the library")
            "PUT" -> {
                val book = books.firstOrNull { it.asin == asin } ?: return error(request, 404, "not_found", "unknown book")
                val b = body(request, LibraryEntryWrite.serializer())
                val old = library[asin]
                val entry = if (old != null && old.updatedAt > b.updatedAt) old
                else LibraryEntry(
                    book, b.status, b.releaseHash, b.positionMs ?: old?.positionMs ?: 0, b.filePath, b.filePositionMs ?: 0, b.speed ?: 1.0, b.updatedAt,
                    // Set by the write that turns the status to finished, kept while it stays finished, cleared otherwise.
                    finishedAt = if (b.status != LibraryStatus.FINISHED) null else old?.finishedAt?.takeIf { old.status == LibraryStatus.FINISHED } ?: Instant.now().toString(),
                )
                library[asin] = entry
                json(request, entry)
            }
            "DELETE" -> {
                library.remove(asin)
                noContent(request)
            }
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    private fun bookmarkRoutes(request: Request, m: String, asin: String, path: List<String>): Response {
        val list = bookmarks.getOrPut(asin) { ArrayList() }
        return when {
            path.size == 4 && m == "GET" -> json(request, BookmarksResponse(list.sortedBy { it.positionMs }))
            path.size == 4 && m == "POST" -> {
                val book = books.firstOrNull { it.asin == asin } ?: return error(request, 404, "not_found", "unknown book")
                val b = body(request, CreateBookmarkRequest.serializer())
                if (b.positionMs < 0 || (b.note?.length ?: 0) > 280 || (b.clientId != null && !validClientId(b.clientId))) {
                    return error(request, 400, "invalid_request", "position_ms must be >= 0, note at most 280 characters, client_id 1-64 of A-Z a-z 0-9 - _")
                }
                // Idempotent with client_id: the EXISTING bookmark comes back, the new body is ignored.
                b.clientId?.let { id -> list.firstOrNull { it.clientId == id } }?.let { return json(request, it) }
                // The book need not be in the library: it is added as saved (updated_at 0, so any later write wins).
                if (asin !in library) library[asin] = LibraryEntry(book, LibraryStatus.SAVED, updatedAt = 0)
                val bookmark = Bookmark("bm-${UUID.randomUUID().toString().take(8)}", b.positionMs, b.note, Instant.now().toString(), b.clientId)
                list.add(bookmark)
                json(request, bookmark)
            }
            path.size == 5 && m == "DELETE" -> {
                list.removeAll { it.id == path[4] }
                noContent(request)
            }
            else -> error(request, 404, "not_found", "no such route")
        }
    }

    private fun resetLibrary() {
        val now = System.currentTimeMillis()
        books.take(3).forEachIndexed { i, b ->
            val status = listOf(LibraryStatus.LISTENING, LibraryStatus.SAVED, LibraryStatus.FINISHED)[i]
            val pos = listOf(0.35, 0.0, 1.0)[i]
            library[b.asin] = LibraryEntry(
                b, status, null, (b.runtimeMin * 60_000L * pos).toLong(), null, 0, 1.0, now - i * 3_600_000L,
                finishedAt = if (status == LibraryStatus.FINISHED) Instant.ofEpochMilli(now - i * 3_600_000L).toString() else null,
            )
        }
    }

    // --- covers and helpers -------------------------------------------------------------------------------------

    private fun cover(request: Request, asin: String): Response {
        val title = books.firstOrNull { it.asin == asin }?.title ?: "?"
        // Warm hues only (persimmon, mustard, rose, plum): the design has no blue or green.
        val hue = WARM_HUES[asin.hashCode().mod(WARM_HUES.size)]
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 110f; textAlign = Paint.Align.CENTER }
        val initials = title.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        canvas.drawText(initials, 150f, 190f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return respond(request, 200, out.toByteArray().toResponseBody("image/png".toMediaType()))
    }

    private fun <T> body(request: Request, serializer: kotlinx.serialization.KSerializer<T>): T {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return AudiobooksJson.decodeFromString(serializer, buffer.readUtf8())
    }

    private inline fun <reified T> json(request: Request, value: T, status: Int = 200, cacheControl: String = "no-store"): Response =
        respond(request, status, AudiobooksJson.encodeToString(kotlinx.serialization.serializer<T>(), value).toResponseBody(JSON), cacheControl = cacheControl)

    private fun text(request: Request, value: String) = respond(request, 200, value.toResponseBody("text/plain".toMediaType()))

    private fun noContent(request: Request) = respond(request, 204, "".toResponseBody(JSON))

    private fun error(request: Request, status: Int, code: String, message: String, retryAfter: Int? = null): Response =
        respond(request, status, AudiobooksJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiErrorDetail(code, message))).toResponseBody(JSON), retryAfter)

    private fun respond(request: Request, status: Int, body: okhttp3.ResponseBody, retryAfter: Int? = null, cacheControl: String = "no-store"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status in 200..299) "OK" else "Error")
            .header("Cache-Control", cacheControl)
            .apply { if (retryAfter != null) header("Retry-After", retryAfter.toString()) }
            .body(body)
            .build()

    companion object {
        const val HOST = "demo.audiobooks.test"
        const val DEMO_PASSWORD = "demo-password"
        /** 26 base32 characters like every real invite; the sign-in screen forgives case, spaces and dashes ("demo demo ...", O = 0). */
        const val DEMO_INVITE = "DEMO-DEMO-DEMO-DEMO-DEMO-DEMO-DE"
        const val BAD_KEY = "badkeybadkey"
        const val DEMO_ASIN = "B0DEMOSHORT"

        /** The local audio fixture (a host-side HTTP server with Range support, see app/scripts/audio_fixture.py), seen from the emulator. */
        const val FIXTURE_BASE = "http://10.0.2.2:8765"
        const val FIXTURE_PARTS = 3
        const val FIXTURE_PART_MS = 60_000L
        private const val DEMO_PARTS = 3
        private const val AUDIBLE_INTRO_MS = 2_000L
        private const val AUDIBLE_OUTRO_MS = 5_000L
        private fun validClientId(id: String) = id.length in 1..64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        private const val PREPARE_POLLS = 4
        private const val PAGE_SIZE = 12
        private val WARM_HUES = floatArrayOf(8f, 16f, 24f, 34f, 42f, 350f, 335f, 12f)
        private val JSON = "application/json".toMediaType()

        private fun sampleBooks(): List<Book> {
            val seeds = listOf(
                Triple("Pride and Prejudice", "Jane Austen", "Rosamund Pike"),
                Triple("Moby Dick", "Herman Melville", "William Hootkins"),
                Triple("The Hound of the Baskervilles", "Arthur Conan Doyle", "Stephen Fry"),
                Triple("Frankenstein", "Mary Shelley", "Dan Stevens"),
                Triple("Dracula", "Bram Stoker", "Alan Cumming"),
                Triple("The Time Machine", "H. G. Wells", "Bill Nighy"),
                Triple("Treasure Island", "Robert Louis Stevenson", "Alfred Molina"),
                Triple("Alice's Adventures in Wonderland", "Lewis Carroll", "Scarlett Johansson"),
                Triple("The Count of Monte Cristo", "Alexandre Dumas", "Bill Homewood"),
                Triple("A Study in Scarlet", "Arthur Conan Doyle", "Stephen Fry"),
                Triple("Great Expectations", "Charles Dickens", "Simon Vance"),
                Triple("Jane Eyre", "Charlotte Brontë", "Thandiwe Newton"),
                Triple("De Avonden", "Gerard Reve", "Hans Kesting"),
                Triple("Max Havelaar", "Multatuli", "Jan Meng"),
                Triple("Twenty Thousand Leagues Under the Sea", "Jules Verne", "Tim Curry"),
                Triple("The Picture of Dorian Gray", "Oscar Wilde", "Hugh Laurie"),
                Triple("The Adventures of Sherlock Holmes", "Arthur Conan Doyle", "Stephen Fry"),
                Triple("Wuthering Heights", "Emily Brontë", "Juliet Stevenson"),
                Triple("The War of the Worlds", "H. G. Wells", "Anthony Head"),
                Triple("Little Women", "Louisa May Alcott", "Laura Dern"),
                Triple("The Jungle Book", "Rudyard Kipling", "Jeremy Irons"),
                Triple("Anna Karenina", "Leo Tolstoy", "Maggie Gyllenhaal"),
                Triple("Crime and Punishment", "Fyodor Dostoevsky", "Constantine Gregory"),
                Triple("The Hobbit-like Tale (Demo)", "Demo Author", "Demo Narrator"),
            )
            val demo = Book(
                asin = DEMO_ASIN, title = "Demo: Three Short Parts", subtitle = "A 3-minute book for the player", authors = listOf("Demo Author"), narrators = listOf("Demo Narrator"),
                language = "en", runtimeMin = 3, coverUrl = "http://$HOST/covers/$DEMO_ASIN.png",
                description = "Three one-minute parts served by the local audio fixture: streaming, chapters, speed, sleep timer and bookmarks can be tried in minutes.",
                releaseYear = 2026, genres = listOf("Demo"),
            )
            val spanish = listOf(
                Triple("Don Quijote de la Mancha", "Miguel de Cervantes", "Demo Narrador"),
                Triple("Martín Fierro", "José Hernández", "Demo Narrador"),
            ).mapIndexed { i, (title, author, narrator) ->
                val asin = "B0DEMOES0${i + 1}"
                Book(
                    asin = asin, title = title, authors = listOf(author), narrators = listOf(narrator), language = "es", runtimeMin = 600 + i * 90,
                    coverUrl = "http://$HOST/covers/$asin.png",
                    description = "$title, de $author, leído por $narrator. Una entrada de demostración servida por el FakeBackend: el texto, la portada y las ediciones se generan.",
                    releaseYear = 1605 + i * 270, genres = listOf("Clásicos", "Ficción"),
                )
            }
            return listOf(demo) + seeds.mapIndexed { i, (title, author, narrator) ->
                val asin = "B0DEMO%04d".format(i)
                Book(
                    asin = asin, title = title, subtitle = if (i % 4 == 0) "Unabridged" else null, authors = listOf(author), narrators = listOf(narrator),
                    series = if (author == "Arthur Conan Doyle") "Sherlock Holmes" else null, seriesPosition = if (author == "Arthur Conan Doyle") (i % 5 + 1).toString() else null,
                    language = if (title in setOf("De Avonden", "Max Havelaar")) "nl" else "en", runtimeMin = 240 + i * 37,
                    coverUrl = "http://$HOST/covers/$asin.png",
                    description = "$title by $author, read by $narrator. A demo entry served by the in-process FakeBackend: the text, the cover and the releases are generated.",
                    releaseYear = 1850 + i * 5, genres = listOf("Classics", "Fiction"),
                )
            } + spanish
        }
    }
}
