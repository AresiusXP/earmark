package xyz.aresius.audiobooks.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Cover art for the notification and lock screen through the app's image loader (same HTTP client, cache and debug backend as the screens). */
class CoilBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return Futures.immediateFailedFuture(IOException("undecodable image"))
        return Futures.immediateFuture(bitmap)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            val request = ImageRequest.Builder(context).data(uri.toString()).allowHardware(false).size(Size(512, 512)).build()
            val bitmap = runCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull()
            if (bitmap != null) future.set(bitmap) else future.setException(IOException("no cover"))
        }
        return future
    }
}
