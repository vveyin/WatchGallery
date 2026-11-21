package com.watchgallery.app.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStrokeStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchgallery.app.data.Photo
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.FilterPreset
import com.watchgallery.app.edit.Stroke as DrawStroke
import com.watchgallery.app.edit.TextStamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 编辑工具 */
private enum class EditTool { NONE, FILTER, ADJUST, CROP, DRAW, TEXT, ROTATE }

/** 调节参数顺序：亮度 / 对比度 / 饱和度 / 色温 */
private val AdjustLabels = listOf("亮度", "对比度", "饱和度", "色温")

/** 读不到图片尺寸时的兜底放大上限（正常情况下按屏幕像素现算，见 [maxZoom]） */
private const val EDIT_MAX_SCALE = 6f

/**
 * 编辑页里的一个文本框（坐标、字号都是**原图像素**）。
 *
 * 预览时按 `尺寸 × 适配比例 × 当前缩放` 换算成屏幕像素，
 * 保存时 [ImageEditor.drawTexts] 用同一套坐标烘焙进图片。
 */
private data class TextBox(
    val id: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val sizePx: Float,
    val color: Int
)

/**
 * 图片编辑页。
 *
 * 布局：
 *  - 图片铺在黑色底上（圆屏取内切圆里最大的等比例矩形），**不因抽屉展开而移动/缩小**；
 *  - 抽屉两态：不显示（屏幕上只留底部一条白线）/ 全屏。**全收起时屏幕上只剩图片** ——
 *    保存、返回都搬到了抽屉里，用 Material 按钮；
 *  - 图片本身可以缩放：双指、表冠、以及设置里那个「缩放按键」；
 *    涂鸦 / 框选模式下单指照常画，只有出现第二根手指才切到缩放。
 */
@Composable
fun EditScreen(
    photo: Photo,
    viewModel: GalleryViewModel,
    isRound: Boolean,
    blurEnabled: Boolean = true,
    crownEvents: Flow<Float> = emptyFlow(),
    showZoomButtons: Boolean = true,
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
    var saving by remember { mutableStateOf(false) }

    // ── 文本框 ──
    var boxes by remember { mutableStateOf<List<TextBox>>(emptyList()) }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var nextBoxId by remember { mutableIntStateOf(1) }

    // ── 缩放 / 平移 ──
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // 预览图：调整 / 旋转 / 翻转实时应用。涂鸦、文本框不进 Bitmap（叠在 Canvas 上画），
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
    val cfg = LocalConfiguration.current
    val screenH = with(density) { cfg.screenHeightDp.dp.toPx() }
    val screenLongPx = with(density) { maxOf(cfg.screenWidthDp, cfg.screenHeightDp).dp.toPx() }

    /**
     * 编辑页的放大上限：同样按**屏幕像素**算 —— 原图长边 / 屏幕长边，再多 15% 余量。
     * 小图不会被拉到模糊，长图又能放到看清细节；不再是一个写死的倍数。
     */
    val maxZoom = remember(sourceBitmap, screenLongPx) {
        val b = sourceBitmap
        if (b == null || screenLongPx <= 0f) EDIT_MAX_SCALE
        else (maxOf(b.width, b.height) / screenLongPx * 1.15f).coerceIn(2f, 12f)
    }

    // ── 抽屉：0 = 不显示，1 = 全屏 ──
    val drawer = rememberDrawerState(false)
    DrawerSettle(drawer)
    val drawerOpen by remember { derivedStateOf { drawer.progress.floatValue > 0.5f } }

    // 表冠缩放：抽屉收起时才作用在图片上（抽屉展开时表冠留给面板，避免和按钮抢手感）
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            if (drawer.progress.floatValue > 0.002f) return@collect
            val z = (zoom + delta * 0.01f).coerceIn(1f, maxZoom)
            zoom = z
            if (z <= 1.0001f) {
                zoom = 1f
                pan = Offset.Zero
            }
        }
    }

    // 抽屉背景 = 「屏幕等价图」的模糊版：黑底 + 图片按它在屏幕上真实的矩形画进去，再整体模糊。
    var bgImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // 进入编辑页时抽屉是收起的：拍一张干净底图给夹层用
    val view = androidx.compose.ui.platform.LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    LaunchedEffect(Unit) {
        if (blurEnabled && window != null) {
            BlurBackdrop.setCovered(false)
            delay(700)
            BlurBackdrop.capture(window)
        }
    }

    // ── 进入 / 退出动画 ──
    // 0 = 缩到 0.92 倍且透明，1 = 正常。进入先播一遍，退出（返回 / 保存完成）反向播完再交还导航，
    // 这样进出编辑页都不会「啪」地硬切。
    val enterAnim = remember { Animatable(0f) }
    val animScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        enterAnim.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    fun closeAnimated() {
        animScope.launch {
            enterAnim.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            onBack()
        }
    }

    fun save() {
        val bmp = sourceBitmap ?: return
        saving = true
        val stamps = boxes.filter { it.text.isNotBlank() }.map {
            TextStamp(it.x, it.y, it.text, it.sizePx, it.color)
        }
        viewModel.saveEditedImage(
            bmp, adjustments, rotation, flipH, flipV,
            normalizeCrop(cropRect, bmp.width, bmp.height), strokes, stamps
        ) { ok ->
            saving = false
            if (ok) closeAnimated()
        }
    }

    fun selectTool(t: EditTool) {
        if (tool == t) {
            tool = EditTool.NONE
        } else {
            tool = t
            if (t != EditTool.CROP) cropMode = false
        }
        editingId = null
    }

    /** 本次编辑开始时的文字，用于「取消」还原 */
    var editOrigin by remember { mutableStateOf("") }

    /** 结束当前文本框的输入；内容为空就丢掉，不为空就保留并失焦 */
    fun blurBox() {
        val id = editingId ?: return
        if (boxes.firstOrNull { it.id == id }?.text?.isBlank() == true) {
            boxes = boxes.filterNot { it.id == id }
        }
        editingId = null
    }

    /** 取消这次输入：文字还原成进来时的样子，空文本仍会被丢弃 */
    fun cancelBoxEdit() {
        val id = editingId ?: return
        boxes = boxes.map { if (it.id == id) it.copy(text = editOrigin) else it }
        blurBox()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                val e = enterAnim.value
                val s = 0.92f + 0.08f * e
                scaleX = s
                scaleY = s
                alpha = e
            }
    ) {

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
                val sf = safeScale(bmpW, bmpH, wpx.toInt(), hpx.toInt())
                val viewCenter = Offset(maxW / 2f, maxH / 2f)

                // 用容器的实际像素合成背景：直接糊原图会按原比例铺满，
                // 和上层「圆内最大矩形」显示的图片大小对不上。
                LaunchedEffect(bmp, wpx, hpx, maxW, maxH) {
                    delay(80)
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

                // 屏幕坐标 → 原图像素：先扣掉平移、除以缩放，再除以「原图→控件」比例
                fun toBitmap(p: Offset): Offset {
                    val local = Offset(
                        wpx / 2f + (p.x - viewCenter.x - pan.x) / zoom,
                        hpx / 2f + (p.y - viewCenter.y - pan.y) / zoom
                    )
                    return Offset(local.x / sf, local.y / sf)
                }

                // ── 图片本身：整体缩放/平移，裁到屏幕内 ──
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(with(density) { wpx.toDp() }, with(density) { hpx.toDp() })
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            }
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
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
                                val l = cr.left * sf
                                val t = cr.top * sf
                                val r = cr.right * sf
                                val b = cr.bottom * sf
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
                                    style = DrawStrokeStyle(1.5.dp.toPx())
                                )
                            }
                        }

                        // 文本框不放在这里：它要盖在手势层之上才好点中，见下面单独的一层
                    }
                }

                // ── 手势层：单指（涂鸦/框选/平移/点按）+ 双指缩放 ──
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bmpW, bmpH, wpx, hpx, tool, cropMode, maxZoom) {
                            val slop = 6.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downAt = down.uptimeMillis
                                var lastPos = down.position
                                var travel = 0f
                                var pinch = false
                                var strokeStarted = false

                                while (true) {
                                    val e = awaitPointerEvent()
                                    val pressed = e.changes.count { it.pressed }
                                    if (pressed == 0) break

                                    if (pressed >= 2) {
                                        // 双指：缩放 + 平移。刚画了一半的笔画直接撤掉
                                        if (strokeStarted) {
                                            strokes = strokes.dropLast(1)
                                            strokeStarted = false
                                        }
                                        pinch = true
                                        val z = e.calculateZoom()
                                        if (z != 1f) {
                                            zoom = (zoom * z).coerceIn(1f, maxZoom)
                                        }
                                        val p = e.calculatePan()
                                        pan += p
                                        if (zoom <= 1.001f) {
                                            zoom = 1f
                                            pan = Offset.Zero
                                        } else {
                                            val mx = wpx * (zoom - 1f) / 2f
                                            val my = hpx * (zoom - 1f) / 2f
                                            pan = Offset(
                                                pan.x.coerceIn(-mx, mx),
                                                pan.y.coerceIn(-my, my)
                                            )
                                        }
                                        e.changes.forEach { it.consume() }
                                        continue
                                    }

                                    if (pinch) continue
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    val pos = c.position
                                    val delta = pos - lastPos
                                    travel += delta.getDistance()
                                    lastPos = pos

                                    if (travel > slop) {
                                        val bp = toBitmap(pos)
                                        when {
                                            cropMode -> {
                                                val x = bp.x.coerceIn(0f, bmpW.toFloat())
                                                val y = bp.y.coerceIn(0f, bmpH.toFloat())
                                                val r = cropRect
                                                cropRect = if (r == null) RectF(x, y, x, y)
                                                else RectF(
                                                    min(r.left, x), min(r.top, y),
                                                    maxOf(r.left, x), maxOf(r.top, y)
                                                )
                                            }
                                            tool == EditTool.DRAW -> {
                                                val x = bp.x.coerceIn(0f, bmpW.toFloat())
                                                val y = bp.y.coerceIn(0f, bmpH.toFloat())
                                                if (!strokeStarted) {
                                                    strokes = strokes + DrawStroke(
                                                        listOf(PointF(x, y)),
                                                        currentStrokeColor,
                                                        currentStrokeWidth
                                                    )
                                                    strokeStarted = true
                                                } else {
                                                    strokes = strokes.updateLast {
                                                        it.copy(points = it.points + PointF(x, y))
                                                    }
                                                }
                                            }
                                            zoom > 1.001f -> {
                                                // 放大后单指平移
                                                val mx = wpx * (zoom - 1f) / 2f
                                                val my = hpx * (zoom - 1f) / 2f
                                                pan = Offset(
                                                    (pan.x + delta.x).coerceIn(-mx, mx),
                                                    (pan.y + delta.y).coerceIn(-my, my)
                                                )
                                            }
                                        }
                                        c.consume()
                                    }
                                    if (!c.pressed) break
                                }

                                // 轻点：文本框模式下新建；其它情况先收掉正在输入的文本框
                                if (!pinch && travel < slop) {
                                    if (tool == EditTool.TEXT && editingId == null) {
                                        val bp = toBitmap(down.position)
                                        val x = bp.x.coerceIn(0f, bmpW.toFloat())
                                        val y = bp.y.coerceIn(0f, bmpH.toFloat())
                                        val size = (bmpH * 0.075f).coerceIn(18f, 120f)
                                        val id = nextBoxId++
                                        val lum = luminance(bmp, x, y, size)
                                        boxes = boxes + TextBox(
                                            id = id, x = x, y = y, text = "",
                                            sizePx = size,
                                            color = if (lum > 0.55f) android.graphics.Color.BLACK
                                            else android.graphics.Color.WHITE
                                        )
                                        editOrigin = ""
                                        editingId = id
                                    } else {
                                        blurBox()
                                    }
                                }
                            }
                        }
                )

                // ── 文本框层：跟图片同一套变换，但组合在**手势层之上**，否则点不中 ──
                // 这一层整体没有 pointerInput，空白处的事件会穿透到下面的手势层。
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(with(density) { wpx.toDp() }, with(density) { hpx.toDp() })
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            }
                    ) {
                        boxes.forEach { box ->
                            val focused = box.id == editingId
                            // 边框颜色跟着底下的图片亮度走：图亮用灰框、图暗用白框，都是半透明的
                            val lum = if (focused) luminance(bmp, box) else 0f
                            val borderColor = if (lum > 0.55f) Color(0x99A0A0A0) else Color(0xCCFFFFFF)
                            Box(
                                Modifier
                                    .offset {
                                        IntOffset(
                                            (box.x * sf).roundToInt(),
                                            (box.y * sf).roundToInt()
                                        )
                                    }
                                    .then(
                                        if (focused) Modifier
                                            .border(1.5.dp, borderColor, RoundedCornerShape(3.dp))
                                            .padding(2.dp)
                                        else Modifier.padding(2.dp)
                                    )
                                    .then(
                                        if (tool == EditTool.TEXT) Modifier.clickable {
                                            if (editingId != null && editingId != box.id) blurBox()
                                            editOrigin = box.text
                                            editingId = box.id
                                        } else Modifier
                                    )
                            ) {
                                Text(
                                    text = box.text.ifBlank { if (focused) "输入文字…" else " " },
                                    style = TextStyle(
                                        color = if (box.text.isBlank() && focused) {
                                            Color(0x88FFFFFF)
                                        } else {
                                            Color(box.color)
                                        },
                                        fontSize = with(density) { (box.sizePx * sf).toSp() },
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 6
                                )
                            }
                        }
                    }
                }

                if (showZoomButtons) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { zoom = (zoom + 0.3f).coerceAtMost(maxZoom) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomIn, "放大", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                zoom = (zoom - 0.3f).coerceAtLeast(1f)
                                if (zoom <= 1.001f) {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOut, "缩小", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // ── 底部拉起热区：常驻组合，只有白线跟着状态显隐 ──
        val showPullLine by remember { derivedStateOf { drawer.progress.floatValue < 0.002f } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        awaitDrawerDrag(
                            state = drawer,
                            down = down,
                            travelPx = screenH,
                            slopPx = 8.dp.toPx()
                        )
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

        // ── 毛玻璃：固定铺满整屏，只露出抽屉盖住的那一块 ──
        if (blurEnabled) {
            BlurBand(
                heightPx = { drawer.progress.floatValue * screenH },
                image = bgImage,
                dim = 0.34f,
                cornerRadiusPx = with(density) { 22.dp.toPx() },
                fallback = Color.Black.copy(alpha = 0.55f)
            )
        }

        // ── 抽屉：整屏高，靠 translationY 控制露出多少；面板自身透明（玻璃板）──
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { screenH.toDp() })
                .graphicsLayer { translationY = (1f - drawer.progress.floatValue) * screenH }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        awaitDrawerDrag(
                            state = drawer,
                            down = down,
                            travelPx = screenH,
                            slopPx = 8.dp.toPx()
                        )
                    }
                },
            color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 把手贴着面板顶边
                Box(
                    Modifier.fillMaxWidth().height(26.dp),
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

                // ── 工具行 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip("滤镜", tool == EditTool.FILTER, { selectTool(EditTool.FILTER) }, Icons.Filled.AutoAwesome)
                    Chip("调节", tool == EditTool.ADJUST, { selectTool(EditTool.ADJUST) }, Icons.Filled.Tune)
                    Chip("裁剪", tool == EditTool.CROP, { selectTool(EditTool.CROP) }, Icons.Filled.Crop)
                    Chip("涂鸦", tool == EditTool.DRAW, { selectTool(EditTool.DRAW) }, Icons.Filled.Draw)
                    Chip("文字", tool == EditTool.TEXT, { selectTool(EditTool.TEXT) }, Icons.Filled.TextFields)
                    Chip("旋转", tool == EditTool.ROTATE, { selectTool(EditTool.ROTATE) }, Icons.Filled.RotateRight)
                }

                Spacer(Modifier.height(6.dp))

                // ── 当前工具的选项行 ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                        .size(30.dp)
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
                        EditTool.TEXT -> {
                            Chip("点图片加文字", false, { editingId = null })
                            Chip("清空文字", false, {
                                boxes = emptyList(); editingId = null
                            })
                        }
                        EditTool.ROTATE -> {
                            // 旋转/翻转会改变坐标系：原来的裁剪框对新方向不再成立，直接清掉
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

                // ── 调节滑杆 ──
                if (tool == EditTool.ADJUST) {
                    Spacer(Modifier.height(6.dp))
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
                        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 4.dp),
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
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(28.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── 返回 / 保存：Material 按钮，收在抽屉里（收起抽屉屏幕上只剩图片）──
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { closeAnimated() },
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("返回", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = { if (!saving) save() },
                        enabled = !saving,
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("保存", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // ── 文本框输入条：只用系统输入法，界面上只有输入框 + 取消 / 确定 ──
        // 出现时**不主动调起输入法**：先让用户看清输入条和按钮，点一下输入框再弹键盘；
        // 输入法上的确认键也被禁用（ImeAction.None），只认这里的「确定」。
        val editing = boxes.firstOrNull { it.id == editingId }
        if (editing != null) {
            val keyboard = LocalSoftwareKeyboardController.current
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    WatchTextField(
                        value = editing.text,
                        onValueChange = { v ->
                            boxes = boxes.map { if (it.id == editing.id) it.copy(text = v) else it }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                keyboard?.hide()
                                cancelBoxEdit()
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("取消", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = {
                                keyboard?.hide()
                                blurBox()
                            },
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("确定", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 取样一小块区域的平均亮度（0 = 黑，1 = 白）。
 *
 * 文本框的边框靠它选色：图偏亮用灰框、偏暗用白框 —— 否则白框压在白雪上完全看不见。
 * 只取几十个点，主线程上算不会卡。
 */
private fun luminance(bmp: Bitmap, box: TextBox): Float =
    luminance(bmp, box.x, box.y, box.sizePx, box.text.length.coerceAtLeast(1))

private fun luminance(bmp: Bitmap, x: Float, y: Float, sizePx: Float, chars: Int = 1): Float {
    val w = (sizePx * 0.62f * chars).coerceAtLeast(sizePx).coerceAtMost(bmp.width.toFloat())
    val h = (sizePx * 1.3f).coerceAtMost(bmp.height.toFloat())
    var sum = 0f
    var n = 0
    for (i in 0..5) {
        for (j in 0..2) {
            val px = (x + w * i / 5f).toInt().coerceIn(0, bmp.width - 1)
            val py = (y + h * j / 2f).toInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sum += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            n++
        }
    }
    return if (n == 0) 0.5f else sum / n
}

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
            .height(40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = if (label.isEmpty()) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = fg, modifier = Modifier.size(15.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(5.dp))
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
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun TextButtonForTool(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * 细滑杆：比 Material 的 Slider 矮很多，一行能塞进抽屉。
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
            .height(28.dp)
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

/** 位图坐标 ↔ 控件坐标的比例 */
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
