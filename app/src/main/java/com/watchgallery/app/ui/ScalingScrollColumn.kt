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
 * v0.17 起：按钮外框尺寸与间隔完全固定（fillMaxWidth + 固定高度 + spacedBy），
 * 不再用 graphicsLayer 缩放整个 item —— 绘制层缩放不改变布局占位，放大后
 * 按钮会向外膨胀侵入间隔，导致「间隔忽大忽小 / 重叠」。居中聚焦改为只缩放
 * 按钮的「内部内容」（文字/图标，见 [focusScale]），外框和间隔恒定。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 76.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
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
 * 列表条目的淡出包装：只按条目中心到屏幕中心的距离调整 alpha（居中清晰、边缘淡出），
 * 不改变任何尺寸，因此不会侵入固定间隔。
 *
 * 用 item 在窗口中的真实 Y 坐标（positionInRoot）与屏幕高度一半比较；
 * onGloballyPositioned 在滚动导致位置变化时每帧回调，graphicsLayer 在绘制阶段
 * 读取该值重绘，不触发重组。
 */
@Composable
fun Modifier.scalingItem(key: String): Modifier {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val centerY = remember(key) { mutableFloatStateOf(-1f) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            centerY.floatValue = pos.y + coords.size.height / 2f
        }
        .graphicsLayer {
            val cy = centerY.floatValue
            val vc = screenHeightPx / 2f
            alpha = if (cy >= 0f && vc > 0f) {
                val t = (abs(cy - vc) / (vc * 0.95f)).coerceIn(0f, 1f)
                1f - 0.42f * t
            } else 1f
        }
}

/**
 * 内容聚焦缩放：测量自身中心，按到屏幕中心的距离缩放自身（用于按钮内的文字/图标）。
 *
 * 与 [scalingItem] 不同，这里真正应用 scaleX/scaleY —— 但作用对象是按钮「内部内容」
 * 而非按钮外框，所以按钮尺寸与间隔保持恒定，只会让居中项的文字/图标更大更清晰。
 */
@Composable
fun Modifier.focusScale(): Modifier {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val centerY = remember { mutableFloatStateOf(-1f) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            centerY.floatValue = pos.y + coords.size.height / 2f
        }
        .graphicsLayer {
            val cy = centerY.floatValue
            val vc = screenHeightPx / 2f
            if (cy >= 0f && vc > 0f) {
                val t = (abs(cy - vc) / (vc * 0.9f)).coerceIn(0f, 1f)
                val s = 1.12f - 0.30f * (t * t)   // 居中 1.12 → 边缘 0.82
                scaleX = s
                scaleY = s
            }
        }
}
