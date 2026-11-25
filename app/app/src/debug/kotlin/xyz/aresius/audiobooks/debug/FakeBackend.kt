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
import xyz.aresius.audiobooks.core.network.Chapter
import xyz.aresius.audiobooks.core.network.Chapters
import xyz.aresius.audiobooks.core.network.CreateInviteRequest
import xyz.aresius.audiobooks.core.network.CreatedInvite
import xyz.aresius.audiobooks.core.network.DebridListResponse
import xyz.aresius.audiobooks.core.network.DebridStatus
import xyz.aresius.audiobooks.core.network.Device
import xyz.aresius.audiobooks.core.network.DevicesResponse
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
    private val debridKeys = HashMap<String, DebridStatus>()
    private val library = LinkedHashMap<String, LibraryEntry>()
    private val books = sampleBooks()

    init {
        invites[DEMO_INVITE] = Invite("inv-demo", "Demo invite", Instant.now().toString(), Instant.now().plusSeconds(72 * 3600).toString()) to DEMO_INVITE
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
        if (seg.firstOrNull() == "covers") return cover(request, seg.getOrNull(1)?.substringBefore('.').orEmpty())
        if (seg == listOf("healthz")) return text(request, "ok")
        if (seg.size < 2 || seg[0] != "v1") return error(request, 404, "not_found", "no such route")
        val path = seg.drop(1)
        if (path == listOf("version")) return json(request, VersionInfo("0.0.0-demo", 1, bootstrapped))
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
                if (invites.remove(b.inviteCode.trim()) == null) error(request, 401, "unauthorized", "unknown, used or expired invite")
                else signIn(request, b.displayName, b.deviceName, Roles.USER)
            }
            "link" -> {
                val b = body(request, LinkRequest.serializer())
                if (!linkCodes.remove(b.linkCode.trim())) error(request, 401, "unauthorized", "unknown, used or expired link code")
                else {
                    ensureUser()
                    signIn(request, me[currentUserId]!!.displayName, b.deviceName, me[currentUserId]!!.role, existing = currentUserId)
                }
            }
            "refresh" -> json(request, session(me[currentUserId] ?: defaultUser()))
            else -> error(request, 404, "not_found", "no such route")
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

    private fun defaultUser(): Me = Me("u-demo", "Demo Admin", Roles.ADMIN, Settings()).also { me[it.id] = it; currentUserId = it.id; bootstrapped = true }

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
        path == listOf("me", "devices") && m == "GET" -> json(request, DevicesResponse(devices.toList()))
        path == listOf("me", "devices", "link-code") && m == "POST" -> {
            val code = "LINK-${UUID.randomUUID().toString().take(6).uppercase()}"
            linkCodes.add(code)
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
                val code = "INV-${UUID.randomUUID().toString().take(8).uppercase()}"
                val invite = Invite("inv-${UUID.randomUUID().toString().take(6)}", b.note, Instant.now().toString(), Instant.now().plusSeconds((b.expiresInHours ?: 72) * 3600L).toString())
                invites[code] = invite to code
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
        val hits = books.filter { b -> listOf(b.title, b.authors.joinToString(), b.narrators.joinToString(), b.series.orEmpty()).any { it.contains(q, ignoreCase = true) } }
        return json(request, BookListResponse(hits))
    }

    private fun discover(request: Request): Response {
        val page = request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val from = page * PAGE_SIZE
        return json(request, BookListResponse(if (from >= books.size) emptyList() else books.subList(from, minOf(books.size, from + PAGE_SIZE))))
    }

    private fun bookRoutes(request: Request, path: List<String>): Response {
        val book = books.firstOrNull { it.asin == path.getOrNull(1) } ?: return error(request, 404, "not_found", "unknown book")
        return when (path.getOrNull(2)) {
            null -> json(request, BookDetail(book, Instant.now().minusSeconds(600).toString(), releasesFor(book)))
            "chapters" -> json(request, Chapters(book.asin, book.runtimeMin * 60_000L, (0 until 8).map { Chapter("Chapter ${it + 1}", it * book.runtimeMin * 7_500L, book.runtimeMin * 7_500L) }))
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
        val files = (1..6).map { TorrentFile(it - 1, "${book.title}/Part ${it}.$format", size / 6) }
        return ReleaseView(
            infoHash = hash, title = "${book.authors.firstOrNull().orEmpty()} - ${book.title} [${format.uppercase()} ${kbps}kbps]", source = "demo", size = size, seeders = seeders,
            format = format, bitrateKbps = kbps, narrator = book.narrators.firstOrNull(), language = book.language, score = 0.9 - kbps / 1000.0, collection = false,
            magnet = "magnet:?xt=urn:btih:$hash", files = files, torrentFiles = emptyList(), cached = cached, usable = usable,
        )
    }

    private fun releaseRoutes(request: Request, m: String, path: List<String>): Response = when {
        path.getOrNull(2) == "prepare" && m == "POST" ->
            if (debridKeys.isEmpty()) error(request, 403, "no_debrid_key", "add a debrid key first") else json(request, Prepare(Prepare.PREPARING, 40))
        else -> error(request, 404, "not_found", "not implemented by the demo backend")
    }

    // --- library ----------------------------------------------------------------------------------------------

    private fun libraryRoutes(request: Request, m: String, path: List<String>): Response {
        if (path.size == 2 && m == "GET") {
            val status = request.url.queryParameter("status")
            return json(request, LibraryResponse(library.values.filter { status == null || it.status == status }.sortedByDescending { it.updatedAt }))
        }
        val asin = path.getOrNull(2) ?: return error(request, 404, "not_found", "no such route")
        if (path.size > 3) return error(request, 404, "not_found", "bookmarks are not part of the demo backend")
        return when (m) {
            "GET" -> library[asin]?.let { json(request, it) } ?: error(request, 404, "not_found", "not in the library")
            "PUT" -> {
                val book = books.firstOrNull { it.asin == asin } ?: return error(request, 404, "not_found", "unknown book")
                val b = body(request, LibraryEntryWrite.serializer())
                val old = library[asin]
                val entry = if (old != null && old.updatedAt > b.updatedAt) old
                else LibraryEntry(book, b.status, b.releaseHash, b.positionMs ?: old?.positionMs ?: 0, b.filePath, b.filePositionMs ?: 0, b.speed ?: 1.0, b.updatedAt)
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

    private fun resetLibrary() {
        val now = System.currentTimeMillis()
        books.take(3).forEachIndexed { i, b ->
            val status = listOf(LibraryStatus.LISTENING, LibraryStatus.SAVED, LibraryStatus.FINISHED)[i]
            val pos = listOf(0.35, 0.0, 1.0)[i]
            library[b.asin] = LibraryEntry(b, status, null, (b.runtimeMin * 60_000L * pos).toLong(), null, 0, 1.0, now - i * 3_600_000L)
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

    private inline fun <reified T> json(request: Request, value: T): Response =
        respond(request, 200, AudiobooksJson.encodeToString(kotlinx.serialization.serializer<T>(), value).toResponseBody(JSON))

    private fun text(request: Request, value: String) = respond(request, 200, value.toResponseBody("text/plain".toMediaType()))

    private fun noContent(request: Request) = respond(request, 204, "".toResponseBody(JSON))

    private fun error(request: Request, status: Int, code: String, message: String, retryAfter: Int? = null): Response =
        respond(request, status, AudiobooksJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiErrorDetail(code, message))).toResponseBody(JSON), retryAfter)

    private fun respond(request: Request, status: Int, body: okhttp3.ResponseBody, retryAfter: Int? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status in 200..299) "OK" else "Error")
            .header("Cache-Control", "no-store")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter.toString()) }
            .body(body)
            .build()

    companion object {
        const val HOST = "demo.audiobooks.test"
        const val DEMO_PASSWORD = "demo-password"
        const val DEMO_INVITE = "DEMO-INVITE"
        const val BAD_KEY = "badkeybadkey"
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
            return seeds.mapIndexed { i, (title, author, narrator) ->
                val asin = "B0DEMO%04d".format(i)
                Book(
                    asin = asin, title = title, subtitle = if (i % 4 == 0) "Unabridged" else null, authors = listOf(author), narrators = listOf(narrator),
                    series = if (author == "Arthur Conan Doyle") "Sherlock Holmes" else null, seriesPosition = if (author == "Arthur Conan Doyle") (i % 5 + 1).toString() else null,
                    language = if (title in setOf("De Avonden", "Max Havelaar")) "nl" else "en", runtimeMin = 240 + i * 37,
                    coverUrl = "http://$HOST/covers/$asin.png",
                    description = "$title by $author, read by $narrator. A demo entry served by the in-process FakeBackend: the text, the cover and the releases are generated.",
                    releaseYear = 1850 + i * 5, genres = listOf("Classics", "Fiction"),
                )
            }
        }
    }
}
