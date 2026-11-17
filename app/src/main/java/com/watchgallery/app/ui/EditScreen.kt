package com.watchgallery.app.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.Photo
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.FilterPreset
import com.watchgallery.app.edit.Stroke as DrawStroke

/** 编辑工具类型 */
private enum class EditTool { NONE, FILTER, ADJUST, CROP, DRAW, ROTATE }

/**
 * 图片编辑页：对标手机相册，支持滤镜/色彩调节/裁剪/涂鸦/旋转翻转，并保存。
 * 手表上工具以底部横向图标条 + 可滚动面板呈现。
 */
@Composable
fun EditScreen(
    photo: Photo,
    viewModel: GalleryViewModel,
    isRound: Boolean,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photo.uri) {
        sourceBitmap = viewModel.decodeUri(photo.uri, maxSize = 1400)
    }

    var tool by remember { mutableStateOf(EditTool.NONE) }
    var adjustments by remember { mutableStateOf(EditAdjustments()) }
    var rotation by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf<RectF?>(null) }
    var cropMode by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<DrawStroke>>(emptyList()) }
    var currentStrokeColor by remember { mutableIntStateOf(android.graphics.Color.RED) }
    var currentStrokeWidth by remember { mutableFloatStateOf(12f) }
    var drawing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 预览图：把当前调整实时应用到 source（轻量预览）
    val preview by remember(sourceBitmap, adjustments, strokes, rotation, flipH, flipV) {
        derivedStateOf {
            val bmp = sourceBitmap ?: return@derivedStateOf null
            var p = com.watchgallery.app.edit.ImageEditor.applyAdjustments(bmp, adjustments)
            if (rotation != 0) p = com.watchgallery.app.edit.ImageEditor.rotate(p, rotation.toFloat())
            if (flipH) p = com.watchgallery.app.edit.ImageEditor.flip(p, true)
            if (flipV) p = com.watchgallery.app.edit.ImageEditor.flip(p, false)
            if (strokes.isNotEmpty()) p = com.watchgallery.app.edit.ImageEditor.drawStrokes(p, strokes)
            p
        }
    }

    RoundSafeArea(
        isRound = isRound,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        horizontal = if (isRound) 4.dp else 12.dp,
        vertical = if (isRound) 4.dp else 10.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "关闭") }
                Text("编辑", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground)
                IconButton(onClick = {
                    val bmp = sourceBitmap ?: return@IconButton
                    saving = true
                    viewModel.saveEditedImage(bmp, adjustments, rotation, flipH, flipV,
                        cropRect, strokes) { saving = false; if (it) onBack() }
                }, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Check, "保存", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 图片预览区
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                Alignment.Center
            ) {
                val bmp = preview
                if (bmp == null) {
                    CircularProgressIndicator()
                } else {
                    val displayW = bmp.width.toFloat()
                    val displayH = bmp.height.toFloat()
                    Box {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .pointerInput(tool, cropMode) {
                                    detectDragGestures(
                                        onDragStart = {
                                            if (tool == EditTool.DRAW && !cropMode) {
                                                val start = it / scaleFactor(displayW, displayH, size.width.toFloat(), size.height.toFloat())
                                                strokes = strokes + DrawStroke(
                                                    listOf(PointF(start.x, start.y)),
                                                    currentStrokeColor, currentStrokeWidth
                                                )
                                                drawing = true
                                            } else if (cropMode) {
                                                if (cropRect == null) cropRect = RectF(it.x, it.y, it.x, it.y)
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            if (tool == EditTool.DRAW && !cropMode && drawing) {
                                                val pt = change.position / scaleFactor(displayW, displayH, size.width.toFloat(), size.height.toFloat())
                                                strokes = strokes.updateLast { it.copy(points = it.points + PointF(pt.x, pt.y)) }
                                            } else if (cropMode) {
                                                cropRect?.let { r -> cropRect = RectF(r.left, r.top, change.position.x, change.position.y) }
                                            }
                                        },
                                        onDragEnd = { drawing = false }
                                    )
                                }
                        )
                        // 涂鸦层（叠加显示当前笔画）
                        if (tool == EditTool.DRAW) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sf = scaleFactor(displayW, displayH, size.width, size.height)
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
                        if (cropMode && cropRect != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sf = scaleFactor(displayW, displayH, size.width, size.height)
                                val r = cropRect!!
                                drawRect(
                                    color = Color.White.copy(alpha = 0.5f),
                                    topLeft = Offset(r.left * sf, r.top * sf),
                                    size = androidx.compose.ui.geometry.Size((r.right - r.left) * sf, (r.bottom - r.top) * sf),
                                    style = Stroke(3.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }

            // 工具面板
            when (tool) {
                EditTool.FILTER -> FilterPanel(adjustments.filter) { adjustments = adjustments.copy(filter = it) }
                EditTool.ADJUST -> AdjustPanel(adjustments) { adjustments = it }
                EditTool.DRAW -> DrawPanel(currentStrokeColor, currentStrokeWidth,
                    { currentStrokeColor = it }, { currentStrokeWidth = it },
                    { strokes = strokes.dropLast(1) }, { strokes = emptyList() })
                EditTool.CROP -> CropPanel(cropMode, cropRect) {
                    cropMode = it; if (!it) cropRect = null
                }
                EditTool.ROTATE -> RotatePanel(
                    onRotate = { rotation = (rotation + 90) % 360 },
                    onFlipH = { flipH = !flipH },
                    onFlipV = { flipV = !flipV },
                    onReset = { rotation = 0; flipH = false; flipV = false }
                )
                EditTool.NONE -> {}
            }

            // 底部工具栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton(Icons.Filled.AutoAwesome, "滤镜", tool == EditTool.FILTER) { tool = toggle(tool, EditTool.FILTER) }
                ToolButton(Icons.Filled.Tune, "调节", tool == EditTool.ADJUST) { tool = toggle(tool, EditTool.ADJUST) }
                ToolButton(Icons.Filled.Crop, "裁剪", tool == EditTool.CROP) { tool = toggle(tool, EditTool.CROP) }
                ToolButton(Icons.Filled.Draw, "涂鸦", tool == EditTool.DRAW) { tool = toggle(tool, EditTool.DRAW) }
                ToolButton(Icons.Filled.RotateRight, "旋转", tool == EditTool.ROTATE) { tool = toggle(tool, EditTool.ROTATE) }
            }
        }
    }
}

private fun toggle(current: EditTool, target: EditTool) = if (current == target) EditTool.NONE else target

private fun scaleFactor(bmpW: Float, bmpH: Float, viewW: Float, viewH: Float): Float =
    minOf(viewW / bmpW, viewH / bmpH)

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector,
                       label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilterPanel(current: FilterPreset, onSelect: (FilterPreset) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPreset.entries.forEach { f ->
            FilterChip(selected = current == f, onClick = { onSelect(f) },
                label = { Text(f.label, style = MaterialTheme.typography.labelMedium) })
        }
    }
}

@Composable
private fun AdjustPanel(adj: EditAdjustments, onChange: (EditAdjustments) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        SliderRow("亮度", adj.brightness, -100f, 100f) { onChange(adj.copy(brightness = it)) }
        SliderRow("对比度", adj.contrast, -100f, 100f) { onChange(adj.copy(contrast = it)) }
        SliderRow("饱和度", adj.saturation, -100f, 100f) { onChange(adj.copy(saturation = it)) }
        SliderRow("色温", adj.warmth, -100f, 100f) { onChange(adj.copy(warmth = it)) }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(48.dp))
        Slider(value = value, valueRange = min..max, onValueChange = onChange,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DrawPanel(color: Int, width: Float, onColor: (Int) -> Unit,
                      onWidth: (Float) -> Unit, onUndo: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(android.graphics.Color.RED, android.graphics.Color.YELLOW,
            android.graphics.Color.GREEN, android.graphics.Color.BLUE,
            android.graphics.Color.WHITE, android.graphics.Color.BLACK).forEach { c ->
            Box(modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(c))
                .border(if (color == c) 3.dp else 1.dp,
                    if (color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                    androidx.compose.foundation.shape.CircleShape)
                .clickable { onColor(c) })
        }
        IconButton(onClick = onUndo) { Icon(Icons.Filled.Undo, "撤销") }
        IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, "清空") }
    }
}

@Composable
private fun CropPanel(cropMode: Boolean, cropRect: RectF?, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        Button(onClick = { onToggle(!cropMode) }) {
            Text(if (cropMode) "完成裁剪" else "拖动选择区域")
        }
        Text(if (cropRect != null) "已选择区域" else "未选择",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RotatePanel(onRotate: () -> Unit, onFlipH: () -> Unit, onFlipV: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        SmallButton(Icons.Filled.RotateRight, "90°", onRotate)
        SmallButton(Icons.Filled.Flip, "水平", onFlipH)
        SmallButton(Icons.Filled.SwapHoriz, "垂直", onFlipV)
        SmallButton(Icons.Filled.RestartAlt, "重置", onReset)
    }
}

@Composable
private fun SmallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun <T> List<T>.updateLast(block: (T) -> T): List<T> =
    if (isEmpty()) this else dropLast(1) + block(last())
