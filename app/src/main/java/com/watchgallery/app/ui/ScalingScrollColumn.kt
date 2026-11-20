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
        val info = state.layoutInfo
        val anchor = info.viewportSize.height / 2f
        val mine = info.visibleItemsInfo.firstOrNull { it.key == key }
        if (mine != null && anchor > 0f) {
            val center = mine.offset + mine.size / 2f
            val t = (kotlin.math.abs(center - anchor) / anchor).coerceIn(0f, 1f)
            val sc = 1.08f - 0.34f * t   // 中心 1.08 → 边缘 0.74
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.62f * t
        }
    }
}
