package com.watchgallery.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.watchgallery.app.data.Photo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 缩放上限（表冠与双指共用） */
private const val MAX_SCALE = 6f

/** 抽屉面板高度：只容纳一个按钮 */
private val DrawerHeight = 54.dp

/** 抽屉收起时，屏幕底部用于「上滑拉起抽屉」的把手区高度 */
private val HandleZone = 38.dp

/** 翻页触发比例：拖动超过屏高的这个比例就切到相邻一张 */
private const val PAGE_THRESHOLD = 0.16f

/**
 * 大图查看器。
 *
 * 交互（对标手机相册在手表上的简化版）：
 *  - 上下滑动跟手翻页（上滑下一张、下滑上一张），松手按位移吸附到位，带缓动动画；
 *  - 图片放大时禁止翻页，上下滑动改为平移图片；
 *  - 双指缩放只改变大小，不改变方向；双击复位、单击显示图片信息、表冠缩放；
 *  - 底部不再放常驻按钮，改为只容纳「编辑」的高度受限抽屉。
 *
 * 性能：
 *  - 相邻照片常驻渲染树（不是切图时才加载），切图只是位移，Bitmap 早已解码完成，因此不卡；
 *  - 拖动时再多挂一张「下下张 / 上上张」，让快速连滑也有图可切；
 *  - 层的 key 用图片 uri，切图时相邻照片的 Image 组合会被复用，不会重新发起请求。
 */
@Composable
fun PhotoViewerScreen(
    photos: List<Photo>,
    initialIndex: Int,
    isRound: Boolean,
    crownEvents: Flow<Float> = emptyFlow(),
    showZoomButtons: Boolean = true,
    origin: Rect? = null,
    thumb: androidx.compose.ui.graphics.painter.Painter? = null,
    blurEnabled: Boolean = true,
    onBack: () -> Unit,
    onEdit: (Photo) -> Unit
) {
    var index by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showInfo by remember { mutableStateOf(false) }

    // 翻页位移：0 表示正好停在第 index 张，负值表示画面向上偏移（正在翻向下一张）
    var pageOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 入场动画：从点击的那张缩略图位置放大到全屏。
    // 相册点开大图原本是「瞬间替换画面」，没有一点过渡；这里用 280ms 缓动把它补上，
    // 有缩略图矩形就从那张的位置长大，取不到（比如表冠触发）就退化成淡入 + 轻微放大。
    // 没有缩略图矩形（例如从别处进入、或编辑完返回）就完全不做入场动画，避免反复「重新打开」的观感
    val enter = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (origin != null) enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }
    var animJob by remember { mutableStateOf<Job?>(null) }

    val density = LocalDensity.current
    val view = LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    val drawerPx = with(density) { DrawerHeight.toPx() }
    val handlePx = with(density) { HandleZone.toPx() }
    val slopPx = with(density) { 6.dp.toPx() }

    // 抽屉：drawerOffset 就是面板的 translationY。
    //   drawerPx → 完全收起（藏在屏幕下沿之外）
    //   0        → 完全展开（只占按钮高度，不遮住整屏）
    val drawerOffset = remember { Animatable(drawerPx) }
    var drawerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun openDrawer() {
        drawerOpen = true
        // 抽屉一入画就不许再抓图：否则几帧之后「编辑」按钮就被糊进背景里了
        BlurBackdrop.setCovered(true)
        scope.launch { drawerOffset.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }
    }

    fun closeDrawer() {
        drawerOpen = false
        scope.launch {
            drawerOffset.animateTo(drawerPx, tween(200, easing = FastOutSlowInEasing))
            // 面板完全滑出去之后再开门补一张干净的大图
            BlurBackdrop.setCovered(false)
            if (blurEnabled && window != null) BlurBackdrop.refreshSoon(window, 80)
        }
    }

    // 进页面时抽屉是收起的：等大图解码并画出来，拍一张干净的底图。
    // 期间用户要是先拉起抽屉，capture 会被 covered 门挡掉，不会拍到面板。
    LaunchedEffect(Unit) {
        if (blurEnabled && window != null) {
            BlurBackdrop.setCovered(false)
            delay(700)
            BlurBackdrop.capture(window)
        }
    }
    DisposableEffect(Unit) {
        onDispose { BlurBackdrop.setCovered(false) }
    }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

    // 单击 / 双击：双击复位缩放，单击切换图片信息。
    // 单击要等 280ms 才能确认不是双击，所以用延迟任务；期间来了第二次点击就取消它。
    var tapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val tapScope = rememberCoroutineScope()

    fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (lastTapAt != 0L && now - lastTapAt <= 280L) {
            lastTapAt = 0L
            tapJob?.cancel()
            tapJob = null
            resetZoom()
            return
        }
        lastTapAt = now
        tapJob?.cancel()
        tapJob = tapScope.launch {
            delay(280)
            showInfo = !showInfo
            lastTapAt = 0L
        }
    }

    val tapRef = rememberUpdatedState<() -> Unit>({ handleTap() })

    // 表冠缩放：逐条消费增量，避免累加值导致缩放失控
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            val s = (scale + delta * 0.01f).coerceIn(1f, MAX_SCALE)
            scale = s
            if (s <= 1.0001f) offset = Offset.Zero
        }
    }

    val photo = photos.getOrNull(index)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 圆屏上左右两端是弧边，控件宽度按屏宽 0.66 收窄，保证完整可见
        val contentWidth = if (isRound) maxWidth * 0.66f else maxWidth
        val pageH = constraints.maxHeight.toFloat()

        // 松手结算：超过阈值就切一张，并把位移补偿一整屏保证画面连续，然后缓动归零
        fun settlePage() {
            val threshold = pageH * PAGE_THRESHOLD
            val n = photos.size
            var delta = 0
            if (pageOffset <= -threshold) delta = 1
            else if (pageOffset >= threshold) delta = -1
            if (delta != 0 && n > 1) {
                index = wrapIndex(index + delta, n)
                pageOffset += delta * pageH
                scale = 1f
                offset = Offset.Zero
                // 换了张图，抽屉背景也得跟着换 —— 只在抽屉收起时拍，才不含面板
                if (!drawerOpen && blurEnabled && window != null) {
                    BlurBackdrop.refreshSoon(window, 360)
                }
            }
            animJob?.cancel()
            animJob = scope.launch {
                animate(
                    initialValue = pageOffset,
                    targetValue = 0f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ) { value, _ -> pageOffset = value }
            }
        }
        val settleRef = rememberUpdatedState<() -> Unit>({ settlePage() })

        if (photo == null) {
            Text("图片不存在", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            val n = photos.size
            // 平时只挂前后各一张；手指按住拖动时再多挂一张，保证快速连滑也有已解码的图
            val range = if (dragging && n >= 5) 2 else 1
            // 张数够多时用 uri 作 key，切图时相邻照片的 Image 组合会被原样复用（Bitmap 不重新解码）
            val keyByUri = n >= 3
            // 当前这张排在最前：入队顺序靠前，加载优先级更高，进页面时先出图
            val slots = if (range >= 2) intArrayOf(0, -1, 1, -2, 2) else intArrayOf(0, -1, 1)
            // 入场那一张的 uri：缩略图只在它还没解码完成时垫在下面
            val startUri = remember { photos.getOrNull(index)?.uri?.toString() }

            val rootW = constraints.maxWidth.toFloat()
            val rootH = constraints.maxHeight.toFloat()
            val originRect = origin
            val startScale = if (originRect != null && rootW > 0f) {
                (originRect.width / rootW).coerceIn(0.12f, 1f)
            } else 0.92f
            val startTx = if (originRect != null) originRect.center.x - rootW / 2f else 0f
            val startTy = if (originRect != null) originRect.center.y - rootH / 2f else 0f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val e = enter.value
                        val s = startScale + (1f - startScale) * e
                        scaleX = s
                        scaleY = s
                        translationX = startTx * (1f - e)
                        translationY = startTy * (1f - e)
                        alpha = 0.30f + 0.70f * e
                    }
            ) {
                for (d in slots) {
                    val p = photos[wrapIndex(index + d, n)]
                    val k = if (keyByUri) p.uri.toString() else "slot$d"
                    key(k) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationY = d * pageH + pageOffset }
                        ) {
                            val bigPainter = rememberAsyncImagePainter(p.uri)
                            // 读 painter.state 本身就会订阅（Coil 内部是 mutableStateOf）
                            val bigState = bigPainter.state
                            val loaded = bigState is AsyncImagePainter.State.Success
                            // 大图还没解码好时，用列表里那张已经解码的缩略图垫底：
                            // 入场动画第一帧就有内容，用户看到的是「点下去立刻放大」。
                            val useThumb = d == 0 && !loaded && thumb != null &&
                                    p.uri.toString() == startUri
                            // 大图解码完成后淡入，观感上像是「缩略图变清晰」而不是硬切
                            val bigAlpha by animateFloatAsState(
                                targetValue = if (useThumb) 0f else 1f,
                                animationSpec = tween(160),
                                label = "bigAlpha"
                            )
                            if (useThumb) {
                                Image(
                                    painter = thumb!!,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Image(
                                painter = bigPainter,
                                contentDescription = p.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // 只有当前这张参与缩放/平移；其余保持原始大小，只做位移
                                        scaleX = if (d == 0) scale else 1f
                                        scaleY = if (d == 0) scale else 1f
                                        translationX = if (d == 0) offset.x else 0f
                                        translationY = if (d == 0) offset.y else 0f
                                        alpha = bigAlpha
                                    }
                            )
                        }
                    }
                }
            }

            // ── 手势层：翻页 / 缩放 / 平移 / 点按 ──
            if (!drawerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // 底部把手区留给抽屉用
                                if (down.position.y > size.height - handlePx) {
                                    return@awaitEachGesture
                                }
                                animJob?.cancel()
                                dragging = true
                                var last = down.position
                                var travel = 0f
                                var multi = false
                                var everZoomed = false
                                val touchSlop = viewConfiguration.touchSlop
                                val pageHeight = size.height.toFloat()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    if (pressed > 1) multi = true

                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()

                                    if (zoom != 1f) {
                                        everZoomed = true
                                        val s = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                        scale = s
                                        if (s <= 1.0001f) offset = Offset.Zero
                                    }

                                    if (scale > 1f) {
                                        // 放大状态：只平移，绝不翻页
                                        offset = clampOffset(offset + pan, scale, size)
                                    } else if (!everZoomed && !multi) {
                                        // 原始大小：跟手翻页
                                        pageOffset = (pageOffset + pan.y)
                                            .coerceIn(-pageHeight, pageHeight)
                                    }

                                    val pos = event.changes.firstOrNull()?.position ?: last
                                    travel += (pos - last).getDistance()
                                    last = pos
                                }

                                dragging = false
                                if (abs(pageOffset) > touchSlop) {
                                    settleRef.value()
                                } else {
                                    pageOffset = 0f
                                    if (!multi && travel < touchSlop &&
                                        SystemClock.uptimeMillis() - down.uptimeMillis < 500L
                                    ) {
                                        tapRef.value()
                                    }
                                }
                            }
                        }
                )
            }

            // 顶部缩放按钮：设置里关掉「缩放按键」后整排不渲染（双指 / 表冠缩放不受影响）
            if (showZoomButtons) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = contentWidth)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { scale = (scale + 0.3f).coerceAtMost(MAX_SCALE) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Filled.ZoomIn, "放大", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            scale = (scale - 0.3f).coerceAtLeast(1f)
                            if (scale <= 1.0001f) offset = Offset.Zero
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Filled.ZoomOut, "缩小", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            }

            // 图片信息浮层（放在抽屉上方）
            if (showInfo) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = DrawerHeight + 14.dp)
                        .widthIn(max = contentWidth),
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "${photo.displayName}\n${photo.width}×${photo.height} · ${photo.bucketName}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // ── 收起状态：底部把手，从这里上滑拉起抽屉 ──
            if (!drawerOpen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(HandleZone)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                // 按下就抓一帧：抽屉还在屏幕外，拍到的是干净的大图
                                if (blurEnabled && window != null) BlurBackdrop.capture(window)
                                var total = 0f
                                var decided = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.all { !it.pressed }) break
                                    val change = event.changes.first()
                                    total += change.position.y - change.previousPosition.y
                                    if (!decided && total < -slopPx) decided = true
                                    if (decided) {
                                        change.consume()
                                        val target = (drawerPx + total).coerceIn(0f, drawerPx)
                                        scope.launch { drawerOffset.snapTo(target) }
                                    }
                                }
                                if (decided) {
                                    if (drawerOffset.value < drawerPx * 0.55f) openDrawer() else closeDrawer()
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
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

            // ── 展开时点面板以外区域收起 ──
            if (drawerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEvent()
                                    pressed = event.changes.any { it.pressed }
                                }
                                closeDrawer()
                            }
                        }
                )
            }

            // ── 毛玻璃条：固定在屏幕底部，只露出抽屉盖住的那一条 ──
            // 背景图按整屏坐标绘制，抽屉滑动时像素不动，只是可见范围在变。
            if (blurEnabled) {
                BlurBand(
                    heightPx = { (drawerPx - drawerOffset.value).coerceIn(0f, drawerPx) },
                    dim = 0.20f,
                    cornerRadiusPx = with(density) { 18.dp.toPx() }
                )
            }

            // ── 抽屉面板：只有按钮高度，靠 translationY 升降，自身透明（玻璃板）──
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(DrawerHeight)
                    .graphicsLayer { translationY = drawerOffset.value }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var total = 0f
                            var decided = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.all { !it.pressed }) break
                                val change = event.changes.first()
                                total += change.position.y - change.previousPosition.y
                                if (!decided && abs(total) > slopPx) {
                                    // 只接受下滑收起，上滑不处理（已到顶）
                                    if (total > 0f) decided = true else break
                                }
                                if (decided) {
                                    change.consume()
                                    scope.launch { drawerOffset.snapTo(total.coerceIn(0f, drawerPx)) }
                                }
                            }
                            if (decided) {
                                if (drawerOffset.value > drawerPx * 0.35f) closeDrawer() else openDrawer()
                            }
                        }
                    },
                color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .width(36.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                closeDrawer()
                                onEdit(photo)
                            },
                            modifier = Modifier
                                .widthIn(min = 96.dp)
                                .height(40.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    }
                }
            }
        }
    }
}

/** 循环取下标（翻页到头会绕回另一端） */
private fun wrapIndex(i: Int, n: Int): Int = if (n <= 0) 0 else ((i % n) + n) % n

/** 把平移量限制在「放大后超出容器的部分」以内，避免图片被拖出屏幕 */
private fun clampOffset(o: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        o.x.coerceIn(-maxX, maxX),
        o.y.coerceIn(-maxY, maxY)
    )
}
