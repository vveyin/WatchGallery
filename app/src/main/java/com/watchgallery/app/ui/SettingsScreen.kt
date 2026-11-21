package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs

/**
 * 设置页 —— 全屏缩放列表（主页与所有二级菜单都有居中放大 + 表冠滚动）。
 * 居中项放大到 1.14 倍、上下边缘缩到 0.78 并淡出；滚动只改变大小/透明度，不改颜色，
 * 因此不会被误认为「当前选中」；只有手指按下时才有底色高亮和轻微回缩。
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
    // 一级菜单的滚动位置放在这一层：进二级页再回来时还停在原来的位置
    val mainListState = rememberLazyListState()

    // 一级 ↔ 二级转场。
    // 进二级：新页从右侧滑入、旧页稍向左让位并淡出。
    // 返回：**和大图页的退出动画完全一致** —— 当前页整屏向右滑出并淡成半透明，
    //      下层从 0.90 倍缩放回正、同时淡入（对应 SwipeBackContainer 的那套参数）。
    // 背景保持透明：底下是 SettingsDrawer 画的毛玻璃背板。
    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            val forward = targetState != SubPage.Main
            if (forward) {
                val enter = slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it } +
                        fadeIn(tween(190))
                val exit = slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 3 } +
                        fadeOut(tween(150))
                enter togetherWith exit
            } else {
                val enter = scaleIn(
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    initialScale = 0.90f
                ) + fadeIn(tween(240)) +
                        slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 20 }
                val exit = slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it } +
                        fadeOut(tween(240))
                enter togetherWith exit
            }
        },
        label = "settingsPage"
    ) { page ->
        when (page) {
            SubPage.Main -> SettingsMain(
                viewModel = viewModel,
                listState = mainListState,
                crownEvents = if (page == subPage) crownEvents else emptyFlow(),
                onOpenCrown = { subPage = SubPage.Crown },
                onOpenTheme = { subPage = SubPage.Theme },
                onOpenDiag = { subPage = SubPage.Diag },
                onOpenHidden = { subPage = SubPage.Hidden },
                onCheckUpdate = onCheckUpdate
            )
            else -> Box(
                Modifier
                    .fillMaxSize()
                    // 圆屏左缘右划返回一级菜单（替代整页跟手容器，避免和转场动画打架）
                    .edgeBackSwipe { subPage = SubPage.Main }
            ) {
                when (page) {
                    SubPage.Crown -> CrownSettings(
                        viewModel = viewModel,
                        crownEvents = if (page == subPage) crownEvents else emptyFlow(),
                        onPrefsChanged = onCrownPrefsChanged
                    )
                    SubPage.Theme -> ThemeSettings(
                        viewModel = viewModel,
                        crownEvents = if (page == subPage) crownEvents else emptyFlow()
                    )
                    SubPage.Diag -> ScanDiagSettings(
                        viewModel = viewModel,
                        crownEvents = if (page == subPage) crownEvents else emptyFlow()
                    )
                    SubPage.Hidden -> HiddenFoldersSettings(
                        viewModel = viewModel,
                        crownEvents = if (page == subPage) crownEvents else emptyFlow()
                    )
                    SubPage.Main -> {}
                }
            }
        }
    }
}

private sealed class SubPage {
    data object Main : SubPage()
    data object Crown : SubPage()
    data object Theme : SubPage()
    data object Diag : SubPage()
    data object Hidden : SubPage()
}

/** 设置主页 */
@Composable
private fun SettingsMain(
    viewModel: GalleryViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    crownEvents: Flow<Float>,
    onOpenCrown: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenHidden: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val showZoomButtons by viewModel.showZoomButtons.collectAsState()
    val blurBackdrop by viewModel.blurBackdrop.collectAsState()
    val animateGifs by viewModel.animateGifs.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importPhotos(uris) }

    ScalingScrollColumn(
        crownEvents = crownEvents,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        listState = listState
    ) {
        item(key = "settings_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("settings_title", scale = false),
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
        item(key = "row_mode") {
            SettingButton(
                icon = if (viewMode == GalleryViewMode.FOLDER) Icons.Filled.Folder else Icons.Filled.GridView,
                title = "浏览方式",
                subtitle = if (viewMode == GalleryViewMode.FOLDER) "文件夹 · 点击切回平铺列表"
                else "平铺列表 · 点击切到文件夹",
                onClick = { viewModel.toggleViewMode(); viewModel.closeFolder() },
                itemKey = "row_mode"
            )
        }
        item(key = "row_hidden") {
            SettingButton(
                icon = Icons.Filled.VisibilityOff,
                title = "隐藏文件夹",
                subtitle = if (hiddenFolders.isEmpty()) "未隐藏 · 点击选择要隐藏的文件夹"
                else "已隐藏 ${hiddenFolders.size} 个文件夹",
                onClick = onOpenHidden,
                itemKey = "row_hidden"
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
        item(key = "row_zoom") {
            SettingButton(
                icon = if (showZoomButtons) Icons.Filled.ZoomIn else Icons.Filled.ZoomOut,
                title = "缩放按键",
                subtitle = if (showZoomButtons) "开 · 查看大图时顶部显示放大/缩小"
                else "关 · 只用双指或表冠缩放",
                onClick = { viewModel.setShowZoomButtons(!showZoomButtons) },
                itemKey = "row_zoom"
            )
        }
        item(key = "row_gif") {
            SettingButton(
                icon = Icons.Filled.Gif,
                title = "列表显示动图",
                subtitle = if (animateGifs) "开 · 列表里的 GIF 会动"
                else "关 · 列表只显示首帧，点开仍是动图",
                onClick = { viewModel.setAnimateGifs(!animateGifs) },
                itemKey = "row_gif"
            )
        }
        item(key = "row_blur") {
            SettingButton(
                icon = if (blurBackdrop) Icons.Filled.BlurOn else Icons.Filled.BlurOff,
                title = "模糊背景",
                subtitle = if (blurBackdrop) "开 · 抽屉后面是毛玻璃背景"
                else "关 · 抽屉用纯色背板",
                onClick = { viewModel.setBlurBackdrop(!blurBackdrop) },
                itemKey = "row_blur"
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
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("crown_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("表冠适配", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "crown_desc") {
            Box(Modifier.fillMaxWidth().settingItem("crown_desc", scale = false)) {
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
            Box(Modifier.fillMaxWidth().settingItem("crown_mode_label", scale = false)) {
                Text("模式", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        CrownMode.entries.forEach { m ->
            item(key = "crown_mode_${m.name}") {
                val mk = "crown_mode_${m.name}"
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = mode == m,
                        label = "${m.label} — ${m.description}",
                        onClick = { mode = m; commit() },
                        modifier = Modifier.fillMaxWidth().height(42.dp).settingItem(mk)
                    )
                }
            }
        }
        item(key = "crown_speed") {
            Box(Modifier.fillMaxWidth().settingItem("crown_speed", scale = false)) {
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
            Box(Modifier.fillMaxWidth().settingItem("crown_test", scale = false)) {
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
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("theme_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("主题", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        options.forEach { (mode, label) ->
            item(key = "theme_${mode.name}") {
                val mk = "theme_${mode.name}"
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = current == mode,
                        label = label,
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier.fillMaxWidth().height(42.dp).settingItem(mk)
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
    // 点击高亮：按下时底色向 primary 偏一点 + 整体轻微回缩。
    // 滚动时不做任何颜色变化 —— 只有位置形变，不会被误认为「选中」。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = tween(90),
        label = "settingPress"
    )
    val container = if (pressed) {
        lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    // 54dp 的「槽位」：按钮本体 46dp，居中放大到 1.12 倍（≈51.5dp）仍待在槽位里，
    // 因此相邻条目永远不会因为放大而重叠。
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .settingItem(itemKey),
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
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

/**
 * 隐藏文件夹子页：列出所有「已识别到有图片」的文件夹，点一行切换隐藏。
 * 隐藏后该文件夹（含子文件夹）的照片在平铺和文件夹两种视图里都不显示。
 */
@Composable
private fun HiddenFoldersSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val folders by viewModel.allFolders.collectAsState()
    val hidden by viewModel.hiddenFolders.collectAsState()

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "hidden_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("hidden_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("隐藏文件夹", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "hidden_desc") {
            Box(Modifier.fillMaxWidth().settingItem("hidden_desc", scale = false)) {
                Text(
                    "选中后该文件夹的照片在平铺列表和文件夹视图里都不显示，子文件夹一起隐藏。再点一次恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (folders.isEmpty()) {
            item(key = "hidden_empty") {
                Box(Modifier.fillMaxWidth().settingItem("hidden_empty", scale = false)) {
                    Text("还没有识别到文件夹", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        folders.forEach { folder ->
            item(key = "hidden_${folder.path}") {
                val selfHidden = folder.path in hidden
                val inherited = !selfHidden && com.watchgallery.app.data.isFolderHidden(folder.path, hidden)
                val mk = "hidden_${folder.path}"
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    val isHidden = selfHidden || inherited
                    Button(
                        onClick = { if (!inherited) viewModel.toggleFolderHidden(folder.path) },
                        modifier = Modifier.fillMaxWidth().height(48.dp).settingItem(mk),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isHidden) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        ),
                        border = if (isHidden) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(
                                buildString {
                                    append("${folder.count} 张")
                                    if (folder.parentPath.isNotBlank()) append(" · ${folder.parentPath}")
                                    if (inherited) append(" · 随上级隐藏")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Icon(
                            if (isHidden) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
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
    val photos by viewModel.allPhotosFlow.collectAsState()

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "diag_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("diag_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("扫描诊断", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "diag_count") {
            Box(Modifier.fillMaxWidth().settingItem("diag_count", scale = false)) {
                Text("共 ${photos.size} 张", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        item(key = "diag_lines") {
            Box(Modifier.fillMaxWidth().settingItem("diag_lines", scale = false)) {
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
                Box(Modifier.fillMaxWidth().settingItem("diag_roots", scale = false)) {
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
                Box(Modifier.fillMaxWidth().settingItem("diag_errors", scale = false)) {
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
                    modifier = Modifier.fillMaxWidth().height(42.dp).settingItem("diag_rescan")) {
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

/**
 * 设置列表条目形变 —— 恢复「居中放大」：正中间最大（1.12 倍），越靠近上下边缘越小（0.76）
 * 并淡出。这是 Wear OS 设置列表的经典层次感。
 *
 * 与旧版「滚动高亮」的区别：形变只动 `scale / alpha`，**不动任何颜色**，
 * 所以滚到中间不会被误认为「这一条被选中了」；点击时的高亮由 [SettingButton] 自己处理。
 *
 * 放大后的溢出量：46dp × 0.12 ≈ 5.5dp，两侧各 2.75dp，小于条目间距（4dp），
 * 并且都待在 54dp 槽位内，不会互相压住。
 */
@Composable
private fun Modifier.settingItem(key: String, scale: Boolean = true): Modifier {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val centerY = remember(key) { mutableFloatStateOf(-1f) }
    val measured = remember(key) { mutableStateOf(false) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            centerY.floatValue = pos.y + coords.size.height / 2f
            measured.value = true
        }
        .graphicsLayer {
            val cy = centerY.floatValue
            val vc = screenHeightPx / 2f
            if (measured.value && vc > 0f) {
                val t = (abs(cy - vc) / (vc * 1.02f)).coerceIn(0f, 1f)
                if (scale) {
                    // 居中 1.12 → 边缘 0.76，二次曲线让中间那块更「聚」
                    val s = 1.12f - 0.36f * (t * t)
                    scaleX = s
                    scaleY = s
                }
                alpha = 1f - 0.40f * t
            }
        }
}

/**
 * 圆屏左缘右划返回：从屏幕左侧 26dp 内起手、横向位移超过 56dp 就触发。
 * 不做跟手位移 —— 转场动画由 [AnimatedContent] 统一负责，避免两套动画叠在一起。
 */
private fun Modifier.edgeBackSwipe(onBack: () -> Unit): Modifier = pointerInput(onBack) {
    val edge = 26.dp.toPx()
    val threshold = 56.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x > edge) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.all { !it.pressed }) break
            val c = event.changes.firstOrNull { it.id == down.id } ?: break
            dx += c.position.x - c.previousPosition.x
            dy += c.position.y - c.previousPosition.y
            if (abs(dy) > abs(dx) * 1.4f && abs(dy) > threshold) break
            if (dx > threshold) {
                c.consume()
                onBack()
                break
            }
        }
    }
}

/** 灵敏度显示：小于 1x 保留两位（0.15x），1x 以上保留一位（2.5x / 10.0x） */
private fun formatSpeed(speed: Float): String =
    if (speed < 1f) String.format(java.util.Locale.US, "%.2f", speed)
    else String.format(java.util.Locale.US, "%.1f", speed)
