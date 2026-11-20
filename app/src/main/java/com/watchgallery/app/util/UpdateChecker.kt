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
    /** 主下载地址 */
    val apkUrl: String,
    /** 备用下载地址（主地址失败时依次尝试） */
    val fallbackUrls: List<String>,
    val releaseNotes: String,
    val apkSizeBytes: Long
) {
    /** 去重后的全部候选地址 */
    val allUrls: List<String>
        get() = (listOf(apkUrl) + fallbackUrls).filter { it.isNotBlank() }.distinct()
}

/**
 * 检查更新 —— Gitee 优先、GitHub 兜底。
 *
 * 为什么要换 Gitee：GitHub Release 资产会 302 跳到
 * `release-assets.githubusercontent.com`，这个域名在国内经常被限速甚至阻断，
 * 表现就是"进度条一直不动"。Gitee 的附件直连国内 CDN，基本能跑满带宽。
 *
 * 约定：
 * - tag 形如 v0.1 / 0.2，解析为 versionName 与可比较序号（0.15 -> 15）。
 * - Release 中第一个 .apk 资源作为下载目标。
 */
object UpdateChecker {

    suspend fun check(
        giteeOwner: String,
        giteeRepo: String,
        githubOwner: String,
        githubRepo: String
    ): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 1) Gitee 优先
        if (giteeOwner.isNotBlank()) {
            runCatching {
                fetch(giteeApi(giteeOwner, giteeRepo), isGitee = true,
                    ghOwner = githubOwner, ghRepo = githubRepo)
            }
                .onSuccess { return@withContext Result.success(it) }
                .onFailure { errors.add("Gitee: ${it.message}") }
        }

        // 2) GitHub 兜底
        runCatching {
            fetch(githubApi(githubOwner, githubRepo), isGitee = false,
                ghOwner = githubOwner, ghRepo = githubRepo)
        }
            .onSuccess { return@withContext Result.success(it) }
            .onFailure { errors.add("GitHub: ${it.message}") }

        Result.failure(Exception(errors.joinToString("；").ifBlank { "检查更新失败" }))
    }

    /** 兼容旧调用：只用 GitHub */
    suspend fun check(owner: String, repo: String): Result<ReleaseInfo> =
        check("", "", owner, repo)

    private fun giteeApi(owner: String, repo: String) =
        "https://gitee.com/api/v5/repos/$owner/$repo/releases/latest"

    private fun githubApi(owner: String, repo: String) =
        "https://api.github.com/repos/$owner/$repo/releases/latest"

    /**
     * 国内可用的 GitHub 加速前缀。GitHub 自己的下载会 302 到
     * release-assets.githubusercontent.com，国内常被限速/阻断，套一层代理能绕开。
     * 顺序即优先级，前一个失败自动换下一个（见 ApkDownloader）。
     */
    private val GH_MIRRORS = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    private fun fetch(
        urlString: String,
        isGitee: Boolean,
        ghOwner: String,
        ghRepo: String
    ): ReleaseInfo {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty(
                "Accept",
                if (isGitee) "application/json" else "application/vnd.github+json"
            )
            setRequestProperty("User-Agent", "WatchGallery-Updater")
        }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw Exception("HTTP $code")
        }
        val text = conn.inputStream.use { s ->
            BufferedReader(InputStreamReader(s, "UTF-8")).use { it.readText() }
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
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (apkUrl.isEmpty()) throw Exception("该版本未附带 APK")

        // 备用地址：同版本的 GitHub 官方直链 + 各加速镜像。
        // Gitee 源成功时也带上（万一仓库权限变化 403）；GitHub 源成功时镜像就是主力兜底。
        val apkName = apkUrl.substringAfterLast('/')
        val ghDirect = "https://github.com/$ghOwner/$ghRepo/releases/download/v$tag/$apkName"
        val fallbacks = buildList {
            add(ghDirect)
            GH_MIRRORS.forEach { add(it + ghDirect) }
        }.filter { it != apkUrl }.distinct()

        return ReleaseInfo(
            versionName = tag,
            versionCode = versionNumber(tag),
            apkUrl = apkUrl,
            fallbackUrls = fallbacks,
            releaseNotes = notes,
            apkSizeBytes = apkSize
        )
    }

    /**
     * 从版本号/ tag 提取可比较的序号：0.1 -> 1，0.2 -> 2，0.15 -> 15。
     *
     * 注意：不能拿 tag 解析出的序号跟 BuildConfig.VERSION_CODE 比。
     * APK 的 versionCode 与 versionName 小数部分不是同一套编号，会把新版误判成最新。
     */
    fun versionNumber(name: String): Int =
        name.removePrefix("v").trim().split(".").lastOrNull()?.toIntOrNull() ?: 0

    /** 返回 true 表示 remote 比 local 新（同一套 versionName 序号比较） */
    fun isNewer(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode
}
