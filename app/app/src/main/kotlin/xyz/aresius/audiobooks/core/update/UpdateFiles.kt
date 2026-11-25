package xyz.aresius.audiobooks.core.update

import android.content.Context
import java.io.File

/** The one directory the updater writes to: `cacheDir/updates`. At most one version is kept. */
class UpdateFiles(val dir: File) {
    fun apk(versionName: String) = File(dir, "audiobooks-$versionName.apk")
    fun part(versionName: String) = File(dir, "audiobooks-$versionName.apk.part")

    fun ensureDir(): File = dir.also { it.mkdirs() }

    /** Deletes every file of other versions (all files when [keepVersionName] is null). */
    fun cleanupExcept(keepVersionName: String?) {
        val keep = keepVersionName?.let { setOf(apk(it).name, part(it).name) }.orEmpty()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    fun clearAll() = cleanupExcept(null)

    fun deleteVersion(versionName: String) {
        apk(versionName).delete()
        part(versionName).delete()
    }
}

fun updatesDir(context: Context): File = File(context.cacheDir, "updates")
