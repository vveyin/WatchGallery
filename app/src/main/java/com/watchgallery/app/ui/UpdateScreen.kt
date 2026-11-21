package com.watchgallery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 * 检查更新页 —— 视觉与设置页统一：
 *
 *  - 同样的缩放滚动列表（表冠可滚、圆屏自动避开弧边）；
 *  - 一张淡灰底的「状态卡」：圆角图标 + 标题 + 说明，版本号与更新说明都放在卡里；
 *  - 操作用满宽药丸按钮，与设置里的条目不打架、不再出现整块紫色大按钮。
 *
 * 流程不变：检查 → 发现新版本 → 下载（进度条在卡内）→ 安装。
 * 安装按钮始终可点，即使上次安装失败也能重试（APK 保留在稳定路径）。
 */
@Composable
fun UpdateScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float> = kotlinx.coroutines.flow.emptyFlow(),
    onBack: () -> Unit
) {
    // 与设置抽屉同款毛玻璃背景：已经抓过底图就铺那张糊好的图，没有就露出底层纯黑。
    // 设置里的「检测更新」是抽屉内的二级页，本来就带着这层背景；这里独立打开时补齐。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        if (BlurBackdrop.image != null) {
            BlurBand(heightPx = { h }, dim = 0.55f)
        }
        UpdateContent(viewModel = viewModel, crownEvents = crownEvents)
    }
}

/**
 * 检测更新的全部界面内容（不含背景与返回手势）。
 * 独立页与设置抽屉里的二级页共用这一份，避免两处界面各写一遍。
 */
@Composable
fun UpdateContent(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // 启动检测已经把新版本信息放进 ViewModel：直接展示「发现新版本」，不重复请求
    val pendingUpdate by viewModel.pendingUpdate.collectAsState()
    LaunchedEffect(pendingUpdate) {
        pendingUpdate?.let {
            viewModel.consumePendingUpdate()
            state = UpdateState.Found(it)
        }
    }

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
                        )
                    ) {
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

    val localVer = BuildConfig.VERSION_NAME

    ScalingScrollColumn(
        crownEvents = crownEvents,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "upd_title") {
            Box(
                Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "检查更新",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // ── 状态卡：所有版本信息都收在这一张卡里 ──
        item(key = "upd_card") {
            when (val s = state) {
                UpdateState.Idle -> StatusCard(
                    icon = Icons.Filled.SystemUpdate,
                    title = "检查新版本",
                    subtitle = "当前版本 v$localVer",
                    footnote = "国内走 Gitee，失败自动切 GitHub"
                )
                UpdateState.Checking -> StatusCard(
                    icon = Icons.Filled.SystemUpdate,
                    title = "正在检查更新…",
                    subtitle = "当前版本 v$localVer",
                    loading = true
                )
                is UpdateState.UpToDate -> StatusCard(
                    icon = Icons.Filled.CheckCircle,
                    title = "已是最新版本",
                    subtitle = "本机 v$localVer · 线上 v${s.remoteName}"
                )
                is UpdateState.Found -> StatusCard(
                    icon = Icons.Filled.NewReleases,
                    title = "发现新版本 v${s.info.versionName}",
                    subtitle = "v$localVer → v${s.info.versionName}",
                    footnote = s.info.releaseNotes.take(160).ifBlank { null }
                )
                is UpdateState.Downloading -> {
                    val percent = if (s.total > 0) {
                        (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f)
                    } else null
                    StatusCard(
                        icon = Icons.Filled.Download,
                        title = "正在下载 v${s.info.versionName}",
                        subtitle = if (percent != null) {
                            "${(percent * 100).toInt()}%  ${fmtMb(s.downloaded)} / ${fmtMb(s.total)}"
                        } else {
                            "已下载 ${fmtMb(s.downloaded)}"
                        },
                        progress = percent
                    )
                }
                is UpdateState.Ready -> StatusCard(
                    icon = Icons.Filled.InstallMobile,
                    title = "下载完成",
                    subtitle = "v${s.info.versionName} 已就绪，点击下方安装",
                    footnote = "安装失败可直接再点一次"
                )
                is UpdateState.Error -> StatusCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = "检查失败",
                    subtitle = s.message,
                    isError = true
                )
            }
        }

        // ── 主操作 ──
        when (val s = state) {
            UpdateState.Idle -> item(key = "upd_act") {
                PrimaryButton("检查更新") { checkUpdate() }
            }
            UpdateState.Checking -> Unit
            is UpdateState.UpToDate -> item(key = "upd_act") {
                PrimaryButton("再次检查") { checkUpdate() }
            }
            is UpdateState.Found -> item(key = "upd_act") {
                PrimaryButton("下载更新") { download(s.info) }
            }
            is UpdateState.Downloading -> Unit
            is UpdateState.Ready -> item(key = "upd_act") {
                PrimaryButton("安装") { install() }
            }
            is UpdateState.Error -> {
                if (existingApk.exists()) {
                    item(key = "upd_act_install") {
                        PrimaryButton("安装已下载的版本") { install() }
                    }
                    item(key = "upd_act_retry") {
                        SecondaryButton("重新检查") { checkUpdate() }
                    }
                } else {
                    item(key = "upd_act") {
                        PrimaryButton("重试") { checkUpdate() }
                    }
                }
            }
        }
    }
}

/**
 * 状态卡：图标徽章 + 标题 + 说明（可选副注）+ 可选进度条。
 * 底色用 surfaceVariant，与设置条目一致；错误态换成 errorContainer，一眼能区分。
 */
@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    footnote: String? = null,
    progress: Float? = null,
    loading: Boolean = false,
    isError: Boolean = false
) {
    val badgeBg = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val badgeFg = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = badgeFg
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }

            if (!footnote.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 满宽药丸主按钮（与设置里的条目同宽、同圆角语言） */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 次要操作：描边按钮 */
@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 字节数格式化为 MB，方便确认下载确实在推进 */
private fun fmtMb(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return String.format("%.1f MB", mb)
}
