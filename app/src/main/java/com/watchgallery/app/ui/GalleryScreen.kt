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
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.watchgallery.app.data.FolderEntry
import com.watchgallery.app.data.Photo
import com.watchgallery.app.theme.AppColors
import com.watchgallery.app.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

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
    val sortMode by viewModel.sortMode.collectAsState()
    val showPhotoNames by viewModel.showPhotoNames.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedUris by viewModel.selectedUris.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val openFolder by viewModel.openFolder.collectAsState()
    val folderPhotos by viewModel.folderPhotos.collectAsState()

    // 文件夹列表的滚动状态提升到父级：进入子文件夹、右滑返回时复用同一个实例，
    // 退出子文件夹后列表仍停在原来的位置（否则新建的 LazyListState 从顶部开始）。
    val folderListState = rememberLazyListState()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    var needSettings by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val createdFolders by viewModel.createdFolders.collectAsState()

    // ── 创建 / 删除文件夹弹窗 ──
    var showCreateFolder by remember { mutableStateOf(false) }
    var pendingDeleteFolder by remember { mutableStateOf<FolderEntry?>(null) }

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
                                createdFolders = createdFolders,
                                crownEvents = emptyFlow(),
                                isRound = isRound,
                                listState = folderListState,
                                onOpen = { viewModel.openFolder(it.path) },
                                onCreateFolder = { showCreateFolder = true },
                                onRequestDelete = { pendingDeleteFolder = it }
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
                                    onColumnsChanged = { viewModel.setGridColumns(it) },
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
                                    onDeleteSelected = { viewModel.deleteSelected() },
                                    sortMode = sortMode
                                )
                            }
                        }
                    }
                )
                // ── 文件夹视图：文件夹列表 ──
                viewMode == GalleryViewMode.FOLDER -> FolderList(
                    folders = folders,
                    createdFolders = createdFolders,
                    crownEvents = crownEvents,
                    isRound = isRound,
                    listState = folderListState,
                    onOpen = { viewModel.openFolder(it.path) },
                    onCreateFolder = { showCreateFolder = true },
                    onRequestDelete = { pendingDeleteFolder = it }
                )
                // ── 平铺列表 ──
                else -> ScalingPhotoList(
                    items = photos,
                    columns = gridColumns,
                    onColumnsChanged = { viewModel.setGridColumns(it) },
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
                    onDeleteSelected = { viewModel.deleteSelected() },
                    sortMode = sortMode
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

        // 创建文件夹：复用重命名弹窗，只改标题 / 确认按钮文字
        if (showCreateFolder && viewMode == GalleryViewMode.FOLDER) {
            RenameDialog(
                initial = "",
                title = "创建文件夹",
                confirmLabel = "创建",
                onDismiss = { showCreateFolder = false },
                onConfirm = { name ->
                    showCreateFolder = false
                    if (viewModel.createFolder(name) == null) {
                        hint = "文件夹已存在或名称无效"
                    }
                }
            )
        }

        // 删除文件夹：只对应用内创建的文件夹弹出确认，系统自带目录不会进到这里
        pendingDeleteFolder?.let { target ->
            WatchDialog(
                onDismiss = { pendingDeleteFolder = null },
                title = "删除文件夹",
                cancelLabel = "取消",
                confirmLabel = "删除",
                onCancel = { pendingDeleteFolder = null },
                onConfirm = {
                    viewModel.deleteCreatedFolder(target.path)
                    pendingDeleteFolder = null
                }
            ) {
                Text(
                    "确定删除文件夹「${target.name}」吗？\n只删除这个空文件夹，不会删除照片。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
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

/** 图片列表的一行：要么是跨整行的日期标题，要么是一张照片 */
private sealed class PhotoRow {
    data class Header(val label: String, val key: String) : PhotoRow()
    data class Photo(val photo: com.watchgallery.app.data.Photo) : PhotoRow()
}

/** 把照片按排序方式拆成「日期标题 + 照片」的行序列；不排序时原样平铺。
 *  数据源已按 dateAdded 倒序，因此相同分组键的照片天然相邻，直接顺序扫描即可。 */
private fun buildPhotoRows(items: List<com.watchgallery.app.data.Photo>, mode: PhotoSortMode): List<PhotoRow> {
    if (mode == PhotoSortMode.NONE || items.isEmpty()) return items.map { PhotoRow.Photo(it) }
    val cal = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
    val dayFmt = SimpleDateFormat("M月d日", Locale.getDefault())
    val monthFmt = SimpleDateFormat("yyyy年M月", Locale.getDefault())
    val yearFmt = SimpleDateFormat("yyyy年", Locale.getDefault())
    val rows = ArrayList<PhotoRow>(items.size + 8)
    var lastKey: String? = null
    for (photo in items) {
        cal.timeInMillis = photo.dateAdded * 1000L
        val (key, label) = when (mode) {
            PhotoSortMode.DAY -> {
                val y = cal.get(Calendar.YEAR)
                val d = cal.get(Calendar.DAY_OF_YEAR)
                "${y}-${d}" to dayFmt.format(Date(photo.dateAdded * 1000L))
            }
            PhotoSortMode.MONTH -> {
                val key2 = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
                key2 to monthFmt.format(Date(photo.dateAdded * 1000L))
            }
            PhotoSortMode.YEAR -> {
                val y = cal.get(Calendar.YEAR)
                "$y" to yearFmt.format(Date(photo.dateAdded * 1000L))
            }
            PhotoSortMode.NONE -> "" to ""
        }
        if (key != lastKey) {
            rows.add(PhotoRow.Header(label, "hdr_$key"))
            lastKey = key
        }
        rows.add(PhotoRow.Photo(photo))
    }
    return rows
}

/** 跨整行的日期标题：左边一小截主色竖条 + 日期文字，圆屏上收窄避免顶到弧边。
 *  与照片 cell 同款：越靠近视口中心越大、越清晰，越远离越小越淡。 */
@Composable
private fun DateHeader(
    label: String,
    centerScale: Boolean,
    gridState: LazyGridState,
    screenHeightPx: Float
) {
    // 圆屏上左右各留一点（缩小到原来的 1/3），让日期文字更贴近左边；方屏不收窄
    val sidePad = if (centerScale) LocalConfiguration.current.screenWidthDp.dp * (0.14f / 3f) else 0.dp
    // 与照片同款的入场浮现
    val appear = remember(label) { Animatable(0f) }
    LaunchedEffect(label) {
        appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    // 本行中心的 root y 坐标，用于算到视口中心的距离；measured 防止滚出顶部时跳回原尺寸
    val headerCenterY = remember(label) { mutableFloatStateOf(0f) }
    val measured = remember(label) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                headerCenterY.floatValue = pos.y + coords.size.height / 2f
                measured.value = true
            }
            .graphicsLayer {
                // 读滚动状态，使缩放随滚动在绘制阶段实时重算，不触发重组
                gridState.firstVisibleItemIndex
                gridState.firstVisibleItemScrollOffset

                val cy = headerCenterY.floatValue
                val viewportCenter = screenHeightPx / 2f
                if (centerScale && measured.value && viewportCenter > 0f) {
                    val distance = kotlin.math.abs(cy - viewportCenter)
                    val radius = viewportCenter * 1.05f
                    val t = (distance / radius).coerceIn(0f, 1f)
                    val eased = t * t
                    val sc = (1.10f - 0.32f * eased) * (0.88f + 0.12f * appear.value)
                    scaleX = sc
                    scaleY = sc
                    alpha = (1f - 0.55f * t) * appear.value
                    // 本行铺满整宽，锚点取自身中线即屏幕水平中线，与照片整排缩放一致
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                } else {
                    val a = appear.value
                    alpha = a
                    scaleX = 0.88f + 0.12f * a
                    scaleY = 0.88f + 0.12f * a
                }
            }
            .padding(top = 10.dp, bottom = 2.dp, start = sidePad, end = sidePad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(15.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 正方形网格照片列表：每排 [columns] 张，越靠近视口中心的「行」越大、上下淡出。
 * 缩放/透明度在 graphicsLayer 绘制阶段按滚动位置实时计算，不触发重组。
 */
@Composable
/**
 * 双指捏合改列数手势。
 *
 * - 单指事件一律放行：网格滚动、单击看大图、长按多选都不受影响。
 * - 一旦检测到第二根手指按下，立即接管：把整串事件 consume 掉，网格不再滚动。
 * - 张开 → 列数减少（图变大），捏合 → 列数增加（图变小），范围 1~10。
 * - 松手后按「视觉列数」吸附（向上取整）：屏幕上看着是几列，松手就停在几列，
 *   不再四舍五入（那样捏到看着 3 列却弹回 2 列），并回写持久化。
 */
private fun Modifier.pointerPinchColumns(
    enabled: Boolean,
    scope: CoroutineScope,
    displayColumns: androidx.compose.runtime.MutableFloatState,
    pinching: androidx.compose.runtime.MutableState<Boolean>,
    haptics: () -> Unit,
    onSnap: (Int) -> Unit
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 只用前两根手指算距离；出现第二指的那帧才开始接管
        var zoomStarted = false
        var startDist = 1f
        var startColumns = displayColumns.floatValue
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                // 所有手指抬起。若刚才在缩放，做吸附动画
                if (zoomStarted) {
                    pinching.value = false
                    val from = displayColumns.floatValue
                    // 视觉列数 = ceil(displayColumns)（网格就是按 ceil 排的），
                    // 松手吸附到视觉列数：看着是 3 列就停 3 列，不再四舍五入弹回 2 列。
                    val target = ceil(from).toInt().coerceIn(1, 10)
                    scope.launch {
                        animate(
                            initialValue = from,
                            targetValue = target.toFloat(),
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { value, _ -> displayColumns.floatValue = value }
                        onSnap(target)
                    }
                }
                break
            }
            if (!zoomStarted) {
                zoomStarted = true
                pinching.value = true
                startColumns = displayColumns.floatValue
                startDist = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
                haptics()
            }
            val dist = (pressed[0].position - pressed[1].position).getDistance().coerceAtLeast(1f)
            val ratio = dist / startDist
            // 张开 ratio>1 → 列数减少（图片变大）；捏合 ratio<1 → 列数增加
            displayColumns.floatValue = (startColumns / ratio).coerceIn(1f, 10f)
            // 接管这串指针：阻止 LazyGrid 把它当成滚动
            event.changes.forEach { it.consume() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScalingPhotoList(
    items: List<Photo>,
    columns: Int,
    /** 双指捏合吸附到新列数后回调，供外部持久化；不传则只改当前显示 */
    onColumnsChanged: (Int) -> Unit = {},
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
    onDeleteSelected: () -> Unit,
    /** 排序 / 分组方式：非 NONE 时在每组照片前插入跨整行的日期标题 */
    sortMode: PhotoSortMode = PhotoSortMode.NONE
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 双指捏合改列数 ──────────────────────────────────────────────
    // displayColumns 是「视觉列数」（可带小数）。网格的列宽 = 屏宽 / displayColumns，
    // 于是布局列数取 ceil：例如 3.8 → 布局 4 列，每格宽 = 屏宽/3.8，第 4 列右缘
    // 超出屏幕被裁掉，正好露出 0.8 列 —— 松手前就看到「吸附后的列数」且部分列露出。
    // 因为改的是真实布局宽度（不是绘制期 graphicsLayer），点击/划选坐标天然对齐。
    val displayColumns = remember { mutableFloatStateOf(columns.toFloat()) }
    // 外部（设置项 / 其它页面）改了列数时同步过来；捏合进行中不同步，避免打架
    val pinching = remember { mutableStateOf(false) }
    LaunchedEffect(columns) {
        if (!pinching.value) displayColumns.floatValue = columns.toFloat()
    }
    val layoutColumns by remember { derivedStateOf { ceil(displayColumns.floatValue).toInt().coerceIn(1, 10) } }

    // 卡片圆角随视觉列数等比缩放：单元格宽度 ∝ 1/displayColumns，圆角按相同比例变化，
    // 于是整个卡片轮廓（含圆角）看起来是等比例放大/缩小的，而不是图变小了圆角却不变。
    // 基准取默认 2 列时为 14.dp；1~10 列对应圆角 28dp→2.8dp。
    // 必须用固定基准 2（而非当前持久列数 columns），否则松手吸附后 columns 更新会让
    // 圆角突然跳变；固定基准下 displayColumns 连续变化，圆角全程连续。
    val cardCornerRadius: Dp = 14.dp * (2f / displayColumns.floatValue)

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

    // 按排序方式把照片拆成「日期标题 + 照片」的行序列。不排序时就是照片本身。
    val photoRows = remember(items, sortMode) { buildPhotoRows(items, sortMode) }

    // 快滑到底就预加载下一批：屏幕底部那一排和当前最后一张照片之间还剩
    // [PRELOAD_ROWS] 排的距离时就开始加载，不用等真的滚到最后一条。
    //
    // 这里必须把 photoRows.size / hasMore / columns 当 remember 的 key：
    // 旧写法是 remember { derivedStateOf { ... } }，lambda 在**首次组合**时就把
    // items（空列表）和 hasMore（false）捕获死了 —— 之后列表怎么变，判断里用的
    // 永远是「hasMore = false」，所以底部那行「继续下滑加载更多」一直挂着却从不加载。
    val needMore by remember(photoRows.size, hasMore, columns) {
        derivedStateOf {
            if (!hasMore) return@derivedStateOf false
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            last >= photoRows.size - columns * PRELOAD_ROWS
        }
    }
    // 列数一变，needMore 的阈值（columns × PRELOAD_ROWS）就瞬间翻转：10 列 → 1 列
    // 时阈值一下缩小几十条，立刻回调 onLoadMore 会让列表数据「在同一帧里增长」，
    // 正好撞上因列数变化而重新布局的 LazyGrid，二次 measure 时容易把已放置过的
    // 节点再 place 一次（闪退）。这里错开一拍再确认，正常滚到底几乎无感。
    LaunchedEffect(needMore) {
        if (!needMore) return@LaunchedEffect
        delay(160)
        if (needMore) onLoadMore()
    }

    // 尺寸改用普通 Box + onSizeChanged 读取，**不用** BoxWithConstraints。
    // BoxWithConstraints 内部是 SubcomposeLayout，它的 content 在「测量阶段」执行；
    // 而这里要读 displayColumns（列数）算网格宽度。设置里改「每排照片」是瞬间跳变，
    // 会在测量过程中让子组合失效、重入布局，把已经放置过的节点再 place 一次，抛
    // IllegalStateException: Place was called on a node which was placed already（闪退）。
    // 换成 onSizeChanged 后，尺寸变化只触发普通重组，不会再重入测量阶段。
    var listSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { listSize = it }) {
        // 首帧还没量出尺寸时用屏幕尺寸兜底（这本就是全屏列表），第二帧起用实测值
        val boxWidth = if (listSize.width > 0) with(density) { listSize.width.toDp() }
                       else LocalConfiguration.current.screenWidthDp.dp
        val boxHeight = if (listSize.height > 0) with(density) { listSize.height.toDp() }
                        else LocalConfiguration.current.screenHeightDp.dp
        val edgePad = (boxHeight / 2 - 72.dp).coerceAtLeast(24.dp)
        // 关掉 Compose 的过度滚动回弹（stretch/glow）：表冠滚到列表边界时它会把内容
        // 拉出再弹回，表现就是「滚动完向相反方向滚一小段」。f-x 的 Material2 LazyColumn
        // 没有这套效果，这里显式关闭以对齐它的硬停止手感。
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        // 网格真实宽度 = 屏宽 × 布局列数 / 视觉列数。displayColumns 为整数时正好铺满屏幕；
        // 有小数字（如 3.8）时网格比屏幕宽，超出的最后一列被上层 Box 裁掉，实现「部分露出」。
        val gridWidth = boxWidth * layoutColumns / displayColumns.floatValue
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layoutColumns),
            modifier = Modifier.width(gridWidth).height(boxHeight)
                // 双指捏合改列数：单指完全放行给网格滚动/点击；一旦识别双指就接管并 consume，
                // 阻止滚动。捏合中只改 displayColumns（真实布局宽度随之变），松手吸附到整数列。
                .pointerPinchColumns(
                    enabled = !selectionMode,
                    scope = scope,
                    displayColumns = displayColumns,
                    pinching = pinching,
                    haptics = {},
                    onSnap = { snapped -> onColumnsChanged(snapped) }
                ),
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
            items(
                items = photoRows,
                key = { row -> when (row) {
                    is PhotoRow.Header -> row.key
                    is PhotoRow.Photo -> row.photo.uri.toString()
                } },
                // 日期标题跨满整行：必须用 maxLineSpan（当前实际列数），
                // 不能用 GridItemSpan(layoutColumns) 这个「绝对列数」。
                // 绝对列数在列数跳变时会和旧的测量结果错位（比如 measure 时是 10 列、
                // place 时已成 1 列，剩余列数算成负数），LazyGrid 就会把同一个节点
                // 放进布局两次，抛 IllegalStateException: Place was called on a node
                // which was placed already —— 也就是「调完每排照片数就闪退」。
                // maxLineSpan 永远等于当前列数，天然不会错位。
                span = { row -> if (row is PhotoRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
            ) { row ->
              when (row) {
                is PhotoRow.Header -> {
                    DateHeader(
                        label = row.label,
                        centerScale = centerScale,
                        gridState = gridState,
                        screenHeightPx = screenHeightPx
                    )
                }
                is PhotoRow.Photo -> {
                val photo = row.photo
                // 滚出屏幕被回收时清掉矩形，避免复制剩余项里残留旧坐标、划选误中屏幕外的照片
                DisposableEffect(photo.uri) {
                    onDispose { cellRects.remove(photo.uri.toString()) }
                }
                // 缩略图：按单元格像素解码（不按原图），GIF 是否动由设置决定。
                // 关闭动图时强制走 BitmapFactoryDecoder —— 它只解第一帧，
                // 于是列表里是静态封面，点开看大图仍然是动的。
                val cellPx = (screenWidthPx / displayColumns.floatValue).toInt().coerceAtLeast(96)
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
                        // 位置动画：捏合缩放改列数时，照片会平滑地挪到新格子里，而不是硬跳。
                        // v0.83 曾因闪退临时移除，但那次闪退的真正原因是外层 BoxWithConstraints
                        // （SubcomposeLayout 在测量阶段重入布局），v0.84 换成普通 Box 后已根治，
                        // 这里恢复动画。
                        .animateItemPlacement(tween(220, easing = FastOutSlowInEasing))
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
                    PhotoCard(photo, showNames, thumbPainter, cardCornerRadius)
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
                } // PhotoRow.Photo
              } // when
            } // items(photoRows)
            if (hasMore) {
                // 同上：底部「加载更多」那一行也要跨满当前整行，用 maxLineSpan
                item(span = { GridItemSpan(maxLineSpan) }, key = "load_more_footer") {
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
    painter: androidx.compose.ui.graphics.painter.Painter,
    cornerRadius: Dp = 14.dp
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(cornerRadius),
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderList(
    folders: List<FolderEntry>,
    createdFolders: Set<String>,
    crownEvents: Flow<Float>,
    isRound: Boolean,
    listState: LazyListState,
    onOpen: (FolderEntry) -> Unit,
    onCreateFolder: () -> Unit,
    onRequestDelete: (FolderEntry) -> Unit
) {
    if (folders.isEmpty()) {
        // 空列表也要能创建文件夹：给一个带「+」的入口
        Box(Modifier.fillMaxSize()) {
            FolderHint("没有可显示的文件夹\n可能都在设置里被隐藏了")
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    .size(40.dp).clip(CircleShape)
                    .clickable(onClick = onCreateFolder),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add, "创建文件夹",
                    modifier = Modifier.size(28.dp), tint = AppColors.Primary
                )
            }
        }
        return
    }
    ScalingScrollColumn(crownEvents = crownEvents, listState = listState) {
        item(key = "folder_header") {
            Box(
                Modifier.fillMaxWidth().height(36.dp).scalingItem("folder_header", scale = false)
            ) {
                // 标题右侧「+」：比正文略大，点击创建文件夹
                val addButton: @Composable () -> Unit = {
                    // 纯加号按钮，颜色用应用主题紫
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onCreateFolder),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add, "创建文件夹",
                            modifier = Modifier.size(20.dp),
                            tint = AppColors.Primary
                        )
                    }
                }
                if (isRound) {
                    // 圆屏：标题与「+」一起居中，避免贴到右弧边被表框切掉
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "文件夹 · ${folders.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        addButton()
                    }
                } else {
                    // 方屏：标题居中、「+」仍在右上角
                    Text(
                        "文件夹 · ${folders.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) { addButton() }
                }
            }
        }
        folders.forEach { folder ->
            item(key = "folder_${folder.path}") {
                FolderRow(folder, folder.path in createdFolders, onOpen, onRequestDelete)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderEntry,
    deletable: Boolean,
    onOpen: (FolderEntry) -> Unit,
    onRequestDelete: (FolderEntry) -> Unit
) {
    val itemKey = "folder_${folder.path}"
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp).scalingItem(itemKey)
            .combinedClickable(
                onClick = { onOpen(folder) },
                onLongClick = if (deletable) ({ onRequestDelete(folder) }) else null
            ),
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
