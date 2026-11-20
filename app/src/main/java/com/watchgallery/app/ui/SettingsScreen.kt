package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.flow.Flow

/**
 * 设置页 —— 全屏缩放列表（主页与所有二级菜单都有居中放大 + 表冠滚动）。
 * 控件统一用 Material3 标准全圆角 Button，紧凑不占满屏。
 */
@Composable
fun SettingsScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onCrownPrefsChanged: () -> Unit,
    onCheckUpdate: () -> Unit = {}
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }

    when (subPage) {
        SubPage.Main -> SettingsMain(
            viewModel = viewModel,
            crownEvents = crownEvents,
            onOpenCrown = { subPage = SubPage.Crown },
            onOpenTheme = { subPage = SubPage.Theme },
            onOpenDiag = { subPage = SubPage.Diag },
            onCheckUpdate = onCheckUpdate
        )
        SubPage.Crown -> CrownSettings(
            viewModel = viewModel,
            crownEvents = crownEvents,
            onPrefsChanged = onCrownPrefsChanged
        )
        SubPage.Theme -> ThemeSettings(viewModel = viewModel, crownEvents = crownEvents)
        SubPage.Diag -> ScanDiagSettings(viewModel = viewModel, crownEvents = crownEvents)
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
    crownEvents: Flow<Float>,
    onOpenCrown: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDiag: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importPhotos(uris) }

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "settings_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("settings_title"),
                contentAlignment = Alignment.Center) {
                Text("设置", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "row_crown") {
            Box(Modifier.fillMaxWidth().scalingItem("row_crown")) {
                SettingButton(
                    icon = Icons.Filled.Cached,
                    title = "表冠适配",
                    subtitle = "${crownMode.label} · ${String.format("%.1f", crownSpeed)}x",
                    onClick = onOpenCrown
                )
            }
        }
        item(key = "row_grid") {
            Box(Modifier.fillMaxWidth().scalingItem("row_grid")) {
                SettingButton(
                    icon = Icons.Filled.GridView,
                    title = "每排照片",
                    subtitle = "$gridColumns 张 · 点击切换 2/3 张",
                    onClick = { viewModel.setGridColumns(if (gridColumns == 2) 3 else 2) }
                )
            }
        }
        item(key = "row_theme") {
            Box(Modifier.fillMaxWidth().scalingItem("row_theme")) {
                SettingButton(
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
            }
        }
        item(key = "row_diag") {
            Box(Modifier.fillMaxWidth().scalingItem("row_diag")) {
                SettingButton(
                    icon = Icons.Filled.Search,
                    title = "扫描诊断",
                    subtitle = "媒体库 ${report.imagesRows} · 目录 ${report.dirHits} · 导入 ${report.importedCount}",
                    onClick = onOpenDiag
                )
            }
        }
        item(key = "row_import") {
            Box(Modifier.fillMaxWidth().scalingItem("row_import")) {
                SettingButton(
                    icon = Icons.Filled.AddPhotoAlternate,
                    title = "导入照片",
                    subtitle = "从文件选择器添加照片",
                    onClick = { runCatching { importLauncher.launch(arrayOf("image/*")) } }
                )
            }
        }
        item(key = "row_log") {
            Box(Modifier.fillMaxWidth().scalingItem("row_log")) {
                SettingButton(
                    icon = Icons.Filled.ContentCopy,
                    title = "复制日志",
                    subtitle = "崩溃与运行日志，用于排查问题",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("WatchGallery 日志", CrashLogger.read(context)))
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        item(key = "row_update") {
            Box(Modifier.fillMaxWidth().scalingItem("row_update")) {
                SettingButton(
                    icon = Icons.Filled.SystemUpdate,
                    title = "检测更新",
                    subtitle = "检查并安装新版本",
                    onClick = onCheckUpdate
                )
            }
        }
        item(key = "row_about") {
            Box(Modifier.fillMaxWidth().scalingItem("row_about")) {
                SettingButton(
                    icon = Icons.Filled.Info,
                    title = "关于",
                    subtitle = "相册 v${com.watchgallery.app.BuildConfig.VERSION_NAME} · 圆屏手表版",
                    onClick = {}
                )
            }
        }
    }
}

/** 表冠适配子页（缩放列表） */
@Composable
private fun CrownSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>,
    onPrefsChanged: () -> Unit
) {
    var mode by remember { mutableStateOf(viewModel.crownMode.value) }
    var speed by remember { mutableFloatStateOf(viewModel.crownSpeed.value) }
    val debug by viewModel.crownDebug.collectAsState()

    fun commit() {
        viewModel.setCrownConfig(mode, speed)
        onPrefsChanged()
    }

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "crown_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("crown_title"),
                contentAlignment = Alignment.Center) {
                Text("表冠适配", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "crown_desc") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_desc")) {
                Text(
                    "不同手表表冠上报方式不同，选错会导致表冠无反应。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item(key = "crown_mode_label") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_mode_label")) {
                Text("模式", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        CrownMode.entries.forEach { m ->
            item(key = "crown_mode_${m.name}") {
                Box(Modifier.fillMaxWidth().scalingItem("crown_mode_${m.name}")) {
                    val selected = mode == m
                    if (selected) {
                        Button(
                            onClick = { mode = m; commit() },
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("${m.label} — ${m.description}",
                                style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { mode = m; commit() },
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("${m.label} — ${m.description}",
                                style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
        item(key = "crown_speed") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_speed")) {
                Column {
                    Text("灵敏度  ${String.format("%.1f", speed)}x",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = speed,
                        valueRange = CrownPrefs.SPEED_MIN..CrownPrefs.SPEED_MAX,
                        onValueChange = { speed = it },
                        onValueChangeFinished = { commit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item(key = "crown_test") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_test")) {
                Column {
                    Text("表冠测试", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
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

/** 主题设置子页（缩放列表） */
@Composable
private fun ThemeSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val current by viewModel.themeMode.collectAsState()
    val options = listOf(
        ThemeMode.DARK to "夜间（黑底）",
        ThemeMode.LIGHT to "白天（白纸）",
        ThemeMode.SYSTEM to "跟随系统"
    )
    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "theme_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("theme_title"),
                contentAlignment = Alignment.Center) {
                Text("主题", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        options.forEach { (mode, label) ->
            item(key = "theme_${mode.name}") {
                Box(Modifier.fillMaxWidth().scalingItem("theme_${mode.name}")) {
                    val selected = current == mode
                    if (selected) {
                        Button(
                            onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** Material3 标准全圆角按钮样式的设置项 */
@Composable
private fun SettingButton(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f), maxLines = 1)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            modifier = Modifier.size(16.dp))
    }
}

/** 扫描诊断子页（缩放列表） */
@Composable
private fun ScanDiagSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val report by viewModel.scanReport.collectAsState()
    val photos by viewModel.photos.collectAsState()

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "diag_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("diag_title"),
                contentAlignment = Alignment.Center) {
                Text("扫描诊断", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "diag_count") {
            Box(Modifier.fillMaxWidth().scalingItem("diag_count")) {
                Text("共 ${photos.size} 张", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        item(key = "diag_lines") {
            Box(Modifier.fillMaxWidth().scalingItem("diag_lines")) {
                Column {
                    DiagLine("Android 版本", report.sdk.toString())
                    DiagLine("读取权限", if (report.permissionGranted) "已授予" else "未授予")
                    DiagLine("媒体库(images)", "${report.imagesRows} 条")
                    DiagLine("媒体库(files)", "${report.filesRows} 条")
                    DiagLine("目录直扫", "${report.dirHits} 张")
                    DiagLine("应用内导入", "${report.importedCount} 张")
                    DiagLine("触发系统扫描", if (report.scanTriggered) "已触发" else "未触发")
                }
            }
        }
        if (report.roots.isNotEmpty()) {
            item(key = "diag_roots") {
                Box(Modifier.fillMaxWidth().scalingItem("diag_roots")) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text("扫描目录", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(report.roots.joinToString("\n"), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (report.errors.isNotEmpty()) {
            item(key = "diag_errors") {
                Box(Modifier.fillMaxWidth().scalingItem("diag_errors")) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text("异常", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error)
                        Text(report.errors.distinct().joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item(key = "diag_rescan") {
            Box(Modifier.fillMaxWidth().scalingItem("diag_rescan")) {
                Button(onClick = { viewModel.refresh() }, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                    Text("重新扫描")
                }
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
