package app.ferry.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import app.ferry.AppGraph
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import java.io.IOException

/**
 * The picker's MediaStore access (plan §4.7, design §4.1.2 [A17]): images and videos are queried **separately**
 * and merged newest first by `DATE_MODIFIED` [CP11], 500 rows per page — a `Bundle` limit/offset on API 30+, a
 * `LIMIT … OFFSET …` sort string on 25–29 — with the self-protecting [PagedStream] whatever the platform does
 * (a query that rejects the paging falls back to one unpaged query). Blocking: call on `Dispatchers.IO`.
 */
class MediaRepo(ctx: Context) {
    private val cr: ContentResolver = ctx.contentResolver

    fun load(mode: PickerMode, keepGoing: () -> Boolean, publish: (List<MediaItem>) -> Unit) {
        val streams = ArrayList<PagedStream>()
        if (mode.images) streams.add(PagedStream(PickerLogic.PAGE) { o, l -> page(false, o, l) })
        if (mode.videos) streams.add(PagedStream(PickerLogic.PAGE) { o, l -> page(true, o, l) })
        PickerLogic.loadMerged(streams, keepGoing, publish = publish)
    }

    private fun page(video: Boolean, offset: Int, limit: Int): List<MediaItem> {
        val uri = collection(video)
        val proj = projection(video)
        val c: Cursor? = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val args = Bundle().apply {
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns._ID))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                }
                cr.query(uri, proj, args, null)
            } else {
                cr.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC LIMIT $limit OFFSET $offset")
            }
        } catch (e: Exception) {
            if (e is SecurityException) throw e
            // The platform refused the paging: read everything in one go (PagedStream stops on the repeat).
            AppGraph.log("media", "paged query refused ($e); one unpaged query")
            cr.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
        }
        return c?.use { read(it, video) } ?: emptyList()
    }

    private fun read(c: Cursor, video: Boolean): List<MediaItem> {
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val bucket = c.getColumnIndex(BUCKET_ID)
        val bucketName = c.getColumnIndex(BUCKET_NAME)
        val duration = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
        val out = ArrayList<MediaItem>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            out.add(
                MediaItem(
                    id = c.getLong(id),
                    isVideo = video,
                    name = c.getString(name) ?: "file",
                    size = if (c.isNull(size)) 0 else c.getLong(size),
                    dateModifiedS = if (c.isNull(date)) 0 else c.getLong(date),
                    bucketId = if (bucket < 0 || c.isNull(bucket)) null else c.getString(bucket),
                    bucketName = if (bucketName < 0) null else c.getString(bucketName),
                    durationMs = if (duration < 0 || c.isNull(duration)) 0 else c.getLong(duration),
                ),
            )
        }
        return out
    }

    companion object {
        // BUCKET_* exist on both tables on every API level 25–35 [CP11] (the column names of Images/Video.*Columns).
        private const val BUCKET_ID = "bucket_id"
        private const val BUCKET_NAME = "bucket_display_name"

        fun collection(video: Boolean): Uri =
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        private fun projection(video: Boolean): Array<String> {
            val p = arrayListOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, BUCKET_ID, BUCKET_NAME, MediaStore.MediaColumns.MIME_TYPE,
            )
            if (video) p.add(MediaStore.Video.VideoColumns.DURATION)
            return p.toTypedArray()
        }

        /** Coil loader for picker thumbnails (memory key `thumb:<id>`; no network, no disk cache needed). */
        fun thumbnailLoader(ctx: Context): ImageLoader = ImageLoader.Builder(ctx)
            .components {
                add(MediaThumbKeyer)
                add(MediaThumbFetcher.Factory())
            }
            .build()
    }
}

/** A thumbnail request for one MediaStore row. */
data class MediaThumb(val id: Long, val isVideo: Boolean)

object MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "thumb:${data.id}"
}

/**
 * Coil fetcher (plan §4.7): the platform's thumbnails, no video library — `loadThumbnail(uri, 320×320)` on 29+,
 * `MediaStore.*.Thumbnails.getThumbnail(MINI_KIND)` on 25–28.
 */
class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cr = options.context.contentResolver
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            cr.loadThumbnail(ContentUris.withAppendedId(MediaRepo.collection(data.isVideo), data.id), Size(320, 320), null)
        } else if (data.isVideo) {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(cr, data.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(cr, data.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
        if (bmp == null) throw IOException("no thumbnail for ${data.id}")
        return DrawableResult(drawable = BitmapDrawable(options.context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaThumbFetcher(data, options)
    }
}
