package com.watchgallery.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 设置抽屉 —— 常驻在主界面之上的一整屏面板，用垂直位移控制显隐：
 *  - drawerOffset = 0          → 完全展开（覆盖屏幕）
 *  - drawerOffset = collapsedY → 完全收起（藏在主界面下方）
 *
 * 交互：
 *  - 主界面底部有一条白色半透明细线，从此处上滑 → 抽屉跟手升起，松手吸附展开；
 *  - 设置一级菜单顶部有一条同样的细线，从此处下滑 → 抽屉跟手降下，松手吸附收起；
 *    二级菜单不响应顶部下滑（用左缘右划返回一级菜单）。
 *
 * 拖动过程用 snapTo 实时跟手，松手用 tween 动画吸附到展开/收起两态。
 *
 * 背景：抽屉背后铺一张静态毛玻璃（[BlurPlate]）。抓图时机是手指按下的那一瞬
 * （抽屉还在屏幕外，所以拍到的是干净的相册），之后抽屉怎么动、内容怎么滚，
 * 背景都不重算 —— 与 f-x 的做法一致。
 */
@Composable
fun SettingsDrawer(
    drawerOffset: Animatable<Float, AnimationVector1D>,
    collapsedY: Float,
    canDragDown: Boolean,
    enabled: Boolean,
    blurEnabled: Boolean,
    onOpened: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slopPx = with(density) { 6.dp.toPx() }
    // 吸附阈值：拖动超过 ~44dp 就切换目标态，否则弹回
    val snapPx = with(density) { 44.dp.toPx() }

    val view = LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // 回到相册（抽屉确定在屏幕外）时把「禁止抓图」的门打开，并补一张干净底图。
    // 只有在 enabled 为真时动门：其它页面（大图/编辑）可能正把门关着，不能被这里误开。
    LaunchedEffect(enabled, blurEnabled) {
        if (!enabled) return@LaunchedEffect
        BlurBackdrop.setCovered(false)
        if (blurEnabled && window != null) {
            // 每次回到相册都重新拍一张：上一张可能是大图/编辑页留下的
            // 延后一点，等相册首帧画完，太早抓会得到一张空图
            kotlinx.coroutines.delay(600)
            BlurBackdrop.capture(window)
        }
    }

    fun settleOpen() {
        onOpened()
        // 抽屉已经在画面上，从这一刻起任何抓图都不干净
        BlurBackdrop.setCovered(true)
        scope.launch { drawerOffset.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
    }
    fun settleClose() {
        onClosed()
        scope.launch {
            drawerOffset.animateTo(collapsedY, tween(280, easing = FastOutSlowInEasing))
            // 面板完全滑出屏幕后才开门补一张：此时画面里只有相册
            BlurBackdrop.setCovered(false)
            if (blurEnabled && window != null) BlurBackdrop.refreshSoon(window, 80)
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
                            // 按下就先拍一帧：此刻抽屉还在屏幕外，拍到的是干净相册。
                            if (blurEnabled && window != null) {
                                BlurBackdrop.setCovered(false)
                                BlurBackdrop.capture(window)
                            }
                            var totalDy = 0f
                            var decided = false
                            var abandoned = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.all { !it.pressed }) break
                                val change = event.changes.first()
                                val dy = change.position.y - change.previousPosition.y
                                totalDy += dy
                                if (!decided && !abandoned && abs(totalDy) > slopPx) {
                                    if (totalDy < 0f) {
                                        decided = true
                                        // 抽屉开始入画，停止刷新
                                        BlurBackdrop.setCovered(true)
                                    } else abandoned = true
                                }
                                if (decided) {
                                    change.consume()
                                    val target = (collapsedY + totalDy).coerceIn(0f, collapsedY)
                                    scope.launch { drawerOffset.snapTo(target) }
                                } else if (abandoned) break
                            }
                            if (decided) {
                                if (drawerOffset.value < collapsedY - snapPx) settleOpen() else settleClose()
                            }
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                GrabLine(modifier = Modifier.padding(bottom = 12.dp), liftProgress = 0f)
            }
        }

        // ── 毛玻璃背板：固定铺满整屏，只露出「抽屉盖住的那一块」──
        // 露出高度 = collapsedY - drawerOffset（收起时 0、展开时整屏），
        // 图本身按整屏坐标绘制，所以拖动时背景像素纹丝不动，只是揭开范围在变。
        if (blurEnabled) {
            BlurBand(
                heightPx = { (collapsedY - drawerOffset.value).coerceIn(0f, screenH) },
                dim = 0.55f
            )
        }

        // ── 抽屉面板：整屏，靠 translationY 升降，自身透明（玻璃板）──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = drawerOffset.value }
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
                                var totalDy = 0f
                                var decided = false
                                var abandoned = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.all { !it.pressed }) break
                                    val change = event.changes.first()
                                    val dy = change.position.y - change.previousPosition.y
                                    totalDy += dy
                                    if (!decided && !abandoned && abs(totalDy) > slopPx) {
                                        if (totalDy > 0f) decided = true else abandoned = true
                                    }
                                    if (decided) {
                                        change.consume()
                                        scope.launch {
                                            drawerOffset.snapTo(totalDy.coerceIn(0f, collapsedY))
                                        }
                                    } else if (abandoned) break
                                }
                                if (decided) {
                                    if (drawerOffset.value > snapPx) settleClose() else settleOpen()
                                }
                            }
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    GrabLine(modifier = Modifier.padding(top = 10.dp), liftProgress = 0f)
                }
            }
        }
    }
}

/** 白色半透明引导细线 */
@Composable
private fun GrabLine(modifier: Modifier = Modifier, liftProgress: Float) {
    Box(
        modifier
            .width(44.dp)
            .height(3.dp)
            .clip(CircleShape)
            .graphicsLayer { alpha = 0.34f + 0.5f * liftProgress }
            .background(Color.White)
    )
}
