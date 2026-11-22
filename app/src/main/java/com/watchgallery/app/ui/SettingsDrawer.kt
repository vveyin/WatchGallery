package com.watchgallery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * 设置抽屉 —— 常驻在主界面之上的一整屏面板。
 *
 * 进度语义和另外两处抽屉完全一致（[DrawerState]）：0 = 收起在屏幕外，1 = 铺满。
 * 拖动只写一个 Float，松手后由 [DrawerSettle] 一条协程吸附 —— 与 f-x 的 `TabDeck` 同款。
 *
 * 交互：
 *  - 主界面底部一条白线，上滑 → 抽屉跟手升起；
 *  - 抽屉顶部一条白线，下滑 → 跟手降下（二级菜单不响应，用左缘右划返回）；
 *  - 松手按**方向**吸附：往上拉过一点点就展开、往下拉过一点点就收起。
 *
 * 背景：抽屉背后铺一张静态毛玻璃（[BlurBand]），像素固定在屏幕坐标上，
 * 抽屉只负责"揭开"它。抓图时机是手指按下的那一瞬（抽屉还在屏幕外）。
 */
@Composable
fun SettingsDrawer(
    state: DrawerState,
    collapsedY: Float,
    canDragDown: Boolean,
    enabled: Boolean,
    blurEnabled: Boolean,
    onOpened: () -> Unit,
    onClosed: () -> Unit,
    /** 面板被浮层盖住的程度：1 = 完全露出，0 = 完全退开（回收站的返回底层）。
     *  用 lambda 而不是 Float：进度每帧都在变，放进绘制阶段读只重绘不重组。 */
    coverProgress: () -> Float = { 1f },
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val slopPx = with(density) { 5.dp.toPx() }
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    val view = LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    DrawerSettle(state) { open -> if (open) onOpened() else onClosed() }

    // 抽屉只要露出一点，任何抓图都不干净（会把面板自己糊进背景）
    val visible by remember { derivedStateOf { state.progress.floatValue > 0.002f } }
    LaunchedEffect(visible, enabled, blurEnabled) {
        // 不在相册页时这扇门归大图 / 编辑页管，这里一个字都不碰
        if (!enabled) return@LaunchedEffect
        if (visible) {
            BlurBackdrop.setCovered(true)
        } else {
            BlurBackdrop.setCovered(false)
            if (blurEnabled && window != null) BlurBackdrop.refreshSoon(window, 80)
        }
    }

    // 回到相册（抽屉确定在屏幕外）时补一张干净的底图：上一张可能是大图/编辑页留下的
    LaunchedEffect(enabled, blurEnabled) {
        if (!enabled) return@LaunchedEffect
        BlurBackdrop.setCovered(false)
        if (blurEnabled && window != null) {
            kotlinx.coroutines.delay(600)
            BlurBackdrop.capture(window)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ── 底部上滑把手：主界面可见；抽屉展开后被面板盖住，自然失效 ──
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 按下就先拍一帧：此刻抽屉还在屏幕外，拍到的是干净相册
                            if (blurEnabled && window != null) {
                                BlurBackdrop.setCovered(false)
                                BlurBackdrop.capture(window)
                            }
                            awaitDrawerDrag(
                                state = state,
                                down = down,
                                travelPx = collapsedY,
                                slopPx = slopPx,
                                onClaimed = { BlurBackdrop.setCovered(true) }
                            )
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                GrabLine(modifier = Modifier.padding(bottom = 12.dp))
            }
        }

        // ── 毛玻璃背板：固定铺满整屏，只露出「抽屉盖住的那一块」──
        if (blurEnabled) {
            BlurBand(
                heightPx = { (state.progress.floatValue * collapsedY).coerceIn(0f, screenH) },
                dim = 0.55f
            )
        }

        // ── 抽屉面板：整屏，靠 translationY 升降，自身透明（玻璃板）──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - state.progress.floatValue) * collapsedY
                    // 浮层盖住时面板「缩小 + 左移 + 淡出」，露出时平滑回正：
                    // 与设置二级菜单的一级菜单退开完全同一套参数。
                    val cp = coverProgress().coerceIn(0f, 1f)
                    val eased = 1f - (1f - cp) * (1f - cp)
                    val s = 0.90f + 0.10f * eased
                    scaleX = s
                    scaleY = s
                    translationX = -screenW * 0.05f * (1f - eased)
                    alpha = cp
                }
        ) {
            if (!blurEnabled) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }

            content()

            // 顶部下滑把手：仅设置一级菜单显示，白线在抽屉内容顶部（非屏幕顶部）
            if (canDragDown) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(34.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                awaitDrawerDrag(
                                    state = state,
                                    down = down,
                                    travelPx = collapsedY,
                                    slopPx = slopPx
                                )
                            }
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    GrabLine(modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/** 白色半透明引导细线 */
@Composable
private fun GrabLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(44.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.55f))
    )
}
