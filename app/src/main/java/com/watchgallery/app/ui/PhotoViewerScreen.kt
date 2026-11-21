package com.watchgallery.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

/**
 * 大图查看器。
 *
 * 交互（对标手机相册在手表上的简化版）：
 *  - 上下滑动切换上一张 / 下一张；图片处于放大状态时上下滑动改为平移图片，不切图；
 *  - 双指缩放只改变大小，不再旋转图片（旋转会让图片歪斜，已被移除）；
 *  - 双击复位、单击显示图片信息；
 *  - 底部不再放常驻按钮，改为只容纳「编辑」的高度受限抽屉：底部上滑拉起，下滑或点空白处收起；
 *  - 表冠缩放。
 */
@Composable
fun PhotoViewerScreen(
    photos: List<Photo>,
    initialIndex: Int,
    isRound: Boolean,
    crownEvents: Flow<Float> = emptyFlow(),
    onBack: () -> Unit,
    onEdit: (Photo) -> Unit
) {
    var index by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showInfo by remember { mutableStateOf(false) }

    val density = LocalDensity.current
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
        scope.launch { drawerOffset.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }
    }

    fun closeDrawer() {
        drawerOpen = false
        scope.launch { drawerOffset.animateTo(drawerPx, tween(200, easing = FastOutSlowInEasing)) }
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

    // 切图：手势协程里要拿到最新的 photos / index，所以用 rememberUpdatedState 包一层
    val stepRef = rememberUpdatedState<(Int) -> Unit>({ delta ->
        val n = photos.size
        if (n > 1) {
            index = ((index + delta) % n + n) % n
            scale = 1f
            offset = Offset.Zero
        }
    })
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

        if (photo == null) {
            Text("图片不存在", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            Image(
                painter = rememberAsyncImagePainter(photo.uri),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        // 旋转已被移除：缩放只改变大小，不改变方向
                    }
                    .then(
                        if (drawerOpen) {
                            // 抽屉展开时图片不接受任何手势，点面板外区域由遮罩负责收起
                            Modifier
                        } else {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // 底部把手区留给抽屉用，这里不碰
                                    if (down.position.y > size.height - handlePx) {
                                        return@awaitEachGesture
                                    }
                                    var last = down.position
                                    var travel = 0f
                                    var accY = 0f
                                    var pageLocked = false
                                    var multi = false
                                    var everZoomed = false
                                    val touchSlop = viewConfiguration.touchSlop
                                    val pageStep = 44.dp.toPx()

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

                                        if (scale > 1f || multi) {
                                            // 放大状态：单指/双指都是平移，绝不切图
                                            offset = clampOffset(offset + pan, scale, size)
                                        } else if (!everZoomed) {
                                            // 原始大小：上下滑动切图（上滑下一张，下滑上一张）
                                            accY += pan.y
                                            if (!pageLocked && abs(accY) >= pageStep) {
                                                stepRef.value(if (accY < 0f) 1 else -1)
                                                pageLocked = true
                                                accY = 0f
                                            }
                                        }

                                        val pos = event.changes.firstOrNull()?.position ?: last
                                        travel += (pos - last).getDistance()
                                        last = pos
                                    }

                                    if (!multi && travel < touchSlop &&
                                        SystemClock.uptimeMillis() - down.uptimeMillis < 500L
                                    ) {
                                        tapRef.value()
                                    }
                                }
                            }
                        }
                    )
            )

            // 顶部缩放按钮（底部按钮已移除，缩放另留两个入口）
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

            // ── 抽屉面板：只有按钮高度，靠 translationY 升降 ──
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
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
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

/** 把平移量限制在「放大后超出容器的部分」以内，避免图片被拖出屏幕 */
private fun clampOffset(o: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        o.x.coerceIn(-maxX, maxX),
        o.y.coerceIn(-maxY, maxY)
    )
}
