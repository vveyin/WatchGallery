package com.watchgallery.app

import android.content.Context
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.Photo
import com.watchgallery.app.theme.WatchGalleryTheme
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.CrashLogger
import com.watchgallery.app.ui.*
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
     * 自适应增益：
     *   - 真正的 REL_WHEEL 一格是十几到几十，直接用；
     *   - 但有些"OPPO 模式"的表实际仍是旋转编码器，一格只上报 ±1，
     *     直接乘灵敏度后滚动量太小、肉眼像没反应。所以 |v| ≤ 2 时按编码器放大。
     */
    private fun oppoRaw(event: MotionEvent): Float {
        for (axis in SCROLL_AXES) {
            val v = event.getAxisValue(axis)
            if (v != 0f && v.isFinite()) {
                return if (abs(v) <= 2f) v * ROTARY_RAW_SCALE else v
            }
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
    var screen by remember { mutableStateOf<Screen>(Screen.Gallery) }
    var currentPhoto by remember { mutableStateOf<Photo?>(null) }
    // 大图翻页用完整列表（不受主页分页显示条数影响）
    val photos by viewModel.allPhotosFlow.collectAsState()

    val context = LocalContext.current
    val isRound = remember {
        context.resources.configuration.screenLayout and
                android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK ==
                android.content.res.Configuration.SCREENLAYOUT_ROUND_YES
    }

    val crownEvents = viewModel.crownEvents

    Box(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            is Screen.Gallery -> GalleryScreen(
                viewModel = viewModel,
                isRound = isRound,
                crownEvents = crownEvents,
                onPhotoClick = { photo ->
                    currentPhoto = photo
                    screen = Screen.Viewer
                },
                onOpenSettings = { screen = Screen.Settings },
                onCheckUpdate = { screen = Screen.Update }
            )
            is Screen.Viewer -> {
                val idx = photos.indexOfFirst { it.uri == currentPhoto?.uri }.coerceAtLeast(0)
                PhotoViewerScreen(
                    photos = photos,
                    initialIndex = idx,
                    isRound = isRound,
                    crownEvents = crownEvents,
                    onBack = { screen = Screen.Gallery },
                    onEdit = { photo ->
                        currentPhoto = photo
                        screen = Screen.Editor
                    }
                )
            }
            is Screen.Editor -> {
                currentPhoto?.let { photo ->
                    EditScreen(
                        photo = photo,
                        viewModel = viewModel,
                        isRound = isRound,
                        onBack = { screen = Screen.Viewer }
                    )
                }
            }
            is Screen.Settings -> SettingsScreen(
                viewModel = viewModel,
                isRound = isRound,
                crownEvents = crownEvents,
                onBack = { screen = Screen.Gallery },
                onCrownPrefsChanged = onCrownPrefsChanged
            )
            is Screen.Update -> UpdateScreen(
                viewModel = viewModel,
                isRound = isRound,
                onBack = { screen = Screen.Gallery }
            )
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

private sealed class Screen {
    data object Gallery : Screen()
    data object Viewer : Screen()
    data object Editor : Screen()
    data object Settings : Screen()
    data object Update : Screen()
}
