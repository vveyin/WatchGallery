package com.watchgallery.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    /**
     * 当前是否有可用网络。
     * 启动时的自动检查先过这一关：没网就什么都不做，也不提示 —— 省掉一次必然失败的请求，
     * 避免用户在无网环境下开 App 就收到「检查失败」。
     */
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    suspend fun check(
        giteeOwner: String,
        giteeRepo: String,
        githubOwner: String,
        githubRepo: String
    ): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()

        /**
         * 两个源都查，取版本号最大的一方。
         * 不能「Gitee 成功就返回」：Gitee 的 API 在部分 CDN 节点会缓存旧响应
         * （新发的 release 几天都读不到，query 里的时间戳也会被缓存层忽略）。
         * 只要 GitHub 仓库保持同步，缓存再多也能拿到真实的新版。
         */
        var best: ReleaseInfo? = null
        var bestCode = -1

        if (giteeOwner.isNotBlank()) {
            runCatching {
                fetch(giteeApi(giteeOwner, giteeRepo), isGitee = true,
                    ghOwner = githubOwner, ghRepo = githubRepo)
            }
                .onSuccess {
                    if (it.versionCode > bestCode) { best = it; bestCode = it.versionCode }
                }
                .onFailure { errors.add("Gitee: ${it.message}") }
        }

        runCatching {
            fetch(githubApi(githubOwner, githubRepo), isGitee = false,
                ghOwner = githubOwner, ghRepo = githubRepo)
        }
            .onSuccess {
                if (it.versionCode > bestCode) { best = it; bestCode = it.versionCode }
            }
            .onFailure { errors.add("GitHub: ${it.message}") }

        val winner = best
        if (winner != null) Result.success(winner)
        else Result.failure(Exception(errors.joinToString("；").ifBlank { "检查更新失败" }))
    }

    /** 兼容旧调用：只用 GitHub */
    suspend fun check(owner: String, repo: String): Result<ReleaseInfo> =
        check("", "", owner, repo)

    private fun giteeApi(owner: String, repo: String) =
        "https://gitee.com/api/v5/repos/$owner/$repo/releases?page=1&per_page=100&_t=${System.currentTimeMillis()}"

    private fun githubApi(owner: String, repo: String) =
        "https://api.github.com/repos/$owner/$repo/releases?per_page=100&_t=${System.currentTimeMillis()}"

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
            // 明确要求不读缓存：部分 CDN 会无视 URL 上的时间戳参数
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
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

        // 列表接口返回数组；为兼容旧 latest 接口，单对象也包成数组处理。
        // Gitee 的 /releases/latest 有 CDN 缓存，新发的 release 常常几天都读不到，
        // 所以改用列表接口 + 时间戳参数绕缓存，并在列表里挑版本号最大的一个。
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))

        var best: ReleaseInfo? = null
        var bestCode = -1
        for (i in 0 until arr.length()) {
            val json = arr.getJSONObject(i)
            if (json.optBoolean("prerelease", false)) continue
            if (json.optBoolean("draft", false)) continue
            val info = runCatching { parseRelease(json, ghOwner, ghRepo) }.getOrNull() ?: continue
            if (info.versionCode > bestCode) { best = info; bestCode = info.versionCode }
        }
        return best ?: throw Exception("未找到带 APK 的 Release")
    }

    /** 从单个 release JSON 解析版本与下载地址；没有 .apk 资产则抛异常跳过 */
    private fun parseRelease(
        json: JSONObject,
        ghOwner: String,
        ghRepo: String
    ): ReleaseInfo {
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
