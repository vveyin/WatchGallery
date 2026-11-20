package com.watchgallery.app.ui

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.BuildConfig
import com.watchgallery.app.util.ApkDownloader
import com.watchgallery.app.util.ApkInstaller
import com.watchgallery.app.util.ReleaseInfo
import com.watchgallery.app.util.UpdateChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val remoteName: String) : UpdateState()
    data class Found(val info: ReleaseInfo) : UpdateState()
    data class Downloading(val downloaded: Long, val total: Long, val info: ReleaseInfo) : UpdateState()
    data class Ready(val info: ReleaseInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * 检查更新页 —— 圆屏安全区布局。
 * 流程：检查 → 发现新版本 → 下载（进度条）→ 安装按钮。
 * 安装按钮始终可点，即使上次安装失败也能重试（APK 保留在稳定路径）。
 */
@Composable
fun UpdateScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float> = kotlinx.coroutines.flow.emptyFlow(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    // 表冠驱动滚动：更新说明较长时可像其它页面一样用表冠浏览
    val scrollState = rememberCrownScrollState(crownEvents)

    // 已有更新包时直接给出「安装」入口，避免安装失败后无法再次安装
    val existingApk = remember { ApkDownloader.updateApkFile(context) }

    fun checkUpdate() {
        state = UpdateState.Checking
        scope.launch {
            val result = UpdateChecker.check(
                BuildConfig.GITEE_OWNER, BuildConfig.GITEE_REPO,
                BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO
            )
            state = result.fold(
                onSuccess = { info ->
                    // 两边都从 versionName 取序号，不能拿 tag 的序号比 APK 的 versionCode
                    if (UpdateChecker.isNewer(
                            UpdateChecker.versionNumber(info.versionName),
                            UpdateChecker.versionNumber(BuildConfig.VERSION_NAME)
                        )) {
                        UpdateState.Found(info)
                    } else {
                        UpdateState.UpToDate(info.versionName)
                    }
                },
                onFailure = { UpdateState.Error(it.message ?: "检查失败") }
            )
        }
    }

    fun download(info: ReleaseInfo) {
        state = UpdateState.Downloading(0L, info.apkSizeBytes, info)
        scope.launch {
            val result = ApkDownloader.download(context, info.allUrls) { downloaded, total ->
                state = UpdateState.Downloading(downloaded, total, info)
            }
            state = result.fold(
                onSuccess = { UpdateState.Ready(info) },
                onFailure = { UpdateState.Error(it.message ?: "下载失败") }
            )
        }
    }

    fun install() {
        if (existingApk.exists()) ApkInstaller.install(context, existingApk)
    }

    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UpdateHeader(onBack)
            Spacer(Modifier.height(8.dp))

            when (val s = state) {
                UpdateState.Idle -> {
                    StatusIcon(Icons.Filled.SystemUpdate)
                    Text("检查新版本", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("当前版本 v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("检查更新") { checkUpdate() }
                }
                UpdateState.Checking -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在检查更新…", style = MaterialTheme.typography.titleMedium)
                }
                is UpdateState.UpToDate -> {
                    StatusIcon(Icons.Filled.CheckCircle)
                    Text("已是最新版本", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("本机 v${BuildConfig.VERSION_NAME} · 线上 v${s.remoteName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("再次检查") { checkUpdate() }
                }
                is UpdateState.Found -> {
                    StatusIcon(Icons.Filled.NewReleases)
                    Text("发现新版本", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("v${BuildConfig.VERSION_NAME} → v${s.info.versionName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    if (s.info.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                s.info.releaseNotes.take(200),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("下载更新") { download(s.info) }
                }
                is UpdateState.Downloading -> {
                    StatusIcon(Icons.Filled.Download)
                    Text("正在下载 v${s.info.versionName}",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val percent = if (s.total > 0) {
                        (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f)
                    } else null
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent },
                            modifier = Modifier.fillMaxWidth().height(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${(percent * 100).toInt()}%  ${fmtMb(s.downloaded)} / ${fmtMb(s.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // 服务器没给总大小：用不定态进度条 + 已下载字节，能看出在动
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("已下载 ${fmtMb(s.downloaded)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is UpdateState.Ready -> {
                    StatusIcon(Icons.Filled.InstallMobile)
                    Text("下载完成", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("v${s.info.versionName} 已就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    // 安装按钮始终可点：安装失败返回后可再次点击重装
                    PrimaryButton("安装") { install() }
                    Spacer(Modifier.height(8.dp))
                    Text("若安装失败，可返回后再次点击安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
                is UpdateState.Error -> {
                    StatusIcon(Icons.Filled.ErrorOutline)
                    Text("出错了", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(s.message, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    // 即使检查/下载失败，只要本地有包也给安装入口
                    if (existingApk.exists()) {
                        PrimaryButton("安装已下载的版本") { install() }
                        Spacer(Modifier.height(8.dp))
                    }
                    PrimaryButton("重试") { checkUpdate() }
                }
            }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun UpdateHeader(onBack: () -> Unit) {
    // 返回改为边缘右划手势
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("检查更新", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(64.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
        Alignment.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp))
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** 字节数格式化为 MB，方便确认下载确实在推进 */
private fun fmtMb(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return String.format("%.1f MB", mb)
}

/** 表冠可滚动的 ScrollState：收到表冠增量就滚动对应距离 */
@Composable
private fun rememberCrownScrollState(crownEvents: Flow<Float>): ScrollState {
    val state = rememberScrollState()
    // 表冠平滑滚动：离散事件累加成目标位移后逐帧逼近，避免一格一格的顿挫
    SmoothCrownScroll(crownEvents) { step ->
        state.scrollBy(step)
    }
    return state
}
