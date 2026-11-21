package com.watchgallery.app.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.Photo
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.FilterPreset
import com.watchgallery.app.edit.Stroke as DrawStroke
import kotlin.math.min
import kotlin.math.sqrt

/** 编辑工具 */
private enum class EditTool { NONE, FILTER, ADJUST, CROP, DRAW, ROTATE }

/** 调节参数顺序：亮度 / 对比度 / 饱和度 / 色温 */
private val AdjustLabels = listOf("亮度", "对比度", "饱和度", "色温")

/** 抽屉基础高度（把手 + 一行按钮） */
private val PanelBaseHeight = 58.dp

/** 调节模式：多一行滑杆 */
private val PanelSliderHeight = 86.dp

/**
 * 图片编辑页 —— 重做版：图片占满可用空间，所有功能收进底部抽屉。
 *
 * 1. 图片显示到最大：按「圆内能容纳的、与图片同比例的最大矩形」计算尺寸，
 *    不再被顶栏和常驻工具面板挤压；非圆屏则整屏 fit。
 * 2. 功能抽屉：底部抽屉里是一条**横向列表**，左右滑动选择功能（滤镜 / 调节 / 裁剪 / 涂鸦 / 旋转）。
 *    选中「调节」后，同一条列表换成四个参数，下方多一行细滑杆。
 * 3. 抽屉打开时图片区自动上移收窄，永远不被抽屉盖住。
 */
@Composable
fun EditScreen(
    photo: Photo,
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit
) {
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo.uri) {
        sourceBitmap = viewModel.decodeUri(photo.uri, maxSize = 1400)
    }

    var tool by remember { mutableStateOf(EditTool.NONE) }
    var adjustParam by remember { mutableIntStateOf(0) }
    var adjustments by remember { mutableStateOf(EditAdjustments()) }
    var rotation by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf<RectF?>(null) }
    var cropMode by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var currentStrokeColor by remember { mutableIntStateOf(android.graphics.Color.RED) }
    var currentStrokeWidth by remember { mutableFloatStateOf(10f) }
    var drawing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }

    // 预览图：调整 / 旋转 / 翻转实时应用。涂鸦不进 Bitmap（叠在 Canvas 上画），
    // 否则每画一个点都要把 1400px 的图重新算一遍，手表上会明显卡手。
    val preview by remember(sourceBitmap, adjustments, rotation, flipH, flipV) {
        derivedStateOf {
            val bmp = sourceBitmap ?: return@derivedStateOf null
            var p = com.watchgallery.app.edit.ImageEditor.applyAdjustments(bmp, adjustments)
            if (rotation != 0) p = com.watchgallery.app.edit.ImageEditor.rotate(p, rotation.toFloat())
            if (flipH) p = com.watchgallery.app.edit.ImageEditor.flip(p, true)
            if (flipV) p = com.watchgallery.app.edit.ImageEditor.flip(p, false)
            p
        }
    }

    val density = LocalDensity.current
    val panelHeight = if (tool == EditTool.ADJUST) PanelSliderHeight else PanelBaseHeight
    val panelPx = with(density) { panelHeight.toPx() }

    // 抽屉升降：translationY 0 = 展开，panelPx = 完全收在屏幕下沿之外
    val panelShift by animateFloatAsState(
        targetValue = if (drawerOpen) 0f else panelPx,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "editPanelShift"
    )
    // 图片区底部让位：抽屉展开时图片整体上移并缩到剩余空间里
    val imageInset by animateDpAsState(
        targetValue = if (drawerOpen) panelHeight else 0.dp,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "editImageInset"
    )

    fun save() {
        val bmp = sourceBitmap ?: return
        saving = true
        viewModel.saveEditedImage(
            bmp, adjustments, rotation, flipH, flipV,
            normalizeCrop(cropRect, bmp.width, bmp.height), strokes
        ) { ok ->
            saving = false
            if (ok) onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── 图片区 ──
        BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = imageInset)) {
            val bmp = preview
            if (bmp == null) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                val maxW = with(density) { maxWidth.toPx() }
                val maxH = with(density) { maxHeight.toPx() }
                val (wpx, hpx) = fitLargest(isRound, maxW, maxH, bmp.width, bmp.height)
                val bmpW = bmp.width
                val bmpH = bmp.height

                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(with(density) { wpx.toDp() }, with(density) { hpx.toDp() })
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .pointerInput(tool, cropMode, bmpW, bmpH) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    val sf = safeScale(bmpW, bmpH, size.width, size.height)
                                    if (cropMode) {
                                        // 裁剪框存**原图坐标**：与保存时的 crop() 同一坐标系
                                        val x = (p.x / sf).coerceIn(0f, bmpW.toFloat())
                                        val y = (p.y / sf).coerceIn(0f, bmpH.toFloat())
                                        cropRect = RectF(x, y, x, y)
                                    } else if (tool == EditTool.DRAW) {
                                        strokes = strokes + DrawStroke(
                                            listOf(PointF(p.x / sf, p.y / sf)),
                                            currentStrokeColor,
                                            currentStrokeWidth
                                        )
                                        drawing = true
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val sf = safeScale(bmpW, bmpH, size.width, size.height)
                                    if (cropMode) {
                                        val x = (change.position.x / sf).coerceIn(0f, bmpW.toFloat())
                                        val y = (change.position.y / sf).coerceIn(0f, bmpH.toFloat())
                                        cropRect?.let { r ->
                                            cropRect = RectF(
                                                min(r.left, x), min(r.top, y),
                                                maxOf(r.left, x), maxOf(r.top, y)
                                            )
                                        }
                                    } else if (tool == EditTool.DRAW && drawing) {
                                        val pt = PointF(change.position.x / sf, change.position.y / sf)
                                        strokes = strokes.updateLast { it.copy(points = it.points + pt) }
                                    }
                                },
                                onDragEnd = {
                                    drawing = false
                                    if (cropMode) cropRect = normalizeCrop(cropRect, bmpW, bmpH)?.takeIf {
                                        it.width() > 4f && it.height() > 4f
                                    }
                                }
                            )
                        }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 涂鸦层：始终叠加显示（不烘焙进预览图）
                    if (strokes.isNotEmpty()) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sf = safeScale(bmpW, bmpH, size.width.toInt(), size.height.toInt())
                            strokes.forEach { s ->
                                for (i in 1 until s.points.size) {
                                    val a = Offset(s.points[i - 1].x * sf, s.points[i - 1].y * sf)
                                    val b = Offset(s.points[i].x * sf, s.points[i].y * sf)
                                    drawLine(Color(s.color), a, b, strokeWidth = s.width * sf)
                                }
                            }
                        }
                    }

                    // 裁剪框
                    val cr = cropRect
                    if (cropMode && cr != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            val sf = safeScale(bmpW, bmpH, size.width.toInt(), size.height.toInt())
                            val l = cr.left * sf
                            val t = cr.top * sf
                            val r = cr.right * sf
                            val b = cr.bottom * sf
                            // 框外压暗，框内保持原样
                            drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, size.copy(height = t.coerceAtLeast(0f)))
                            drawRect(Color.Black.copy(alpha = 0.45f), Offset(0f, b), size.copy(height = (size.height - b).coerceAtLeast(0f)))
                            drawRect(
                                Color.Black.copy(alpha = 0.45f),
                                Offset(0f, t),
                                size.copy(width = l.coerceAtLeast(0f), height = (b - t).coerceAtLeast(0f))
                            )
                            drawRect(
                                Color.Black.copy(alpha = 0.45f),
                                Offset(r, t),
                                size.copy(width = (size.width - r).coerceAtLeast(0f), height = (b - t).coerceAtLeast(0f))
                            )
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(l, t),
                                size = androidx.compose.ui.geometry.Size(
                                    (r - l).coerceAtLeast(0f),
                                    (b - t).coerceAtLeast(0f)
                                ),
                                style = Stroke(1.5.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        // ── 保存 ──
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            IconButton(onClick = { if (!saving) save() }, enabled = !saving, modifier = Modifier.fillMaxSize()) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.Check, "保存", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── 收起时的把手：上滑或点击都能拉开抽屉 ──
        if (!drawerOpen) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var total = 0f
                            var opened = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.all { !it.pressed }) break
                                val c = event.changes.first()
                                total += c.position.y - c.previousPosition.y
                                if (total < -8f) {
                                    c.consume()
                                    opened = true
                                }
                            }
                            // 轻轻一点（没有明显滑动）也算打开：手表屏幕小，别让人反复试
                            if (opened || total > -8f) drawerOpen = true
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier
                        .padding(bottom = 9.dp)
                        .width(40.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }
        }

        // ── 抽屉面板 ──
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelHeight)
                .graphicsLayer { translationY = panelShift },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // 把手：下滑收起
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var total = 0f
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.all { !it.pressed }) break
                                    val c = event.changes.first()
                                    total += c.position.y - c.previousPosition.y
                                    if (total > 6f) c.consume()
                                }
                                if (total > 6f) drawerOpen = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    )
                }

                if (tool == EditTool.ADJUST) {
                    // 调节：横向列表换成四个参数，加一个返回工具列表的箭头
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Chip(
                            label = "",
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            selected = false,
                            onClick = { tool = EditTool.NONE }
                        )
                        AdjustLabels.forEachIndexed { i, name ->
                            Chip(
                                label = name,
                                selected = adjustParam == i,
                                onClick = { adjustParam = i }
                            )
                        }
                    }

                    val value = when (adjustParam) {
                        0 -> adjustments.brightness
                        1 -> adjustments.contrast
                        2 -> adjustments.saturation
                        else -> adjustments.warmth
                    }
                    fun setValue(v: Float) {
                        adjustments = when (adjustParam) {
                            0 -> adjustments.copy(brightness = v)
                            1 -> adjustments.copy(contrast = v)
                            2 -> adjustments.copy(saturation = v)
                            else -> adjustments.copy(warmth = v)
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactSlider(
                            value = value,
                            range = -100f..100f,
                            onChange = { setValue(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${value.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(28.dp)
                        )
                    }
                } else {
                    // 工具列表：横向一条，左右滑动选择
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Chip("滤镜", tool == EditTool.FILTER,
                            onClick = { tool = toggle(tool, EditTool.FILTER) }, icon = Icons.Filled.AutoAwesome)
                        Chip("调节", tool == EditTool.ADJUST,
                            onClick = { tool = EditTool.ADJUST }, icon = Icons.Filled.Tune)
                        Chip("裁剪", tool == EditTool.CROP,
                            onClick = { tool = toggle(tool, EditTool.CROP); if (tool != EditTool.CROP) cropMode = false },
                            icon = Icons.Filled.Crop)
                        Chip("涂鸦", tool == EditTool.DRAW,
                            onClick = { tool = toggle(tool, EditTool.DRAW) }, icon = Icons.Filled.Draw)
                        Chip("旋转", tool == EditTool.ROTATE,
                            onClick = { tool = toggle(tool, EditTool.ROTATE) }, icon = Icons.Filled.RotateRight)
                    }

                    // 当前工具的控件，同样是一条横向列表
                    when (tool) {
                        EditTool.FILTER -> Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 8.dp, end = 8.dp, top = 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterPreset.entries.forEach { f ->
                                Chip(
                                    label = f.label,
                                    selected = adjustments.filter == f,
                                    onClick = { adjustments = adjustments.copy(filter = f) }
                                )
                            }
                        }
                        EditTool.CROP -> Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Chip(
                                label = if (cropMode) "选好了" else "拖出选区",
                                selected = cropMode,
                                onClick = { cropMode = !cropMode }
                            )
                            Chip("清除", selected = false, onClick = { cropRect = null; cropMode = false })
                        }
                        EditTool.DRAW -> Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                android.graphics.Color.RED,
                                android.graphics.Color.YELLOW,
                                android.graphics.Color.GREEN,
                                android.graphics.Color.BLUE,
                                android.graphics.Color.WHITE,
                                android.graphics.Color.BLACK
                            ).forEach { c ->
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .border(
                                            if (currentStrokeColor == c) 2.dp else 1.dp,
                                            if (currentStrokeColor == c) MaterialTheme.colorScheme.primary
                                            else Color.Gray,
                                            CircleShape
                                        )
                                        .clickable { currentStrokeColor = c }
                                )
                            }
                            IconChip(Icons.Filled.Undo) { strokes = strokes.dropLast(1) }
                            IconChip(Icons.Filled.Delete) { strokes = emptyList() }
                        }
                        EditTool.ROTATE -> Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 旋转/翻转会改变坐标系：原来的裁剪框对新方向不再成立，直接清掉，
                            // 避免「先裁剪再旋转」保存时裁到错的地方。
                            Chip("90°", false, {
                                rotation = (rotation + 90) % 360; cropRect = null; cropMode = false
                            }, Icons.Filled.RotateRight)
                            Chip("水平", flipH, {
                                flipH = !flipH; cropRect = null; cropMode = false
                            }, Icons.Filled.Flip)
                            Chip("垂直", flipV, {
                                flipV = !flipV; cropRect = null; cropMode = false
                            }, Icons.Filled.SwapHoriz)
                            Chip("重置", false, {
                                rotation = 0; flipH = false; flipV = false
                                cropRect = null; cropMode = false
                            }, Icons.Filled.RestartAlt)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** 工具互斥切换：再点一次同一个工具 = 收起它 */
private fun toggle(current: EditTool, target: EditTool) =
    if (current == target) EditTool.NONE else target

/** 抽屉里的胶囊按钮（横向列表的单元） */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = if (label.isEmpty()) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = fg, modifier = Modifier.size(14.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(4.dp))
        }
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
        }
    }
}

/** 只有图标的胶囊按钮（撤销 / 清空） */
@Composable
private fun IconChip(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
    }
}

/**
 * 细滑杆：比 Material 的 Slider 矮很多（22dp），一条能塞进抽屉那一行。
 * 按下即定位、拖动即跟手，没有额外的触摸延迟。
 */
@Composable
private fun CompactSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    fun at(x: Float, w: Int): Float {
        if (w <= 0) return value
        val t = (x / w).coerceIn(0f, 1f)
        return range.start + (range.endInclusive - range.start) * t
    }

    Box(
        modifier = modifier
            .height(22.dp)
            .pointerInput(range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onChange(at(down.position.x, size.width))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                        onChange(at(c.position.x, size.width))
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = 4.dp.toPx()
            val knobR = 6.dp.toPx()
            val usable = (size.width - knobR * 2).coerceAtLeast(1f)
            val t = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(knobR, cy),
                end = Offset(size.width - knobR, cy),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF9FB4FF),
                start = Offset(knobR, cy),
                end = Offset(knobR + usable * t, cy),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawCircle(
                color = Color.White,
                radius = knobR,
                center = Offset(knobR + usable * t, cy)
            )
        }
    }
}

/**
 * 让图片「尽量大、但完整」：
 *  - 圆屏：取圆内能放下的、与图片同比例的最大矩形（两边都贴着圆弧）；
 *  - 方屏：整屏 fit。
 */
private fun fitLargest(
    isRound: Boolean,
    maxW: Float,
    maxH: Float,
    bmpW: Int,
    bmpH: Int
): Pair<Float, Float> {
    if (bmpW <= 0 || bmpH <= 0 || maxW <= 0f || maxH <= 0f) return maxW to maxH
    val a = bmpW.toFloat() / bmpH.toFloat()
    if (!isRound) {
        val s = minOf(maxW / bmpW, maxH / bmpH)
        return bmpW * s to bmpH * s
    }
    // 内切圆半径（留 1% 余量，避免抗锯齿边缘被表框吃掉）
    val r = minOf(maxW, maxH) / 2f * 0.99f
    var h = 2f * r / sqrt(a * a + 1f)
    var w = h * a
    if (w > maxW || h > maxH) {
        val s = minOf(maxW / w, maxH / h)
        w *= s
        h *= s
    }
    return w to h
}

/** 位图坐标 ↔ 控件坐标的比例（Fit 之后两个方向必然一致，取较小值容错） */
private fun safeScale(bmpW: Int, bmpH: Int, viewW: Int, viewH: Int): Float {
    if (bmpW <= 0 || bmpH <= 0 || viewW <= 0 || viewH <= 0) return 1f
    return min(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH).coerceAtLeast(0.0001f)
}

/** 把裁剪框规整到图片范围内，并保证 left<right / top<bottom */
private fun normalizeCrop(r: RectF?, bmpW: Int, bmpH: Int): RectF? {
    if (r == null) return null
    val l = min(r.left, r.right).coerceIn(0f, bmpW.toFloat())
    val t = min(r.top, r.bottom).coerceIn(0f, bmpH.toFloat())
    val rr = maxOf(r.left, r.right).coerceIn(0f, bmpW.toFloat())
    val bb = maxOf(r.top, r.bottom).coerceIn(0f, bmpH.toFloat())
    if (rr - l < 2f || bb - t < 2f) return null
    return RectF(l, t, rr, bb)
}

private fun <T> List<T>.updateLast(block: (T) -> T): List<T> =
    if (isEmpty()) this else dropLast(1) + block(last())
