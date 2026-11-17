package app.ferry.ui.tv

import android.content.Context
import app.ferry.AppGraph
import app.ferry.core.model.OutFile
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Call

/**
 * Photo thumbnails on TV home (design §5.1 [A11], plan §4.9): the PC serves only originals (5–15 MB), so a card
 * loads its photo only while it is composed (on screen), at most 2 at a time, decoded at about 320 px and kept in
 * the memory cache under `name|size|mtime` (the 5 s refresh never reloads an unchanged file).
 * The disk cache holds what was **downloaded** (the originals), not the 320 px pictures, so its 100 MB is only the
 * last 7–15 photos (audit O12, deferred: real thumbnails would have to come from the PC, a new feature).
 * Requests go through the **shared** OkHttp client [PR19]: bound to the Wi-Fi/Ethernet network [A9] and carrying
 * `X-Ferry-Device` [CP15]; it is looked up on every call because it is rebuilt when the network changes.
 */
object TvThumbnails {
    private const val DISK_BYTES = 100L * 1024 * 1024
    const val SIZE_PX = 320

    @Volatile private var loader: ImageLoader? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun loader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(ctx.applicationContext)
            .callFactory(Call.Factory { request -> AppGraph.httpClient().newCall(request) })
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(2))
            .respectCacheHeaders(false) // the PC sends no cache headers; the key below decides
            .memoryCache { MemoryCache.Builder(ctx.applicationContext).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder().directory(ctx.applicationContext.cacheDir.resolve("tv-thumbs")).maxSizeBytes(DISK_BYTES).build()
            }
            .build()
            .also { loader = it }
    }

    fun key(f: OutFile): String = "${f.name}|${f.size}|${f.mtime ?: 0}"

    fun request(ctx: Context, url: String, f: OutFile): ImageRequest = ImageRequest.Builder(ctx)
        .data(url)
        .size(SIZE_PX)
        .diskCacheKey(key(f))
        .memoryCacheKey(key(f))
        .build()
}
