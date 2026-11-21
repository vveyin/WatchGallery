package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
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
import kotlinx.coroutines.flow.emptyFlow

/**
 * 设置页 —— 全屏缩放列表（主页与所有二级菜单都有居中形变 + 表冠滚动）。
 * 按钮满宽，居中正常大小、边缘缩小淡出（Wear OS 官方 TransformingLazyColumn 风格）；间隔固定。
 */
@Composable
fun SettingsScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onCrownPrefsChanged: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    onSubPageChanged: (Boolean) -> Unit = {}
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }
    LaunchedEffect(subPage) { onSubPageChanged(subPage == SubPage.Main) }

    // 主页直接显示；二级菜单用返回容器包裹，右划回到一级菜单（一级菜单的下滑收抽屉由外层把手处理）。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (subPage) {
            SubPage.Main -> SettingsMain(
                viewModel = viewModel,
                crownEvents = crownEvents,
                onOpenCrown = { subPage = SubPage.Crown },
                onOpenTheme = { subPage = SubPage.Theme },
                onOpenDiag = { subPage = SubPage.Diag },
                onCheckUpdate = onCheckUpdate
            )
            else -> SwipeBackContainer(
                onBack = { subPage = SubPage.Main },
                backContent = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsMain(
                            viewModel = viewModel,
                            crownEvents = emptyFlow(),
                            onOpenCrown = {},
                            onOpenTheme = {},
                            onOpenDiag = {},
                            onCheckUpdate = {}
                        )
                    }
                },
                content = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        when (subPage) {
                            SubPage.Crown -> CrownSettings(
                                viewModel = viewModel,
                                crownEvents = crownEvents,
                                onPrefsChanged = onCrownPrefsChanged
                            )
                            SubPage.Theme -> ThemeSettings(viewModel = viewModel, crownEvents = crownEvents)
                            SubPage.Diag -> ScanDiagSettings(viewModel = viewModel, crownEvents = crownEvents)
                            SubPage.Main -> {}
                        }
                    }
                }
            )
        }
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
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importPhotos(uris) }

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "settings_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("settings_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("设置", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "row_crown") {
            SettingButton(
                icon = Icons.Filled.Cached,
                title = "表冠适配",
                subtitle = "${crownMode.label} · ${formatSpeed(crownSpeed)}x",
                onClick = onOpenCrown,
                itemKey = "row_crown"
            )
        }
        item(key = "row_grid") {
            SettingButton(
                icon = Icons.Filled.GridView,
                title = "每排照片",
                subtitle = "$gridColumns 张 · 点击切换 2/3 张",
                onClick = { viewModel.setGridColumns(if (gridColumns == 2) 3 else 2) },
                itemKey = "row_grid"
            )
        }
        item(key = "row_names") {
            SettingButton(
                icon = if (showPhotoNames) Icons.AutoMirrored.Filled.Label else Icons.AutoMirrored.Filled.LabelOff,
                title = "显示图片名",
                subtitle = if (showPhotoNames) "开 · 照片底部显示文件名" else "关 · 只看照片",
                onClick = { viewModel.setShowPhotoNames(!showPhotoNames) },
                itemKey = "row_names"
            )
        }
        item(key = "row_theme") {
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
                onClick = onOpenTheme,
                itemKey = "row_theme"
            )
        }
        item(key = "row_diag") {
            SettingButton(
                icon = Icons.Filled.Search,
                title = "扫描诊断",
                subtitle = "媒体库 ${report.imagesRows} · 目录 ${report.dirHits} · 导入 ${report.importedCount}",
                onClick = onOpenDiag,
                itemKey = "row_diag"
            )
        }
        item(key = "row_import") {
            SettingButton(
                icon = Icons.Filled.AddPhotoAlternate,
                title = "导入照片",
                subtitle = "从文件选择器添加照片",
                onClick = { runCatching { importLauncher.launch(arrayOf("image/*")) } },
                itemKey = "row_import"
            )
        }
        item(key = "row_log") {
            SettingButton(
                icon = Icons.Filled.ContentCopy,
                title = "复制日志",
                subtitle = "崩溃与运行日志，用于排查问题",
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("WatchGallery 日志", CrashLogger.read(context)))
                    Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                },
                itemKey = "row_log"
            )
        }
        item(key = "row_update") {
            SettingButton(
                icon = Icons.Filled.SystemUpdate,
                title = "检测更新",
                subtitle = "检查并安装新版本",
                onClick = onCheckUpdate,
                itemKey = "row_update"
            )
        }
        item(key = "row_about") {
            SettingButton(
                icon = Icons.Filled.Info,
                title = "关于",
                subtitle = "相册 v${com.watchgallery.app.BuildConfig.VERSION_NAME} · 圆屏手表版",
                onClick = {},
                itemKey = "row_about"
            )
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
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("crown_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("表冠适配", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "crown_desc") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_desc", scale = false)) {
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
            Box(Modifier.fillMaxWidth().scalingItem("crown_mode_label", scale = false)) {
                Text("模式", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        CrownMode.entries.forEach { m ->
            item(key = "crown_mode_${m.name}") {
                val mk = "crown_mode_${m.name}"
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = mode == m,
                        label = "${m.label} — ${m.description}",
                        onClick = { mode = m; commit() },
                        modifier = Modifier.fillMaxWidth().height(42.dp).scalingItem(mk)
                    )
                }
            }
        }
        item(key = "crown_speed") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_speed", scale = false)) {
                Column {
                    Text("灵敏度  ${formatSpeed(speed)}x",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    // 对数轴：滑杆位置 0~1 等比映射到 0.1x~10.0x
                    Slider(
                        value = CrownPrefs.speedToSlider(speed),
                        valueRange = 0f..1f,
                        onValueChange = { speed = CrownPrefs.sliderToSpeed(it) },
                        onValueChangeFinished = { commit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.1x", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1.0x", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("10.0x", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "crown_test") {
            Box(Modifier.fillMaxWidth().scalingItem("crown_test", scale = false)) {
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
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("theme_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("主题", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        options.forEach { (mode, label) ->
            item(key = "theme_${mode.name}") {
                val mk = "theme_${mode.name}"
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = current == mode,
                        label = label,
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier.fillMaxWidth().height(42.dp).scalingItem(mk)
                    )
                }
            }
        }
    }
}

/**
 * Material3 标准全圆角按钮（Wear OS pill 风格）：满宽。
 * 由 [scalingItem] 做「只缩不放」形变 —— 居中正常大小（≈满屏宽），边缘缩小淡出。
 * 底色用 surfaceVariant（淡灰），不再用 primary 紫蓝填充：设置条属于导航项，
 * 满屏紫色会抢走注意力，也很难和选中态区分。
 */
@Composable
private fun SettingButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    itemKey: String
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .scalingItem(itemKey),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null,
                modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * 选项按钮（单选）：未选中 = 描边；选中 = 淡灰底 + primary 描边与文字。
 * 不用 primary 满填，避免整页紫蓝色块。
 */
@Composable
private fun OptionButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
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
            Box(Modifier.fillMaxWidth().height(28.dp).scalingItem("diag_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("扫描诊断", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "diag_count") {
            Box(Modifier.fillMaxWidth().scalingItem("diag_count", scale = false)) {
                Text("共 ${photos.size} 张", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        item(key = "diag_lines") {
            Box(Modifier.fillMaxWidth().scalingItem("diag_lines", scale = false)) {
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
                Box(Modifier.fillMaxWidth().scalingItem("diag_roots", scale = false)) {
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
                Box(Modifier.fillMaxWidth().scalingItem("diag_errors", scale = false)) {
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { viewModel.refresh() },
                    modifier = Modifier.fillMaxWidth().height(42.dp).scalingItem("diag_rescan")) {
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

/** 灵敏度显示：小于 1x 保留两位（0.15x），1x 以上保留一位（2.5x / 10.0x） */
private fun formatSpeed(speed: Float): String =
    if (speed < 1f) String.format(java.util.Locale.US, "%.2f", speed)
    else String.format(java.util.Locale.US, "%.1f", speed)
