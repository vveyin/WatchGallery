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
import androidx.compose.runtime.staticCompositionLocalOf
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
    // 外部可传 weight 等约束（如看图抽屉把列表放进 Column 并占剩余高度）；默认填满
    modifier: Modifier = Modifier,
    // 顶部只留 20dp：列表停在顶部时，标题就贴在抽屉那条白线下面
    // （以前上下都留 76dp，标题被顶到屏幕中上部，白线和标题之间空一大块）。
    // 底部保留 76dp，最后一条才能滚到屏幕中间。
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp, top = 20.dp, end = 12.dp, bottom = 76.dp
    ),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    // 表冠归属门控：默认始终响应；大图抽屉列表用它和图片缩放/弹窗互斥
    crownEnabled: () -> Boolean = { true },
    content: LazyListScope.() -> Unit
) {

    // 表冠滚动：照搬 f-x，事件到达直接 scrollBy（不触发 overscroll 回弹）
    CrownScroll(crownEvents, enabled = crownEnabled) { step ->
        listState.scrollBy(step)
    }

    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
    LazyColumn(
        state = listState,
        modifier = modifier.then(Modifier.fillMaxSize()),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        content()
    }
    }
}

/**
 * 列表项要不要做「居中放大 / 边缘缩小」。
 *
 * 圆屏靠它让中间那条看起来是满宽、避开弧边；方屏整屏是直角矩形，这套形变没有意义，
 * 反而会让上下两条明显变小。开关由 MainActivity 按「屏幕形状」统一提供，
 * 设置列表、相册列表、诊断页等所有用 [scalingItem] / settingItem 的地方一起生效。
 */
val LocalCenterMagnify = staticCompositionLocalOf { true }

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
    val magnify = LocalCenterMagnify.current
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
                if (scale && magnify) {
                    // 只缩不放：居中 1.00 → 边缘 0.70，永不超出布局占位、按钮不再重叠
                    val s = 1.0f - 0.30f * (t * t)
                    scaleX = s
                    scaleY = s
                }
                alpha = 1f - 0.45f * t
            }
        }
}
