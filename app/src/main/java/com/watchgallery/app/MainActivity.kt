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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.collect
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
            val deleteToast by viewModel.deleteToast.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            var showCrashReport by remember { mutableStateOf(crashedLastTime) }

            LaunchedEffect(message) {
                message?.let {
                    scope.launch { snackbarHostState.showSnackbar(it) }
                    viewModel.clearMessage()
                }
            }

            // 删除提示：和关于页长按 0o0.site 同款黑药丸 toast，1.6 秒后自动消失
            LaunchedEffect(deleteToast) {
                if (deleteToast != null) {
                    delay(1600)
                    viewModel.clearDeleteToast()
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
                        crownEvents = viewModel.crownEvents,
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
    // 删除「已删除 N 张图片」黑药丸提示（在 setContent 里定时清除）
    val deleteToast by viewModel.deleteToast.collectAsState()
    // 导航栈：栈底 Gallery，push 进入 Viewer/Editor/Update，pop 返回。
    // 设置不再是栈中一页，而是常驻抽屉（SettingsDrawer），盖在主界面之上，用垂直手势升降。
    val backStack = remember { mutableStateListOf<Screen>(Screen.Gallery) }
    var currentPhoto by remember { mutableStateOf<Photo?>(null) }
    // 大图页的临时图源：从回收站打开时指向回收站列表，普通相册为 null（用主列表）
    var viewerPhotos by remember { mutableStateOf<List<Photo>?>(null) }
    // 查看器/编辑器的「来源层」：右划退出时底层要露出的那一页。
    // 从相册进查看器 = Gallery；从回收站进查看器 = Trash。
    // 这样退出时露出的就是正确的来源，不会先闪一下图片列表。
    var viewerUnder by remember { mutableStateOf<Screen>(Screen.Gallery) }
    // 右划返回共享的水平进度：顶层 SwipeBackContainer 写它，常驻底层读它做跟手缓动。
    val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    // 回收站一旦打开过就常驻在组合里（与相册并列的一层），避免看图退出时被销毁重建。
    var trashOpened by remember { mutableStateOf(false) }
    // 从抽屉进入回收站时记下抽屉进度与来源，退出回收站时恢复到「刚刚划到的位置」。
    var drawerProgressBeforeTrash by remember { mutableFloatStateOf(1f) }
    var trashFromDrawer by remember { mutableStateOf(false) }
    // 页面「推入」的水平进度：widthPx = 新页面还在屏幕右侧（刚入栈的第一帧），
    // 0 = 新页面完全铺满。与右划返回的 swipeOffset 取较大值，统一驱动底层
    // 「缩小 + 左移 + 变暗」的跟手缓动（设置页二级菜单同款）。
    val enterOffset = remember { Animatable(0f) }
    // 出栈计数：浮层移出组合之后再把两个进度归零。若像以前那样在 onBack() 之后
    // 立刻 snapTo(0)，还没来得及移除的浮层会被拉回原位闪一帧原图。
    var popTick by remember { mutableIntStateOf(0) }
    // 退回回收站的第一帧 swipeOffset 还是满宽，若直接用它算抽屉 cover 会闪出一帧设置菜单；
    // 等归零后再放开。
    var suppressDrawerCover by remember { mutableStateOf(false) }
    val navScope = rememberCoroutineScope()
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
    // 页面入栈动画用的屏宽（在组合阶段算好，供普通函数 navigate 使用）
    val enterWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
    // 三处抽屉统一用 DrawerState（0 = 收起，1 = 铺满），做法照抄 f-x
    val settingsDrawer = rememberDrawerState(false)
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsAtRoot by remember { mutableStateOf(true) }  // 设置是否在一级菜单
    var settingsBackTick by remember { mutableIntStateOf(0) } // 通知设置页「系统返回：退回一级菜单」
    // 使用系统返回手势（默认开）：接管系统返回键/中间右划，逐层返回而非直接退出软件。
    val useSystemBack by viewModel.useSystemBack.collectAsState()

    // 入场动画只用一次：大图页读到缩略图矩形后就清掉，
    // 之后从编辑页返回时不会再放大一遍（看起来像重新打开）。
    LaunchedEffect(backStack.lastOrNull(), viewerOrigin) {
        if (backStack.lastOrNull() is Screen.Viewer && viewerOrigin != null) {
            kotlinx.coroutines.delay(500)
            viewerOrigin = null
        }
    }

    /**
     * 入栈：同步推进导航栈，再把新页面放到屏幕右侧并缓动滑入。
     *
     * ⚠️ backStack.add 必须与 goBack 的出栈一样「同步」执行：此前它排在协程里、
     * 且位于 enterOffset.snapTo 之后。连续快速导航时 snapTo 会挂起等待上一段动画释放，
     * 这期间同步的 goBack() 可能先把当前页弹掉，随后本协程才把新页 add 进来 ——
     * 栈里就残留一个已经不可见、但仍是 current 的看图页。表现：回到相册后
     * current 不是 Gallery → 相册层 interactive=false → 点相册照片完全没反应，
     * 且「重进 App 就好」（重进把栈重置为 [Gallery]）。日志已证实该现象。
     *
     * add 提到协程外同步执行后，launch 里第一帧 snapTo 仍是同步完成的，不会闪现。
     */
    fun navigate(to: Screen) {
        backStack.add(to)
        navScope.launch {
            enterOffset.snapTo(enterWidthPx)
            enterOffset.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        }
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            suppressDrawerCover = backStack.last() is Screen.Trash
            popTick++
        }
    }

    // 出栈完成后（此时浮层已经移出组合）才把进度归零：
    // 这样既不会闪一帧原图，下一次入栈也能从干净的 0 开始。
    LaunchedEffect(popTick) {
        if (popTick > 0) {
            swipeOffset.snapTo(0f)
            enterOffset.snapTo(0f)
            suppressDrawerCover = false
        }
    }

    /**
     * 打开回收站：抽屉保持展开，作为回收站返回时的底层 —— 右划返回时抽屉全程可见，
     * 不再「返回完成才突然出现」。它的面板会随浮层进度淡出/退开（设置二级菜单同款），
     * 毛玻璃背板则留在原处，正好当回收站的模糊背景。
     */
    fun openTrash() {
        drawerProgressBeforeTrash = settingsDrawer.progress.floatValue
        trashFromDrawer = true
        trashOpened = true
        settingsOpen = false   // 表冠交给回收站；抽屉进度保持不变
        viewerUnder = Screen.Gallery   // 重置：退出回收站时露出的是相册
        viewModel.refreshTrash()
        navigate(Screen.Trash)
    }

    /** 从回收站退出：回到相册，抽屉本来就一直展开着，只需恢复可交互状态。 */
    fun onTrashBack() {
        goBack()
        if (trashFromDrawer) {
            settingsOpen = drawerProgressBeforeTrash > 0.5f
            settingsAtRoot = true
            trashFromDrawer = false
        }
    }

    fun openSettings() {
        settingsOpen = true
        settingsDrawer.open()
    }

    fun closeSettings() {
        settingsOpen = false
        settingsAtRoot = true
        settingsDrawer.close()
    }

    // ── 接管系统返回手势（设置项「使用系统返回手势」控制）──
    //
    // 背景：部分手表把「屏幕中间右划」识别成系统返回手势。应用若不接管，系统默认行为
    // 是直接 finish Activity = 退出软件，用户以为「中间右划退出了 App」。开启本项后，
    // 用 BackHandler 把系统返回接到应用自己的逐层返回逻辑，与左缘右划返回体验一致。
    //
    // 优先级：WatchDialog 内嵌模式自带 BackHandler（组合树更靠里），会先于这里消费，
    // 所以对话框打开时返回先关对话框 —— 这里不需要也不应该抢。
    //
    // enabled 条件里刻意排除「栈底相册 + 设置未开」：这种状态没有更深的层可退，
    // 必须放行给系统，否则用户按返回键将无法退出软件。
    BackHandler(enabled = useSystemBack && (settingsOpen || backStack.size > 1)) {
        when {
            settingsOpen -> {
                if (settingsAtRoot) {
                    // 设置一级菜单：收起抽屉
                    closeSettings()
                } else {
                    // 设置二级页：通知设置页用它自己的滑出动画退回一级菜单
                    settingsBackTick++
                }
            }
            backStack.size > 1 -> {
                if (backStack.last() is Screen.Trash) onTrashBack() else goBack()
            }
        }
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

    // 看图抽屉里删除成功后回到相册：删除走的是系统确认弹窗，弹窗关掉后这里收到事件，
    // 若还停在看图页就退回底层（可能是相册或最近删除）。
    LaunchedEffect(Unit) {
        viewModel.viewerDeleteDone.collect {
            if (backStack.lastOrNull() is Screen.Viewer) goBack()
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
        // 点击门控不能在回调里读捕获进来的 interactive 布尔值 ——
        // pointerInput 的 detectTapGestures 闭包是建链那一刻创建的，会一直持有当时的值。
        // 相册层常驻组合：进回收站/看图期间它被重组为 interactive=false，回到相册后
        // 即使重新变 true，旧闭包里仍是 false（日志已证实：stack=[P] 却 gallery.onPhotoClick
        // interactive=false）。改为在点击真正发生时直接读导航栈：backStack 是稳定的
        // SnapshotStateList 引用，任何时刻读到的都是当前栈顶，不受闭包创建时机影响。
        fun tapAllowed(): Boolean = when (screen) {
            is Screen.Gallery -> backStack.lastOrNull() is Screen.Gallery
            is Screen.Trash -> backStack.lastOrNull() is Screen.Trash
            else -> backStack.lastOrNull() == screen
        }
        // 大图页、编辑页都透明：它们自己带黑底并随入场动画淡入，
        // 这样从大图进编辑页能看到图片「浮起来」而不是先黑一下。
        val bg = when {
            screen is Screen.Viewer || screen is Screen.Editor -> Color.Transparent
            // 回收站始终不渲染背景：直接复用设置抽屉那层铺满全屏的独立背板——
            // 开模糊是「图片列表的毛玻璃」，关模糊是纯色底。这块背板画在抽屉层、
            // 不随面板退开动画淡出，所以进出回收站全程背景恒定，不会闪出相册图片列表。
            screen is Screen.Trash -> Color.Transparent
            else -> Color.Black
        }
        Box(Modifier.fillMaxSize().background(bg)) {
            when (screen) {
                is Screen.Gallery -> GalleryScreen(
                    viewModel = viewModel,
                    isRound = isRound,
                    crownEvents = layerCrown,
                    animateGifs = animateGifs,
                    onPhotoClick = { photo ->
                        if (tapAllowed()) {
                            // 固化打开瞬间的列表快照，而不是让大图页跟着实时 StateFlow 变。
                            // 从最近删除恢复后 refresh() 是异步 scan：若大图页用实时列表，
                            // 打开后列表一重排 index 就会错位（点这张却显示旁边那张、重进才好）。
                            // 用快照后，大图页翻页/定位全程稳定，退出再进才重新取最新列表。
                            viewerPhotos = photos
                            viewerUnder = Screen.Gallery
                            viewerOrigin = viewModel.viewerOrigin
                            viewerThumb = viewModel.viewerThumb
                            currentPhoto = photo
                            navigate(Screen.Viewer)
                        }
                    },
                    onOpenSettings = { if (tapAllowed()) openSettings() },
                    onCheckUpdate = { if (tapAllowed()) navigate(Screen.Update) }
                )
                is Screen.Viewer -> {
                    val cp = currentPhoto
                    val baseList = viewerPhotos ?: photos
                    // 定位点开的那张。**优先用列表里的那个对象**：
                    // uri 是稳定锚点（content uri 内嵌 MediaStore id；回收站 file uri 指向
                    // 唯一备份文件），id 兜底。⚠️ 这里必须和 PhotoViewerScreen 的锚点策略一致：
                    // 回收站图的 id 是「deletedAt + 索引」合成值，会撞车/随重排变化，若 id 优先
                    // 会定位到相邻那张（点这张打不开、旁边的大图能滑进去）。
                    val anchored = cp?.let { c ->
                        baseList.firstOrNull { it.uri == c.uri }
                            ?: baseList.firstOrNull { it.id == c.id }
                    }
                    val target = anchored ?: cp
                    // 列表里确实没有这张（refresh 的异步 scan 还没回填）才补到首位，
                    // 保证「点哪张就一定看得到哪张」，而不是被顶到第 0 张。
                    val viewerList = if (target != null &&
                        baseList.none { it.uri == target.uri || it.id == target.id }
                    ) listOf(target) + baseList else baseList
                    // 再按「同一个对象的 uri → media id」定位它的下标
                    val idx = (if (target != null)
                                 viewerList.indexOfFirst { it.uri == target.uri }
                               else -1)
                        .takeIf { it >= 0 }
                        ?: (if (target != null)
                                viewerList.indexOfFirst { it.id == target.id }
                            else -1).coerceAtLeast(0)
                    PhotoViewerScreen(
                        viewModel = viewModel,
                        photos = viewerList,
                        initialIndex = idx,
                        isRound = isRound,
                        crownEvents = layerCrown,
                        showZoomButtons = showZoomButtons,
                        origin = viewerOrigin,
                        thumb = viewerThumb,
                        blurEnabled = blurBackdrop,
                        // 传入「点进来的那张」：列表异步刷新后据此重新定位，修复恢复图点不开
                        focusPhoto = target,
                        isTrash = viewerUnder is Screen.Trash,
                        onBack = { if (interactive) goBack() },
                        onEdit = { photo ->
                            if (interactive) { currentPhoto = photo; navigate(Screen.Editor) }
                        },
                        onDelete = { photo ->
                            if (interactive) {
                                if (viewerUnder is Screen.Trash) {
                                    // 在最近删除里看图点删除 = 彻底删除（真正调系统删原图）
                                    viewModel.deleteTrashForever(listOf(photo))
                                } else {
                                    viewModel.deleteOneFromViewer(photo)
                                }
                            }
                        },
                        onRestore = { photo ->
                            if (interactive) {
                                viewModel.restoreTrash(photo)
                                goBack()   // 恢复后退回最近删除，列表由 refreshTrash 刷新
                            }
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
                is Screen.Trash -> TrashScreen(
                    viewModel = viewModel,
                    isRound = isRound,
                    crownEvents = layerCrown,
                    animateGifs = animateGifs,
                    onBack = { if (tapAllowed()) onTrashBack() },
                    onOpenPhoto = { photo, all ->
                        if (tapAllowed()) {
                            // 从回收站进看图：navigate 里 snapTo(enterWidthPx) 到 backStack.add 之间
                            // 有一帧 current 仍是 Trash，drawerCover 会按浮层进度算出 1f 闪出一帧设置菜单。
                            // 先压住抽屉 cover，入栈动画结束后由 popTick 之外单独放开。
                            suppressDrawerCover = true
                            viewerPhotos = all       // 大图页翻页用回收站列表
                            viewerUnder = Screen.Trash // 退出查看器露出的底层是回收站
                            // 用回收站缩略图的锚点做放大入场动画（TrashScreen 已写入 viewModel）
                            viewerOrigin = viewModel.viewerOrigin
                            viewerThumb = viewModel.viewerThumb
                            currentPhoto = photo
                            navigate(Screen.Viewer)
                        }
                    }
                )
            }
        }
    }

    // 方屏（设置里选方屏，或系统就是方屏）关掉所有列表的「居中放大」：
    // 直角矩形上这套形变只会让上下两条莫名变小。
    CompositionLocalProvider(LocalCenterMagnify provides isRound) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        val navWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

        // ── 常驻底层：相册 + 回收站 ──
        // 进大图 / 编辑 / 更新 / 回收站看图时都**不卸载**这两层：
        // 退出时条件翻转会整棵重建、缩略图重新解码而闪烁，所以它们永远在组合里，
        // 上层页面只是盖上去。右划返回时，底层读共享的 swipeOffset 做跟手缩放/透明
        // （与设置页二级菜单同款缓动）；露出的到底是相册还是回收站，由 backIsTrash 决定。

        // 谁是当前「浮层」（顶层），谁就是「返回底层」——决定跟手变换的方向：
        //  - 浮层：跟手右移 + 淡出（front）
        //  - 底层：从缩小/变暗平滑放大回正（back，设置页同款）
        // 相册/回收站/查看器之间来回切换时，底层与浮层角色会互换，这里按 current 判定。
        // 变换计算放在文件级普通函数里（见 backLayerTransform / frontLayerTransform），
        // 避免在 graphicsLayer 的隐式 receiver 里做浮点运算触发 Kotlin 运算符歧义。

        // 相册层：始终常驻最底。
        //  - 当前就是相册：正常铺满。
        //  - 浮层是从相册进去的（看图 / 更新）：作为返回底层跟手。
        //  - 浮层来自回收站：相册被回收站盖住，保持正常（回收站自己当底层跟手）。
        val galleryIsBack = current !is Screen.Gallery && viewerUnder !is Screen.Trash
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (galleryIsBack) {
                        // 取「入栈进度」与「返回进度」的较大值：入栈时从 1→0 平滑退开，
                        // 右划返回时从 0→1 平滑回正，两段共用同一条曲线。
                        val t = backLayerTransform(maxOf(enterOffset.value, swipeOffset.value), navWidthPx)
                        scaleX = t.scale; scaleY = t.scale
                        translationX = t.translationX; alpha = t.alpha
                    } else {
                        scaleX = 1f; scaleY = 1f; translationX = 0f; alpha = 1f
                    }
                }
        ) {
            ScreenLayer(Screen.Gallery, interactive = current is Screen.Gallery)
        }

        // 设置抽屉：夹在相册与回收站之间。这样从回收站右划返回时，抽屉作为「返回底层」
        // 在整个手势过程中都露得出来，而不是返回完成才突然出现。
        //  - 抽屉面板：随浮层进度做「缩小 + 左移 + 淡出」（设置二级菜单同款退开动画）；
        //  - 毛玻璃背板：不受影响，始终铺满，正好当回收站的模糊背景。
        val drawerCover: () -> Float = {
            when {
                // 刚退回回收站的那一帧：还在归零途中，先按住不露，避免闪一帧菜单
                suppressDrawerCover -> 0f
                // 当前就在回收站：进入时随浮层退开，右划返回相册时随进度露出（设置同款）
                current is Screen.Trash ->
                    (maxOf(enterOffset.value, swipeOffset.value) / navWidthPx).coerceIn(0f, 1f)
                // 从回收站进看图 / 编辑：底层是回收站自己，设置一级菜单必须全程隐藏。
                // 否则进出看图时会闪出设置菜单，回到回收站又突然消失。
                current !is Screen.Gallery && viewerUnder is Screen.Trash -> 0f
                else -> 1f
            }
        }
        SettingsDrawer(
            state = settingsDrawer,
            collapsedY = collapsedY,
            canDragDown = settingsAtRoot,
            enabled = !settingsOpen && current is Screen.Gallery && !selectionMode,
            blurEnabled = blurBackdrop,
            coverProgress = drawerCover,
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
                onSubPageChanged = { settingsAtRoot = it },
                onOpenTrash = { openTrash() },
                backTick = settingsBackTick
            )
        }

        // 回收站层：打开过就常驻在相册之上（与看图退出共用同一实例，不销毁）。
        // 三种角色：
        //  - current=Trash：它是浮层 → frontTransform + 右划手势
        //  - current 是从回收站进的浮层（看图）：它是返回底层 → backTransform
        //  - 其它：平移出屏幕（不参与命中）。
        if (trashOpened) {
            val trashIsFront = current is Screen.Trash
            val trashIsBack = current !is Screen.Gallery && current !is Screen.Trash &&
                viewerUnder is Screen.Trash
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        when {
                            trashIsFront -> {
                                // 入栈：滑入屏幕（enterOffset 从 widthPx→0）；
                                // 右划返回：跟手右移（swipeOffset 从 0→widthPx）。
                                val off = maxOf(enterOffset.value, swipeOffset.value)
                                translationX = off
                                alpha = frontLayerAlpha(off, navWidthPx)
                                scaleX = 1f; scaleY = 1f
                            }
                            trashIsBack -> {
                                val t = backLayerTransform(maxOf(enterOffset.value, swipeOffset.value), navWidthPx)
                                scaleX = t.scale; scaleY = t.scale
                                translationX = t.translationX; alpha = t.alpha
                            }
                            else -> {
                                translationX = navWidthPx * 2f; alpha = 1f
                                scaleX = 1f; scaleY = 1f
                            }
                        }
                    }
                    .swipeBackGesture(
                        offsetX = swipeOffset,
                        onBack = { onTrashBack() },
                        enabled = trashIsFront
                    )
            ) {
                ScreenLayer(
                    Screen.Trash,
                    // swipeOffset 只驱动返回时的位移动画，绝不能参与点击门控：
                    // 右划返回动画期间它可能停在 ≥1f，若作为条件会把回收站所有点击吞掉，
                    // 表现为「退回最近删除后点任何照片都没反应」。
                    interactive = trashIsFront
                )
            }
        }

        // 上层：栈顶那一屏。右划返回只作用在这一层，底下已经画好，不再复制一份。
        // 编辑页例外：调参数、涂鸦时特别容易误触左缘，所以**禁用右划返回**。
        if (current !is Screen.Gallery && current !is Screen.Trash) {
            if (current is Screen.Editor) {
                ScreenLayer(current, interactive = true)
            } else {
                SwipeBackContainer(
                    onBack = { goBack() },
                    backContent = {},
                    content = { ScreenLayer(current, interactive = true) },
                    offsetX = swipeOffset
                )
            }
        }

        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))

        // 删除「已删除 N 张图片」黑药丸提示：关于页同款，位置略高于底部避开弧边
        InAppToast(
            message = deleteToast,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp)
        )
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
    data object Trash : Screen()
}

/** 右划返回时「返回底层」的跟手变换：从缩小/左偏/变暗平滑放大回正 */
private data class LayerTransform(val scale: Float, val translationX: Float, val alpha: Float)

private fun backLayerTransform(offsetX: Float, widthPx: Float): LayerTransform {
    val progress = (offsetX / widthPx).coerceIn(0f, 1f)
    val eased = 1f - (1f - progress) * (1f - progress)
    return LayerTransform(
        scale = 0.90f + 0.10f * eased,
        translationX = -widthPx * 0.05f * (1f - eased),
        alpha = 0.65f + 0.35f * eased
    )
}

/** 右划返回时「当前浮层」的透明度：跟手右移由 graphicsLayer 直接写 translationX */
private fun frontLayerAlpha(offsetX: Float, widthPx: Float): Float {
    val progress = (offsetX / widthPx).coerceIn(0f, 1f)
    return 1f - 0.38f * progress
}

/** 进程级标记：本次进程是否已做过冷启动更新检测（进程重启才重置） */
private object LaunchUpdateCheck { var ran = false }
