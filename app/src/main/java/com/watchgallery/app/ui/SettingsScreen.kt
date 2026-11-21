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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchgallery.app.BuildConfig
import com.watchgallery.app.R
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.ScreenShape
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    onSubPageChanged: (Boolean) -> Unit = {}
) {
    var subPage by remember { mutableStateOf<SubPage>(SubPage.Main) }
    LaunchedEffect(subPage) { onSubPageChanged(subPage == SubPage.Main) }
    // 一级菜单的滚动位置放在这一层：进二级页再回来时还停在原来的位置
    val mainListState = rememberLazyListState()

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

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
                    val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
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
                onOpenCrown = { subPage = SubPage.Crown },
                onOpenTheme = { subPage = SubPage.Theme },
                onOpenDiag = { subPage = SubPage.Diag },
                onOpenHidden = { subPage = SubPage.Hidden },
                onOpenUpdate = { subPage = SubPage.Update },
                onOpenAbout = { subPage = SubPage.About }
            )
        }

        // ── 顶层：二级页 ──
        if (subPage != SubPage.Main) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offsetX.value
                        val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
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
    data object Update : SubPage()
    data object About : SubPage()
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
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val crownMode by viewModel.crownMode.collectAsState()
    val crownSpeed by viewModel.crownSpeed.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val showZoomButtons by viewModel.showZoomButtons.collectAsState()
    val blurBackdrop by viewModel.blurBackdrop.collectAsState()
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
 * 关于页：图标在最上面居中，下面一行行文字。
 *
 * 圆屏两头是弧边，正文如果按「整屏居中」排，最下面几行会被切掉；所以内容块用
 * `Arrangement.Center` 垂直居中，再在末尾塞两排空白 —— 等于把整块文字往上推一行半，
 * 每一行都落在圆里能看见的位置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutPage(crownEvents: Flow<Float>) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var toast by remember { mutableStateOf<String?>(null) }
    CrownScroll(crownEvents) { scroll.scrollBy(it) }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1500)
            toast = null
        }
    }

    val linkColor = Color(0xFF4FA3FF)

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minH = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .heightIn(min = minH)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 应用图标：自适应图标的前景 + 背景两层叠起来，和桌面上的图标一致
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
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

                Spacer(Modifier.height(5.dp))
                Text(
                    "相册",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    "手表本地相册 · 由春羽开发",
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))
                AboutLine("应用版本", BuildConfig.VERSION_NAME)
                AboutLine("Version Code", BuildConfig.VERSION_CODE.toString())
                AboutLine("开发者名称", "春羽")

                Spacer(Modifier.height(4.dp))
                Text(
                    "0o0社区@春羽",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                // 网址：蓝字，点击用浏览器打开，长按复制并弹应用内提示
                Text(
                    "0o0.site",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = linkColor,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { openUrl(context, "https://0o0.site") },
                            onLongClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("0o0.site", "0o0.site"))
                                toast = "已复制网址"
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )

                // 末尾两排空白（每排 13dp）：把上面这整块文字往上顶，
                // 让最后一行落在圆屏可见的位置，不被表盘下沿的弧边切掉
                Spacer(Modifier.height(26.dp))
            }
        }

        // 应用内 toast（不用系统 Toast：手表上系统 Toast 会被圆角裁掉）
        InAppToast(
            message = toast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp)
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(
        Modifier.padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            value,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
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
