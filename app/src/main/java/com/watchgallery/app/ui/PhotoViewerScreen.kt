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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import kotlin.math.sqrt

/**
 * 最高解码档位的长边像素。缩放上限按它算：解码只到这个尺寸，再放大只是把像素拉开。
 */
private const val TOP_TIER_LONG = 3600f

/** 抽屉收起时，屏幕底部用于「上滑拉起抽屉」的把手区高度 */
private val HandleZone = 38.dp

/** 翻页触发比例：拖动超过屏高的这个比例就切到相邻一张 */
private const val PAGE_THRESHOLD = 0.16f

// ── 表冠归属：大图页同一串表冠事件同一时刻只能给一个消费者 ──
// 抽屉列表 / 详情浮窗 / 图片缩放订阅同一个 crownEvents(SharedFlow)，
// 不互斥的话转一下表冠会同时缩放图片+滚列表。优先级从高到低：
private const val CROWN_OWNER_NONE = 0    // 谁都不响应（重命名输入框等）
private const val CROWN_OWNER_DETAIL = 1  // 详细信息浮窗
private const val CROWN_OWNER_DRAWER = 2  // 大图抽屉的操作列表
private const val CROWN_OWNER_IMAGE = 3   // 图片缩放

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
 * 清晰度：Coil 按「原图长宽比 + 缩放档位」请求解码（长边 1400 / 2400 / 3600），
 * 放大到 3 倍以内都是原始像素，而不是像以前那样按视图大小解码、一放大就是一堆被拉开的像素。
 * 放大上限按屏幕像素现算（见 [maxZoomFor]），不再写死倍数。
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
    focusPhoto: Photo? = null,
    isTrash: Boolean = false,
    onBack: () -> Unit,
    onEdit: (Photo) -> Unit,
    onDelete: (Photo) -> Unit = {},
    onRestore: (Photo) -> Unit = {}
) {
    var index by remember {
        mutableIntStateOf(initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
    }

    // 当前正在看的这张用「稳定标识（uri，media id 兜底）」锚定，而不是记住一个
    // 会因列表刷新而错位的位置。恢复图这类异步刷新场景：点进来那一帧列表还没到位，
    // 之后 scan 回填会重排列表 —— 只记 index 就会指向别的图，表现成「点这张打不开 /
    // 打开的是旁边的图，滑一下才到」，而重进（数据一次就绪）就正常。
    // 现在列表每次变化都按锚点把 index 重新算到同一张图上；用户主动翻页时同步换锚点，
    // 所以刷新也不会把人拽回原处。
    var anchor by remember {
        mutableStateOf(
            focusPhoto?.uri?.toString()
                ?: photos.getOrNull(initialIndex)?.uri?.toString()
        )
    }
    var anchorId by remember {
        mutableLongStateOf(focusPhoto?.id ?: photos.getOrNull(initialIndex)?.id ?: -1L)
    }
    LaunchedEffect(photos) {
        if (photos.isEmpty()) return@LaunchedEffect
        // 先按 media id 找锚点：id 是 MediaStore 主键，最稳定；恢复图跨卷扫描后
        // uri 可能换卷而变，但 id 不变。id 找不到（记录被重建）再退回按 uri 找。
        var target = if (anchorId >= 0) photos.indexOfFirst { it.id == anchorId } else -1
        if (target < 0 && anchor != null) {
            target = photos.indexOfFirst { it.uri.toString() == anchor }
        }
        if (target >= 0 && target < photos.size && target != index) {
            index = target
        }
        // 兜底：index 越界时收回来，绝不停在「图片不存在」的空帧上
        if (index !in photos.indices) index = photos.lastIndex.coerceAtLeast(0)
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
    // 入场动画期间只画「点中的那一张」：不然上下一张会跟着一起被放大，
    // 视觉上就是「点了这张，怎么上面/下面那张也一起飞进来」。
    // 用布尔量而不是直接读 enter.value —— 后者会让整棵图片树每帧重组。
    var entered by remember { mutableStateOf(origin == null) }
    LaunchedEffect(Unit) {
        if (origin != null) {
            enter.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            entered = true
        }
    }
    var animJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(view) { view.context.findHostActivity()?.window }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val screenH = with(density) { cfg.screenHeightDp.dp.toPx() }
    val screenWpxTop = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val screenHpxTop = with(density) { cfg.screenHeightDp.dp.roundToPx() }
    val handlePx = with(density) { HandleZone.toPx() }
    val slopPx = with(density) { 6.dp.toPx() }

    /**
     * 当前这张图的放大上限：按屏幕像素算，不写死倍数。
     * 每次调用现算，切图 / 换屏幕尺寸后自然跟着变。
     */
    fun zoomCap(): Float = maxZoomFor(photos.getOrNull(index), screenWpxTop, screenHpxTop)

    /**
     * 缩放改变后重新收一次平移量。
     *
     * 放大时边界只会变大，不用管；缩小时边界会收窄，如果原本已经把图推到边上，
     * 这里把它收回允许范围，图不会突然「掉」到屏幕外。
     */
    fun reclampOffset() {
        if (scale <= 1.0001f) {
            offset = Offset.Zero
            return
        }
        offset = clampOffset(
            offset, scale,
            IntSize(screenWpxTop, screenHpxTop),
            photos.getOrNull(index)
        )
    }

    /**
     * 以**屏幕中心当前对应的那个图片像素**为锚点缩放。
     *
     * 屏幕上的点 = 屏幕中心 + (图片坐标 − 图片中心) × scale + offset。
     * 要让屏幕中心对应的图片点保持不动，scale 变成 k 倍时 offset 必须同比例变成 k 倍；
     * 只改 scale 不动 offset，放大后屏幕中心看到的就是图片上的另一个位置了 ——
     * 平移过之后再放大就会「飘走」，看着像是以图片自己的中心在放大。
     */
    fun setScale(target: Float) {
        val s = target.coerceIn(1f, zoomCap())
        if (s == scale) return
        if (s <= 1.0001f) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        offset *= s / scale
        scale = s
        reclampOffset()
    }

    // ── 抽屉：0 = 收起，1 = 铺满（三处抽屉统一用 DrawerState）──
    val drawer = rememberDrawerState(false)
    DrawerSettle(drawer)
    // 用 derivedStateOf 而不是直接读 progress：后者会让整个大图页在拖动的每一帧重组
    val drawerOpen by remember { derivedStateOf { drawer.progress.floatValue > 0.5f } }
    // 缩放档位：只有跨档才重组（否则每一帧缩放都会重算 ImageRequest）
    val zoomTier by remember {
        derivedStateOf {
            when {
                scale > 4.5f -> 3
                scale > 2.2f -> 2
                else -> 1
            }
        }
    }

    // 抽屉只要露出一点就不许抓图，否则几帧之后「编辑」按钮就被糊进背景里了
    val drawerVisible by remember { derivedStateOf { drawer.progress.floatValue > 0.002f } }
    LaunchedEffect(drawerVisible) {
        if (drawerVisible) {
            BlurBackdrop.setCovered(true, BlurBackdrop.Channel.VIEWER)
        } else {
            BlurBackdrop.setCovered(false, BlurBackdrop.Channel.VIEWER)
            if (blurEnabled && window != null) BlurBackdrop.refreshSoon(window, 80, BlurBackdrop.Channel.VIEWER)
        }
    }

    // 进页面时抽屉是收起的：等大图解码并画出来，拍一张干净的底图（只喂 viewer 通道，不碰相册背景）
    LaunchedEffect(Unit) {
        if (blurEnabled && window != null) {
            BlurBackdrop.setCovered(false, BlurBackdrop.Channel.VIEWER)
            delay(700)
            BlurBackdrop.capture(window, BlurBackdrop.Channel.VIEWER)
        }
    }
    DisposableEffect(Unit) {
        onDispose { BlurBackdrop.setCovered(false, BlurBackdrop.Channel.VIEWER) }
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

    // ── 详细信息 / 重命名 ──
    var showDetail by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Photo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingRename by remember { mutableStateOf<Pair<Photo, String>?>(null) }

    // 表冠缩放方向反转开关：用 rememberUpdatedState 让切换开关后立即生效，
    // 不必重启下面的 LaunchedEffect（重启会丢掉正在进行的事件流订阅）。
    val zoomInvert by viewModel.zoomInvertCrown.collectAsState()
    val zoomInvertRef = rememberUpdatedState(zoomInvert)

    // 表冠归属：同一时刻只让一个消费者响应 crownEvents。
    // 优先级：详细信息浮窗 > 重命名输入框(吞掉) > 抽屉列表 > 图片缩放。
    // 抽屉只要露出一点(drawerVisible)就归列表，避免拉抽屉时图片还在缩放。
    val crownOwner by remember {
        derivedStateOf {
            when {
                showDetail -> CROWN_OWNER_DETAIL
                renameTarget != null -> CROWN_OWNER_NONE
                drawerVisible -> CROWN_OWNER_DRAWER
                else -> CROWN_OWNER_IMAGE
            }
        }
    }
    val crownOwnerRef = rememberUpdatedState(crownOwner)

    // 表冠缩放：逐条消费增量，避免累加值导致缩放失控。
    // 只有归属是「图片缩放」时才响应；弹窗/抽屉打开时让位给对应列表。
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            if (crownOwnerRef.value != CROWN_OWNER_IMAGE) return@collect
            val sign = if (zoomInvertRef.value) -1f else 1f
            setScale(scale + delta * 0.01f * sign)
        }
    }

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
        val screenWpx = constraints.maxWidth
        val screenHpx = constraints.maxHeight

        // 松手结算：超过阈值就切一张，并把位移补偿一整屏保证画面连续，然后缓动归零
        fun settlePage() {
            val threshold = pageH * PAGE_THRESHOLD
            val n = photos.size
            var delta = 0
            if (pageOffset <= -threshold) delta = 1
            else if (pageOffset >= threshold) delta = -1
            if (delta != 0 && n > 1) {
                index = wrapIndex(index + delta, n)
                // 用户主动翻页 → 换锚点到当前这张，后面的异步刷新就停在这张、不往回拽
                photos.getOrNull(index)?.let {
                    anchor = it.uri.toString()
                    anchorId = it.id
                }
                pageOffset += delta * pageH
                scale = 1f
                offset = Offset.Zero
                // 换了张图，抽屉背景也得跟着换 —— 只在抽屉收起时拍，才不含面板
                if (!drawerVisible && blurEnabled && window != null) {
                    BlurBackdrop.refreshSoon(window, 360, BlurBackdrop.Channel.VIEWER)
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
                    // 入场动画没结束前只挂当前这一张，避免上下一张跟着一起放大
                    if (!entered && d != 0) continue
                    val p = photos[wrapIndex(index + d, n)]
                    val k = if (keyByUri) p.uri.toString() else "slot$d"
                    key(k) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationY = d * pageH + pageOffset }
                        ) {
                            // 解码尺寸按「原图长宽比 + 当前缩放档位」算：
                            // 以前只是把屏幕尺寸乘 3 当方框传进去，长图（比如 1080×8000 的截图）
                            // 会被压到 180×1350，放大后自然一团糊。
                            // 当前这张按缩放档位解码；前后各一张固定最低档（翻页时正好接着用，不重新解码）
                            val tier = if (d == 0) zoomTier else 1
                            val size = remember(p.uri, p.width, p.height, screenWpx, screenHpx, tier) {
                                decodeSize(p.width, p.height, screenWpx, screenHpx, tier)
                            }
                            // GIF 一律按动图解码（查看大图时不看列表那个开关）
                            val bigPainter = rememberAsyncImagePainter(
                                remember(p.uri, size) {
                                    ImageRequest.Builder(context)
                                        .data(p.uri)
                                        .size(size.first, size.second)
                                        .build()
                                }
                            )
                            // 高倍档位解码要几十毫秒：先用低清打底，避免换档时闪一帧空白
                            val basePainter = if (d == 0 && tier > 1) {
                                val baseSize = remember(p.uri, p.width, p.height, screenWpx, screenHpx) {
                                    decodeSize(p.width, p.height, screenWpx, screenHpx, 1)
                                }
                                rememberAsyncImagePainter(
                                    remember(p.uri, baseSize) {
                                        ImageRequest.Builder(context)
                                            .data(p.uri)
                                            .size(baseSize.first, baseSize.second)
                                            .build()
                                    }
                                )
                            } else null
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
                            if (basePainter != null && !loaded) {
                                Image(
                                    painter = basePainter,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                    .graphicsLayer {
                                        // 锚点固定在**屏幕中心**：图片在层里是居中 Fit 的，
                                        // 图层中心就是屏幕中心，缩放时中间那一点始终不动
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
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
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        scaleX = if (d == 0) scale else 1f
                                        scaleY = if (d == 0) scale else 1f
                                        translationX = if (d == 0) offset.x else 0f
                                        translationY = if (d == 0) offset.y else 0f
                                        val base = if (useThumb) bigAlpha
                                        else if (basePainter != null && !loaded) 0f else 1f
                                        // 入场：从「缩略图那种偏暗 + 半透明」平滑升到标准亮度，
                                        // 不是一下子跳到全亮（那是原来黑一下 / 闪一下的来源）
                                        alpha = if (d == 0) base * (0.35f + 0.65f * enter.value)
                                        else base
                                    }
                                    // 亮度部分：整块压一层黑，透明度随入场进度降到 0。
                                    // 在 draw 阶段读动画值，只失效绘制，不重组图片树。
                                    .drawWithContent {
                                        drawContent()
                                        if (d == 0) {
                                            val dim = (1f - enter.value) * 0.45f
                                            if (dim > 0.002f) {
                                                drawRect(Color.Black.copy(alpha = dim))
                                            }
                                        }
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
                                    // 右划返回已经认领这串指针（事件被上层消费）：
                                    // 立即停手，不再累计翻页位移，避免返回时顺手切了图
                                    if (event.changes.firstOrNull()?.isConsumed == true) break
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    if (pressed > 1) multi = true

                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()

                                    if (zoom != 1f) {
                                        everZoomed = true
                                        setScale(scale * zoom)
                                    }

                                    if (scale > 1f) {
                                        // 放大状态：只平移，绝不翻页。锚点始终是屏幕中心，
                                        // 边界按图片实际显示尺寸算，并允许推出去一点点
                                        offset = clampOffset(
                                            offset + pan, scale, size,
                                            photos.getOrNull(index)
                                        )
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
                            onClick = { setScale(scale + 0.3f) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomIn, "放大", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { setScale(scale - 0.3f) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOut, "缩小", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── 收起状态：底部把手，从这里上滑拉起抽屉 ──
            // ⚠️ 这个节点必须**常驻**：以前写成 `if (!drawerOpen)`，手指正拉到一半、
            // progress 刚过 0.5 时它被移出组合，指针协程随之取消 —— release() 不执行、
            // dragging 一直是 true，吸附协程直接 return，抽屉就冻在屏幕中间，
            // 非得再拉一下才继续。现在只用 alpha 隐藏，节点永远在树上。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(HandleZone)
                    .graphicsLayer { alpha = (1f - drawer.progress.floatValue).coerceIn(0f, 1f) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 按下就抓一帧：抽屉还在屏幕外，拍到的是干净的大图（viewer 通道独立）
                            if (blurEnabled && window != null) BlurBackdrop.capture(window, BlurBackdrop.Channel.VIEWER)
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

            // ── 毛玻璃条：固定在屏幕底部，只露出抽屉盖住的那一条 ──
            if (blurEnabled) {
                BlurBand(
                    heightPx = { (drawer.progress.floatValue * screenH).coerceIn(0f, screenH) },
                    dim = 0.20f,
                    cornerRadiusPx = with(density) { 18.dp.toPx() },
                    channel = BlurBackdrop.Channel.VIEWER
                )
            }

            // ── 抽屉面板：整屏高，靠 translationY 升降，自身透明（玻璃板）──
            // 拖拽只挂在顶部把手上（设置抽屉同款），下面的操作做成一个列表：
            // 这样未来加功能直接往下加 item、超出一屏能滚动，且列表滚动不与抽屉拖拽抢手势。
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(with(density) { screenH.toDp() })
                    .graphicsLayer { translationY = (1f - drawer.progress.floatValue) * screenH },
                color = if (blurEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部把手：下滑收起抽屉
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(30.dp)
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

                    // ── 顶部一小行：文件名（居中）──
                    Text(
                        photo.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                    )

                    // ── 操作项列表：未来加功能往下加 item 即可，超出一屏可滚动 ──
                    ScalingScrollColumn(
                        crownEvents = crownEvents,
                        crownEnabled = { crownOwnerRef.value == CROWN_OWNER_DRAWER },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        item(key = "viewer_edit") {
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
                        }

                        item(key = "viewer_detail") {
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
                        }

                        item(key = "viewer_rename") {
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
                        }

                        // 最近删除查看大图：删除键上方加「恢复」
                        if (isTrash) {
                            item(key = "viewer_restore") {
                                OutlinedButton(
                                    onClick = {
                                        drawer.close()
                                        onRestore(photo)
                                    },
                                    modifier = Modifier
                                        .widthIn(min = 118.dp)
                                        .height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Filled.RestoreFromTrash, null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("恢复", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        item(key = "viewer_delete") {
                            OutlinedButton(
                                onClick = { onDelete(photo) },
                                modifier = Modifier
                                    .widthIn(min = 118.dp)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("删除", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // ── 详细信息 ──
            if (showDetail) {
                PhotoDetailDialog(
                    photo = photo,
                    crownEvents = crownEvents,
                    crownEnabled = { crownOwnerRef.value == CROWN_OWNER_DETAIL },
                    onClose = { showDetail = false }
                )
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

/**
 * 详细信息弹窗：面板是圆内最大正方形（0.70 × 短边），内容再多也不会被表框切掉。
 *
 * 内容超出面板时，除了手指拖动，**表冠也能滚** —— 手表上手指被表框挡着，
 * 用表冠看「文件夹 / 类型」这些靠下的字段更顺手。
 */
@Composable
private fun PhotoDetailDialog(
    photo: Photo,
    crownEvents: Flow<Float>,
    crownEnabled: () -> Boolean = { true },
    onClose: () -> Unit
) {
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
    val scroll = rememberScrollState()
    CrownScroll(crownEvents, enabled = crownEnabled) { step -> scroll.scrollBy(step) }
    WatchDialog(
        onDismiss = onClose,
        title = "详细信息",
        cancelLabel = "关闭",
        onCancel = onClose,
        scrollState = scroll,
        embedded = true   // 留在本 Activity 窗口内，表冠走「表冠适配」双模式解析
    ) {
        DialogLine("名称", photo.displayName)
        DialogLine("尺寸", if (photo.width > 0) "${photo.width} × ${photo.height}" else "—")
        DialogLine("大小", sizeText)
        DialogLine("日期", date)
        DialogLine("文件夹", photo.folderPath.ifBlank { "根目录" })
        DialogLine("类型", photo.mimeType)
    }
}

/**
 * 重命名弹窗：只用输入框 + 取消 / 确定，**输入交给系统输入法**（不自绘键盘）。
 *
 * 进入弹窗时**不主动弹输入法**：手表上输入法会盖掉大半个面板，先把名字和按钮摆出来，
 * 用户点一下输入框再调起；输入法上的确认键也不提交，只认面板里的「确定」。
 */
@Composable
internal fun RenameDialog(
    initial: String,
    title: String = "重命名",
    confirmLabel: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    WatchDialog(
        onDismiss = onDismiss,
        title = title,
        cancelLabel = "取消",
        confirmLabel = confirmLabel,
        confirmEnabled = text.isNotBlank(),
        onCancel = {
            keyboard?.hide()
            onDismiss()
        },
        onConfirm = {
            keyboard?.hide()
            onConfirm(text)
        }
    ) {
        WatchTextField(
            value = text,
            onValueChange = { text = it },
            focusRequester = focus
        )
    }
}

/** 循环取下标（翻页到头会绕回另一端） */
private fun wrapIndex(i: Int, n: Int): Int = if (n <= 0) 0 else ((i % n) + n) % n

/**
 * 按「原图长宽比 + 缩放档位」算解码尺寸。
 *
 * 以前是把屏幕宽高各乘 3 当作方框交给 Coil，Coil 会等比塞进这个方框 ——
 * 一张 1080×8000 的长图被压成 180×1350，放大后全是糊的。
 * 现在按长边目标（1400 / 2400 / 3600）等比缩放，长图能保持 1:7 的比例解出更多细节；
 * 再用 4MP 的面积上限兜底，方形大图不会一次解出几十 MB。
 */
private fun decodeSize(
    srcW: Int,
    srcH: Int,
    screenW: Int,
    screenH: Int,
    tier: Int
): Pair<Int, Int> {
    val sw = if (srcW > 0) srcW else screenW.coerceAtLeast(1)
    val sh = if (srcH > 0) srcH else screenH.coerceAtLeast(1)
    val longTarget = when (tier) {
        1 -> 1400
        2 -> 2400
        else -> TOP_TIER_LONG.toInt()
    }
    var w = sw
    var h = sh
    val longSide = maxOf(sw, sh)
    if (longSide > longTarget) {
        val k = longTarget.toFloat() / longSide
        w = (sw * k).roundToInt().coerceAtLeast(1)
        h = (sh * k).roundToInt().coerceAtLeast(1)
    }
    val area = w.toLong() * h.toLong()
    val maxArea = 4_000_000L
    if (area > maxArea) {
        val k = sqrt(maxArea.toDouble() / area).toFloat()
        w = (w * k).roundToInt().coerceAtLeast(1)
        h = (h * k).roundToInt().coerceAtLeast(1)
    }
    return w to h
}

/**
 * 一张图各自的放大上限 —— 按**屏幕像素**算，不再写死一个倍数。
 *
 * 依据是「这张图还剩多少像素可看」：铺满屏幕后长边约占屏幕长边，所以
 * `原图长边 / 屏幕长边` 就是 1:1 像素映射对应的倍数，再乘 4.6（= 1:1 的 4.6 倍余量，
 * 即原 2.3 倍的两倍）；同时不能超过最高解码档位（[TOP_TIER_LONG]）——
 * 超过它只是把像素拉开，不会更清楚。
 *
 * 屏幕越大 / 原图越小，上限自动变小；长图（1080×8000）能放到看清文字，
 * 而 200×200 的小图不会被无意义地拉到十几倍糊成一片。
 */
private fun maxZoomFor(photo: Photo?, screenW: Int, screenH: Int): Float {
    val dispLong = maxOf(screenW, screenH).coerceAtLeast(1).toFloat()
    val srcW = if (photo != null && photo.width > 0) photo.width else screenW
    val srcH = if (photo != null && photo.height > 0) photo.height else screenH
    val srcLong = maxOf(srcW, srcH).toFloat()
    val cap = minOf(srcLong, TOP_TIER_LONG) / dispLong * 4.6f
    // 下限 4 倍、上限 64 倍：均为原值（2 / 32）的两倍
    return cap.coerceIn(4f, 64f)
}

/** 允许推出屏幕的余量：屏幕短边的 10%，推到这儿就停住 */
private const val OVERSCROLL = 0.10f

/**
 * 这张图按 `ContentScale.Fit` 摆进屏幕后的实际显示尺寸。
 *
 * 平移边界必须按它算，不能按整块屏幕算：一张 4000×1000 的全景图在方屏上只占
 * 450×112，用屏幕高度当边界的话，能被推出上边框一整屏 —— 图片直接拖没了。
 */
private fun fitContentSize(photo: Photo?, screenW: Int, screenH: Int): Pair<Float, Float> {
    val sw = screenW.coerceAtLeast(1).toFloat()
    val sh = screenH.coerceAtLeast(1).toFloat()
    val iw = if (photo != null && photo.width > 0) photo.width.toFloat() else sw
    val ih = if (photo != null && photo.height > 0) photo.height.toFloat() else sh
    val k = minOf(sw / iw, sh / ih)
    return iw * k to ih * k
}

/**
 * 把平移量限制在「放大后图片超出屏幕的部分 + 一点余量」以内。
 *
 * 缩放锚点固定是**屏幕中心**（见 graphicsLayer 的 transformOrigin），所以这里只算平移：
 *  - 先把图片边缘刚好贴到屏幕边，这是「看得到整张图」的范围；
 *  - 再各加 [OVERSCROLL] 的余量，图片可以被推出去一点，松手后停在那儿；
 *  - 推到头就硬停，不会出现「一滑图片就不见了」。
 */
private fun clampOffset(o: Offset, scale: Float, screen: IntSize, photo: Photo?): Offset {
    val (cw, ch) = fitContentSize(photo, screen.width, screen.height)
    val slack = minOf(screen.width, screen.height) * OVERSCROLL
    val maxX = maxOf(0f, (cw * scale - screen.width) / 2f) + slack
    val maxY = maxOf(0f, (ch * scale - screen.height) / 2f) + slack
    return Offset(
        o.x.coerceIn(-maxX, maxX),
        o.y.coerceIn(-maxY, maxY)
    )
}
