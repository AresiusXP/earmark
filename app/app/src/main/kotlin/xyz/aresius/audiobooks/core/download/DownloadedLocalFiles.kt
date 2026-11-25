package xyz.aresius.audiobooks.core.download

import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.aresius.audiobooks.core.auth.SessionStore

/**
 * [LocalFiles] over the downloads database. [localFile] answers from an in-memory index of the COMPLETE rows of the signed-in user
 * (never a `.part` file, never another user's file) and re-checks the file on disk (exists, size as recorded).
 */
class DownloadedLocalFiles(
    dao: DownloadsDao,
    sessions: SessionStore,
    private val storage: StorageManager,
    scope: CoroutineScope,
) : LocalFiles {
    private class Index(val files: Map<String, DownloadFileEntity>, val asins: Set<String>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val index: StateFlow<Index?> = sessions.session.map { it?.userId }.distinctUntilChanged().flatMapLatest { user ->
        if (user == null) flowOf(Index(emptyMap(), emptySet())) else dao.observeCompleteFiles(user).map { rows -> build(rows) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun build(rows: List<DownloadFileEntity>) = Index(rows.associateBy { key(it.asin, it.releaseHash, it.path) }, rows.map { it.asin }.toSet())

    private fun key(asin: String, hash: String, path: String) = "$asin|$hash|${PathSafety.matchKey(path)}"

    override fun localFile(asin: String, releaseHash: String, path: String): File? {
        val idx = index.value ?: runBlocking { withTimeoutOrNull(2.seconds) { index.filterNotNull().first() } } ?: return null
        val row = idx.files[key(asin, releaseHash, path)] ?: return null
        val dir = storage.bookDir(asin, releaseHash) ?: return null
        val file = PathSafety.resolve(dir, row.localPath) ?: return null
        return file.takeIf { it.isFile && it.length() == row.size }
    }

    override fun hasDownloads(asin: String): Flow<Boolean> = index.map { it?.asins?.contains(asin) ?: false }.distinctUntilChanged()
}
