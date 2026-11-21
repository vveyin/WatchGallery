package com.watchgallery.app.ui

import android.app.Activity
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.watchgallery.app.data.Photo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 缩放上限（表冠与双指共用） */
private const val MAX_SCALE = 6f

/** 抽屉收起时，屏幕底部用于「上滑拉起抽屉」的把手区高度 */
private val HandleZone = 38.dp

/** 翻页触发比例：拖动超过屏高的这个比例就切到相邻一张 */
private const val PAGE_THRESHOLD = 0.16f

/**
 * 大图查看器。
 *
 * 交互（对标手机相册在手表上的简化版）：
 *  - 上下滑动跟手翻页（上滑下一张、下滑上一张），松手按位移吸附到位；
 *  - 图片放大时禁止翻页，上下滑动改为平移图片；
 *  - 双指缩放只改变大小，不改变方向；双击复位、表冠缩放；
 *  - 单击**不再**弹图片名（名字固定在抽屉顶部），抽屉里是 编辑 / 详细信息 / 重命名；
 *  - 底部只留一条把手，上滑拉起抽屉（两态：不显示 / 全屏）。
 *
 * 清晰度：Coil 按「屏幕的 3 倍」请求解码，放大到 3 倍以内都是原始像素，
 * 而不是像以前那样按视图大小解码、一放大就是一堆被拉开的像素。
 */
@Composable
fun PhotoViewerScreen(
    viewModel: GalleryViewModel,
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

    // 翻页位移：0 表示正好停在第 index 张，负值表示画面向上偏移（正在翻向下一张）
    var pageOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 入场动画：从点击的那张缩略图位置放大到全屏。
    // 黑底跟着动画一起淡入 —— 动画期间下面那层相册还看得见，等图片铺满屏幕时才彻底变黑，
    // 所以不会再出现「点进去先黑一下」。
    val enter = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (origin != null) enter.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    var animJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    val screenH = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val handlePx = with(density) { HandleZone.toPx() }
    val slopPx = with(density) { 6.dp.toPx() }

    // ── 抽屉：0 = 收起，1 = 铺满（三处抽屉统一用 DrawerState）──
    val drawer = rememberDrawerState(false)
    DrawerSettle(drawer)
    // 用 derivedStateOf 而不是直接读 progress：后者会让整个大图页在拖动的每一帧重组
    val drawerOpen by remember { derivedStateOf { drawer.progress.floatValue > 0.5f } }

    // 抽屉只要露出一点就不许抓图，否则几帧之后「编辑」按钮就被糊进背景里了
    val drawerVisible by remember { derivedStateOf { drawer.progress.floatValue > 0.002f } }
    LaunchedEffect(drawerVisible) {
        if (drawerVisible) {
            BlurBackdrop.setCovered(true)
        } else {
            BlurBackdrop.setCovered(false)
            if (blurEnabled && window != null) BlurBackdrop.refreshSoon(window, 80)
        }
    }

    // 进页面时抽屉是收起的：等大图解码并画出来，拍一张干净的底图
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

    // 双击复位缩放；单击什么都不做（名字固定在抽屉顶部，不再点一下弹一条）
    var lastTapAt by remember { mutableLongStateOf(0L) }
    fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (lastTapAt != 0L && now - lastTapAt <= 280L) {
            lastTapAt = 0L
            resetZoom()
        } else {
            lastTapAt = now
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

    // ── 详细信息 / 重命名 ──
    var showDetail by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Photo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingRename by remember { mutableStateOf<Pair<Photo, String>?>(null) }

    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pair = pendingRename ?: return@rememberLauncherForActivityResult
        pendingRename = null
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.renamePhoto(pair.first, pair.second) { _, _ -> }
        }
    }

    fun commitRename(photo: Photo, raw: String) {
        val name = raw.trim()
        if (name.isEmpty()) return
        viewModel.renamePhoto(photo, name) { ok, sender ->
            if (!ok && sender != null) {
                // 系统不允许直接改名：拉起「是否允许修改这张照片」的系统弹窗，同意后重试
                pendingRename = photo to name
                runCatching { writeLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            }
        }
    }

    val photo = photos.getOrNull(index)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 黑底跟着入场动画一起淡入：动画期间还能看见下面的相册。
            // 放在 drawBehind 里读动画值 —— 只失效绘制，不会每帧重组整棵图片树。
            .drawBehind { drawRect(Color.Black.copy(alpha = enter.value)) }
    ) {
        // 圆屏上左右两端是弧边，控件宽度按屏宽 0.66 收窄，保证完整可见
        val contentWidth = if (isRound) maxWidth * 0.66f else maxWidth
        val pageH = constraints.maxHeight.toFloat()
        // 解码尺寸：屏幕的 3 倍（封顶 1600），放大后不会立刻糊成马赛克
        val decodeW = (constraints.maxWidth * 3).coerceIn(320, 1600)
        val decodeH = (constraints.maxHeight * 3).coerceIn(320, 1600)

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
                if (!drawerVisible && blurEnabled && window != null) {
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
            val keyByUri = n >= 3
            val slots = if (range >= 2) intArrayOf(0, -1, 1, -2, 2) else intArrayOf(0, -1, 1)
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
                            // GIF 一律按动图解码（查看大图时不看列表那个开关）
                            val bigPainter = rememberAsyncImagePainter(
                                remember(p.uri, decodeW, decodeH) {
                                    ImageRequest.Builder(context)
                                        .data(p.uri)
                                        .size(decodeW, decodeH)
                                        .build()
                                }
                            )
                            val bigState = bigPainter.state
                            val loaded = bigState is AsyncImagePainter.State.Success
                            val useThumb = d == 0 && !loaded && thumb != null &&
                                    p.uri.toString() == startUri
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

            // ── 手势层：翻页 / 缩放 / 平移 / 双击 ──
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

            // 顶部缩放按钮：设置里关掉「缩放按键」后整排不渲染
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

            // ── 收起状态：底部把手，从这里上滑拉起抽屉 ──
            if (!drawerOpen) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(HandleZone)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // 按下就抓一帧：抽屉还在屏幕外，拍到的是干净的大图
                                if (blurEnabled && window != null) BlurBackdrop.capture(window)
                                awaitDrawerDrag(
                                    state = drawer,
                                    down = down,
                                    travelPx = screenH,
                                    slopPx = slopPx
                                )
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

            // ── 毛玻璃条：固定在屏幕底部，只露出抽屉盖住的那一条 ──
            if (blurEnabled) {
                BlurBand(
                    heightPx = { (drawer.progress.floatValue * screenH).coerceIn(0f, screenH) },
                    dim = 0.20f,
                    cornerRadiusPx = with(density) { 18.dp.toPx() }
                )
            }

            // ── 抽屉面板：整屏高，靠 translationY 升降，自身透明（玻璃板）──
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { screenH.toDp() })
                    .graphicsLayer { translationY = (1f - drawer.progress.floatValue) * screenH }
                    // 整块面板都能上下拖；轻点不消费，留给里面的按钮
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            awaitDrawerDrag(
                                state = drawer,
                                down = down,
                                travelPx = screenH,
                                slopPx = with(density) { 8.dp.toPx() }
                            )
                        }
                    },
                color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 把手贴着面板顶边
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.55f))
                        )
                    }

                    // ── 顶部一小行：文件名 ──
                    Text(
                        photo.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            drawer.close()
                            onEdit(photo)
                        },
                        modifier = Modifier
                            .widthIn(min = 118.dp)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("编辑", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(Modifier.height(7.dp))

                    OutlinedButton(
                        onClick = { showDetail = true },
                        modifier = Modifier
                            .widthIn(min = 118.dp)
                            .height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Filled.Info, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("详细信息", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.height(7.dp))

                    OutlinedButton(
                        onClick = {
                            renameText = photo.displayName.substringBeforeLast('.')
                            renameTarget = photo
                        },
                        modifier = Modifier
                            .widthIn(min = 118.dp)
                            .height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.DriveFileRenameOutline, null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("重命名", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.weight(1f))
                }
            }

            // ── 详细信息 ──
            if (showDetail) {
                PhotoDetailDialog(photo = photo, onClose = { showDetail = false })
            }

            // ── 重命名 ──
            renameTarget?.let { target ->
                RenameDialog(
                    initial = renameText,
                    onDismiss = { renameTarget = null },
                    onConfirm = { newName ->
                        renameTarget = null
                        commitRename(target, newName)
                    }
                )
            }
        }
    }
}

/** 详细信息弹窗 */
@Composable
private fun PhotoDetailDialog(photo: Photo, onClose: () -> Unit) {
    val date = remember(photo.dateAdded) {
        if (photo.dateAdded <= 0) "—"
        else DateFormat.format("yyyy-MM-dd HH:mm", photo.dateAdded * 1000L).toString()
    }
    val sizeText = remember(photo.size) {
        when {
            photo.size <= 0 -> "—"
            photo.size < 1024 -> "${photo.size} B"
            photo.size < 1024 * 1024 -> "${(photo.size / 1024f).roundToInt()} KB"
            else -> String.format("%.1f MB", photo.size / 1024f / 1024f)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 220.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "详细信息",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                DetailLine("名称", photo.displayName)
                DetailLine("尺寸", if (photo.width > 0) "${photo.width} × ${photo.height}" else "—")
                DetailLine("大小", sizeText)
                DetailLine("日期", date)
                DetailLine("文件夹", photo.folderPath.ifBlank { "根目录" })
                DetailLine("类型", photo.mimeType)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 重命名弹窗：输入框 + 手表上的小键盘（不依赖系统输入法） */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 230.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "重命名",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                MiniKeyboard(
                    onChar = { c -> text += c },
                    onBackspace = { text = text.dropLast(1) }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                        Text("确定", style = MaterialTheme.typography.labelMedium)
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
