package com.watchgallery.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchgallery.app.data.Album
import com.watchgallery.app.data.CrownMode
import com.watchgallery.app.data.CrownPrefs
import com.watchgallery.app.data.MediaRepository
import com.watchgallery.app.data.Photo
import com.watchgallery.app.edit.EditAdjustments
import com.watchgallery.app.edit.ImageEditor
import com.watchgallery.app.edit.Stroke
import com.watchgallery.app.theme.ThemeMode
import com.watchgallery.app.util.ImageSaver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("watch_gallery", Context.MODE_PRIVATE)

    /** 完整扫描结果（供大图翻页用），不直接给列表渲染 */
    private val allPhotos = MutableStateFlow<List<Photo>>(emptyList())

    /** 每页条数 */
    private val pageSize = 30

    /** 当前列表已显示的条数：先显示前 30 张，滑到底再追加 */
    private val _visibleCount = MutableStateFlow(pageSize)
    val visibleCount: StateFlow<Int> = _visibleCount

    /** 列表可见的照片（分页切片） */
    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos

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

    /** 列表每排显示张数（2 或 3），默认 2；存 SharedPreferences 持久化 */
    private val _gridColumns = MutableStateFlow(
        prefs.getInt("grid_columns", 2).coerceIn(2, 3)
    )
    val gridColumns: StateFlow<Int> = _gridColumns

    fun setGridColumns(columns: Int) {
        val c = columns.coerceIn(2, 3)
        _gridColumns.value = c
        prefs.edit().putInt("grid_columns", c).apply()
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

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
        _selectedUris.value = _selectedUris.value + uris
    }

    /** 删除选中的照片，成功后退出多选并刷新 */
    fun deleteSelected(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val uris = _selectedUris.value
            if (uris.isEmpty()) { onDone(0); return@launch }
            val toDelete = allPhotos.value.filter { it.uri.toString() in uris }
            val ok = repo.deletePhotos(toDelete)
            _message.value = if (ok > 0) "已删除 $ok 张照片" else "删除失败（系统照片可能无删除权限）"
            exitSelectionMode()
            refresh()
            onDone(ok)
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
     * 表冠增量投递：保留原始浮点增量，不做"攒够 1 格"的离散化。
     *
     * 之前这里把小数攒够 1 格才发、发完连余数一起清零，等于把表冠增量量化成整格，
     * 再被下游瞬间 scrollBy 一大段 —— 这正是"一格一格"顿挫的来源。
     * 现在下游 [SmoothCrownScroll] 会自行对所有浮点增量求和并用阻尼逐帧逼近，
     * 因此这里必须每个增量都原样投递（包括 0.2~0.8 的小数），距离才守恒、才连贯。
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

    fun setCrownConfig(mode: CrownMode, speed: Float) {
        _crownMode.value = mode
        _crownSpeed.value = speed.coerceIn(CrownPrefs.SPEED_MIN, CrownPrefs.SPEED_MAX)
        CrownPrefs.save(getApplication(), mode, _crownSpeed.value)
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
            allPhotos.value = result.photos
            _visibleCount.value = pageSize
            _photos.value = result.photos.take(pageSize)
            _albums.value = repo.toAlbums(result.photos)
            _scanReport.value = result.report
            _isLoading.value = false
        }
    }

    /** 滑到列表底部时追加下一批，避免一次性渲染全部照片 */
    fun loadMore() {
        val total = allPhotos.value.size
        val current = _visibleCount.value
        if (current >= total) return
        val next = (current + pageSize).coerceAtMost(total)
        _visibleCount.value = next
        _photos.value = allPhotos.value.take(next)
    }

    /** 从系统文件选择器导入照片（复制进应用私有目录，之后一定读得到） */
    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            val ok = repo.importFromUris(uris)
            _message.value = if (ok > 0) "已导入 $ok 张照片" else "导入失败"
            val result = repo.scan()
            allPhotos.value = result.photos
            _visibleCount.value = pageSize
            _photos.value = result.photos.take(pageSize)
            _albums.value = repo.toAlbums(result.photos)
            _scanReport.value = result.report
            _isLoading.value = false
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

                val name = "WatchGallery_${System.currentTimeMillis()}.png"
                val uri = ImageSaver.saveToGallery(getApplication(), bmp, name)
                _message.value = if (uri != null) "已保存到相册" else "保存失败"
                onDone(uri != null)
            } catch (e: Exception) {
                _message.value = "保存失败：${e.message}"
                onDone(false)
            }
        }
    }

    /** 大图翻页用的完整列表（不受分页显示条数影响） */
    val allPhotosFlow: StateFlow<List<Photo>> = allPhotos

    /** 照片总数（列表判断能否加载更多） */
    val totalCountFlow: StateFlow<Int> = MutableStateFlow(0).also { total ->
        viewModelScope.launch {
            allPhotos.collect { total.value = it.size }
        }
    }

    fun clearMessage() { _message.value = null }

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
