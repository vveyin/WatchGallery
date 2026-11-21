package com.watchgallery.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs

/**
 * 两态抽屉的进度：**0 = 完全收起（在屏幕外），1 = 完全铺开**。
 *
 * 三处抽屉（设置 / 查看大图 / 编辑）共用这一份实现，做法照抄 f-x 的 `TabDeck`：
 *
 * | 阶段 | 谁写 [progress] | 代价 |
 * |---|---|---|
 * | 拖动中 | 手指事件直接写这个 Float | 零协程、零调度，手到哪抽屉到哪 |
 * | 松手后 | 一条 `animate` 把 progress 推到 [target] | 全程只有一个协程 |
 *
 * 之前的实现有两处病根，都是"每帧开一个协程去 `snapTo`"：
 * 旧协程晚到一步就把新值盖回去（抽一下），而 Compose 的受限挂起作用域里
 * 又调不了 `Animatable`，只能这么绕。现在拖动只写一个 Float，什么都不会乱。
 */
@Stable
class DrawerState(initiallyOpen: Boolean = false) {

    /** 当前进度（0~1）。拖动中由手指直接改，松手后由 [DrawerSettle] 缓动。 */
    val progress = mutableFloatStateOf(if (initiallyOpen) 1f else 0f)

    /** 吸附目标：0 或 1。只在松手 / 外部调用 open/close 时写。 */
    var target by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set

    /** 手指是否正按着抽屉拖（拖动中不做吸附动画） */
    var dragging by mutableStateOf(false)
        private set

    /** 本次拖动起点（用来判断"这一下是往哪个方向拉"） */
    private var dragFrom = 0f

    /** 速度（progress / 毫秒），用于快速一甩时就近吸附 */
    private var velocity = 0f
    private var lastAt = 0L
    private var lastProgress = 0f

    val isOpen: Boolean get() = target >= 0.5f

    fun beginDrag() {
        dragging = true
        dragFrom = progress.floatValue
        velocity = 0f
        lastAt = 0L
        lastProgress = dragFrom
    }

    /** 拖动中每帧调用（fraction 已经换算成 0~1） */
    fun dragTo(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        val now = System.nanoTime() / 1_000_000L
        val dt = now - lastAt
        if (dt in 1..64) {
            velocity = velocity * 0.55f + ((v - lastProgress) / dt.toFloat()) * 0.45f
        } else if (dt > 64) {
            velocity = 0f
        }
        lastAt = now
        lastProgress = v
        progress.floatValue = v
    }

    /**
     * 松手：定吸附目标。
     *
     * 判据是「**方向**优先，距离兜底」—— 这是手表上唯一的可行解：
     * 屏幕本来就只有两百多 dp，手指从画面中间起手时往下最多只能拖五六十字，
     * 按"过半屏"或"过 42%"判定永远够不着，松手就弹回去，手感就是
     * "怎么拉都拉不下去"。现在只要往那个方向拉过 [DIRECTION_EPS] 就倒向那一边。
     */
    fun release() {
        dragging = false
        val moved = progress.floatValue - dragFrom
        val open = when {
            velocity > FLING -> true          // 快速上甩
            velocity < -FLING -> false        // 快速下甩
            moved > DIRECTION_EPS -> true     // 往上拉过一点
            moved < -DIRECTION_EPS -> false   // 往下拉过一点
            else -> progress.floatValue > 0.5f
        }
        velocity = 0f
        target = if (open) 1f else 0f
    }

    /** 手势没认领（只是点了一下）：原状态不变 */
    fun cancelDrag() {
        dragging = false
        velocity = 0f
        target = if (progress.floatValue > 0.5f) 1f else 0f
    }

    fun open() { target = 1f }

    fun close() { target = 0f }

    /** 直接落位、不播动画（进页面时用） */
    fun snap(open: Boolean) {
        dragging = false
        velocity = 0f
        progress.floatValue = if (open) 1f else 0f
        target = if (open) 1f else 0f
    }

    private companion object {
        /** 方向判定阈值：progress 的 6%（约 14dp 屏高），一根手指轻轻一推就够 */
        const val DIRECTION_EPS = 0.06f

        /** 快速一甩：0.8 屏/秒 */
        const val FLING = 0.0008f
    }
}

/** 记住一个抽屉状态（跨重组保留） */
@Composable
fun rememberDrawerState(initiallyOpen: Boolean = false): DrawerState =
    remember { DrawerState(initiallyOpen) }

/**
 * 吸附动画：**整个抽屉生命周期里只有这一条协程**。
 *
 * 拖动中直接 return（手指自己写 progress）；松手后把它从当前位置推到 target。
 * `onSettled` 在动画结束时回调一次，用来做"落位后才允许抓背景"这类收尾。
 */
@Composable
fun DrawerSettle(state: DrawerState, onSettled: (open: Boolean) -> Unit = {}) {
    val settled = rememberUpdatedState(onSettled)
    LaunchedEffect(state.target, state.dragging) {
        if (state.dragging) return@LaunchedEffect
        val from = state.progress.floatValue
        val to = state.target
        if (abs(from - to) > 0.001f) {
            animate(
                initialValue = from,
                targetValue = to,
                animationSpec = spring(dampingRatio = 0.90f, stiffness = 520f)
            ) { value, _ -> state.progress.floatValue = value }
        }
        state.progress.floatValue = to
        settled.value(to >= 0.5f)
    }
}

/**
 * 把手势换算成抽屉进度。
 *
 * 只累加**相邻两帧的差**（`position - previousPosition`）：面板本身跟着手指在动，
 * 它的局部坐标系会朝反方向偏移，拿"当前 y − 按下时 y"去算会互相抵消；
 * 而同一帧里两个坐标过的是同一个层变换，相减后变换被抵消，剩下的就是屏幕位移。
 *
 * @param travelPx 走完 0→1 需要的手指行程（px）。全屏面板就是屏高，1:1 跟手。
 */
internal suspend fun AwaitPointerEventScope.awaitDrawerDrag(
    state: DrawerState,
    down: PointerInputChange,
    travelPx: Float,
    slopPx: Float,
    onClaimed: () -> Unit = {}
) {
    state.beginDrag()
    var total = 0f
    var claimed = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        total += change.position.y - change.previousPosition.y
        if (!claimed) {
            if (abs(total) > slopPx) {
                claimed = true
                onClaimed()
            } else {
                if (!change.pressed) break
                continue
            }
        }
        change.consume()
        // 手指往下 = 抽屉收起（progress 变小）
        state.dragTo(state.progress.floatValue - (change.position.y - change.previousPosition.y) / travelPx)
        if (!change.pressed) break
    }
    if (claimed) state.release() else state.cancelDrag()
}
