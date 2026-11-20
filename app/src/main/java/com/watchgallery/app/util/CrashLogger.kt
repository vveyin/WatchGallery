package com.watchgallery.app.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.watchgallery.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志捕获。
 *
 * 设计要点：
 * 1. 在 Application.onCreate 最早安装全局 UncaughtExceptionHandler，
 *    覆盖主线程与子线程；Compose 组合异常最终也会冒泡到这里。
 * 2. 崩溃时把堆栈、线程、设备、版本同步写进内部文件（append），
 *    调用方在崩溃后才有可能把它复制出去。
 * 3. 写一个 crash.pending 标记，下次启动据此先展示「崩溃报告页」，
 *    否则应用一打开就崩，用户根本进不了设置页去复制日志。
 * 4. 同时记录启动/关键阶段 breadcrumb，便于定位崩在哪一步。
 */
object CrashLogger {
    private const val LOG_FILE = "watchgallery.log"
    private const val PENDING_FILE = "crash.pending"
    private const val MAX_BYTES = 512 * 1024
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sb = StringBuilder()
                sb.appendLine("=== CRASH ${stamp()} ===")
                sb.appendLine("thread=${thread.name} prio=${thread.priority}")
                sb.appendLine("device=${Build.BRAND}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
                sb.appendLine("app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                sb.appendLine("stack:")
                sb.appendLine(Log.getStackTraceString(throwable))
                var cause = throwable.cause
                while (cause != null) {
                    sb.appendLine("caused by:")
                    sb.appendLine(Log.getStackTraceString(cause))
                    cause = cause.cause
                }
                sb.appendLine()
                append(app, sb.toString())
                runCatching { File(app.filesDir, PENDING_FILE).writeText(stamp()) }
            } catch (_: Throwable) {
            }
            // 交回系统默认处理，保持"应用已停止"的正常行为
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
        }
    }

    /** 记录一条带时间戳的 breadcrumb */
    fun log(context: Context, message: String) {
        append(context, "${stamp()}  $message\n")
    }

    /** 每次启动写一条分隔，方便看这次崩在哪 */
    fun logStart(context: Context) {
        append(
            context,
            "\n===== START ${stamp()}  v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})  sdk=${Build.VERSION.SDK_INT} =====\n"
        )
    }

    fun read(context: Context): String = try {
        val f = File(context.filesDir, LOG_FILE)
        if (f.exists() && f.length() > 0) f.readText() else "（暂无日志）"
    } catch (e: Exception) {
        "（读取日志失败：${e.message}）"
    }

    fun hasPending(context: Context): Boolean = File(context.filesDir, PENDING_FILE).exists()

    /** 读取并清除"上次崩溃"标记；返回是否确实发生过崩溃 */
    fun consumePending(context: Context): Boolean {
        val f = File(context.filesDir, PENDING_FILE)
        if (!f.exists()) return false
        return try {
            f.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, LOG_FILE).writeText("") }
        consumePending(context)
    }

    private fun append(context: Context, text: String) {
        try {
            val f = File(context.filesDir, LOG_FILE)
            f.appendText(text)
            if (f.length() > MAX_BYTES) {
                val kept = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(kept)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stamp(): String = timeFmt.format(Date())
}
