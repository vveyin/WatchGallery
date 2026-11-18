package com.watchgallery.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * 相册主界面 —— 全屏缩放列表（Wear OS 风格）。
 *
 * 不再套圆屏矩形安全区：列表占满整屏，靠「中间大、两边小并淡出」的缩放效果
 * 自然避开表盘弧边，这就是 Wear OS 原生 ScalingLazyColumn 的观感。
 */
@OptIn(ExperimentalFoundationApi::class)
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
                crownEvents = crownEvents,
                hasMore = visibleCount < totalCount,
                totalCount = totalCount,
                onLoadMore = { viewModel.loadMore() },
                onPhotoClick = onPhotoClick
            )
        }

        // 顶部居中工具条：圆屏顶部中央是可视的，放三个小圆按钮
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TopIcon(Icons.Filled.AddPhotoAlternate, "导入") { launchImport() }
            TopIcon(Icons.Filled.SystemUpdate, "检查更新") { onCheckUpdate() }
            TopIcon(Icons.Filled.Settings, "设置") { onOpenSettings() }
        }
    }
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp))
    }
}

/**
 * Wear OS 风格的缩放列表：列表项越靠近屏幕中心越大、越靠边缘越小并淡出。
 * 缩放/透明度在 graphicsLayer 的绘制阶段按滚动位置实时计算，不触发重组。
 */
@Composable
private fun ScalingPhotoList(
    items: List<Photo>,
    crownEvents: Flow<Float>,
    hasMore: Boolean,
    totalCount: Int,
    onLoadMore: () -> Unit,
    onPhotoClick: (Photo) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            listState.scroll { scrollBy(-delta * 9f) }
        }
    }

    // 从 layoutInfo 派生：滚动时可见项位置变化会更新这个状态，进而触发重组，
    // 让每个卡片的缩放/透明度跟着滚动位置实时变化（Wear OS ScalingLazyColumn 观感）。
    val layout by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val centers = info.visibleItemsInfo.associate { it.index to (it.offset + it.size / 2f) }
            Pair(centers, info.viewportSize.height / 2f)
        }
    }

    // 滑到倒数第二张时预加载下一批
    val needMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMore && last >= items.size - 3
        }
    }
    LaunchedEffect(needMore) {
        if (needMore) onLoadMore()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 让首尾项也能滚到视口中央
        val itemHeight = 112.dp
        val edgePad = ((maxHeight - itemHeight) / 2).coerceAtLeast(20.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = edgePad)
        ) {
            if (hasMore) {
                item(key = "load_more_footer") {
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
            itemsIndexed(items, key = { _, p -> p.id }) { index, photo ->
                val itemCenter = layout.first[index]
                val viewportCenter = layout.second
                val t = if (itemCenter != null && viewportCenter > 0f) {
                    (kotlin.math.abs(itemCenter - viewportCenter) /
                            (viewportCenter * 1.15f)).coerceIn(0f, 1f)
                } else 0f
                val s = 1f - 0.42f * t      // 中间 1.0 → 边缘 0.58
                val a = 1f - 0.72f * t      // 中间 1.0 → 边缘 0.28
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            scaleX = s
                            scaleY = s
                            alpha = a
                        }
                        .clickable { onPhotoClick(photo) }
                ) {
                    PhotoCard(photo)
                }
            }
        }
    }
}

@Composable
private fun PhotoCard(photo: Photo) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    photo.displayName,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
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
