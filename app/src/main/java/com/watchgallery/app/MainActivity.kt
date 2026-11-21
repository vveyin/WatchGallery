package com.watchgallery.app

import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.Photo
import com.watchgallery.app.data.ScreenShape
import com.watchgallery.app.theme.WatchGalleryTheme
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import com.watchgallery.app.util.UpdateChecker
import com.watchgallery.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: GalleryViewModel by viewModels()

    // 表冠模式与灵敏度（生命周期内缓存，避免每帧读 SharedPreferences）
    private var crownMode: CrownMode = CrownMode.OPPO
    private var crownSpeed: Float = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashedLastTime = CrashLogger.hasPending(this)
        CrashLogger.log(this, "MainActivity.onCreate start (crashedLastTime=$crashedLastTime)")
        applyCrownPrefs()

        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val message by viewModel.message.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            var showCrashReport by remember { mutableStateOf(crashedLastTime) }

            LaunchedEffect(message) {
                message?.let {
                    scope.launch { snackbarHostState.showSnackbar(it) }
                    viewModel.clearMessage()
                }
            }

            WatchGalleryTheme(themeMode = themeMode) {
                val context = LocalContext.current
                val round = remember {
                    context.resources.configuration.screenLayout and
                            android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK ==
                            android.content.res.Configuration.SCREENLAYOUT_ROUND_YES
                }
                if (showCrashReport) {
                    CrashReportScreen(
                        isRound = round,
                        onContinue = {
                            CrashLogger.log(context, "user dismissed crash report")
                            showCrashReport = false
                        }
                    )
                } else {
                    AppNavigation(
                        viewModel, snackbarHostState,
                        onCrownPrefsChanged = { applyCrownPrefs() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyCrownPrefs()
    }

    private fun applyCrownPrefs() {
        val (mode, speed) = CrownPrefs.load(this)
        crownMode = mode
        crownSpeed = speed
    }

    /**
     * 用 dispatchGenericMotionEvent 比 onGenericMotionEvent 更早拦截。
     * 完整移植 f-x 的双模式表冠处理：
     *   OPPO 走普通滚轮轴（不过滤来源）；华为/三星走 ACTION_SCROLL + 编码器来源。
     * 两条路算出的量最后都乘灵敏度，再交给 Compose。
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        try {
            val amount = scrollAmount(event)
            // 诊断：记录最近一次带滚动轴的事件，设置页实时显示，帮用户判断哪种模式有效
            reportDebug(event, amount)
            if (amount != 0f) {
                dispatchCrown(amount)
                return true
            }
        } catch (_: Exception) {
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** 只记录有滚动轴的 motion 事件，避免触摸噪声刷屏 */
    private fun reportDebug(event: MotionEvent, amount: Float) {
        val axes = SCROLL_AXES.toList().mapNotNull { axis: Int ->
            val v = event.getAxisValue(axis)
            if (v != 0f && v.isFinite()) "ax$axis=$v" else null
        }
        if (axes.isNotEmpty()) {
            viewModel.reportCrownDebug(
                "action=${event.action} src=0x${event.source.toString(16)} ${axes.joinToString(" ")} → ${"%.2f".format(amount)}"
            )
        }
    }

    /** 从通用运动事件抽滚动量，按模式分两条路；返回值已乘灵敏度，0 = 不是表冠事件 */
    private fun scrollAmount(event: MotionEvent): Float {
        val raw = when (crownMode) {
            CrownMode.OPPO -> oppoRaw(event)
            CrownMode.HUAWEI_SAMSUNG -> huaweiSamsungRaw(event)
        }
        if (raw == 0f) return 0f
        val scaled = raw * crownSpeed
        return if (scaled.isFinite()) scaled else 0f
    }

    /**
     * OPPO：逐个滚轮轴试，取第一个非零值，不做 action / source 判断（source 位不可靠）。
     *
     * 照搬 f-x：直接返回原始值，不做任何自适应放大。
     * 之前 |v| ≤ 2 时乘 15 的"自适应增益"会把表冠停下瞬间的微小反向抖动放大成
     * 十几像素的反向跳动 —— 就是滚动完"像有回弹、向反方向滚一小段"的来源之一。
     * 滚动量统一交给灵敏度 crownSpeed 调节（设置页可实时调），手感与 f-x 一致。
     */
    private fun oppoRaw(event: MotionEvent): Float {
        for (axis in SCROLL_AXES) {
            val v = event.getAxisValue(axis)
            if (v != 0f && v.isFinite()) return v
        }
        return 0f
    }

    /** 华为 / 三星：标准旋转编码器，判据三件套一个都不能少 */
    private fun huaweiSamsungRaw(event: MotionEvent): Float {
        if (event.action != MotionEvent.ACTION_SCROLL) return 0f
        if (!event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return 0f
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (scroll != 0f) scroll else event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        } else {
            event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        }
        if (v == 0f || !v.isFinite()) return 0f
        return v * ROTARY_RAW_SCALE
    }

    /** 方向键 / 音量键兜底（部分表把表冠映射成按键，无滚轮事件） */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                viewModel.reportCrownDebug("key=$keyCode（表冠映射为按键）")
                dispatchCrown(3f * crownSpeed)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                viewModel.reportCrownDebug("key=$keyCode（表冠映射为按键）")
                dispatchCrown(-3f * crownSpeed)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dispatchCrown(delta: Float) {
        // 事件已是独立增量，直接投递；不再做 16ms 节流（过快转动时会把整格丢掉）
        viewModel.pushCrown(delta)
    }

    private companion object {
        /** 旋转编码器一格 = 1.0，OPPO REL_WHEEL 一格是十几到几十，放大 15 倍拉齐手感 */
        const val ROTARY_RAW_SCALE = 15f

        /** AXIS_SCROLL 是 API 26+ 常量，低版本不列（编译期内联为字面量，但框架不认这个轴） */
        val SCROLL_AXES: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intArrayOf(
                MotionEvent.AXIS_SCROLL,
                MotionEvent.AXIS_VSCROLL,
                MotionEvent.AXIS_HSCROLL,
                MotionEvent.AXIS_WHEEL
            )
        } else {
            intArrayOf(
                MotionEvent.AXIS_VSCROLL,
                MotionEvent.AXIS_HSCROLL,
                MotionEvent.AXIS_WHEEL
            )
        }
    }
}

@Composable
private fun AppNavigation(
    viewModel: GalleryViewModel,
    snackbarHostState: SnackbarHostState,
    onCrownPrefsChanged: () -> Unit
) {
    // 导航栈：栈底 Gallery，push 进入 Viewer/Editor/Update，pop 返回。
    // 设置不再是栈中一页，而是常驻抽屉（SettingsDrawer），盖在主界面之上，用垂直手势升降。
    val backStack = remember { mutableStateListOf<Screen>(Screen.Gallery) }
    var currentPhoto by remember { mutableStateOf<Photo?>(null) }
    // 大图翻页用「当前上下文」的列表：在文件夹里打开就翻该文件夹，否则翻全部
    val photos by viewModel.viewerPhotos.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val showZoomButtons by viewModel.showZoomButtons.collectAsState()
    val blurBackdrop by viewModel.blurBackdrop.collectAsState()
    val animateGifs by viewModel.animateGifs.collectAsState()
    // 打开大图时点击的那张缩略图位置 + 已解码的缩略图本身，供大图页做「从缩略图展开」的入场动画
    var viewerOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var viewerThumb by remember {
        mutableStateOf<androidx.compose.ui.graphics.painter.Painter?>(null)
    }

    val context = LocalContext.current
    val deviceRound = remember {
        context.resources.configuration.screenLayout and
                android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK ==
                android.content.res.Configuration.SCREENLAYOUT_ROUND_YES
    }
    // 屏幕形状：设置里可以强制「圆屏 / 方屏」。选方屏后整屏铺满、列表不做居中放大。
    val screenShape by viewModel.screenShape.collectAsState()
    val isRound = when (screenShape) {
        ScreenShape.ROUND -> true
        ScreenShape.SQUARE -> false
        ScreenShape.AUTO -> deviceRound
    }
    val crownEvents = viewModel.crownEvents

    // ── 屏蔽系统手势（仅 Android 10+）──
    // 把整个窗口登记成系统手势排除区：边缘滑动不再被系统当成「返回 / 退出」，
    // 全部交给应用自己的右划返回、抽屉拖动。低版本没有这套 API，设置里也不显示这一项。
    val blockGestures by viewModel.blockGestures.collectAsState()
    val rootView = LocalView.current
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(blockGestures, rootSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val w = if (rootSize.width > 0) rootSize.width else rootView.width
            val h = if (rootSize.height > 0) rootSize.height else rootView.height
            applyGestureExclusion(rootView, blockGestures && w > 0 && h > 0, w, h)
        }
    }

    // ── 设置抽屉状态 ──
    val density = LocalDensity.current
    val collapsedY = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 三处抽屉统一用 DrawerState（0 = 收起，1 = 铺满），做法照抄 f-x
    val settingsDrawer = rememberDrawerState(false)
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsAtRoot by remember { mutableStateOf(true) }  // 设置是否在一级菜单

    // 入场动画只用一次：大图页读到缩略图矩形后就清掉，
    // 之后从编辑页返回时不会再放大一遍（看起来像重新打开）。
    LaunchedEffect(backStack.lastOrNull(), viewerOrigin) {
        if (backStack.lastOrNull() is Screen.Viewer && viewerOrigin != null) {
            kotlinx.coroutines.delay(500)
            viewerOrigin = null
        }
    }

    fun navigate(to: Screen) { backStack.add(to) }
    fun goBack() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    fun openSettings() {
        settingsOpen = true
        settingsDrawer.open()
    }

    fun closeSettings() {
        settingsOpen = false
        settingsAtRoot = true
        settingsDrawer.close()
    }

    val current = backStack.last()

    // ── 冷启动自动检查更新 ──
    // 只在整个进程第一次打开 App 时做一次：
    //  - 从后台回到前台不会重建 Activity，天然不触发；
    //  - Activity 因配置变化重建时，进程级标记已经置位，也不会重复检查。
    LaunchedEffect(Unit) {
        if (LaunchUpdateCheck.ran) return@LaunchedEffect
        LaunchUpdateCheck.ran = true
        // ① 先判断有没有网：没网就完全静默，什么都不提示
        if (!UpdateChecker.isOnline(context)) return@LaunchedEffect
        delay(900)   // 让相册先出图，别和扫盘抢 IO
        if (!UpdateChecker.isOnline(context)) return@LaunchedEffect
        // ② 有网再查新版本
        val info = UpdateChecker.check(
            BuildConfig.GITEE_OWNER, BuildConfig.GITEE_REPO,
            BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO
        ).getOrNull() ?: return@LaunchedEffect
        // ③ 确实有新版本才提示：直接进更新页，页面立刻显示「发现新版本 + 下载更新」
        if (UpdateChecker.isNewer(
                UpdateChecker.versionNumber(info.versionName),
                UpdateChecker.versionNumber(BuildConfig.VERSION_NAME)
            ) && backStack.size == 1
        ) {
            viewModel.setPendingUpdate(info)
            navigate(Screen.Update)
        }
    }

    // 渲染某一层界面。
    // - 除大图页外都垫不透明纯黑背景：右划露出下层时不被透视叠加。
    // - **大图页故意透明**：入场动画期间要能看见下面那张相册，等图片长大、黑底淡入，
    //   才轮到"列表消失"，这样点进去不会先黑一下。
    // - 底层（interactive=false）传空表冠流：避免上下两层同时滚。
    @Composable
    fun ScreenLayer(screen: Screen, interactive: Boolean) {
        val layerCrown = if (interactive && !settingsOpen) crownEvents else emptyFlow()
        // 大图页、编辑页都透明：它们自己带黑底并随入场动画淡入，
        // 这样从大图进编辑页能看到图片「浮起来」而不是先黑一下。
        val bg = if (screen is Screen.Viewer || screen is Screen.Editor) Color.Transparent
        else Color.Black
        Box(Modifier.fillMaxSize().background(bg)) {
            when (screen) {
                is Screen.Gallery -> GalleryScreen(
                    viewModel = viewModel,
                    isRound = isRound,
                    crownEvents = layerCrown,
                    animateGifs = animateGifs,
                    onPhotoClick = { photo ->
                        if (interactive) {
                            viewerOrigin = viewModel.viewerOrigin
                            viewerThumb = viewModel.viewerThumb
                            currentPhoto = photo
                            navigate(Screen.Viewer)
                        }
                    },
                    onOpenSettings = { if (interactive) openSettings() },
                    onCheckUpdate = { if (interactive) navigate(Screen.Update) }
                )
                is Screen.Viewer -> {
                    val idx = photos.indexOfFirst { it.uri == currentPhoto?.uri }.coerceAtLeast(0)
                    PhotoViewerScreen(
                        viewModel = viewModel,
                        photos = photos,
                        initialIndex = idx,
                        isRound = isRound,
                        crownEvents = layerCrown,
                        showZoomButtons = showZoomButtons,
                        origin = viewerOrigin,
                        thumb = viewerThumb,
                        blurEnabled = blurBackdrop,
                        onBack = { if (interactive) goBack() },
                        onEdit = { photo ->
                            if (interactive) { currentPhoto = photo; navigate(Screen.Editor) }
                        }
                    )
                }
                is Screen.Editor -> currentPhoto?.let { photo ->
                    EditScreen(
                        photo = photo,
                        viewModel = viewModel,
                        isRound = isRound,
                        blurEnabled = blurBackdrop,
                        crownEvents = layerCrown,
                        showZoomButtons = showZoomButtons,
                        onBack = { if (interactive) goBack() }
                    )
                }
                is Screen.Update -> UpdateScreen(
                    viewModel = viewModel,
                    isRound = isRound,
                    crownEvents = layerCrown,
                    onBack = { if (interactive) goBack() }
                )
            }
        }
    }

    // 方屏（设置里选方屏，或系统就是方屏）关掉所有列表的「居中放大」：
    // 直角矩形上这套形变只会让上下两条莫名变小。
    CompositionLocalProvider(LocalCenterMagnify provides isRound) {
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        // ── 相册常驻最底层 ──
        // 进大图 / 编辑 / 更新时**不卸载**它：以前是"有下层才套 SwipeBackContainer"，
        // 退出大图时条件翻转，整棵相册树被重建 —— 缩略图全部重新解码，
        // 屏幕上就会抽几帧（正方形里塞一张变形图，然后才恢复）。
        // 现在相册永远在这儿，上层页面只是盖上去，退出时把它露出来即可。
        ScreenLayer(Screen.Gallery, interactive = current is Screen.Gallery)

        // 上层：栈顶那一屏。右划返回只作用在这一层，底下相册已经画好了，不再复制一份。
        // 编辑页例外：调参数、涂鸦时特别容易误触左缘，所以**禁用右划返回**，
        // 返回只走抽屉里那颗按钮（编辑页自己的进出场动画也才不会被手势打断）。
        if (current !is Screen.Gallery) {
            if (current is Screen.Editor) {
                ScreenLayer(current, interactive = true)
            } else {
                SwipeBackContainer(
                    onBack = { goBack() },
                    backContent = {},
                    content = { ScreenLayer(current, interactive = true) }
                )
            }
        }

        // 设置抽屉：常驻，盖在主导航层之上
        SettingsDrawer(
            state = settingsDrawer,
            collapsedY = collapsedY,
            canDragDown = settingsAtRoot,
            enabled = !settingsOpen && current is Screen.Gallery && !selectionMode,
            blurEnabled = blurBackdrop,
            onOpened = { settingsOpen = true },
            onClosed = { settingsOpen = false }
        ) {
            // 背景透明：毛玻璃背板由 SettingsDrawer 画，设置条目浮在上面
            SettingsScreen(
                viewModel = viewModel,
                isRound = isRound,
                crownEvents = if (settingsOpen) crownEvents else emptyFlow(),
                onBack = {},
                onCrownPrefsChanged = onCrownPrefsChanged,
                onCheckUpdate = { closeSettings(); navigate(Screen.Update) },
                onSubPageChanged = { settingsAtRoot = it }
            )
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
    }
}

/**
 * 把整个窗口登记为「系统手势排除区」。
 *
 * `systemGestureExclusionRects` 是 API 29 起才有的能力，调用点已经用
 * `Build.VERSION.SDK_INT >= Q` 包住；关掉时传空表，立刻把排除区收回。
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun applyGestureExclusion(view: android.view.View, on: Boolean, w: Int, h: Int) {
    view.systemGestureExclusionRects =
        if (on) listOf(Rect(0, 0, w, h)) else emptyList()
}

private sealed class Screen {
    data object Gallery : Screen()
    data object Viewer : Screen()
    data object Editor : Screen()
    data object Update : Screen()
}

/** 进程级标记：本次进程是否已做过冷启动更新检测（进程重启才重置） */
private object LaunchUpdateCheck { var ran = false }
