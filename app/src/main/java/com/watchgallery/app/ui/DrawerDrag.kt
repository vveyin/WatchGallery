package com.watchgallery.app.ui

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs

/** 一次纵向拖动的结果：是否真的拖动过、累计位移、松手时的速度（都按屏幕坐标，向下为正） */
internal class VerticalDrag(
    val dragged: Boolean,
    val total: Float,
    val velocity: Float
)

/**
 * 跟手累计纵向位移（两态抽屉专用）。
 *
 * 关键：**只累加相邻两帧的差**（`position - previousPosition`），
 * 而不是「当前 y − 按下时的 y」。
 *
 * 面板本身是跟着手指在动的，它的局部坐标系会朝反方向偏移：
 * 手指往下 10px，面板也往下 10px，那么「当前局部 y − 按下时局部 y」还是 0。
 * 之前就是这么算的，于是「拉上来之后怎么划都不动」，只有松手时按状态回弹。
 *
 * 而同一帧里 position 和 previousPosition 经过的是同一个层变换，
 * 相减后变换被完全抵消，剩下的正好是屏幕上的真实位移 —— 与面板动不动无关。
 *
 * @param slopPx 超过这个位移才认为用户是在拖动（不消费，点击类控件照常工作）
 * @param onDrag 每次位移后回调累计值，调用方直接同步赋值给状态（不能 launch）
 */
internal suspend fun AwaitPointerEventScope.trackVerticalDrag(
    down: PointerInputChange,
    slopPx: Float,
    onDrag: (total: Float) -> Unit
): VerticalDrag {
    var total = 0f
    var dragged = false
    var lastMs = down.uptimeMillis
    // 指数平滑的瞬时速度，收尾停住不动时会很快衰减，避免「拖一下就飞走」
    var velocity = 0f

    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        // 还没开始拖就发现有别的控件把事件吃掉了（滑杆、横向列表）：
        // 把手势让给它，避免「调亮度顺手把抽屉拖下来」。
        if (!dragged && change.isConsumed) break
        val dy = change.position.y - change.previousPosition.y
        total += dy

        val dt = (change.uptimeMillis - lastMs).coerceAtLeast(1L).toFloat()
        velocity = velocity * 0.55f + (dy / dt * 1000f) * 0.45f
        lastMs = change.uptimeMillis

        if (!dragged && abs(total) > slopPx) dragged = true
        if (dragged) {
            change.consume()
            onDrag(total)
        }
        if (!change.pressed) break
    }
    return VerticalDrag(dragged, total, velocity)
}
