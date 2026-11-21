package com.watchgallery.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.watchgallery.app.ui.BlurBackdrop
import com.watchgallery.app.util.CrashLogger

/** 必须在任何 Activity / ViewModel 创建之前安装崩溃捕获 */
class WatchGalleryApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        CrashLogger.logStart(this)
        // 毛玻璃背景的 RenderScript 需要 applicationContext（后台线程复用实例）
        BlurBackdrop.appContext = this
    }

    /**
     * 全局图片加载器：注册动图解码器。
     *
     * Coil 默认只解第一帧（GIF 在相册里就是一张静态图），加上这两个之后
     * 大图页的 GIF 会自己动起来；列表里要不要动由请求上的 `decoderFactory` 决定
     * （设置里的「列表显示动图」）。
     *
     * - `ImageDecoderDecoder` 走系统 `AnimatedImageDrawable`（API 28+，目标机 Android 11）；
     * - `GifDecoder` 兜底老系统。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(ImageDecoderDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .crossfade(false)
            .build()
}
