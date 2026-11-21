package com.watchgallery.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/**
 * Wear OS 风格缩放滚动列表 —— 设置主页、二级菜单等全屏列表用。
 *
 * v0.21 起对齐官方 TransformingLazyColumn 机制：按钮满宽，居中项为正常大小（≈满屏宽），
 * 越靠上下边缘越小越淡；缩放「只缩不放」（系数 ≤ 1），因此按钮始终待在布局框内、
 * 间距恒定、不再重叠。布局占位固定、间隔由 spacedBy 控制。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 76.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {

    // 表冠滚动：照搬 f-x，事件到达直接 scrollBy（不触发 overscroll 回弹）
    CrownScroll(crownEvents) { step ->
        listState.scrollBy(step)
    }

    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
    }
}

/**
 * 列表项「只缩不放」的圆屏形变 + 边缘淡出（按钮、标题用）—— 参照 Wear OS 官方
 * TransformingLazyColumn + SurfaceTransformation：居中项是正常大小，越靠边缘越缩小淡出。
 *
 * 关键：缩放系数始终 ≤ 1（居中 1.0 → 边缘 0.70）。绘制层缩放不改变布局占位，
 * 若像旧版那样把居中项放大到 1.3 倍，按钮会向外膨胀侵入固定间距、相互重叠。
 * 只缩不放则按钮永远待在自己的布局框内，间距恒定、绝不重叠，且视觉上正是
 * 「居中满宽最大、边缘缩小」的官方设置列表效果。
 *
 * @param scale 为 false 时只做淡出、不缩放（用于较长的说明/诊断文本）
 */
@Composable
fun Modifier.scalingItem(key: String, scale: Boolean = true): Modifier {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val centerY = remember(key) { mutableFloatStateOf(-1f) }
    val measured = remember(key) { mutableStateOf(false) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            centerY.floatValue = pos.y + coords.size.height / 2f
            measured.value = true
        }
        .graphicsLayer {
            val cy = centerY.floatValue
            val vc = screenHeightPx / 2f
            // 与照片列表同理：测量过就始终计算，避免滚出屏幕后 cy<0 跳过缩放
            // 导致 scale 恢复 1.0「突然变大」。
            if (measured.value && vc > 0f) {
                val t = (abs(cy - vc) / (vc * 1.05f)).coerceIn(0f, 1f)
                if (scale) {
                    // 只缩不放：居中 1.00 → 边缘 0.70，永不超出布局占位、按钮不再重叠
                    val s = 1.0f - 0.30f * (t * t)
                    scaleX = s
                    scaleY = s
                }
                alpha = 1f - 0.45f * t
            }
        }
}
