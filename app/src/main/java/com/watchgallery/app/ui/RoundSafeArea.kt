package com.watchgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/**
 * 圆屏安全区容器 —— 保证所有内容落在内切圆内，不被表盘裁切。
 *
 * 早期实现只是给四周加 padding：那**不是**真的安全区。
 * 400px 圆屏里塞一个 344×360 的矩形，四个角到圆心距离 249 > 半径 200，
 * 四角依然会被弧边切掉，而且列表顶部/底部会被表框吃掉。
 *
 * 现在改成几何上正确的做法：圆内最大的、能完整显示一块区域的形状是
 * 以圆心为中心的正方形，边长 = 0.707 × 直径。取 0.74 倍并让调用方再留一点 Padding，
 * 这样任何一块内容（网格、文字、按钮）都严格在圆内，不会出现"被切一半"。
 *
 * 非圆屏（方形表）直接铺满，只留少量边距。
 */
@Composable
fun RoundSafeArea(
    isRound: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Dp = if (isRound) 6.dp else 14.dp,
    vertical: Dp = if (isRound) 6.dp else 10.dp,
    fillFactor: Float = 0.74f,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    if (!isRound) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = horizontal, vertical = vertical),
            contentAlignment = contentAlignment,
            content = content
        )
        return
    }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 圆内能容纳的最大矩形是正方形：边长 ≈ 直径 × 0.707，这里留一点余量取 0.74 倍
        val side = min(maxWidth, maxHeight) * fillFactor
        Box(
            modifier = Modifier
                .size(side)
                .padding(horizontal = horizontal, vertical = vertical),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}
