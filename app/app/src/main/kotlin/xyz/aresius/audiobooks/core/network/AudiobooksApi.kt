package xyz.aresius.audiobooks.core.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * One method per path of api/openapi.yaml (including the ones the server does not implement yet). Non-2xx answers
 * throw [retrofit2.HttpException]; [safeApiCall] turns them into [ApiError]. Paths have no leading slash so the
 * base URL's path (a self-hosted instance behind a prefix) is kept.
 */
interface AudiobooksApi {
    // meta
    @GET("healthz") suspend fun healthz(): ResponseBody
    @GET("v1/version") suspend fun getVersion(): VersionInfo
    @GET("v1/app/update") suspend fun getAppUpdate(): AppUpdateDto
    @Streaming @GET("v1/app/update/apk") suspend fun getAppApk(): ResponseBody

    // auth
    @POST("v1/auth/bootstrap") suspend fun bootstrap(@Body body: BootstrapRequest): SessionResponse
    @POST("v1/auth/join") suspend fun join(@Body body: JoinRequest): SessionResponse
    @POST("v1/auth/link") suspend fun linkDevice(@Body body: LinkRequest): SessionResponse
    @GET("v1/auth/google/nonce") suspend fun googleNonce(): GoogleNonceResponse
    @POST("v1/auth/google") suspend fun googleSignIn(@Body body: GoogleSignInRequest): SessionResponse
    @POST("v1/auth/google/join") suspend fun googleJoin(@Body body: GoogleJoinRequest): SessionResponse
    @POST("v1/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): SessionResponse

    // me
    @GET("v1/me") suspend fun getMe(): Me
    @PATCH("v1/me") suspend fun updateMe(@Body body: UpdateMeRequest): Me
    @POST("v1/me/google") suspend fun linkGoogle(@Body body: GoogleLinkRequest): Me
    @DELETE("v1/me/google") suspend fun unlinkGoogle()
    @GET("v1/me/devices") suspend fun listDevices(): DevicesResponse
    @DELETE("v1/me/devices/{deviceId}") suspend fun revokeDevice(@Path("deviceId") deviceId: String)
    @POST("v1/me/devices/link-code") suspend fun createLinkCode(): LinkCodeResponse
    @GET("v1/me/debrid") suspend fun listDebrid(): DebridListResponse
    @PUT("v1/me/debrid/{provider}") suspend fun putDebridKey(@Path("provider") provider: String, @Body body: PutDebridKeyRequest): DebridStatus
    @POST("v1/me/debrid/{provider}") suspend fun recheckDebridKey(@Path("provider") provider: String): DebridStatus
    @DELETE("v1/me/debrid/{provider}") suspend fun deleteDebridKey(@Path("provider") provider: String)

    // admin
    @GET("v1/admin/users") suspend fun adminListUsers(): AdminUsersResponse
    @PATCH("v1/admin/users/{userId}") suspend fun adminUpdateUser(@Path("userId") userId: String, @Body body: AdminUpdateUserRequest): AdminUser
    @GET("v1/admin/invites") suspend fun adminListInvites(): InvitesResponse
    @POST("v1/admin/invites") suspend fun adminCreateInvite(@Body body: CreateInviteRequest): CreatedInvite
    @DELETE("v1/admin/invites/{inviteId}") suspend fun adminRevokeInvite(@Path("inviteId") inviteId: String)

    // catalog
    @GET("v1/search") suspend fun search(@Query("q") query: String): BookListResponse
    @GET("v1/discover") suspend fun discover(@Query("page") page: Int = 0): BookListResponse
    @GET("v1/books/{asin}") suspend fun getBook(@Path("asin") asin: String): BookDetail
    @GET("v1/books/{asin}/chapters") suspend fun getChapters(@Path("asin") asin: String): Chapters

    // releases
    @POST("v1/releases/{hash}/prepare") suspend fun prepareRelease(@Path("hash") hash: String, @Body body: PrepareRequest): Prepare

    /**
     * `302` (the CDN link in `Location`, never stored) or `202` with a [Prepare] body. The client does not follow redirects, so the
     * caller reads the status from the [Response]; see `ReleaseRepository.downloadLink`.
     */
    @GET("v1/releases/{hash}/files/{index}/download")
    suspend fun downloadFile(@Path("hash") hash: String, @Path("index") index: Int, @Query("provider") provider: String): Response<ResponseBody>

    @POST("v1/releases/{hash}/torrent-files") suspend fun reportTorrentFiles(@Path("hash") hash: String, @Body body: TorrentFilesRequest): TorrentFilesResponse

    /** Durations a phone learned by decoding (first writer wins on the server, see `DurationReporter`). */
    @POST("v1/releases/{hash}/durations") suspend fun reportDurations(@Path("hash") hash: String, @Body body: DurationsRequest): DurationsResponse

    // library
    @GET("v1/me/library") suspend fun listLibrary(@Query("status") status: String? = null): LibraryResponse
    @GET("v1/me/library/{asin}") suspend fun getLibraryEntry(@Path("asin") asin: String): LibraryEntry
    @PUT("v1/me/library/{asin}") suspend fun putLibraryEntry(@Path("asin") asin: String, @Body body: LibraryEntryWrite): LibraryEntry
    @DELETE("v1/me/library/{asin}") suspend fun deleteLibraryEntry(@Path("asin") asin: String)
    @GET("v1/me/library/{asin}/bookmarks") suspend fun listBookmarks(@Path("asin") asin: String): BookmarksResponse
    @POST("v1/me/library/{asin}/bookmarks") suspend fun createBookmark(@Path("asin") asin: String, @Body body: CreateBookmarkRequest): Bookmark
    @DELETE("v1/me/library/{asin}/bookmarks/{bookmarkId}") suspend fun deleteBookmark(@Path("asin") asin: String, @Path("bookmarkId") bookmarkId: String)
}
