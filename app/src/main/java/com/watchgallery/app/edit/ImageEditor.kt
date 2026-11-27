package com.watchgallery.app.edit

import android.graphics.*
import kotlin.math.*

/**
 * 图片编辑引擎 —— 对标手机相册常用编辑操作，纯 Bitmap 处理。
 * 参考 Fossify Gallery / ImageToolbox 的功能集合，但针对手表精简为最重要的几类。
 */

/** 滤镜预设（用 ColorMatrix 实现） */
enum class FilterPreset(val label: String) {
    ORIGINAL("原图"),
    GRAYSCALE("黑白"),
    SEPIA("怀旧"),
    VIVID("鲜艳"),
    COOL("冷色"),
    WARM("暖色"),
    INVERT("反色")
}

data class EditAdjustments(
    val brightness: Float = 0f,     // -100 .. 100
    val contrast: Float = 0f,       // -100 .. 100
    val saturation: Float = 0f,     // -100 .. 100
    val warmth: Float = 0f,         // -100 .. 100
    val filter: FilterPreset = FilterPreset.ORIGINAL
)

/** 涂鸦笔画 */
data class Stroke(
    val points: List<PointF>,
    val color: Int,
    val width: Float
)

/**
 * 文本框（保存时烘焙进图片）。
 *
 * 坐标与字号都是**原图像素**，和编辑页预览共用同一套换算，
 * 所以预览里框在哪儿，保存出来就在哪儿。
 */
data class TextStamp(
    val x: Float,
    val y: Float,
    val text: String,
    val sizePx: Float,
    val color: Int,
    /** 文本框的宽度（原图像素）。null = 旧格式：按不换行单行绘制 */
    val widthPx: Float? = null,
    /** 文本框底色（ARGB）。0 = 无底色（旧格式兼容），如 0xFFFFFFFF 白、0xFFD6D6D6 淡灰 */
    val bgColor: Int = 0
)

object ImageEditor {

    /** 应用色彩调整 + 滤镜，返回新 Bitmap */
    fun applyAdjustments(source: Bitmap, adj: EditAdjustments): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val matrix = ColorMatrix()
        // 基础滤镜
        when (adj.filter) {
            FilterPreset.GRAYSCALE -> matrix.setSaturation(0f)
            FilterPreset.SEPIA -> {
                matrix.set(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            FilterPreset.VIVID -> {
                matrix.setSaturation(1.6f)
                matrix.postConcat(ColorMatrix(floatArrayOf(
                    1.1f, 0f, 0f, 0f, 0f,
                    0f, 1.1f, 0f, 0f, 0f,
                    0f, 0f, 1.1f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            FilterPreset.COOL -> matrix.set(floatArrayOf(
                0.95f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.15f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.WARM -> matrix.set(floatArrayOf(
                1.15f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.INVERT -> matrix.set(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            FilterPreset.ORIGINAL -> matrix.reset()
        }

        // 用户调节：饱和度叠加
        if (adj.saturation != 0f) {
            val sat = 1f + adj.saturation / 100f
            val satMatrix = ColorMatrix().apply { setSaturation(sat.coerceIn(0f, 3f)) }
            matrix.postConcat(satMatrix)
        }
        // 亮度（-100..100 -> -255..255）
        val bright = adj.brightness / 100f * 255f
        // 对比度
        val contrastScale = 1f + adj.contrast / 100f
        val c = contrastScale.coerceIn(0f, 3f)
        val offset = (128f * (1f - c)) + bright
        val adjustMatrix = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(adjustMatrix)
        // 色温
        if (adj.warmth != 0f) {
            val w = adj.warmth / 100f * 40f
            val warmMatrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, w,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, -w,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(warmMatrix)
        }

        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /** 旋转（degrees 为 90 的倍数），返回新 Bitmap */
    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees, source.width / 2f, source.height / 2f)
        }
        // 90 度倍数时交换宽高
        val swap = (abs(degrees) % 180) == 90f
        val w = if (swap) source.height else source.width
        val h = if (swap) source.width else source.height
        val result = Bitmap.createBitmap(w, h, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val offsetX = (w - source.width) / 2f
        val offsetY = (h - source.height) / 2f
        matrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
        return result
    }

    /** 翻转 */
    fun flip(source: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, source.width / 2f, source.height / 2f)
            else postScale(1f, -1f, source.width / 2f, source.height / 2f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** 按比例裁剪，rect 为源图坐标系 */
    fun crop(source: Bitmap, rect: RectF): Bitmap {
        val clipped = Rect(
            rect.left.toInt().coerceIn(0, source.width),
            rect.top.toInt().coerceIn(0, source.height),
            rect.right.toInt().coerceIn(0, source.width),
            rect.bottom.toInt().coerceIn(0, source.height)
        )
        if (clipped.width() <= 0 || clipped.height() <= 0) return source
        return Bitmap.createBitmap(source, clipped.left, clipped.top, clipped.width(), clipped.height())
    }

    /** 在 Bitmap 上绘制涂鸦笔画 */
    fun drawStrokes(source: Bitmap, strokes: List<Stroke>): Bitmap {
        val result = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            val path = Path()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
            canvas.drawPath(path, paint)
        }
        return result
    }

    /** 在 Bitmap 上绘制文本框（与编辑页预览同一套坐标：左上角 + 字号 + 框宽，单位都是原图像素） */
    fun drawTexts(source: Bitmap, texts: List<TextStamp>): Bitmap {
        if (texts.isEmpty()) return source
        val result = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        // 文本框可以移到图片/屏幕外自由编辑；超出图片边界的内容一律不烘焙进成品。
        // clipRect 同时裁掉底色圆角矩形和文字笔画（包括负坐标、半截出界的字）。
        canvas.clipRect(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        for (t in texts) {
            if (t.text.isBlank()) continue
            paint.textSize = t.sizePx
            paint.color = t.color
            val lineH = t.sizePx * 1.25f
            // widthPx 有值：按框宽自动折行（和预览一致）；旧格式：不折行，只认手动 \n
            val lines = if (t.widthPx != null && t.widthPx > 0f) {
                val out = mutableListOf<String>()
                for (para in t.text.split('\n')) out += wrapLine(para, paint, t.widthPx)
                out
            } else {
                t.text.split('\n')
            }
            // 底色：先铺在文字下面，高度随折行后的内容自适应（行数 × 行高），与预览一致
            if (t.bgColor != 0) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = t.bgColor
                }
                val h = lines.size * lineH
                val r = t.sizePx * 0.12f
                canvas.drawRoundRect(
                    t.x, t.y, t.x + (t.widthPx ?: paint.measureText(t.text)), t.y + h, r, r, bgPaint
                )
            }
            lines.forEachIndexed { i, line ->
                // y 是文本框左上角，绘制时换算到基线；超出框高的行继续往下顺延
                canvas.drawText(line, t.x, t.y + (-paint.ascent()) + i * lineH, paint)
            }
        }
        return result
    }

    /** 按最大宽度把一段文字折成多行（中英文混排都按像素宽度断行） */
    private fun wrapLine(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val n = paint.breakText(text, start, text.length, true, maxWidth, null).coerceAtLeast(1)
            out += text.substring(start, start + n)
            start += n
        }
        return out
    }

    /** 生成缩略图，避免大图占用过多内存 */
    fun thumbnail(source: Bitmap, maxSize: Int): Bitmap {
        val ratio = min(maxSize.toFloat() / source.width, maxSize.toFloat() / source.height)
        if (ratio >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
