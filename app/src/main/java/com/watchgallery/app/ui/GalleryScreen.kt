package com.watchgallery.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.watchgallery.app.data.FolderEntry
import com.watchgallery.app.data.Photo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs

/**
 * 多选手势方向判定的最小位移（px）：离开起点超过这个距离才判定方向，
 * 判定一次即锁定到手指抬起，避免中途来回摆动导致滚动/选取反复切换。
 */
private const val SELECT_DIRECTION_SLOP = 20f

/** 分页预加载提前量：还剩这么多排就加载下一批 */
private const val PRELOAD_ROWS = 5

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * 相册主界面 —— 正方形网格列表（列数可配置，默认两列）。
 * 全屏铺满，靠「中间行大、上下行淡出」的缩放自然避开表盘弧边。
 */
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float> = emptyFlow(),
    animateGifs: Boolean = true,
    onPhotoClick: (Photo) -> Unit,
    onOpenSettings: () -> Unit = {},
    onCheckUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    val photos by viewModel.photos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val report by viewModel.scanReport.collectAsState()
    val visibleCount by viewModel.visibleCount.collectAsState()
    val totalCount by viewModel.totalCountFlow.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val openFolder by viewModel.openFolder.collectAsState()
    val folderPhotos by viewModel.folderPhotos.collectAsState()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    var needSettings by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            needSettings = false
            viewModel.refresh()
        } else {
            val act = context as? Activity
            needSettings = act != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(act, mediaPermission())
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importPhotos(uris) }

    // 系统删除确认弹窗：MediaStore.createDeleteRequest 生成的 IntentSender，
    // 由系统自己的界面询问「删除这些照片？」，用户确认后系统执行删除。
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeleteRequestResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(deleteRequest) {
        val sender = deleteRequest?.sender ?: return@LaunchedEffect
        runCatching {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }.onFailure { viewModel.onDeleteRequestUnavailable() }
    }

    fun launchImport() {
        runCatching { importLauncher.launch(arrayOf("image/*")) }
            .onFailure { hint = "系统没有文件选择器，可将照片放到 Pictures 目录后点重新扫描" }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasMediaPermission(context)
                permissionGranted = granted
                if (granted) {
                    needSettings = false
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            !permissionGranted -> PermissionOverlay(
                needSettings = needSettings,
                onRequest = { permissionLauncher.launch(mediaPermission()) },
                onOpenSettings = { openAppSettings(context) }
            )
            photos.isEmpty() && isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(30.dp),
                color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp
            )
            photos.isEmpty() -> EmptyOverlay(
                summary = report.summary,
                scanCount = "${report.imagesRows}/${report.dirHits}/${report.importedCount}",
                hint = hint,
                onRefresh = { viewModel.refresh() },
                onImport = { launchImport() },
                onOpenSettings = onOpenSettings
            )
            else -> when {
                // ── 文件夹视图：进了某个文件夹 ──
                viewMode == GalleryViewMode.FOLDER && openFolder != null -> SwipeBackContainer(
                    onBack = { viewModel.closeFolder() },
                    backContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            FolderList(
                                folders = folders,
                                crownEvents = emptyFlow(),
                                onOpen = { viewModel.openFolder(it.path) }
                            )
                        }
                    },
                    content = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            if (folderPhotos.isEmpty()) {
                                FolderHint("这个文件夹里已经没有照片了")
                            } else {
                                ScalingPhotoList(
                                    items = folderPhotos,
                                    columns = gridColumns,
                                    crownEvents = crownEvents,
                                    animateGifs = animateGifs,
                                    showNames = showPhotoNames,
                                    hasMore = false,
                                    totalCount = folderPhotos.size,
                                    selectionMode = selectionMode,
                                    selectedUris = selectedUris,
                                    centerScale = isRound,
                                    onLoadMore = {},
                                    onPhotoClick = onPhotoClick,
                                    onRecordOrigin = { rect, painter ->
                                        viewModel.viewerOrigin = rect
                                        viewModel.viewerThumb = painter
                                    },
                                    onOpenPhoto = { uri ->
                                        folderPhotos.firstOrNull { it.uri.toString() == uri }?.let(onPhotoClick)
                                    },
                                    onLongPress = { viewModel.enterSelectionMode(it) },
                                    onToggle = { viewModel.toggleSelected(it) },
                                    onSelectRange = { viewModel.addSelected(it) },
                                    onDeselectRange = { viewModel.removeSelected(it) },
                                    onCancelSelection = { viewModel.exitSelectionMode() },
                                    onDeleteSelected = { viewModel.deleteSelected() }
                                )
                            }
                        }
                    }
                )
                // ── 文件夹视图：文件夹列表 ──
                viewMode == GalleryViewMode.FOLDER -> FolderList(
                    folders = folders,
                    crownEvents = crownEvents,
                    onOpen = { viewModel.openFolder(it.path) }
                )
                // ── 平铺列表 ──
                else -> ScalingPhotoList(
                    items = photos,
                    columns = gridColumns,
                    crownEvents = crownEvents,
                    animateGifs = animateGifs,
                    showNames = showPhotoNames,
                    hasMore = visibleCount < totalCount,
                    totalCount = totalCount,
                    selectionMode = selectionMode,
                    selectedUris = selectedUris,
                    centerScale = isRound,
                    onLoadMore = { viewModel.loadMore() },
                    onPhotoClick = onPhotoClick,
                    onRecordOrigin = { rect, painter ->
                        viewModel.viewerOrigin = rect
                        viewModel.viewerThumb = painter
                    },
                    onOpenPhoto = { uri ->
                        photos.firstOrNull { it.uri.toString() == uri }?.let(onPhotoClick)
                    },
                    onLongPress = { viewModel.enterSelectionMode(it) },
                    onToggle = { viewModel.toggleSelected(it) },
                    onSelectRange = { viewModel.addSelected(it) },
                    onDeselectRange = { viewModel.removeSelected(it) },
                    onCancelSelection = { viewModel.exitSelectionMode() },
                    onDeleteSelected = { viewModel.deleteSelected() }
                )
            }
        }

        // 多选模式：底部操作栏（覆盖在网格之上）。从屏幕下方滑上来，退出时滑回去
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(200)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(150)) { it } + fadeOut(tween(120))
        ) {
            SelectionBar(
                selectedCount = selectedUris.size,
                onDelete = { viewModel.deleteSelected() },
                onCancel = { viewModel.exitSelectionMode() },
                modifier = Modifier
            )
        }
    }
}

/**
 * 命中照片：先看是否落在某个 cell 矩形内；不在矩形内时取最近的 cell（间隙内也算）。
 *
 * 关键：cellRects 存的是**未缩放**的布局矩形，而照片实际是按行缩放绘制的
 * （graphicsLayer 中间 1.10 / 边缘 0.78，围绕「屏幕水平中线 + 本行竖直中心」缩放）。
 * 命中判定必须走和绘制完全一致的「视觉矩形」，否则会偏移小半格到一格——
 * 表现就是「点这张的复选圈却选中了上/下一张」。命中检测发生在交互期，
 * 此时条目浮现动画（0.88→1.0）早已结束，按 1.0 算即可。
 */
private fun hitCellOrNearest(
    cellRects: Map<String, Rect>, p: Offset, slop: Float,
    viewportCenterY: Float, screenWidthPx: Float, centerScale: Boolean
): String? {
    var best: String? = null
    var bestDist = Float.MAX_VALUE
    for ((uri, r) in cellRects) {
        val v = visualCellRect(r, viewportCenterY, screenWidthPx, centerScale)
        val dx = maxOf(v.left - p.x, 0f, p.x - v.right)
        val dy = maxOf(v.top - p.y, 0f, p.y - v.bottom)
        val d = if (dx == 0f && dy == 0f) 0f else kotlin.math.hypot(dx, dy)
        if (d < bestDist) {
            bestDist = d
            best = uri
        }
    }
    return if (bestDist <= slop) best else null
}

/**
 * 照片 cell 此刻被 graphicsLayer 缩放后的「视觉矩形」，命中检测统一用它。
 * 缩放公式与绘制处（item 的 graphicsLayer lambda）完全一致，避免两套算法对不上。
 */
private fun visualCellRect(
    cell: Rect, viewportCenterY: Float, screenWidthPx: Float, centerScale: Boolean
): Rect {
    val cy = (cell.top + cell.bottom) / 2f
    val s = if (centerScale && viewportCenterY > 0f) {
        val t = (abs(cy - viewportCenterY) / (viewportCenterY * 1.05f)).coerceIn(0f, 1f)
        1.10f - 0.32f * t * t
    } else 1f
    val px = screenWidthPx / 2f
    return Rect(
        px + (cell.left - px) * s,
        cy + (cell.top - cy) * s,
        px + (cell.right - px) * s,
        cy + (cell.bottom - cy) * s
    )
}

/** 某张照片右上角复选圈的热区，坐标在**视觉矩形**空间里；inset/radius 随该行缩放同步放大。 */
private fun inCheckCircle(
    visualCell: Rect, p: Offset, inset: Float, radius: Float, scale: Float
): Boolean {
    val cx = visualCell.right - inset * scale
    val cy = visualCell.top + inset * scale
    return kotlin.math.hypot(p.x - cx, p.y - cy) <= radius * scale
}

/**
 * 多选模式下的「普通滑动快捷多选」手势（一次按下 → 抬起）。
 *
 * 两个入口共用这一份逻辑，避免两套判定各写一遍、行为还不一致：
 *  - 长按成立后手指还没抬起 → 从长按点起直接接着滑（不用抬手指）；
 *  - 多选覆盖层 → 从按下点起滑。
 *
 * 规则（45° 分界，判定一次即锁定）：
 *  - 横向滑动 = 起点到当前点的矩形覆盖到的照片全部选中；
 *  - 纵向滑动 = 滚动图片列表；
 *  - 横向锁定后再纵向滑动，仍只是扩大选取范围，不会切成滚动；
 *  - 完全没移动 = 轻点，交给 [onTapAt]（打开大图 / 点复选圈切换）。
 */
private suspend fun AwaitPointerEventScope.runSelectionDrag(
    down: PointerInputChange,
    onScroll: (Float) -> Unit,
    onSelectRect: (Offset, Offset) -> Unit,
    allowTap: Boolean,
    onTapAt: (Offset) -> Unit,
    /** 把指针坐标换算到 root 视觉空间供框选用；覆盖层是恒等，长按接续要把 cell 局部坐标换过来。
     *  注意：滚动/方向判定用**原始**局部坐标——graphicsLayer 只作用于绘制，输入坐标本就是未缩放的，
     *  若把 delta 也缩放，滚动量会被放大 1/scale 倍。 */
    mapPoint: (Offset) -> Offset = { it }
) {
    val rawAnchor = down.position
    var last = rawAnchor
    var mode = 0            // 0=未判定 1=纵向滚动 2=横向选取
    var tapped = true
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        val raw = change.position
        val dx = raw.x - rawAnchor.x
        val dy = raw.y - rawAnchor.y
        if ((raw - rawAnchor).getDistance() > 8f) tapped = false

        if (mode == 0) {
            if (maxOf(abs(dx), abs(dy)) > SELECT_DIRECTION_SLOP) {
                mode = if (abs(dx) > abs(dy)) 2 else 1
                if (mode == 1) onScroll(-dy)      // 补上判定前已移动的纵向距离，起手不丢位移
                else onSelectRect(mapPoint(rawAnchor), mapPoint(raw))
            }
        } else if (mode == 1) {
            onScroll(-(raw.y - last.y))           // 手指往下拖 → 内容往前
        } else {
            onSelectRect(mapPoint(rawAnchor), mapPoint(raw))
        }
        last = raw
        if (!change.pressed) break
    }
    if (allowTap && tapped && mode == 0) onTapAt(mapPoint(rawAnchor))
}

/**
 * 正方形网格照片列表：每排 [columns] 张，越靠近视口中心的「行」越大、上下淡出。
 * 缩放/透明度在 graphicsLayer 绘制阶段按滚动位置实时计算，不触发重组。
 */
@Composable
internal fun ScalingPhotoList(
    items: List<Photo>,
    columns: Int,
    crownEvents: Flow<Float>,
    animateGifs: Boolean,
    showNames: Boolean,
    hasMore: Boolean,
    totalCount: Int,
    selectionMode: Boolean,
    selectedUris: Set<String>,
    /** 中间一排放大、上下淡出。圆屏靠它避开弧边；方屏关掉（每张一样大、不透明） */
    centerScale: Boolean = true,
    onLoadMore: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onRecordOrigin: (Rect?, androidx.compose.ui.graphics.painter.Painter?) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectRange: (Collection<String>) -> Unit,
    onDeselectRange: (Collection<String>) -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val context = LocalContext.current

    // 多选慢速拖动产生的滚动增量：手势回调处在受限协程作用域，不能直接调用
    // gridState.scroll{}，故把增量投递到普通协程里再执行滚动。
    val selectionScroll = remember {
        MutableSharedFlow<Float>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    LaunchedEffect(Unit) {
        selectionScroll.collect { dy -> gridState.scrollBy(dy) }
    }

    // 每张照片的屏幕矩形（px），供滑动刷选判定命中用。key = uri 字符串。
    val cellRects = remember { mutableMapOf<String, Rect>() }

    // 每张照片当前那个已解码的缩略图 painter：多选覆盖层里点开大图时，
    // 大图页要拿它当入场动画的第一帧（此时网格 item 的组合可能已被复用）。
    val painterByUri = remember { mutableMapOf<String, androidx.compose.ui.graphics.painter.Painter>() }

    // 多选轻点的判定尺寸（px）：
    //  - 复选圈圆心距 cell 右上角各 5dp+11dp=16dp，热区半径放大到 20dp（手指粗，宁大勿小）；
    //  - 照片本体允许 22dp 的容差，吃掉行缩放（1.10 / 0.78）带来的边界误差。
    val circleInsetPx = with(density) { 16.dp.toPx() }
    val circleRadiusPx = with(density) { 20.dp.toPx() }
    val nearestSlopPx = with(density) { 22.dp.toPx() }

    // 多选覆盖层自身的 root 偏移。cellRects 存的是 positionInRoot()（相对整个应用根），
    // 而覆盖层 pointerInput 的 down.position 是相对覆盖层 Box 的局部坐标。主相册里覆盖层
    // 恰好铺满全屏（偏移为 0）所以 v0.61 看着是对的；回收站列表上方有标题栏 + 保留时长行，
    // 覆盖层原点被顶下去几十像素，局部坐标就比 root 坐标小这一截 —— 表现成「划过下面、
    // 选中的却是上面那张」。这里把覆盖层原点记下来，统一把局部坐标换算成 root 坐标。
    val overlayOrigin = remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 手势开始时读取最新的多选状态。不能拿它当 pointerInput 的 key —— 长按进入多选后
    // 状态翻转会让手势协程重启，后续拖动就收不到了。
    val selectionModeState = rememberUpdatedState(selectionMode)
    // 同理：手势里要判断「起点这张是不是已经选了」来定加选/取消，必须读最新值
    val selectedUrisState = rememberUpdatedState(selectedUris)

    // 起点→当前点的矩形覆盖到的照片批量加选 / 批量取消。
    // 四周加 3px，保证纯横向/纵向划动（矩形某一边为 0）也能命中最外一行/列。
    // gestureHits 记录本次手势已处理过的照片，只上报新增的，避免每个移动事件都重建选中集合。
    val gestureHits = remember { mutableSetOf<String>() }
    fun applyRect(a: Offset, b: Offset, deselect: Boolean) {
        val pad = 3f
        val r = Rect(
            left = minOf(a.x, b.x) - pad,
            top = minOf(a.y, b.y) - pad,
            right = maxOf(a.x, b.x) + pad,
            bottom = maxOf(a.y, b.y) + pad
        )
        val fresh = ArrayList<String>(4)
        for ((uri, cell) in cellRects) {
            // 用视觉矩形判定，和看到的照片边界一致
            val v = visualCellRect(cell, screenHeightPx / 2f, screenWidthPx, centerScale)
            if (v.overlaps(r) && gestureHits.add(uri)) fresh.add(uri)
        }
        if (fresh.isNotEmpty()) {
            if (deselect) onDeselectRange(fresh) else onSelectRange(fresh)
        }
    }

    // 表冠滚动：照搬 f-x，事件到达直接 scrollBy，即时跟手、无阻尼。
    // 关键：必须调用 LazyGridState.scrollBy（内部按像素即时滚动、不触发 overscroll 弹簧），
    // 而不是 gridState.scroll{} 里那套会走 fling/overscroll 的 ScrollScope — 后者滚到
    // 边界时会触发 Compose 的回弹动画，正是「滚动完向反方向弹一小段」的根因。
    CrownScroll(crownEvents) { step ->
        gridState.scrollBy(step)
    }

    // 快滑到底就预加载下一批：屏幕底部那一排和当前最后一张照片之间还剩
    // [PRELOAD_ROWS] 排的距离时就开始加载，不用等真的滚到最后一条。
    //
    // 这里必须把 items.size / hasMore / columns 当 remember 的 key：
    // 旧写法是 remember { derivedStateOf { ... } }，lambda 在**首次组合**时就把
    // items（空列表）和 hasMore（false）捕获死了 —— 之后列表怎么变，判断里用的
    // 永远是「hasMore = false」，所以底部那行「继续下滑加载更多」一直挂着却从不加载。
    val needMore by remember(items.size, hasMore, columns) {
        derivedStateOf {
            if (!hasMore) return@derivedStateOf false
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            last >= items.size - columns * PRELOAD_ROWS
        }
    }
    LaunchedEffect(needMore) {
        if (needMore) onLoadMore()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val edgePad = (maxHeight / 2 - 72.dp).coerceAtLeast(24.dp)
        // 关掉 Compose 的过度滚动回弹（stretch/glow）：表冠滚到列表边界时它会把内容
        // 拉出再弹回，表现就是「滚动完向相反方向滚一小段」。f-x 的 Material2 LazyColumn
        // 没有这套效果，这里显式关闭以对齐它的硬停止手感。
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            // 不在手势进行中切换 userScrollEnabled：长按进入多选时切换会重建滚动节点、
            // 打断正在进行的指针手势（v0.26 卡死崩溃的另一个诱因）。
            // 多选模式下覆盖层会消费掉全部事件，网格本来就滚不动，交给覆盖层手动滚动。
            userScrollEnabled = true,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = edgePad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // 横向间隔取原来的一半（8dp → 4dp）：列宽随之变宽、照片变大，
            // 而左右 contentPadding 不变，所以整排的外沿位置保持原样。
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items, key = { it.uri.toString() }) { photo ->
                // 滚出屏幕被回收时清掉矩形，避免复制剩余项里残留旧坐标、划选误中屏幕外的照片
                DisposableEffect(photo.uri) {
                    onDispose { cellRects.remove(photo.uri.toString()) }
                }
                // 缩略图：按单元格像素解码（不按原图），GIF 是否动由设置决定。
                // 关闭动图时强制走 BitmapFactoryDecoder —— 它只解第一帧，
                // 于是列表里是静态封面，点开看大图仍然是动的。
                val cellPx = (screenWidthPx / columns).toInt().coerceAtLeast(96)
                val thumbModel = remember(photo.uri, animateGifs, cellPx) {
                    val b = coil.request.ImageRequest.Builder(context)
                        .data(photo.uri)
                        .size(cellPx, cellPx)
                    if (!animateGifs) b.decoderFactory(coil.decode.BitmapFactoryDecoder.Factory())
                    b.build()
                }
                val thumbPainter = rememberAsyncImagePainter(thumbModel)
                SideEffect { painterByUri[photo.uri.toString()] = thumbPainter }
                DisposableEffect(photo.uri) {
                    onDispose { painterByUri.remove(photo.uri.toString()) }
                }
                // 本单元格的长按信号：由 detectTapGestures 置位，拖动接续的协程读取
                val longPressed = remember { mutableStateOf(false) }
                // 同一排的所有照片共享一个「排缩放值」，并以屏幕水平中线为锚点统一缩放，
                // 这样整排是连在一起放大/缩小的，而不是每张照片各自以自身中心缩。
                val rowCenterY = remember { mutableFloatStateOf(-1f) }
                val leftX = remember { mutableFloatStateOf(0f) }
                val cellWidth = remember { mutableFloatStateOf(1f) }
                val measured = remember { mutableStateOf(false) }
                // 条目浮现缓动：新出现在列表里的条目淡入 + 轻微放大回正（220ms）。
                // 进列表、翻页加载、从文件夹视图切回来时，照片都是「浮现」出来的
                // 而不是硬贴上去；uri 不变的条目刷新列表时不重播。
                val appear = remember(photo.uri.toString()) { Animatable(0f) }
                LaunchedEffect(photo.uri.toString()) {
                    appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            val w = coords.size.width.toFloat()
                            val h = coords.size.height.toFloat()
                            rowCenterY.floatValue = pos.y + h / 2f
                            leftX.floatValue = pos.x
                            cellWidth.floatValue = w.coerceAtLeast(1f)
                            measured.value = true
                            cellRects[photo.uri.toString()] = Rect(pos.x, pos.y, pos.x + w, pos.y + h)
                        }
                        .graphicsLayer {
                            gridState.firstVisibleItemIndex
                            gridState.firstVisibleItemScrollOffset

                            val cy = rowCenterY.floatValue
                            val viewportCenter = screenHeightPx / 2f
                            // 关键：只要测量过就始终计算缩放，不再用 cy>=0 判断。
                            // 照片滚出屏幕顶部后 cy 会变负；旧代码此时跳过缩放、scale 恢复
                            // 默认 1.0，比周围缩小的照片大，看起来「突然变大」。
                            // 现在 cy 为负时 distance 更大、t 被 clamp 到 1，稳定停在最小缩放。
                            if (centerScale && measured.value && viewportCenter > 0f) {
                                val distance = kotlin.math.abs(cy - viewportCenter)
                                // 退出半径加大到 1.05×半屏，让顶部渐隐更平缓，不会到边缘突变。
                                val radius = viewportCenter * 1.05f
                                val t = (distance / radius).coerceIn(0f, 1f)
                                val eased = t * t
                                val sc = (1.10f - 0.32f * eased) * (0.88f + 0.12f * appear.value)
                                scaleX = sc
                                scaleY = sc
                                alpha = (1f - 0.55f * t) * appear.value
                                // 锚点统一到屏幕水平中线：同排照片围绕同一条竖线缩放，
                                // 间距随缩放同步变化，视觉上整排连在一起。
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = ((screenWidthPx / 2f) - leftX.floatValue) / cellWidth.floatValue,
                                    pivotFractionY = 0.5f
                                )
                            } else {
                                // 方屏（不做居中放大）也要有浮现缓动
                                val a = appear.value
                                alpha = a
                                scaleX = 0.88f + 0.12f * a
                                scaleY = 0.88f + 0.12f * a
                            }
                        }
                        .then(
                            Modifier
                                // ── A：长按成立后，手指不用抬起就能直接接着滑动多选 ──
                                // 绝不能用 withTimeoutOrNull 自己算长按：超时会取消指针协程，
                                // Compose 的事件分发会卡住（v0.26 的卡死崩溃就是这么来的）。
                                // 这里只「观察、不消费」，等 detectTapGestures 的 onLongPress 置位后接管，
                                // 接管后走的是和多选覆盖层完全一样的那套滑动逻辑（runSelectionDrag）。
                                .pointerInput(photo.uri.toString()) {
                                    awaitEachGesture {
                                        // ⚠️ 顺序极其重要：必须先挂起等按下，再判断多选状态。
                                        // awaitEachGesture 的实现是
                                        //   while (isActive) { awaitPointerEventScope { block() } }
                                        // 如果 block 在**任何挂起点之前**就 return，下一轮会立刻再次
                                        // 调用 block —— 形成没有挂起点的空转循环，把主线程
                                        // （AndroidUiDispatcher.Main）占满：UI 立即卡死、几秒后 ANR 被杀。
                                        // 这正是 v0.26/v0.27「长按进多选后松手，卡几秒直接崩溃」的根因。
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        // 已在多选模式：交给覆盖层统一处理，本次手势什么都不做
                                        if (selectionModeState.value) return@awaitEachGesture
                                        longPressed.value = false
                                        // 长按前只观察：不消费，滚动/单击都不受影响
                                        while (!longPressed.value) {
                                            val e = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = e.changes.firstOrNull { it.id == down.id }
                                                ?: return@awaitEachGesture
                                            if (!c.pressed) return@awaitEachGesture
                                        }
                                        // 长按成立：以长按点为锚点，立刻按普通滑动多选处理
                                        // （不触发轻点，长按本身已选中这张照片）
                                        // 长按是「从这里开始多选」的动作，所以这个手势永远只加选
                                        gestureHits.clear()
                                        // 这个 pointerInput 挂在 cell 上，change.position 是 cell 局部坐标；
                                        // 而 applyRect 用的是 root 视觉矩形，必须换过去，否则会从
                                        // 屏幕左上角开始框选（表现：选中的是滑动位置上面那张）。
                                        fun localToVisual(p: Offset): Offset {
                                            val base = cellRects[photo.uri.toString()]
                                                ?: return p
                                            val v = visualCellRect(
                                                base, screenHeightPx / 2f, screenWidthPx, centerScale
                                            )
                                            return Offset(
                                                v.left + p.x * (v.width / base.width.coerceAtLeast(1f)),
                                                v.top + p.y * (v.height / base.height.coerceAtLeast(1f))
                                            )
                                        }
                                        runSelectionDrag(
                                            down = down,
                                            onScroll = { selectionScroll.tryEmit(it) },
                                            onSelectRect = { a, b -> applyRect(a, b, deselect = false) },
                                            allowTap = false,
                                            onTapAt = {},
                                            mapPoint = ::localToVisual
                                        )
                                    }
                                }
                                // ── B：单击打开大图 / 长按进入多选 ──
                                .pointerInput(photo.uri.toString()) {
                                    detectTapGestures(
                                        onLongPress = {
                                            longPressed.value = true
                                            onLongPress(photo.uri.toString())
                                        },
                                        onTap = {
                                            // 记下这张缩略图在屏幕上的位置：大图页会从这里放大展开
                                            onRecordOrigin(
                                                cellRects[photo.uri.toString()],
                                                thumbPainter
                                            )
                                            onPhotoClick(photo)
                                        }
                                    )
                                }
                        )
                ) {
                    PhotoCard(photo, showNames, thumbPainter)
                    // 长按进入多选后出现的复选圈：从右上角「弹」出来，不是硬冒出来
                    AnimatedVisibility(
                        visible = selectionMode,
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                        enter = fadeIn(tween(130)) + scaleIn(
                            initialScale = 0.35f,
                            animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f)
                        ),
                        exit = fadeOut(tween(110)) + scaleOut(
                            targetScale = 0.35f,
                            animationSpec = tween(110)
                        )
                    ) {
                        SelectionCheck(checked = photo.uri.toString() in selectedUris)
                    }
                }
            }
            if (hasMore) {
                item(span = { GridItemSpan(columns) }, key = "load_more_footer") {
                    Box(
                        Modifier.fillMaxWidth().height(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "继续下滑加载更多  ·  已显示 $items.size / $totalCount 张",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        } // CompositionLocalProvider(LocalOverscrollConfiguration provides null)

        // 多选模式下的手势层（手机厂商相册逻辑：按方向区分意图，判定一次即锁定）：
        //  - 横向滑动（|dx| > |dy|，45° 为分界）= 快捷多选：起点到当前位置的矩形覆盖到的照片全部选中；
        //    起手那张**已经选中**时反转为快捷键取消（滑过的都取消选中）
        //  - 纵向滑动（|dy| ≥ |dx|）= 滚动图片列表；
        //  - 横向锁定后不松手再纵向滑动，仍只是扩大选取矩形，不会切成滚动；
        //  - 轻点照片 = 打开大图查看；轻点右上角复选圈 = 切换这张的选中状态。
        // 全程不画任何可见框。具体判定在 runSelectionDrag 里，与长按接续手势共用。
        if (selectionMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { overlayOrigin.value = it.positionInRoot() }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            gestureHits.clear()
                            // 覆盖层局部坐标 → root 坐标：所有命中判定（cellRects）都在 root 空间，
                            // 必须加上覆盖层原点的偏移，否则回收站里会选中划过区域上面那张。
                            fun localToRoot(p: Offset): Offset = p + overlayOrigin.value
                            val downRoot = localToRoot(down.position)
                            // 加选还是取消，由「起点这张照片当前是否已选中」决定（手机相册同款）：
                            // 从已选中的照片起手 → 整个滑动过程都是取消；从未选中的起手 → 加选。
                            // 这样同一个手势既能快速多选，也能快速反选，不用换按钮。
                            val startUri = hitCellOrNearest(
                                cellRects, downRoot, nearestSlopPx,
                                screenHeightPx / 2f, screenWidthPx, centerScale
                            )
                            val deselect = startUri != null && startUri in selectedUrisState.value
                            runSelectionDrag(
                                down = down,
                                onScroll = { selectionScroll.tryEmit(it) },
                                onSelectRect = { a, b -> applyRect(a, b, deselect) },
                                allowTap = true,
                                onTapAt = { p ->
                                    if (startUri != null) {
                                        val cell = cellRects[startUri]
                                        val visual = cell?.let {
                                            visualCellRect(it, screenHeightPx / 2f, screenWidthPx, centerScale)
                                        }
                                        val scale = if (centerScale && visual != null && cell != null) {
                                            visual.width / cell.width.coerceAtLeast(1f)
                                        } else 1f
                                        // p 是 runSelectionDrag 经 mapPoint 换算后的 root 坐标
                                        // 点在复选圈上才算「选择」，点照片本体是打开大图
                                        if (visual != null && inCheckCircle(
                                                visual, p, circleInsetPx, circleRadiusPx, scale
                                            )) {
                                            onToggle(startUri)
                                        } else {
                                            onRecordOrigin(cell, painterByUri[startUri])
                                            onOpenPhoto(startUri)
                                        }
                                    }
                                },
                                // 覆盖层的 pointerInput 坐标系相对自身 Box，需换算到 root
                                // 才能与 cellRects / visualCellRect 对齐。
                                mapPoint = ::localToRoot
                            )
                        }
                    }
            )
        }
    }
}

@Composable
private fun PhotoCard(
    photo: Photo,
    showName: Boolean,
    painter: androidx.compose.ui.graphics.painter.Painter
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painter,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 文件名条：设置里关闭「显示图片名」后整条不渲染，照片占满整个卡片
            if (showName) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        photo.displayName,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

/** 照片右上角的复选圈：选中实心对勾，未选中空心圈（位置由调用方的 AnimatedVisibility 给） */
@Composable
private fun SelectionCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.45f)
            )
            .then(
                if (!checked) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** 多选模式底部操作栏：已选数量 + 较小的删除按钮 + 取消 */
@Composable
private fun SelectionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("已选 $selectedCount 张", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface)
        // 删除：只留垃圾桶图标
        IconButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除",
                tint = if (selectedCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(19.dp)
            )
        }
        // 取消：标准返回箭头
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/** 文件夹列表：一条 = 一个「直接含有图片」的文件夹，点子目录会作为独立条目并列显示 */
@Composable
private fun FolderList(
    folders: List<FolderEntry>,
    crownEvents: Flow<Float>,
    onOpen: (FolderEntry) -> Unit
) {
    if (folders.isEmpty()) {
        FolderHint("没有可显示的文件夹\n可能都在设置里被隐藏了")
        return
    }
    ScalingScrollColumn(crownEvents = crownEvents) {
        item(key = "folder_header") {
            Box(
                Modifier.fillMaxWidth().height(28.dp).scalingItem("folder_header", scale = false),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "文件夹 · ${folders.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        folders.forEach { folder ->
            item(key = "folder_${folder.path}") {
                FolderRow(folder, onOpen)
            }
        }
    }
}

@Composable
private fun FolderRow(folder: FolderEntry, onOpen: (FolderEntry) -> Unit) {
    val itemKey = "folder_${folder.path}"
    Surface(
        onClick = { onOpen(folder) },
        modifier = Modifier.fillMaxWidth().height(56.dp).scalingItem(itemKey),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                val cover = folder.cover
                if (cover != null) {
                    Image(
                        painter = rememberAsyncImagePainter(cover.uri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.Folder, null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 父级路径做副标题：拆出来的子目录与父目录并列时，能看出它原来在哪
                    if (folder.parentPath.isBlank()) "${folder.count} 张" else "${folder.count} 张 · ${folder.parentPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderHint(text: String) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyOverlay(
    summary: String,
    scanCount: String,
    hint: String?,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.PhotoLibrary, null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text("没有找到照片", fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("媒体库/目录/导入：$scanCount\n$summary",
            fontSize = 10.sp, lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 5)
        hint?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, fontSize = 10.sp, lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp)) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("重新扫描", fontSize = 11.sp)
            }
            OutlinedButton(onClick = onImport,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp)) {
                Text("导入照片", fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(2.dp)) {
            Text("权限与扫描诊断", fontSize = 10.sp)
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun PermissionOverlay(
    needSettings: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.PhotoLibrary, null,
            modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("需要照片权限", fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(
            if (needSettings) "权限被拒绝，请到系统设置开启「照片/存储」权限"
            else "用于浏览和编辑手表上的照片",
            fontSize = 11.sp, lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = if (needSettings) onOpenSettings else onRequest) {
            Text(if (needSettings) "去设置开启" else "授予权限", fontSize = 12.sp)
        }
    }
}
