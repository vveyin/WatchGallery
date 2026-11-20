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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
 *    二级菜单不响应顶部下滑（用右划返回一级菜单）。
 *
 * 拖动过程用 snapTo 实时跟手，松手用 tween 动画吸附到展开/收起两态。
 */
@Composable
fun SettingsDrawer(
    drawerOffset: Animatable<Float, AnimationVector1D>,
    collapsedY: Float,
    canDragDown: Boolean,
    enabled: Boolean,
    onOpened: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slopPx = with(density) { 6.dp.toPx() }
    // 吸附阈值：拖动超过 ~44dp 就切换目标态，否则弹回
    val snapPx = with(density) { 44.dp.toPx() }

    fun settleOpen() {
        onOpened()
        scope.launch { drawerOffset.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
    }
    fun settleClose() {
        onClosed()
        scope.launch { drawerOffset.animateTo(collapsedY, tween(280, easing = FastOutSlowInEasing)) }
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
                                    if (totalDy < 0f) decided = true else abandoned = true
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

        // ── 抽屉面板：整屏，靠 translationY 升降 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = drawerOffset.value }
        ) {
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
