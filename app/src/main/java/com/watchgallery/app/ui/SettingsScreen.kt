package com.watchgallery.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchgallery.app.BuildConfig
import com.watchgallery.app.R
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.ScreenShape
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置页 —— 全屏缩放列表（主页与所有二级菜单都有表冠滚动）。
 * 居中项适度放大、上下边缘缩小并淡出；滚动只改变大小/透明度，不改颜色，
 * 因此不会被误认为「当前选中」；只有手指按下时才有底色高亮和轻微回缩。
 *
 * 一级菜单按板块分组：手表适配 / 显示设置 / 其他功能 / 检测更新 / 关于。
 * 二级页（含检测更新、关于）都在设置抽屉内渲染，因此自动带上抽屉那层毛玻璃背景。
 *
 * 二级页右划返回**跟手**：从屏幕左缘按下往右拖，当前页实时跟着手指移动、
 * 一级菜单在后面从 0.90 倍放大回正 —— 与查看大图的右划返回完全同一套参数。
 */
@Composable
fun SettingsScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float>,
    onBack: () -> Unit,
    onCrownPrefsChanged: () -> Unit,
    onSubPageChanged: (Boolean) -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenLog: () -> Unit = {}
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }
    LaunchedEffect(subPage) { onSubPageChanged(subPage == SubPage.Main) }
    // 一级菜单的滚动位置放在这一层：进二级页再回来时还停在原来的位置
    val mainListState = rememberLazyListState()

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 二级页的水平偏移：widthPx = 一级页（正常态），0 = 二级页完全铺满。
    // 初值是 NaN 而不是 0f —— 0f 会被算成「二级页完全打开」，一级菜单整页缩到
    // 0.90 倍并左移 5% 屏宽，设置条看着既不居中也没有居中放大。
    // 首帧宽度未知，先给 NaN，等 BoxWithConstraints 量出宽度再 snapTo(widthPx)。
    val offsetX = remember { Animatable(Float.NaN) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

        LaunchedEffect(widthPx) {
            if (!offsetX.value.isFinite()) offsetX.snapTo(widthPx)
        }

        // 打开二级页：从右侧滑入（一级菜单同步从后面退开）
        LaunchedEffect(subPage) {
            if (subPage != SubPage.Main) {
                offsetX.snapTo(widthPx)
                offsetX.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            }
        }

        // ── 底层：一级菜单 ──
        // 始终参与组合（二级页打开时不可见），这样返回时列表位置、开关状态都还在。
        // 透明度跟随返回手势：二级页完全打开时它是 0，拖动时随进度浮现。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val raw = if (offsetX.value.isFinite()) offsetX.value else widthPx
                    val progress = (raw / widthPx).coerceIn(0f, 1f)
                    val eased = 1f - (1f - progress) * (1f - progress)
                    val s = 0.90f + 0.10f * eased
                    scaleX = s
                    scaleY = s
                    translationX = -widthPx * 0.05f * (1f - eased)
                    alpha = if (subPage == SubPage.Main) 1f else progress
                }
        ) {
            SettingsMain(
                viewModel = viewModel,
                listState = mainListState,
                crownEvents = if (subPage == SubPage.Main) crownEvents else emptyFlow(),
                onOpenTrash = onOpenTrash,
                onOpenCrown = { subPage = SubPage.Crown },
                onOpenTheme = { subPage = SubPage.Theme },
                onOpenSort = { subPage = SubPage.Sort },
                onOpenGrid = { subPage = SubPage.Grid },
                onOpenDiag = { subPage = SubPage.Diag },
                onOpenHidden = { subPage = SubPage.Hidden },
                onOpenUpdate = { subPage = SubPage.Update },
                onOpenAbout = { subPage = SubPage.About },
                onOpenLog = { subPage = SubPage.Log }
            )
        }

        // ── 顶层：二级页 ──
        if (subPage != SubPage.Main) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (offsetX.value.isFinite()) offsetX.value else 0f
                        val raw = if (offsetX.value.isFinite()) offsetX.value else 0f
                        val progress = (raw / widthPx).coerceIn(0f, 1f)
                        alpha = 1f - 0.38f * progress
                    }
                    .pointerInput(subPage) {
                        val edge = 26.dp.toPx()
                        val slop = 8.dp.toPx()
                        val threshold = 72.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.x > edge) return@awaitEachGesture
                            // 不在这里 consume：边缘 26dp 也可能压在按钮上，先让按钮照常响应，
                            // 等真的判定成「横向右划」再逐帧消费，按钮的点击自然被取消。
                            var totalDx = 0f
                            var totalDy = 0f
                            var decided = false
                            var abandoned = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.all { !it.pressed }) break
                                val change = event.changes.first()
                                totalDx += change.position.x - change.previousPosition.x
                                totalDy += change.position.y - change.previousPosition.y
                                if (!decided && !abandoned) {
                                    if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                        if (totalDx > 0f && abs(totalDx) > abs(totalDy) * 1.2f) {
                                            decided = true
                                        } else {
                                            abandoned = true
                                        }
                                    }
                                }
                                if (decided) {
                                    change.consume()
                                    // 手势回调处在受限挂起作用域里，Animatable 只能投递到普通协程执行
                                    scope.launch { offsetX.snapTo(totalDx.coerceAtLeast(0f)) }
                                } else if (abandoned) {
                                    break
                                }
                            }
                            if (decided && totalDx > threshold) {
                                // 先跟手滑完整屏，再切回一级菜单，不会「划过去突然消失」
                                scope.launch {
                                    offsetX.animateTo(widthPx, tween(170, easing = FastOutSlowInEasing))
                                    subPage = SubPage.Main
                                }
                            } else if (decided) {
                                scope.launch {
                                    offsetX.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                    }
            ) {
                when (subPage) {
                    SubPage.Crown -> CrownSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents,
                        onPrefsChanged = onCrownPrefsChanged
                    )
                    SubPage.Theme -> ThemeSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.Sort -> SortSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.Grid -> GridSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.Diag -> ScanDiagSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.Hidden -> HiddenFoldersSettings(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.Update -> UpdateContent(
                        viewModel = viewModel,
                        crownEvents = crownEvents
                    )
                    SubPage.About -> AboutPage(crownEvents = crownEvents)
                    SubPage.Log -> LogViewerPage(crownEvents = crownEvents)
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
    data object Sort : SubPage()
    data object Grid : SubPage()
    data object Diag : SubPage()
    data object Hidden : SubPage()
    data object Update : SubPage()
    data object About : SubPage()
    data object Log : SubPage()
}

/** 设置主页 */
@Composable
private fun SettingsMain(
    viewModel: GalleryViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    crownEvents: Flow<Float>,
    onOpenTrash: () -> Unit,
    onOpenCrown: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenSort: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenHidden: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLog: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val showZoomButtons by viewModel.showZoomButtons.collectAsState()
    val blurBackdrop by viewModel.blurBackdrop.collectAsState()
    val restoreKeepDate by viewModel.restoreKeepOriginalDate.collectAsState()
    val animateGifs by viewModel.animateGifs.collectAsState()
    val blockGestures by viewModel.blockGestures.collectAsState()
    val screenShape by viewModel.screenShape.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val context = LocalContext.current

    ScalingScrollColumn(
        crownEvents = crownEvents,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        listState = listState
    ) {
        // 最近删除入口与顶部白线之间留半个按钮高度的间隔，避免贴太近
        item(key = "trash_top_gap") {
            Box(Modifier.fillMaxWidth().height(27.dp))
        }
        // 最近删除入口：位于抽屉顶部白线下方、「设置」标题上方
        item(key = "row_trash") {
            SettingButton(
                icon = Icons.Filled.RestoreFromTrash,
                title = "最近删除",
                subtitle = "已删除的照片在这里保留，可恢复",
                onClick = onOpenTrash,
                itemKey = "row_trash"
            )
        }

        item(key = "settings_title") {
            Box(Modifier.fillMaxWidth().height(26.dp).settingItem("settings_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("设置", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }

        // ───────────────── 1. 手表适配 ─────────────────
        item(key = "sec_watch") { SectionHeader("手表适配", "sec_watch") }
        item(key = "row_crown") {
            SettingButton(
                icon = Icons.Filled.Cached,
                title = "表冠适配",
                subtitle = "${crownMode.label} · ${formatSpeed(crownSpeed)}x",
                onClick = onOpenCrown,
                itemKey = "row_crown"
            )
        }
        item(key = "row_shape") {
            SettingButton(
                icon = if (screenShape == ScreenShape.ROUND) Icons.Filled.Circle
                else Icons.Filled.CropSquare,
                title = "屏幕形状",
                subtitle = when (screenShape) {
                    ScreenShape.AUTO -> "自动 · 点击切换圆屏 / 方屏"
                    ScreenShape.ROUND -> "圆屏 · 列表中间放大，避开弧边"
                    ScreenShape.SQUARE -> "方屏 · 铺满整屏，不做居中放大"
                },
                onClick = { viewModel.setScreenShape(ScreenShape.next(screenShape)) },
                itemKey = "row_shape"
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
        // `setSystemGestureExclusionRects` 是 Android 10（API 29）才有的 API，
        // 低版本既没有这个能力、也没有「边缘返回」这类系统手势，所以整项都不显示。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item(key = "row_gesture") {
                SettingButton(
                    icon = Icons.Filled.Block,
                    title = "屏蔽系统手势",
                    subtitle = if (blockGestures) "开 · 边缘滑动只交给应用处理"
                    else "关 · 系统手势优先（左滑可能退出）",
                    onClick = { viewModel.setBlockGestures(!blockGestures) },
                    itemKey = "row_gesture"
                )
            }
        }

        // ───────────────── 2. 显示设置 ─────────────────
        item(key = "sec_display") { SectionHeader("显示设置", "sec_display") }
        item(key = "row_theme") {
            SettingButton(
                icon = if (themeMode == ThemeMode.LIGHT) Icons.Filled.LightMode
                else Icons.Filled.DarkMode,
                title = "主题设置",
                subtitle = if (themeMode == ThemeMode.LIGHT) "白天" else "夜间",
                onClick = onOpenTheme,
                itemKey = "row_theme"
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
                subtitle = "$gridColumns 张 · 点击进入滑动调节（也可在相册双指捏合）",
                onClick = onOpenGrid,
                itemKey = "row_grid"
            )
        }
        item(key = "row_sort") {
            val sortLabel = when (sortMode) {
                PhotoSortMode.DAY -> "按天排序 · 同天照片聚在一起，标出月日"
                PhotoSortMode.MONTH -> "按月排序 · 同月照片聚在一起，标出年月"
                PhotoSortMode.YEAR -> "按年排序 · 同年照片聚在一起，标出年份"
                PhotoSortMode.NONE -> "不排序 · 只按时间顺序平铺"
            }
            SettingButton(
                icon = Icons.Filled.Sort,
                title = "图片排序方式",
                subtitle = sortLabel,
                onClick = onOpenSort,
                itemKey = "row_sort"
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

        // ───────────────── 3. 其他功能 ─────────────────
        item(key = "sec_other") { SectionHeader("其他功能", "sec_other") }
        item(key = "row_restore_date") {
            SettingButton(
                icon = Icons.Filled.DateRange,
                title = "恢复图片保留原日期",
                subtitle = if (restoreKeepDate) "开 · 从最近删除恢复后保持原来的日期"
                else "关 · 恢复后日期变为恢复时间",
                onClick = { viewModel.setRestoreKeepOriginalDate(!restoreKeepDate) },
                itemKey = "row_restore_date"
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
        item(key = "row_log") {
            SettingButton(
                icon = Icons.Filled.Article,
                title = "查看日志",
                subtitle = "崩溃与运行日志，可复制、可清除",
                onClick = onOpenLog,
                itemKey = "row_log"
            )
        }

        // ───────────────── 4. 检测更新 ─────────────────
        item(key = "sec_update") { SectionHeader("检测更新", "sec_update") }
        item(key = "row_update") {
            SettingButton(
                icon = Icons.Filled.SystemUpdate,
                title = "检测更新",
                subtitle = "检查并安装新版本",
                onClick = onOpenUpdate,
                itemKey = "row_update"
            )
        }

        // ───────────────── 5. 关于 ─────────────────
        item(key = "sec_about") { SectionHeader("关于", "sec_about") }
        item(key = "row_about") {
            SettingButton(
                icon = Icons.Filled.Info,
                title = "关于",
                subtitle = "相册 v${BuildConfig.VERSION_NAME} · 圆屏手表版",
                onClick = onOpenAbout,
                itemKey = "row_about"
            )
        }
    }
}

/**
 * 板块标题：左边小字（主色）+ 一条渐隐细线。
 * 不参与居中放大（`scale = false`），只做边缘淡出，避免两行标题被挤成一团。
 */
@Composable
private fun SectionHeader(title: String, key: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 7.dp, bottom = 1.dp)
            .settingItem("hdr_$key", scale = false),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 3.dp)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        )
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
    var zoomInvert by remember { mutableStateOf(viewModel.zoomInvertCrown.value) }
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
        item(key = "crown_zoom_invert") {
            Box(Modifier.fillMaxWidth().settingItem("crown_zoom_invert", scale = false)) {
                Column {
                    Text("缩放方向反转", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (zoomInvert) "开 · 放大与缩小的表冠方向相反"
                        else "关 · 维持当前的表冠缩放方向",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            OptionButton(
                                selected = !zoomInvert,
                                label = "关（默认）",
                                onClick = { zoomInvert = false; viewModel.setZoomInvert(false) },
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                                    .settingItem("crown_zoom_invert_off")
                            )
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            OptionButton(
                                selected = zoomInvert,
                                label = "开（反转）",
                                onClick = { zoomInvert = true; viewModel.setZoomInvert(true) },
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                                    .settingItem("crown_zoom_invert_on")
                            )
                        }
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

/** 主题设置子页：只有夜间 / 白天两档（默认夜间） */
@Composable
private fun ThemeSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val current by viewModel.themeMode.collectAsState()
    val options = listOf(
        ThemeMode.DARK to "夜间（黑底）",
        ThemeMode.LIGHT to "白天（白纸）"
    )
    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "theme_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("theme_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("主题设置", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "theme_desc") {
            Box(Modifier.fillMaxWidth().settingItem("theme_desc", scale = false)) {
                Text(
                    "夜间适合暗环境，白天在强光下更清楚。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        options.forEach { (mode, label) ->
            item(key = "theme_${mode.name}") {
                val mk = "theme_${mode.name}"
                val selected = if (mode == ThemeMode.LIGHT) current == ThemeMode.LIGHT
                else current != ThemeMode.LIGHT
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = selected,
                        label = label,
                        onClick = { viewModel.setThemeMode(mode) },
                        modifier = Modifier.fillMaxWidth().height(44.dp).settingItem(mk)
                    )
                }
            }
        }
    }
}

/**
 * 每排照片数量二级页：线性滑杆 1~10 张。
 * 拖动过程中滑杆连续跟手、不吸附；松手后才平滑吸附到最近的整数刻度点并写回。
 * 刻度点和当前吸附点高亮，让「整数的点」看得见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val scope = rememberCoroutineScope()
    val columns by viewModel.gridColumns.collectAsState()
    // 滑杆显示值：拖动时可为小数（连续），松手后动画吸附到整数
    var sliderValue by remember { mutableFloatStateOf(columns.toFloat()) }
    // 正在吸附的动画任务：拖动时取消它；外部改列数时若有动画在跑就不强行同步
    var snapJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(columns) {
        if (snapJob?.isActive != true) sliderValue = columns.toFloat()
    }

    fun snapAndCommit() {
        val target = sliderValue.roundToInt().coerceIn(1, 10)
        snapJob = scope.launch {
            animate(
                initialValue = sliderValue,
                targetValue = target.toFloat(),
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) { v, _ -> sliderValue = v }
            viewModel.setGridColumns(target)
        }
    }

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "grid_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("grid_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("每排照片", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "grid_desc") {
            Box(Modifier.fillMaxWidth().settingItem("grid_desc", scale = false)) {
                Text(
                    "拖动滑杆选择每排显示的照片张数（1~10 张），松手后吸附到整数点。也可在相册里双指捏合调整。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item(key = "grid_value") {
            Box(Modifier.fillMaxWidth().settingItem("grid_value", scale = false),
                contentAlignment = Alignment.Center) {
                Text(
                    "每排 ${sliderValue.roundToInt().coerceIn(1, 10)} 张",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item(key = "grid_slider") {
            Box(Modifier.fillMaxWidth().settingItem("grid_slider", scale = false)) {
                Column {
                    Slider(
                        value = sliderValue,
                        valueRange = 1f..10f,
                        // steps = 0 → 线性连续轴，拖动时不做任何吸附；吸附在松手回调里完成。
                        // 若用 steps = 9，Material3 会在拖动中逐点吸附，不符合「不松手不吸附」。
                        steps = 0,
                        onValueChange = {
                            snapJob?.cancel()
                            sliderValue = it
                        },
                        onValueChangeFinished = { snapAndCommit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 1~10 整数刻度点：当前将吸附到的点高亮
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val snapped = sliderValue.roundToInt().coerceIn(1, 10)
                        for (n in 1..10) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(
                                    if (n == snapped) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 张", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("10 张", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 图片排序方式二级页：按天 / 按月 / 按年 / 不排序 */
@Composable
private fun SortSettings(
    viewModel: GalleryViewModel,
    crownEvents: Flow<Float>
) {
    val current by viewModel.sortMode.collectAsState()
    val options = listOf(
        PhotoSortMode.DAY to "按天排序",
        PhotoSortMode.MONTH to "按月排序",
        PhotoSortMode.YEAR to "按年排序",
        PhotoSortMode.NONE to "不排序"
    )
    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "sort_title") {
            Box(Modifier.fillMaxWidth().height(28.dp).settingItem("sort_title", scale = false),
                contentAlignment = Alignment.Center) {
                Text("图片排序方式", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "sort_desc") {
            Box(Modifier.fillMaxWidth().settingItem("sort_desc", scale = false)) {
                Text(
                    "按天 / 月 / 年排序会把同一时间段的照片聚在一起，并在上方标出所属日期。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        options.forEach { (mode, label) ->
            item(key = "sort_${mode.name}") {
                val mk = "sort_${mode.name}"
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    OptionButton(
                        selected = mode == current,
                        label = label,
                        onClick = { viewModel.setSortMode(mode) },
                        modifier = Modifier.fillMaxWidth().height(44.dp).settingItem(mk)
                    )
                }
            }
        }
    }
}

/**
 * 关于页 —— 一页静态信息，没有任何按钮外观：
 *
 *   · **没有标题**，最上面只有应用图标（居中），下面一列**左对齐**的文字；
 *   · 没有胶囊底、没有描边、没有按压反馈（网址那行仍可点/长按，但看起来就是一行蓝字）；
 *   · 应用名单独一档大字号（20sp），其余 12sp；
 *   · 文字块宽度按「这一行此刻在屏幕上的高度」取圆内弦长自动收窄 —— 滚到上下弧边时
 *     行尾不会顶到表壳被切掉；方屏（[LocalCenterMagnify] = false）保持满宽；
 *   · 各行按序号错开淡入上浮（220ms / 每行 +35ms）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutPage(crownEvents: Flow<Float>) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    // 入场总开关：进这一屏后置 true，各行按序号错开浮起
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val linkColor = Color(0xFF4FA3FF)

    Box(Modifier.fillMaxSize()) {
        ScalingScrollColumn(
            crownEvents = crownEvents,
            verticalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 80.dp)
        ) {
            // 序号在构建期算好（content lambda 会重组，不能在里面 ++）
            var i = 0
            val iconIdx = i++
            val nameIdx = i++
            val tagIdx = i++
            val introIdx = i++
            val verIdx = i++
            val codeIdx = i++
            val devIdx = i++
            val socIdx = i++
            val urlIdx = i++

            item(key = "about_icon") {
                AboutIcon(entered = entered, index = iconIdx)
            }
            item(key = "about_name") {
                AboutName("edipic", entered = entered, index = nameIdx)
            }
            item(key = "about_tagline") {
                AboutTagline("手表上的随身相册 · 由春羽开发", entered = entered, index = tagIdx)
            }
            item(key = "about_intro") {
                AboutText(
                    text = "原图浏览 · 表冠缩放 · 旋转裁剪 · 多选整理\n一块表，装下你舍不得删的每一张",
                    entered = entered,
                    index = introIdx,
                    lineHeight = 17.sp
                )
            }
            item(key = "about_version") {
                AboutText(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    entered = entered,
                    index = verIdx
                )
            }
            item(key = "about_code") {
                AboutText(
                    text = "Version Code ${BuildConfig.VERSION_CODE}",
                    entered = entered,
                    index = codeIdx
                )
            }
            // 「空一排」：一个整行的视觉间隔（f-x 里也是一项，不是 spacing 特例）
            item(key = "about_gap") { Spacer(Modifier.height(12.dp)) }
            item(key = "about_dev") {
                AboutText(
                    text = "开发者 春羽",
                    entered = entered,
                    index = devIdx,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item(key = "about_social") {
                AboutText(
                    text = "0o0社区@春羽",
                    entered = entered,
                    index = socIdx,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 网址：蓝字，点击用浏览器打开，长按复制并弹应用内提示（系统 Toast 在圆屏会被裁）
            item(key = "about_url") {
                AboutText(
                    text = "0o0.site",
                    entered = entered,
                    index = urlIdx,
                    color = linkColor,
                    fontWeight = FontWeight.SemiBold,
                    onClick = { openUrl(context, "https://0o0.site") },
                    onLongClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("0o0.site", "0o0.site"))
                        toast = "已复制网址"
                    }
                )
            }
            item(key = "about_tail") { Spacer(Modifier.height(24.dp)) }
        }

        // 应用内 toast（不用系统 Toast：手表上系统 Toast 会被圆角裁掉）
        InAppToast(
            message = toast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp)
        )
    }
}

/** 各行入场：淡入 + 从下方浮起 10dp，按序号错开（f-x 关于页同一套参数） */
@Composable
private fun enterReveal(entered: Boolean, index: Int): Float {
    val reveal by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(
            durationMillis = 220,
            delayMillis = index * 35,
            easing = FastOutSlowInEasing
        ),
        label = "aboutReveal"
    )
    return reveal
}

/** 图标那一行：只有图标、没有胶囊底（它是这一屏的门面，不是一条信息） */
@Composable
private fun AboutIcon(entered: Boolean, index: Int) {
    val reveal = enterReveal(entered, index)
    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 10f
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(17.dp))
        ) {
            // 自适应图标的前景 + 背景两层叠合，和桌面上那颗图标一致
            Image(
                painter = painterResource(R.mipmap.ic_launcher_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 应用名：这一屏唯一的大字号（20sp），纯色前景 */
@Composable
private fun AboutName(text: String, entered: Boolean, index: Int) {
    val reveal = enterReveal(entered, index)
    Text(
        text,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 10f
            }
    )
}

/** 一句话定位：名字下面那行小字（11sp，次要色） */
@Composable
private fun AboutTagline(text: String, entered: Boolean, index: Int) {
    val reveal = enterReveal(entered, index)
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 10f
            }
    )
}

/**
 * 一条信息：**纯文字**，左对齐，没有胶囊底、没有描边、没有按压反馈。
 *
 * 与上一版的区别只有外观：内容、顺序、字号、入场动画都没动。
 * 网址那行仍然挂点击/长按（打开浏览器、复制并弹提示），但视觉上就是一行蓝字，
 * 不再有「这是一个按钮」的暗示 —— 用户要的是静态信息页。
 *
 * @param onClick / onLongClick 只有网址那行挂得上，其余传 null（连手势节点都不建）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutText(
    text: String,
    entered: Boolean,
    index: Int,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val reveal = enterReveal(entered, index)
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = color,
        textAlign = TextAlign.Start,
        modifier = Modifier
            // 圆屏按弦长收窄，文字在这个块内左对齐；方屏原样满宽
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 10f
            }
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        // 不加涟漪：这是信息页，不是一组按钮
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier
                }
            )
            // 上下留一点呼吸，同时把点击热区从一条细字放宽到一行高
            .padding(vertical = 3.dp)
    )
}


private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Material3 标准全圆角按钮（Wear OS pill 风格）：满宽。
 * 由 [settingItem] 做「只缩不放」形变 —— 居中正常大小（≈满屏宽），边缘缩小淡出。
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
 * 运行日志查看页 —— 作为设置二级菜单打开，自动获得同款进/出动画、毛玻璃背景与边缘右划返回。
 * 用 [ScalingScrollColumn] 承载，支持表冠滚动（与设置/诊断页同一套）。
 * 只展示日志 + 复制/清除，**不提示崩溃**（与启动时的崩溃报告页区分）。
 */
@Composable
private fun LogViewerPage(crownEvents: Flow<Float>) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(CrashLogger.read(context)) }

    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "log_title") {
            Box(
                Modifier.fillMaxWidth().height(28.dp).settingItem("log_title", scale = false),
                contentAlignment = Alignment.Center
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
        }
        item(key = "log_actions") {
            Box(Modifier.fillMaxWidth().settingItem("log_actions", scale = false)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("WatchGallery 日志", logText))
                            Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            CrashLogger.clear(context)
                            logText = CrashLogger.read(context)
                            Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除", fontSize = 12.sp)
                    }
                }
            }
        }
        item(key = "log_body") {
            Box(Modifier.fillMaxWidth().settingItem("log_body", scale = false)) {
                val shown = if (logText.isBlank()) "（暂无日志）" else logText
                Text(
                    shown,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )
            }
        }
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
    val magnify = LocalCenterMagnify.current
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
                if (scale && magnify) {
                    // 居中 1.12 → 边缘 0.76，二次曲线让中间那块更「聚」
                    val s = 1.12f - 0.36f * (t * t)
                    scaleX = s
                    scaleY = s
                }
                alpha = 1f - 0.40f * t
            }
        }
}

/** 灵敏度显示：小于 1x 保留两位（0.15x），1x 以上保留一位（2.5x / 10.0x） */
private fun formatSpeed(speed: Float): String =
    if (speed < 1f) String.format(java.util.Locale.US, "%.2f", speed)
    else String.format(java.util.Locale.US, "%.1f", speed)
