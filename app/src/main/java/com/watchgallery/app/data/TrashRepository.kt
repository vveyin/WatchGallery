package com.watchgallery.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次备份的结果：是否全部成功 + 本次写入的备份文件名（取消删除时据此回滚） */
data class BackupResult(val ok: Boolean, val names: List<String>)

/**
 * 最近删除（回收站）仓库。
 *
 * 设计要点：
 *  - **不改系统删除流程**：MediaStore 照片仍走系统确认弹窗删除（无需存储权限）。
 *    弹窗确认前先把原图字节复制进应用私有目录当备份；确认后原图被系统删掉，备份留在回收站。
 *    用户取消则按 [BackupResult.names] 回滚备份，原图纹丝不动。
 *  - **恢复** = 把备份写回记录的 [TrashEntry.originalFolder]：
 *    content 原图用 MediaStore.insert(RELATIVE_PATH=原目录)，导入图写回 imported 目录。
 *  - **过期清理** = 按保留时长删备份与元数据。永久不过期。
 */
class TrashRepository(private val context: Context) {

    companion object {
        /** 保留时长选项（天）；-1 = 永久 */
        const val KEEP_FOREVER = -1L
        val KEEP_OPTIONS = listOf(1L, 7L, 30L, KEEP_FOREVER)
    }

    private val prefs = context.getSharedPreferences("watch_gallery_trash", Context.MODE_PRIVATE)
    private val importedDir: File
        get() = File(context.filesDir, "imported").apply { if (!exists()) mkdirs() }

    /** 备份目录：应用私有，卸载即清 */
    val trashDir: File
        get() = File(context.filesDir, "trash").apply { if (!exists()) mkdirs() }

    // ── 保留时长 ──
    fun loadKeepDays(): Long = prefs.getLong("keep_days", 30L)
    fun saveKeepDays(days: Long) { prefs.edit().putLong("keep_days", days).apply() }

    // ── 元数据 ──
    private fun loadEntries(): MutableList<TrashEntry> {
        val raw = prefs.getString("entries", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TrashEntry(
                    backupName = o.getString("backupName"),
                    displayName = o.getString("displayName"),
                    originalFolder = o.optString("originalFolder", ""),
                    mimeType = o.optString("mimeType", "image/jpeg"),
                    deletedAt = o.getLong("deletedAt"),
                    width = o.optInt("width", 0),
                    height = o.optInt("height", 0),
                    // 老数据没有这个字段：退化成删除时间，恢复后至少不会排到很离谱的位置
                    originalDateAdded = o.optLong("originalDateAdded", o.getLong("deletedAt")),
                    // 软删除：原图仍在 MediaStore，只是被隐藏；恢复=取消隐藏，彻底删除=系统删原图
                    softDeleted = o.optBoolean("softDeleted", false),
                    originalUri = o.optString("originalUri", "")
                )
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveEntries(entries: List<TrashEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("backupName", e.backupName)
                put("displayName", e.displayName)
                put("originalFolder", e.originalFolder)
                put("mimeType", e.mimeType)
                put("deletedAt", e.deletedAt)
                put("width", e.width)
                put("height", e.height)
                put("originalDateAdded", e.originalDateAdded)
                put("softDeleted", e.softDeleted)
                put("originalUri", e.originalUri)
            })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    /** 回收站里的照片（按删除时间倒序）；uri 指向私有备份文件，coil 直接可读 */
    fun listPhotos(): List<Photo> =
        loadEntries()
            .sortedByDescending { it.deletedAt }
            .mapIndexed { i, e ->
                val f = File(trashDir, e.backupName)
                Photo(
                    id = e.deletedAt + i.toLong(),
                    uri = Uri.fromFile(f),
                    displayName = e.displayName,
                    dateAdded = e.deletedAt,
                    width = e.width,
                    height = e.height,
                    bucketName = "最近删除",
                    mimeType = e.mimeType,
                    size = f.length(),
                    folderPath = e.originalFolder
                )
            }

    /**
     * 软删除：把照片记入回收站，但**不删原图**（MediaStore 记录与字节都原样保留）。
     * 仍复制一份字节到私有目录当备份，用于回收站缩略图与「原图在外部被删」时的恢复兜底；
     * 关键是记下原图 uri 并标记 softDeleted，让恢复走「取消隐藏」而非「重新 insert」。
     */
    suspend fun softDelete(photos: List<Photo>): BackupResult = withContext(Dispatchers.IO) {
        backupInternal(photos, softDelete = true)
    }

    /** 删除前备份一批照片到私有目录（老式硬删除用） */
    suspend fun backup(photos: List<Photo>): BackupResult = withContext(Dispatchers.IO) {
        backupInternal(photos, softDelete = false)
    }

    private suspend fun backupInternal(photos: List<Photo>, softDelete: Boolean): BackupResult = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext BackupResult(true, emptyList())
        val entries = loadEntries()
        val now = System.currentTimeMillis()
        val added = mutableListOf<TrashEntry>()
        val names = mutableListOf<String>()
        var ok = true
        photos.forEachIndexed { index, photo ->
            try {
                val ext = photo.displayName.substringAfterLast('.', "").take(8)
                val name = "trash_${now}_${index}" + if (ext.isNotBlank()) ".$ext" else ""
                val target = File(trashDir, name)
                context.contentResolver.openInputStream(photo.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: run { ok = false; return@forEachIndexed }
                if (target.length() <= 0) {
                    target.delete()
                    ok = false
                    return@forEachIndexed
                }
                names += name
                added += TrashEntry(
                    backupName = name,
                    displayName = photo.displayName,
                    originalFolder = photo.folderPath,
                    mimeType = photo.mimeType.ifBlank { "image/jpeg" },
                    deletedAt = now,
                    width = photo.width,
                    height = photo.height,
                    // 记住原图时间（相册按它倒序排）；恢复时写回，保持原来的时间顺序
                    originalDateAdded = photo.dateAdded,
                    softDeleted = softDelete,
                    originalUri = if (softDelete) photo.uri.toString() else ""
                )
            } catch (_: Exception) {
                ok = false
            }
        }
        if (ok) {
            saveEntries(added + entries)
        } else {
            // 部分失败：本次已写的全部回滚，保持「要么全进回收站、要么都不进」
            names.forEach { runCatching { File(trashDir, it).delete() } }
        }
        BackupResult(ok, if (ok) names else emptyList())
    }

    /**
     * 回滚一次备份（用户取消系统删除弹窗）。
     * entries 已写入，按名单删文件 + 移除元数据。
     */
    fun rollback(names: Collection<String>) {
        if (names.isEmpty()) return
        val entries = loadEntries()
        entries.filter { it.backupName in names }.forEach {
            runCatching { File(trashDir, it.backupName).delete() }
        }
        saveEntries(entries.filterNot { it.backupName in names })
    }

    /** 一次恢复的结果：成功时带回 backupName，以及是否是软删除（VM 据此取消隐藏） */
    data class RestoreResult(val backupName: String, val wasSoftDelete: Boolean, val originalUri: String)

    /**
     * 恢复一张照片。软删除项：原图从未删除，**只需取消隐藏**，不重新 insert；
     * 老式硬删除项：走原有 insert 流程把备份写回。
     * **不在这里写 entries** —— 由 [restore] / [restoreBatch] 统一写一次，
     * 避免多协程并发「读-改-写」SharedPreferences 互相覆盖。
     */
    private fun restoreOne(photo: Photo, keepOriginalDate: Boolean): RestoreResult? {
        val entry = findEntry(photo) ?: return null
        val backup = File(trashDir, entry.backupName)
        if (!backup.exists()) return null
        // ── 软删除：原图还在，恢复=取消隐藏，直接标记为成功 ──
        if (entry.softDeleted && entry.originalUri.isNotBlank()) {
            return RestoreResult(entry.backupName, true, entry.originalUri)
        }
        return try {
            val ok = if (entry.originalFolder == Photo.IMPORTED_FOLDER) {
                // 文件名带 backupName 的唯一后缀，避免同毫秒恢复多张时互相覆盖
                val stamp = entry.backupName.substringBefore('.').removePrefix("trash_")
                val name = "RESTORE_${stamp}_${entry.displayName}"
                val target = File(importedDir, name)
                backup.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                // 导入目录按 lastModified 排序：开「保留原日期」才写回原图时间保持原来的顺序；
                // 关掉则不写，文件保持 newFile 的当前时间（= 恢复时间）。
                if (keepOriginalDate && entry.originalDateAdded > 0) {
                    runCatching { target.setLastModified(entry.originalDateAdded * 1000L) }
                }
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                }
                true
            } else {
                insertToMediaStore(entry, backup, keepOriginalDate) != null
            }
            if (ok) {
                runCatching { backup.delete() }
                RestoreResult(entry.backupName, false, entry.originalUri)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 恢复单张，返回成功恢复的条目信息（含哪些是软删除需要 VM 取消隐藏的原图 uri）。
     * null = 失败。
     */
    suspend fun restore(photo: Photo, keepOriginalDate: Boolean = true): RestoreResult? = withContext(Dispatchers.IO) {
        val result = restoreOne(photo, keepOriginalDate)
        if (result != null) saveEntries(loadEntries().filterNot { it.backupName == result.backupName })
        result
    }

    /** 批量恢复结果：成功张数 + 其中需要取消隐藏的原图 uri 列表 */
    data class BatchRestoreResult(val count: Int, val unhideUris: List<String>)

    /**
     * 批量恢复：在单个 IO 协程里串行处理，全部完成后只写一次 entries。
     * @return 成功张数与需要取消隐藏的软删除原图 uri
     */
    suspend fun restoreBatch(photos: List<Photo>, keepOriginalDate: Boolean = true): BatchRestoreResult =
        withContext(Dispatchers.IO) {
            if (photos.isEmpty()) return@withContext BatchRestoreResult(0, emptyList())
            val restoredNames = HashSet<String>()
            val unhide = ArrayList<String>()
            for (photo in photos) {
                restoreOne(photo, keepOriginalDate)?.let { r ->
                    restoredNames += r.backupName
                    if (r.wasSoftDelete) unhide += r.originalUri
                }
            }
            if (restoredNames.isNotEmpty()) {
                saveEntries(loadEntries().filterNot { it.backupName in restoredNames })
            }
            BatchRestoreResult(restoredNames.size, unhide)
        }

    private fun insertToMediaStore(entry: TrashEntry, backup: File, keepOriginalDate: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RESTORE_${entry.displayName}")
            put(MediaStore.Images.Media.MIME_TYPE, entry.mimeType)
            // 写回宽高：不写的话刚恢复时 MediaStore 的 WIDTH/HEIGHT 是 0，
            // 大图页按 0 尺寸解码/定位会异常（点进去一片空白或显示错位），这是恢复图点不开的根因之一。
            if (entry.width > 0) put(MediaStore.Images.Media.WIDTH, entry.width)
            if (entry.height > 0) put(MediaStore.Images.Media.HEIGHT, entry.height)
            // 注意：Android 10+ 的 DATE_ADDED/DATE_MODIFIED 是系统只读字段，
            // insert 时写入会被强制覆盖成当前时间，写了也没用。
            // 真正的「保留原日期」在下面 insert 成功后用 RestoreDateStore 按 mediaId 记录，
            // 再由 MediaRepository 扫描时覆盖 dateAdded。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rel = if (entry.originalFolder.isBlank()) {
                    "${Environment.DIRECTORY_PICTURES}/WatchGallery"
                } else entry.originalFolder
                put(MediaStore.Images.Media.RELATIVE_PATH, rel)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                backup.inputStream().use { it.copyTo(out) }
            } ?: run { resolver.delete(uri, null, null); return null }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
            }
            // 记录/清除恢复图日期映射：开保留原日期 → 记原图时间；关 → 清掉，沿用系统当前时间。
            val newId = ContentUris.parseId(uri)
            if (keepOriginalDate) RestoreDateStore.put(context, newId, entry.originalDateAdded)
            else RestoreDateStore.remove(context, newId)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * 彻底删除：删备份文件 + 元数据。
     * 对软删除项，原图仍在 MediaStore —— 调用方应先用 [softDeleteUris] 拿原图 uri 走系统删除，
     * 确认删除成功后（或不走系统删除时）再调这里清备份。
     */
    suspend fun deleteForever(photos: List<Photo>): Int = withContext(Dispatchers.IO) {
        val names = photos.mapNotNull { findEntry(it)?.backupName }.toSet()
        val entries = loadEntries()
        var n = 0
        entries.filter { it.backupName in names }.forEach {
            if (runCatching { File(trashDir, it.backupName).delete() }.getOrDefault(false)) n++
        }
        saveEntries(entries.filterNot { it.backupName in names })
        n
    }

    /** 这些回收站条目里属于软删除的原图 uri（彻底删除时要真正系统删掉的） */
    fun softDeleteUris(photos: List<Photo>): List<Uri> =
        photos.mapNotNull { p ->
            findEntry(p)?.let { e ->
                if (e.softDeleted && e.originalUri.isNotBlank()) runCatching { Uri.parse(e.originalUri) }.getOrNull()
                else null
            }
        }

    /** 彻底删除系统弹窗被取消：恢复软删除项（它们本就没真删），无需任何改动，返回存在几个软删除项 */
    fun countSoftDeleted(photos: List<Photo>): Int =
        photos.count { p -> findEntry(p)?.softDeleted == true }

    /** 过期清理，返回清理张数 */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        val keep = loadKeepDays()
        if (keep == KEEP_FOREVER) return@withContext 0
        val cutoff = System.currentTimeMillis() - keep * 24L * 3600L * 1000L
        val entries = loadEntries()
        val expired = entries.filter { it.deletedAt < cutoff }
        expired.forEach { runCatching { File(trashDir, it.backupName).delete() } }
        saveEntries(entries.filterNot { it.deletedAt < cutoff })
        expired.size
    }

    private fun findEntry(photo: Photo): TrashEntry? {
        val name = photo.uri.toString().substringAfterLast('/')
        return loadEntries().firstOrNull { it.backupName == name }
    }
}

/** 回收站条目 */
data class TrashEntry(
    val backupName: String,
    val displayName: String,
    val originalFolder: String,
    val mimeType: String,
    val deletedAt: Long,
    val width: Int,
    val height: Int,
    /** 原图在相册里的时间（秒），恢复时写回以保持原来的时间顺序 */
    val originalDateAdded: Long = deletedAt,
    /**
     * true = 软删除：原图仍在 MediaStore，仅从相册隐藏。
     * 恢复 = 取消隐藏（原图 id/uri/日期全部原样保留）；彻底删除 = 这时才系统删原图。
     * false = 老式硬删除：原图已被系统删，只剩备份字节，恢复要重新 insert。
     */
    val softDeleted: Boolean = false,
    /** 软删除时记录的原图 content/file uri，恢复与彻底删除时据此定位原图 */
    val originalUri: String = ""
)
