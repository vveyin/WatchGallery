package com.watchgallery.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 下载管理器 —— 流式下载、断点续传、多地址回退。
 *
 * 关键点：
 * 1. 下载完成后 APK 保存在稳定路径（update.apk），安装失败也不会丢，可反复点安装；
 * 2. 主地址（Gitee）失败时自动换备用地址（GitHub）重试；
 * 3. 支持 Range 断点续传：上次下到一半断了，重试从断点继续，不从头再来；
 * 4. 服务器不给 Content-Length 时（GitHub 的 302 资产常见），进度回调 total = -1，
 *    UI 改为显示已下载字节，避免"0% 一直不动"的错觉。
 */
object ApkDownloader {

    const val APK_FILE_NAME = "watchgallery_update.apk"

    /** 更新 APK 的存放目录（app 内部 files 目录，安装器可读） */
    fun updateApkFile(context: Context): File = File(context.filesDir, APK_FILE_NAME)

    private fun tmpFile(context: Context): File = File(context.filesDir, "$APK_FILE_NAME.tmp")

    /**
     * 依次尝试 urls，任意一个成功即返回。
     * onProgress(downloaded, total)：total 为 -1 表示服务器未告知总大小。
     */
    suspend fun download(
        context: Context,
        urls: List<String>,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return@withContext Result.failure(Exception("没有可用的下载地址"))

        var lastError: Exception? = null
        for ((index, url) in candidates.withIndex()) {
            if (index > 0) {
                // 换源时清掉上个源可能留下的半截文件，避免拼接出坏包
                runCatching { tmpFile(context).delete() }
            }
            val r = downloadOne(context, url, onProgress)
            if (r.isSuccess) return@withContext r
            lastError = r.exceptionOrNull() as? Exception
        }
        Result.failure(lastError ?: Exception("下载失败"))
    }

    /** 单地址下载，带一次断点续传重试 */
    private fun downloadOne(
        context: Context,
        urlString: String,
        onProgress: (Long, Long) -> Unit
    ): Result<File> {
        val tmp = tmpFile(context)
        var attempt = 0
        var lastError: Exception? = null

        while (attempt < 2) {
            attempt++
            try {
                val existing = if (tmp.exists()) tmp.length() else 0L
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 45000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "WatchGallery-Updater")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existing > 0) {
                        setRequestProperty("Range", "bytes=$existing-")
                    }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    throw Exception("HTTP $code")
                }

                // 206 = 续传成功，接着写；200 = 服务器不支持 Range，从头写
                val appending = code == 206 && existing > 0
                val downloadedStart = if (appending) existing else 0L
                val contentLength = conn.contentLengthLong
                val total = when {
                    contentLength > 0 && appending -> contentLength + downloadedStart
                    contentLength > 0 -> contentLength
                    else -> -1L
                }

                val out = FileOutputStream(tmp, appending)
                var downloaded = downloadedStart
                val buffer = ByteArray(32 * 1024)
                conn.inputStream.use { input ->
                    out.use { o ->
                        var lastCallback = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            o.write(buffer, 0, read)
                            downloaded += read
                            // 节流：每 128KB 刷新一次 UI，减少重组
                            if (downloaded - lastCallback >= 128 * 1024 || read < buffer.size) {
                                lastCallback = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        o.flush()
                    }
                }
                conn.disconnect()

                // 有总大小就校验完整性，避免把截断的包当成功
                if (total > 0 && tmp.length() < total) {
                    throw Exception("下载不完整 ${tmp.length()}/$total")
                }
                if (tmp.length() == 0L) throw Exception("下载文件为空")

                val outFile = updateApkFile(context)
                if (outFile.exists()) outFile.delete()
                if (!tmp.renameTo(outFile)) {
                    tmp.copyTo(outFile, overwrite = true)
                    tmp.delete()
                }
                return Result.success(outFile)
            } catch (e: Exception) {
                lastError = e
                // 第一次失败后保留 tmp，第二次尝试走断点续传
                if (attempt >= 2) break
            }
        }
        return Result.failure(lastError ?: Exception("下载失败"))
    }
}
