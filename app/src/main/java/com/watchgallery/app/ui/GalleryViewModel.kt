package com.watchgallery.app.ui

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchgallery.app.data.Album
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.FolderEntry
import com.watchgallery.app.data.MediaRepository
import com.watchgallery.app.data.Photo
import com.watchgallery.app.data.ScreenShape
import com.watchgallery.app.data.TrashRepository
import com.watchgallery.app.data.buildFolders
import com.watchgallery.app.data.isFolderHidden
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.ImageEditor
import com.watchgallery.app.edit.Stroke
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.ImageSaver
import com.watchgallery.app.util.ReleaseInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 照片列表的两种浏览方式 */
enum class GalleryViewMode { FLAT, FOLDER }

/** 照片排序 / 分组方式：按天、按月、按年、不排序（默认按天） */
enum class PhotoSortMode {
    DAY, MONTH, YEAR, NONE;

    companion object {
        fun fromKey(key: String?): PhotoSortMode = when (key) {
            "month" -> MONTH
            "year" -> YEAR
            "none" -> NONE
            else -> DAY
        }
        fun key(mode: PhotoSortMode): String = when (mode) {
            DAY -> "day"
            MONTH -> "month"
            YEAR -> "year"
            NONE -> "none"
        }
    }
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val trashRepo = TrashRepository(app)
    private val prefs = app.getSharedPreferences("watch_gallery", Context.MODE_PRIVATE)

    /** 完整扫描结果（未过滤），大图翻页与文件夹树都从这里派生 */
    private val _allPhotos = MutableStateFlow<List<Photo>>(emptyList())
    val allPhotosFlow: StateFlow<List<Photo>> = _allPhotos

    /** 每页条数 */
    private val pageSize = 30

    /** 当前列表已显示的条数：先显示前 30 张，滑到底再追加 */
    private val _visibleCount = MutableStateFlow(pageSize)
    val visibleCount: StateFlow<Int> = _visibleCount

    // ── 浏览方式：平铺 / 文件夹 ──
    private val _viewMode = MutableStateFlow(
        if (prefs.getString("view_mode", "flat") == "folder") GalleryViewMode.FOLDER
        else GalleryViewMode.FLAT
    )
    val viewMode: StateFlow<GalleryViewMode> = _viewMode

    fun setViewMode(mode: GalleryViewMode) {
        _viewMode.value = mode
        prefs.edit().putString("view_mode", if (mode == GalleryViewMode.FOLDER) "folder" else "flat").apply()
    }

    fun toggleViewMode() {
        setViewMode(if (_viewMode.value == GalleryViewMode.FLAT) GalleryViewMode.FOLDER else GalleryViewMode.FLAT)
    }

    // ── 隐藏文件夹 ──
    /** 被隐藏的文件夹完整路径；隐藏父目录时子目录一起隐藏 */
    private val _hiddenFolders = MutableStateFlow(
        prefs.getStringSet("hidden_folders", emptySet())?.toSet() ?: emptySet()
    )
    val hiddenFolders: StateFlow<Set<String>> = _hiddenFolders

    fun setFolderHidden(path: String, hidden: Boolean) {
        val cur = _hiddenFolders.value
        val next = if (hidden) cur + path else cur - path
        if (next == cur) return
        _hiddenFolders.value = next
        prefs.edit().putStringSet("hidden_folders", HashSet(next)).apply()
    }

    fun toggleFolderHidden(path: String) {
        setFolderHidden(path, path !in _hiddenFolders.value)
    }

    // ── 软删除：被移入最近删除的照片原图 uri（原图仍在 MediaStore，仅从相册隐藏）──
    /**
     * 软删除的照片原图 uri 集合。这些照片的 MediaStore 记录从未被删除，
     * 只在相册列表里隐藏；恢复 = 从这里移除，彻底删除 = 这时才真正调系统删除。
     * 用 uri 字符串做 key（MediaStore 的 content uri 含稳定 id，导入图是稳定的 file uri）。
     */
    private val _hiddenPhotoUris = MutableStateFlow(
        prefs.getStringSet("hidden_photo_uris", emptySet())?.toSet() ?: emptySet()
    )
    val hiddenPhotoUris: StateFlow<Set<String>> = _hiddenPhotoUris

    private fun setHiddenPhotoUris(uris: Set<String>) {
        if (_hiddenPhotoUris.value == uris) return
        _hiddenPhotoUris.value = uris
        prefs.edit().putStringSet("hidden_photo_uris", HashSet(uris)).apply()
    }

    // ── 应用内创建的文件夹 ──
    /**
     * 应用内创建的文件夹相对路径集合。文件夹视图只从「有照片的目录」派生条目，
     * 空目录不会出现；这里单独记录应用创建的空文件夹，合并进列表，且只有这些
     * 文件夹允许长按删除（系统自带的有照片目录不允许删）。
     */
    private val _createdFolders = MutableStateFlow(
        prefs.getStringSet("created_folders", emptySet())?.toSet() ?: emptySet()
    )
    val createdFolders: StateFlow<Set<String>> = _createdFolders

    /** 创建一个空文件夹；返回其相对路径，重名或非法名返回 null */
    fun createFolder(name: String): String? {
        val path = repo.createUserFolderDir(name) ?: return null
        if (path in _createdFolders.value || path in allFolders.value.map { it.path }) return null
        val next = _createdFolders.value + path
        _createdFolders.value = next
        prefs.edit().putStringSet("created_folders", HashSet(next)).apply()
        return path
    }

    /** 仅删除应用内创建的文件夹（系统自带目录不允许删） */
    fun deleteCreatedFolder(path: String) {
        if (path !in _createdFolders.value) return
        val next = _createdFolders.value - path
        _createdFolders.value = next
        prefs.edit().putStringSet("created_folders", HashSet(next)).apply()
        repo.deleteUserFolderDir(path)
        if (_openFolder.value == path) _openFolder.value = null
    }

    /** 某个文件夹是否是应用内创建的（可删除） */
    fun isCreatedFolder(path: String): Boolean = path in _createdFolders.value

    /** 隐藏过滤后的全部照片：两种浏览方式、大图翻页、文件夹树都基于它
     *  （软删除的照片原图仍在扫描结果里，这里按 uri 一并隐藏） */
    private val visiblePhotos: StateFlow<List<Photo>> =
        combine(_allPhotos, _hiddenFolders, _hiddenPhotoUris) { list, hidden, hiddenUris ->
            var out = list
            if (hidden.isNotEmpty()) out = out.filter { !isFolderHidden(it.folderPath, hidden) }
            if (hiddenUris.isNotEmpty()) out = out.filter { it.uri.toString() !in hiddenUris }
            out
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 列表可见的照片（分页切片，平铺模式用） */
    val photos: StateFlow<List<Photo>> =
        combine(visiblePhotos, _visibleCount) { list, count ->
            if (list.size <= count) list else list.take(count)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 照片总数（已过滤隐藏文件夹） */
    val totalCountFlow: StateFlow<Int> =
        visiblePhotos.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 文件夹视图：含「直接有图片」的文件夹 + 应用内创建的空文件夹（已过滤隐藏） */
    val folders: StateFlow<List<FolderEntry>> =
        combine(visiblePhotos, _createdFolders) { list, created ->
            val base = buildFolders(list)
            val photoPaths = base.map { it.path }.toSet()
            // 应用创建的空文件夹按路径补条目；有照片时以照片派生条目为准（带封面/张数）
            val extras = created.filter { it !in photoPaths && !isFolderHidden(it, _hiddenFolders.value) }
                .map { path ->
                    val segs = path.split('/').filter { it.isNotBlank() }
                    FolderEntry(
                        path = path,
                        name = segs.lastOrNull() ?: path,
                        parentPath = segs.dropLast(1).joinToString("/"),
                        photos = emptyList()
                    )
                }
            (base + extras).sortedWith(compareByDescending<FolderEntry> { it.count }.thenBy { it.path })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 所有识别到有图片的文件夹（包含已被隐藏的，供设置里取消隐藏） */
    val allFolders: StateFlow<List<FolderEntry>> =
        _allPhotos.map { buildFolders(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 当前打开的文件夹 ──
    private val _openFolder = MutableStateFlow<String?>(null)
    val openFolder: StateFlow<String?> = _openFolder

    fun openFolder(path: String) {
        if (_selectionMode.value) exitSelectionMode()
        _openFolder.value = path
    }

    fun closeFolder() {
        if (_selectionMode.value) exitSelectionMode()
        _openFolder.value = null
    }

    /** 当前打开的文件夹里的照片（直接属于该目录，不含子目录） */
    val folderPhotos: StateFlow<List<Photo>> =
        combine(visiblePhotos, _openFolder) { list, path ->
            if (path == null) emptyList() else list.filter { it.folderPath == path }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 大图翻页用的列表：在文件夹里就翻该文件夹，否则翻全部 */
    val viewerPhotos: StateFlow<List<Photo>> =
        combine(visiblePhotos, _openFolder) { list, path ->
            if (path == null) list else list.filter { it.folderPath == path }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _themeMode = MutableStateFlow(
        when (prefs.getString("theme", "dark")) {
            "light" -> ThemeMode.LIGHT
            "system" -> ThemeMode.SYSTEM
            else -> ThemeMode.DARK
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    /** 列表每排显示张数（1~10），默认 2；设置里滑动条或双指捏合都会写回，存 SharedPreferences 持久化 */
    private val _gridColumns = MutableStateFlow(
        prefs.getInt("grid_columns", 2).coerceIn(1, 10)
    )
    val gridColumns: StateFlow<Int> = _gridColumns

    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(1, 10)
        if (_gridColumns.value == c) return
        _gridColumns.value = c
        prefs.edit().putInt("grid_columns", c).apply()
    }

    /** 图片排序 / 分组方式，默认按天；存 SharedPreferences 持久化 */
    private val _sortMode = MutableStateFlow(
        PhotoSortMode.fromKey(prefs.getString("sort_mode", "day"))
    )
    val sortMode: StateFlow<PhotoSortMode> = _sortMode

    fun setSortMode(mode: PhotoSortMode) {
        _sortMode.value = mode
        prefs.edit().putString("sort_mode", PhotoSortMode.key(mode)).apply()
    }

    /** 图片列表是否显示图片名，默认显示；设置页可关 */
    private val _showPhotoNames = MutableStateFlow(prefs.getBoolean("show_names", true))
    val showPhotoNames: StateFlow<Boolean> = _showPhotoNames

    fun setShowPhotoNames(show: Boolean) {
        _showPhotoNames.value = show
        prefs.edit().putBoolean("show_names", show).apply()
    }

    /** 查看大图时是否显示顶部的放大/缩小按键，默认显示；设置页可关（关掉后仍可双指/表冠缩放） */
    private val _showZoomButtons = MutableStateFlow(prefs.getBoolean("show_zoom_buttons", true))
    val showZoomButtons: StateFlow<Boolean> = _showZoomButtons

    fun setShowZoomButtons(show: Boolean) {
        _showZoomButtons.value = show
        prefs.edit().putBoolean("show_zoom_buttons", show).apply()
    }

    /**
     * 抽屉毛玻璃背景开关，默认开。
     * 关了之后设置 / 大图 / 编辑三处抽屉都退回纯色背板 —— 老设备 GPU 吃力时可以关掉省电。
     */
    private val _blurBackdrop = MutableStateFlow(prefs.getBoolean("blur_backdrop", true))
    val blurBackdrop: StateFlow<Boolean> = _blurBackdrop

    fun setBlurBackdrop(on: Boolean) {
        _blurBackdrop.value = on
        prefs.edit().putBoolean("blur_backdrop", on).apply()
    }

    /**
     * 图片列表里的 GIF 要不要动。
     *
     * 只影响**列表**：点开看大图时 GIF 一律按动图播，不受这个开关约束 ——
     * 用户要的是"列表里安静一点，点开还是动的"。
     */
    private val _animateGifs = MutableStateFlow(prefs.getBoolean("animate_gifs", true))
    val animateGifs: StateFlow<Boolean> = _animateGifs

    fun setAnimateGifs(on: Boolean) {
        _animateGifs.value = on
        prefs.edit().putBoolean("animate_gifs", on).apply()
    }

    /**
     * 屏蔽系统手势（仅 Android 10+）。
     *
     * 开启后由 MainActivity 把整个窗口登记成「系统手势排除区」：边缘滑动不再被系统
     * 拿去当返回 / 退出手势，全部交给应用自己的右划返回和抽屉拖动。
     * 低版本没有这套 API，设置里也不会显示这一项。
     */
    private val _blockGestures = MutableStateFlow(prefs.getBoolean("block_gestures", false))
    val blockGestures: StateFlow<Boolean> = _blockGestures

    fun setBlockGestures(on: Boolean) {
        _blockGestures.value = on
        prefs.edit().putBoolean("block_gestures", on).apply()
    }

    /**
     * 使用系统返回手势：开启后由应用接管系统的返回事件（BackHandler）。
     *
     * 背景：部分手表把「屏幕中间右划」识别成系统返回手势，而系统默认行为是直接
     * finish 掉 Activity = 退出软件，用户以为「中间右划退出了 App」。开启本项后，
     * 系统返回统一走应用自己的逐层返回逻辑：大图/编辑/更新 → 上一层，设置二级页 → 一级页，
     * 栈底（相册）才放行给系统退出。默认开启。
     */
    private val _useSystemBack = MutableStateFlow(prefs.getBoolean("use_system_back", true))
    val useSystemBack: StateFlow<Boolean> = _useSystemBack

    fun setUseSystemBack(on: Boolean) {
        _useSystemBack.value = on
        prefs.edit().putBoolean("use_system_back", on).apply()
    }

    /**
     * 屏幕形状：自动 / 圆屏 / 方屏。
     *
     * 系统 `SCREENLAYOUT_ROUND` 在部分设备上不准；而且圆屏那套「列表中间放大、
     * 上下淡出」在方屏上并不好看，所以允许手动指定。选方屏后整屏铺满、不做居中放大。
     */
    private val _screenShape = MutableStateFlow(
        ScreenShape.fromKey(prefs.getString("screen_shape", ScreenShape.AUTO.key))
    )
    val screenShape: StateFlow<ScreenShape> = _screenShape

    fun setScreenShape(shape: ScreenShape) {
        _screenShape.value = shape
        prefs.edit().putString("screen_shape", shape.key).apply()
    }

    /**
     * 最近一次点击的缩略图在屏幕上的矩形（px，相对窗口根布局）。
     * 只用于大图页的入场动画：从缩略图位置放大到全屏。不做状态观察，读一次即可。
     */
    var viewerOrigin: androidx.compose.ui.geometry.Rect? = null

    /**
     * 最近一次点击的那张缩略图 painter（Coil 已经解码在内存里的那张）。
     *
     * 大图页第一帧直接拿它铺满全屏来播入场动画：解码大图要几十到上百毫秒，
     * 若等大图出来再画，用户先看到的是黑屏，动画等于白播。
     * 缩略图先顶上，大图加载完成后再淡入替换，观感就是「点下去立刻放大」。
     */
    var viewerThumb: androidx.compose.ui.graphics.painter.Painter? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /**
     * 删除提示：用关于页那种黑药丸 InAppToast 显示（"已删除 N 张图片"），
     * 与 _message（底部 Snackbar）分开，避免两种提示互相顶掉。
     */
    private val _deleteToast = MutableStateFlow<String?>(null)
    val deleteToast: StateFlow<String?> = _deleteToast
    fun clearDeleteToast() { _deleteToast.value = null }

    // ── 启动自动检查到的新版本 ──
    /** 冷启动联网检测到的新版本；更新页读取后立即清空，避免下次手动进入时重复展示 */
    private val _pendingUpdate = MutableStateFlow<ReleaseInfo?>(null)
    val pendingUpdate: StateFlow<ReleaseInfo?> = _pendingUpdate

    fun setPendingUpdate(info: ReleaseInfo) { _pendingUpdate.value = info }

    fun consumePendingUpdate() { _pendingUpdate.value = null }

    // ── 多选状态 ──
    /** 是否处于多选模式 */
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode

    /** 已选中的照片 uri 集合 */
    private val _selectedUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedUris: StateFlow<Set<String>> = _selectedUris

    fun enterSelectionMode(uri: String) {
        _selectionMode.value = true
        _selectedUris.value = setOf(uri)
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedUris.value = emptySet()
    }

    fun toggleSelected(uri: String) {
        val cur = _selectedUris.value
        _selectedUris.value = if (uri in cur) cur - uri else cur + uri
    }

    fun setSelected(uris: Collection<String>) {
        _selectedUris.value = uris.toSet()
    }

    /** 在已有选择基础上追加（用于滑动框选，不清除之前选中的） */
    fun addSelected(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val cur = _selectedUris.value
        val next = cur + uris
        // 没有真正新增就不要动 state：否则每个移动事件都会触发一次全列表重组
        if (next.size != cur.size) _selectedUris.value = next
    }

    /** 从已选中移除（滑动快捷取消选择用） */
    fun removeSelected(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val cur = _selectedUris.value
        val next = cur - uris.toSet()
        if (next.size != cur.size) _selectedUris.value = next
    }

    // ── 最近删除（回收站）──

    private val _trashPhotos = MutableStateFlow<List<Photo>>(emptyList())
    val trashPhotos: StateFlow<List<Photo>> = _trashPhotos

    private val _keepDays = MutableStateFlow(trashRepo.loadKeepDays())
    val keepDays: StateFlow<Long> = _keepDays

    fun setKeepDays(days: Long) {
        _keepDays.value = days
        trashRepo.saveKeepDays(days)
    }

    /**
     * 从最近删除恢复图片时是否保留原来的拍摄日期，默认开（保持原有行为）。
     * 关掉后恢复的图片日期改为恢复当时的时间。
     */
    private val _restoreKeepOriginalDate = MutableStateFlow(prefs.getBoolean("restore_keep_date", true))
    val restoreKeepOriginalDate: StateFlow<Boolean> = _restoreKeepOriginalDate

    fun setRestoreKeepOriginalDate(on: Boolean) {
        _restoreKeepOriginalDate.value = on
        prefs.edit().putBoolean("restore_keep_date", on).apply()
    }

    /** 进入最近删除页时刷新（顺带按保留时长清理过期项） */
    fun refreshTrash() {
        viewModelScope.launch {
            trashRepo.purgeExpired()
            _trashPhotos.value = trashRepo.listPhotos()
        }
    }

    fun restoreTrash(photo: Photo) {
        viewModelScope.launch {
            val result = trashRepo.restore(photo, _restoreKeepOriginalDate.value)
            _message.value = if (result != null) "已恢复到原目录" else "恢复失败"
            if (result != null) {
                // 软删除项：原图从未删，取消隐藏即可让它原样回到相册（id/uri/日期全保留）
                if (result.wasSoftDelete && result.originalUri.isNotBlank()) {
                    setHiddenPhotoUris(_hiddenPhotoUris.value - result.originalUri)
                }
                refreshTrash()
                refresh()
            }
        }
    }

    /** 批量恢复：单次串行处理 + 一次刷新，避免多协程并发覆盖导致底部几张恢复不掉 */
    fun restoreTrashBatch(photos: List<Photo>) {
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val result = trashRepo.restoreBatch(photos, _restoreKeepOriginalDate.value)
            _message.value = if (result.count > 0) "已恢复 ${result.count} 张到原目录" else "恢复失败"
            if (result.count > 0) {
                if (result.unhideUris.isNotEmpty()) {
                    setHiddenPhotoUris(_hiddenPhotoUris.value - result.unhideUris.toSet())
                }
                refreshTrash()
                refresh()
            }
        }
    }

    /**
     * 回收站彻底删除：第一次删除时原图已被系统真正删掉，这里只清备份。
     * 兼容旧版软删除项（原图仍在 MediaStore）：先用系统确认弹窗删原图，再清备份。
     */
    fun deleteTrashForever(photos: List<Photo>) {
        if (photos.isEmpty()) return
        val softUris = trashRepo.softDeleteUris(photos)
        val sender = if (softUris.isNotEmpty()) repo.createSystemDeleteRequest(softUris) else null
        pendingForeverPhotos = photos
        pendingForeverMode = true
        if (sender != null) {
            _deleteRequest.value = SystemDeleteRequest(sender, ++deleteRequestId)
        } else {
            // 无软删除原图（或系统弹窗拉不起）：直接清备份
            finishTrashForever()
        }
    }

    private var pendingForeverPhotos: List<Photo> = emptyList()
    /** 当前系统删除弹窗是否服务于「回收站彻底删除旧软删项」，用于和真删回调分流 */
    private var pendingForeverMode = false

    private fun finishTrashForever() {
        val photos = pendingForeverPhotos
        pendingForeverPhotos = emptyList()
        pendingForeverMode = false
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val n = trashRepo.deleteForever(photos)
            _message.value = "已彻底删除 $n 张"
            refreshTrash()
        }
    }

    // ── 删除（第一次删除即真删：先备份，再走系统确认弹窗真删原图）──

    /**
     * 系统删除请求。带自增 id 而不是直接放 IntentSender —— 同一次删除可能连续触发，
     * 用 id 保证 UI 侧每次都能收到一个「新」事件去拉起弹窗。
     */
    data class SystemDeleteRequest(val sender: IntentSender, val id: Long)

    private val _deleteRequest = MutableStateFlow<SystemDeleteRequest?>(null)
    val deleteRequest: StateFlow<SystemDeleteRequest?> = _deleteRequest

    /** 从看图抽屉发起的删除成功后发一次，UI 据此退出看图页 */
    private val _viewerDeleteDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val viewerDeleteDone: SharedFlow<Unit> = _viewerDeleteDone

    /** 本次删除是否来自看图抽屉：成功后除了刷新，还要通知退出看图 */
    private var deleteFromViewer = false
    /** 本次待删的全部照片（拉不起系统弹窗时兜底直接删） */
    private var pendingAll: List<Photo> = emptyList()
    /** 本次删除已写进回收站的备份文件名（用户取消系统弹窗时据此回滚） */
    private var pendingTrashNames: List<String> = emptyList()
    /** 系统弹窗确认后仍需应用自己删的私有文件 */
    private var pendingPrivate: List<Photo> = emptyList()
    /** 本次待删总数（用于提示文案） */
    private var pendingCount: Int = 0

    private var deleteRequestId = 0L

    /**
     * 从看图抽屉删除当前这一张：复用系统确认弹窗（先备份进最近删除，再真删原图）。
     * 不走多选模式，直接把待删集合设为这一张。
     */
    fun deleteOneFromViewer(photo: Photo) {
        val targets = listOf(photo)
        pendingCount = targets.size
        pendingAll = targets
        deleteFromViewer = true
        startRealDelete(targets)
    }

    /**
     * 删除选中的照片：第一次删除就真删原图。
     *
     * MediaStore 里的照片不直接 delete，而是用 MediaStore.createDeleteRequest 生成
     * 系统确认弹窗（Android 11+ 官方推荐）：用户点「删除」后由系统完成删除，
     * 应用无需 WRITE/MANAGE_EXTERNAL_STORAGE，也不会被权限卡住。
     * 删除前先把字节备份进回收站（供恢复），用户取消则回滚备份、原图不动。
     * 应用私有目录导入的照片不进系统弹窗，确认后顺带删掉。
     */
    fun deleteSelected() {
        val uris = _selectedUris.value
        if (uris.isEmpty()) return
        val targets = _allPhotos.value.filter { it.uri.toString() in uris }
        if (targets.isEmpty()) {
            exitSelectionMode()
            return
        }
        pendingCount = targets.size
        pendingAll = targets
        startRealDelete(targets)
    }

    /** 备份 + 生成系统删除弹窗；共用给多选删除和看图抽屉删除。 */
    private fun startRealDelete(targets: List<Photo>) {
        pendingForeverMode = false
        viewModelScope.launch {
            // 删除前先把原图备份进回收站（字节 + 文件名/时间）；备份失败则整个删除作废，
            // 绝不出现「删了找不回」。真删项的备份 softDeleted=false，恢复时重新 insert。
            val backup = trashRepo.backup(targets)
            if (!backup.ok) {
                _deleteToast.value = "备份失败，已取消删除"
                pendingCount = 0
                pendingAll = emptyList()
                deleteFromViewer = false
                exitSelectionMode()
                return@launch
            }
            pendingTrashNames = backup.names
            val plan = repo.buildDeletePlan(targets)
            pendingPrivate = plan.privatePhotos
            val sender = repo.createSystemDeleteRequest(plan.mediaUris)
            if (sender != null) {
                _deleteRequest.value = SystemDeleteRequest(sender, ++deleteRequestId)
            } else {
                // 旧系统/无系统弹窗或全是私有图：直接真删（含 MediaStore 与私有文件）
                pendingPrivate = emptyList()
                doDelete(targets)
            }
        }
    }

    /** UI 拉起系统删除弹窗后的回调；confirmed = 用户在系统弹窗里点了删除。 */
    fun onDeleteRequestResult(confirmed: Boolean) {
        _deleteRequest.value = null
        // 回收站彻底删除旧软删项：系统已删原图，这里清备份，不走真删流程
        if (pendingForeverMode) {
            if (confirmed) finishTrashForever()
            else {
                pendingForeverPhotos = emptyList()
                pendingForeverMode = false
                _message.value = "已取消删除"
            }
            return
        }
        val priv = pendingPrivate
        val total = pendingCount
        pendingPrivate = emptyList()
        pendingAll = emptyList()
        val backupNames = pendingTrashNames
        pendingTrashNames = emptyList()
        pendingCount = 0
        if (!confirmed) {
            // 用户取消：回滚回收站备份，原图保持不动
            trashRepo.rollback(backupNames)
            _deleteToast.value = "已取消删除"
            deleteFromViewer = false
            return
        }
        viewModelScope.launch {
            // 系统已经真删掉 MediaStore 里的照片，这里只需清理私有原文件；
            // 备份留在回收站，随时可恢复。
            repo.deletePrivatePhotos(priv)
            _deleteToast.value = "已删除 $total 张图片"
            exitSelectionMode()
            refreshTrash()
            refresh()
            if (deleteFromViewer) {
                deleteFromViewer = false
                _viewerDeleteDone.emit(Unit)
            }
        }
    }

    /** 系统删除弹窗拉不起来时的兜底（机型阉割 / URI 被拒）：直接真删。 */
    fun onDeleteRequestUnavailable() {
        _deleteRequest.value = null
        // 彻底删除模式弹窗拉不起：没有系统删除能力，原图（旧软删项）删不掉，
        // 只清备份并提示，避免误走真删逻辑把回收站备份回滚掉。
        if (pendingForeverMode) {
            finishTrashForever()
            return
        }
        val all = pendingAll
        pendingPrivate = emptyList()
        pendingAll = emptyList()
        pendingCount = 0
        doDelete(all)
    }

    /** 无系统弹窗时的直接真删兜底；删失败则回滚备份。 */
    private fun doDelete(targets: List<Photo>) {
        viewModelScope.launch {
            if (targets.isEmpty()) {
                _deleteToast.value = "删除失败"
                trashRepo.rollback(pendingTrashNames)
                pendingTrashNames = emptyList()
                deleteFromViewer = false
                exitSelectionMode()
                return@launch
            }
            val ok = repo.deletePhotos(targets)
            if (ok > 0) {
                // 真删成功：备份留在回收站
                _deleteToast.value = "已删除 $ok 张图片"
            } else {
                // 一张都没删掉：回滚备份，提示失败
                trashRepo.rollback(pendingTrashNames)
                _deleteToast.value = "删除失败"
            }
            pendingTrashNames = emptyList()
            pendingCount = 0
            exitSelectionMode()
            refreshTrash()
            refresh()
            if (deleteFromViewer) {
                deleteFromViewer = false
                _viewerDeleteDone.emit(Unit)
            }
        }
    }

    /**
     * 表冠增量事件流。
     *
     * 注意：这里必须是**事件流（SharedFlow）**而不是可累加的 StateFlow。
     * 之前用 `_crownDelta.value += delta` 把增量累加成一个总位移，UI 再按
     * `scrollBy(-crownDelta * 6f)` 滚动相对量 —— 累加值只会越来越大，
     * 屏幕滚到底后无论怎么转都"没反应"（这正是 OPPO 模式表冠失效的根因）。
     * 改成每次事件独立投递，UI 收到多少就滚多少。
     */
    private val _crownEvents = MutableSharedFlow<Float>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val crownEvents: SharedFlow<Float> = _crownEvents

    /**
     * 表冠增量投递：每个事件原样投递，不做任何累积或离散化。
     * 下游 [CrownScroll] 照搬 f-x：收到即 scrollBy，即时跟手、无阻尼。
     */
    fun pushCrown(delta: Float) {
        if (delta == 0f || !delta.isFinite()) return
        _crownEvents.tryEmit(delta)
    }

    /** 表冠诊断：最近一次收到的原始运动事件，设置页实时显示，帮用户判断哪种模式有效 */
    private val _crownDebug = MutableStateFlow<String?>(null)
    val crownDebug: StateFlow<String?> = _crownDebug

    fun reportCrownDebug(info: String) { _crownDebug.value = info }

    /** 表冠模式（OPPO / 华为三星）与灵敏度，设置页读写 */
    private val _crownMode = MutableStateFlow<CrownMode>(CrownPrefs.load(app).first)
    val crownMode: StateFlow<CrownMode> = _crownMode

    private val _crownSpeed = MutableStateFlow(CrownPrefs.load(app).second)
    val crownSpeed: StateFlow<Float> = _crownSpeed

    /** 表冠缩放方向反转：false=现状（顺着转放大），true=方向相反。只影响缩放，不影响列表滚动。 */
    private val _zoomInvertCrown = MutableStateFlow(CrownPrefs.loadZoomInvert(app))
    val zoomInvertCrown: StateFlow<Boolean> = _zoomInvertCrown

    fun setCrownConfig(mode: CrownMode, speed: Float) {
        _crownMode.value = mode
        _crownSpeed.value = speed.coerceIn(CrownPrefs.SPEED_MIN, CrownPrefs.SPEED_MAX)
        CrownPrefs.save(getApplication(), mode, _crownSpeed.value)
    }

    fun setZoomInvert(invert: Boolean) {
        _zoomInvertCrown.value = invert
        CrownPrefs.saveZoomInvert(getApplication(), invert)
    }

    init {
        refresh()
    }

    /** 最近一次扫描的诊断信息（设置页「扫描诊断」展示） */
    private val _scanReport = MutableStateFlow(com.watchgallery.app.data.ScanReport())
    val scanReport: StateFlow<com.watchgallery.app.data.ScanReport> = _scanReport

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repo.scan()
            _allPhotos.value = result.photos
            _visibleCount.value = pageSize
            _albums.value = repo.toAlbums(result.photos)
            _scanReport.value = result.report
            _isLoading.value = false
            // 当前打开的文件夹如果已经不存在（照片被删光），退回文件夹列表
            val open = _openFolder.value
            if (open != null && result.photos.none { it.folderPath == open }) _openFolder.value = null
        }
    }

    /** 滑到列表底部时追加下一批，避免一次性渲染全部照片 */
    fun loadMore() {
        val total = totalCountFlow.value
        val current = _visibleCount.value
        if (current >= total) return
        _visibleCount.value = (current + pageSize).coerceAtMost(total)
    }

    /** 从系统文件选择器导入照片（复制进应用私有目录，之后一定读得到） */
    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            val ok = repo.importFromUris(uris)
            _message.value = if (ok > 0) "已导入 $ok 张照片" else "导入失败"
            val result = repo.scan()
            _allPhotos.value = result.photos
            _visibleCount.value = pageSize
            _albums.value = repo.toAlbums(result.photos)
            _scanReport.value = result.report
            _isLoading.value = false
            // 当前打开的文件夹如果已经不存在（照片被删光），退回文件夹列表
            val open = _openFolder.value
            if (open != null && result.photos.none { it.folderPath == open }) _openFolder.value = null
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme", when (mode) {
            ThemeMode.DARK -> "dark"
            ThemeMode.LIGHT -> "light"
            ThemeMode.SYSTEM -> "system"
        }).apply()
    }

    /** 循环切换主题：夜间 -> 白天 -> 跟随系统 -> 夜间 */
    fun cycleTheme() {
        val next = when (_themeMode.value) {
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
            ThemeMode.SYSTEM -> ThemeMode.DARK
        }
        setThemeMode(next)
    }

    /**
     * 对原图应用全部编辑并保存。
     * source 为已解码的原始 Bitmap（编辑页持有），adjustments/rotation/flip/strokes/crop 顺序执行。
     */
    fun saveEditedImage(
        source: Bitmap,
        adjustments: EditAdjustments,
        rotation: Int,
        flipH: Boolean,
        flipV: Boolean,
        cropRect: android.graphics.RectF?,
        strokes: List<Stroke>,
        texts: List<com.watchgallery.app.edit.TextStamp> = emptyList(),
        onDone: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                var bmp: Bitmap = source
                if (cropRect != null) bmp = ImageEditor.crop(bmp, cropRect)
                if (rotation != 0) bmp = ImageEditor.rotate(bmp, rotation.toFloat())
                if (flipH) bmp = ImageEditor.flip(bmp, true)
                if (flipV) bmp = ImageEditor.flip(bmp, false)
                bmp = ImageEditor.applyAdjustments(bmp, adjustments)
                if (strokes.isNotEmpty()) bmp = ImageEditor.drawStrokes(bmp, strokes)
                if (texts.isNotEmpty()) bmp = ImageEditor.drawTexts(bmp, texts)

                val name = "WatchGallery_${System.currentTimeMillis()}.png"
                val uri = ImageSaver.saveToGallery(getApplication(), bmp, name)
                _message.value = if (uri != null) "已保存到相册" else "保存失败"
                if (uri != null) {
                    // 新照片已经写进 MediaStore，重新扫描一次，
                    // 这样退回列表页时立刻能看到刚保存的这张
                    refresh()
                }
                onDone(uri != null)
            } catch (e: Exception) {
                _message.value = "保存失败：${e.message}"
                onDone(false)
            }
        }
    }

    fun clearMessage() { _message.value = null }

    /**
     * 重命名：成功 → 刷新列表；需要系统授权 → 把 IntentSender 交给界面去拉起。
     */
    fun renamePhoto(
        photo: Photo,
        newName: String,
        onResult: (ok: Boolean, needPermission: IntentSender?) -> Unit
    ) {
        viewModelScope.launch {
            when (val r = repo.rename(photo, newName)) {
                is com.watchgallery.app.data.RenameOutcome.Ok -> {
                    _message.value = "已重命名为 $newName"
                    refresh()
                    onResult(true, null)
                }
                is com.watchgallery.app.data.RenameOutcome.NeedPermission -> {
                    onResult(false, r.sender)
                }
                else -> {
                    _message.value = "重命名失败（系统不允许修改这张照片）"
                    onResult(false, null)
                }
            }
        }
    }


    /** 解码 Uri 为 Bitmap（按最大边采样，避免 OOM） */
    fun decodeUri(uri: Uri, maxSize: Int = 1600): Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
            val raw = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            ImageEditor.thumbnail(raw, maxSize)
        } catch (_: Exception) {
            null
        }
    }
}
