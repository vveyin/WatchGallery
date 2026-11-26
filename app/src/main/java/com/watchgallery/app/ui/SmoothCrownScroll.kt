package com.watchgallery.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow

/**
 * 表冠滚动 —— 照搬 vveyin/f-x 项目 FunctionList.kt 的实现（原生无阻尼）。
 *
 * f-x 的核心就一句：每个表冠事件到达，直接 listState.scrollBy(-amount * FACTOR)，
 * 没有累加、没有逐帧逼近、没有阻尼滑行 —— 转多少滚多少，停手即停。
 *
 * 之前的 SmoothCrownScroll 把所有增量累加成「目标位移」，再用阻尼循环每帧逼近 22%，
 * 停手后还会继续滑行一段时间（阻尼感）。按用户要求撤销，回到 f-x 的即时手感。
 *
 * @param crownEvents 表冠增量事件流（已含方向与灵敏度）
 * @param pixelsPerUnit 每个增量单位对应的滚动像素，f-x 取 1.0
 * @param enabled 门控：表冠事件的「归属」判断，返回 false 时本消费者丢弃该事件。
 *                大图页有多个表冠消费者（图片缩放 / 抽屉列表 / 详情浮窗），
 *                它们订阅同一个 SharedFlow，必须靠它做互斥，避免一串事件被用两遍。
 *                用 rememberUpdatedState 读最新值，切换归属不必重启 collect。
 * @param applyDelta 把位移应用到具体滚动状态；step 已内化负号，直接 scrollBy 即可
 */
@Composable
fun CrownScroll(
    crownEvents: Flow<Float>,
    pixelsPerUnit: Float = 1f,
    enabled: () -> Boolean = { true },
    applyDelta: suspend (Float) -> Unit
) {
    val enabledRef = rememberUpdatedState(enabled)
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            if (enabledRef.value() && delta.isFinite() && delta != 0f) {
                applyDelta(-delta * pixelsPerUnit)
            }
        }
    }
}
