package com.watchgallery.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * 表冠平滑滚动控制器（通用）。
 *
 * 物理表冠每"咔哒"一格才上报一个离散 MotionEvent。若在事件到达的瞬间直接
 * scrollBy 一大段，肉眼看到的就是"一格一格"的顿挫。这里把离散增量先累加成
 * 一个「目标位移」，再用阻尼循环逐帧逼近，把每一跳摊成连续的滑行：
 *
 *   离散事件 ──累加──▶ target ──每帧逼近 22%──▶ applied ──▶ applyDelta(step)
 *
 * 快速连续转动时 target 持续增大，applied 一路追赶，形成跟手的连续滚动；
 * 停手后 applied 平滑追上 target 停住。底层是网格、列表还是普通 Column 都无所谓，
 * 调用方只需在 [applyDelta] 里把这一小步应用到自己的滚动状态。
 *
 * @param crownEvents 表冠增量事件流
 * @param pixelsPerUnit 每个表冠增量单位对应的目标像素（越大转一格滚得越远）
 * @param applyDelta 每帧收到的小位移，调用方应用到具体滚动状态（step 已含方向）
 */
@Composable
fun SmoothCrownScroll(
    crownEvents: Flow<Float>,
    pixelsPerUnit: Float = 9f,
    applyDelta: suspend (Float) -> Unit
) {
    // 离散事件累加成目标位移（内化负号，使 step 方向与列表 scrollBy 一致）
    val target = remember { MutableStateFlow(0f) }
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            if (delta.isFinite() && delta != 0f) {
                target.value += -delta * pixelsPerUnit
            }
        }
    }

    // 逐帧阻尼逼近：把瞬间的一大跳摊成连续滑行
    LaunchedEffect(Unit) {
        var applied = 0f
        while (true) {
            withFrameNanos { }
            val diff = target.value - applied
            when {
                abs(diff) > 0.5f -> {
                    val step = diff * 0.22f
                    applyDelta(step)
                    applied += step
                }
                diff != 0f -> {
                    applyDelta(diff)
                    applied = target.value
                }
            }
        }
    }
}
