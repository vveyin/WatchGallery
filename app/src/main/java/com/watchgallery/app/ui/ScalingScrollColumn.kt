package com.watchgallery.app.ui

import androidx.compose.foundation.gestures.scrollBy
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
 * Wear OS 风格的缩放滚动列表 —— 设置页等全屏列表用。
 *
 * 全屏铺满（不再套圆屏矩形安全区），条目越靠近屏幕中心越大、越靠边缘越小并淡出，
 * 靠缩放自然避开弧边。表冠增量驱动滚动。条目用 [scalingItem] 包装即可获得缩放效果。
 */
@Composable
fun ScalingScrollColumn(
    crownEvents: Flow<Float>,
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
            contentPadding = PaddingValues(vertical = 88.dp)
        ) {
            content()
        }
    }
}

/**
 * 缩放列表条目的包装：按自身在视口中的位置计算缩放与透明度。
 * 每帧按可见项几何中心计算，进出可见区不突变。
 */
@Composable
fun Modifier.scalingItem(key: String): Modifier {
    val state = LocalScalingState.current
    if (state == null) return this
    return this.graphicsLayer {
        // 显式读取滚动 SnapshotState，强制 draw 阶段每帧重算（否则缩放会停留在
        // 条目首次进入可见区时的值，出现"中间小、两边大"）。
        state.firstVisibleItemIndex
        state.firstVisibleItemScrollOffset

        val info = state.layoutInfo
        val anchor = info.viewportSize.height / 2f
        val mine = info.visibleItemsInfo.firstOrNull { it.key == key }
        if (mine != null && mine.size > 0 && anchor > 0f) {
            val itemCenter = mine.offset + mine.size / 2f
            val distance = kotlin.math.abs(itemCenter - anchor)
            val t = (distance / (mine.size * 1.4f)).coerceIn(0f, 1f)
            val eased = t * t
            val sc = 1.22f - 0.67f * eased   // 中心 1.22 → 边缘 0.55
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.66f * t
        }
    }
}
