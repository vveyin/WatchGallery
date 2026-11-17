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

    /**
     * 从版本号/ tag 提取可比较的序号：0.1 -> 1，0.2 -> 2，0.15 -> 15，0.17 -> 17。
     *
     * 注意：绝不能拿 GitHub tag 解析出的序号跟 BuildConfig.VERSION_CODE 比。
     * APK 的 versionCode 是 0.2=3、0.3=4（比小数部分多 1），两套编号对不上，
     * 会把 v0.3 误判成"已是最新"。比较双方都必须从 versionName 提取同一套序号。
     */
    fun versionNumber(name: String): Int =
        name.removePrefix("v").trim().split(".").lastOrNull()?.toIntOrNull() ?: 0

    /** 解析 v0.1 / 0.15 形式：versionName 原样，序号由小数部分得出 */
    private fun parseVersion(tag: String): Pair<String, Int> {
        val name = tag.removePrefix("v").trim()
        return name to versionNumber(name)
    }

    /** 返回 true 表示 remote 比 local 新（同一套 versionName 序号比较） */
    fun isNewer(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode
}
