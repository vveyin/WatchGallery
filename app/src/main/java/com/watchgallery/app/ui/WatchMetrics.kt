package com.watchgallery.app.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * 圆屏自适应度量 —— 借鉴 f-x 的 WatchMetrics 思路。
 * 圆屏上可用宽度按内切圆弦长计算，所有内容约束在安全圆内，避免被表盘裁切。
 */
data class WatchMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float,
    val isRound: Boolean
) {
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density
    val shortSideDp: Float get() = min(widthDp, heightDp)

    /** 圆屏安全内切圆直径（留 8% 边距） */
    val safeDiameterDp: Float
        get() = if (isRound) shortSideDp * 0.84f else shortSideDp

    /** 内容区左上角相对画布的偏移（居中） */
    val safeInsetDp: Float get() = (shortSideDp - safeDiameterDp) / 2f

    /**
     * 在给定 y 偏移处（相对圆心的垂直距离），圆内可用的水平弦宽。
     * 用于让列表/网格贴近圆形边缘排布。
     */
    fun chordWidthAt(offsetY: Float): Float {
        if (!isRound) return safeDiameterDp
        val r = safeDiameterDp / 2f
        val dy = offsetY.coerceIn(-r, r)
        return 2f * sqrt(max(0f, r * r - dy * dy))
    }

    /** 网格每行列数：圆屏 2 列，方屏按宽度自适应 */
    val gridColumns: Int
        get() = if (isRound) 2 else max(2, (safeDiameterDp / 120f).toInt())

    companion object {
        /** 从 View 尺寸构造，isRound 由系统检测 */
        fun from(size: Size, density: Float, isRound: Boolean) = WatchMetrics(
            widthPx = size.width,
            heightPx = size.height,
            density = density,
            isRound = isRound
        )
    }
}

/** 手表通用尺寸常量（按短边缩放的基础值） */
object WatchDimens {
    val touchTargetMin = 48.dp   // 最小触控区，手表上尽量大
    val iconButton = 44.dp
    val fab = 56.dp
    val cornerLarge = 20.dp
    val padding = 12.dp
}
