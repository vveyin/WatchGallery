package com.watchgallery.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一张图片的信息 */
data class Photo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val bucketName: String,
    val mimeType: String,
    val size: Long
) {
    val isLandscape: Boolean get() = width > height
}

/** 相册分组 */
data class Album(
    val name: String,
    val photos: List<Photo>
) {
    val cover: Photo? get() = photos.firstOrNull()
}

/**
 * 媒体库仓库 —— 通过 MediaStore 扫描设备上的图片，对标手机相册的数据源。
 */
class MediaRepository(private val context: Context) {

    suspend fun loadPhotos(): List<Photo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Photo>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result.add(
                        Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = cursor.getString(nameCol) ?: "IMG_$id",
                            dateAdded = cursor.getLong(dateCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            bucketName = cursor.getString(bucketCol) ?: "其他",
                            mimeType = cursor.getString(mimeCol) ?: "image/jpeg",
                            size = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        result
    }

    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.Default) {
        loadPhotos().groupBy { it.bucketName }
            .map { (name, photos) -> Album(name, photos) }
            .sortedByDescending { it.photos.size }
    }

    /** 读取图片真实尺寸（部分 MediaStore 记录宽高可能为 0 时兜底） */
    fun readImageSize(uri: Uri): Size? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
            if (opts.outWidth > 0) Size(opts.outWidth, opts.outHeight) else null
        }
    } catch (_: Exception) {
        null
    }
}
