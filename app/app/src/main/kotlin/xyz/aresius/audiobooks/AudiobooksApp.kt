package xyz.aresius.audiobooks

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath
import xyz.aresius.audiobooks.core.data.SessionCleanup
import xyz.aresius.audiobooks.core.network.HttpClientFactory
import xyz.aresius.audiobooks.core.update.UpdateStarter

@HiltAndroidApp
class AudiobooksApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var sessionCleanup: SessionCleanup
    @Inject lateinit var updateStarter: UpdateStarter
    @Inject lateinit var httpClients: HttpClientFactory
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        sessionCleanup.start(appScope)
        updateStarter.start()
    }

    /** Images: memory 15 % of the app heap, disk 100 MB, through the app's HTTP client (timeouts; the debug FakeBackend serves its covers). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClients.plain() })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
}
