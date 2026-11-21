package com.watchgallery.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.PixelCopy
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.resume
import java.util.concurrent.Executors

/**
 * 模糊背景（毛玻璃）—— 与 f-x 同一套思路：**抓一张静态图，糊好，之后每次只画这张图**。
 *
 * 为什么不实时模糊：
 *  - 手表 GPU 弱，整屏 RenderEffect 每帧都要离屏渲染 + 卷积，滚动抽屉时必掉帧；
 *  - Android 11（API 30）及以下根本没有 `RenderEffect`，实时模糊做不出来；
 *  - 用户是单指操作，抽屉静止时背景本来就不该变，「静态刷新」不会穿帮。
 *
 * 刷新时机（与 f-x 一致）：
 *  1. 抽屉手指按下（还没开始移动）→ 抓一帧，此时抽屉还在屏幕外，拍到的是干净底层；
 *  2. 抽屉滑动松手后 100ms → 再抓一帧，把落位后的画面固化下来；
 *  3. 抓图 / 模糊期间不重复受理，来新请求只记一个 pending，做完再补一帧。
 *
 * 拍到的画面里难免带上界面浮层（保存按钮、把手），但一律会缩到 1/[DOWNSCALE] 再重度模糊，
 * 只剩大色块；而且抽屉面板本身会盖住底部那块，所以看不出残影。
 */
object BlurBackdrop {

    /** 截图的缩小倍数：越小越快、越糊。手表 400px 屏 → 133px 小图。 */
    private const val DOWNSCALE = 3

    /** 模糊半径（作用在缩小后的图上），上限 25（ScriptIntrinsicBlur 的硬限制）。 */
    private const val RADIUS = 22f

    /** 当前这张糊好的图；三处抽屉背景都读它。 */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 每次发布 +1，方便调用方做「新图入场」之类的动画。 */
    var generation by mutableIntStateOf(0)
        private set

    /** 有一次抓图 / 卷积正在做。 */
    var refreshing by mutableStateOf(false)
        private set

    /** 上一次失败原因（诊断用，不影响界面）。 */
    var lastError: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "backdrop-blur").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** 正在处理时又来请求：只记一个标记，做完补一帧。 */
    private var pending = false

    /**
     * 抽屉正盖在屏幕上。为真时 [capture] 一律拒绝。
     *
     * 这是「背景里不能出现抽屉自己」的硬保证：只要抽屉可见（哪怕只露出一条把手），
     * 拍下来的画面就带着它的按钮，糊完就是一块残影。所以刷新只允许发生在
     * 抽屉完全收起的时候，由各界面的手势/动画在合适的时刻开关这道门。
     */
    private var covered = false

    /** 排着队的延时刷新；关门时要能取消，否则照样会拍到抽屉。 */
    private var pendingRefresh: Runnable? = null

    private var rs: RenderScript? = null

    /** 最近一次抓图用的窗口：补帧时不用调用方再传。 */
    private var lastWindow: java.lang.ref.WeakReference<Window>? = null

    /**
     * 抓当前窗口的一帧并糊掉。
     *
     * 必须在抽屉**还没盖上来**的时候调用：PixelCopy 拍的是窗口已合成的那一帧，
     * 抽屉一旦入画就会被一起糊进背景（残影）。
     */
    fun capture(window: Window) {
        // 抽屉在画面上（或正在滑进来/滑出去）→ 这一帧一定不干净，直接丢掉
        if (covered) return
        if (refreshing) {
            pending = true
            return
        }
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return

        refreshing = true
        lastWindow = java.lang.ref.WeakReference(window)
        val bw = (w / DOWNSCALE).coerceAtLeast(1)
        val bh = (h / DOWNSCALE).coerceAtLeast(1)
        val bitmap = runCatching {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            refreshing = false
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                PixelCopy.request(
                    window,
                    Rect(0, 0, w, h),
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            blurAndPublish(bitmap)
                        } else {
                            finish(BitmapFailure("PixelCopy result=$result"))
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }.onFailure { finish(BitmapFailure(it.message ?: "PixelCopy 失败")) }
        } else {
            // API 24/25 没有 PixelCopy：直接让 DecorView 画到软件位图（在主线程，几十毫秒）
            val ok = runCatching {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
                decor.draw(canvas)
            }.isSuccess
            if (ok) blurAndPublish(bitmap) else finish(BitmapFailure("draw 失败"))
        }
    }

    /**
     * 开关「抽屉可见」这道门。关（true）时同时撤销排队的刷新。
     *
     * 打开抽屉、拖动中、抽屉停在任意一档 —— 都要关门；
     * 抽屉滑出屏幕之后才开（false），这时拍到的才是干净底层。
     */
    fun setCovered(value: Boolean) {
        covered = value
        if (value) {
            pendingRefresh?.let { main.removeCallbacks(it) }
            pendingRefresh = null
        }
    }

    /** 延时刷新。同一时刻只保留最后一个请求，避免连拖时排一长串抓图。 */
    fun refreshSoon(window: Window, delayMs: Long = 100L) {
        pendingRefresh?.let { main.removeCallbacks(it) }
        val ref = java.lang.ref.WeakReference(window)
        val task = Runnable {
            pendingRefresh = null
            ref.get()?.let { capture(it) }
        }
        pendingRefresh = task
        main.postDelayed(task, delayMs)
    }

    private fun blurAndPublish(source: Bitmap) {
        worker.execute {
            val out = runCatching { renderScriptBlur(source) }
                .getOrElse { runCatching { stackBlur(source, 10) }.getOrElse { source } }
            val img = runCatching { out.asImageBitmap() }.getOrNull()
            main.post {
                if (img != null) {
                    image = img
                    generation++
                    lastError = null
                }
                finish(null)
            }
        }
    }

    private fun finish(error: BitmapFailure?) {
        if (error != null) lastError = error.message
        refreshing = false
        if (pending) {
            pending = false
            val win = lastWindow?.get()
            if (win != null) main.postDelayed({ capture(win) }, 40)
        }
    }

    private class BitmapFailure(message: String) : Exception(message)

    /** RenderScript 高斯模糊：Android 11 及以下唯一稳定可用的硬件模糊通道。 */
    @Suppress("DEPRECATION")
    private fun renderScriptBlur(bitmap: Bitmap): Bitmap {
        val context = appContext ?: throw BitmapFailure("no context")
        val script = rs ?: RenderScript.create(context).also { rs = it }
        val input = Allocation.createFromBitmap(script, bitmap)
        val output = Allocation.createTyped(script, input.type)
        val blur = ScriptIntrinsicBlur.create(script, Element.U8_4(script))
        blur.setRadius(RADIUS.coerceIn(0.1f, 25f))
        blur.setInput(input)
        blur.forEach(output)
        output.copyTo(bitmap)
        input.destroy()
        output.destroy()
        blur.destroy()
        return bitmap
    }

    /**
     * 纯 Kotlin 的盒式模糊兜底：极少数设备没有可用的 RenderScript 驱动，
     * 或 RenderScript 初始化抛异常时用。小图上三次盒模糊已经足够柔。
     */
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = radius.coerceAtLeast(1)
        repeat(3) {
            boxBlurPass(pixels, w, h, r)
            boxBlurPass(pixels, w, h, r)
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        return src
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(pixels.size)
        // 横向
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            val base = y * w
            for (i in -radius..radius) {
                val x = i.coerceIn(0, w - 1)
                val c = pixels[base + x]
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            val div = radius * 2 + 1
            for (x in 0 until w) {
                tmp[base + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val outX = (x - radius).coerceIn(0, w - 1)
                val inX = (x + radius + 1).coerceIn(0, w - 1)
                val co = pixels[base + outX]
                val ci = pixels[base + inX]
                sumR += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sumG += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sumB += (ci and 0xFF) - (co and 0xFF)
            }
        }
        // 纵向
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -radius..radius) {
                val y = i.coerceIn(0, h - 1)
                val c = tmp[y * w + x]
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            val div = radius * 2 + 1
            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                val outY = (y - radius).coerceIn(0, h - 1)
                val inY = (y + radius + 1).coerceIn(0, h - 1)
                val co = tmp[outY * w + x]
                val ci = tmp[inY * w + x]
                sumR += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sumG += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sumB += (ci and 0xFF) - (co and 0xFF)
            }
        }
    }

    /**
     * 合成一张「与屏幕完全等价」的图再模糊：黑底 + 按图片**实际显示的矩形**把图放进去。
     *
     * 编辑页不能直接糊原图：原图会按自己的比例铺满，而上层图片是按「圆内最大矩形」
     * 居中显示的，两者大小/位置对不上，抽屉背景里的画面就像另外一张图。
     * 按屏幕坐标合成后，糊出来的背景与上面的图片严丝合缝，抽屉滑到哪儿都对得齐。
     *
     * @param dst 图片在屏幕上的矩形（px，整屏坐标系）
     */
    suspend fun blurScreenAsync(
        src: Bitmap,
        screenW: Int,
        screenH: Int,
        dst: android.graphics.RectF
    ): ImageBitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        worker.execute {
            val out = runCatching {
                val w = (screenW / DOWNSCALE).coerceAtLeast(16)
                val h = (screenH / DOWNSCALE).coerceAtLeast(16)
                val composed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(composed)
                canvas.drawColor(android.graphics.Color.BLACK)
                val k = w.toFloat() / screenW.coerceAtLeast(1).toFloat()
                canvas.drawBitmap(
                    src,
                    null,
                    android.graphics.RectF(dst.left * k, dst.top * k, dst.right * k, dst.bottom * k),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                )
                val blurred = runCatching { renderScriptBlur(composed) }
                    .getOrElse { runCatching { stackBlur(composed, 8) }.getOrElse { composed } }
                blurred.asImageBitmap()
            }.getOrNull()
            cont.resume(out)
        }
    }

    /** 由 App 启动时注入 applicationContext（避免每张图都问一遍）。 */
    var appContext: Context? = null
        internal set
}

/**
 * 抽屉背板 —— **固定铺在屏幕底层的静态模糊图**，不跟抽屉一起移动。
 *
 * 和 f-x 一样的分层：底层永远是那张糊好的整屏图，抽屉面板（透明玻璃）从下往上滑，
 * 滑到哪儿就把哪一块背景「揭开」。所以拖动时背景里的色块是钉死的，只有覆盖范围在变，
 * 观感是「玻璃板在图片上移动」，而不是「一张背景图跟着面板一起升起来」。
 *
 * 实现上不做布局高度抖动：整屏画一次，用 [clipPath] 只保留底部 [heightPx] 那一段，
 * 图本身按整屏尺寸、底边对齐，因此可见区域的像素坐标恒定不动。
 *
 * @param heightPx 抽屉当前露出的高度（px），0 表示完全不显示。
 *        用 lambda 而不是 Float：抽屉位移每帧都在变，如果在组合阶段读它，
 *        整棵子树（设置列表 / 工具行）都会跟着每帧重组；放进绘制阶段读，
 *        就只会让这块 Canvas 重绘。
 * @param image 用哪张糊好的图；默认全局那张（设置抽屉抓的相册）
 * @param cornerRadiusPx 顶部圆角，与抽屉面板保持一致，否则圆角外会漏出底图
 */
@Composable
fun BlurBand(
    heightPx: () -> Float,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = BlurBackdrop.image,
    dim: Float = 0.35f,
    cornerRadiusPx: Float = 0f,
    fallback: androidx.compose.ui.graphics.Color? = null
) {
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = dim)
    val fallbackColor = fallback ?: MaterialTheme.colorScheme.background

    Canvas(modifier.fillMaxSize()) {
        val h = heightPx().coerceAtLeast(0f)
        if (h < 1f) return@Canvas
        val top = size.height - h
        val clip = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = top,
                    right = size.width,
                    bottom = size.height,
                    topLeftCornerRadius = CornerRadius(cornerRadiusPx),
                    topRightCornerRadius = CornerRadius(cornerRadiusPx)
                )
            )
        }
        clipPath(clip) {
            if (image != null) {
                // 这张 Canvas 永远铺满整屏，直接把整屏图铺上去：
                // 像素坐标与屏幕一一对应，抽屉揭开到哪儿都严丝合缝。
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                )
            } else {
                drawRect(fallbackColor, topLeft = Offset(0f, top), size = Size(size.width, h))
            }
            // 压暗：让面板上的文字/图标有对比度
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(size.width, h))
        }
    }
}

/** 从 Context 链里找 Activity（LocalView.context 常被包了主题壳）。 */
fun Context.findHostActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
