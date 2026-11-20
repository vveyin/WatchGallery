package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.flow.Flow

/**
 * 设置页 —— 圆屏安全区内布局，分主菜单与「表冠适配」子页。
 * 借鉴 f-x：表冠模式 + 灵敏度是独立子页，点击即可切换/调节。
 */
@Composable
fun SettingsScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onCrownPrefsChanged: () -> Unit
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }

    when (subPage) {
        SubPage.Main -> SettingsMain(
            viewModel = viewModel,
            isRound = isRound,
            crownEvents = crownEvents,
            onBack = onBack,
            onOpenCrown = { subPage = SubPage.Crown },
            onOpenTheme = { subPage = SubPage.Theme },
            onOpenDiag = { subPage = SubPage.Diag }
        )
        SubPage.Crown -> CrownSettings(
            viewModel = viewModel,
            isRound = isRound,
            crownEvents = crownEvents,
            onBack = { subPage = SubPage.Main },
            onPrefsChanged = onCrownPrefsChanged
        )
        SubPage.Theme -> ThemeSettings(
            viewModel = viewModel,
            isRound = isRound,
            crownEvents = crownEvents,
            onBack = { subPage = SubPage.Main }
        )
        SubPage.Diag -> ScanDiagSettings(
            viewModel = viewModel,
            isRound = isRound,
            crownEvents = crownEvents,
            onBack = { subPage = SubPage.Main }
        )
    }
}

private sealed class SubPage {
    data object Main : SubPage()
    data object Crown : SubPage()
    data object Theme : SubPage()
    data object Diag : SubPage()
}

/** 设置主页 */
@Composable
private fun SettingsMain(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onOpenCrown: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDiag: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val context = LocalContext.current

    RoundSafeArea(isRound) {
        Column(Modifier.fillMaxSize()) {
            SettingsHeader("设置", onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberCrownScrollState(crownEvents)),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingRow(
                    icon = Icons.Filled.Cached,
                    title = "表冠适配",
                    subtitle = "${crownMode.label} · ${String.format("%.1f", crownSpeed)}x",
                    onClick = onOpenCrown
                )
                SettingRow(
                    icon = when (themeMode) {
                        ThemeMode.DARK -> Icons.Filled.DarkMode
                        ThemeMode.LIGHT -> Icons.Filled.LightMode
                        else -> Icons.Filled.BrightnessAuto
                    },
                    title = "主题",
                    subtitle = when (themeMode) {
                        ThemeMode.DARK -> "夜间"
                        ThemeMode.LIGHT -> "白天"
                        else -> "跟随系统"
                    },
                    onClick = onOpenTheme
                )
                SettingRow(
                    icon = Icons.Filled.Search,
                    title = "扫描诊断",
                    subtitle = "媒体库 ${report.imagesRows} 条 · 目录 ${report.dirHits} 张 · 导入 ${report.importedCount} 张",
                    onClick = onOpenDiag
                )
                SettingRow(
                    icon = Icons.Filled.ContentCopy,
                    title = "复制日志",
                    subtitle = "崩溃与运行日志，用于排查问题",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("WatchGallery 日志", CrashLogger.read(context)))
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                )
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "关于",
                    subtitle = "相册 v${com.watchgallery.app.BuildConfig.VERSION_NAME} · 圆屏手表版",
                    onClick = {}
                )
            }
        }
    }
}

/** 表冠适配子页 */
@Composable
private fun CrownSettings(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onPrefsChanged: () -> Unit
) {
    var mode by remember { mutableStateOf(viewModel.crownMode.value) }
    var speed by remember { mutableFloatStateOf(viewModel.crownSpeed.value) }
    val debug by viewModel.crownDebug.collectAsState()

    fun commit() {
        viewModel.setCrownConfig(mode, speed)
        onPrefsChanged()
    }

    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberCrownScrollState(crownEvents))
        ) {
            SettingsHeader("表冠适配", onBack)
            Spacer(Modifier.height(4.dp))
            Text(
                "不同手表表冠上报方式不同，选错会导致表冠无反应。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("模式", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            CrownMode.entries.forEach { m ->
                val selected = mode == m
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { mode = m; commit() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { mode = m; commit() },
                            modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(m.label, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(m.description, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("灵敏度  ${String.format("%.1f", speed)}x",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Slider(
                value = speed,
                valueRange = CrownPrefs.SPEED_MIN..CrownPrefs.SPEED_MAX,
                onValueChange = { speed = it },
                onValueChangeFinished = { commit() },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("慢", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("快", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Text("表冠测试", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(
                        "转动表冠，下方若出现数值说明当前模式已能识别；一直显示「未检测到」请换另一模式。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        debug ?: "未检测到表冠事件",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (debug != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 主题设置子页 */
@Composable
private fun ThemeSettings(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit
) {
    val current by viewModel.themeMode.collectAsState()
    val options = listOf(
        ThemeMode.DARK to "夜间（黑底）",
        ThemeMode.LIGHT to "白天（白纸）",
        ThemeMode.SYSTEM to "跟随系统"
    )
    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberCrownScrollState(crownEvents))
        ) {
            SettingsHeader("主题", onBack)
            Spacer(Modifier.height(8.dp))
            options.forEach { (mode, label) ->
                val selected = current == mode
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.setThemeMode(mode) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 扫描诊断子页 —— "相册空白"时用来自证数据到底卡在哪一层：
 * 权限、媒体库行数、目录命中数、异常信息都在这里。
 */
@Composable
private fun ScanDiagSettings(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit
) {
    val report by viewModel.scanReport.collectAsState()
    val photos by viewModel.photos.collectAsState()

    RoundSafeArea(isRound) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberCrownScrollState(crownEvents))
        ) {
            SettingsHeader("扫描诊断", onBack)
            Text(
                "共 ${photos.size} 张",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            DiagLine("Android 版本", report.sdk.toString())
            DiagLine("读取权限", if (report.permissionGranted) "已授予" else "未授予")
            DiagLine("媒体库(images)", "${report.imagesRows} 条")
            DiagLine("媒体库(files)", "${report.filesRows} 条")
            DiagLine("目录直扫", "${report.dirHits} 张")
            DiagLine("应用内导入", "${report.importedCount} 张")
            DiagLine("触发系统扫描", if (report.scanTriggered) "已触发" else "未触发")

            if (report.roots.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("扫描目录", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    report.roots.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (report.errors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("异常", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error)
                Text(
                    report.errors.distinct().joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text("重新扫描")
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

/** 表冠可滚动的 ScrollState：收到表冠增量就滚动对应距离，转多少滚多少 */
@Composable
private fun rememberCrownScrollState(crownEvents: Flow<Float>): ScrollState {
    val state = rememberScrollState()
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            state.scrollBy(-delta * 8f)
        }
    }
    return state
}
