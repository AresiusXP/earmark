package xyz.aresius.audiobooks.testutil

/** Sample bodies in the shapes of api/openapi.yaml. */
object Samples {
    const val ME_ADMIN = """{"id":"u1","display_name":"Ada","role":"admin","settings":{"region":"us","languages":["en"],"prefer_format":"","preferred_provider":null,"source_mode":"auto","p2p_enabled":false}}"""
    const val ME_USER = """{"id":"u2","display_name":"Bob","role":"user","settings":{"region":"nl","languages":["en","nl"],"prefer_format":"m4b","preferred_provider":"torbox","source_mode":"debrid","p2p_enabled":false}}"""

    fun session(me: String = ME_ADMIN, access: String = "access-new", refresh: String = "refresh-new") =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"token_type":"Bearer","user":$me}"""

    fun book(asin: String = "B000000001", title: String = "Pride and Prejudice") =
        """{"asin":"$asin","title":"$title","authors":["Jane Austen"],"narrators":["Rosamund Pike"],"language":"english","runtime_min":660,"cover_url":"https://img.example/$asin.jpg"}"""

    fun books(vararg asins: String) = """{"books":[${asins.joinToString(",") { book(it, "Book $it") }}]}"""

    fun release(hash: String = "a".repeat(40), usable: String = """{"debrid":["torbox"],"p2p":false}""", cached: String = """{"torbox":true}""") =
        """{"info_hash":"$hash","title":"Jane Austen - P&P [M4B]","source":"knaben","size":440401920,"seeders":42,"format":"m4b","bitrate_kbps":64,"score":0.9,"collection":false,"magnet":"magnet:?xt=urn:btih:$hash","files":[],"torrent_files":[],"cached":$cached,"usable":$usable}"""

    fun bookDetail(asin: String = "B000000001", releases: String = release()) =
        """{"book":${book(asin)},"searched_at":"2026-11-25T10:00:00Z","releases":[$releases]}"""

    fun libraryEntry(asin: String, status: String = "listening", updated: Long = 1000, position: Long = 600000) =
        """{"book":${book(asin, "Book $asin")},"status":"$status","release_hash":null,"position_ms":$position,"file_path":null,"file_position_ms":0,"speed":1.0,"updated_at":$updated}"""

    fun debrid(provider: String, configured: Boolean = true, status: String = "ok") =
        if (configured) """{"provider":"$provider","configured":true,"status":"$status","validated_at":"2026-11-25T10:00:00Z","last_error":null}"""
        else """{"provider":"$provider","configured":false}"""
}
