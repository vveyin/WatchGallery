package com.watchgallery.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * Wear OS 风格的「边缘右划返回」手势。
 *
 * 手指从屏幕左边缘按下并向右划动时，整页跟随右移并淡出；松手若位移超过阈值
 * 则触发 onBack，否则回弹归位。替代各页面左上角的返回按钮。
 *
 * 只接管"从左边缘开始的水平右划"：一旦判定为垂直滚动就立即放手，
 * 不干扰列表滚动与表冠事件。
 */
@Composable
fun Modifier.swipeToGoBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val edgePx = with(density) { 24.dp.toPx() }
    val thresholdPx = with(density) { 64.dp.toPx() }
    val touchSlop = with(density) { 8.dp.toPx() }

    return this
        .graphicsLayer {
            translationX = offsetX.value
            alpha = (1f - abs(offsetX.value) / (thresholdPx * 3f)).coerceIn(0.35f, 1f)
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
                            if (abs(totalDx) > abs(totalDy) * 1.2f) decided = true
                            else abandoned = true
                        }
                    }
                    if (decided) {
                        change.consume()
                        scope.launch { offsetX.snapTo(totalDx.coerceAtLeast(0f)) }
                    } else if (abandoned) {
                        break // 垂直方向，交还列表
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
}
