package com.watchgallery.app

import android.app.Application
import com.watchgallery.app.util.CrashLogger

/** 必须在任何 Activity / ViewModel 创建之前安装崩溃捕获 */
class WatchGalleryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        CrashLogger.logStart(this)
    }
}
