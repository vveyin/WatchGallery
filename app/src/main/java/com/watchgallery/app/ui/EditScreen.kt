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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStrokeStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
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

/** 文本框可选的文字颜色（ARGB）：白 / 黑 / 红 / 橙 / 黄 / 绿 / 蓝 / 紫 */
private val TextColors = listOf<Int>(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
    0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt()
)

/** 框线与两侧圆点可选的颜色（ARGB）：白 / 淡灰 —— 框内部透明，靠框线勾出文字区域 */
private val BoxBgColors = listOf<Int>(
    0xFFFFFFFF.toInt(), 0xFFD9D9D9.toInt()
)
private val BoxBgLabels = listOf("白", "淡灰")

/** 读不到图片尺寸时的兜底放大上限（正常情况下按屏幕像素现算，见 [maxZoom]），原 6f 的两倍 */
private const val EDIT_MAX_SCALE = 12f

/**
 * 编辑页里的一个文本框（坐标、字号都是**原图像素**）。
 *
 * 预览时按 `尺寸 × 适配比例 × 当前缩放` 换算成屏幕像素，
 * 保存时 [ImageEditor.drawTexts] 用同一套坐标烘焙进图片。
 */
private data class TextBox(
    val id: Int,
    /** 左上角（原图像素） */
    val x: Float,
    val y: Float,
    val text: String,
    /** 字号（原图像素） */
    val sizePx: Float,
    val color: Int,
    /** 框宽（原图像素）：决定一行排多少字，超出自动换行；左右手柄拖动可改 */
    val widthPx: Float,
    /** 框线与两侧圆点的颜色（ARGB）：白 / 淡灰；框内部透明，只有文字可见 */
    val bgColor: Int = 0xFFFFFFFF.toInt()
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
    /** 只选中（显示边框）但不进入输入的框：单击选中，双击才进输入抽屉 */
    var selectedBoxId by remember { mutableStateOf<Int?>(null) }
    var nextBoxId by remember { mutableIntStateOf(1) }
    /** 双击手势记录：上一次轻点的时间与框 id（用于区分单击选中 / 双击进输入） */
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapBoxId by remember { mutableIntStateOf(-1) }

    // 文本框输入抽屉：进入输入时弹起，确定/取消/拉下时收起
    val textDrawer = rememberDrawerState(false)

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
     * 编辑页的放大上限：同样按**屏幕像素**算 —— 原图长边 / 屏幕长边 × 4.6
     * （1:1 的 4.6 倍余量，即原 2.3 倍的两倍）。
     * 小图不会被拉到模糊，长图又能放到看清细节；不再是一个写死的倍数。
     */
    val maxZoom = remember(sourceBitmap, screenLongPx) {
        val b = sourceBitmap
        if (b == null || screenLongPx <= 0f) EDIT_MAX_SCALE
        else (maxOf(b.width, b.height) / screenLongPx * 4.6f).coerceIn(4f, 48f)
    }

    // ── 抽屉：0 = 不显示，1 = 全屏 ──
    val drawer = rememberDrawerState(false)
    DrawerSettle(drawer)
    val drawerOpen by remember { derivedStateOf { drawer.progress.floatValue > 0.5f } }

    // 抽屉背景 = 「屏幕等价图」的模糊版：黑底 + 图片按它在屏幕上真实的矩形画进去，再整体模糊。
    var bgImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // 进入编辑页时抽屉是收起的：拍一张干净底图给夹层用
    val view = androidx.compose.ui.platform.LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    LaunchedEffect(Unit) {
        if (blurEnabled && window != null) {
            BlurBackdrop.setCovered(false, BlurBackdrop.Channel.VIEWER)
            delay(700)
            BlurBackdrop.capture(window, BlurBackdrop.Channel.VIEWER)
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
            TextStamp(it.x, it.y, it.text, it.sizePx, it.color, it.widthPx, it.bgColor)
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
        selectedBoxId = null
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

    // ── 文本框输入抽屉的联动 ──
    val keyboard = LocalSoftwareKeyboardController.current
    DrawerSettle(textDrawer) { open ->
        // 抽屉拉下去只是暂时收起：编辑没有结束，文本框保持选中，
        // 从底部白线还能把它再拉上来继续改；只有点「确定」才算编辑完成。
        if (!open) keyboard?.hide()
    }
    LaunchedEffect(editingId) {
        if (editingId != null) textDrawer.open() else textDrawer.close()
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

                /**
                 * 平移边界：按**图片自身尺寸**算（不是整块屏幕），再各放屏幕短边 10% 的余量。
                 *
                 * 图片边缘贴到屏幕边之后还能再推出去一点，推到余量上限就硬停 ——
                 * 既满足「放大后可以挪到屏幕外一点」，又不会一滑把图推出屏幕再也找不回来。
                 */
                fun clampPan(p: Offset): Offset {
                    val slack = minOf(maxW, maxH) * 0.10f
                    val mx = wpx * (zoom - 1f) / 2f + slack
                    val my = hpx * (zoom - 1f) / 2f + slack
                    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
                }

                /**
                 * 以**屏幕中心当前对应的那个图片像素**为锚点缩放。
                 *
                 * 屏幕上的点 = 视图中心 + (图片坐标 − 图片中心) × zoom + pan；
                 * 要让屏幕中心对应的图片点不动，zoom 变成 k 倍时 pan 也要同比例变成 k 倍。
                 * 只改 zoom 不动 pan 的话，平移过之后再放大，屏幕中心看到的就是图片上另一个位置。
                 */
                fun setZoom(target: Float) {
                    val z = target.coerceIn(1f, maxZoom)
                    if (z == zoom) return
                    if (z <= 1.0001f) {
                        zoom = 1f
                        pan = Offset.Zero
                        return
                    }
                    pan *= z / zoom
                    zoom = z
                    pan = clampPan(pan)
                }

                // 表冠缩放方向反转开关：rememberUpdatedState 保证切换后立即生效，不重启事件订阅
                val zoomInvert by viewModel.zoomInvertCrown.collectAsState()
                val zoomInvertRef = rememberUpdatedState(zoomInvert)

                // 表冠缩放：抽屉收起时才作用在图片上（抽屉展开时表冠留给面板，避免和按钮抢手感）
                LaunchedEffect(crownEvents) {
                    crownEvents.collect { delta ->
                        if (drawer.progress.floatValue > 0.002f) return@collect
                        if (textDrawer.progress.floatValue > 0.002f) return@collect
                        val sign = if (zoomInvertRef.value) -1f else 1f
                        setZoom(zoom + delta * 0.01f * sign)
                    }
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
                                // 图片框居中对齐，图层中心就是屏幕中心：
                                // 放大时以屏幕中心为锚点，而不是跟着图片自己中心跑
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
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

                        // 涂鸦层已移到不裁剪的变换层（见下方文本框层），这样画到图片外的笔画也能显示

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
                                // 起手点落在了文本框上：这次按下整个交给文本框层
                                // （拖动移动它、单击选中、双击进入输入），手势层什么都不做
                                val onBox = tool == EditTool.TEXT && run {
                                    val bp = toBitmap(down.position)
                                    boxes.any { b ->
                                        // 中间态只把当前编辑框当作「框」，其它框照常走图片手势
                                        (editingId == null || editingId == b.id) && run {
                                            // 高度随文字自适应：按折行数估算内容高度
                                            val h = estimateBoxHeight(b)
                                            bp.x >= b.x && bp.x <= b.x + b.widthPx &&
                                                bp.y >= b.y && bp.y <= b.y + h
                                        }
                                    }
                                }

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
                                        // 起手点在文本框上：双指交给文本框层缩放它自己，图片不跟着缩放
                                        if (onBox) {
                                            e.changes.forEach { it.consume() }
                                            continue
                                        }
                                        val z = e.calculateZoom()
                                        if (z != 1f) setZoom(zoom * z)
                                        val p = e.calculatePan()
                                        if (zoom <= 1.001f) {
                                            zoom = 1f
                                            pan = Offset.Zero
                                        } else {
                                            pan = clampPan(pan + p)
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
                                            onBox -> {
                                                // 文本框的拖动由文本框层自己处理
                                            }
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
                                                // 不再 clamp 到图片内：手在图片外画，笔画点照常记录真实坐标。
                                                // 超出图片的笔画在预览里能看到（涂鸦层已移出裁剪框），
                                                // 保存时由 ImageEditor 的 clipRect 裁掉图片外部分。
                                                val x = bp.x
                                                val y = bp.y
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
                                                // 放大后单指平移（同样允许推出屏幕一点）
                                                pan = clampPan(pan + delta)
                                            }
                                        }
                                        c.consume()
                                    }
                                    if (!c.pressed) break
                                }

                                // 轻点：文本框模式下新建；其它情况先收掉正在输入的文本框
                                if (!pinch && travel < slop) {
                                    if (onBox) {
                                        // 单击/双击文本框由文本框层处理（双击进入输入抽屉）
                                    } else if (tool == EditTool.TEXT && editingId == null) {
                                        val bp = toBitmap(down.position)
                                        val x = bp.x.coerceIn(0f, bmpW.toFloat())
                                        val y = bp.y.coerceIn(0f, bmpH.toFloat())
                                        val size = (bmpH * 0.075f).coerceIn(18f, 120f)
                                        val id = nextBoxId++
                                        val lum = luminance(bmp, x, y, size)
                                        // 默认框宽取图宽的四成左右，保证一开始就能排下一句话；
                                        // 高度不用存——随文字折行自动伸展
                                        val defW = (bmpW * 0.4f).coerceIn(size * 4f, bmpW.toFloat())
                                        boxes = boxes + TextBox(
                                            id = id, x = x, y = y, text = "",
                                            sizePx = size,
                                            color = if (lum > 0.55f) android.graphics.Color.BLACK
                                            else android.graphics.Color.WHITE,
                                            widthPx = defW,
                                            bgColor = android.graphics.Color.WHITE
                                        )
                                        editOrigin = ""
                                        editingId = id
                                    } else if (editingId != null) {
                                        // 中间态：抽屉已拉下，点空白不结束编辑 —— 既不是取消也不是确定，
                                        // 保持当前框选中，可继续操作它或从底部白线把输入抽屉拉回来
                                        selectedBoxId = editingId
                                    } else {
                                        blurBox()
                                        selectedBoxId = null
                                    }
                                }
                            }
                        }
                )

                // ── 文本框层：跟图片同一套变换，但组合在**手势层之上**，否则点不中 ──
                // 这一层整体没有 pointerInput，空白处的事件会穿透到下面的手势层。
                // 不裁剪：文字很多时文本框会被撑得高于图片，允许它超出图片边界显示和操作，
                // 否则超出图片的那截文字会被裁掉、永远看不全。
                Box(Modifier.fillMaxSize()) {
                    // 图片框保留固定 size 作为定位参照：文本框的 offset 以它的左上角为原点。
                    // 固定 size 不会裁剪子项，文本框 wrapContent 高度由文字撑开，可超出本框。
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(with(density) { wpx.toDp() }, with(density) { hpx.toDp() })
                            .graphicsLayer {
                                // 以屏幕中心为锚点做缩放/平移，跟图片层保持一致
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            }
                    ) {
                        // 涂鸦层：放在不裁剪的参照框里，与图片同一套变换/坐标。
                        // 之前放在图片框内被 clipToBounds + 圆角裁掉，手在图片外画的笔画看不见。
                        // 现在超出图片的笔画照常显示；保存时由 ImageEditor.clipRect 裁掉图片外部分。
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

                        boxes.forEach { box ->
                            val focused = box.id == editingId
                            // 单击选中、双击进入输入的框都显示边框
                            val selected = focused || box.id == selectedBoxId
                            // 中间态门控：某个框正在编辑（editingId 非空）时，
                            // 只有它自己可操作（拉下抽屉后能直接拖/捏/双击重新打开输入）；
                            // 其它框一律不挂手势，点它们不会被改、也不会被带进编辑。
                            val boxEnabled = tool == EditTool.TEXT &&
                                (editingId == null || editingId == box.id)
                            // 框线颜色跟随「框色」选项（白 / 淡灰），内部透明不铺底色，
                            // 两侧圆点用同一个颜色，切换框色时框与圆点一起变。
                            val frameColor = Color(box.bgColor)
                            Box(
                                Modifier
                                    // 解除参照框 size(wpx,hpx) 施加的 maxWidth/maxHeight 约束：
                                    // 之前 Column 的宽度被卡在图片宽、Text 的高度被卡在图片高，
                                    // 于是文字撑不高、右边圆点也在图片右缘就"拉不动"了。
                                    // unbounded 让内容按真实尺寸测量，超出图片照样显示与操作。
                                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                                    .offset {
                                        IntOffset(
                                            (box.x * sf).roundToInt(),
                                            (box.y * sf).roundToInt()
                                        )
                                    }
                                    // 内部透明：不铺底色，只有文字可见（框线在选中时由 border 画出）
                                    .then(
                                        if (selected) Modifier
                                            .border(1.5.dp, frameColor, RoundedCornerShape(3.dp))
                                            .padding(2.dp)
                                        else Modifier.padding(2.dp)
                                    )
                                    .then(
                                        if (boxEnabled) Modifier
                                            // 一个手势入口统一处理：单击选中 / 双击输入 / 单指移动 / 双指捏合缩放
                                            .pointerInput(box.id, sf) {
                                                val tapSlop = 6.dp.toPx()
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = true)
                                                    var travel = 0f
                                                    var pinching = false
                                                    var consumedElsewhere = false
                                                    while (true) {
                                                        val ev = awaitPointerEvent()
                                                        if (ev.changes.count { it.pressed } == 0) break
                                                        if (ev.changes.count { it.pressed } >= 2) {
                                                            pinching = true
                                                            val z = ev.calculateZoom()
                                                            if (z != 1f && z.isFinite()) {
                                                                boxes = boxes.map { b ->
                                                                    if (b.id == box.id) {
                                                                        // 严格等比：只给字号定一个上下界，宽度用「字号实际变化比例」同比缩放。
                                                                        // 之前宽度单独 coerceIn，字号到顶/到底后宽度还在变，比例就崩了。
                                                                        val ns = (b.sizePx * z).coerceIn(8f, bmpH * 0.4f)
                                                                        val ratio = ns / b.sizePx
                                                                        b.copy(
                                                                            sizePx = ns,
                                                                            widthPx = b.widthPx * ratio
                                                                        )
                                                                    } else b
                                                                }
                                                            }
                                                            ev.changes.forEach { it.consume() }
                                                            continue
                                                        }
                                                        if (pinching) continue
                                                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (c.isConsumed) {
                                                            consumedElsewhere = true
                                                            break
                                                        }
                                                        // 抽搐根因：pointerInput 挂在这个会移动的框上，c.position 是
                                                        // 「相对框自身左上角」的局部坐标。框每移动一帧，下一帧的局部坐标基准
                                                        // 就跟着偏移；用跨帧保存的 lastPos 相减，delta 会正负交替 → 走走停停。
                                                        // 改用同一事件内的 position - previousPosition：这两个都基于框当前
                                                        // 坐标系，差值就是这帧手指的真实位移，与框自身位置无关（Compose 官方
                                                        // detectDragGestures 正是这么算 dragAmount 的）。
                                                        val frameDelta = c.position - c.previousPosition
                                                        travel += frameDelta.getDistance()
                                                        if (travel > tapSlop) {
                                                            selectedBoxId = box.id
                                                            boxes = boxes.map { b ->
                                                                if (b.id == box.id) b.copy(
                                                                    // 允许框自由移到图片/屏幕外：边界放到图片尺寸的很多倍，
                                                                    // 只防止数值彻底拖丢，不再卡在图片边缘
                                                                    x = (b.x + frameDelta.x / sf).coerceIn(-bmpW * 8f, bmpW * 8f),
                                                                    y = (b.y + frameDelta.y / sf).coerceIn(-bmpH * 8f, bmpH * 8f)
                                                                ) else b
                                                            }
                                                            c.consume()
                                                        }
                                                    }
                                                    if (!pinching && !consumedElsewhere && travel < tapSlop) {
                                                        val now = System.currentTimeMillis()
                                                        if (box.id == lastTapBoxId && now - lastTapTime < 320L) {
                                                            selectedBoxId = box.id
                                                            editOrigin = box.text
                                                            editingId = box.id
                                                            // 拉下抽屉的中间态下 editingId 已是本框、值没变，
                                                            // LaunchedEffect 不会重触发，这里显式把输入抽屉拉回来
                                                            textDrawer.open()
                                                            lastTapTime = 0L
                                                            lastTapBoxId = -1
                                                        } else {
                                                            selectedBoxId = box.id
                                                            lastTapTime = now
                                                            lastTapBoxId = box.id
                                                        }
                                                    }
                                                }
                                            }
                                        else Modifier
                                    )
                            ) {
                                Column(
                                    Modifier.width(with(density) { (box.widthPx * sf).toDp() })
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
                                            lineHeight = with(density) { (box.sizePx * sf * 1.25f).toSp() },
                                            fontWeight = FontWeight.Medium
                                        ),
                                        softWrap = true,
                                        // 不限制行数：文字在当前宽度下自动折行，超出就往下顺延
                                        maxLines = Int.MAX_VALUE
                                    )
                                }

                                // ── 左右边中点的圆手柄：仅选中且可操作时显示 ──
                                // 拖改宽度（widthPx）；框高不存固定值，随文字折行自动伸展
                                if (selected && boxEnabled) {
                                    // 圆点用与框线相同的颜色（白 / 淡灰），跟着框色一起变
                                    val handleColor = frameColor
                                    val handleR = 5.dp
                                    val hitR = 14.dp
                                    // 圆点用 Box 的垂直居中对齐，而不是按估算高度手动算 y：
                                    // 框高随文字折行实时变化时，中点由布局给出，圆点自然跟着上下移动，
                                    // 且始终在两侧正中（不受中文折行估算误差影响）。
                                    fun edgeHandle(axis: Int): Modifier = Modifier
                                        .offset {
                                            // 把命中圆点中心压到框的左/右边缘线上：
                                            // CenterStart 时圆心在 x=hitR，左移 hitR；
                                            // CenterEnd 时圆心在 x=W-hitR，右移 hitR。
                                            IntOffset(
                                                (if (axis == 0) -1 else 1) * hitR.toPx().roundToInt(),
                                                0
                                            )
                                        }
                                        .size(hitR * 2)
                                        .pointerInput(box.id, axis, sf) {
                                            detectDragGestures(
                                                onDragStart = { selectedBoxId = box.id },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    boxes = boxes.map { b ->
                                                        if (b.id != box.id) b else when (axis) {
                                                            // 左边手柄：改宽度同时右移左边，右边界固定。
                                                            // 不再用图片宽做边界——框可以拉到图片/屏幕外，只保留一个
                                                            // 极宽的防失控上限和"最小宽度"约束即可。
                                                            0 -> {
                                                                val right = b.x + b.widthPx
                                                                val nx = (b.x + amount.x / sf)
                                                                    .coerceIn(-bmpW * 8f, right - b.sizePx * 2f)
                                                                b.copy(x = nx, widthPx = right - nx)
                                                            }
                                                            // 右边手柄：只改宽度，左边界固定。上限放到图片宽的
                                                            // 很多倍（不再卡在 bmpW*3），文字再多也能把框拉宽到图片外。
                                                            else -> b.copy(
                                                                widthPx = (b.widthPx + amount.x / sf)
                                                                    .coerceIn(b.sizePx * 2f, bmpW * 8f)
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        .drawBehind {
                                            drawCircle(
                                                color = handleColor,
                                                radius = handleR.toPx(),
                                                center = center
                                            )
                                        }

                                    Box(edgeHandle(0).align(Alignment.CenterStart))
                                    Box(edgeHandle(1).align(Alignment.CenterEnd))
                                }
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
                            onClick = { setZoom(zoom + 0.3f) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomIn, "放大", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { setZoom(zoom - 0.3f) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOut, "缩小", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // ── 底部拉起热区：常驻组合，只有白线跟着状态显隐 ──
        // 编辑文本时这条热区归输入抽屉（划下去还能划上来）；
        // 点了「确定」结束编辑后，它才重新负责拉起主编辑面板。
        val showPullLine by remember {
            derivedStateOf {
                drawer.progress.floatValue < 0.002f && textDrawer.progress.floatValue < 0.002f
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .pointerInput(editingId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        awaitDrawerDrag(
                            state = if (editingId != null) textDrawer else drawer,
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
                            slopPx = 8.dp.toPx(),
                            // 工具行 / 选项行横向滚动时已经消费了事件：
                            // 抽屉让位，不再跟着上下抖
                            respectConsumed = true
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

        // ── 文本框输入抽屉：把手 + 输入框 + 文字颜色 + 取消 / 确定 ──
        // 与主抽屉同一套 DrawerState：跟手拖动、松手吸附；出现时不主动调起输入法，
        // 点一下输入框再弹键盘；输入法上的确认键禁用（ImeAction.None），只认「确定」。
        val editing = boxes.firstOrNull { it.id == editingId }
        if (editing != null || textDrawer.progress.floatValue > 0.002f) {
            if (blurEnabled) {
                BlurBand(
                    heightPx = { textDrawer.progress.floatValue * screenH },
                    image = bgImage,
                    dim = 0.34f,
                    cornerRadiusPx = with(density) { 22.dp.toPx() },
                    fallback = Color.Black.copy(alpha = 0.55f)
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { screenH.toDp() })
                    .graphicsLayer { translationY = (1f - textDrawer.progress.floatValue) * screenH },
                color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                // 面板改「列表」结构：把手固定、内容区可滚动、按钮钉在底部。
                // 关键：下拉收起手势只挂在把手上（不再铺满整个面板、也不在 Initial pass 抢事件），
                // 否则纵向拖动会被它吃掉 → 内容滚不动 → 文字一长就点不到「确定」。
                Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 把手贴着面板顶边，下拉只从这儿收起抽屉
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    awaitDrawerDrag(
                                        state = textDrawer,
                                        down = down,
                                        travelPx = screenH,
                                        slopPx = 8.dp.toPx()
                                    )
                                }
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

                    // 内容区占满剩余高度并可滚动：文字再多也能一路滚到底
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    Spacer(Modifier.height(8.dp))
                    WatchTextField(
                        value = editing?.text ?: "",
                        onValueChange = { v ->
                            val id = editingId ?: return@WatchTextField
                            boxes = boxes.map { if (it.id == id) it.copy(text = v) else it }
                        },
                        // 不限制字数：多行输入，可换行，内容自动在框宽内折行
                        singleLine = false,
                        modifier = Modifier.heightIn(min = 56.dp, max = 132.dp)
                    )

                    // ── 文字颜色 ──
                    Spacer(Modifier.height(10.dp))
                    // 滑动选色的换算步长：一颗圆点 + 间距，手指每滑过这么远换一颗颜色
                    val colorStep = with(density) { 36.dp.toPx() }
                    fun pickColor(idx: Int) {
                        val id = editingId ?: return
                        boxes = boxes.map {
                            if (it.id == id) it.copy(color = TextColors[idx.coerceIn(0, TextColors.lastIndex)]) else it
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "颜色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextColors.forEachIndexed { ci, c ->
                            val picked = editing?.color == c
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (picked) 2.dp else 1.dp,
                                        color = if (picked) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = 0.35f),
                                        shape = CircleShape
                                    )
                                    // 滑动选色：横向起手后跟着手指走，滑过一颗圆点换一颗颜色；
                                    // 纵向起手不认领，把事件让给抽屉的拖动 / 滚动
                                    .pointerInput(ci) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var totalX = 0f
                                            var totalY = 0f
                                            var claimed = false
                                            val slop = viewConfiguration.touchSlop
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!ch.pressed) break
                                                val dx = ch.position.x - ch.previousPosition.x
                                                val dy = ch.position.y - ch.previousPosition.y
                                                totalX += dx
                                                totalY += dy
                                                if (!claimed) {
                                                    if (abs(totalX) > slop && abs(totalX) > abs(totalY)) {
                                                        claimed = true
                                                        pickColor(ci)
                                                    } else if (abs(totalY) > slop) {
                                                        break   // 纵向手势，让给抽屉
                                                    }
                                                    continue
                                                }
                                                ch.consume()
                                                pickColor(ci + (totalX / colorStep).roundToInt())
                                            }
                                        }
                                    }
                                    .clickable {
                                        pickColor(ci)
                                    }
                            )
                        }
                    }

                    // ── 文本框底色：白 / 淡灰，铺在文字下面保证任何背景上都看得见 ──
                    Spacer(Modifier.height(8.dp))
                    fun pickBg(idx: Int) {
                        val id = editingId ?: return
                        boxes = boxes.map {
                            if (it.id == id) it.copy(bgColor = BoxBgColors[idx.coerceIn(0, BoxBgColors.lastIndex)]) else it
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "底色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BoxBgColors.forEachIndexed { ci, c ->
                            val picked = editing?.bgColor == c
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(
                                        width = if (picked) 2.dp else 1.dp,
                                        color = if (picked) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.35f),
                                        shape = CircleShape
                                    )
                                    .clickable { pickBg(ci) }
                            )
                        }
                        // 选项名称，跟在对应圆点后面
                        Text(
                            BoxBgLabels.joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    } // 内容区（可滚动）到此结束

                    Spacer(Modifier.height(8.dp))
                    // 按钮钉在面板底部：再长的文字也压不住「确定」
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
 * 估算文本框折行后的内容高度（原图像素）：行数 × 行高（字号 × 1.25）。
 *
 * 框高不存固定值，随文字自动伸展；命中测试与手柄定位都用这个估算值，
 * 换行估算按每行 `宽度 / (字号 × 0.62)` 个字符，与实际渲染足够接近。
 */
private fun estimateBoxHeight(b: TextBox): Float {
    val cpl = (b.widthPx / (b.sizePx * 0.62f)).toInt().coerceAtLeast(1)
    val lines = b.text.split('\n').sumOf { seg -> seg.length / cpl + 1 }.coerceAtLeast(1)
    return lines * b.sizePx * 1.25f
}

/**
 * 取样一小块区域的平均亮度（0 = 黑，1 = 白）。
 *
 * 文本框的边框靠它选色：图偏亮用灰框、偏暗用白框 —— 否则白框压在白雪上完全看不见。
 * 只取几十个点，主线程上算不会卡。
 */
private fun luminance(bmp: Bitmap, box: TextBox): Float =
    luminance(bmp, box.x, box.y, box.sizePx, (box.widthPx / box.sizePx).toInt().coerceAtLeast(1))

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
