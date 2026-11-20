package com.watchgallery.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

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

/** 扫描过程的可诊断信息 —— 出问题时直接展示给用户，避免"空白页无解释" */
data class ScanReport(
    val sdk: Int = 0,
    val permissionGranted: Boolean = false,
    val imagesRows: Int = 0,
    val filesRows: Int = 0,
    val dirHits: Int = 0,
    val importedCount: Int = 0,
    val scanTriggered: Boolean = false,
    val roots: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val summary: String
        get() = "系统 Android $sdk · 权限${if (permissionGranted) "已授予" else "未授予"}\n" +
                "媒体库 $imagesRows 条 · 文件表 $filesRows 条\n" +
                "目录扫描 $dirHits 张 · 已导入 $importedCount 张"
}

data class ScanResult(val photos: List<Photo>, val report: ScanReport)

/**
 * 媒体库仓库 —— 通过 MediaStore 扫描设备上的图片，对标手机相册的数据源。
 *
 * 手表 ROM 的媒体索引普遍不可靠（用 adb push 或者某些同步工具塞进去的照片
 * 根本不会进 MediaStore），所以这里做成多级兜底：
 *   1. 各外部存储卷的 images 表
 *   2. files 表按 media_type=IMAGE 再查一遍（部分 ROM 只往这张表里写）
 *   3. 若还是 0 条 → 主动触发系统媒体扫描（MediaProvider 是系统进程，能读到我们读不到的目录），
 *      扫完再查一遍
 *   4. 目录直扫（Android 10 及以下 / 有全盘权限时有效）
 *   5. 应用内"导入"目录（用户通过系统文件选择器手动导入的照片，一定读得到）
 */
class MediaRepository(private val context: Context) {

    private val importedDir: File
        get() = File(context.filesDir, "imported").apply { if (!exists()) mkdirs() }

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val collected = LinkedHashMap<String, Photo>()
        val usedIds = HashSet<Long>()
        var imagesRows = 0
        var filesRows = 0
        var dirHits = 0

        fun addAll(list: List<Photo>) {
            list.forEach { photo ->
                // 双重去重：uri 相同（同一条目被多卷查到）或 id 相同（跨卷同 _ID）都跳过。
                // 只按 uri 去重时，两张 uri 不同但 _ID 相同的照片会让 Compose 的
                // key 重复，直接抛 IllegalArgumentException 崩溃（v0.8 实测）。
                if (collected.containsKey(photo.uri.toString())) return@forEach
                if (!usedIds.add(photo.id)) return@forEach
                collected[photo.uri.toString()] = photo
            }
        }

        // 1) images 表（逐卷）
        val images = queryImages(errors).also { imagesRows = it.size }
        addAll(images)

        // 2) files 表兜底
        if (collected.isEmpty()) {
            val files = queryFilesTable(errors).also { filesRows = it.size }
            addAll(files)
        }

        // 3) 仍然为空 → 主动让系统重新索引常见目录，然后重查
        var scanTriggered = false
        if (collected.isEmpty()) {
            scanTriggered = triggerMediaScan()
            if (scanTriggered) {
                val again = queryImages(errors).also { imagesRows = maxOf(imagesRows, it.size) }
                addAll(again)
                if (collected.isEmpty()) {
                    val files = queryFilesTable(errors).also { filesRows = maxOf(filesRows, it.size) }
                    addAll(files)
                }
            }
        }

        // 4) 目录直扫仅作兜底：MediaStore 已经有照片时跳过。
        //    遍历整个存储目录（几千个文件逐个 stat）非常慢，是"主页一直转圈"的元凶。
        //    只有在前三级一个都没查到时才值得做这一步。
        if (collected.isEmpty()) {
            val dirPhotos = scanCommonDirs(errors)
            dirHits = dirPhotos.size
            addAll(dirPhotos)
        }

        // 5) 应用内导入目录，永远不会失败
        val imported = scanImported()
        addAll(imported)

        val sorted = collected.values.sortedByDescending { it.dateAdded }
        ScanResult(
            photos = sorted,
            report = ScanReport(
                sdk = Build.VERSION.SDK_INT,
                permissionGranted = hasMediaPermission(),
                imagesRows = imagesRows,
                filesRows = filesRows,
                dirHits = dirHits,
                importedCount = imported.size,
                scanTriggered = scanTriggered,
                roots = candidateDirs().map { it.absolutePath },
                errors = errors
            )
        )
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- MediaStore

    private fun imageCollections(): List<Uri> {
        val list = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                MediaStore.getExternalVolumeNames(context).forEach {
                    list.add(MediaStore.Images.Media.getContentUri(it))
                }
            }
        }
        // external 是历史别名，某些 ROM 只认它
        list.add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
        list.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        return list.distinct()
    }

    private fun queryImages(errors: MutableList<String>): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val out = mutableListOf<Photo>()
        val seen = HashSet<String>()
        imageCollections().forEach { collection ->
            try {
                // 排序交给内存做：部分手表 ROM 不支持按 DATE_ADDED 排序，会直接抛异常
                context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (idCol < 0) return@use
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(collection, id)
                        if (!seen.add(uri.toString())) continue
                        var date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                        if (date == 0L && modCol >= 0) date = cursor.getLong(modCol)
                        out.add(
                            Photo(
                                id = id,
                                uri = uri,
                                displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "IMG_$id" else "IMG_$id",
                                dateAdded = date,
                                width = if (wCol >= 0) cursor.getInt(wCol) else 0,
                                height = if (hCol >= 0) cursor.getInt(hCol) else 0,
                                bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "其他" else "其他",
                                mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg",
                                size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                errors.add("images($collection): ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return out
    }

    /** files 表兜底：不少定制 ROM 只维护 files 表，images 视图是空的 */
    private fun queryFilesTable(errors: MutableList<String>): List<Photo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE
        )
        val out = mutableListOf<Photo>()
        try {
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                if (idCol < 0) return@use
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val modCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    var date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    if (date == 0L && modCol >= 0) date = cursor.getLong(modCol)
                    out.add(
                        Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "IMG_$id" else "IMG_$id",
                            dateAdded = date,
                            width = 0,
                            height = 0,
                            bucketName = "其他",
                            mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/*" else "image/*",
                            size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            errors.add("files: ${e.javaClass.simpleName} ${e.message}")
        }
        return out
    }

    // ---------------------------------------------------------------- 媒体扫描

    /**
     * 让系统把常见图片目录重新索引进 MediaStore。
     *
     * 这是"照片明明在手表里、相册却是空的"最常见原因：adb push / 部分同步工具
     * 写文件不触发媒体扫描，MediaStore 里没有记录；而应用在 Android 11 的分区存储下
     * 又读不了 /sdcard/Pictures。MediaProvider 跑在系统进程里，能读能写索引，
     * 所以由它代扫一遍是唯一稳的办法。
     */
    private suspend fun triggerMediaScan(): Boolean {
        val paths = candidateDirs().map { it.absolutePath }
        if (paths.isEmpty()) return false
        return withTimeoutOrNull(3_500L) {
            suspendCancellableCoroutine { cont ->
                try {
                    var remaining = paths.size
                    MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                        remaining--
                        if (remaining <= 0 && cont.isActive) cont.resume(true)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    /** 常见图片目录 + 各存储卷根目录（用于媒体扫描与直扫） */
    private fun candidateDirs(): List<File> {
        val roots = mutableListOf<File>()

        // 通过应用专属目录反推外部存储根（不需要任何权限就能拿到路径）
        runCatching {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                // /storage/emulated/0/Android/data/pkg/files → /storage/emulated/0
                var f: File? = dir
                repeat(4) { f = f?.parentFile }
                f?.let { roots.add(it) }
            }
        }

        runCatching {
            Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        }
        runCatching {
            File("/storage").listFiles()?.forEach { if (it.isDirectory) roots.add(it) }
        }
        runCatching { roots.add(File("/sdcard")) }

        val dirs = mutableListOf<File>()
        roots.filterNotNull().distinctBy { it.absolutePath }.forEach { root ->
            dirs.add(root)
            listOf("Pictures", "DCIM", "DCIM/Camera", "Download", "Downloads", "Screenshots", "Movies").forEach {
                dirs.add(File(root, it))
            }
        }
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }

    /** 目录直扫（Android 10 及以下，或目标目录恰好可读时有效） */
    private fun scanCommonDirs(errors: MutableList<String>): List<Photo> {
        val exts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        val out = mutableListOf<Photo>()
        val seen = HashSet<String>()

        fun walk(dir: File, depth: Int) {
            if (depth > 3 || !dir.isDirectory || !dir.canRead()) return
            val files = try { dir.listFiles() } catch (e: Exception) {
                errors.add("list(${dir.name}): ${e.javaClass.simpleName}")
                null
            } ?: return
            for (f in files) {
                if (out.size > 3000) return
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
        candidateDirs().forEach { walk(it, 0) }
        return out
    }

    /** 应用内导入目录 */
    private fun scanImported(): List<Photo> {
        val dir = importedDir
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return emptyList()
        return files.filter { it.isFile && it.length() > 0 }
            .map {
                Photo(
                    id = it.absolutePath.hashCode().toLong() and 0x7fffffff,
                    uri = Uri.fromFile(it),
                    displayName = it.name,
                    dateAdded = it.lastModified() / 1000,
                    width = 0,
                    height = 0,
                    bucketName = "已导入",
                    mimeType = "image/*",
                    size = it.length()
                )
            }
    }

    /**
     * 从系统文件选择器导入图片：把内容复制进应用私有目录。
     * 不依赖 MediaStore、不依赖全盘读权限，是"相册空白"时最可靠的入口。
     */
    suspend fun importFromUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var ok = 0
        uris.forEachIndexed { index, uri ->
            try {
                val name = "IMG_${System.currentTimeMillis()}_$index.jpg"
                val target = File(importedDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > 0) {
                    ok++
                    runCatching {
                        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                    }
                } else {
                    target.delete()
                }
            } catch (_: Exception) {
            }
        }
        ok
    }

    /**
     * 删除一组照片。MediaStore 记录用 ContentResolver.delete（带 _ID），
     * 应用私有导入目录的文件直接删 File。返回成功删除的张数。
     */
    suspend fun deletePhotos(photos: List<Photo>): Int = withContext(Dispatchers.IO) {
        var ok = 0
        photos.forEach { photo ->
            try {
                val deleted = when {
                    photo.uri.scheme == "content" ->
                        context.contentResolver.delete(photo.uri, null, null) > 0
                    photo.uri.scheme == "file" ->
                        File(photo.uri.path ?: "").let { it.exists() && it.delete() }
                    else -> false
                }
                if (deleted) ok++
            } catch (_: Exception) {
                // 系统照片可能无删除权限（如只读卷），跳过即可
            }
        }
        ok
    }

    suspend fun loadPhotos(): List<Photo> = scan().photos

    /** 由已扫描结果直接分组，避免重复扫盘 */
    fun toAlbums(photos: List<Photo>): List<Album> =
        photos.groupBy { it.bucketName }
            .map { (name, list) -> Album(name, list) }
            .sortedByDescending { it.photos.size }

    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.Default) {
        toAlbums(loadPhotos())
    }

    /** 读取图片真实尺寸（部分 MediaStore 记录宽高为 0 时兜底） */
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
