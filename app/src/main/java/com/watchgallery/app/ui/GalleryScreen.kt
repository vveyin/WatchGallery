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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** 按系统版本取正确的媒体读取权限 */
private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * 相册主界面：标题栏 + 图片网格 + 表冠翻页。
 *
 * 布局要点（0.3 修复）：整屏包在 RoundSafeArea 里。
 * 之前只有标题栏包了安全区，而安全区本身 fillMaxSize —— 放在 Column 里会把
 * 整个剩余高度吃掉，网格高度被压成 0，看起来就是"照片列表是空的"。
 * 现在根节点统一安全区，内部 Column 正常分配：标题 wrap content，网格 weight(1f)。
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
    val report by viewModel.scanReport.collectAsState()

    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    val albums by viewModel.albums.collectAsState()

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

    // 手动导入：系统文件选择器多选图片，内容复制进应用私有目录
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importPhotos(uris) }

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
                onRequest = { permissionLauncher.launch(mediaPermission()) },
                onOpenSettings = { openAppSettings(context) }
            )
        }
        return
    }

    val displayed = remember(photos, selectedAlbum) {
        if (selectedAlbum == null) photos else photos.filter { it.bucketName == selectedAlbum }
    }

    val gridState = rememberLazyGridState()

    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            gridState.scroll { scrollBy(-delta * 5f) }
        }
    }

    RoundSafeArea(
        isRound = isRound,
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        horizontal = if (isRound) 4.dp else 12.dp,
        vertical = if (isRound) 4.dp else 10.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            GalleryHeader(
                title = selectedAlbum ?: "相册",
                hasAlbums = albums.size > 1,
                groupingOn = selectedAlbum != null,
                onToggleGroup = {
                    selectedAlbum = if (selectedAlbum == null) albums.firstOrNull()?.name else null
                },
                onOpenSettings = onOpenSettings,
                onCheckUpdate = onCheckUpdate
            )
            Spacer(Modifier.height(4.dp))

            when {
                isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp)
                    )
                }
                displayed.isEmpty() -> EmptyGallery(
                    report = report.summary,
                    hint = hint,
                    onRefresh = { viewModel.refresh() },
                    onImport = {
                        runCatching { importLauncher.launch(arrayOf("image/*")) }
                            .onFailure {
                                hint = "系统没有文件选择器，可用数据线把照片放到 Pictures 目录后点重新扫描"
                            }
                    }
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(if (isRound) RoundedCornerShape(14.dp) else RoundedCornerShape(8.dp)),
                    contentPadding = PaddingValues(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(displayed, key = { it.id }) { photo ->
                        PhotoThumb(photo) { onPhotoClick(photo) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryHeader(
    title: String,
    hasAlbums: Boolean,
    groupingOn: Boolean,
    onToggleGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (hasAlbums) {
            HeaderIcon(
                icon = if (groupingOn) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                desc = "相册分组",
                onClick = onToggleGroup
            )
        }
        Box {
            HeaderIcon(
                icon = Icons.Filled.MoreVert,
                desc = "更多",
                onClick = { menuOpen = true }
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("设置", fontSize = 13.sp) },
                    onClick = { menuOpen = false; onOpenSettings() }
                )
                DropdownMenuItem(
                    text = { Text("检查更新", fontSize = 13.sp) },
                    onClick = { menuOpen = false; onCheckUpdate() }
                )
            }
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoThumb(photo: Photo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)
            .combinedClickable(onClick = onClick)
    ) {
        Image(
            painter = rememberAsyncImagePainter(photo.uri),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun EmptyGallery(
    report: String,
    hint: String?,
    onRefresh: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "没有找到照片",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            report,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
        hint?.let {
            Spacer(Modifier.height(3.dp))
            Text(
                it,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("重新扫描", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onImport,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("导入", fontSize = 11.sp)
            }
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
        Icon(
            Icons.Filled.PhotoLibrary, null,
            modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "需要照片权限",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (needSettings) "权限已被拒绝，请到系统设置里开启「照片/存储」权限"
            else "用于浏览和编辑手表上的照片",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = if (needSettings) onOpenSettings else onRequest,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp)
        ) {
            Text(if (needSettings) "去设置开启" else "授予权限", fontSize = 12.sp)
        }
        if (!needSettings) {
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(2.dp)) {
                Text("打不开？去系统设置", fontSize = 10.sp)
            }
        }
    }
}
