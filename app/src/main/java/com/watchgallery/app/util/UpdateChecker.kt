package com.watchgallery.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 最新版本信息 */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val releaseNotes: String,
    val apkSizeBytes: Long
)

/**
 * 检查 GitHub Release 更新 —— 读取仓库最新 release 的 tag_name 与 APK 资源。
 *
 * 约定：
 * - tag 形如 v0.1 / 0.2，解析为 versionName 与 versionCode（0.15 -> 15）。
 * - Release 中第一个 .apk 资源作为下载目标。
 * - owner/repo 由 BuildConfig 注入（见 app/build.gradle.kts）。
 */
object UpdateChecker {

    suspend fun check(owner: String, repo: String): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WatchGallery-Updater")
            }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext Result.failure(Exception("HTTP $code"))
            }
            val text = conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            }
            conn.disconnect()

            val json = JSONObject(text)
            val tag = json.optString("tag_name", "v0.0").removePrefix("v").trim()
            val notes = json.optString("body", "")
            var apkUrl = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) {
                return@withContext Result.failure(Exception("最新版本未附带 APK"))
            }
            val (vName, vCode) = parseVersion(tag)
            Result.success(ReleaseInfo(vName, vCode, apkUrl, notes, apkSize))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 解析 0.1 / 0.15 形式：versionCode = 小数部分（1、15） */
    private fun parseVersion(tag: String): Pair<String, Int> {
        val parts = tag.split(".")
        val name = tag
        val code = if (parts.size >= 2) parts.last().toIntOrNull() ?: 0 else 0
        return name to code
    }

    /**
     * 比较版本：仅比较 versionCode（小数部分递增），例如 0.1 < 0.2 < 0.15。
     * 返回 true 表示 remote 比 local 新。
     */
    fun isNewer(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode
}
