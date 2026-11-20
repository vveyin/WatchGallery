package com.watchgallery.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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

/** 供 scalingItem 读取当前缩放列表的滚动状态 */
private val LocalScalingState = compositionLocalOf<LazyListState?> { null }

/**
 * Wear OS 风格的缩放滚动列表 —— 设置主页、二级菜单等全屏列表用。
 *
 * 全屏铺满，条目越靠近屏幕中心越大、越靠边缘越小并淡出，靠缩放自然避开弧边。
 * 表冠增量驱动滚动。条目用 [scalingItem] 包装即可获得缩放效果。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 30.dp, vertical = 84.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()

    // 表冠平滑滚动：离散事件累加成目标位移后逐帧逼近，避免一格一格的顿挫
    SmoothCrownScroll(crownEvents) { step ->
        listState.scroll { scrollBy(step) }
    }

    CompositionLocalProvider(LocalScalingState provides listState) {
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
 * 缩放列表条目的包装：按条目「真实屏幕位置」到屏幕正中心的距离计算缩放与透明度。
 *
 * 关键修正：不再用 layoutInfo 里 item.offset 去比对视口中心 —— 那个 offset 的相对
 * 坐标系（是否含 contentPadding / viewportStartOffset）在各版本里不一致，会导致
 * 「最大项出现在屏幕偏下三分之一」而非正中。这里直接取 item 在窗口中的实际 Y 坐标，
 * 与窗口高度的一半比较，最大项严格落在屏幕正中心，进出可见区不突变。
 *
 * onGloballyPositioned 在滚动导致布局位置变化时每帧回调，更新 centerY；graphicsLayer
 * 绘制阶段读取它并显式读取滚动 SnapshotState，保证平滑重绘、不触发重组。
 */
@Composable
fun Modifier.scalingItem(key: String): Modifier {
    val state = LocalScalingState.current
    if (state == null) return this
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val centerY = remember { mutableFloatStateOf(-1f) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            centerY.floatValue = pos.y + coords.size.height / 2f
        }
        .graphicsLayer {
            // 显式读取滚动 SnapshotState，强制 draw 阶段每帧重算。
            state.firstVisibleItemIndex
            state.firstVisibleItemScrollOffset

            val cy = centerY.floatValue
            val viewportCenter = screenHeightPx / 2f
            if (cy >= 0f && viewportCenter > 0f) {
                val distance = kotlin.math.abs(cy - viewportCenter)
                // 以屏幕半高的 0.9 倍为衰减半径：相邻项就有明显大小差，边缘收敛平滑。
                val radius = viewportCenter * 0.9f
                val t = (distance / radius).coerceIn(0f, 1f)
                val eased = t * t
                val sc = 1.14f - 0.48f * eased   // 正中 1.14 → 边缘 0.66（幅度收敛，避免放大后超出圆屏/相邻重叠）
                scaleX = sc
                scaleY = sc
                alpha = 1f - 0.55f * t          // 正中清晰 → 边缘淡出
            }
        }
}
