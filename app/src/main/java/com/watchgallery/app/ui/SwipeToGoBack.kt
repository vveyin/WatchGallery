package com.watchgallery.app.ui

import androidx.compose.animation.core.Animatable
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
 * 手指从屏幕左边缘按下并向右划动时，顶层跟随右移并逐渐淡出，露出下面的返回目标界面；
 * 松手若位移超过阈值则触发 [onBack]，否则回弹归位。
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

        // 底层：返回后的界面，一直渲染在下面
        Box(modifier = Modifier.fillMaxSize()) {
            backContent()
        }

        // 顶层：当前界面，跟手右移 + 淡出，露出底层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.value
                    val progress = (offsetX.value / widthPx).coerceIn(0f, 1f)
                    alpha = 1f - 0.45f * progress
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
                                    if (totalDx > 0f && abs(totalDx) > abs(totalDy) * 1.2f) decided = true
                                    else abandoned = true
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
                            onBack()
                            scope.launch { offsetX.snapTo(0f) }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(180)) }
                        }
                    }
                }
        ) {
            content()
        }
    }
}
