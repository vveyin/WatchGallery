package com.watchgallery.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
 * v0.18 起：列表项「整体」参与居中放大 —— 按钮有较小的基础宽度，居中时放大到最大
 * （约为屏幕宽度），越靠上下边缘越小。缩放锚点是每项自身中心（居中项位于屏幕中心，
 * 放大后向两侧扩到接近满屏），因此「屏幕宽度」是放大后的最大尺寸，而非每项默认宽度。
 * 布局占位固定、间隔由 spacedBy 控制（v0.18 起 6dp）。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 76.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()

    // 表冠平滑滚动：离散事件累加成目标位移后逐帧逼近，避免一格一格的顿挫
    SmoothCrownScroll(crownEvents) { step ->
        listState.scroll { scrollBy(step) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
}

/**
 * 列表项「整体」居中缩放 + 边缘淡出（按钮、标题用）。
 *
 * 测量自身中心到屏幕中心的距离：居中最大、边缘最小。缩放作用于整个 item，
 * 于是按钮会真正地居中放大（最大≈屏幕宽），而不是只放大文字。
 * 用 item 在窗口中的真实 Y 坐标（positionInRoot）计算，graphicsLayer 在绘制阶段
 * 读取该值重绘，不触发重组。
 *
 * @param scale 为 false 时只做淡出、不缩放（用于较长的说明/诊断文本，避免放大后溢出圆屏）
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
                    val s = 1.30f - 0.45f * (t * t)   // 居中 1.30 → 边缘 0.85
                    scaleX = s
                    scaleY = s
                }
                alpha = 1f - 0.42f * t
            }
        }
}
