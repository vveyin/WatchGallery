package com.watchgallery.app

import android.app.Application
import com.watchgallery.app.ui.BlurBackdrop
import com.watchgallery.app.util.CrashLogger
import com.watchgallery.app.util.MainThreadWatchdog

/** 必须在任何 Activity / ViewModel 创建之前安装崩溃捕获 */
class WatchGalleryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        CrashLogger.logStart(this)
        // ANR 不走 UncaughtExceptionHandler，单独用看门狗抓主线程堆栈
        MainThreadWatchdog.install(this)
        // 毛玻璃背景的 RenderScript 需要 applicationContext（后台线程复用实例）
        BlurBackdrop.appContext = this
    }
}
