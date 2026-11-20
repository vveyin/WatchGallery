package com.watchgallery.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.watchgallery.app.data.Photo

/**
 * 大图查看器：双指缩放、单指平移、双击复位、左右切换、表冠缩放（通过 crownEvents 事件流）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photos: List<Photo>,
    initialIndex: Int,
    isRound: Boolean,
    crownEvents: kotlinx.coroutines.flow.Flow<Float> = kotlinx.coroutines.flow.emptyFlow(),
    onBack: () -> Unit,
    onEdit: (Photo) -> Unit
) {
    var index by remember { mutableIntStateOf(initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }

    val photo = photos.getOrNull(index)
    var showInfo by remember { mutableStateOf(false) }

    // 表冠缩放：逐条消费增量事件（避免累加值导致缩放失控）
    LaunchedEffect(crownEvents) {
        crownEvents.collect { delta ->
            scale = (scale + delta * 0.01f).coerceIn(1f, 6f)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 圆屏上左右两端是弧边，控件宽度按屏宽 0.66 收窄，保证完整可见
        val safeWidth = maxWidth * 0.66f
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
                        rotationZ = rotation
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rot ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-size.width * (scale - 1), size.width * (scale - 1)),
                                (offset.y + pan.y).coerceIn(-size.height * (scale - 1), size.height * (scale - 1))
                            )
                            rotation += rot
                        }
                    }
                    .combinedClickable(
                        onClick = { showInfo = !showInfo },
                        onDoubleClick = { scale = 1f; offset = Offset.Zero; rotation = 0f }
                    )
            )

            // 顶部工具条（限制在圆屏安全区内，避免左右上角被弧边裁掉）
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = safeWidth)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 返回改为边缘右划手势，顶部只留缩放按钮并居中
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { scale = (scale + 0.3f).coerceAtMost(6f) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.ZoomIn, "放大", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { scale = (scale - 0.3f).coerceAtLeast(1f) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.ZoomOut, "缩小", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 底部：图片信息 + 编辑 + 切换
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = safeWidth)
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showInfo) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "${photo.displayName}\n${photo.width}×${photo.height} · ${photo.bucketName}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { index = (index - 1 + photos.size) % photos.size; scale = 1f; offset = Offset.Zero },
                        enabled = photos.size > 1,
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Filled.ArrowBack, "上一张", modifier = Modifier.size(16.dp)) }
                    FloatingActionButton(
                        onClick = { onEdit(photo) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Filled.Edit, "编辑", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    FilledTonalIconButton(
                        onClick = { index = (index + 1) % photos.size; scale = 1f; offset = Offset.Zero },
                        enabled = photos.size > 1,
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Filled.ArrowForward, "下一张", modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}
