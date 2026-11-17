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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.watchgallery.app.data.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 按系统版本取正确的媒体读取权限 */
private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * 相册主界面：顶部标题 + 主题切换 + 图片网格 + 旋转表冠翻页。
 *
 * 权限：每次回到前台都重新探测（覆盖安装 / ROM 更新后可能重置权限，
 * 只在 remember 里查一次会出现"更新后读不到照片且无法再授权"）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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

    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    val albums by viewModel.albums.collectAsState()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    // 系统已记住"拒绝且不再询问"时，请求权限不会弹窗，只能引导去设置
    var needSettings by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            needSettings = false
            viewModel.refresh()
        } else {
            // 拒绝后判断是否还能再次弹窗
            val act = context as? Activity
            needSettings = act != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(act, mediaPermission())
        }
    }

    // 每次回到前台重新探测权限（从系统设置授权后返回、覆盖安装后权限变化）
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

    if (!permissionGranted) {
        RoundSafeArea(
            isRound = isRound,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            PermissionScreen(
                needSettings = needSettings,
                onRequest = { launcher.launch(mediaPermission()) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }
        return
    }

    val displayed = remember(photos, selectedAlbum) {
        if (selectedAlbum == null) photos else photos.filter { it.bucketName == selectedAlbum }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    // 表冠滚动相册网格：事件流按到达顺序逐条消费，转多少滚多少
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            gridState.scroll { scrollBy(-delta * 6f) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏（放进圆屏安全区，避免被表盘弧边裁切）
            RoundSafeArea(
                isRound = isRound,
                vertical = if (isRound) 14.dp else 10.dp,
                horizontal = if (isRound) 20.dp else 14.dp
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (selectedAlbum == null) "相册" else selectedAlbum ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 相册分组筛选
                    IconButton(
                        onClick = { selectedAlbum = if (selectedAlbum == null) albums.firstOrNull()?.name else null },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = "相册分组",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    // 检查更新
                    IconButton(onClick = onCheckUpdate, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = "检查更新",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    // 设置入口
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            } // end RoundSafeArea

            when {
                isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                displayed.isEmpty() -> EmptyGallery(onRefresh = { viewModel.refresh() })
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isRound) 2 else 3),
                        state = gridState,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayed, key = { it.id }) { photo ->
                            PhotoThumb(photo, isRound) { onPhotoClick(photo) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoThumb(photo: Photo, isRound: Boolean, onClick: () -> Unit) {
    val shape = if (isRound) RoundedCornerShape(16.dp) else RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape)
            .combinedClickable(onClick = onClick)
    ) {
        Image(
            painter = rememberAsyncImagePainter(photo.uri),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 视频/格式角标占位（可扩展）
    }
}

@Composable
private fun EmptyGallery(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "没有找到照片\n可拷贝照片到手表后再刷新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("重新扫描")
        }
    }
}

@Composable
private fun PermissionScreen(
    needSettings: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.PhotoLibrary, null,
            modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("需要照片权限", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (needSettings) "权限已被拒绝，请到系统设置里开启「照片/存储」权限"
            else "用于浏览和编辑手表上的照片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = if (needSettings) onOpenSettings else onRequest,
            modifier = Modifier.fillMaxWidth(0.85f)) {
            Text(if (needSettings) "去设置开启" else "授予权限")
        }
        if (!needSettings) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenSettings) { Text("打不开？去系统设置") }
        }
    }
}
