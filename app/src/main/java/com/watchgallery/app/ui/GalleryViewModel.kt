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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("watch_gallery", Context.MODE_PRIVATE)

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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 表冠/编码器累计增量，由 Activity 写入，Compose 收集后驱动缩放/滚动 */
    private val _crownDelta = MutableStateFlow(0f)
    val crownDelta: StateFlow<Float> = _crownDelta

    fun pushCrown(delta: Float) { _crownDelta.value += delta }

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

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _photos.value = repo.loadPhotos()
            _albums.value = repo.loadAlbums()
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
