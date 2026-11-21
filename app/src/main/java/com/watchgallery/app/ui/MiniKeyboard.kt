package com.watchgallery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 四行字母（大写显示，原样输出） */
private val KEY_ROWS = listOf(
    "QWERTYU",
    "IOPASDF",
    "GHJKLZX",
    "CVBNM"
)

/**
 * 手表上的迷你字母键盘。
 *
 * 手表上没有可靠的系统输入法（不少 ROM 干脆没装），所以重命名和文本框都自带一个：
 * 26 个字母 + 空格 / 退格，够改文件名和写一两句话。按键铺满宽度（weight），
 * 圆屏方屏都不用手算尺寸。
 */
@Composable
fun MiniKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        KEY_ROWS.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.forEach { c ->
                    KeyBox(
                        label = c.toString(),
                        modifier = Modifier.weight(1f)
                    ) { onChar(c) }
                }
                // 短行补空位，保证每行键宽一致（不会因为少两个字母而拉宽）
                repeat(7 - row.length) { Box(Modifier.weight(1f)) }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            KeyBox("空格", Modifier.weight(2f)) { onChar(' ') }
            KeyBox("⌫", Modifier.weight(1f)) { onBackspace() }
        }
    }
}

@Composable
private fun KeyBox(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(24.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = if (label.length > 1) 9.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
