package com.watchgallery.app.data

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
                    originalDateAdded = o.optLong("originalDateAdded", o.getLong("deletedAt"))
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

    /** 删除前备份一批照片到私有目录 */
    suspend fun backup(photos: List<Photo>): BackupResult = withContext(Dispatchers.IO) {
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
                    originalDateAdded = photo.dateAdded
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

    /**
     * 恢复一张照片到原目录。返回恢复成功后要移除的 backupName（失败为 null）。
     * **不在这里写 entries** —— 批量恢复时由 [restoreBatch] 统一写一次，
     * 避免多协程并发「读-改-写」SharedPreferences 互相覆盖（底部几张恢复不掉的元凶）。
     */
    private fun restoreOne(photo: Photo, keepOriginalDate: Boolean): String? {
        val entry = findEntry(photo) ?: return null
        val backup = File(trashDir, entry.backupName)
        if (!backup.exists()) return null
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
                entry.backupName
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 恢复单张（外部单张入口） */
    suspend fun restore(photo: Photo, keepOriginalDate: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val name = restoreOne(photo, keepOriginalDate)
        if (name != null) saveEntries(loadEntries().filterNot { it.backupName == name })
        name != null
    }

    /**
     * 批量恢复：在单个 IO 协程里串行处理，全部完成后只写一次 entries。
     * 既消除了并发覆盖，也保证「恢复顺序」不会变成相册里的排序（每张都写回原时间）。
     * @return 成功恢复的张数
     */
    suspend fun restoreBatch(photos: List<Photo>, keepOriginalDate: Boolean = true): Int = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext 0
        val restoredNames = HashSet<String>()
        for (photo in photos) {
            restoreOne(photo, keepOriginalDate)?.let { restoredNames += it }
        }
        if (restoredNames.isNotEmpty()) {
            saveEntries(loadEntries().filterNot { it.backupName in restoredNames })
        }
        restoredNames.size
    }

    private fun insertToMediaStore(entry: TrashEntry, backup: File, keepOriginalDate: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RESTORE_${entry.displayName}")
            put(MediaStore.Images.Media.MIME_TYPE, entry.mimeType)
            // 开「保留原日期」时写回原图时间（秒），相册按 DATE_ADDED 倒序排，恢复后回到原来的时间位置；
            // 关掉时不写这两个字段，让 MediaStore 用当前时间（= 恢复时间）。
            if (keepOriginalDate && entry.originalDateAdded > 0) {
                put(MediaStore.Images.Media.DATE_ADDED, entry.originalDateAdded)
                put(MediaStore.Images.Media.DATE_MODIFIED, entry.originalDateAdded)
            }
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
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** 彻底删除：删备份文件 + 元数据 */
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
    val originalDateAdded: Long = deletedAt
)
