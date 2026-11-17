package com.watchgallery.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 下载管理器 —— 流式下载并回调进度。
 *
 * 关键点：下载完成后 APK 保存在稳定路径（update.apk），不会因为安装失败而丢失；
 * 安装按钮始终可用，用户可反复点击「安装」直到成功。
 */
object ApkDownloader {

    const val APK_FILE_NAME = "watchgallery_update.apk"

    /** 更新 APK 的存放目录（app 内部 files 目录，其他应用不可见但安装器可读） */
    fun updateApkFile(context: Context): File = File(context.filesDir, APK_FILE_NAME)

    suspend fun download(
        context: Context,
        urlString: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WatchGallery-Updater")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.failure(Exception("下载失败 HTTP $code"))
            }
            val total = conn.contentLengthLong
            val outFile = updateApkFile(context)
            // 先写临时文件，下载完成后原子替换，避免半成品被当成完整包
            val tmpFile = File(context.filesDir, "$APK_FILE_NAME.tmp")
            if (tmpFile.exists()) tmpFile.delete()

            var downloaded = 0L
            val buffer = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            conn.disconnect()

            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            if (outFile.length() == 0L) {
                return@withContext Result.failure(Exception("下载文件为空"))
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
