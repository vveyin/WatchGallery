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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

/** 供 scalingItem 读取当前缩放列表的滚动状态 */
private val LocalScalingState = compositionLocalOf<LazyListState?> { null }

/**
 * Wear OS 风格的缩放滚动列表 —— 设置主页、二级菜单、更新页等全屏列表用。
 *
 * 全屏铺满，条目越靠近屏幕中心越大、越靠边缘越小并淡出，靠缩放自然避开弧边。
 * 表冠增量驱动滚动。条目用 [scalingItem] 包装即可获得缩放效果。
 *
 * 关键：条目必须在 draw 阶段每帧重算缩放，否则 LazyColumn 滚动只移动布局位置、
 * 不 invalidate 条目自身的绘制，缩放会卡在条目首次进入可见区时的值（边缘→小），
 * 表现为"中间小、两边大"。[scalingItem] 中显式读取滚动 SnapshotState 强制重绘。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
    contentPadding: PaddingValues = PaddingValues(vertical = 84.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: LazyListScope.() -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            listState.scroll { scrollBy(-delta * 9f) }
        }
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
 * 缩放列表条目的包装：按自身在视口中的位置计算缩放与透明度。
 *
 * 以「视口垂直中心」为锚点、按条目自身几何中心到锚点的距离归一化，二次缓动，
 * 中心项最大（1.28x）、相邻明显更大、边缘淡出；进出可见区不突变。
 */
@Composable
fun Modifier.scalingItem(key: String): Modifier {
    val state = LocalScalingState.current
    if (state == null) return this
    return this.graphicsLayer {
        // 显式读取滚动 SnapshotState，强制 draw 阶段每帧重算。
        state.firstVisibleItemIndex
        state.firstVisibleItemScrollOffset

        val info = state.layoutInfo
        val anchor = info.viewportSize.height / 2f
        val mine = info.visibleItemsInfo.firstOrNull { it.key == key }
        if (mine != null && mine.size > 0 && anchor > 0f) {
            val itemCenter = mine.offset + mine.size / 2f
            val distance = kotlin.math.abs(itemCenter - anchor)
            val t = (distance / (mine.size * 1.35f)).coerceIn(0f, 1f)
            val eased = t * t
            val sc = 1.28f - 0.70f * eased   // 中心 1.28 → 边缘 0.58
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.62f * t          // 中心清晰 → 边缘淡出
        }
    }
}
