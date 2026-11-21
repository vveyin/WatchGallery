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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
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
            else -> ScalingPhotoList(
                items = photos,
                columns = gridColumns,
                crownEvents = crownEvents,
                showNames = showPhotoNames,
                hasMore = visibleCount < totalCount,
                totalCount = totalCount,
                selectionMode = selectionMode,
                selectedUris = selectedUris,
                onLoadMore = { viewModel.loadMore() },
                onPhotoClick = onPhotoClick,
                onLongPress = { viewModel.enterSelectionMode(it) },
                onToggle = { viewModel.toggleSelected(it) },
                onSelectRange = { viewModel.addSelected(it) },
                onCancelSelection = { viewModel.exitSelectionMode() },
                onDeleteSelected = { viewModel.deleteSelected() }
            )
        }

        // 多选模式：底部操作栏（覆盖在网格之上）
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedUris.size,
                onDelete = { viewModel.deleteSelected() },
                onCancel = { viewModel.exitSelectionMode() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 返回屏幕上某坐标点命中的照片 uri（取第一个包含该点的 cell），未命中返回 null。 */
private fun hitCell(cellRects: Map<String, Rect>, p: Offset): String? =
    cellRects.entries.firstOrNull { (_, r) ->
        p.x in r.left..r.right && p.y in r.top..r.bottom
    }?.key

/**
 * 正方形网格照片列表：每排 [columns] 张，越靠近视口中心的「行」越大、上下淡出。
 * 缩放/透明度在 graphicsLayer 绘制阶段按滚动位置实时计算，不触发重组。
 */
@Composable
private fun ScalingPhotoList(
    items: List<Photo>,
    columns: Int,
    crownEvents: Flow<Float>,
    showNames: Boolean,
    hasMore: Boolean,
    totalCount: Int,
    selectionMode: Boolean,
    selectedUris: Set<String>,
    onLoadMore: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onLongPress: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectRange: (Collection<String>) -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

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

    // 手势开始时读取最新的多选状态。不能拿它当 pointerInput 的 key —— 长按进入多选后
    // 状态翻转会让手势协程重启，后续拖动就收不到了。
    val selectionModeState = rememberUpdatedState(selectionMode)

    // 起点→当前点的矩形覆盖到的照片全部加入选中。
    // 四周加 3px，保证纯横向/纵向划动（矩形某一边为 0）也能命中最外一行/列。
    // gestureHits 记录本次手势已上报过的照片，只上报新增的，避免每个移动事件都重建选中集合。
    val gestureHits = remember { mutableSetOf<String>() }
    fun selectInRect(a: Offset, b: Offset) {
        val pad = 3f
        val r = Rect(
            left = minOf(a.x, b.x) - pad,
            top = minOf(a.y, b.y) - pad,
            right = maxOf(a.x, b.x) + pad,
            bottom = maxOf(a.y, b.y) + pad
        )
        val fresh = ArrayList<String>(4)
        for ((uri, cell) in cellRects) {
            if (cell.overlaps(r) && gestureHits.add(uri)) fresh.add(uri)
        }
        if (fresh.isNotEmpty()) onSelectRange(fresh)
    }

    // 表冠滚动：照搬 f-x，事件到达直接 scrollBy，即时跟手、无阻尼。
    // 关键：必须调用 LazyGridState.scrollBy（内部按像素即时滚动、不触发 overscroll 弹簧），
    // 而不是 gridState.scroll{} 里那套会走 fling/overscroll 的 ScrollScope — 后者滚到
    // 边界时会触发 Compose 的回弹动画，正是「滚动完向反方向弹一小段」的根因。
    CrownScroll(crownEvents) { step ->
        gridState.scrollBy(step)
    }

    // 滑到倒数一排时预加载下一批
    val needMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && last >= items.size - columns
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
                // 本单元格的长按信号：由 detectTapGestures 置位，拖动接续的协程读取
                val longPressed = remember { mutableStateOf(false) }
                // 同一排的所有照片共享一个「排缩放值」，并以屏幕水平中线为锚点统一缩放，
                // 这样整排是连在一起放大/缩小的，而不是每张照片各自以自身中心缩。
                val rowCenterY = remember { mutableFloatStateOf(-1f) }
                val leftX = remember { mutableFloatStateOf(0f) }
                val cellWidth = remember { mutableFloatStateOf(1f) }
                val measured = remember { mutableStateOf(false) }
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
                            if (measured.value && viewportCenter > 0f) {
                                val distance = kotlin.math.abs(cy - viewportCenter)
                                // 退出半径加大到 1.05×半屏，让顶部渐隐更平缓，不会到边缘突变。
                                val radius = viewportCenter * 1.05f
                                val t = (distance / radius).coerceIn(0f, 1f)
                                val eased = t * t
                                val sc = 1.10f - 0.32f * eased   // 正中 1.10 → 边缘 0.78
                                scaleX = sc
                                scaleY = sc
                                alpha = 1f - 0.55f * t
                                // 锚点统一到屏幕水平中线：同排照片围绕同一条竖线缩放，
                                // 间距随缩放同步变化，视觉上整排连在一起。
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = ((screenWidthPx / 2f) - leftX.floatValue) / cellWidth.floatValue,
                                    pivotFractionY = 0.5f
                                )
                            }
                        }
                        .then(
                            Modifier
                                // ── A：长按成立后的拖动接续 ──
                                // 这里绝不能用 withTimeoutOrNull 自己算长按：超时会取消指针协程，
                                // Compose 的事件分发会卡住（v0.26 的卡死崩溃就是这么来的）。
                                // 改为「只观察、不消费」，等 detectTapGestures 的 onLongPress 置位后再接管。
                                .pointerInput(photo.uri.toString()) {
                                    awaitEachGesture {
                                        // 已在多选模式：交给覆盖层统一处理
                                        if (selectionModeState.value) return@awaitEachGesture
                                        longPressed.value = false
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        gestureHits.clear()
                                        var lastChange = down
                                        // 长按前只观察：不消费，滚动/单击都不受影响
                                        while (!longPressed.value) {
                                            val e = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = e.changes.firstOrNull { it.id == down.id }
                                                ?: return@awaitEachGesture
                                            if (!c.pressed) return@awaitEachGesture
                                            lastChange = c
                                        }
                                        // 长按已成立：从这里开始接管本次手势，任意方向都做矩形选取
                                        // 若是「长按后立刻松手」，lastChange 已经是抬起事件，直接结束
                                        if (!lastChange.pressed) return@awaitEachGesture
                                        val anchor = down.position
                                        lastChange.consume()
                                        selectInRect(anchor, lastChange.position)
                                        while (true) {
                                            val e = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                            c.consume()
                                            if (!c.pressed) break
                                            selectInRect(anchor, c.position)
                                        }
                                    }
                                }
                                // ── B：单击打开大图 / 长按进入多选 ──
                                .pointerInput(photo.uri.toString()) {
                                    detectTapGestures(
                                        onLongPress = {
                                            longPressed.value = true
                                            onLongPress(photo.uri.toString())
                                        },
                                        onTap = { onPhotoClick(photo) }
                                    )
                                }
                        )
                ) {
                    PhotoCard(photo, showNames)
                    if (selectionMode) {
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
        //  - 纵向滑动（|dy| ≥ |dx|）= 滚动图片列表；
        //  - 横向锁定后不松手再纵向滑动，仍只是扩大选取矩形，不会切成滚动；
        //  - 完全没移动的轻点 = 切换起点照片的选中状态。
        // 全程不画任何可见框。
        if (selectionMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            gestureHits.clear()
                            val anchor = down.position
                            var last = down.position
                            // 0=未判定, 1=纵向滚动, 2=横向选取
                            var mode = 0
                            var tapped = true

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.all { !it.pressed }) break
                                val change = event.changes.first()
                                change.consume()
                                val cur = change.position

                                val dx = cur.x - anchor.x
                                val dy = cur.y - anchor.y
                                if ((cur - anchor).getDistance() > 8f) tapped = false

                                if (mode == 0) {
                                    // 离开起点 20px 后按 45° 分界判定方向，之后整个手势不再改变
                                    if (maxOf(abs(dx), abs(dy)) > SELECT_DIRECTION_SLOP) {
                                        mode = if (abs(dx) > abs(dy)) 2 else 1
                                        if (mode == 1) {
                                            // 补上判定前已移动的纵向距离，起手不丢位移
                                            selectionScroll.tryEmit(-(cur.y - anchor.y))
                                        } else {
                                            selectInRect(anchor, cur)
                                        }
                                    }
                                } else if (mode == 1) {
                                    // 纵向：滚动列表（手指往下拖 → 内容往前，故取负）
                                    selectionScroll.tryEmit(-(cur.y - last.y))
                                } else {
                                    // 横向：选取；不松手继续纵向滑动也只是扩大矩形范围
                                    selectInRect(anchor, cur)
                                }
                                last = cur
                            }
                            // 抬起：完全没移动视为轻点 —— 切换起点照片的选中状态
                            if (tapped) {
                                hitCell(cellRects, down.position)?.let { onToggle(it) }
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun PhotoCard(photo: Photo, showName: Boolean) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = rememberAsyncImagePainter(photo.uri),
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

/** 照片右上角的复选圈：选中实心对勾，未选中空心圈 */
@Composable
private fun BoxScope.SelectionCheck(checked: Boolean) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(5.dp)
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
