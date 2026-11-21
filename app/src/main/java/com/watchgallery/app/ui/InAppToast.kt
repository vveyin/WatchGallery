package com.watchgallery.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用内 toast —— 和 f-x 同一套观感：屏幕下方浮出一颗半透明黑药丸，白字居中，
 * 淡入 + 轻微上浮，1.5 秒后自己淡出。
 *
 * 为什么不用系统 Toast：手表屏幕是圆的，系统 Toast 固定贴着屏幕底部弹出，
 * 圆角那一刀会把文字切掉；自己画在安全区里才看得清。
 *
 * 传入 null 表示隐藏；退出动画期间保留最后一条文字，不会闪成空白。
 */
@Composable
fun InAppToast(
    message: String?,
    modifier: Modifier = Modifier
) {
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message != null) shown = message }

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn(tween(140)) +
                slideInVertically(tween(200)) { it / 2 } +
                scaleIn(tween(200), initialScale = 0.92f),
        exit = fadeOut(tween(240)) +
                slideOutVertically(tween(200)) { it / 3 } +
                scaleOut(tween(200), targetScale = 0.94f)
    ) {
        Box(
            Modifier
                .background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                shown,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}
