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
                // 关键：用 getColumnIndex 而不是 OrThrow。
                // 部分手表 ROM 的 MediaStore 不提供 WIDTH/HEIGHT/SIZE 等列，
                // 一旦 OrThrow 抛异常，整个 while 循环都不执行，表现为"一张照片都没有"。
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                if (idCol < 0) return@use   // 连 _ID 都没有，这份 cursor 不可用

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val w = if (wCol >= 0) cursor.getInt(wCol) else 0
                    val h = if (hCol >= 0) cursor.getInt(hCol) else 0
                    result.add(
                        Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "IMG_$id" else "IMG_$id",
                            dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                            width = w,
                            height = h,
                            bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "其他" else "其他",
                            mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg",
                            size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        // MediaStore 为空时，退回直接扫描常见图片目录（部分手表不建媒体索引）
        if (result.isEmpty()) {
            result.addAll(scanCommonDirs())
        }
        result
    }

    /**
     * 直接扫描常见图片目录作为兜底。
     * 多数手表（尤其 OPPO/华为的 Wear 系统）MediaStore 索引不完整，
     * 用户用数据线拷贝进去的照片可能查不到；直接列目录能救回来。
     */
    private fun scanCommonDirs(): List<Photo> {
        val roots = listOfNotNull(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            java.io.File("/sdcard/Pictures"),
            java.io.File("/sdcard/DCIM"),
            java.io.File("/storage/emulated/0/Pictures"),
            java.io.File("/storage/emulated/0/DCIM")
        ).distinct()

        val exts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        val out = mutableListOf<Photo>()
        val seen = HashSet<String>()

        fun walk(dir: java.io.File, depth: Int) {
            if (depth > 3 || !dir.isDirectory || !dir.canRead()) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (f.extension.lowercase() in exts && f.length() > 0) {
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out.add(
                        Photo(
                            id = path.hashCode().toLong() and 0x7fffffff,
                            uri = Uri.fromFile(f),
                            displayName = f.name,
                            dateAdded = f.lastModified() / 1000,
                            width = 0,
                            height = 0,
                            bucketName = f.parentFile?.name ?: "其他",
                            mimeType = "image/*",
                            size = f.length()
                        )
                    )
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return out.sortedByDescending { it.dateAdded }
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
