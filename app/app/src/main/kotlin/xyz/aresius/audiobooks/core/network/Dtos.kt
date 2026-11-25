package xyz.aresius.audiobooks.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire models of api/openapi.yaml, written by hand. Enum-like fields stay strings so an unknown value from a newer server
 * never fails the whole response; [Provider] and the constants below give them a typed face. Unknown keys are ignored.
 */
val AudiobooksJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

enum class Provider(val wire: String) {
    TorBox("torbox"),
    RealDebrid("realdebrid"),
    ;

    companion object {
        fun fromWire(value: String?): Provider? = entries.firstOrNull { it.wire == value }
    }
}

object LibraryStatus {
    const val SAVED = "saved"
    const val LISTENING = "listening"
    const val FINISHED = "finished"
    val ALL = listOf(SAVED, LISTENING, FINISHED)
}

object Roles {
    const val ADMIN = "admin"
    const val USER = "user"
}

object SourceMode {
    const val AUTO = "auto"
    const val DEBRID = "debrid"
    const val P2P = "p2p"
    val ALL = listOf(AUTO, DEBRID, P2P)
}

val SETTINGS_REGIONS = listOf("us", "uk", "de", "fr", "it", "es", "ca", "au", "in", "nl")
val SETTINGS_LANGUAGES = listOf("en", "nl")
val SETTINGS_FORMATS = listOf("", "m4b", "mp3")

// --- errors -----------------------------------------------------------------------------------------------------

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(
    val code: String = "",
    val message: String? = null,
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
)

// --- meta -------------------------------------------------------------------------------------------------------

@Serializable
data class VersionInfo(
    val version: String = "",
    @SerialName("api_version") val apiVersion: Int = 0,
    val bootstrapped: Boolean = false,
    @SerialName("google_enabled") val googleEnabled: Boolean = false,
)

@Serializable
data class AppUpdateDto(
    val available: Boolean = false,
    val version: String = "",
    @SerialName("version_code") val versionCode: Long = 0,
    val size: Long = 0,
    val sha256: String = "",
    val url: String = "",
)

// --- auth -------------------------------------------------------------------------------------------------------

@Serializable
data class BootstrapRequest(
    @SerialName("instance_password") val instancePassword: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class JoinRequest(
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LinkRequest(
    @SerialName("link_code") val linkCode: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class GoogleNonceResponse(val nonce: String, @SerialName("expires_at") val expiresAt: String = "") {
    // A nonce is single use and short lived, but still not something to print.
    override fun toString() = "GoogleNonceResponse(expiresAt=$expiresAt)"
}

@Serializable
data class GoogleSignInRequest(
    @SerialName("id_token") val idToken: String,
    @SerialName("device_name") val deviceName: String,
) {
    override fun toString() = "GoogleSignInRequest(deviceName=$deviceName)"
}

@Serializable
data class GoogleJoinRequest(
    @SerialName("id_token") val idToken: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("device_name") val deviceName: String,
) {
    override fun toString() = "GoogleJoinRequest(deviceName=$deviceName)"
}

@Serializable
data class GoogleLinkRequest(@SerialName("id_token") val idToken: String) {
    override fun toString() = "GoogleLinkRequest()"
}

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class SessionResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val user: Me,
)

// --- me ---------------------------------------------------------------------------------------------------------

@Serializable
data class Settings(
    val region: String = "us",
    val languages: List<String> = listOf("en"),
    @SerialName("prefer_format") val preferFormat: String = "",
    @SerialName("preferred_provider") val preferredProvider: String? = null,
    @SerialName("source_mode") val sourceMode: String = SourceMode.AUTO,
    @SerialName("p2p_enabled") val p2pEnabled: Boolean = false,
)

/**
 * What a `PATCH /v1/me` does with `preferred_provider`: [Set] a provider, or [Clear] it with an explicit JSON `null` (an absent field
 * means "unchanged", so `explicitNulls = false` alone cannot express a clear).
 */
@Serializable(with = ProviderChoiceSerializer::class)
sealed interface ProviderChoice {
    data class Set(val provider: Provider) : ProviderChoice
    data object Clear : ProviderChoice
}

object ProviderChoiceSerializer : KSerializer<ProviderChoice> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ProviderChoice", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProviderChoice) {
        val json = encoder as? JsonEncoder ?: error("ProviderChoice encodes to JSON only")
        json.encodeJsonElement(
            when (value) {
                is ProviderChoice.Set -> JsonPrimitive(value.provider.wire)
                ProviderChoice.Clear -> JsonNull
            },
        )
    }

    override fun deserialize(decoder: Decoder): ProviderChoice {
        val json = decoder as? JsonDecoder ?: error("ProviderChoice decodes from JSON only")
        val text = (json.decodeJsonElement() as? JsonPrimitive)?.contentOrNull
        return Provider.fromWire(text)?.let { ProviderChoice.Set(it) } ?: ProviderChoice.Clear
    }
}

/** Only the non-null fields are sent (`explicitNulls = false`); [preferredProvider] is the one field that can also be cleared with `null`. */
@Serializable
data class SettingsPatch(
    val region: String? = null,
    val languages: List<String>? = null,
    @SerialName("prefer_format") val preferFormat: String? = null,
    @SerialName("source_mode") val sourceMode: String? = null,
    @SerialName("p2p_enabled") val p2pEnabled: Boolean? = null,
    @SerialName("preferred_provider") val preferredProvider: ProviderChoice? = null,
)

/** `Me.google`: whether a Google account is linked and (for the user itself) its email. */
@Serializable
data class GoogleLink(val linked: Boolean = false, val email: String? = null)

@Serializable
data class Me(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val role: String = Roles.USER,
    val settings: Settings = Settings(),
    val google: GoogleLink = GoogleLink(),
) {
    val isAdmin: Boolean get() = role == Roles.ADMIN
}

@Serializable
data class UpdateMeRequest(
    @SerialName("display_name") val displayName: String? = null,
    val settings: SettingsPatch? = null,
)

@Serializable
data class Device(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class DevicesResponse(val devices: List<Device> = emptyList())

@Serializable
data class LinkCodeResponse(
    @SerialName("link_code") val linkCode: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class DebridStatus(
    val provider: String,
    val configured: Boolean = false,
    val status: String? = null,
    @SerialName("validated_at") val validatedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("shared_with_others") val sharedWithOthers: Boolean = false,
) {
    val isOk: Boolean get() = configured && status == "ok"
}

@Serializable
data class DebridListResponse(val providers: List<DebridStatus> = emptyList())

@Serializable
data class PutDebridKeyRequest(@SerialName("api_key") val apiKey: String)

// --- admin ------------------------------------------------------------------------------------------------------

@Serializable
data class AdminUser(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val role: String = Roles.USER,
    val disabled: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("device_count") val deviceCount: Int = 0,
    val debrid: Map<String, Boolean> = emptyMap(),
    @SerialName("google_linked") val googleLinked: Boolean = false,
    @SerialName("google_email") val googleEmail: String? = null,
) {
    val isAdmin: Boolean get() = role == Roles.ADMIN
}

@Serializable
data class AdminUsersResponse(val users: List<AdminUser> = emptyList())

@Serializable
data class AdminUpdateUserRequest(val disabled: Boolean? = null)

@Serializable
data class Invite(
    val id: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

/** `POST /v1/admin/invites` answer: the invite plus its one-time code (never returned again). */
@Serializable
data class CreatedInvite(
    val id: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    val code: String,
) {
    fun toInvite() = Invite(id, note, createdAt, expiresAt)

    // Keep the one-time code out of logs.
    override fun toString() = "CreatedInvite(id=$id)"
}

@Serializable
data class InvitesResponse(val invites: List<Invite> = emptyList())

@Serializable
data class CreateInviteRequest(
    val note: String? = null,
    @SerialName("expires_in_hours") val expiresInHours: Int? = null,
)

// --- catalog ----------------------------------------------------------------------------------------------------

@Serializable
data class Book(
    val asin: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: String? = null,
    @SerialName("series_position") val seriesPosition: String? = null,
    val language: String = "",
    @SerialName("runtime_min") val runtimeMin: Int = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class BookListResponse(val books: List<Book> = emptyList())

@Serializable
data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long = 0,
    /** Audio length reported by a phone; only on the files of a book page, only when known (contract v1.1). Read only. */
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class Usable(
    val debrid: List<String> = emptyList(),
    val p2p: Boolean = false,
)

@Serializable
data class ReleaseView(
    @SerialName("info_hash") val infoHash: String,
    val title: String = "",
    val source: String = "",
    val size: Long = 0,
    val seeders: Int = 0,
    val format: String? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    val narrator: String? = null,
    val language: String? = null,
    val score: Double = 0.0,
    val collection: Boolean = false,
    val magnet: String = "",
    val files: List<TorrentFile> = emptyList(),
    @SerialName("torrent_files") val torrentFiles: List<TorrentFile> = emptyList(),
    val cached: Map<String, Boolean> = emptyMap(),
    val usable: Usable = Usable(),
)

@Serializable
data class BookDetail(
    val book: Book,
    @SerialName("searched_at") val searchedAt: String? = null,
    val releases: List<ReleaseView> = emptyList(),
)

@Serializable
data class Chapter(
    val title: String,
    @SerialName("start_ms") val startMs: Long = 0,
    @SerialName("length_ms") val lengthMs: Long = 0,
)

@Serializable
data class Chapters(
    val asin: String = "",
    @SerialName("runtime_ms") val runtimeMs: Long = 0,
    val chapters: List<Chapter> = emptyList(),
    /** Audible branding at the start / end of the Audible edition (marks are measured with it included); null when unknown. */
    @SerialName("intro_ms") val introMs: Long? = null,
    @SerialName("outro_ms") val outroMs: Long? = null,
)

// --- releases ---------------------------------------------------------------------------------------------------

@Serializable
data class PrepareRequest(val provider: String, val magnet: String? = null)

@Serializable
data class Prepare(
    val state: String,
    val progress: Int? = null,
    val reason: String? = null,
    val files: List<TorrentFile> = emptyList(),
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
) {
    companion object {
        const val READY = "ready"
        const val PREPARING = "preparing"
        const val FAILED = "failed"
    }
}

/** Requests carry no `duration_ms`: the field is read only on a torrent file and `explicitNulls = false` leaves it out. */
@Serializable
data class TorrentFilesRequest(val files: List<TorrentFile>)

@Serializable
data class DurationEntry(
    val path: String,
    @SerialName("duration_ms") val durationMs: Long,
)

@Serializable
data class DurationsRequest(val durations: List<DurationEntry>)

/** [durations] are the STORED values (first writer wins) of the accepted entries; [ignored] counts the entries in no known audio file list. */
@Serializable
data class DurationsResponse(
    val durations: List<DurationEntry> = emptyList(),
    val ignored: Int = 0,
)

@Serializable
data class TorrentFilesResponse(@SerialName("torrent_files") val torrentFiles: List<TorrentFile> = emptyList())

// --- library ----------------------------------------------------------------------------------------------------

@Serializable
data class LibraryEntry(
    val book: Book,
    val status: String = LibraryStatus.SAVED,
    @SerialName("release_hash") val releaseHash: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_position_ms") val filePositionMs: Long = 0,
    val speed: Double = 1.0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** Server time (ISO-8601) at which the status became `finished`; null otherwise (and for answers of an older server). Read only. */
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class LibraryResponse(val entries: List<LibraryEntry> = emptyList())

@Serializable
data class LibraryEntryWrite(
    val status: String,
    @SerialName("release_hash") val releaseHash: String? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_position_ms") val filePositionMs: Long? = null,
    val speed: Double? = null,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class Bookmark(
    val id: String,
    @SerialName("position_ms") val positionMs: Long = 0,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("client_id") val clientId: String? = null,
)

@Serializable
data class BookmarksResponse(val bookmarks: List<Bookmark> = emptyList())

@Serializable
data class CreateBookmarkRequest(
    @SerialName("position_ms") val positionMs: Long,
    val note: String? = null,
    /** A UUID made once per bookmark: repeating the POST with it returns the existing bookmark instead of creating another. */
    @SerialName("client_id") val clientId: String? = null,
)
