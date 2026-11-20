package com.watchgallery.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 交互式返回容器（iOS 风格）：
 *
 * 底层始终渲染「返回后的界面」[backContent]，顶层是当前界面 [content]。
 * 手指从屏幕左边缘按下并向右划动时：
 *  - 顶层跟手右移并逐渐淡出；
 *  - 底层从缩小/偏移状态平滑放大回正，露出返回目标；
 * 松手若位移超过阈值，先把顶层用动画完整滑出屏幕，动画结束后再触发 [onBack]，
 * 这样不会出现「划过去当前界面突然消失」的跳变；未超阈值则动画回弹归位。
 *
 * 只接管"从左边缘开始的水平右划"：一旦判定为垂直滚动或反向滑动就立即放手，
 * 不干扰列表滚动与表冠事件。
 */
@Composable
fun SwipeBackContainer(
    onBack: () -> Unit,
    backContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val edgePx = with(density) { 26.dp.toPx() }
    val thresholdPx = with(density) { 72.dp.toPx() }
    val touchSlop = with(density) { 8.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

        // 底层：返回后的界面。随手势进度从「缩小 + 左偏 + 变暗」平滑放大回正。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
                    // 缓出曲线：手势前半段就有明显层次变化
                    val eased = 1f - (1f - progress) * (1f - progress)
                    val scale = 0.90f + 0.10f * eased
                    scaleX = scale
                    scaleY = scale
                    translationX = -widthPx * 0.05f * (1f - eased)
                    alpha = 0.65f + 0.35f * eased
                }
        ) {
            backContent()
        }

        // 顶层：当前界面，跟手右移 + 淡出，超阈值时完整滑出后再切换页面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                    val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
                    alpha = 1f - 0.38f * progress
                }
                .pointerInput(onBack) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x > edgePx) return@awaitEachGesture
                        down.consume()

                        var totalDx = 0f
                        var totalDy = 0f
                        var decided = false
                        var abandoned = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.all { !it.pressed }) break
                            val change = event.changes.first()
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            totalDx += dx
                            totalDy += dy
                            if (!decided && !abandoned) {
                                if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                    if (totalDx > 0f && abs(totalDx) > abs(totalDy) * 1.2f) {
                                        decided = true
                                    } else {
                                        abandoned = true
                                    }
                                }
                            }
                            if (decided) {
                                change.consume()
                                scope.launch { offsetX.snapTo(totalDx.coerceAtLeast(0f)) }
                            } else if (abandoned) {
                                break // 垂直方向或反向，交还列表
                            }
                        }
                        if (decided && totalDx > thresholdPx) {
                            // 先把当前页完整滑出，动画结束后再切换页面，避免突然消失。
                            scope.launch {
                                offsetX.animateTo(widthPx, tween(170, easing = FastOutSlowInEasing))
                                onBack()
                                // 页面已切换：回到 0，让新的顶层页正常显示。
                                offsetX.snapTo(0f)
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                        }
                    }
                }
        ) {
            content()
        }
    }
}
