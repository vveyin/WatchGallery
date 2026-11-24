package com.watchgallery.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 手表上的弹窗容器。
 *
 * 圆屏里能完整显示的最大矩形是「内切圆里的正方形」，边长 ≈ 0.707 × 直径 ——
 * 和编辑页把图片摆在圆内最大矩形里是同一套几何。因此这里直接把面板做成
 * `0.70 × min(宽, 高)` 的正方形：不管表盘多小、系统字体多大，四角都不会被表框切掉。
 *
 * 标题与按钮固定在上下两端，只有中间内容滚动 —— 内容再多也不会把「取消/确定」顶出屏幕。
 * 弹出系统输入法时外层加 `imePadding()`，整块面板跟着缩小、始终留在键盘上方。
 */
@Composable
fun WatchDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    cancelLabel: String? = null,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
    onConfirm: (() -> Unit)? = null,
    /** 内容区滚动状态：需要表冠滚动时由调用方传进来，不传就自己建一个 */
    scrollState: ScrollState? = null,
    body: @Composable ColumnScope.() -> Unit
) {
    // 出现动画：面板从 0.82 倍放大 + 淡入，避免命名框硬弹出。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.Center
        ) {
            // 内切圆内最大正方形（留一点余量，避免贴到弧边）
            val side = min(maxWidth, maxHeight) * 0.70f
            val ownScroll = rememberScrollState()
            val bodyScroll = scrollState ?: ownScroll
            val scale = 0.82f + 0.18f * enter.value
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(side).graphicsLayer {
                    alpha = enter.value
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(bodyScroll),
                        content = body
                    )
                    if (cancelLabel != null || confirmLabel != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (cancelLabel != null) {
                                DialogAction(cancelLabel, false, Modifier.weight(1f)) {
                                    (onCancel ?: onDismiss)()
                                }
                            }
                            if (confirmLabel != null) {
                                DialogAction(
                                    confirmLabel, true, Modifier.weight(1f),
                                    enabled = confirmEnabled
                                ) { onConfirm?.invoke() ?: onDismiss() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 弹窗底部按钮：确定用实心、取消用描边，高度压到手表能按的大小 */
@Composable
private fun RowScope.DialogAction(
    label: String,
    primary: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val padding = PaddingValues(horizontal = 6.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 34.dp),
            contentPadding = padding
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 34.dp),
            contentPadding = padding
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** 弹窗里的一项「标签 + 值」 */
@Composable
fun DialogLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 弹窗 / 编辑页共用的单行输入框：**交给系统输入法**，不自绘键盘 */
@Composable
fun WatchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        // ImeAction.None：输入法右下角那枚键**不再直接确认/提交**，
        // 只负责换行（单行时什么都不做）。确认只走界面上的按钮，避免误触把半截内容提交掉。
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    )
}
