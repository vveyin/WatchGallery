package com.watchgallery.app.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchgallery.app.data.Photo
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.FilterPreset
import com.watchgallery.app.edit.Stroke as DrawStroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/** 编辑工具 */
private enum class EditTool { NONE, FILTER, ADJUST, CROP, DRAW, ROTATE }

/** 调节参数顺序：亮度 / 对比度 / 饱和度 / 色温 */
private val AdjustLabels = listOf("亮度", "对比度", "饱和度", "色温")

/**
 * 抽屉只有两态：**不显示**（屏幕上只留底部一条白线）和**全屏铺开**。
 * 和设置抽屉一致，工具、选项、滑杆一次全摆出来，不用再一档一档往上拉。
 */

/**
 * 图片编辑页。
 *
 * 布局：
 *  - 图片占满整个屏幕（圆屏取内切圆内最大的等比例矩形），**不因抽屉展开而移动/缩小**；
 *  - 抽屉是盖在图片上的一层毛玻璃，只有两态：不显示 / 全屏。收起时底部留一条白线，
 *    从白线上滑即可全屏铺开；在面板上向下拖、或轻点面板顶部的把手即可收回；
 *  - 背景是「屏幕等价图」的模糊版：黑底 + 图片按它在屏幕上的实际位置画进去再糊，
 *    所以抽屉里的背景和上面的图片大小、位置完全对得上。
 */
@Composable
fun EditScreen(
    photo: Photo,
    viewModel: GalleryViewModel,
    isRound: Boolean,
    blurEnabled: Boolean = true,
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
    val scope = rememberCoroutineScope()

    // ── 抽屉：0 = 不显示，screenH = 全屏 ──
    // 位移用普通 Float 状态，不用 Animatable：拖动发生在 awaitEachGesture 的
    // 「受限挂起作用域」里，调不了 snapTo，只能每帧 launch 一个协程去设值，
    // 旧值后到就会把面板拉回去（抽搐）。同步赋值 + 一个可取消的 Job 跑吸附动画最稳。
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var panel by remember { mutableFloatStateOf(0f) }
    var panelJob by remember { mutableStateOf<Job?>(null) }

    fun settleTo(target: Float) {
        panelJob?.cancel()
        panelJob = scope.launch {
            animate(panel, target, animationSpec = tween(240, easing = FastOutSlowInEasing)) { v, _ ->
                panel = v
            }
        }
    }
    fun openPanel() = settleTo(screenH)
    fun closePanel() = settleTo(0f)

    // 抽屉背景 = 「屏幕等价图」的模糊版：黑底 + 图片按它在屏幕上真实的矩形画进去，再整体模糊。
    // 直接糊原图会按原比例铺满，和上层「圆内最大矩形」显示的图片大小对不上。具体合成在图片区里做。
    var bgImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

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

    fun selectTool(t: EditTool) {
        tool = if (tool == t) EditTool.NONE else t
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── 图片区：铺满整屏，抽屉只是盖在上面，不改变它的位置和大小 ──
        BoxWithConstraints(Modifier.fillMaxSize()) {
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

                // 用容器的实际像素合成背景（而不是 LocalConfiguration 的屏幕尺寸）：
                // 后者和真实布局差一点点，背景里的图片就会跟上面那张错开。
                // 拖滑杆时预览图连续变化，停 80ms 再算，避免算力全花在中间态上。
                LaunchedEffect(bmp, wpx, hpx, maxW, maxH) {
                    kotlinx.coroutines.delay(80)
                    bgImage = BlurBackdrop.blurScreenAsync(
                        bmp,
                        maxW.toInt().coerceAtLeast(1),
                        maxH.toInt().coerceAtLeast(1),
                        RectF(
                            (maxW - wpx) / 2f, (maxH - hpx) / 2f,
                            (maxW + wpx) / 2f, (maxH + hpx) / 2f
                        )
                    )
                }

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

        // ── 底部拉起热区 ──
        // 常驻组合：按 panel 值做条件渲染会让节点在手势中途被移出组合、pointerInput
        // 协程连带取消，手感是「拉一下就断」。只有那条白线跟着状态显示/隐藏。
        val showPullLine by remember { derivedStateOf { panel < 1f } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .pointerInput(Unit) {
                    val slop = 10.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 抽屉已经露出来了：这块归面板自己管，别重复拖
                        if (panel >= 1f) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.all { !it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        var dragged = false
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.changes.all { !it.pressed }) break
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            val dy = c.position.y - down.position.y
                            if (!dragged && dy < -slop) dragged = true
                            if (dragged) {
                                c.consume()
                                panelJob?.cancel()
                                panel = (-dy).coerceIn(0f, screenH)
                            }
                        }
                        // 这里只有 46dp 的行程，拖不到顶，上滑即视为「要打开」
                        if (dragged) openPanel()
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            if (showPullLine) {
                Box(
                    Modifier
                        .padding(bottom = 10.dp)
                        .width(44.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.55f))
                )
            }
        }

        // ── 毛玻璃：固定铺满整屏，只露出抽屉盖住的那一块（像素不随面板移动）──
        if (blurEnabled) {
            BlurBand(
                heightPx = { panel },
                image = bgImage,
                dim = 0.34f,
                cornerRadiusPx = with(density) { 22.dp.toPx() },
                // 模糊图还没做好时垫一块半透明黑，避免先透出原图再跳变
                fallback = Color.Black.copy(alpha = 0.55f)
            )
        }

        // ── 抽屉：整屏高，靠 translationY 控制露出多少；面板自身透明（玻璃板）──
        // 拖动交给 trackVerticalDrag：它只累加相邻两帧的位移差，与面板自身移动无关，
        // 所以「面板跟着手指跑」也不会把位移抵消掉。
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { screenH.toDp() })
                .graphicsLayer { translationY = screenH - panel }
                // 整块面板都能上下拖；轻点不消费，留给里面的按钮
                .pointerInput(Unit) {
                    val slop = 8.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = panel
                        // total 向下为正：面板露出量 panel 越大 = 越往上铺开，所以取负号
                        val drag = trackVerticalDrag(down, slop) { total ->
                            panelJob?.cancel()
                            panel = (start - total).coerceIn(0f, screenH)
                        }
                        if (drag.dragged) {
                            // 两态抽屉：往下拖够 1/4 屏、或者快速下甩，就收到「不显示」。
                            // 以前按半屏判定 —— 手表屏幕本来就小，起手位置又常在中间，
                            // 根本拖不到半屏，松手只能弹回全屏，看起来就是「放下又自动往上走」。
                            val flick = drag.velocity > 900f && drag.total > screenH * 0.06f
                            if (drag.total > screenH * 0.22f || flick) closePanel() else openPanel()
                        }
                    }
                },
            color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 把手贴着面板顶边：一眼看出这是抽屉的上沿（轻点收起）──
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { closePanel() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.55f))
                    )
                }

                Spacer(Modifier.weight(1f))

                // ── 工具行 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip("滤镜", tool == EditTool.FILTER, { selectTool(EditTool.FILTER) }, Icons.Filled.AutoAwesome)
                    Chip("调节", tool == EditTool.ADJUST, { selectTool(EditTool.ADJUST) }, Icons.Filled.Tune)
                    Chip("裁剪", tool == EditTool.CROP, {
                        selectTool(EditTool.CROP)
                        if (tool != EditTool.CROP) cropMode = false
                    }, Icons.Filled.Crop)
                    Chip("涂鸦", tool == EditTool.DRAW, { selectTool(EditTool.DRAW) }, Icons.Filled.Draw)
                    Chip("旋转", tool == EditTool.ROTATE, { selectTool(EditTool.ROTATE) }, Icons.Filled.RotateRight)
                }

                Spacer(Modifier.height(10.dp))

                // ── 当前工具的选项行 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (tool) {
                        EditTool.FILTER -> FilterPreset.entries.forEach { f ->
                            Chip(
                                label = f.label,
                                selected = adjustments.filter == f,
                                onClick = { adjustments = adjustments.copy(filter = f) }
                            )
                        }
                        EditTool.ADJUST -> {
                            Chip("", false, { tool = EditTool.NONE }, Icons.AutoMirrored.Filled.ArrowBack)
                            AdjustLabels.forEachIndexed { i, name ->
                                Chip(name, adjustParam == i, { adjustParam = i })
                            }
                        }
                        EditTool.CROP -> {
                            Chip(
                                label = if (cropMode) "选好了" else "拖出选区",
                                selected = cropMode,
                                onClick = { cropMode = !cropMode }
                            )
                            Chip("清除", false, { cropRect = null; cropMode = false })
                        }
                        EditTool.DRAW -> {
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
                                        .size(34.dp)
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
                        EditTool.ROTATE -> {
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

                // ── 调节滑杆（只在调节工具下出现） ──
                if (tool == EditTool.ADJUST) {
                    Spacer(Modifier.height(10.dp))
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
                        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactSlider(
                            value = value,
                            range = -100f..100f,
                            onChange = { setValue(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${value.toInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(30.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
            }
        }

        // ── 保存：浮在最上层，抽屉全屏铺开时也能点 ──
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 30.dp)
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

    }
}

/** 抽屉里的胶囊按钮（横向列表的单元）：44dp 高，给手指留足点击面积 */
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
            .height(44.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = if (label.isEmpty()) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = fg, modifier = Modifier.size(17.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

/** 只有图标的胶囊按钮（撤销 / 清空） */
@Composable
private fun IconChip(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 细滑杆：比 Material 的 Slider 矮很多，一行能塞进抽屉。
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
            .height(30.dp)
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
            val knobR = 7.dp.toPx()
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
