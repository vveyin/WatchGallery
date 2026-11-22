package com.watchgallery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchgallery.app.data.Photo
import com.watchgallery.app.data.TrashRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 最近删除（回收站）页面。
 *
 * 复用相册的 [ScalingPhotoList] 整套手势（长按多选 / 滑动框选 / 居中放大 / 浮现缓动），
 * 只是把选中状态放在本页局部；多选栏除了「删除」（彻底删除）还多一颗「恢复」按钮。
 */
@Composable
fun TrashScreen(
    viewModel: GalleryViewModel,
    isRound: Boolean,
    crownEvents: Flow<Float> = emptyFlow(),
    animateGifs: Boolean = true,
    onBack: () -> Unit = {},
    /** 单击照片（或多选态点照片本体）打开大图；回传当前这张与整个回收站列表 */
    onOpenPhoto: (photo: Photo, all: List<Photo>) -> Unit = { _, _ -> }
) {
    val photos by viewModel.trashPhotos.collectAsState()
    val keepDays by viewModel.keepDays.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()

    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun enterSelection(uri: String) {
        selectionMode = true
        selected = setOf(uri)
    }
    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }
    fun toggle(uri: String) {
        selected = if (uri in selected) selected - uri else selected + uri
    }
    fun addRange(uris: Collection<String>) { selected = selected + uris }
    fun removeRange(uris: Collection<String>) { selected = selected - uris.toSet() }

    fun doRestore() {
        val targets = photos.filter { it.uri.toString() in selected }
        if (targets.isEmpty()) return
        // 逐张恢复到原目录
        targets.forEach { viewModel.restoreTrash(it) }
        exitSelection()
    }
    fun doDelete() {
        val targets = photos.filter { it.uri.toString() in selected }
        if (targets.isEmpty()) return
        viewModel.deleteTrashForever(targets)
        exitSelection()
    }

    // 背景透明：模糊背景下层由抽屉的毛玻璃背板提供（关掉模糊时 ScreenLayer 垫纯黑）。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部标题 ──
            Box(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("最近删除", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }

            // ── 保留时长选择（1天 / 7天 / 30天 / 永久）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("保留", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TrashRepository.KEEP_OPTIONS.forEach { days ->
                    val active = keepDays == days
                    val label = if (days == TrashRepository.KEEP_FOREVER) "永久" else "${days}天"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { viewModel.setKeepDays(days) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 11.sp,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 照片网格 ──
            Box(Modifier.fillMaxSize().weight(1f)) {
                if (photos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("最近删除是空的", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ScalingPhotoList(
                        items = photos,
                        columns = gridColumns,
                        crownEvents = crownEvents,
                        animateGifs = animateGifs,
                        showNames = false,
                        hasMore = false,
                        totalCount = photos.size,
                        selectionMode = selectionMode,
                        selectedUris = selected,
                        centerScale = isRound,
                        onLoadMore = {},
                        // 单击打开大图看内容（不再进多选）
                        onPhotoClick = { photo -> onOpenPhoto(photo, photos) },
                        // 记录被点缩略图的位置与已解码 painter，供大图页做同款放大入场动画
                        onRecordOrigin = { rect, painter ->
                            viewModel.viewerOrigin = rect
                            viewModel.viewerThumb = painter
                        },
                        // 多选态下点照片本体也打开大图
                        onOpenPhoto = { uri ->
                            photos.firstOrNull { p -> p.uri.toString() == uri }
                                ?.let { onOpenPhoto(it, photos) }
                        },
                        onLongPress = { enterSelection(it) },
                        onToggle = { toggle(it) },
                        onSelectRange = { addRange(it) },
                        onDeselectRange = { removeRange(it) },
                        onCancelSelection = { exitSelection() },
                        onDeleteSelected = { doDelete() }
                    )
                }

                // ── 多选栏：删除 + 恢复（仅图标）+ 取消 ──
                if (selectionMode) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                RoundedCornerShape(24.dp)
                            )
                            .border(
                                1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("已选 ${selected.size} 张", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                        // 恢复（只有图标，没有文字）
                        IconButton(
                            onClick = { doRestore() },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Restore,
                                contentDescription = "恢复",
                                tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // 彻底删除（只有图标）
                        IconButton(
                            onClick = { doDelete() },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "彻底删除",
                                tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // 取消
                        IconButton(
                            onClick = { exitSelection() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
