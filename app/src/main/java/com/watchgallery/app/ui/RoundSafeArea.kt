package com.watchgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆屏安全区容器 —— 保证所有内容落在内切圆内，不被表盘裁切。
 *
 * 圆屏上：内容区宽度 = 短边 * 0.86，四周留出安全边距；
 * 方形屏上：左右再多留一点，避免贴边。
 */
@Composable
fun RoundSafeArea(
    isRound: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Dp = if (isRound) 22.dp else 16.dp,
    vertical: Dp = if (isRound) 20.dp else 12.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontal, vertical = vertical),
        contentAlignment = contentAlignment,
        content = content
    )
}
